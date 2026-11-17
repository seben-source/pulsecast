package app.pulsecast.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulsecast.data.Billing
import app.pulsecast.stats.Stats

/**
 * Prompt Pro: the upgrade screen. Prices and the purchase come from Google Play.
 * Until the subscription exists in Play Console, the button records interest
 * and says Pro isn't available yet.
 */

private data class Benefit(val icon: ImageVector, val title: String, val detail: String)

private val BENEFITS = listOf(
    Benefit(Icons.Filled.Close, "No ads", "A clean feed with nothing sponsored in it."),
    Benefit(Icons.Filled.Send, "Ask about any story", "Ask follow-up questions on any card. Answers come from the article itself, in plain English."),
    Benefit(Icons.Filled.Person, "What it means for you", "Pick your field and see why each big story matters for your work."),
    Benefit(Icons.Filled.Notifications, "Follow anything", "Follow companies, models and topics. They get their own tab, and an alert as soon as a new story lands."),
    Benefit(Icons.Filled.DateRange, "Weekly deep-dive", "Every Sunday: the week in AI and what actually mattered."),
    Benefit(Icons.Filled.Search, "Full archive and search", "Search every story Prompt has covered, not just the last 10 days."),
)

private enum class Plan(val title: String, val basePlan: String, val fallbackPrice: String, val per: String, val note: String, val badge: String?) {
    YEARLY("Yearly", Billing.YEARLY, "A$34.99", "year", "Best value", "SAVE 41%"),
    MONTHLY("Monthly", Billing.MONTHLY, "A$4.99", "month", "Billed monthly", null),
}

@Composable
fun ProScreen(vm: FeedViewModel, s: FeedState, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var plan by remember { mutableStateOf(Plan.YEARLY) }
    var tapped by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    val uri = LocalUriHandler.current
    LaunchedEffect(Unit) { Stats.event("pro_view") }
    // Read so the screen redraws when Google Play answers.
    @Suppress("UNUSED_VARIABLE") val billingVersion = s.billingVersion
    val billing = vm.billing
    fun price(p: Plan) = "${billing.price(p.basePlan) ?: p.fallbackPrice} / ${p.per}"
    val trial = billing.hasTrial(plan.basePlan) || !billing.ready

    Box(Modifier.fillMaxSize().background(Ink),contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onClose, modifier = Modifier.padding(start = 0.dp)) { Icon(Icons.Filled.Close, "Close", tint = Text2) }
            }

            // Hero
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(">_", color = Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Spacer(Modifier.width(10.dp))
                Text("Prompt", color = Text1, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Spacer(Modifier.width(8.dp))
                ProBadge(big = true)
            }
            if (s.pro) {
                Text(
                    "You're on Prompt Pro.",
                    color = Text1, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    if (vm.subscribed()) "Thanks for supporting Prompt. Everything below is switched on."
                    else "Unlocked with the testing switch in Settings (debug builds only).",
                    color = Body, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Column(
                    Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(vertical = 6.dp),
                ) {
                    BENEFITS.forEachIndexed { i, b ->
                        if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(Line))
                        BenefitRow(b)
                    }
                }
                if (vm.subscribed()) Text(
                    "Manage subscription",
                    color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                        .border(1.dp, Accent, RoundedCornerShape(28.dp)).clickable { uri.openUri(Billing.MANAGE_URL) }.padding(vertical = 14.dp),
                )
                return@Column
            }
            Text(
                "Understand AI faster.",
                color = Text1, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                "Everything in Prompt, plus tools that save you time and explain what the news means for you.",
                color = Body, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp),
            )

            // Benefits
            Column(
                Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(vertical = 6.dp),
            ) {
                BENEFITS.forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(Line))
                    BenefitRow(b)
                }
            }

            // Plans
            Text("Choose your plan", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
            Plan.entries.forEach { p ->
                PlanOption(p, price(p), selected = plan == p) { plan = p }
                Spacer(Modifier.height(10.dp))
            }

            // Call to action
            Text(
                if (trial) "Start 7-day free trial" else "Subscribe",
                color = OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Accent)
                    .clickable {
                        vm.proInterest(plan.name.lowercase())
                        tapped = !(activity != null && billing.ready && billing.buy(activity, plan.basePlan))
                    }
                    .padding(vertical = 16.dp),
            )
            val note = billing.message
                ?: if (tapped) "Prompt Pro isn't available yet. Thanks for your interest, we'll let you know when it launches."
                else if (trial) "Free for 7 days, then ${price(plan)}. Renews automatically; cancel anytime in Google Play."
                else "${price(plan)}. Renews automatically; cancel anytime in Google Play."
            Text(
                note,
                color = if (tapped || billing.message != null) Accent else Text2, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Text(
                "The news itself stays free for everyone.",
                color = Text2, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
        }
    }
}

@Composable
fun ProBadge(big: Boolean = false) {
    Text(
        "PRO",
        color = OnAccent,
        fontSize = if (big) 13.sp else 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Accent).padding(horizontal = if (big) 8.dp else 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun BenefitRow(b: Benefit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Raised), contentAlignment = Alignment.Center) {
            Icon(b.icon, null, tint = Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(b.detail, color = Text2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PlanOption(p: Plan, price: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (selected) Accent else Color.Transparent)
                .border(2.dp, if (selected) Accent else Text2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = OnAccent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, color = Text1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                p.badge?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, Accent, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Text(p.note, color = Text2, fontSize = 12.sp)
        }
        Text(price, color = Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
