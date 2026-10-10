package com.primenimoo.handhack.ui.about

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.primenimoo.handhack.BuildConfig
import com.primenimoo.handhack.R
import com.primenimoo.handhack.ui.components.HandHackCard
import com.primenimoo.handhack.ui.components.HandHackTopBar
import com.primenimoo.handhack.ui.components.SectionHeader
import com.primenimoo.handhack.ui.components.StatRow
import com.primenimoo.handhack.ui.components.openExternalUri
import com.primenimoo.handhack.ui.theme.HandHackSpacing

@Composable
fun AboutScreen(
    versionName: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val telegramUrl = context.getString(R.string.telegram_url)

    Column(modifier = modifier.fillMaxSize()) {
        HandHackTopBar(title = "О приложении", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HandHackSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(HandHackSpacing.md),
        ) {
            HandHackCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_handhack_logo),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.width(HandHackSpacing.md))
                    Column {
                        Text("HandHack", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = "Версия $versionName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(HandHackSpacing.lg))
                Text(
                    text = "HandHack подбирает пароль Wi-Fi по перехваченным хендшейкам. Загрузите файл вида «ИмяСети:WPA*01…» — программа разберёт записи, сверит каждую сеть со словарём паролей и покажет найденный пароль вместе с реальной статистикой подбора.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(title = "Разработчик")
            HandHackCard {
                StatRow(label = "Автор", value = "@primenimooo")
                Spacer(Modifier.height(HandHackSpacing.md))
                Button(
                    onClick = { openExternalUri(context, android.net.Uri.parse(telegramUrl)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    androidx.compose.material3.Icon(Icons.Rounded.Send, contentDescription = null)
                    Spacer(Modifier.width(HandHackSpacing.sm))
                    Text("Перейти в канал")
                }
            }

            SectionHeader(title = "Как это работает")
            HandHackCard {
                Text(
                    text = "Каждая запись из файла хендшейков проверяется по словарю паролей. Как только находится совпадение, оно выводится как результат. Скорость подбора зависит от размера словаря и числа потоков — их можно изменить в настройках.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(HandHackSpacing.lg))
        }
    }
}