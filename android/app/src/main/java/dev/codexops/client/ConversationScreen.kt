package dev.codexops.client

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.m3.Markdown
import dev.codexops.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun ColumnScope.ConversationScreen(st: ScreenState, actions: ConversationActions) {
    var confirmUnlock by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    val messages = st.entries.filter { it.kind != "reasoning" }
    val actionablePlan =
        messages.lastOrNull()?.takeIf {
            it.kind == "plan" &&
                it.completed &&
                st.collaborationModes.any { preset ->
                    preset.mode == "default" &&
                        preset.turnSetting(st.collaborationModel()) != null
                }
        }
    // Reverse layout anchors new history at the latest message, even when that
    // message is taller than the viewport. Stable keys preserve reading position.
    LaunchedEffect(scroll) {
        snapshotFlow {
                Triple(
                    scroll.isScrollInProgress,
                    scroll.firstVisibleItemIndex,
                    scroll.firstVisibleItemScrollOffset,
                )
            }
            .collect { (scrolling, index, offset) ->
                if (scrolling) followLatest = index == 0 && offset < 48
            }
    }
    LaunchedEffect(messages.lastOrNull(), st.decisions, st.journal, st.busy, st.activeTurn) {
        if (followLatest && !scroll.isScrollInProgress) scroll.scrollToItem(0)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().testTag("timeline"),
        state = scroll,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
    ) {
        if (st.busy || st.activeTurn != null)
            item(key = "activity") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        if (st.busy) "Updating…" else "Working on ${st.host.displayName}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        if (st.attention && st.decisions.isEmpty())
            item {
                Text(
                    "Needs attention. Approval details are unavailable here; open this task on desktop.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        st.journal?.let { journal ->
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (journal.str("stage") == "accepted") "Message accepted"
                            else "Delivery needs review",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "This operation will not be sent again automatically. Check the task on ${st.host.displayName} before unlocking the composer.",
                            Modifier.padding(vertical = 8.dp),
                            fontSize = 13.sp,
                        )
                        SelectionContainer {
                            Text(
                                "Task: ${journal.str("threadId").ifEmpty { "ID not received" }}\nWorkspace: ${journal.str("cwd")}",
                                fontSize = 11.sp,
                            )
                        }
                        TextButton(onClick = { confirmUnlock = true }) {
                            Text("I've checked the task…")
                        }
                    }
                }
            }
        }
        items(st.decisions.reversed(), key = { it.key }) { decision ->
            DecisionCard(decision, st, actions)
        }
        items(messages.asReversed(), key = { it.key }) { entry ->
            Message(
                entry = entry,
                canImplement =
                    entry.key == actionablePlan?.key &&
                        st.ready &&
                        !st.busy &&
                        st.activeTurn == null &&
                        st.journal == null,
                onImplement = { actions.implementPlan(entry.key) },
            )
        }
        if (st.historyCursor != null)
            item(key = "history") {
                TextButton(onClick = actions::older, modifier = Modifier.fillMaxWidth()) {
                    Glyph(R.drawable.ic_up)
                    Spacer(Modifier.width(8.dp))
                    Text("Load earlier messages")
                }
            }
        if (st.entries.isEmpty() && st.thread == null)
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().fillParentMaxHeight().padding(vertical = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Glyph(R.drawable.ic_compose, modifier = Modifier.size(26.dp))
                        }
                    }
                    Text(
                        "What shall we work on?",
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Ask a question, investigate an issue,\nor pick up an idea.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
    ConversationComposer(
        state = st,
        actions = actions,
        onSend = { followLatest = true },
    )
    if (confirmUnlock)
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("Unlock the composer?") },
            text = {
                Text(
                    "Only continue after checking whether ${st.host.displayName} already received this message. Sending it again could duplicate the work."
                )
            },
            confirmButton = {
                TextButton({
                    confirmUnlock = false
                    actions.unlockAfterReview()
                }) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton({ confirmUnlock = false }) { Text("Keep checking") }
            },
        )
}

@Composable
private fun Message(entry: Entry, canImplement: Boolean, onImplement: () -> Unit) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    var planFullscreen by rememberSaveable(entry.key) { mutableStateOf(false) }
    if (entry.kind == "userMessage")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(.9f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            ) {
                SelectionContainer {
                    Text(
                        entry.text,
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        lineHeight = 23.sp,
                    )
                }
            }
        }
    else if (entry.kind == "agentMessage")
        SelectionContainer { Markdown(entry.text.take(100000)) }
    else if (entry.kind == "plan") {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("plan-card"),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Plan", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(
                        onClick = { planFullscreen = true },
                        modifier = Modifier.testTag("open-plan-fullscreen"),
                    ) {
                        Text("Full screen")
                    }
                }
                SelectionContainer { Markdown(entry.text.take(100000)) }
                if (canImplement)
                    Button(
                        onClick = onImplement,
                        modifier = Modifier.testTag("implement-plan"),
                    ) {
                        Text("Implement")
                }
            }
        }
        if (planFullscreen)
            FullscreenPlan(
                text = entry.text,
                canImplement = canImplement,
                onDismiss = { planFullscreen = false },
                onImplement = {
                    planFullscreen = false
                    onImplement()
                },
            )
    }
    else
        Surface(
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Glyph(
                        if (entry.kind == "commandExecution") R.drawable.ic_terminal
                        else R.drawable.ic_file
                    )
                    Text(
                        when (entry.kind) {
                            "commandExecution" -> "Command"
                            "fileChange" -> "File changes"
                            else -> entry.kind
                        },
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Glyph(
                        if (expanded) R.drawable.ic_up else R.drawable.ic_down,
                        if (expanded) "Collapse details" else "Expand details",
                        Modifier.size(16.dp),
                    )
                }
                if (expanded)
                    SelectionContainer {
                        Text(
                            entry.text.take(60000),
                            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
            }
        }
}

@Composable
private fun FullscreenPlan(
    text: String,
    canImplement: Boolean,
    onDismiss: () -> Unit,
    onImplement: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("plan-fullscreen"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Plan",
                        Modifier.weight(1f).padding(start = 8.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-plan-fullscreen")) {
                        Text("Close")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                        SelectionContainer { Markdown(text.take(100000)) }
                    }
                }
                if (canImplement) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Button(
                        onClick = onImplement,
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                                .testTag("implement-plan-fullscreen"),
                    ) {
                        Text("Implement")
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionCard(d: Decision, st: ScreenState, actions: ConversationActions) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Your input is needed",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val p = d.params
            if (d.method == "item/tool/requestUserInput") {
                val answers = remember(d.key) { mutableStateMapOf<String, String>() }
                val questions = p.list("questions")
                questions.forEach { q ->
                    Text(q.str("question"))
                    q.list("options").forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                answers[q.str("id")] = option.str("label")
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                answers[q.str("id")] == option.str("label"),
                                { answers[q.str("id")] = option.str("label") },
                            )
                            Column {
                                Text(option.str("label"))
                                if (option.str("description").isNotBlank())
                                    Text(option.str("description"), fontSize = 12.sp)
                            }
                        }
                    }
                    OutlinedTextField(
                        answers[q.str("id")] ?: "",
                        { answers[q.str("id")] = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Your answer") },
                        visualTransformation =
                            if (q.str("isSecret") == "true") PasswordVisualTransformation()
                            else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
                Button(
                    { actions.answer(d, Decisions.answers(answers.toMap())) },
                    enabled = st.ready && questions.all { !answers[it.str("id")].isNullOrBlank() },
                ) {
                    Text("Submit answers")
                }
            } else if (
                d.method in
                    setOf(
                        "item/commandExecution/requestApproval",
                        "item/fileChange/requestApproval",
                        "item/permissions/requestApproval",
                    )
            ) {
                if (p.str("reason").isNotBlank()) Text(p.str("reason"))
                val file =
                    st.entries.find { it.id == p.str("itemId") && it.turn == p.str("turnId") }
                val context =
                    when (d.method) {
                        "item/fileChange/requestApproval" -> file?.text.orEmpty()
                        "item/permissions/requestApproval" -> p.map("permissions").toString()
                        else ->
                            p.str("command").ifBlank {
                                p.map("networkApprovalContext")
                                    .takeIf { it.isNotEmpty() }
                                    ?.toString()
                                    .orEmpty()
                            }
                    }
                if (p.str("cwd").isNotBlank()) Text(p.str("cwd"), fontSize = 12.sp)
                SelectionContainer {
                    Text(
                        context.ifBlank {
                            "Approval details unavailable. Open this task on desktop."
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                val available =
                    (p["availableDecisions"] as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.content
                    }
                Row {
                    TextButton(
                        { actions.answer(d, Decisions.result(d, false)) },
                        enabled = st.ready && (available == null || "decline" in available),
                    ) {
                        Text("Decline")
                    }
                    Button(
                        { actions.answer(d, Decisions.result(d, true)) },
                        enabled =
                            st.ready &&
                                context.isNotBlank() &&
                                (available == null || "accept" in available),
                    ) {
                        Text("Approve once")
                    }
                }
            } else Text("This request requires the desktop client: ${d.method}")
        }
    }
}
