package com.primenimoo.handhack.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.primenimoo.handhack.data.settings.PerformanceMode
import com.primenimoo.handhack.data.settings.ThemeMode
import com.primenimoo.handhack.data.settings.WorkerCountMode
import com.primenimoo.handhack.ui.components.HandHackCard
import com.primenimoo.handhack.ui.components.HandHackConfirmDialog
import com.primenimoo.handhack.ui.components.HandHackTopBar
import com.primenimoo.handhack.ui.components.SectionHeader
import com.primenimoo.handhack.ui.components.StatRow
import com.primenimoo.handhack.ui.theme.AccentPreset
import com.primenimoo.handhack.ui.theme.HandHackSpacing
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenHistory: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val workers = viewModel.effectiveWorkers(settings)
    var confirmClearHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        HandHackTopBar(title = "Настройки", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HandHackSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(HandHackSpacing.md),
        ) {
            SectionHeader(title = "Оформление")
            HandHackCard {
                Text("Тема", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(HandHackSpacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm)) {
                    ThemeMode.entries.forEach { mode ->
                        ChoicePill(
                            text = mode.label,
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(HandHackSpacing.lg))

                Text("Акцентный цвет", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(HandHackSpacing.sm))
                AccentGrid(
                    selected = settings.accentPreset,
                    onSelect = viewModel::setAccent,
                )

                Spacer(Modifier.height(HandHackSpacing.lg))
                ToggleRow(
                    title = "Dynamic Color",
                    subtitle = "Использовать цвета системы, если доступно (Android 12+)",
                    checked = settings.dynamicColor,
                    onChange = viewModel::setDynamicColor,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                ToggleRow(
                    title = "Анимации",
                    subtitle = "Переходы и плавные изменения",
                    checked = settings.animationsEnabled,
                    onChange = viewModel::setAnimations,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                ToggleRow(
                    title = "Тактильный отклик",
                    subtitle = "Лёгкая вибрация при действиях",
                    checked = settings.hapticsEnabled,
                    onChange = viewModel::setHaptics,
                )
            }

            SectionHeader(title = "Производительность")
            HandHackCard {
                PerformanceMode.entries.forEach { mode ->
                    ChoiceRow(
                        title = mode.label,
                        subtitle = mode.description,
                        selected = settings.performanceMode == mode,
                        onClick = { viewModel.setPerformance(mode) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                StatRow(label = "Ядер в устройстве", value = viewModel.coreCount().toString())
                StatRow(label = "Будет использовано потоков", value = workers.toString(), emphasize = true)

                Spacer(Modifier.height(HandHackSpacing.sm))
                WorkerCountSection(
                    mode = settings.workerCountMode,
                    manualCount = settings.manualWorkerCount,
                    maxWorkers = viewModel.coreCount().coerceAtLeast(1),
                    onModeChange = viewModel::setWorkerMode,
                    onCountChange = viewModel::setWorkerCount,
                )
            }

            SectionHeader(title = "Интерфейс")
            HandHackCard {
                ToggleRow(
                    title = "Не гасить экран",
                    subtitle = "Во время подбора и подготовки",
                    checked = settings.keepScreenOn,
                    onChange = viewModel::setKeepScreenOn,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                ToggleRow(
                    title = "Автосохранение словаря",
                    subtitle = "Сохранять изменения сразу после выхода из редактора",
                    checked = settings.autoSaveDictionary,
                    onChange = viewModel::setAutoSave,
                )
            }

            SectionHeader(title = "Уведомления")
            HandHackCard {
                ToggleRow(
                    title = "Завершение подбора",
                    checked = settings.notifyProcessCompleted,
                    onChange = viewModel::setNotifyCompleted,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                ToggleRow(
                    title = "Ошибки",
                    checked = settings.notifyErrors,
                    onChange = viewModel::setNotifyErrors,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                ToggleRow(
                    title = "Импорт файлов",
                    checked = settings.notifyImportCompleted,
                    onChange = viewModel::setNotifyImport,
                )
            }

            SectionHeader(title = "Данные")
            HandHackCard {
                StatRow(label = "Последний импорт", value = settings.lastImportLabel)
                StatRow(label = "Последний экспорт", value = settings.lastExportLabel)
                StatRow(label = "Записей в истории", value = viewModel.historyCount().toString())
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                LinkRow("История подборов", onOpenHistory)
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                LinkRow("О приложении", onOpenAbout)
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                LinkRow("Показать обучение снова", onOpenOnboarding)
                HorizontalDivider(modifier = Modifier.padding(vertical = HandHackSpacing.sm))
                LinkRow("Очистить историю") { confirmClearHistory = true }
            }

            Spacer(Modifier.height(HandHackSpacing.lg))
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.padding(HandHackSpacing.lg),
        )
    }

    if (confirmClearHistory) {
        HandHackConfirmDialog(
            title = "Очистить историю?",
            message = "Все завершённые подборы будут удалены безвозвратно. Настройки и словарь не затрагиваются.",
            confirmLabel = "Очистить",
            onConfirm = {
                confirmClearHistory = false
                viewModel.clearHistory()
                scope.launch {
                    snackbarHost.showSnackbar("История очищена")
                }
            },
            onDismiss = { confirmClearHistory = false },
        )
    }
}

@Composable
private fun WorkerCountSection(
    mode: WorkerCountMode,
    manualCount: Int,
    maxWorkers: Int,
    onModeChange: (WorkerCountMode) -> Unit,
    onCountChange: (Int) -> Unit,
) {
    Text("Потоки", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(HandHackSpacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm)) {
        WorkerCountMode.entries.forEach { option ->
            ChoicePill(
                text = option.label,
                selected = mode == option,
                onClick = { onModeChange(option) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (mode == WorkerCountMode.LIMITED) {
        Spacer(Modifier.height(HandHackSpacing.sm))
        Text(
            text = "Потоков: $manualCount",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { stateDescription = "$manualCount потоков" },
        )
        Slider(
            value = manualCount.toFloat(),
            onValueChange = { onCountChange(it.toInt()) },
            valueRange = 1f..maxWorkers.coerceAtLeast(1).toFloat(),
            steps = (maxWorkers - 1).coerceAtLeast(0),
            modifier = Modifier.semantics { contentDescription = "Количество потоков" },
        )
    }
}

@Composable
private fun AccentGrid(selected: AccentPreset, onSelect: (AccentPreset) -> Unit) {
    AccentPreset.entries.chunked(5).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HandHackSpacing.sm),
        ) {
            row.forEach { preset ->
                val isSelected = preset == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .background(preset.seed, CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onSelect(preset) }
                        .semantics {
                            role = Role.RadioButton
                            contentDescription = preset.label
                            stateDescription = if (isSelected) "Выбрано" else "Не выбрано"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(HandHackSpacing.sm))
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp)
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "Включено" else "Выключено"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(HandHackSpacing.md))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics {
                role = Role.RadioButton
                stateDescription = if (selected) "Выбрано" else "Не выбрано"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                stateDescription = if (selected) "Выбрано" else "Не выбрано"
            }
            .padding(horizontal = HandHackSpacing.sm, vertical = HandHackSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}