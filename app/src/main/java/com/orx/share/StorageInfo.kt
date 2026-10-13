package com.orx.share

import android.os.Environment
import android.os.StatFs

/** Total and free internal storage of the phone. */
data class StorageInfo(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
    val usedFraction: Float get() = if (total > 0) used.toFloat() / total else 0f

    companion object {
        fun read(): StorageInfo {
            val stat = StatFs(Environment.getDataDirectory().path)
            return StorageInfo(total = stat.totalBytes, free = stat.availableBytes)
        }
    }
}
