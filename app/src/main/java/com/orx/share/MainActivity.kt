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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orx.share.ui.HomeScreen
import com.orx.share.ui.OrxTheme

class MainActivity : ComponentActivity() {

    private lateinit var nearby: NearbyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearby = NearbyManager(applicationContext)

        setContent {
            OrxTheme {
                val state by nearby.state.collectAsStateWithLifecycle()
                var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
                var storage by remember { mutableStateOf(StorageInfo.read()) }
                var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

                val pickFiles = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
                ) { uris -> selected = uris }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    if (result.values.all { it }) {
                        afterPermission?.invoke()
                    } else {
                        Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
                    }
                    afterPermission = null
                }

                fun withPermissions(action: () -> Unit) {
                    afterPermission = action
                    permissionLauncher.launch(requiredPermissions())
                }

                // Once connected as a sender, push the selected files automatically.
                androidx.compose.runtime.LaunchedEffect(state.phase) {
                    storage = StorageInfo.read()
                    if (state.phase == NearbyManager.Phase.CONNECTED &&
                        selected.isNotEmpty() && !state.transferring && !state.done
                    ) {
                        nearby.sendFiles(selected)
                    }
                }
                androidx.compose.runtime.LaunchedEffect(state.done) {
                    if (state.done) storage = StorageInfo.read()
                }

                HomeScreen(
                    state = state,
                    storage = storage,
                    selectedCount = selected.size,
                    onPickFiles = { pickFiles.launch("*/*") },
                    onSend = { withPermissions { nearby.startSending() } },
                    onReceive = { withPermissions { nearby.startReceiving() } },
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
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.toTypedArray()
    }
}
