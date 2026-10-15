package com.orx.share.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orx.share.ApkGroup
import com.orx.share.InstalledApp
import com.orx.share.NearbyManager
import com.orx.share.R
import com.orx.share.ScanReport
import com.orx.share.StorageInfo

@Composable
fun HomeScreen(
    state: NearbyManager.UiState,
    storage: StorageInfo,
    tab: Int,
    onTab: (Int) -> Unit,
    selectedCount: Int,
    onPickFiles: () -> Unit,
    onScanFile: () -> Unit,
    apps: List<InstalledApp>,
    appsLoading: Boolean,
    selectedApps: Set<String>,
    onToggleApp: (String) -> Unit,
    vtKey: String,
    onSaveVtKey: (String) -> Unit,
    receivedApps: List<ApkGroup>,
    scanApp: suspend (ApkGroup) -> ScanReport,
    onInstallApp: (ApkGroup) -> Unit,
    onDeleteApp: (ApkGroup) -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onStop: () -> Unit,
    onConnect: (NearbyManager.Peer) -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val idle = state.phase == NearbyManager.Phase.IDLE
    val gradient = Brush.linearGradient(listOf(OrxCyan, OrxViolet))
    var showVt by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(OrxBg)
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrxLogo(size = 110.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = TextStyle(
                    brush = gradient,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                ),
            )
            Text(
                text = stringResource(R.string.tagline),
                color = OrxMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))
            StorageCard(storage)

            Spacer(Modifier.height(16.dp))
            TabSwitch(tab = tab, onTab = onTab, enabled = idle)

            Spacer(Modifier.height(12.dp))
            if (tab == 0) {
                OutlinedButton(
                    onClick = onPickFiles,
                    enabled = idle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.AttachFile, null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (selectedCount > 0) stringResource(R.string.files_selected, selectedCount)
                        else stringResource(R.string.btn_pick_files)
                    )
                }
            } else {
                AppPicker(
                    apps = apps,
                    loading = appsLoading,
                    selected = selectedApps,
                    enabled = idle,
                    onToggle = onToggleApp,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selectedCount > 0) stringResource(R.string.apps_selected, selectedCount) else "",
                        color = OrxMuted,
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = { showVt = true }) {
                        Icon(Icons.Rounded.Settings, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.vt_settings), fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onScanFile,
                    enabled = idle,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.AttachFile, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.btn_scan_file))
                }
            }

            Spacer(Modifier.height(12.dp))
            if (idle) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onSend,
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OrxViolet),
                    ) {
                        Icon(Icons.Rounded.Upload, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.btn_send))
                    }
                    Button(
                        onClick = onReceive,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OrxCyan,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                    ) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.btn_receive))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Stop, null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.btn_stop))
                }
            }

            Spacer(Modifier.height(20.dp))
            StatusBlock(state)

            if (state.phase == NearbyManager.Phase.DISCOVERING) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.nearby_devices),
                    color = OrxMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (state.peers.isEmpty()) {
                    Text(stringResource(R.string.no_devices), color = OrxMuted, fontSize = 13.sp)
                }
                state.peers.forEach { peer ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(OrxSurface)
                            .clickable { onConnect(peer) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PhoneAndroid, null, tint = OrxCyan)
                        Spacer(Modifier.size(12.dp))
                        Text(peer.name, fontWeight = FontWeight.Medium)
                    }
                }
            }

            state.savedDir?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.received_in, it),
                    color = OrxMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (receivedApps.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.received_apps_title),
                    color = OrxMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
                receivedApps.forEach { group ->
                    key(group.id) {
                        ReceivedAppCard(
                            group = group,
                            rescanKey = vtKey,
                            busy = state.transferring,
                            scan = scanApp,
                            onInstall = { onInstallApp(group) },
                            onDelete = { onDeleteApp(group) },
                        )
                    }
                }
            }
        }
    }

    if (showVt) {
        VtKeyDialog(
            current = vtKey,
            onSave = {
                onSaveVtKey(it)
                showVt = false
            },
            onDismiss = { showVt = false },
        )
    }

    state.pending?.let { p ->
        AlertDialog(
            onDismissRequest = onReject,
            title = { Text(stringResource(R.string.verify_title)) },
            text = {
                Column {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.verify_body, p.digits))
                }
            },
            confirmButton = {
                TextButton(onClick = onAccept) { Text(stringResource(R.string.btn_accept)) }
            },
            dismissButton = {
                TextButton(onClick = onReject) { Text(stringResource(R.string.btn_reject)) }
            },
        )
    }
}

/** Switch between sharing files and sharing apps. */
@Composable
private fun TabSwitch(tab: Int, onTab: (Int) -> Unit, enabled: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OrxSurface)
            .padding(4.dp)
    ) {
        listOf(R.string.tab_files, R.string.tab_apps).forEachIndexed { index, label ->
            val selected = tab == index
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) OrxViolet else Color.Transparent)
                    .clickable(enabled = enabled) { onTab(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label),
                    fontWeight = FontWeight.Medium,
                    color = if (selected) Color.White else OrxMuted,
                )
            }
        }
    }
}

@Composable
private fun StorageCard(storage: StorageInfo) {
    val ctx = LocalContext.current
    fun fmt(b: Long) = Formatter.formatFileSize(ctx, b)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(OrxSurface)
            .padding(18.dp)
    ) {
        Text(stringResource(R.string.storage_title), color = OrxMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stringResource(R.string.storage_free, fmt(storage.free)),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = OrxCyan,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.storage_total, fmt(storage.total)),
                color = OrxMuted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { storage.usedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = OrxViolet,
            trackColor = OrxBg,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.storage_used, fmt(storage.used)),
            color = OrxMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatusBlock(state: NearbyManager.UiState) {
    val text = when {
        state.failed -> stringResource(R.string.status_failed)
        state.done -> stringResource(R.string.status_done)
        state.transferring && state.sending -> stringResource(R.string.status_sending, state.progress)
        state.transferring -> stringResource(R.string.status_receiving, state.progress)
        state.phase == NearbyManager.Phase.CONNECTED ->
            stringResource(R.string.status_connected, state.connectedName ?: "")
        state.phase == NearbyManager.Phase.CONNECTING -> stringResource(R.string.status_connecting)
        state.phase == NearbyManager.Phase.ADVERTISING -> stringResource(R.string.status_advertising)
        state.phase == NearbyManager.Phase.DISCOVERING -> stringResource(R.string.status_discovering)
        else -> stringResource(R.string.status_idle)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontWeight = FontWeight.Medium, color = OrxText)
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = OrxMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        if (state.transferring) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = OrxCyan,
                trackColor = OrxSurface,
            )
        }
    }
}
