package com.primenimoo.handhack.detect

import android.content.Context
import android.net.Uri
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.engine.capture.CaptureLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides whether a user supplied file is a Wi-Fi handshake we can actually work with.
 *
 * HandHack reads six containers, and they are told apart by their bytes rather than by their
 * extension: a `.txt` full of `.hc22000` records is read as `.hc22000`, and a `.hccapx` that was
 * renamed to `.cap` is still recognised. An image, an archive or an office document has to be
 * refused instead of being fed to the password search, so a file that no reader recognises is
 * reported as unsupported with the reason the reader gave.
 *
 * Detection runs on a local copy in the app's workspace, because both the classic pcap and the
 * pcapng readers walk a file by seeking through it, which a content stream cannot do.
 */
class FileDetector {

    suspend fun inspect(context: Context, uri: Uri): DetectionResult = withContext(Dispatchers.IO) {
        val fileName = runCatching { FileAccess.displayName(context, uri) }.getOrDefault("file")
        val extension = FileAccess.extensionOf(fileName)
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val reportedSize = FileAccess.size(context, uri)

        if (reportedSize == 0L) {
            return@withContext DetectionResult.Unsupported(EMPTY_FILE)
        }

        // A size the provider refuses to report is resolved by reading, bounded so a
        // hostile or truncated file cannot stall the picker.
        val size = if (reportedSize > 0L) reportedSize else measure(context, uri)
        if (size == 0L) {
            return@withContext DetectionResult.Unsupported(EMPTY_FILE)
        }
        if (size > MAX_SUPPORTED_BYTES) {
            return@withContext DetectionResult.Unsupported(
                "Файл слишком большой: ${FileAccess.formatSize(size)}. " +
                    "Поддерживаются файлы до ${FileAccess.formatSize(MAX_SUPPORTED_BYTES)}.",
            )
        }

        val workspace = FileAccess.workspaceFile(context, "validate.tmp")
        val bytes = runCatching { FileAccess.copyToLocal(context, uri, workspace) }
            .getOrElse {
                workspace.delete()
                return@withContext DetectionResult.Failed(UNREADABLE_FILE)
            }
        if (bytes <= 0L) {
            workspace.delete()
            return@withContext DetectionResult.Unsupported(EMPTY_FILE)
        }

        try {
            val loaded = CaptureLoader.load(workspace)

            if (!loaded.isUsable) {
                return@withContext DetectionResult.Unsupported(
                    loaded.notes.firstOrNull() ?: UNSUPPORTED_CONTENT,
                )
            }

            DetectionResult.Detected(
                FileInfo(
                    uri = uri,
                    fileName = fileName,
                    extension = extension,
                    mimeType = mimeType,
                    sizeBytes = bytes,
                    format = DetectedFormat(
                        id = loaded.format.id,
                        label = loaded.format.label,
                        extensions = loaded.format.extensions,
                        mimeTypes = setOf("application/octet-stream", "text/plain"),
                        textBased = loaded.format.textBased,
                    ),
                    readable = true,
                    empty = false,
                    lineHint = loaded.targets.size,
                    structureNote = describe(loaded),
                ),
            )
        } finally {
            // The local copy exists only to identify the file; the run reads its own copy.
            workspace.delete()
        }
    }

    private fun describe(loaded: CaptureLoader.Loaded): String {
        val networks = FileAccess.formatCount(loaded.targets.size.toLong()) +
            " " + FileAccess.plural(loaded.targets.size, "сеть", "сети", "сетей")
        val note = loaded.notes.firstOrNull()
        return if (note.isNullOrBlank()) networks else "$networks. $note"
    }

    private suspend fun measure(context: Context, uri: Uri): Long = runCatching {
        FileAccess.openInput(context, uri).use { input ->
            val buffer = ByteArray(FileAccess.BUFFER_SIZE)
            var total = 0L
            while (total < MAX_SUPPORTED_BYTES) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
            }
            total
        }
    }.getOrDefault(0L)

    companion object {
        /** Every supported container fits comfortably in memory, well below this. */
        const val MAX_SUPPORTED_BYTES = 32L * 1024 * 1024

        const val EMPTY_FILE = "Файл пустой — нет данных для подбора."
        const val UNREADABLE_FILE = "Не удалось прочитать файл. Возможно, он повреждён или удалён."
        const val UNSUPPORTED_CONTENT =
            "Не удалось обработать файл: неподдерживаемый формат. " +
                "Поддерживаются hc22000, hccapx, hccap, pcap, cap и pcapng."

        /** Every extension the picker should offer. */
        val SUPPORTED_EXTENSIONS: Set<String> = CaptureLoader.CaptureFormat.ALL_EXTENSIONS
    }
}