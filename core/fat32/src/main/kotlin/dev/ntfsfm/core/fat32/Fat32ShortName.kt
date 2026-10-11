package dev.ntfsfm.core.fat32

import java.util.Locale

/** 8.3 names: when a name can be stored as-is, and the NAME~N.EXT alias otherwise. */
object Fat32ShortName {
    private val safeChars: Set<Char> = (('A'..'Z') + ('0'..'9') + "$%'-_@~!(){}^#&".toList()).toSet()

    /** True if [name] is already a valid upper-case 8.3 name, so no LFN entries are needed. */
    fun fitsExactly(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        val base = name.substringBeforeLast('.', name)
        val ext = if ('.' in name) name.substringAfterLast('.') else ""
        if (base.length > 8 || ext.length > 3) return false
        if (base.any { it.uppercaseChar() !in safeChars } || ext.any { it.uppercaseChar() !in safeChars }) return false
        return name == name.uppercase(Locale.ROOT)
    }

    /** First NAME~N.EXT not in [existingShortNames] (which must be upper-case). */
    fun generate(longName: String, existingShortNames: Set<String>): String {
        val dotIndex = longName.lastIndexOf('.')
        val rawBase = if (dotIndex > 0) longName.substring(0, dotIndex) else longName
        val rawExt = if (dotIndex > 0) longName.substring(dotIndex + 1) else ""
        val base = rawBase.uppercase(Locale.ROOT).filter { it in safeChars }.ifEmpty { "FILE" }
        val ext = rawExt.uppercase(Locale.ROOT).filter { it in safeChars }.take(3)
        for (n in 1 until 1_000_000) {
            val tail = "~$n"
            val truncatedBase = base.take((8 - tail.length).coerceAtLeast(1)) + tail
            val candidate = if (ext.isEmpty()) truncatedBase else "$truncatedBase.$ext"
            if (candidate.uppercase(Locale.ROOT) !in existingShortNames) return candidate
        }
        error("Could not generate a unique short name for '$longName'")
    }
}
