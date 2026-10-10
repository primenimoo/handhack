package com.primenimoo.handhack.ui.dictionary

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.ui.components.HandHackCard
import com.primenimoo.handhack.ui.components.HandHackConfirmDialog
import com.primenimoo.handhack.ui.components.HandHackTopBar
import com.primenimoo.handhack.ui.components.SectionHeader
import com.primenimoo.handhack.ui.components.StatRow
import com.primenimoo.handhack.ui.components.StatusChip
import com.primenimoo.handhack.ui.components.copyToClipboard
import com.primenimoo.handhack.ui.components.rememberHaptics
import com.primenimoo.handhack.ui.theme.HandHackSpacing
import com.primenimoo.handhack.ui.theme.HandHackTypography

/**
 * How many dictionary lines the preview shows.
 *
 * Small enough to compose eagerly (which keeps the page a single scroll container) and
 * large enough to recognise the file's shape at a glance.
 */
private const val PREVIEW_LIMIT = 50

@Composable
fun DictionaryScreen(
    viewModel: DictionaryViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editorState.collectAsStateWithLifecycle()
    val download by viewModel.downloadState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val haptics = rememberHaptics()

    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
var confirmDiscard by rememberSaveable { mutableStateOf(false) }
var query by rememberSaveable { mutableStateOf("") }

// Kept closed until the user asks for it: laying out a 13k line string inside a
// TextField blocks the first frame for seconds on a mid range device.
var editorOpen by rememberSaveable { mutableStateOf(false) }

    val requestBack: () -> Unit = {
        if (editor.dirty) confirmDiscard = true else onBack?.invoke()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::importFrom) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? -> uri?.let(viewModel::exportTo) }

    LaunchedEffect(editor.message) {
        editor.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HandHackTopBar(
            title = "Словарь",
            subtitle = "${FileAccess.formatCount(state.entryCount)} записей",
            onBack = requestBack.takeIf { onBack != null },
            actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = "Импортировать словарь")
                    }
                    FilledTonalIconButton(
                        onClick = { exportLauncher.launch("special.txt") },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = "Экспортировать словарь")
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HandHackSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(HandHackSpacing.md),
        ) {
            HandHackCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Источник", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (state.dictionary.isDefault) "special.txt (по умолчанию)" else "Рабочая копия",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    StatusChip(text = if (state.dictionary.isDefault) "Оригинал" else "Изменён")
                }
                Spacer(Modifier.height(HandHackSpacing.sm))
                StatRow(label = "Записей", value = FileAccess.formatCount(state.entryCount), emphasize = true)
                StatRow(label = "Размер", value = FileAccess.formatSize(state.dictionary.sizeBytes))
                Spacer(Modifier.height(HandHackSpacing.sm))
                Text(
                    text = "Оригинал из assets никогда не изменяется — все правки идут в рабочую копию.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(title = "Инструменты")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm),
            ) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Очистить")
                }
                OutlinedButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Сброс")
                }
            }

            OutlinedButton(
                onClick = viewModel::downloadFromGitHub,
                enabled = !download.downloading,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                if (download.downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Скачивание…")
                } else {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Скачать с GitHub")
                }
            }

            SectionHeader(title = "Редактор", subtitle = if (editor.dirty) "Есть несохранённые изменения" else "Сохранено")
            if (!editorOpen) {
                HandHackCard {
                    Text(
                        text = "Правка всего словаря одним полем тормозит на больших списках, " +
                            "поэтому текст загружается только по запросу.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(HandHackSpacing.sm))
                    OutlinedButton(
                        onClick = { editorOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Открыть редактор")
                    }
                }
            } else {
                DictionaryLineEditor(
                    lines = editor.lines,
                    enabled = !editor.saving,
                    onLineChanged = viewModel::onLineChanged,
                    onLineRemoved = viewModel::onLineRemoved,
                    onLineAdded = { viewModel.onLineAdded() },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm),
                ) {
                    Button(
                        onClick = {
                            viewModel.save()
                            haptics(false)
                        },
                        enabled = state.canSave,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        if (editor.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Сохранить")
                    }
                    OutlinedButton(
                        onClick = viewModel::discard,
                        enabled = editor.dirty && !editor.saving,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text("Отменить")
                    }
                }
            }

            SectionHeader(title = "Предпросмотр", subtitle = "Первые $PREVIEW_LIMIT записей")
            val preview = remember(state.dictionary.entries) {
                state.dictionary.entries.take(PREVIEW_LIMIT)
            }
            // A nested lazy list inside a scrolling column forced the outer scroll to
            // measure it and pinned a fixed 300.dp window that clipped on short screens.
            // Fifty eager rows cost nothing and scroll naturally with the page.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                preview.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(44.dp),
                        )
                        Text(
                            text = entry,
                            style = HandHackTypography.monoBody,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(HandHackSpacing.lg))
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.padding(HandHackSpacing.lg),
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить рабочую копию?") },
            text = { Text("Все записи будут удалены. Оригинал special.txt останется на месте, и его можно восстановить кнопкой «Сброс».") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Восстановить словарь?") },
            text = { Text("Рабочая копия будет заменена исходным special.txt из приложения. Несохранённые изменения будут потеряны.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreDefault()
                    confirmReset = false
                }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }

    if (confirmDiscard) {
        HandHackConfirmDialog(
            title = "Выйти без сохранения?",
            message = "В редакторе есть несохранённые изменения. Они будут потеряны.",
            confirmLabel = "Выйти",
            onConfirm = {
                confirmDiscard = false
                onBack?.invoke()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/**
 * Dictionary editor built as one field per line.
 *
 * A single text field holding every entry re-measured all of them on each keystroke, which
 * cost seconds per frame on a full dictionary. Rendering the lines lazily keeps a keystroke
 * scoped to the row being edited.
 */
@Composable
private fun DictionaryLineEditor(
    lines: List<String>,
    enabled: Boolean,
    onLineChanged: (Int, String) -> Unit,
    onLineRemoved: (Int) -> Unit,
    onLineAdded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(40.dp),
                    )
                    OutlinedTextField(
                        value = line,
                        onValueChange = { onLineChanged(index, it) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = HandHackTypography.monoBody,
                        shape = MaterialTheme.shapes.medium,
                    )
                    FilledTonalIconButton(onClick = { onLineRemoved(index) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Удалить запись ${index + 1}",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(HandHackSpacing.sm))
        OutlinedButton(
            onClick = onLineAdded,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Добавить запись")
        }
    }
}