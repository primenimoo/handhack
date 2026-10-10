package com.primenimoo.handhack

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.primenimoo.handhack.data.settings.HandHackSettings
import com.primenimoo.handhack.engine.AnalysisStatus
import com.primenimoo.handhack.ui.bootstrap.BootstrapScreen
import com.primenimoo.handhack.ui.navigation.HandHackNavHost
import com.primenimoo.handhack.ui.notifications.AnalysisNotifier
import com.primenimoo.handhack.ui.theme.HandHackTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val handHack: HandHackApplication
        get() = application as HandHackApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val application = handHack
        val notifier = application.notifier

        // The system splash is held until both the settings snapshot and the data the
        // screens depend on are available, so the app never flashes an empty home screen
        // while the dictionary is still being prepared.
        var settingsReady = false
        splash.setKeepOnScreenCondition { !settingsReady }

        lifecycleScope.launch {
            var bootstrapObserved = false
            application.bootstrap.collect { state ->
                if (state.isReady && !bootstrapObserved) {
                    bootstrapObserved = true
                    settingsReady = true
                }
            }
        }

        lifecycleScope.launch {
            application.settingsRepository.settings.collect { settings ->
                applyKeepScreenOn(settings.keepScreenOn)
                render(settings)
                notifier.syncSettings(settings)
            }
        }

        observeAnalysis(application, notifier)
    }

    /**
     * The ongoing progress notification and the terminal notification are driven by the
     * engine itself, so they stay correct even if the user leaves the screen.
     */
    private fun observeAnalysis(application: HandHackApplication, notifier: AnalysisNotifier) {
        val engine = application.analysisEngine
        lifecycleScope.launch {
            engine.progress.collect { progress ->
                if (engine.status.value == AnalysisStatus.RUNNING) {
                    val fileName = engine.currentRequest.value?.fileName.orEmpty()
                    notifier.showProgress(fileName, progress.processed, progress.total)
                }
            }
        }
        lifecycleScope.launch {
            engine.result.collect { result ->
                if (result != null && result.status.isTerminal) {
                    notifier.showResult(result)
                }
            }
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private var contentRendered = false

    private fun render(settings: HandHackSettings) {
        if (contentRendered) return
        contentRendered = true
        val repository = handHack.settingsRepository
        val bootstrap = handHack.bootstrap
        setContent {
            val current by repository.settings.collectAsStateWithLifecycle(initialValue = settings)
            val startup by bootstrap.collectAsStateWithLifecycle()

            HandHackTheme(
                themeMode = current.themeMode,
                accentSeed = current.accentSeed,
                useDynamicColor = current.dynamicColor,
                animationsEnabled = current.animationsEnabled,
                hapticsEnabled = current.hapticsEnabled,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // The startup screen owns the frame until the data is ready; only then
                    // is the navigation graph composed at all, which also keeps the heavy
                    // dictionary work off the first interactive frame.
                    if (startup.isReady) {
                        HandHackNavHost(
                            settings = current,
                            navController = rememberNavController(),
                        )
                    } else {
                        BootstrapScreen(state = startup)
                    }
                }
            }
        }
    }
}