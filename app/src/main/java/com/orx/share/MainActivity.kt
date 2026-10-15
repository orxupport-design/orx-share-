package com.orx.share

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orx.share.ui.HomeScreen
import com.orx.share.ui.OrxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var nearby: NearbyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearby = NearbyManager(applicationContext)

        setContent {
            OrxTheme {
                val state by nearby.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()

                var tab by remember { mutableIntStateOf(0) } // 0 = files, 1 = apps
                var pickedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
                var pickedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
                var pendingOutbox by remember { mutableStateOf<List<NearbyManager.SendItem>>(emptyList()) }
                var storage by remember { mutableStateOf(StorageInfo.read()) }
                var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

                var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
                var appsLoading by remember { mutableStateOf(true) }
                var vtKey by remember { mutableStateOf(Prefs.vtKey(applicationContext)) }
                var deleteTick by remember { mutableIntStateOf(0) }
                val receivedApps = remember(state.receivedTick, deleteTick) {
                    ReceivedApps.list(applicationContext)
                }

                LaunchedEffect(Unit) {
                    apps = withContext(Dispatchers.IO) { AppListing.load(applicationContext) }
                    appsLoading = false
                }

                val pickFiles = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
                ) { uris -> pickedUris = uris }

                // Lets the user check an APK that is already on this phone (downloaded, or from a chat) before installing.
                val pickApk = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { ReceivedApps.importApk(applicationContext, uri) }
                            if (ok) deleteTick += 1
                            Toast.makeText(
                                this@MainActivity,
                                if (ok) R.string.apk_imported else R.string.apk_import_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    // On Android 12+ "approximate" location is enough, so a denied FINE location is fine there.
                    // On Android 10-11 Nearby still needs FINE location to discover phones.
                    val ok = result
                        .filterKeys {
                            !(it == Manifest.permission.ACCESS_FINE_LOCATION &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        }
                        .values.all { it }
                    if (ok) {
                        afterPermission?.invoke()
                    } else {
                        Toast.makeText(this@MainActivity, R.string.permissions_needed, Toast.LENGTH_LONG).show()
                    }
                    afterPermission = null
                }

                fun withPermissions(action: () -> Unit) {
                    afterPermission = action
                    permissionLauncher.launch(requiredPermissions())
                }

                /** What the user chose to send on the current tab. */
                fun buildOutbox(): List<NearbyManager.SendItem> =
                    if (tab == 0) {
                        pickedUris.map { NearbyManager.SendItem(it) }
                    } else {
                        // "<package>@<time>__<file>.apk": the receiver groups the base + split APKs by this prefix.
                        val stamp = System.currentTimeMillis() / 1000
                        apps.filter { it.packageName in pickedApps }.flatMap { app ->
                            app.apkPaths.map { path ->
                                NearbyManager.SendItem(
                                    uri = Uri.fromFile(File(path)),
                                    name = "${app.packageName}@${stamp}__${File(path).name}",
                                )
                            }
                        }
                    }

                fun installApp(group: ApkGroup) {
                    if (!AppInstaller.canInstall(this@MainActivity)) {
                        // First time only: the user must allow "install unknown apps" for orx share.
                        Toast.makeText(this@MainActivity, R.string.allow_install_then_retry, Toast.LENGTH_LONG).show()
                        startActivity(AppInstaller.unknownSourcesIntent(this@MainActivity))
                        return
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            AppInstaller.install(applicationContext, group.files)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.install_failed, e.message ?: ""),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }

                // Once connected as a sender, push the chosen items automatically.
                LaunchedEffect(state.phase) {
                    storage = StorageInfo.read()
                    if (state.phase == NearbyManager.Phase.CONNECTED &&
                        pendingOutbox.isNotEmpty() && !state.transferring && !state.done
                    ) {
                        nearby.sendFiles(pendingOutbox)
                        pendingOutbox = emptyList()
                    }
                }
                LaunchedEffect(state.done) {
                    if (state.done) storage = StorageInfo.read()
                }

                HomeScreen(
                    state = state,
                    storage = storage,
                    tab = tab,
                    onTab = { tab = it },
                    selectedCount = if (tab == 0) pickedUris.size else pickedApps.size,
                    onPickFiles = { pickFiles.launch("*/*") },
                    onScanFile = { pickApk.launch("*/*") },
                    apps = apps,
                    appsLoading = appsLoading,
                    selectedApps = pickedApps,
                    onToggleApp = { pkg ->
                        pickedApps = if (pkg in pickedApps) pickedApps - pkg else pickedApps + pkg
                    },
                    vtKey = vtKey,
                    onSaveVtKey = { key ->
                        Prefs.setVtKey(applicationContext, key)
                        vtKey = key
                    },
                    receivedApps = receivedApps,
                    scanApp = { group -> ApkScanner.scan(applicationContext, group, vtKey) },
                    onInstallApp = { installApp(it) },
                    onDeleteApp = { group ->
                        group.files.forEach { it.delete() }
                        deleteTick += 1
                    },
                    onSend = {
                        withPermissions {
                            pendingOutbox = buildOutbox()
                            nearby.startSending()
                        }
                    },
                    onReceive = {
                        withPermissions {
                            pendingOutbox = emptyList()
                            nearby.startReceiving()
                        }
                    },
                    onStop = { nearby.stop() },
                    onConnect = { nearby.connectTo(it) },
                    onAccept = { nearby.acceptPending() },
                    onReject = { nearby.rejectPending() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) nearby.stop()
    }

    /** Runtime permissions Nearby Connections needs on each Android version. */
    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        // Requested on every Android version: Nearby fails with 8034 without it on some devices.
        list += Manifest.permission.ACCESS_COARSE_LOCATION
        list += Manifest.permission.ACCESS_FINE_LOCATION
        return list.toTypedArray()
    }
}
