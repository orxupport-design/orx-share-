package com.orx.share

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

/**
 * Handles discovery (Bluetooth/BLE) and fast transfer (Wi-Fi Direct / Wi-Fi hotspot)
 * through Google's Nearby Connections API. Nearby picks the fastest medium automatically
 * and encrypts the connection. Both users must confirm the same pairing code.
 */
class NearbyManager(context: Context) {

    enum class Phase { IDLE, ADVERTISING, DISCOVERING, CONNECTING, CONNECTED }
    data class Peer(val id: String, val name: String)
    data class Pending(val id: String, val name: String, val digits: String)
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
    private val incomingNames = HashMap<Long, String>()
    private val incomingFiles = HashMap<Long, Payload>()
    private val sendingIds = HashSet<Long>()
    private val progressMap = HashMap<Long, Pair<Long, Long>>() // id -> (transferred, total)

    private val receiveDir: File
        get() = (appContext.getExternalFilesDir("Received") ?: File(appContext.filesDir, "Received"))
            .also { it.mkdirs() }

    // ---------- Start / stop ----------

    fun startReceiving() {
        reset()
        _state.update { it.copy(phase = Phase.ADVERTISING) }
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        client.startAdvertising(deviceName, serviceId, connectionCallback, options)
            .addOnFailureListener { fail() }
    }

    fun startSending() {
        reset()
        _state.update { it.copy(phase = Phase.DISCOVERING) }
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnFailureListener { fail() }
    }

    fun connectTo(peer: Peer) {
        _state.update { it.copy(phase = Phase.CONNECTING) }
        client.requestConnection(deviceName, peer.id, connectionCallback)
            .addOnFailureListener { fail() }
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        reset()
    }

    private fun reset() {
        connectedId = null
        incomingNames.clear()
        incomingFiles.clear()
        sendingIds.clear()
        progressMap.clear()
        _state.value = UiState()
    }

    private fun fail() {
        _state.update { it.copy(phase = Phase.IDLE, failed = true, transferring = false) }
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

    fun sendFiles(uris: List<Uri>) {
        val target = connectedId ?: return
        _state.update { it.copy(transferring = true, sending = true, done = false, failed = false, progress = 0) }
        for (uri in uris) {
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r") ?: continue
            val payload = Payload.fromFile(pfd)
            sendingIds.add(payload.id)
            val name = queryName(uri) ?: "file_${payload.id}"
            // Send the file name first, then the file itself.
            client.sendPayload(target, Payload.fromBytes("FILE:${payload.id}:$name".toByteArray()))
            client.sendPayload(target, payload)
        }
    }

    private fun queryName(uri: Uri): String? =
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }

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
                        connectedName = it.pending?.name ?: it.peers.firstOrNull { p -> p.id == id }?.name,
                        pending = null,
                    )
                }
            } else {
                _state.update { it.copy(phase = Phase.IDLE, pending = null, failed = true) }
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
                    _state.update { it.copy(transferring = true, sending = false, done = false, failed = false) }
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
                    incomingFiles.remove(update.payloadId)?.let { saveReceived(it) }
                    sendingIds.remove(update.payloadId)
                    if (sendingIds.isEmpty() && incomingFiles.isEmpty()) {
                        _state.update { it.copy(progress = 100, transferring = false, done = true) }
                    }
                }

                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED ->
                    _state.update { it.copy(transferring = false, failed = true) }

                else -> Unit
            }
        }
    }

    // ---------- Saving received files ----------

    private fun saveReceived(payload: Payload) {
        val pfd = payload.asFile()?.asParcelFileDescriptor() ?: return
        val rawName = incomingNames.remove(payload.id) ?: "orx_${payload.id}"
        io.launch {
            try {
                // Security: strip any path components so a sender can't write outside our folder.
                val safe = File(rawName).name.ifBlank { "orx_${payload.id}" }
                var target = File(receiveDir, safe)
                val base = target.nameWithoutExtension
                val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
                var n = 1
                while (target.exists()) {
                    target = File(receiveDir, "${base}_$n$ext")
                    n++
                }
                FileInputStream(pfd.fileDescriptor).use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                pfd.close()
                _state.update { it.copy(savedDir = receiveDir.absolutePath) }
            } catch (e: Exception) {
                _state.update { it.copy(failed = true) }
            }
        }
    }
}
