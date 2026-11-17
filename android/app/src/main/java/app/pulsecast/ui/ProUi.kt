package app.pulsecast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulsecast.data.AskClient
import app.pulsecast.data.Card
import app.pulsecast.data.Fields
import app.pulsecast.data.Follows
import app.pulsecast.data.Issue
import app.pulsecast.data.Turn
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun Kicker(text: String, color: androidx.compose.ui.graphics.Color = Accent) {
    Text(text, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
}

@Composable
private fun SmallChip(label: String, on: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) OnAccent else Text1,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Accent else CardBg)
            .border(1.dp, if (on) Accent else Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------- What it means for you ----------

/** Inside an opened card. Free readers see a locked teaser; Pro readers see their field's line. */
@Composable
fun ForYouBox(card: Card, pro: Boolean, field: String?, onPickField: (String?) -> Unit, onPro: () -> Unit) {
    if (card.forYou.isEmpty()) return
    val shape = RoundedCornerShape(10.dp)
    val box = Modifier.padding(top = 12.dp).fillMaxWidth().clip(shape).border(1.dp, Accent.copy(alpha = 0.5f), shape)
    when {
        !pro -> Row(box.clickable(onClick = onPro).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker("WHAT IT MEANS FOR YOU")
                Text("See what this story means for your work.", color = Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            ProBadge()
        }
        field == null -> Column(box.padding(12.dp)) {
            Kicker("WHAT IT MEANS FOR YOU")
            Text("Pick your field to see why stories matter for your work:", color = Text1, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Fields.LABELS.forEach { (k, label) -> SmallChip(label) { onPickField(k) } }
            }
        }
        else -> {
            val line = card.forYou[field] ?: return // this story doesn't really affect their field
            Column(box.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker("FOR ${Fields.LABELS[field]?.uppercase(Locale.ROOT) ?: "YOU"}")
                    Spacer(Modifier.weight(1f))
                    Text("Change", color = Text2, fontSize = 11.sp, modifier = Modifier.clickable { onPickField(null) }.padding(start = 8.dp))
                }
                Text(line, color = Text1, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------- Ask about this story ----------

@Composable
fun AskButton(pro: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Send, null, tint = Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Ask about this story", color = Text1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (!pro) {
            Spacer(Modifier.width(8.dp))
            ProBadge()
        }
    }
}

private val SUGGESTED = listOf("Explain this simply", "Who does this affect?", "What's the catch?", "What happens next?")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(card: Card, vm: FeedViewModel, onDismiss: () -> Unit) {
    val turns = remember(card.id) { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    fun send(raw: String) {
        val q = raw.trim()
        if (pending != null || q.length < 2) return
        pending = q
        error = null
        input = ""
        scope.launch {
            try {
                val a = vm.ask(card, q, turns.toList())
                turns.add(Turn(q, a))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't get an answer. Try again."
                input = q
            } finally {
                pending = null
            }
        }
    }

    LaunchedEffect(turns.size, pending) {
        val last = turns.size + (if (pending != null) 1 else 0) - 1
        if (last >= 0) list.animateScrollToItem(last)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CardBg, contentColor = Text1) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding()) {
            Kicker("ASK ABOUT THIS STORY")
            Text(card.headline, color = Text1, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            if (!AskClient.configured) {
                Text("Ask is almost ready. It switches on once its server is connected.", color = Text2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(turns) { t ->
                    Question(t.q)
                    Text(t.a, color = Body, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                }
                pending?.let { q ->
                    item {
                        Question(q)
                        Text("Reading the article…", color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (turns.isEmpty() && pending == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SUGGESTED.forEach { SmallChip(it) { send(it) } }
                }
            }
            error?.let { Text(it, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(300) },
                    placeholder = { Text("Ask anything about this story", color = Text2) },
                    singleLine = true,
                    enabled = pending == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Text1, unfocusedTextColor = Text1,
                        focusedBorderColor = Accent, unfocusedBorderColor = Line, cursorColor = Accent,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { send(input) }, enabled = pending == null && input.trim().length >= 2) {
                    Icon(Icons.Filled.Send, "Send", tint = if (input.trim().length >= 2) Accent else Text2)
                }
            }
            Text(
                "Answers come only from the article. Check the source before relying on a detail.",
                color = Text2, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Question(q: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            q, color = Text1, fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(Raised).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ---------- Weekly deep-dive ----------

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private fun weekLabel(i: Issue) = runCatching { LocalDate.parse(i.weekOf).format(DAY).uppercase(Locale.ROOT) }.getOrDefault(i.weekOf)

/** At the top of For you while the latest issue is under a week old. */
@Composable
fun WeeklyBanner(issue: Issue, pro: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Kicker("THE WEEK IN AI · ${weekLabel(issue)}")
            Text(issue.title, color = Text1, fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 4.dp))
            Text(if (pro) "Read this week's deep-dive" else "This week's deep-dive is in Prompt Pro", color = Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (!pro) {
            Spacer(Modifier.width(10.dp))
            ProBadge()
        }
    }
}

/** One issue, or the list of all issues when [start] is null. */
@Composable
fun WeeklyScreen(issues: List<Issue>, start: Issue?, onClose: () -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    if (showAll) {
        WeeklyScreen(issues, null) { showAll = false }
        return
    }
    var current by remember { mutableStateOf(start) }
    val fromList = start == null
    val back: () -> Unit = {
        if (current != null && fromList) {
            current = null
        } else {
            onClose()
        }
    }
    BackHandler(onBack = back)
    val uri = LocalUriHandler.current

    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            IconButton(onClick = back, modifier = Modifier.padding(top = 4.dp)) {
                Icon(if (current != null && fromList) Icons.Filled.ArrowBack else Icons.Filled.Close, "Back", tint = Text2)
            }
            val issue = current
            if (issue == null) {
                Text("The week in AI", color = Text1, fontFamily = FontFamily.Serif, fontSize = 28.sp)
                Text("A deep-dive every Sunday morning.", color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                if (issues.isEmpty()) Text("The first issue arrives on Sunday.", color = Text2, fontSize = 14.sp)
                issues.forEach { i ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(CardBg).clickable { current = i }.padding(14.dp)) {
                        Kicker(weekLabel(i))
                        Text(i.title, color = Text1, fontFamily = FontFamily.Serif, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                return@Column
            }
            val cardsById = issue.cards.associateBy { it.id }
            Kicker("THE WEEK IN AI · ${weekLabel(issue)}")
            Text(issue.title, color = Text1, fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp, modifier = Modifier.padding(top = 6.dp))
            Text(issue.intro, color = Body, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp))
            issue.sections.forEach { sec ->
                val first = sec.cardIds.firstNotNullOfOrNull { cardsById[it]?.image }
                Box(Modifier.padding(top = 28.dp).fillMaxWidth().height(1.dp).background(Line))
                if (first != null) {
                    AsyncImage(
                        model = first, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 20.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Raised),
                    )
                }
                Text(sec.heading, color = Text1, fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 26.sp, modifier = Modifier.padding(top = 16.dp))
                Text(sec.body, color = Body, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
                sec.cardIds.mapNotNull { cardsById[it] }.forEach { c ->
                    Text(
                        "→ ${c.headline} (${c.source.name})",
                        color = Accent, fontSize = 13.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp).clickable { uri.openUri(c.source.url) },
                    )
                }
            }
            if (issue.takeaway.isNotBlank()) {
                Column(Modifier.padding(top = 28.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Raised).padding(14.dp)) {
                    Kicker("THE TAKEAWAY")
                    Text(issue.takeaway, color = Text1, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Text(
                "Written by AI from the week's Prompt stories. Tap a story to read its source.",
                color = Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp),
            )
            if (!fromList && issues.size > 1) {
                TextButton(onClick = { showAll = true }) { Text("All issues", color = Accent) }
            }
        }
    }
}

// ---------- Following (Settings) ----------

@Composable
fun FollowingSection(s: FeedState, vm: FeedViewModel) {
    var input by remember { mutableStateOf("") }
    Text("Following", color = Text1, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
    Text("Companies, models or topics. New matching stories get their own tab and an alert.", color = Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(40) },
            placeholder = { Text("e.g. Anthropic, Gemini, robotics", color = Text2) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.addFollow(input); input = "" }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Text1, unfocusedTextColor = Text1,
                focusedBorderColor = Accent, unfocusedBorderColor = Line, cursorColor = Accent,
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { vm.addFollow(input); input = "" }, enabled = input.trim().length >= 2) { Text("Follow", color = Accent) }
    }
    if (s.follows.isNotEmpty()) {
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.follows.sortedBy { it.lowercase() }.forEach { t -> SmallChip("$t  ×", on = true) { vm.removeFollow(t) } }
        }
    }
    val ideas = Follows.SUGGESTIONS.filter { idea -> s.follows.none { it.equals(idea, ignoreCase = true) } }
    if (ideas.isNotEmpty()) {
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ideas.forEach { idea -> SmallChip("+ $idea") { vm.addFollow(idea) } }
        }
    }
}

@Composable
fun FieldPicker(s: FeedState, vm: FeedViewModel) {
    Text("Your field", color = Text1, fontSize = 15.sp, modifier = Modifier.padding(top = 20.dp))
    Text("Opened stories show what they mean for your work.", color = Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Fields.LABELS.forEach { (k, label) -> SmallChip(label, on = s.field == k) { vm.setField(if (s.field == k) null else k) } }
    }
}
