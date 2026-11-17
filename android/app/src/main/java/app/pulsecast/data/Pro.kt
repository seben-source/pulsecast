package app.pulsecast.data

import android.app.Activity
import android.content.Context
import android.util.Log
import app.pulsecast.BuildConfig
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.UUID

/**
 * Prompt Pro settings and entitlement. Stays on the phone.
 * Pro = an active Google Play subscription, or the debug-build test switch.
 */
class ProStore(context: Context) {
    private val prefs = context.getSharedPreferences("pro", Context.MODE_PRIVATE)

    /** Last known state of the Play subscription (kept so Pro works offline). */
    var subscribed: Boolean
        get() = prefs.getBoolean("subscribed", false)
        set(v) = prefs.edit().putBoolean("subscribed", v).apply()

    /** Test switch. Only has an effect in debug builds (the ones Android Studio installs). */
    var debugUnlock: Boolean
        get() = prefs.getBoolean("debugUnlock", false)
        set(v) = prefs.edit().putBoolean("debugUnlock", v).apply()

    val isPro: Boolean get() = subscribed || (BuildConfig.DEBUG && debugUnlock)

    /** "What it means for you": one of [Fields.KEYS], or null until chosen. */
    var field: String?
        get() = prefs.getString("field", null)?.takeIf { it in Fields.KEYS }
        set(v) = prefs.edit().putString("field", v).apply()

    /** Followed companies, models and topics, as typed. */
    var follows: Set<String>
        get() = prefs.getStringSet("follows", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("follows", v).apply()

    var followAlerts: Boolean
        get() = prefs.getBoolean("followAlerts", true)
        set(v) = prefs.edit().putBoolean("followAlerts", v).apply()

    /** Cards already alerted (or already in the feed when followed), so each alerts once. */
    var notifiedIds: Set<String>
        get() = prefs.getStringSet("notified", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("notified", v.toList().takeLast(500).toSet()).apply()

    var lastWeeklyNotified: String?
        get() = prefs.getString("lastWeekly", null)
        set(v) = prefs.edit().putString("lastWeekly", v).apply()

    /** Random per-install id, used only for the Ask daily limit. */
    val installId: String
        get() = prefs.getString("installId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installId", it).apply() }
}

object Fields {
    val LABELS = linkedMapOf(
        "developer" to "Developers",
        "business" to "Business & marketing",
        "creative" to "Creators & designers",
        "education" to "Students & teachers",
        "health" to "Healthcare",
    )
    val KEYS = LABELS.keys
}

object Follows {
    val SUGGESTIONS = listOf("OpenAI", "Anthropic", "Google", "Meta", "Nvidia", "Microsoft", "Apple", "Mistral", "Robotics", "Chips", "Policy", "Open source")

    private val cache = HashMap<String, Regex>()

    /** Whole-word, case-insensitive match on what the card says. */
    fun matches(card: Card, term: String): Boolean {
        val re = cache.getOrPut(term) { Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(term.trim()) + "(?![\\p{L}\\p{N}])") }
        return re.containsMatchIn("${card.headline} ${card.summary} ${card.keyPoints.joinToString(" ")} ${card.topic} ${card.source.name}")
    }

    fun matchesAny(card: Card, terms: Set<String>) = terms.any { matches(card, it) }
}

/**
 * Google Play subscriptions. Product "prompt_pro" with base plans "yearly" and
 * "monthly" (each with a 7-day free trial offer) must exist in Play Console;
 * until then [ready] stays false and the Pro screen says it isn't available yet.
 */
class Billing(context: Context, private val store: ProStore, private val onChange: () -> Unit) : PurchasesUpdatedListener {
    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    @Volatile var product: ProductDetails? = null
        private set

    /** A short message for the Pro screen after a purchase attempt, or null. */
    @Volatile var message: String? = null
        private set

    val ready: Boolean get() = product != null

    fun start() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Billing setup: ${result.debugMessage}")
                    return
                }
                loadProduct()
                refreshPurchases()
            }

            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                product = details.productDetailsList.firstOrNull()
                onChange()
            } else {
                Log.w(TAG, "Product details: ${result.debugMessage}")
            }
        }
    }

    /** Re-checks the subscription with Google Play (renewals, cancellations, refunds). */
    fun refreshPurchases() {
        if (!client.isReady) return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, complete = true)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.let { handle(it, complete = false) }
            BillingClient.BillingResponseCode.USER_CANCELED -> {}
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()
            else -> {
                message = "The purchase didn't go through. Please try again."
                onChange()
            }
        }
    }

    private fun handle(purchases: List<Purchase>, complete: Boolean) {
        val ours = purchases.filter { PRODUCT_ID in it.products }
        val active = ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // Google refunds a purchase that isn't acknowledged within 3 days.
        active.filter { !it.isAcknowledged }.forEach { p ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r ->
                if (r.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "Acknowledge: ${r.debugMessage}")
            }
        }
        if (complete) {
            store.subscribed = active.isNotEmpty()
        } else if (active.isNotEmpty()) {
            store.subscribed = true
            message = null
        }
        if (ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            message = "Your payment is pending. Pro switches on as soon as Google Play confirms it."
        }
        onChange()
    }

    private fun offer(basePlan: String): ProductDetails.SubscriptionOfferDetails? =
        product?.subscriptionOfferDetails
            ?.filter { it.basePlanId == basePlan }
            // Play only lists offers this person is eligible for; prefer the free trial.
            ?.maxByOrNull { o -> if (o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }) 1 else 0 }

    /** The plan's regular price as Google Play formats it for this person, e.g. "A$34.99". */
    fun price(basePlan: String): String? = offer(basePlan)?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice

    fun hasTrial(basePlan: String): Boolean = offer(basePlan)?.pricingPhases?.pricingPhaseList?.any { it.priceAmountMicros == 0L } == true

    /** Opens Google Play's purchase sheet. False if the plan isn't available. */
    fun buy(activity: Activity, basePlan: String): Boolean {
        val details = product ?: return false
        val o = offer(basePlan) ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(o.offerToken)
                        .build(),
                ),
            )
            .build()
        message = null
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    fun end() = client.endConnection()

    companion object {
        const val PRODUCT_ID = "prompt_pro"
        const val YEARLY = "yearly"
        const val MONTHLY = "monthly"
        const val MANAGE_URL = "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=app.prompt.ainews"
        private const val TAG = "PromptBilling"
    }
}
