package com.primenimoo.handhack.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Small helpers around the Storage Access Framework.
 *
 * HandHack never asks for MANAGE_EXTERNAL_STORAGE and never touches files the
 * user has not explicitly handed over through the system picker, so scoped
 * storage rules apply untouched.
 */
object FileAccess {

    const val BUFFER_SIZE = 64 * 1024

    fun openInput(context: Context, uri: Uri): InputStream =
        BufferedInputStream(context.contentResolver.openInputStream(uri), BUFFER_SIZE)

    fun openOutput(context: Context, uri: Uri): java.io.OutputStream =
        BufferedOutputStreamCompat(context.contentResolver.openOutputStream(uri))

    fun takePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun releasePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** Display name from OpenableColumns, falling back to the last path segment. */
    suspend fun displayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
    }

    /** Size in bytes, or -1 when the provider refuses to report it. */
    suspend fun size(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
                }
        }.getOrNull() ?: -1L
    }

    suspend fun copyToLocal(context: Context, uri: Uri, target: File): Long = withContext(Dispatchers.IO) {
        target.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw java.io.IOException("Не удалось создать папку ${parent.name}")
            }
        }
        var total = 0L
        openInput(context, uri).use { input ->
            target.outputStream().buffered(BUFFER_SIZE).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.flush()
            }
        }
        total
    }

    suspend fun writeTextToUri(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        openOutput(context, uri).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 12 } ?: ""

    /** Human readable size using thin spaces, matching the app's typography. */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${trim(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${trim(mb)} MB"
        val gb = mb / 1024.0
        return "${trim(gb)} GB"
    }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    /** Thousands separator used across the UI, e.g. 12 500. */
    fun formatCount(value: Long): String {
        val negative = value < 0
        val digits = kotlin.math.abs(value).toString()
        val sb = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append('\u202F')
            sb.append(c)
        }
        return if (negative) "-$sb" else sb.toString()
    }

    /**
 * A path inside the app's private workspace, with the parent directory created.
 *
 * Centralised so no caller can forget `mkdirs()` again — that omission made every
 * handshake import fail with an opaque FileNotFoundException.
 */
fun workspaceFile(context: Context, name: String): File {
    val dir = File(context.filesDir, "workspace")
    if (!dir.exists() && !dir.mkdirs()) {
        throw java.io.IOException("Не удалось создать рабочую папку")
    }
    return File(dir, name)
}

/** Russian plural forms: 1 сеть, 2 сети, 5 сетей. */
    fun plural(count: Int, one: String, few: String, many: String): String {
        val mod100 = count % 100
        if (mod100 in 11..14) return many
        return when (count % 10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }

    fun formatDuration(millis: Long): String {
        if (millis < 0) return "—"
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            String.format("%02d:%02d", minutes, seconds)
        } else {
            String.format("00:%02d", seconds)
        }
    }
}

private class BufferedOutputStreamCompat(stream: java.io.OutputStream?) : java.io.BufferedOutputStream(
    stream ?: throw java.io.IOException("Output stream unavailable"),
    FileAccess.BUFFER_SIZE,
)

@Suppress("unused")
private fun ContentResolver.unusedMarker(): Nothing = throw UnsupportedOperationException()
