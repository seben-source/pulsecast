package app.pulsecast

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import app.pulsecast.ads.AdsSetup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulsecast.ui.DarkPalette
import app.pulsecast.ui.FeedScreen
import app.pulsecast.ui.FeedViewModel
import app.pulsecast.ui.LightPalette
import app.pulsecast.ui.LocalPalette
import app.pulsecast.ui.ThemeMode
import app.pulsecast.work.FeedCheckWorker
import app.pulsecast.work.Notifier

class MainActivity : ComponentActivity() {
    private val vm: FeedViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status bar and navigation bar icons follow the chosen theme.
            LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val palette = if (dark) DarkPalette else LightPalette
            CompositionLocalProvider(LocalPalette provides palette) {
                MaterialTheme(
                    colorScheme = if (dark) {
                        darkColorScheme(primary = palette.accent, background = palette.bg, surface = palette.card, onSurface = palette.text1)
                    } else {
                        lightColorScheme(primary = palette.accent, background = palette.bg, surface = palette.card, onSurface = palette.text1)
                    },
                ) {
                    Box(Modifier.fillMaxSize().background(palette.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
                        FeedScreen(vm)
                    }
                }
            }
        }
        AdsSetup.start(this) { runOnUiThread { vm.adsReady() } }
        FeedCheckWorker.schedule(this)
        // Ask once for notifications so the morning briefing can arrive (Android 13+).
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this) && !prefs.getBoolean("askedNotify", false)) {
            prefs.edit().putBoolean("askedNotify", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStop() {
        super.onStop()
        vm.markVisited()
    }
}
