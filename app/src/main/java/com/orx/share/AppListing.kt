package com.orx.share

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File

data class InstalledApp(
    val packageName: String,
    val label: String,
    val apkPaths: List<String>,
    val size: Long,
)

object AppListing {
    /** Apps the user installed (and updated system apps like Chrome), without system-only apps. */
    @Suppress("DEPRECATION")
    fun load(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { ai ->
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            .mapNotNull { ai ->
                val paths = buildList<String> {
                    add(ai.sourceDir)
                    ai.splitSourceDirs?.forEach { add(it) }
                }
                val files = paths.map { File(it) }
                if (files.none { it.canRead() }) return@mapNotNull null
                InstalledApp(
                    packageName = ai.packageName,
                    label = ai.loadLabel(pm).toString(),
                    apkPaths = paths,
                    size = files.sumOf { it.length() },
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
