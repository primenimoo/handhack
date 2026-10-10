package com.primenimoo.handhack.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.primenimoo.handhack.ui.navigation.Routes
import com.primenimoo.handhack.ui.theme.HandHackSpacing
import com.primenimoo.handhack.ui.theme.LocalAnimationsEnabled
import com.primenimoo.handhack.ui.theme.NavCapsuleRadius

/** Destinations reachable from the floating capsule bar. */
enum class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Главная", Icons.Rounded.Home),
    DICTIONARY(Routes.DICTIONARY, "Словарь", Icons.Rounded.Style),
    HISTORY(Routes.HISTORY, "История", Icons.Rounded.History),
    SETTINGS(Routes.SETTINGS, "Настройки", Icons.Rounded.Settings),
    ABOUT(Routes.ABOUT, "О программе", Icons.Rounded.Info),
}

/**
 * Floating capsule navigation bar pinned to the bottom edge. Every destination lives
 * here, so no screen has to be reached through a buried link.
 */
@Composable
fun CapsuleNavigationBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animations = LocalAnimationsEnabled.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = HandHackSpacing.sm, vertical = HandHackSpacing.sm),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(NavCapsuleRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Основная навигация" },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MainTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    val indicatorScale by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0f,
                        animationSpec = tween(if (animations) 220 else 0),
                        label = "indicator_${tab.route}",
                    )
                    val interaction = remember(tab) { MutableInteractionSource() }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = isSelected,
                                onClick = { onSelect(tab) },
                                role = Role.Tab,
                                interactionSource = interaction,
                                indication = null,
                            )
                            .semantics {
                                contentDescription = tab.label
                                stateDescription = if (isSelected) "Выбрано" else "Не выбрано"
                            },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(NavCapsuleRadius - 4.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = indicatorScale.coerceIn(0f, 1f))
                        } else {
                            Color.Transparent
                        },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}