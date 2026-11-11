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
import app.pulsecast.ui.Accent
import app.pulsecast.ui.CardBg
import app.pulsecast.ui.FeedScreen
import app.pulsecast.ui.FeedViewModel
import app.pulsecast.ui.Ink
import app.pulsecast.ui.Text1
import app.pulsecast.work.FeedCheckWorker
import app.pulsecast.work.Notifier

class MainActivity : ComponentActivity() {
    private val vm: FeedViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = CardBg, onSurface = Text1)) {
                Box(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    FeedScreen(vm)
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
