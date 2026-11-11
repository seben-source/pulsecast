package app.pulsecast.stats

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Anonymous usage numbers and crash reports through Google Firebase.
 *
 * Firebase only switches on once app/google-services.json is added (see the
 * README). Until then every call here quietly does nothing, so the app builds
 * and runs either way.
 */
object Stats {
    private var analytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        analytics = runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull()
        runCatching { FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true) }
            .onFailure { Log.i("PulseCastStats", "Firebase not configured; stats off") }
    }

    fun event(name: String, vararg params: Pair<String, Any?>) {
        val a = analytics ?: return
        runCatching {
            val b = Bundle()
            for ((k, v) in params) when (v) {
                is Int -> b.putLong(k, v.toLong())
                is Long -> b.putLong(k, v)
                is Double -> b.putDouble(k, v)
                null -> Unit
                else -> b.putString(k, v.toString().take(100))
            }
            a.logEvent(name, b)
        }
    }

    /** Non-fatal problems worth knowing about (e.g. the feed failing to load). */
    fun problem(e: Throwable) {
        runCatching { FirebaseCrashlytics.getInstance().recordException(e) }
    }
}
