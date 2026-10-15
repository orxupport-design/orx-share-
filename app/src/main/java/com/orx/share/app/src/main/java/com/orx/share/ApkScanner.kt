package com.orx.share

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class Risk { SAFE, CAUTION, DANGER }
enum class VtStatus { OFF, CLEAN, UNKNOWN, FLAGGED, BAD_KEY, ERROR }

/** One problem found in an app. [res] is a string resource; [arg] fills its %1$s placeholder. */
data class Finding(val risk: Risk, val res: Int, val arg: String = "")

class ScanReport(
    val packageName: String,
    val label: String,
    val versionName: String,
    val icon: Drawable?,
    val risk: Risk,
    val findings: List<Finding>,
    val vt: VtStatus,
)

/** Small private settings (stored only inside the app's own storage). */
object Prefs {
    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("orx_prefs", Context.MODE_PRIVATE)

    fun vtKey(ctx: Context): String = sp(ctx).getString("vt_key", "") ?: ""

    fun setVtKey(ctx: Context, key: String) {
        sp(ctx).edit().putString("vt_key", key.trim()).apply()
    }
}

/** Looks up a file fingerprint (SHA-256) on VirusTotal. The file itself is never uploaded. */
object VirusTotal {
    enum class Kind { KNOWN, UNKNOWN, BAD_KEY, ERROR }
    class Result(val kind: Kind, val malicious: Int = 0, val suspicious: Int = 0)

    /** Blocking call: run it on a background thread. */
    fun lookup(apiKey: String, sha256: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("https://www.virustotal.com/api/v3/files/$sha256").openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("x-apikey", apiKey)
            c.setRequestProperty("accept", "application/json")
            when (c.responseCode) {
                200 -> {
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    val stats = JSONObject(body)
                        .getJSONObject("data")
                        .getJSONObject("attributes")
                        .getJSONObject("last_analysis_stats")
                    Result(Kind.KNOWN, stats.optInt("malicious"), stats.optInt("suspicious"))
                }
                404 -> Result(Kind.UNKNOWN)
                401, 403 -> Result(Kind.BAD_KEY)
                else -> Result(Kind.ERROR)
            }
        } catch (e: Exception) {
            Result(Kind.ERROR)
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * Checks a received app BEFORE it is installed. Checks:
 *  1. The file is a real, readable APK with a valid signature.
 *  2. If the same app is already installed, the developer signature must match (detects fake/modified copies).
 *  3. Dangerous permission combinations typical of banking trojans / spyware.
 *  4. Optional: VirusTotal reputation by SHA-256 (needs the user's free API key).
 * This is a safety net, not a guarantee: no scanner can promise an app is 100% safe.
 */
object ApkScanner {
    private const val ACCESSIBILITY = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val DEVICE_ADMIN = "android.permission.BIND_DEVICE_ADMIN"
    private const val LISTENER = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    private const val INSTALL = "android.permission.REQUEST_INSTALL_PACKAGES"
    private const val CALL_LOG = "android.permission.READ_CALL_LOG"
    private val SMS = setOf(
        "android.permission.SEND_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
    )

    suspend fun scan(ctx: Context, group: ApkGroup, vtKey: String?): ScanReport =
        withContext(Dispatchers.IO) { scanBlocking(ctx, group, vtKey) }

    private fun scanBlocking(ctx: Context, group: ApkGroup, vtKey: String?): ScanReport {
        val pm = ctx.packageManager
        val base = group.files.firstOrNull { it.name.endsWith("__base.apk") } ?: group.files.first()

        val info = readArchive(pm, base)
        val ai = info?.applicationInfo
        if (info == null || ai == null) return unreadable(group)

        ai.sourceDir = base.absolutePath
        ai.publicSourceDir = base.absolutePath
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(info.packageName)
        val icon = runCatching { ai.loadIcon(pm) }.getOrNull()

        val findings = mutableListOf<Finding>()

        // 1 + 2: signature checks
        val archiveSigners = signerDigests(info)
        if (archiveSigners.isEmpty()) {
            findings += Finding(Risk.DANGER, R.string.finding_unsigned)
        } else {
            val installed = readInstalled(pm, info.packageName)
            if (installed != null) {
                val installedSigners = signerDigests(installed)
                if (installedSigners.isNotEmpty() && archiveSigners.intersect(installedSigners).isEmpty()) {
                    findings += Finding(Risk.DANGER, R.string.finding_spoof, label)
                }
            }
        }

        // 3: permission combinations (also looks at services/receivers, where accessibility etc. are declared)
        val perms = buildSet<String> {
            info.requestedPermissions?.forEach { add(it) }
            info.services?.forEach { s -> s.permission?.let { add(it) } }
            info.receivers?.forEach { r -> r.permission?.let { add(it) } }
        }
        val hits = mutableListOf<Int>()
        if (ACCESSIBILITY in perms) hits += R.string.perm_accessibility
        if (OVERLAY in perms) hits += R.string.perm_overlay
        if (perms.any { it in SMS }) hits += R.string.perm_sms
        if (DEVICE_ADMIN in perms) hits += R.string.perm_device_admin
        if (LISTENER in perms) hits += R.string.perm_listener
        if (INSTALL in perms) hits += R.string.perm_install
        if (CALL_LOG in perms) hits += R.string.perm_calllog
        val hasAccessibility = ACCESSIBILITY in perms
        val permRisk = when {
            hasAccessibility && hits.size >= 4 -> Risk.DANGER
            hasAccessibility && hits.size >= 3 -> Risk.CAUTION
            hits.size >= 5 -> Risk.CAUTION
            else -> Risk.SAFE
        }
        if (permRisk != Risk.SAFE) {
            val sep = ctx.getString(R.string.list_sep)
            findings += Finding(permRisk, R.string.finding_permissions, hits.joinToString(sep) { ctx.getString(it) })
        }

        // 4: VirusTotal (optional)
        var vt = VtStatus.OFF
        if (!vtKey.isNullOrBlank()) {
            var malicious = 0
            var suspicious = 0
            var known = false
            var badKey = false
            var error = false
            // The free VirusTotal key allows 4 lookups per minute, so check at most 4 files per app.
            for (f in group.files.take(4)) {
                val r = VirusTotal.lookup(vtKey.trim(), sha256File(f))
                when (r.kind) {
                    VirusTotal.Kind.KNOWN -> {
                        known = true
                        malicious = maxOf(malicious, r.malicious)
                        suspicious = maxOf(suspicious, r.suspicious)
                    }
                    VirusTotal.Kind.BAD_KEY -> badKey = true
                    VirusTotal.Kind.ERROR -> error = true
                    VirusTotal.Kind.UNKNOWN -> Unit
                }
                if (badKey) break
            }
            vt = when {
                badKey -> VtStatus.BAD_KEY
                malicious >= 1 || suspicious >= 3 -> VtStatus.FLAGGED
                error && !known -> VtStatus.ERROR
                known -> VtStatus.CLEAN
                else -> VtStatus.UNKNOWN
            }
            when {
                malicious >= 3 -> findings += Finding(Risk.DANGER, R.string.finding_vt_danger, malicious.toString())
                malicious >= 1 -> findings += Finding(Risk.CAUTION, R.string.finding_vt_caution, malicious.toString())
                suspicious >= 3 -> findings += Finding(Risk.CAUTION, R.string.finding_vt_caution, suspicious.toString())
            }
        }

        val risk = findings.maxByOrNull { it.risk.ordinal }?.risk ?: Risk.SAFE
        return ScanReport(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName ?: "",
            icon = icon,
            risk = risk,
            findings = findings,
            vt = vt,
        )
    }

    private fun unreadable(group: ApkGroup) = ScanReport(
        packageName = group.packageHint,
        label = group.packageHint,
        versionName = "",
        icon = null,
        risk = Risk.DANGER,
        findings = listOf(Finding(Risk.DANGER, R.string.finding_unreadable)),
        vt = VtStatus.OFF,
    )

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    /** The system's own parser reads the APK, so a malformed file can't crash or fool our code. */
    @Suppress("DEPRECATION")
    private fun readArchive(pm: PackageManager, f: File): PackageInfo? {
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            signingFlag()
        return try {
            pm.getPackageArchiveInfo(f.absolutePath, flags)
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readInstalled(pm: PackageManager, pkg: String): PackageInfo? =
        try {
            pm.getPackageInfo(pkg, signingFlag())
        } catch (e: Exception) {
            null
        }

    /** SHA-256 fingerprints of the developer certificates that signed this package. */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo
                if (si == null) null
                else if (si.hasMultipleSigners()) si.apkContentsSigners
                else si.signingCertificateHistory
            } else {
                info.signatures
            }
        return sigs?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256File(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
