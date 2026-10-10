package com.primenimoo.handhack.ui.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.primenimoo.handhack.HandHackApplication
import com.primenimoo.handhack.MainActivity
import com.primenimoo.handhack.R
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.data.settings.HandHackSettings
import com.primenimoo.handhack.engine.AnalysisResult
import com.primenimoo.handhack.engine.AnalysisStatus
import kotlin.math.roundToInt

/**
 * Local notifications only. Nothing is ever sent over the network — the app has no
 * INTERNET permission at all.
 */
class AnalysisNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun syncSettings(settings: HandHackSettings) {
        this.settings = settings
    }

    private var settings: HandHackSettings = HandHackSettings()

    fun showProgress(fileName: String, processed: Long, total: Long) {
        val fraction = if (total > 0) (processed.toFloat() / total).coerceIn(0f, 1f) else 0f
        val notification = baseBuilder(CHANNEL_PROGRESS_ID)
            .setContentTitle(context.getString(R.string.notify_progress_title, fileName))
            .setContentText(context.getString(R.string.notify_progress_text, FileAccess.formatCount(processed), FileAccess.formatCount(total)))
            .setProgress(100, (fraction * 100).roundToInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        safeNotify(PROGRESS_ID, notification)
    }

    fun showResult(result: AnalysisResult) {
        when (result.status) {
            AnalysisStatus.COMPLETED -> if (settings.notifyProcessCompleted) {
                notifyCompleted(result)
            }

            AnalysisStatus.NO_RESULT -> if (settings.notifyProcessCompleted) {
                notify(
                    title = context.getString(R.string.notify_no_result_title),
                    text = "${result.fileName} — совпадений со словарём не найдено",
                )
            }

            AnalysisStatus.ERROR -> if (settings.notifyErrors) {
                notify(
                    title = context.getString(R.string.notify_error_title),
                    text = result.errorMessage ?: result.fileName,
                )
            }

            else -> Unit
        }
        manager.cancel(PROGRESS_ID)
    }

    fun showImportCompleted(entryCount: Long, fileLabel: String) {
        if (!settings.notifyImportCompleted) return
        notify(
            title = context.getString(R.string.notify_import_title),
            text = "$fileLabel — записей: ${FileAccess.formatCount(entryCount)}",
        )
    }

    private fun notifyCompleted(result: AnalysisResult) {
        notify(
            title = context.getString(R.string.notify_done_title),
            text = buildString {
                append(result.fileName)
                append(" — обработано ")
                append(FileAccess.formatCount(result.processedItems))
                if (result.matchedEntries.isNotEmpty()) {
                    append(", совпадений: ")
                    append(result.matchedEntries.size)
                }
            },
        )
    }

    private fun notify(title: String, text: String) {
        val notification = baseBuilder(CHANNEL_STATUS_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        safeNotify(STATUS_ID, notification)
    }

    private fun baseBuilder(channelId: String): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent())
        return builder
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun safeNotify(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val CHANNEL_PROGRESS_ID = HandHackApplication.CHANNEL_PROGRESS
        const val CHANNEL_STATUS_ID = HandHackApplication.CHANNEL_STATUS
        private const val PROGRESS_ID = 1001
        private const val STATUS_ID = 1002

        val SupportedSince: Int = Build.VERSION_CODES.O
    }
}