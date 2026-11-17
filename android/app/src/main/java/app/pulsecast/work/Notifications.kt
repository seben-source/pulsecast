package app.pulsecast.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pulsecast.BuildConfig
import app.pulsecast.MainActivity
import app.pulsecast.R
import app.pulsecast.data.Card
import app.pulsecast.data.FeedRepository
import app.pulsecast.data.Follows
import app.pulsecast.data.Habits
import app.pulsecast.data.ProStore
import app.pulsecast.data.WeeklyRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks the feed every 2 hours (Android picks the exact moment).
 * - Morning briefing: once a day, from 7am, the top 5 stories of the last day.
 * - Breaking: a story with very high heat that is under 6 hours old, at most
 *   once every 2 hours, never between 10pm and 7am.
 */
class FeedCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val habits = Habits(applicationContext)
        val pro = ProStore(applicationContext)
        if (!habits.briefingEnabled && !habits.breakingEnabled && !pro.isPro) return@withContext Result.success()
        val feed = try {
            FeedRepository(applicationContext, BuildConfig.FEED_URL).refresh()
        } catch (_: Exception) {
            return@withContext Result.retry()
        }
        val now = ZonedDateTime.now()
        val quiet = now.toLocalTime().let { it.isBefore(LocalTime.of(7, 0)) || it.isAfter(LocalTime.of(22, 0)) }
        val today = LocalDate.now().toString()

        if (habits.briefingEnabled && !quiet && habits.lastBriefingDay != today) {
            val dayAgo = Instant.now().minus(Duration.ofHours(24))
            val top = feed.cards.filter { it.publishedAt.isAfter(dayAgo) }.sortedByDescending { it.heat }.take(5)
            if (top.size >= 3) {
                Notifier.post(
                    applicationContext, 1, "Your AI briefing",
                    "${top.size} things that happened in AI today",
                    top.joinToString("\n") { "• ${it.headline}" },
                )
                habits.lastBriefingDay = today
            }
        }

        if (habits.breakingEnabled && !quiet) {
            val sixHoursAgo = Instant.now().minus(Duration.ofHours(6))
            val breaking: Card? = feed.cards
                .filter { it.heat >= BREAKING_HEAT && it.publishedAt.isAfter(sixHoursAgo) }
                .maxByOrNull { it.heat }
            if (breaking != null && breaking.id != habits.lastBreakingId) {
                Notifier.post(applicationContext, 2, breaking.headline, breaking.whyItMatters.ifBlank { breaking.summary }, breaking.summary)
                habits.lastBreakingId = breaking.id
            }
        }

        // Prompt Pro: the Sunday deep-dive, once per issue.
        if (pro.isPro && !quiet) {
            val issue = runCatching { WeeklyRepository(applicationContext).refresh().firstOrNull() }.getOrNull()
            if (issue != null && issue.weekOf != pro.lastWeeklyNotified &&
                Duration.between(issue.publishedAt, Instant.now()).toDays() < 2
            ) {
                Notifier.post(applicationContext, 3, "Your weekly AI deep-dive", issue.title, "${issue.title}\n\n${issue.intro}")
                pro.lastWeeklyNotified = issue.weekOf
            }
        }
        Result.success()
    }

    companion object {
        const val BREAKING_HEAT = 85
        private const val NAME = "pulsecast-feed-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FeedCheckWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * Prompt Pro: checks every 15 minutes (Android's shortest interval) for new
 * stories matching what the reader follows. Only scheduled while needed.
 * Stories that arrive at night wait until 7am.
 */
class FollowAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pro = ProStore(applicationContext)
        val follows = pro.follows
        if (!pro.isPro || !pro.followAlerts || follows.isEmpty()) return@withContext Result.success()
        val now = LocalTime.now()
        if (now.isBefore(LocalTime.of(7, 0)) || now.isAfter(LocalTime.of(22, 0))) return@withContext Result.success()
        val feed = try {
            FeedRepository(applicationContext, BuildConfig.FEED_URL).refresh()
        } catch (_: Exception) {
            return@withContext Result.success() // try again in 15 minutes
        }
        val dayAgo = Instant.now().minus(Duration.ofHours(24))
        val seen = pro.notifiedIds
        val fresh = feed.cards.filter { it.id !in seen && it.publishedAt.isAfter(dayAgo) }
        val hits = fresh.mapNotNull { card -> follows.firstOrNull { Follows.matches(card, it) }?.let { card to it } }
        if (hits.isEmpty()) return@withContext Result.success()
        if (hits.size <= 3) {
            for ((card, term) in hits) {
                Notifier.post(
                    applicationContext, 1000 + (card.id.hashCode() and 0xFFFF), card.headline, "Following: $term", "${card.summary}\n\nFollowing: $term",
                    channel = Notifier.FOLLOW_CHANNEL,
                )
            }
        } else {
            Notifier.post(
                applicationContext, 999, "${hits.size} new stories you follow",
                hits.map { it.second }.distinct().joinToString(", "),
                hits.take(6).joinToString("\n") { "• ${it.first.headline}" },
                channel = Notifier.FOLLOW_CHANNEL,
            )
        }
        pro.notifiedIds = seen + hits.map { it.first.id }
        Result.success()
    }

    companion object {
        private const val NAME = "prompt-follow-alerts"

        fun sync(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<FollowAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

object Notifier {
    private const val CHANNEL = "briefing"
    const val FOLLOW_CHANNEL = "follows"

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, title: String, text: String, big: String, channel: String = CHANNEL) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            if (channel == FOLLOW_CHANNEL) NotificationChannel(FOLLOW_CHANNEL, "Stories you follow", NotificationManager.IMPORTANCE_DEFAULT)
            else NotificationChannel(CHANNEL, "AI briefing", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
