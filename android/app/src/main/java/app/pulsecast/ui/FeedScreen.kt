package app.pulsecast.ui

import android.app.Activity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import app.pulsecast.R
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulsecast.ads.AdsSetup
import app.pulsecast.ads.SponsoredPostView
import app.pulsecast.data.Card
import app.pulsecast.share.ShareCard
import java.time.Duration
import java.time.Instant


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(vm: FeedViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val rows = remember(s) { vm.rows(s) }
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(s.tab, s.topic, s.searching) { listState.scrollToItem(0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { vm.seen(it) }
    }

    // On foldables and tablets the feed stays a readable column, centred.
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.fillMaxHeight().widthIn(max = 680.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), color = Text1, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.setSearching(!s.searching) }) {
                Icon(if (s.searching) Icons.Filled.Close else Icons.Filled.Search, if (s.searching) "Close search" else "Search", tint = Text2)
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings", tint = Text2) }
        }
        if (s.searching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search AI news", color = Text2) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searched() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Text1, unfocusedTextColor = Text1,
                    focusedBorderColor = Accent, unfocusedBorderColor = Line, cursorColor = Accent,
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).focusRequester(focus),
            )
        }
        staleNotice(s.generatedAt)?.let {
            Text(it, color = Text2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        if (!s.searching) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Tab.entries.forEach { tab ->
                val on = s.tab == tab
                Text(
                    tab.label,
                    color = if (on) Ink else Text2,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) Text1 else Color.Transparent)
                        .clickable { vm.setTab(tab) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        val topics = remember(s.cards) { vm.topics() }
        if (topics.size > 1 && !s.searching) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                item { Chip("All", s.topic == null) { vm.setTopic(null) } }
                items(topics) { t -> Chip(t, s.topic == t) { vm.setTopic(if (s.topic == t) null else t) } }
            }
        }

        PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                s.error?.let { item(key = "error") { Text(it, color = Text2, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) } }
                if (s.loaded && rows.isEmpty() && (s.cards.isNotEmpty() || s.error == null)) {
                    item(key = "empty") {
                        Text(
                                            when {
                                s.searching && s.query.isNotBlank() -> "No stories match \"${s.query.trim()}\"."
                                s.searching -> "Search headlines, companies, models and sources."
                                s.tab == Tab.SAVED -> "Tap the star on any story to keep it here."
                                else -> "No stories yet. Pull down to refresh."
                            },
                            color = Text2, modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(rows, key = { it.key }, contentType = { it::class }) { row ->
                    when (row) {
                        is Row.Post -> PostCard(
                            card = row.card,
                            saved = row.card.id in s.saved,
                            onOpen = { vm.opened(row.card) },
                            onSave = { vm.toggleSave(row.card) },
                            onShare = { vm.shared(row.card) },
                            onRead = { vm.readSource(row.card) },
                        )
                        is Row.Ad -> SponsoredPost(vm, row.slot, s.adsVersion)
                        is Row.CaughtUp -> CaughtUp(row.newCount)
                    }
                }
                if (rows.isNotEmpty() && s.tab != Tab.SAVED) item(key = "end") { EndOfFeed() }
            }
        }
    }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CardBg, contentColor = Text1) {
            Settings(s, vm)
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) OnAccent else Text1,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Accent else Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun PostCard(card: Card, saved: Boolean, onOpen: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onRead: () -> Unit) {
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .clickable {
                if (!expanded) onOpen()
                expanded = !expanded
            }
            .animateContentSize(),
    ) {
        // The article's own picture, full width at the top, like a post.
        var imageFailed by remember(card.image) { mutableStateOf(false) }
        if (card.image != null && !imageFailed) {
            AsyncImage(
                model = card.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Raised),
            )
        }
        Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.topic.uppercase(), color = Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            Text("  ·  ${card.source.name}  ·  ${ago(card.publishedAt)}", color = Text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            label(card)?.let {
                Text(it, color = OnAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Text(card.headline, color = Text1, fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 26.sp, modifier = Modifier.padding(top = 10.dp))
        Text(card.summary, color = Body, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
        if (card.whyItMatters.isNotBlank()) {
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Raised).padding(12.dp)) {
                Text("WHY IT MATTERS", color = Accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                Text(card.whyItMatters, color = Text1, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            card.keyPoints.forEach { Text("•  $it", color = Text1, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            if (card.context.isNotBlank()) Detail("Context", card.context)
            if (card.whatNext.isNotBlank()) Detail("What's next", card.whatNext)
            if (card.alsoCoveredBy.isNotEmpty()) {
                Text("ALSO COVERED BY", color = Text2, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp))
                Text(card.alsoCoveredBy.joinToString("  ·  ") { it.name }, color = Text2, fontSize = 13.sp)
            }
        } else {
            Text("Tap for key points", color = Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSave) { Icon(Icons.Filled.Star, if (saved) "Saved" else "Save", tint = if (saved) Accent else Text2) }
            IconButton(onClick = {
                onShare()
                ShareCard.share(context, card)
            }) { Icon(Icons.Filled.Share, "Share", tint = Text2) }
            Spacer(Modifier.weight(1f))
            card.discussionUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Discussion", color = Text2, fontSize = 13.sp) } }
            TextButton(onClick = {
                onRead()
                uri.openUri(card.source.url)
            }) { Text("Read at ${card.source.name}", color = Accent, fontSize = 13.sp, maxLines = 1) }
        }
        }
    }
}

@Composable
private fun Detail(title: String, body: String) {
    Text(title.uppercase(), color = Text2, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp))
    Text(body, color = Text1, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
}

private fun label(card: Card): String? = when {
    card.heat >= 80 -> "HOT"
    card.type == "release" -> "OFFICIAL"
    card.type == "research" -> "PAPER"
    card.type == "tool" -> "TOOL"
    else -> null
}

@Composable
private fun SponsoredPost(vm: FeedViewModel, slot: Int, adsVersion: Int) {
    val ad = remember(slot, adsVersion) { vm.ads.adFor(slot) } ?: return
    val p = LocalPalette.current
    key(p.isDark) {
        AndroidView(
            factory = { SponsoredPostView.create(it, p.card.toArgb(), p.text1.toArgb(), p.text2.toArgb(), p.accent.toArgb(), p.onAccent.toArgb()) },
            update = { SponsoredPostView.bind(it, ad) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CaughtUp(newCount: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Line))
        Text("You're caught up: $newCount new since last time", color = Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Line))
    }
}

@Composable
private fun EndOfFeed() {
    Text(
        "That's everything from the last 10 days. New stories arrive through the day; pull down to check.",
        color = Text2, fontSize = 13.sp, modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun Settings(s: FeedState, vm: FeedViewModel) {
    val activity = LocalContext.current as? Activity
    val uri = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text("Settings", fontFamily = FontFamily.Serif, fontSize = 24.sp, color = Text1)
        Text("Appearance", color = Text1, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { mode -> Chip(mode.label, s.themeMode == mode) { vm.setThemeMode(mode) } }
        }
        Toggle("Morning briefing", "The day's top AI stories, once a day from 7am.", s.briefing, vm::setBriefing)
        Toggle("Breaking stories", "Only for the very biggest AI news. Never at night.", s.breaking, vm::setBreaking)
        if (activity != null && AdsSetup.privacyOptionsRequired(activity)) {
            TextButton(onClick = { AdsSetup.showPrivacyOptions(activity) }) { Text("Ad privacy choices", color = Accent) }
        }
        TextButton(onClick = { uri.openUri(app.pulsecast.BuildConfig.FEED_URL.substringBeforeLast('/') + "/privacy.html") }) { Text("Privacy policy", color = Accent) }
        Text(
            "Every story is summarised by AI from the linked article, and links to its source. Check the source before relying on a detail.",
            color = Text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Text1, fontSize = 15.sp)
            Text(detail, color = Text2, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
    }
}

/** Shown only if the news engine hasn't published for over 3 hours. */
private fun staleNotice(generatedAt: Instant?): String? {
    if (generatedAt == null || generatedAt == Instant.EPOCH) return null
    val hours = Duration.between(generatedAt, Instant.now()).toHours()
    return if (hours >= 3) "Updates paused: last new stories ${ago(generatedAt)} ago" else null
}

private fun ago(t: Instant): String {
    val m = Duration.between(t, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 60 * 24 -> "${m / 60}h"
        else -> "${m / (60 * 24)}d"
    }
}
