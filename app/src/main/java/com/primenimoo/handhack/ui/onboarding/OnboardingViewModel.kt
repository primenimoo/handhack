package com.primenimoo.handhack.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.primenimoo.handhack.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class OnboardingPage(
    val title: String,
    val body: String,
    val bullets: List<String>,
)

val onboardingPages = listOf(
    OnboardingPage(
        title = "HandHack",
        body = "Подбор пароля Wi-Fi по хендшейкам.",
        bullets = listOf("Разбор хендшейков", "Проверка по словарю", "Реальная статистика подбора"),
    ),
    OnboardingPage(
        title = "Как это работает",
        body = "Вы загружаете файл с хендшейками, HandHack разбирает его и сверяет каждую запись со словарём паролей.",
        bullets = listOf("Определение формата", "Проверка и подготовка", "Подбор с реальным прогрессом"),
    ),
    OnboardingPage(
        title = "Словарь",
        body = "В комплекте есть список special.txt. Его можно править, импортировать и экспортировать — оригинал останется нетронутым.",
        bullets = listOf("Рабочая копия отдельно от оригинала", "Сброс к исходному словарю"),
    ),
    OnboardingPage(
        title = "Скорость подбора",
        body = "Чем больше словарь и число потоков, тем быстрее идёт подбор. Настройки потоков и оформления — внизу экрана.",
        bullets = listOf("До 32 потоков", "Экономный и максимальный режимы", "Пауза и остановка в любой момент"),
    ),
)

class OnboardingViewModel(
    application: Application,
    private val repository: SettingsRepository,
) : AndroidViewModel(application) {

    private val _page = MutableStateFlow(0)
    val page: StateFlow<Int> = _page.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = repository.settings.first()
            if (settings.onboardingPage in onboardingPages.indices) {
                _page.value = settings.onboardingPage
            }
        }
    }

    val totalPages: Int get() = onboardingPages.size

    fun next() {
        val nextPage = (_page.value + 1).coerceAtMost(onboardingPages.lastIndex)
        _page.value = nextPage
        viewModelScope.launch { repository.setOnboardingPage(nextPage) }
    }

    fun back() {
        val previous = (_page.value - 1).coerceAtLeast(0)
        _page.value = previous
        viewModelScope.launch { repository.setOnboardingPage(previous) }
    }

    fun finish() {
        viewModelScope.launch { repository.completeOnboarding(onboardingPages.lastIndex) }
    }
}