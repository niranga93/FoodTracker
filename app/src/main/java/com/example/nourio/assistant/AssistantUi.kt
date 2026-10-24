package com.example.nourio.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nourio.LocalOpenPage
import com.example.nourio.data.MealDb
import kotlinx.coroutines.launch

/** One message in the chat. An assistant message has [answer] or [error]. */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val answer: Answer? = null,
    val error: Boolean = false,
    val action: ProposedAction? = null,
    val outcome: String? = null,       // set once the action ran or was cancelled
)

/** The conversation lives as long as the app does, so leaving the screen doesn't lose it. */
object AssistantSession {
    val messages = mutableStateListOf<ChatMessage>()
    val turns = mutableListOf<Assistant.Turn>()
    fun clear() { messages.clear(); turns.clear() }
}

private val SUGGESTIONS = listOf(
    "How much protein did I average this week?",
    "Which foods gave me the most calories this month?",
    "Do I eat more at weekends?",
    "How has my weight changed in the last 30 days?",
    "Do I eat more after a short night?",
    "I'd like to report a bug",
    "Stop the daily tips and reminders",
)

/** The chat itself, shown in the floating assistant's sheet. */
@Composable
fun AssistantChat(db: MealDb, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AssistantPrefs(context) }
    val openPage = LocalOpenPage.current
    val messages = AssistantSession.messages
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }      // waiting for consent
    val list = rememberLazyListState()
    val hasKey = remember { com.example.nourio.ai.AiSettings.isReady() }
    val firstName = remember { com.example.nourio.data.ProfileStore(context).load().name }   // stays on the phone

    LaunchedEffect(messages.size, busy) {
        // Item 0 is the introduction, so the newest message sits at index messages.size
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + if (busy) 1 else 0)
    }

    fun send(q: String) {
        val question = q.trim()
        if (question.isEmpty() || busy) return
        if (!prefs.consented) { pending = question; return }
        input = ""
        messages += ChatMessage(true, question)
        busy = true
        scope.launch {
            Assistant.ask(context, db, question, AssistantSession.turns.toList()).fold(
                onSuccess = { r ->
                    AssistantSession.turns += Assistant.Turn(question, r.planJson)
                    messages += ChatMessage(false, r.answer.text, r.answer, action = r.plan.action.takeIf { r.plan.kind == "action" })
                },
                onFailure = { e ->
                    AssistantSession.turns += Assistant.Turn(question, null)
                    val msg = e.message?.takeIf { " key" in it }
                        ?: "I couldn't work that out just now. Check your connection and try again."
                    messages += ChatMessage(false, msg, error = true)
                }
            )
            busy = false
        }
    }

    // The same consent as every other AI feature; agreeing turns on its switch in Data & privacy
    pending?.let { q ->
        com.example.nourio.AiConsentDialog(
            com.example.nourio.ai.AiUse.ASSISTANT,
            onAgree = { pending = null; send(q) },
            onDismiss = { pending = null }
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar()
            Spacer(Modifier.width(10.dp))
            Text("Nourio Assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (messages.isNotEmpty()) TextButton(onClick = { AssistantSession.clear() }) { Text("New chat") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        PrivacyRibbon()

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { IntroBubble(firstName) }
            if (messages.isEmpty()) item { EmptyState(hasKey, onSetupKey = { openPage("connections") }, onPick = ::send) }
            itemsIndexed(messages) { i, m ->
                when {
                    m.fromUser -> UserBubble(m.text)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistantBubble(m)
                        m.action?.let { a -> ActionCard(a, m.outcome) { done -> messages[i] = m.copy(outcome = done) } }
                    }
                }
            }
            if (busy) item { ThinkingBubble() }
        }

        InputBar(input, enabled = hasKey && !busy, onChange = { input = it }, onSend = { send(input) })
    }
}

@Composable
private fun PrivacyRibbon() {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.primaryContainer.copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔒", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "Answers are worked out on your phone. Only your question is sent.",
            style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer
        )
    }
}

@Composable
private fun Avatar() {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) { Text("✦", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
}

/**
 * The assistant introduces itself at the top of every chat: who it is, what it can answer, how the
 * data stays on the phone, and its limits. Shown on the phone only; it's not part of the conversation
 * sent to Gemini.
 */
@Composable
private fun IntroBubble(name: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(cs.surfaceContainerHigh)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (name.isNotBlank()) "Hi ${name.trim().substringBefore(' ')}, I'm Nourio Assistant." else "Hi, I'm Nourio Assistant.",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
            Text(
                "Ask about anything you've tracked, in your own words.",
                style = MaterialTheme.typography.bodyLarge
            )
            IntroPoint(
                "📊", "What I can answer",
                "Food, nutrients, water, fasting, weight, steps, sleep and heart rate, over any dates. " +
                    "I can also compare periods and see how two things relate."
            )
            IntroPoint(
                "🛠", "What I can do for you",
                "Report a bug, suggest a feature or flag a problem with your data. Delete data or withdraw a " +
                    "consent. I always show you exactly what will happen first, and nothing changes until you confirm."
            )
            IntroPoint(
                "🔒", "How it stays private",
                "The AI only plans the answer. Your phone does the maths, so your data never leaves it."
            )
            IntroPoint(
                "💡", "Good to know",
                "I only see what's in Nourio or Health Connect, and I give information, not medical advice."
            )
        }
    }
}

@Composable
private fun IntroPoint(icon: String, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 16.sp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Under the introduction while the chat is empty: a key prompt, or questions to tap. */
@Composable
private fun EmptyState(hasKey: Boolean, onSetupKey: () -> Unit, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 42.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hasKey) {
            Text(
                "To get started I need an AI key. Gemini's is free and takes about a minute.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant
            )
            OutlinedButton(onClick = onSetupKey) { Text("Add an AI key") }
            return@Column
        }
        Text("Try asking", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        SUGGESTIONS.forEach { s ->
            Text(
                s, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                    .clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                .background(MaterialTheme.colorScheme.primary).padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun AssistantBubble(m: ChatMessage) {
    val cs = MaterialTheme.colorScheme
    var showMethod by remember { mutableStateOf(false) }
    val a = m.answer
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(if (m.error || a?.missing == true) cs.surfaceVariant else cs.surfaceContainerHigh)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            a?.chart?.let { MiniChart(it) }
            a?.footnote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
            if (a?.method != null) {
                Text(
                    if (showMethod) "Hide how I worked it out" else "How I worked it out",
                    style = MaterialTheme.typography.labelMedium, color = cs.primary,
                    modifier = Modifier.clickable { showMethod = !showMethod }
                )
                if (showMethod) Text(
                    "${a.method} Calculated on your phone from your own records.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar()
        Spacer(Modifier.width(10.dp))
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Looking through your records…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.example.nourio.AiStatusLine()
        }
    }
}

/** Day by day, so the number in the answer has its context. The latest day is solid. */
@Composable
private fun MiniChart(r: StepResult) {
    val cs = MaterialTheme.colorScheme
    val pts = r.series.takeLast(31)
    val max = pts.maxOf { it.second }.coerceAtLeast(1e-6)
    val min = if (r.metric.group == AMetric.Group.WEIGHT) pts.minOf { it.second } - 0.5 else 0.0
    Column {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val slot = size.width / pts.size
            val bw = (slot * 0.62f).coerceAtMost(18.dp.toPx())
            pts.forEachIndexed { i, (_, v) ->
                val h = (((v - min) / (max - min).coerceAtLeast(1e-6)) * size.height).toFloat().coerceAtLeast(3f)
                drawRoundRect(
                    cs.primary.copy(alpha = if (i == pts.lastIndex) 1f else 0.45f),
                    Offset(i * slot + (slot - bw) / 2, size.height - h), Size(bw, h), CornerRadius(4f)
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(Executor.date(pts.first().first), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(Executor.date(pts.last().first), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun InputBar(value: String, enabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value, onValueChange = onChange, enabled = enabled,
            placeholder = { Text("Ask about your meals, sleep, weight…") },
            shape = RoundedCornerShape(24.dp), maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
        }
    }
}

// ------------------------------------------------------------ action cards

/**
 * What an action will do, in full, with the one button that does it. Gemini only proposed it; this
 * card is the user's decision. Destructive actions are red, and erasing everything takes two taps.
 */
@Composable
private fun ActionCard(a: ProposedAction, outcome: String?, onOutcome: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val destructive = a.type == ActionType.DELETE_DATA || a.type == ActionType.WITHDRAW_CONSENT
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.padding(start = 42.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (destructive && outcome == null) cs.error.copy(alpha = 0.6f) else cs.outlineVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val heading = when (a.type) {
            ActionType.REPORT_BUG -> "🐞  Bug report"
            ActionType.FEATURE_REQUEST -> "✨  Feature request"
            ActionType.DATA_ISSUE -> "🧾  Problem with my data"
            ActionType.DELETE_DATA -> "🗑  Delete ${a.scope?.label ?: "data"}"
            ActionType.WITHDRAW_CONSENT -> "🚫  Withdraw consent: ${a.consent?.label ?: ""}"
        }
        Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        if (outcome != null) {
            Text(outcome, style = MaterialTheme.typography.bodyMedium, color = cs.primary)
            return@Column
        }

        when {
            a.type.isReport -> {
                var body by rememberSaveable { mutableStateOf(a.details) }
                var withDevice by rememberSaveable { mutableStateOf(true) }
                if (a.title.isNotBlank()) Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = body, onValueChange = { body = it }, minLines = 3, maxLines = 8,
                    label = { Text("Details (edit anything)") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { withDevice = !withDevice }) {
                    Checkbox(checked = withDevice, onCheckedChange = { withDevice = it })
                    Text("Add app version and phone model", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Opens your email app addressed to Nourio support. Nothing is sent until you press send there, " +
                        "and none of your logged data is attached.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching { Actions.emailReport(context, a, body, withDevice) }.fold(
                            onSuccess = { onOutcome("Opened in your email app. Thanks for helping improve Nourio.") },
                            onFailure = { onOutcome("No email app found. You can write to ${com.example.nourio.SUPPORT_EMAIL} directly.") }
                        )
                    }, enabled = body.isNotBlank()) { Text("Open in email") }
                    TextButton(onClick = { onOutcome("Cancelled. Nothing was sent.") }) { Text("Cancel") }
                }
            }

            a.type == ActionType.DELETE_DATA -> {
                var preview by remember { mutableStateOf<DeletePreview?>(null) }
                var armed by remember { mutableStateOf(false) }       // second tap for "everything"
                LaunchedEffect(a) { preview = Actions.preview(context, a) }
                val p = preview
                when {
                    p == null -> Text("Counting…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    p.count == 0 -> Text("There's nothing to delete for that period.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(p.text, style = MaterialTheme.typography.bodyMedium)
                        Text("This can't be undone.", style = MaterialTheme.typography.bodySmall, color = cs.error)
                        val everything = a.scope == DeleteScope.EVERYTHING
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (everything && !armed) { armed = true; return@Button }
                                    busy = true
                                    scope.launch { onOutcome("✓ " + runCatching { Actions.delete(context, a) }.getOrElse { "Couldn't delete: ${it.message}" }); busy = false }
                                },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                            ) {
                                Text(when {
                                    everything && armed -> "Tap again to erase everything"
                                    everything -> "Erase everything"
                                    else -> "Delete ${p.count} ${a.scope?.label ?: ""}".trim()
                                })
                            }
                            TextButton(onClick = { onOutcome("Cancelled. Nothing was deleted.") }) { Text("Cancel") }
                        }
                    }
                }
            }

            a.type == ActionType.WITHDRAW_CONSENT && a.consent != null -> {
                Text(a.consent.effect, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            busy = true
                            scope.launch { onOutcome("✓ " + runCatching { Actions.withdraw(context, a.consent) }.getOrElse { "Couldn't change that: ${it.message}" }); busy = false }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                    ) { Text("Withdraw") }
                    TextButton(onClick = { onOutcome("Cancelled. Nothing changed.") }) { Text("Cancel") }
                }
            }
        }
    }
}


// ------------------------------------------------------------ the floating bubble

private const val BUBBLE_DP = 52
private const val EDGE_DP = 16      // the same margin as the Add meal button
private const val ABOVE_FAB_DP = 88     // starts just above the Add meal button

/**
 * A bubble that floats over the main tabs. Drag it anywhere; it settles on the nearest side and
 * remembers where. Tap it and the chat slides up over whatever is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingAssistant(db: MealDb) {
    val context = LocalContext.current
    val prefs = remember { AssistantPrefs(context) }
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val bubble = with(density) { BUBBLE_DP.dp.toPx() }
        val edge = with(density) { EDGE_DP.dp.toPx() }
        val maxX = with(density) { maxWidth.toPx() } - bubble - edge
        val maxY = with(density) { maxHeight.toPx() } - bubble - edge
        val startY = maxY - with(density) { ABOVE_FAB_DP.dp.toPx() }
        val x = remember { Animatable(if (prefs.bubbleLeft) edge else maxX) }
        val y = remember { Animatable(prefs.bubbleY.takeIf { it >= 0f }?.let { it * maxY } ?: startY) }

        Box(
            Modifier
                .offset { IntOffset(x.value.roundToInt(), y.value.roundToInt().coerceIn(edge.roundToInt(), maxY.roundToInt())) }
                .size(BUBBLE_DP.dp)
                // Quieter than the Add meal button: a card-coloured disc with a green ring
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(com.example.nourio.cardContainer())
                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            // Settle on the nearer side, and remember it
                            val left = x.value + bubble / 2 < (maxX + edge) / 2
                            scope.launch { x.animateTo(if (left) edge else maxX, spring(dampingRatio = 0.7f)) }
                            prefs.bubbleLeft = left
                            prefs.bubbleY = (y.value / maxY).coerceIn(0f, 1f)
                        }
                    ) { change, drag ->
                        change.consume()
                        scope.launch {
                            x.snapTo((x.value + drag.x).coerceIn(edge, maxX))
                            y.snapTo((y.value + drag.y).coerceIn(edge, maxY))
                        }
                    }
                }
                .clickable(onClickLabel = "Ask Nourio") { open = true }
                .semantics { contentDescription = "Nourio Assistant" },
            contentAlignment = Alignment.Center
        ) {
            Text("✦", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheet,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.fillMaxHeight(0.92f)) {
                AssistantChat(db, onClose = { scope.launch { sheet.hide() }.invokeOnCompletion { open = false } })
            }
        }
    }
}
