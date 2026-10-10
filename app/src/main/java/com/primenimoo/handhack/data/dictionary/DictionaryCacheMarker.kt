package com.primenimoo.handhack.data.dictionary

import java.io.File

/**
 * Sidecar describing a prepared dictionary copy.
 *
 * Its only job is to let a warm launch trust metadata it would otherwise have to
 * recompute by re-reading both the asset and the working copy in full.
 *
 * Kept free of Android types so the round-trip can be unit tested on the JVM.
 */
internal data class DictionaryCacheMarker(
    val workingSize: Long,
    val workingStamp: Long,
    val entryCount: Long,
    val isDefault: Boolean,
) {
    /** True when the working copy on disk is still the exact one this marker describes. */
    fun matches(workingFile: File): Boolean =
        workingFile.isFile &&
            workingFile.length() == workingSize &&
            workingFile.lastModified() == workingStamp

    fun serialize(): String = buildString {
        appendLine("version=$VERSION")
        appendLine("size=$workingSize")
        appendLine("stamp=$workingStamp")
        appendLine("count=$entryCount")
        appendLine("default=$isDefault")
    }

    companion object {
        /** Bump whenever the meaning of the stored fields changes. */
        const val VERSION = 1

        fun of(file: File, entryCount: Long, isDefault: Boolean): DictionaryCacheMarker =
            DictionaryCacheMarker(
                workingSize = file.length(),
                workingStamp = file.lastModified(),
                entryCount = entryCount,
                isDefault = isDefault,
            )

        /**
         * Parses a marker, returning null for anything unusable.
         *
         * A corrupt, truncated or outdated marker must never be trusted: the caller then
         * falls back to the authoritative (but slower) byte comparison.
         */
        fun parse(raw: String?): DictionaryCacheMarker? {
            if (raw.isNullOrBlank()) return null
            val fields = raw.lineSequence().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()

            if (fields["version"]?.toIntOrNull() != VERSION) return null
            val size = fields["size"]?.toLongOrNull() ?: return null
            val stamp = fields["stamp"]?.toLongOrNull() ?: return null
            val count = fields["count"]?.toLongOrNull() ?: return null
            val isDefault = fields["default"]?.toBooleanStrictOrNull() ?: return null
            if (size < 0L || stamp < 0L || count < 0L) return null

            return DictionaryCacheMarker(
                workingSize = size,
                workingStamp = stamp,
                entryCount = count,
                isDefault = isDefault,
            )
        }
    }
}