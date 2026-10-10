package com.primenimoo.handhack.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.primenimoo.handhack.HandHackApplication
import com.primenimoo.handhack.data.settings.HandHackSettings
import com.primenimoo.handhack.ui.about.AboutScreen
import com.primenimoo.handhack.ui.components.CapsuleNavigationBar
import com.primenimoo.handhack.ui.components.BrandHeader
import com.primenimoo.handhack.ui.components.MainTab
import com.primenimoo.handhack.ui.dictionary.DictionaryScreen
import com.primenimoo.handhack.ui.dictionary.DictionaryViewModel
import com.primenimoo.handhack.ui.history.HistoryScreen
import com.primenimoo.handhack.ui.history.HistoryViewModel
import com.primenimoo.handhack.ui.main.HomeScreen
import com.primenimoo.handhack.ui.main.MainViewModel
import com.primenimoo.handhack.ui.onboarding.OnboardingScreen
import com.primenimoo.handhack.ui.onboarding.OnboardingViewModel
import com.primenimoo.handhack.ui.settings.SettingsScreen
import com.primenimoo.handhack.ui.settings.SettingsViewModel
import com.primenimoo.handhack.ui.theme.LocalAnimationsEnabled

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val DICTIONARY = "dictionary"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val ABOUT = "about"
}

/** Space kept free at the bottom so the floating capsule never covers content. */
private val NavBarReserve = 88.dp

@Composable
fun HandHackNavHost(
    settings: HandHackSettings,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val application = LocalContext.current.applicationContext as HandHackApplication
    val animations = LocalAnimationsEnabled.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val duration = if (animations) 240 else 0
    val tabRoutes = remember { MainTab.entries.map { it.route }.toSet() }
    val isTabRoute = currentRoute != null && currentRoute in tabRoutes
    val selectedTab = MainTab.entries.firstOrNull { it.route == currentRoute } ?: MainTab.HOME

    val mainViewModel: MainViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                MainViewModel(
                    application = application,
                    engine = application.analysisEngine,
                    dictionaryRepository = application.dictionaryRepository,
                    historyRepository = application.historyRepository,
                    settingsRepository = application.settingsRepository,
                )
            }
        },
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(application, application.settingsRepository, application.historyRepository)
            }
        },
    )
    val startDestination = if (settings.onboardingCompleted) Routes.HOME else Routes.ONBOARDING

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = NavBarReserve),
                enterTransition = {
                    fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 12 }
                },
                exitTransition = { fadeOut(tween(if (animations) 160 else 0)) },
                popEnterTransition = { fadeIn(tween(duration)) },
                popExitTransition = {
                    fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { it / 12 }
                },
            ) {
                composable(Routes.ONBOARDING) {
                    // Created per destination so the startup frame does not pay for
                    // view models the user may never open.
                    val onboardingViewModel: OnboardingViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { OnboardingViewModel(application, application.settingsRepository) }
                        },
                    )
                    OnboardingScreen(
                        viewModel = onboardingViewModel,
                        onFinished = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel = mainViewModel,
                        onOpenHistory = { navController.navigate(Routes.HISTORY) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }

                composable(Routes.DICTIONARY) {
                    val dictionaryViewModel: DictionaryViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { DictionaryViewModel(application, application.dictionaryRepository) }
                        },
                    )
                    DictionaryScreen(
                        viewModel = dictionaryViewModel,
                        onBack = null,
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onBack = null,
                        onOpenHistory = { navController.navigate(Routes.HISTORY) { launchSingleTop = true } },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) { launchSingleTop = true } },
                        onOpenOnboarding = { navController.navigate(Routes.ONBOARDING) },
                    )
                }

                composable(Routes.HISTORY) {
                    val historyViewModel: HistoryViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { HistoryViewModel(application, application.historyRepository) }
                        },
                    )
                    HistoryScreen(viewModel = historyViewModel, onBack = null)
                }

                composable(Routes.ABOUT) {
                    AboutScreen(
                        versionName = settingsViewModel.versionName,
                        onBack = null,
                    )
                }
            }

if (isTabRoute) {
                    // Without an explicit alignment the parent Box would stack this at
                    // TopStart, which pinned the bar over the header instead of the bottom.
                    CapsuleNavigationBar(
                        selected = selectedTab,
                        onSelect = { tab ->
                            if (tab.route != currentRoute) {
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun viewModelFactory(create: () -> androidx.lifecycle.ViewModel): androidx.lifecycle.ViewModelProvider.Factory =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer { create() }
    }