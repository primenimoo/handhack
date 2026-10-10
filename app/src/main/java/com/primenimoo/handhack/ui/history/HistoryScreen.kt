package com.primenimoo.handhack.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.data.history.HistoryEntry
import com.primenimoo.handhack.data.history.HistoryOutcome
import com.primenimoo.handhack.ui.components.HandHackCard
import com.primenimoo.handhack.ui.components.HandHackTopBar
import com.primenimoo.handhack.ui.components.SectionHeader
import com.primenimoo.handhack.ui.components.StatusChip
import com.primenimoo.handhack.ui.theme.HandHackSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        HandHackTopBar(
            title = "История",
            subtitle = "${entries.size} записей",
            onBack = onBack,
        )

        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(HandHackSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(HandHackSpacing.md))
                Text("Пока пусто", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(HandHackSpacing.xs))
                Text(
                    text = "Здесь появятся завершённые подборы.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = HandHackSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(HandHackSpacing.sm),
            ) {
                item {
                    SectionHeader(
                        title = "Подборы",
                        action = {
                            OutlinedButton(
                                onClick = { confirmClear = true },
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Очистить")
                            }
                        },
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    HistoryRow(entry)
                }
                item { Spacer(Modifier.height(HandHackSpacing.lg)) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить историю?") },
            text = { Text("Все записи о подборах будут удалены. На файлы и словарь это не влияет.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    HandHackCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(HandHackSpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${formatDate(entry.timestampMillis)} · ${entry.formatLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(
                text = entry.outcome.label,
                container = when (entry.outcome) {
                    HistoryOutcome.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                    HistoryOutcome.NO_RESULT -> MaterialTheme.colorScheme.tertiaryContainer
                    HistoryOutcome.CANCELLED -> MaterialTheme.colorScheme.surfaceContainerHighest
                    HistoryOutcome.FAILED -> MaterialTheme.colorScheme.errorContainer
                },
                content = when (entry.outcome) {
                    HistoryOutcome.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
                    HistoryOutcome.NO_RESULT -> MaterialTheme.colorScheme.onTertiaryContainer
                    HistoryOutcome.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                    HistoryOutcome.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                },
            )
        }
        Spacer(Modifier.height(HandHackSpacing.sm))
        Row(modifier = Modifier.fillMaxWidth()) {
            Metric("Обработано", FileAccess.formatCount(entry.processedItems), Modifier.weight(1f))
            Metric("Время", FileAccess.formatDuration(entry.durationMillis), Modifier.weight(1f))
            Metric("Всего", FileAccess.formatCount(entry.totalItems), Modifier.weight(1f))
        }
        if (entry.detail.isNotBlank()) {
            Spacer(Modifier.height(HandHackSpacing.xs))
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(millis))