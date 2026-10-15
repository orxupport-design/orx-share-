package com.orx.share.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.orx.share.ApkGroup
import com.orx.share.Finding
import com.orx.share.InstalledApp
import com.orx.share.R
import com.orx.share.Risk
import com.orx.share.ScanReport
import com.orx.share.VtStatus

private val OrxDanger = Color(0xFFFF5252)
private val OrxWarn = Color(0xFFFFB300)

/** Icon of an installed app (falls back to a generic Android icon). */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp = remember(packageName) {
        runCatching {
            ctx.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier)
    } else {
        Icon(Icons.Rounded.Android, contentDescription = null, tint = OrxCyan, modifier = modifier)
    }
}

/** Scrollable list of my installed apps, with a checkbox on each one. */
@Composable
fun AppPicker(
    apps: List<InstalledApp>,
    loading: Boolean,
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    val ctx = LocalContext.current
    if (loading) {
        Text(stringResource(R.string.apps_loading), color = OrxMuted, fontSize = 13.sp)
        return
    }
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(OrxSurface)
    ) {
        items(apps, key = { it.packageName }) { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onToggle(app.packageName) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.packageName, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        Formatter.formatFileSize(ctx, app.size),
                        color = OrxMuted,
                        fontSize = 12.sp,
                    )
                }
                Checkbox(
                    checked = app.packageName in selected,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(checkedColor = OrxCyan, checkmarkColor = OrxBg),
                )
            }
        }
    }
}

@Composable
private fun FindingRow(f: Finding) {
    val color = if (f.risk == Risk.DANGER) OrxDanger else OrxWarn
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(f.res, f.arg), color = color, fontSize = 13.sp)
    }
}

/**
 * A received app. It is checked automatically. A warning appears ONLY when something looks harmful;
 * otherwise the user just sees the app and an Install button.
 */
@Composable
fun ReceivedAppCard(
    group: ApkGroup,
    rescanKey: String,
    busy: Boolean,
    scan: suspend (ApkGroup) -> ScanReport,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    var report by remember(group.id, group.hasBase, group.version, rescanKey) { mutableStateOf<ScanReport?>(null) }
    LaunchedEffect(group.id, group.hasBase, group.version, rescanKey) {
        if (group.hasBase) {
            report = try {
                scan(group)
            } catch (e: Exception) {
                null
            }
        }
    }
    val r = report
    val icon = remember(r) {
        r?.icon?.let { d -> runCatching { d.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(OrxSurface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(44.dp))
            } else {
                Icon(Icons.Rounded.Android, contentDescription = null, tint = OrxCyan, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r?.label ?: group.packageHint,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val size = Formatter.formatFileSize(ctx, group.size)
                val version = r?.versionName.orEmpty()
                val line = if (version.isNotBlank()) {
                    stringResource(R.string.app_version, version) + "  •  " + size
                } else {
                    size
                }
                Text(line, color = OrxMuted, fontSize = 12.sp)
            }
        }

        if (!group.hasBase) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.receiving_wait), color = OrxMuted, fontSize = 13.sp)
        } else if (r == null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = OrxCyan)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scanning), color = OrxMuted, fontSize = 13.sp)
            }
        } else {
            // Warnings only when something is wrong.
            r.findings.filter { it.risk != Risk.SAFE }.forEach { FindingRow(it) }

            val vtText: Int? = when (r.vt) {
                VtStatus.CLEAN -> R.string.vt_clean
                VtStatus.UNKNOWN -> R.string.vt_unknown
                VtStatus.ERROR -> R.string.vt_error
                VtStatus.BAD_KEY -> R.string.vt_bad_key
                else -> null
            }
            if (vtText != null) {
                Text(
                    stringResource(vtText),
                    color = OrxMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.btn_delete), color = OrxMuted)
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    Text(stringResource(R.string.receiving_wait), color = OrxMuted, fontSize = 12.sp)
                } else {
                    val safe = r.risk == Risk.SAFE
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (safe) OrxViolet else OrxDanger,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(stringResource(if (safe) R.string.btn_install else R.string.btn_install_anyway))
                    }
                }
            }
        }
    }
}

/** Dialog to paste the (free) VirusTotal API key. Empty = online scan off. */
@Composable
fun VtKeyDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vt_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.vt_dialog_body), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.vt_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}
