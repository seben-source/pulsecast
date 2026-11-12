package app.pulsecast.ui

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
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulsecast.stats.Stats

/**
 * Prompt Pro: the upgrade screen. A preview for now; Google Play billing is not
 * connected yet, so the button records interest instead of charging.
 */

private data class Benefit(val icon: ImageVector, val title: String, val detail: String)

private val BENEFITS = listOf(
    Benefit(Icons.Filled.Close, "No ads", "A clean feed with nothing sponsored in it."),
    Benefit(Icons.Filled.Send, "Ask about any story", "Ask follow-up questions on any card. Answers come from the article itself, in plain English."),
    Benefit(Icons.Filled.Person, "What it means for you", "Pick your field and see why each big story matters for your work."),
    Benefit(Icons.Filled.Notifications, "Follow anything, instantly", "Follow companies, models and topics, and get alerts within minutes, not hours."),
    Benefit(Icons.Filled.DateRange, "Weekly deep-dive", "Every Sunday: the week in AI and what actually mattered."),
    Benefit(Icons.Filled.Search, "Full archive and search", "Search every story Prompt has covered, not just the last 10 days."),
    Benefit(Icons.Filled.Build, "New tools you can use", "A running list of useful new AI tools and price changes, each with a one-line verdict."),
)

private enum class Plan(val title: String, val price: String, val note: String, val badge: String?) {
    YEARLY("Yearly", "A$34.99 / year", "A$2.92 a month", "SAVE 41%"),
    MONTHLY("Monthly", "A$4.99 / month", "Billed monthly", null),
}

@Composable
fun ProScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var plan by remember { mutableStateOf(Plan.YEARLY) }
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Stats.event("pro_view") }

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
                PlanOption(p, selected = plan == p) { plan = p }
                Spacer(Modifier.height(10.dp))
            }

            // Call to action
            Text(
                "Start 7-day free trial",
                color = OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Accent)
                    .clickable {
                        tapped = true
                        Stats.event("pro_interest", "plan" to plan.name.lowercase())
                    }
                    .padding(vertical = 16.dp),
            )
            Text(
                if (tapped) "Prompt Pro isn't available yet. Thanks for your interest, we'll let you know when it launches."
                else "Free for 7 days, then ${plan.price}. Cancel anytime in Google Play.",
                color = if (tapped) Accent else Text2, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
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
private fun PlanOption(p: Plan, selected: Boolean, onClick: () -> Unit) {
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
        Text(p.price, color = Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
