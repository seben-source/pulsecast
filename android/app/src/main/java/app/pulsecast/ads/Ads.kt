package app.pulsecast.ads

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.pulsecast.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Consent first (Google shows its form only where the law requires it, e.g. EU/UK),
 * then the Mobile Ads SDK starts. Ads are only requested after that.
 */
object AdsSetup {
    private val started = AtomicBoolean(false)

    fun start(activity: Activity, onReady: () -> Unit) {
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "Consent form: ${error.message}")
                    if (consent.canRequestAds()) init(activity, onReady)
                }
            },
            { error -> Log.w(TAG, "Consent info: ${error.message}") },
        )
        // A returning user who already answered can get ads straight away.
        if (consent.canRequestAds()) init(activity, onReady)
    }

    /** Shown in Settings when the law requires a way to change ad choices. */
    fun privacyOptionsRequired(activity: Activity) =
        UserMessagingPlatform.getConsentInformation(activity).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) =
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { }

    private fun init(context: Context, onReady: () -> Unit) {
        if (!started.compareAndSet(false, true)) return
        Thread { MobileAds.initialize(context) { onReady() } }.start()
    }

    const val TAG = "PulseCastAds"
}

/**
 * Keeps a few native ads loaded so a sponsored post is ready when the reader
 * scrolls to an ad slot. Slot n always gets the same ad, so it doesn't change
 * while on screen.
 */
class NativeAdPool(private val context: Context, private val onChange: () -> Unit) {
    private val ads = ArrayList<NativeAd>()
    private val bySlot = HashMap<Int, NativeAd>()
    private var loading = false
    private var ready = false

    fun start() {
        ready = true
        fill()
    }

    fun adFor(slot: Int): NativeAd? {
        bySlot[slot]?.let { return it }
        if (ads.isEmpty()) {
            fill()
            return null
        }
        val ad = ads.removeAt(0)
        bySlot[slot] = ad
        if (ads.size < 2) fill()
        return ad
    }

    private fun fill() {
        if (!ready || loading || ads.size >= 3) return
        loading = true
        lateinit var loader: AdLoader
        loader = AdLoader.Builder(context, BuildConfig.NATIVE_AD_UNIT_ID)
            .forNativeAd { ad ->
                ads.add(ad)
                if (!loader.isLoading) {
                    loading = false
                    onChange()
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(AdsSetup.TAG, "Native ad failed: ${error.message}")
                    if (!loader.isLoading) loading = false
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build(),
            )
            .build()
        loader.loadAds(AdRequest.Builder().build(), 3)
    }

    fun destroy() {
        ads.forEach { it.destroy() }
        bySlot.values.forEach { it.destroy() }
        ads.clear()
        bySlot.clear()
    }
}

/**
 * A native ad laid out like a PulseCast post: same card, same type, with a
 * clear "Sponsored" label (required by AdMob policy) and the advertiser's
 * own image or video, headline, text and button.
 */
object SponsoredPostView {
    private val INK = Color.parseColor("#F2F2EE")
    private val MUTED = Color.parseColor("#9A9C98")
    private val CARD = Color.parseColor("#181B1E")
    private val ACCENT = Color.parseColor("#B7F36B")

    fun create(context: Context): NativeAdView {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val adView = NativeAdView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat() }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) } }
        val nameCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val advertiser = TextView(context).apply { setTextColor(INK); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        val label = TextView(context).apply { text = "Sponsored"; setTextColor(MUTED); textSize = 12f }
        nameCol.addView(advertiser)
        nameCol.addView(label)
        header.addView(icon)
        header.addView(nameCol)
        val headline = TextView(context).apply { setTextColor(INK); textSize = 19f; typeface = Typeface.SERIF; setPadding(0, dp(12), 0, dp(6)) }
        val body = TextView(context).apply { setTextColor(MUTED); textSize = 14f; setLineSpacing(0f, 1.2f) }
        val media = MediaView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(12) }
        }
        val cta = Button(context).apply {
            setTextColor(Color.parseColor("#0F1113"))
            isAllCaps = false
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(22).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) }
        }
        column.addView(header)
        column.addView(headline)
        column.addView(body)
        column.addView(media)
        column.addView(cta)
        adView.addView(column)
        adView.iconView = icon
        adView.advertiserView = advertiser
        adView.headlineView = headline
        adView.bodyView = body
        adView.mediaView = media
        adView.callToActionView = cta
        return adView
    }

    fun bind(view: NativeAdView, ad: NativeAd) {
        (view.headlineView as TextView).text = ad.headline
        (view.bodyView as TextView).apply {
            text = ad.body
            visibility = if (ad.body.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        (view.advertiserView as TextView).text = ad.advertiser ?: ad.store ?: "Advertiser"
        (view.iconView as ImageView).apply {
            setImageDrawable(ad.icon?.drawable)
            visibility = if (ad.icon == null) View.GONE else View.VISIBLE
        }
        view.mediaView?.apply {
            mediaContent = ad.mediaContent
            visibility = if (ad.mediaContent == null) View.GONE else View.VISIBLE
        }
        (view.callToActionView as Button).apply {
            text = ad.callToAction
            visibility = if (ad.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        view.setNativeAd(ad)
    }
}
