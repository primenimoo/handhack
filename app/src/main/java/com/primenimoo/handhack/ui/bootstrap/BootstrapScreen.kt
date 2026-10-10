package com.primenimoo.handhack.ui.bootstrap

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.primenimoo.handhack.BootstrapPhase
import com.primenimoo.handhack.BootstrapState
import com.primenimoo.handhack.R
import com.primenimoo.handhack.ui.components.AnalysisProgressBar
import com.primenimoo.handhack.ui.components.percentOf
import com.primenimoo.handhack.ui.theme.HandHackSpacing
import com.primenimoo.handhack.ui.theme.HandHackTheme
import com.primenimoo.handhack.ui.theme.HandHackTypography

/**
 * Full-screen startup screen shown while HandHack prepares its data.
 *
 * Adaptive by construction: the column is width-bounded so it never stretches into
 * unreadable line lengths on a tablet, height-bounded and scrollable so a short screen
 * clips nothing, and the whole thing centres without relying on fixed offsets.
 */
@Composable
fun BootstrapScreen(
    state: BootstrapState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(HandHackSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_handhack_logo),
                contentDescription = null,
                modifier = Modifier.sizeIn(maxWidth = 120.dp, maxHeight = 120.dp),
            )
            Spacer(Modifier.height(HandHackSpacing.lg))
            Text(
                text = "HandHack",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(HandHackSpacing.xs))
            Text(
                text = "Подбор пароля Wi-Fi по хендшейку",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(HandHackSpacing.xl))
            AnalysisProgressBar(
                fraction = state.progress.coerceIn(0f, 1f),
                indeterminate = !state.isReady && state.progress <= 0f,
                modifier = Modifier.fillMaxWidth(),
                description = "Загрузка: ${percentOf(state.progress.coerceIn(0f, 1f))}",
            )
            Spacer(Modifier.height(HandHackSpacing.sm))
            Text(
                text = "${state.label} · ${percentOf(state.progress.coerceIn(0f, 1f))}",
                style = HandHackTypography.statValue,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = state.label },
            )
            Spacer(Modifier.height(HandHackSpacing.sm))
            Text(
                text = statusLine(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            state.failureMessage?.let { message ->
                Spacer(Modifier.height(HandHackSpacing.md))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One concrete line of detail, so the wait is never unexplained. */
private fun statusLine(state: BootstrapState): String = when (state.phase) {
    BootstrapPhase.CheckingCache -> "Проверяем, что уже подготовлено"
    BootstrapPhase.PreparingDictionary ->
        if (state.fromCache) "Читаем подготовленный словарь" else "Копируем встроенный словарь"
    BootstrapPhase.LoadingCache -> "Читаем подготовленный словарь"
    BootstrapPhase.LoadingHistory -> "Восстанавливаем историю подборов"
    BootstrapPhase.Ready -> when {
        state.failureMessage != null -> "Приложение откроется с ограничениями"
        state.dictionaryEntries > 0L -> buildString {
            append("В словаре ${state.dictionaryEntries} записей")
            if (state.historyEntries > 0) append(" · история ${state.historyEntries}")
        }
        else -> "Словарь пока пуст — импортируйте записи"
    }
}

@Preview(showBackground = true)
@Composable
private fun BootstrapScreenPreview() {
    HandHackTheme {
        BootstrapScreen(
            state = BootstrapState(
                phase = BootstrapPhase.LoadingHistory,
                progress = 0.75f,
                fromCache = true,
                dictionaryEntries = 13_846,
            ),
        )
    }
}