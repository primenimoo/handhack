package com.primenimoo.handhack.detect

import android.net.Uri

/** The one file format HandHack accepts. */
data class DetectedFormat(
    val id: String,
    val label: String,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
    val textBased: Boolean,
)

/** Outcome of inspecting a user supplied file. */
sealed interface DetectionResult {
    data class Detected(val info: FileInfo) : DetectionResult
    data class Unsupported(val reason: String) : DetectionResult
    data class Failed(val reason: String) : DetectionResult
}

data class FileInfo(
    val uri: Uri,
    val fileName: String,
    val extension: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val format: DetectedFormat,
    val readable: Boolean,
    val empty: Boolean,
    /** Number of verifiable networks found in the file. */
    val lineHint: Int?,
    val structureNote: String,
)