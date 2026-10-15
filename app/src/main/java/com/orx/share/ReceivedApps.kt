package com.orx.share

import android.content.Context
import android.net.Uri
import java.io.File

/** Folders where received files are stored. */
object ReceiveDirs {
    fun root(ctx: Context): File =
        (ctx.getExternalFilesDir("Received") ?: File(ctx.filesDir, "Received"))
            .also { it.mkdirs() }
}

/**
 * All APK files that belong to one received app (the base APK plus any split APKs).
 * [id] looks like "com.example.app@1760000000".
 */
class ApkGroup(
    val id: String,
    val packageHint: String,
    val files: List<File>,
    val hasBase: Boolean,
) {
    val size: Long get() = files.sumOf { it.length() }

    /** Changes whenever any of the files is replaced, so a swapped file is always scanned again. */
    val version: String = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
}

object ReceivedApps {
    // Strict whitelist: "<package>@<unix seconds>__<file>.apk". Anything else is treated as a normal file,
    // so a sender can never choose an arbitrary path or name inside the apps folder.
    private val NAME = Regex("([A-Za-z0-9._]{1,200})@([0-9]{6,12})__([A-Za-z0-9._\\-]{1,200}\\.apk)")

    fun isAppFileName(name: String): Boolean = NAME.matches(name)

    /** The "<package>@<stamp>" part of an app file name. Uses the regex, so a package name containing "__" is safe. */
    private fun idOf(name: String): String {
        val m = NAME.matchEntire(name) ?: return name
        return "${m.groupValues[1]}@${m.groupValues[2]}"
    }

    fun dir(ctx: Context): File = File(ReceiveDirs.root(ctx), "apps").also { it.mkdirs() }

    fun list(ctx: Context): List<ApkGroup> {
        val files = dir(ctx).listFiles()
            ?.filter { it.isFile && isAppFileName(it.name) }
            ?: return emptyList()
        return files
            .groupBy { idOf(it.name) }
            .map { (id, group) ->
                val sorted = group.sortedBy { if (it.name.endsWith("__base.apk")) 0 else 1 }
                ApkGroup(
                    id = id,
                    packageHint = id.substringBefore('@'),
                    files = sorted,
                    hasBase = sorted.first().name.endsWith("__base.apk"),
                )
            }
            .sortedByDescending { g -> g.files.maxOf { it.lastModified() } }
    }

    private val PKG = Regex("[A-Za-z0-9._]{1,200}")

    /**
     * Copies an APK the user picked on this phone into the apps folder, so it is scanned and installed
     * exactly like an app received from another phone. Blocking: call it from a background thread.
     */
    fun importApk(ctx: Context, uri: Uri): Boolean {
        val dir = dir(ctx)
        // Ends in ".apk" so every Android version's package parser accepts it, but it never matches
        // the "<package>@<time>__<file>.apk" pattern, so it is never listed as a received app.
        val tmp = File(dir, "import_${System.nanoTime()}.apk")
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            // The system's own parser decides whether this is a real APK, and tells us the package name.
            val info = ctx.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0) ?: return false
            val pkg = info.packageName
            if (!PKG.matches(pkg)) return false
            tmp.renameTo(File(dir, "$pkg@${System.currentTimeMillis() / 1000}__base.apk"))
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete() // nothing left behind if it failed (no-op after a successful rename)
        }
    }
}
