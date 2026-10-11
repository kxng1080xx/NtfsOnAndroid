package dev.ntfsfm.app

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exponent)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[exponent - 1])
}

/** Copy speed, e.g. "23.4 MB/s". */
fun formatSpeed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "-- MB/s" else formatBytes(bytesPerSecond) + "/s"

/** Rough time left, e.g. "1h 4m", "3m 20s", "12s". */
fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
