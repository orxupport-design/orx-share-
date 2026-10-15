package com.orx.share

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles discovery (Bluetooth/BLE) and fast transfer (Wi-Fi Direct / Wi-Fi hotspot)
 * through Google's Nearby Connections API. Nearby picks the fastest medium automatically
 * and encrypts the connection. Both users must confirm the same pairing code.
 */
class NearbyManager(context: Context) {

    enum class Phase { IDLE, ADVERTISING, DISCOVERING, CONNECTING, CONNECTED }
    data class Peer(val id: String, val name: String)
    data class Pending(val id: String, val name: String, val digits: String)

    /** One thing to send: a Uri and (optionally) the name the receiver should save it under. */
    data class SendItem(val uri: Uri, val name: String? = null)

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val peers: List<Peer> = emptyList(),
        val pending: Pending? = null,
        val connectedName: String? = null,
        val progress: Int = 0,
        val transferring: Boolean = false,
        val sending: Boolean = false,
        val done: Boolean = false,
        val failed: Boolean = false,
        val savedDir: String? = null,
        val error: String? = null,
        /** Increases every time a received file has been fully saved, so the UI can refresh its lists. */
        val receivedTick: Int = 0,
    )

    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val serviceId = "com.orx.share.v1"
    private val deviceName = Build.MODEL ?: "Android"
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var connectedId: String? = null

    // These are touched from Nearby callbacks (main thread) and from the saving coroutines (IO threads).
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val incomingNames = ConcurrentHashMap<Long, String>()
    private val incomingFiles = ConcurrentHashMap<Long, Payload>()
    private val sendingIds = ConcurrentHashMap.newKeySet<Long>()
    private val progressMap = ConcurrentHashMap<Long, Pair<Long, Long>>() // id -> (transferred, total)
    private val pendingSaves = AtomicInteger(0)

    // ---------- Start / stop ----------

    fun startReceiving() {
        reset()
        _state.update { it.copy(phase = Phase.ADVERTISING) }
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(deviceName, serviceId, connectionCallback, options)
            .addOnFailureListener { fail(it) }
    }

    fun startSending() {
        reset()
        _state.update { it.copy(phase = Phase.DISCOVERING) }
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnFailureListener { fail(it) }
    }

    fun connectTo(peer: Peer) {
        _state.update { it.copy(phase = Phase.CONNECTING) }
        client.requestConnection(deviceName, peer.id, connectionCallback)
            .addOnFailureListener { fail(it) }
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        reset()
    }

    private fun reset() {
        connectedId = null
        endpointNames.clear()
        incomingNames.clear()
        incomingFiles.clear()
        sendingIds.clear()
        progressMap.clear()
        pendingSaves.set(0)
        _state.value = UiState()
    }

    private fun fail(e: Exception? = null) {
        val detail = (e as? ApiException)
            ?.let { "${it.statusCode}: ${ConnectionsStatusCodes.getStatusCodeString(it.statusCode)}" }
            ?: e?.message
        _state.update { it.copy(phase = Phase.IDLE, failed = true, transferring = false, error = detail) }
    }

    // ---------- Pairing confirmation (protects against connecting to the wrong phone) ----------

    fun acceptPending() {
        val p = _state.value.pending ?: return
        client.acceptConnection(p.id, payloadCallback)
        _state.update { it.copy(pending = null) }
    }

    fun rejectPending() {
        val p = _state.value.pending ?: return
        client.rejectConnection(p.id)
        _state.update { it.copy(pending = null, phase = Phase.IDLE) }
    }

    // ---------- Sending ----------

    fun sendFiles(items: List<SendItem>) {
        val target = connectedId ?: return
        progressMap.clear()
        _state.update {
            it.copy(transferring = true, sending = true, done = false, failed = false, error = null, progress = 0)
        }
        var sent = 0
        for (item in items) {
            val pfd = runCatching { appContext.contentResolver.openFileDescriptor(item.uri, "r") }
                .getOrNull() ?: continue
            val payload = Payload.fromFile(pfd)
            sendingIds.add(payload.id)
            val name = item.name ?: queryName(item.uri) ?: "file_${payload.id}"
            // Send the file name first, then the file itself.
            client.sendPayload(target, Payload.fromBytes("FILE:${payload.id}:$name".toByteArray()))
            client.sendPayload(target, payload)
            sent++
        }
        if (sent == 0) {
            _state.update {
                it.copy(transferring = false, failed = true, error = "Could not read the selected items")
            }
        }
    }

    private fun queryName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull()

    // ---------- Callbacks ----------

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            _state.update { s ->
                if (s.peers.any { it.id == id }) s
                else s.copy(peers = s.peers + Peer(id, info.endpointName))
            }
        }

        override fun onEndpointLost(id: String) {
            _state.update { s -> s.copy(peers = s.peers.filterNot { it.id == id }) }
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            endpointNames[id] = info.endpointName
            _state.update {
                it.copy(
                    phase = Phase.CONNECTING,
                    pending = Pending(id, info.endpointName, info.authenticationDigits),
                )
            }
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedId = id
                client.stopAdvertising()
                client.stopDiscovery()
                _state.update {
                    it.copy(
                        phase = Phase.CONNECTED,
                        connectedName = endpointNames[id],
                        pending = null,
                    )
                }
            } else {
                val code = result.status.statusCode
                _state.update {
                    it.copy(
                        phase = Phase.IDLE, pending = null, failed = true,
                        error = "$code: ${ConnectionsStatusCodes.getStatusCodeString(code)}",
                    )
                }
            }
        }

        override fun onDisconnected(id: String) {
            connectedId = null
            _state.update { it.copy(phase = Phase.IDLE, transferring = false) }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val text = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    if (text.startsWith("FILE:")) {
                        val parts = text.split(":", limit = 3)
                        if (parts.size == 3) parts[1].toLongOrNull()?.let { incomingNames[it] = parts[2] }
                    }
                }
                Payload.Type.FILE -> {
                    incomingFiles[payload.id] = payload
                    _state.update {
                        it.copy(transferring = true, sending = false, done = false, failed = false, error = null)
                    }
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {
            val isFile = sendingIds.contains(update.payloadId) || incomingFiles.containsKey(update.payloadId)
            if (!isFile) return
            progressMap[update.payloadId] = update.bytesTransferred to update.totalBytes
            val total = progressMap.values.sumOf { it.second }.coerceAtLeast(1)
            val done = progressMap.values.sumOf { it.first }
            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    _state.update { it.copy(progress = pct) }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    val received = incomingFiles.remove(update.payloadId)
                    sendingIds.remove(update.payloadId)
                    if (received != null) {
                        pendingSaves.incrementAndGet()
                        saveReceived(received)
                    }
                    maybeFinish()
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED ->
                    _state.update { it.copy(transferring = false, failed = true) }

                else -> Unit
            }
        }
    }

    /** The transfer is "done" only when nothing is in flight AND every received file is saved to disk. */
    private fun maybeFinish() {
        if (sendingIds.isEmpty() && incomingFiles.isEmpty() && pendingSaves.get() == 0) {
            _state.update { it.copy(progress = 100, transferring = false, done = !it.failed) }
        }
    }

    // ---------- Saving received files ----------

    private fun saveReceived(payload: Payload) {
        val pfd = payload.asFile()?.asParcelFileDescriptor()
        if (pfd == null) {
            pendingSaves.decrementAndGet()
            return
        }
        io.launch {
            try {
                // The name message is tiny and sent first, but Nearby does not promise the order:
                // give it a few seconds to arrive before falling back to a generic name.
                var rawName = incomingNames.remove(payload.id)
                var waited = 0
                while (rawName == null && waited < 3000) {
                    delay(100)
                    waited += 100
                    rawName = incomingNames.remove(payload.id)
                }
                // Security: strip any path components so a sender can't write outside our folders.
                val safe = File(rawName ?: "orx_${payload.id}").name.ifBlank { "orx_${payload.id}" }
                val isApp = ReceivedApps.isAppFileName(safe)
                val dir = if (isApp) ReceivedApps.dir(appContext) else ReceiveDirs.root(appContext)

                var target = File(dir, safe)
                if (isApp) {
                    target.delete()
                } else {
                    val base = target.nameWithoutExtension
                    val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
                    var n = 1
                    while (target.exists()) {
                        target = File(dir, "${base}_$n$ext")
                        n++
                    }
                }

                // Write to a temporary name first, so half-written files never show up in the app list.
                val tmp = File(dir, "${target.name}.part")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw IOException("Could not save the file")
                }
                _state.update {
                    it.copy(
                        savedDir = ReceiveDirs.root(appContext).absolutePath,
                        receivedTick = it.receivedTick + 1,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(failed = true, error = e.message) }
            } finally {
                pendingSaves.decrementAndGet()
                maybeFinish()
            }
        }
    }
}
