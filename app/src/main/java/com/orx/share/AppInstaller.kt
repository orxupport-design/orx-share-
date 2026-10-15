package com.orx.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File

/**
 * Installs received APKs through Android's PackageInstaller.
 * Android always shows its own confirmation screen for apps installed this way (a security rule that
 * no app can bypass). The first time, the user must also allow "install unknown apps" for orx share.
 */
object AppInstaller {
    const val ACTION = "com.orx.share.INSTALL_RESULT"

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** Blocking (copies the files into the install session): call it from a background thread. */
    fun install(ctx: Context, files: List<File>) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            for (f in files) {
                f.inputStream().use { input ->
                    session.openWrite(f.name, 0, f.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            // Explicit intent to our own (non-exported) receiver.
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/** Receives the result of an install session from the system. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system asks us to show its "Install?" screen.
                @Suppress("DEPRECATION")
                val confirm: Intent? =
                    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, R.string.install_done, Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the user cancelled
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString()
                Toast.makeText(context, context.getString(R.string.install_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
