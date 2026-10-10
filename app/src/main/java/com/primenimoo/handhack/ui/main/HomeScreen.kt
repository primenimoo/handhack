package com.primenimoo.handhack.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.primenimoo.handhack.R
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.core.SuccessSound
import com.primenimoo.handhack.engine.AnalysisStatus
import com.primenimoo.handhack.ui.components.AnalysisProgressBar
import com.primenimoo.handhack.ui.components.HandHackCard
import com.primenimoo.handhack.ui.components.SectionHeader
import com.primenimoo.handhack.ui.components.StatRow
import com.primenimoo.handhack.ui.components.StatTile
import com.primenimoo.handhack.ui.components.StatusChip
import com.primenimoo.handhack.ui.components.copyToClipboard
import com.primenimoo.handhack.ui.components.percentOf
import com.primenimoo.handhack.ui.components.rememberHaptics
import com.primenimoo.handhack.ui.theme.HandHackSpacing
import com.primenimoo.handhack.ui.theme.HandHackTypography
import com.primenimoo.handhack.ui.theme.LocalAnimationsEnabled
import com.primenimoo.handhack.ui.theme.LocalSoundEnabled
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = rememberHaptics()
    val soundEnabled = LocalSoundEnabled.current
    val animations = LocalAnimationsEnabled.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::importFile) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.status) {
        if (state.status == AnalysisStatus.COMPLETED) {
            haptics(true)
            if (soundEnabled && !state.result?.recoveredPasswords.isNullOrEmpty()) {
                SuccessSound.play(context)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                if (animations) {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(140)))
                } else {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                }
            },
            label = "homeStep",
            modifier = Modifier.fillMaxSize(),
        ) { step ->
            when (step) {
                MainStep.IMPORT, MainStep.DETECT, MainStep.VALIDATE -> ImportStep(
                    state = state,
                    onPick = { picker.launch(arrayOf("*/*")) },
                    onSettings = onOpenSettings,
                    onHistory = onOpenHistory,
                    modifier = Modifier.fillMaxSize(),
                )

                MainStep.PREPARE, MainStep.PROGRESS -> ProgressStep(
                    state = state,
                    onStart = viewModel::start,
                    onPause = { if (state.status == AnalysisStatus.PAUSED) viewModel.resume() else viewModel.pause() },
                    onCancel = viewModel::cancel,
                    onReset = viewModel::reset,
                    modifier = Modifier.fillMaxSize(),
                )

                MainStep.RESULT -> ResultStep(
                    state = state,
                    onCopy = { text ->
                        copyToClipboard(context, "HandHack", text)
                        haptics(false)
                    },
                    onReset = viewModel::reset,
                    onPickAgain = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(HandHackSpacing.lg),
        )
    }
}

@Composable
private fun ImportStep(
    state: MainUiState,
    onPick: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HandHackSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(HandHackSpacing.md),
    ) {
        Spacer(Modifier.height(24.dp))

        HandHackCard {
            Column(horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_handhack_logo),
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                    )
                    Spacer(Modifier.width(HandHackSpacing.md))
                    Column {
                        Text("HandHack", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = "Подбор пароля Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(HandHackSpacing.lg))
                Text(
                    text = "Выберите файл с хендшейками вида «ИмяСети:WPA*01…» — HandHack разберёт его, сверит каждую сеть со словарём и покажет найденный пароль.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(HandHackSpacing.lg))
                Button(
                    onClick = onPick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(HandHackSpacing.sm))
                    Text("Выбрать файл")
                }
            }
        }

        SectionHeader(title = "Словарь")
        HandHackCard {
            StatRow(
                label = "Записей в рабочей копии",
                value = FileAccess.formatCount(state.dictionarySize),
                emphasize = true,
            )
            StatRow(label = "Потоков будет использовано", value = state.workers.toString())
            Spacer(Modifier.height(HandHackSpacing.sm))
            Text(
                text = "Для каждой сети из файла HandHack перебирает пароли из словаря и сверяет их с хендшейком, пока не найдёт совпадение.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(HandHackSpacing.xl))
        Text(
            text = "by primenimooo",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = HandHackSpacing.md),
        )
        Spacer(Modifier.height(HandHackSpacing.xl))
    }
}

@Composable
private fun ProgressStep(
    state: MainUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.fileInfo
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HandHackSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(HandHackSpacing.md),
    ) {
        Spacer(Modifier.height(24.dp))

        HandHackCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(HandHackSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info?.fileName ?: "Файл",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = info?.structureNote.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(text = info?.format?.label ?: "—")
            }
            Spacer(Modifier.height(HandHackSpacing.md))
            StatRow(label = "Размер", value = FileAccess.formatSize(info?.sizeBytes ?: -1))
            StatRow(label = "Паролей к проверке", value = FileAccess.formatCount(state.progress.total))
            StatRow(label = "Потоков", value = state.workers.toString())
        }

        HandHackCard {
            Text(
                text = state.status.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(HandHackSpacing.sm))
            AnalysisProgressBar(
                fraction = state.progress.fraction,
                indeterminate = state.progress.indeterminate,
                description = "Обработано ${state.progress.processed} из ${state.progress.total}",
            )
            Spacer(Modifier.height(HandHackSpacing.sm))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = FileAccess.formatCount(state.progress.processed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (state.progress.indeterminate) "—" else percentOf(state.progress.fraction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (state.progress.stageLabel.isNotBlank()) {
                Spacer(Modifier.height(HandHackSpacing.xs))
                Text(
                    text = state.progress.stageLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.isRunning || state.status == AnalysisStatus.PAUSED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm),
            ) {
                FilledTonalButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(if (state.status == AnalysisStatus.PAUSED) "Продолжить" else "Пауза")
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Отменить")
                }
            }
        } else {
            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                if (state.isPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(HandHackSpacing.sm))
                    Text("Подготовка…")
                } else {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(HandHackSpacing.sm))
                    Text("Начать подбор")
                }
            }
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Сбросить")
            }
        }

        if (state.statistics.processed > 0) {
            SectionHeader(title = "Статистика")
            HandHackCard {
                StatRow(label = "Обработано", value = FileAccess.formatCount(state.statistics.processed))
                StatRow(label = "Скорость", value = "${state.statistics.itemsPerSecond.roundToInt()} записей/с")
                StatRow(label = "Прошло", value = FileAccess.formatDuration(state.statistics.elapsedMillis))
                StatRow(
                    label = "Осталось примерно",
                    value = FileAccess.formatDuration(state.statistics.remainingMillis),
                )
            }
        }

        Spacer(Modifier.height(HandHackSpacing.xl))
    }
}

@Composable
private fun ResultStep(
    state: MainUiState,
    onCopy: (String) -> Unit,
    onReset: () -> Unit,
    onPickAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.result
    val noResult = result?.status == AnalysisStatus.NO_RESULT

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HandHackSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(HandHackSpacing.md),
    ) {
        Spacer(Modifier.height(24.dp))

        HandHackCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        noResult -> Icons.Rounded.ErrorOutline
                        result?.status == AnalysisStatus.ERROR -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                    tint = when {
                        noResult -> MaterialTheme.colorScheme.tertiary
                        result?.status == AnalysisStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(HandHackSpacing.sm))
                Text(
                    text = when {
                        result?.status == AnalysisStatus.ERROR -> "Ошибка"
                        noResult -> "Совпадений нет"
                        else -> "Подбор завершён"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(HandHackSpacing.sm))
            Text(
                text = result?.failureReason ?: result?.errorMessage ?: "Файл обработан целиком.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm),
        ) {
            StatTile(
                label = "Обработано",
                value = FileAccess.formatCount(result?.processedItems ?: 0),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Время",
                value = FileAccess.formatDuration(result?.durationMillis ?: 0),
                modifier = Modifier.weight(1f),
            )
        }

        if (result != null && result.recoveredPasswords.isNotEmpty()) {
            SectionHeader(
                title = "Пароль найден",
                subtitle = "Совпадение с хендшейком подтверждено",
            )
            result.recoveredPasswords.forEach { recovered ->
                HandHackCard {
                    Text(
                        text = recovered.ssid,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(HandHackSpacing.xs))
                    Text(
                        text = recovered.password,
                        style = com.primenimoo.handhack.ui.theme.HandHackTypography.monoBody,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(HandHackSpacing.sm))
                    OutlinedButton(
                        onClick = { onCopy(recovered.password) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(HandHackSpacing.xs))
                        Text("Скопировать пароль")
                    }
                }
            }
        }

        if (!noResult && result != null) {
            SectionHeader(title = "Детали", subtitle = "Файл: ${result.fileName}")
            HandHackCard {
                StatRow(label = "Формат", value = result.formatLabel)
                StatRow(label = "Размер", value = FileAccess.formatSize(result.sizeBytes))
                StatRow(label = "Паролей проверено", value = FileAccess.formatCount(result.totalItems))
                StatRow(label = "Потоков", value = result.workersUsed.toString())
            }
        }

        if (result != null && result.matchedEntries.isNotEmpty()) {
            SectionHeader(title = "Найденные пароли", subtitle = "${result.matchedEntries.size}")
            // Plain Column instead of a nested LazyColumn: a lazy list inside a scrolling
            // column forces the outer scroll to measure it anyway, and a fixed 280.dp
            // window clipped the list on short screens. The number of recovered passwords
            // is bounded by the networks in one handshake file, so eager composition is cheap.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(HandHackSpacing.xs),
            ) {
                result.matchedEntries.forEach { entry ->
                    HandHackCard(contentPadding = PaddingValues(HandHackSpacing.md)) {
                        Text(
                            text = entry,
                            style = HandHackTypography.monoBody,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                val summary = buildString {
                    appendLine("HandHack — ${result?.fileName ?: "файл"}")
                    appendLine("Формат: ${result?.formatLabel ?: "—"}")
                    appendLine("Проверено паролей: ${FileAccess.formatCount(result?.processedItems ?: 0)}")
                    appendLine("Время: ${FileAccess.formatDuration(result?.durationMillis ?: 0)}")
                    result?.failureReason?.let { appendLine("Итог: $it") }
                    if (result != null && result.matchedEntries.isNotEmpty()) {
                        appendLine()
                        appendLine("Совпадения:")
                        result.matchedEntries.forEach { appendLine("• $it") }
                    }
                }
                onCopy(summary)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Rounded.Send, contentDescription = null)
            Spacer(Modifier.width(HandHackSpacing.sm))
            Text("Скопировать результат")
        }

        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Новый подбор")
        }

        Spacer(Modifier.height(HandHackSpacing.xl))
    }
}