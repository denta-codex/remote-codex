package dev.codexops.client

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.codexops.core.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private data class HistoryScrollAnchor(val key: String, val entryKey: String?, val offset: Int, val motion: Long)

@Composable
internal fun ColumnScope.ConversationScreen(st: ScreenState, actions: ConversationActions) {
    val cover = LocalAppWindowClass.current.coverScreen
    var confirmUnlock by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val haptics = LocalAppHaptics.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var followLatest by remember { mutableStateOf(true) }
    var initiallyPositioned by remember { mutableStateOf(false) }
    var openingAnchorKey by remember { mutableStateOf<String?>(null) }
    var initialRenderedKey by remember { mutableStateOf<String?>(null) }
    var readerScrolled by remember { mutableStateOf(false) }
    var upwardMotion by remember { mutableLongStateOf(0L) }
    var movingUp by remember { mutableStateOf(false) }
    var readerMotion by remember { mutableLongStateOf(0L) }
    val nearHistory by remember { derivedStateOf { scroll.firstVisibleItemIndex <= 3 } }
    val readingScroll = remember(scroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Stop before the next layout/streaming update, even for a short drag.
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    readerMotion++
                    readerScrolled = true
                    movingUp = available.y > 0f
                    if (movingUp) upwardMotion++
                    followLatest = false
                }
                return Offset.Zero
            }
        }
    }
    val saveDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            uri?.let(actions::saveFile)
        }
    val messages = st.entries.filter { it.kind != "reasoning" }
    val rows = remember(st.entries, st.activeTurn, st.ready, st.turnStatuses, st.decisions, st.attention, st.historyGroupStarts) {
        conversationRows(st.entries, st.activeTurn, st.ready, st.turnStatuses,
            st.decisions.any { it.blocksUser } || st.attention, st.historyGroupStarts)
    }
    val openingMessageKey = rows.lastOrNull { it is ConversationRow.Message }?.key
    var changesTurn by rememberSaveable(st.host.endpoint, st.thread) { mutableStateOf<String?>(null) }
    rows.filterIsInstance<ConversationRow.Changes>().firstOrNull { it.turn == changesTurn }?.let {
        TurnChangesViewer(it, onDismiss = { changesTurn = null })
    }
    val activityRepresentsTurn = rows.filterIsInstance<ConversationRow.Activity>().any { it.representsActiveTurn }
    val actionablePlan =
        messages.lastOrNull()?.takeIf {
            it.kind == "plan" &&
                it.completed &&
                st.collaborationModes.any { preset ->
                    preset.mode == "default" &&
                        preset.turnSetting(st.collaborationModel()) != null
                }
        }
    // A normal list anchors the top of the visible message. Reverse layout instead
    // anchors its bottom, moving the paragraph being read when that message grows.
    LaunchedEffect(scroll) {
        snapshotFlow {
                initiallyPositioned && readerScrolled && !scroll.isScrollInProgress && !scroll.canScrollForward
            }
            .collect { atRestAtEnd ->
                if (atRestAtEnd) followLatest = true
            }
    }
    LaunchedEffect(scroll, rows, st.busy, st.historyCursor, st.recoveryHasMore, initialRenderedKey) {
        if (initiallyPositioned || st.busy) return@LaunchedEffect
        val latestMessage = rows.indexOfLast { it is ConversationRow.Message }
        if (latestMessage < 0) return@LaunchedEffect
        withFrameNanos { }
        // Open at the beginning of the newest message, including replies taller
        // than the viewport. Keep it still until the reader reaches the end.
        followLatest = false
        scroll.scrollToItem(latestMessage + if (st.historyCursor != null || st.recoveryHasMore) 1 else 0)
        val entry = (rows[latestMessage] as ConversationRow.Message).entry
        // Markdown starts as a small loading box. Re-anchor once its real
        // height is known so clamping that placeholder cannot hide the start.
        if (entry.kind in setOf("agentMessage", "plan") && entry.text.isNotBlank() &&
            initialRenderedKey != entry.key) {
            snapshotFlow { scroll.layoutInfo }.collect { layout ->
                // Earlier Markdown can also grow and push the loading reply
                // off-screen. Keep it composed until its render callback arrives.
                if (layout.visibleItemsInfo.none { it.key == entry.key })
                    scroll.scrollToItem(latestMessage + if (st.historyCursor != null || st.recoveryHasMore) 1 else 0)
            }
            return@LaunchedEffect
        }
        openingAnchorKey = entry.key
        initiallyPositioned = true
    }
    LaunchedEffect(scroll, initiallyPositioned, readerScrolled, followLatest, rows, st.historyCursor, st.recoveryHasMore) {
        if (!initiallyPositioned || readerScrolled || followLatest) return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == openingAnchorKey }
        if (index < 0) return@LaunchedEffect
        val target = index + if (st.historyCursor != null || st.recoveryHasMore) 1 else 0
        // Keep the opening anchor while surrounding Markdown finishes measuring.
        // A user scroll, send, or jump ends this anchoring immediately.
        snapshotFlow { scroll.layoutInfo }.collect {
            if (scroll.canScrollForward &&
                (scroll.firstVisibleItemIndex != target || scroll.firstVisibleItemScrollOffset != 0))
                scroll.scrollToItem(target)
        }
    }
    LaunchedEffect(scroll, followLatest, initiallyPositioned, messages.isNotEmpty()) {
        if (!initiallyPositioned || !followLatest || messages.isEmpty()) return@LaunchedEffect
        // Observe measured content, including asynchronous Markdown and composer
        // resizing. Serial collection lets each animation finish while coalescing
        // new layouts; a token must not cancel and restart the animation.
        snapshotFlow { scroll.layoutInfo }.collect { layout ->
            // On a cover display the IME can temporarily consume the entire
            // timeline. There is no visible end to follow until it has height again.
            if (layout.totalItemsCount > 0 && layout.viewportSize.height > 0 && scroll.canScrollForward) {
                scroll.animateScrollToItem(layout.totalItemsCount - 1)
            }
        }
    }
    // One background page after opening. Further pages require fresh upward
    // input near the boundary; layout changes alone never drain the history.
    var pendingHistoryAnchor by remember { mutableStateOf<HistoryScrollAnchor?>(null) }
    var prefetched by remember { mutableStateOf(false) }
    var consumedMotion by remember { mutableLongStateOf(0L) }
    LaunchedEffect(initiallyPositioned, st.ready, st.busy, st.historyLoading,
        st.historyFailed, st.historyRecovery, st.historyCursor, st.recoveryHasMore, upwardMotion, movingUp, nearHistory) {
        val freshMotion = upwardMotion > consumedMotion
        if (st.historyLoading || !movingUp) consumedMotion = upwardMotion
        if (!initiallyPositioned || !st.ready || st.busy || st.historyLoading ||
            st.historyFailed || st.historyRecovery != null ||
            (st.historyCursor == null && !st.recoveryHasMore)) return@LaunchedEffect
        if (!prefetched || (freshMotion && movingUp && nearHistory)) {
            consumedMotion = upwardMotion
            prefetched = true
            if (readerScrolled) {
                val visible = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key != "history" }
                val row = rows.firstOrNull { it.key == visible?.key }
                if (visible != null && row != null) pendingHistoryAnchor = HistoryScrollAnchor(
                    row.key, (row as? ConversationRow.Activity)?.entries?.firstOrNull()?.key,
                    -visible.offset, readerMotion)
            }
            actions.older()
        }
    }
    LaunchedEffect(st.historyLoading, st.entries) {
        if (st.historyLoading) return@LaunchedEffect
        val anchor = pendingHistoryAnchor ?: return@LaunchedEffect
        pendingHistoryAnchor = null
        if (readerMotion != anchor.motion || followLatest) return@LaunchedEffect
        val index = rows.indexOfFirst { row ->
            row.key == anchor.key || (anchor.entryKey != null && row is ConversationRow.Activity &&
                row.entries.any { it.key == anchor.entryKey })
        }
        if (index >= 0) scroll.requestScrollToItem(
            index + if (st.historyCursor != null || st.recoveryHasMore) 1 else 0, anchor.offset)
    }
    val latestTurn = st.entries.lastOrNull()?.turn
    val latestReply = st.entries.lastOrNull { it.turn == latestTurn && it.kind in setOf("agentMessage", "plan") }
    val signature = remember(st.entries) { latestTurn?.let { turn -> replySignature(turn, st.entries.filter { it.turn == turn }.map { it.raw }) } }
    LaunchedEffect(st.thread, signature, st.busy, st.activeTurn, st.appForeground, scroll) {
        val thread = st.thread ?: return@LaunchedEffect
        if (signature == null || st.busy || st.activeTurn != null || !st.appForeground) return@LaunchedEffect
        // Allow the new reply to be measured before consulting its visible bounds.
        withFrameNanos { }
        withFrameNanos { }
        snapshotFlow {
            val layout = scroll.layoutInfo
            val reply = layout.visibleItemsInfo.firstOrNull { it.key == latestReply?.key }
            reply != null && reply.offset + reply.size <= layout.viewportEndOffset && !scroll.isScrollInProgress
        }.collect { replyViewed -> if (replyViewed) actions.viewedReply(thread, signature) }
    }
    st.historyNotice?.let { Text(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall) }
    st.historyRecovery?.let { recovery ->
        TextButton(onClick = actions::recoverHistory, enabled = !st.historyLoading && !st.busy,
            modifier = Modifier.fillMaxWidth().testTag("recover-history")) { Text(recovery.label) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(readingScroll).testTag("timeline"),
            state = scroll,
            contentPadding = PaddingValues(
                horizontal = if (cover) 12.dp else 20.dp,
                vertical = if (cover) 8.dp else 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                if (cover) 12.dp else 20.dp,
                Alignment.Bottom,
            ),
        ) {
            if (st.historyCursor != null || st.recoveryHasMore)
                item(key = "history") {
                    if (st.historyFailed) {
                        TextButton(onClick = actions::older,
                            enabled = st.ready && !st.historyLoading && st.historyRecovery == null,
                            modifier = Modifier.fillMaxWidth()) { Text("Retry loading earlier messages") }
                    } else {
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                            if (st.historyLoading) CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
            items(rows, key = { it.key }) { row ->
                if (row is ConversationRow.Activity) ToolActivityRow(row,
                    "${st.host.endpoint}/${st.thread}", actions, st.appForeground)
                else if (row is ConversationRow.Changes) TurnChangesRow(row) { changesTurn = row.turn }
                else if (row is ConversationRow.Message) {
                    val entry = row.entry
                    Message(
                        entry = entry,
                        actions = actions,
                        visualizationScope = "${st.host.endpoint}/${st.thread}/${entry.key}",
                        canImplement =
                            entry.key == actionablePlan?.key &&
                                st.ready &&
                                !st.busy &&
                                st.activeTurn == null &&
                                st.queuedMessages.isEmpty() &&
                                st.queueReady &&
                                st.journal == null,
                        onImplement = { actions.implementPlan(entry.key) },
                        onTextRendered = {
                            if (!initiallyPositioned && entry.key == openingMessageKey) initialRenderedKey = entry.key
                            st.liveAssistantText?.takeIf {
                                it.thread == st.thread && it.key == entry.key &&
                                    it.textLength == entry.text.length && it.textHash == entry.text.hashCode()
                            }?.let { update ->
                                val layout = scroll.layoutInfo
                                val item = layout.visibleItemsInfo.firstOrNull { it.key == entry.key }
                                val visible = followLatest && st.appForeground && st.ready &&
                                    lifecycle.isAtLeast(Lifecycle.State.RESUMED) && layout.viewportSize.height > 0 &&
                                    item != null && item.offset < layout.viewportEndOffset &&
                                    item.offset + item.size > layout.viewportStartOffset &&
                                    item.offset + item.size <= layout.viewportEndOffset
                                actions.assistantTextRendered(update, visible)?.let(haptics::stream)
                            }
                        },
                    )
                }
            }
            items(st.decisions, key = { it.key }) { decision ->
                DecisionCard(decision, st, actions)
            }
            if (st.attention && st.decisions.isEmpty())
                item(key = "attention") {
                    Text(
                        "Needs attention. Approval details are unavailable here; open this task on desktop.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            st.journal?.let { journal ->
                item(key = "journal") {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                when {
                                    st.busy -> StockWorkspaceAdapter.progress(journal.str("stage")).trimEnd('…')
                                    journal.containsKey("queuedMessage") -> "Queue action needs review"
                                    journal.str("failure").isNotEmpty() -> "Setup needs attention"
                                    journal.str("stage") == "accepted" -> "Message accepted"
                                    else -> "Operation needs review"
                                },
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                journal.str("failure").ifEmpty {
                                    "This operation will not be sent again automatically. Check the task on ${st.host.displayName} before discarding its record."
                                },
                                Modifier.padding(vertical = 8.dp),
                                fontSize = 13.sp,
                            )
                            SelectionContainer {
                                Text(
                                    if (journal.containsKey("queuedMessage")) journal.str("text")
                                    else "Task: ${journal.str("threadId").ifEmpty { "ID not received" }}\nWorkspace: ${journal.str("cwd").ifEmpty { "Existing task" }}",
                                    fontSize = 11.sp,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!st.busy && st.ready) {
                                    TextButton(
                                        onClick = actions::recoverPreparation,
                                        modifier = Modifier.testTag("check-setup"),
                                    ) {
                                        Text(
                                            if (journal.str("stage") in setOf("queuedRemoved", "queuedSteerRejected"))
                                                "Send saved message"
                                            else "Check and continue"
                                        )
                                    }
                                }
                                TextButton(onClick = { confirmUnlock = true }) {
                                    Text("Discard record…")
                                }
                            }
                        }
                    }
                }
            }
            if (st.busy || (st.activeTurn != null && !activityRepresentsTurn))
                item(key = "activity") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            if (st.busy && st.journal != null)
                                StockWorkspaceAdapter.progress(st.journal.str("stage"))
                            else if (st.busy) "Updating…"
                            else if (!st.ready) "Connection lost"
                            else if (st.attention || st.decisions.any { it.blocksUser }) "Waiting for you"
                            else "Waiting for the next update",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            if (st.entries.isEmpty() && st.thread == null)
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().fillParentMaxHeight()
                            .padding(vertical = if (cover) 8.dp else 40.dp),
                        verticalArrangement = Arrangement.spacedBy(
                            if (cover) 8.dp else 14.dp,
                            Alignment.CenterVertically,
                        ),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(
                                Modifier.size(if (cover) 40.dp else 52.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Glyph(
                                    R.drawable.ic_compose,
                                    modifier = Modifier.size(if (cover) 20.dp else 26.dp),
                                )
                            }
                        }
                        Text(
                            "What shall we work on?",
                            fontSize = if (cover) 22.sp else 28.sp,
                            lineHeight = if (cover) 26.sp else 34.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!cover)
                            Text(
                                "Ask a question, investigate an issue,\nor pick up an idea.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            if (messages.isNotEmpty())
                item(key = "latest-end") { Spacer(Modifier.height(1.dp)) }
        }
        if (!followLatest)
            FilledTonalIconButton(
                onClick = { followLatest = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    .testTag("jump-to-latest"),
            ) {
                Glyph(R.drawable.ic_down, "Jump to latest")
            }
    }
    if (st.thread != null && (!cover || st.decisions.isEmpty())) ConversationSummary(st, actions)
    if (!cover || st.decisions.isEmpty())
        ConversationComposer(
            state = st,
            actions = actions,
            onSend = { followLatest = true },
        )
    if (confirmUnlock)
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("Discard the saved record?") },
            text = {
                Text(
                    "Only discard it after checking the task and workspace on ${st.host.displayName}. Starting again could duplicate a task or leave another worktree behind."
                )
            },
            confirmButton = {
                TextButton({
                    confirmUnlock = false
                    actions.unlockAfterReview()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton({ confirmUnlock = false }) { Text("Keep checking") }
            },
        )
    st.filePreview?.let { preview ->
        FilePreviewDialog(
            preview = preview,
            actions = actions,
            onDismiss = actions::dismissFile,
            onSave = { saveDocument.launch(preview.reference.displayName) },
        )
    }
}

@Composable
private fun Message(
    entry: Entry,
    actions: ConversationActions,
    visualizationScope: String,
    canImplement: Boolean,
    onImplement: () -> Unit,
    onTextRendered: (() -> Unit)? = null,
) {
    var planFullscreen by rememberSaveable(entry.key) { mutableStateOf(false) }
    if (entry.kind == "userMessage")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(.9f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MediaGallery(entry.media, actions)
                    FileReferenceList(entry.files, actions)
                    if (entry.text.isNotBlank())
                        SelectionContainer {
                            Text(entry.text, lineHeight = 23.sp)
                        }
                }
            }
        }
    else if (entry.kind == "agentMessage")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MediaGallery(entry.media, actions)
            if (entry.text.isNotBlank())
                VisualizationAwareMarkdown(entry.text.take(100000), visualizationScope, entry.completed, actions, onTextRendered)
        }
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
                MediaGallery(entry.media, actions)
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
                SelectionContainer { FileAwareMarkdown(entry.text.take(100000), actions, onTextRendered) }
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
                actions = actions,
                canImplement = canImplement,
                onDismiss = { planFullscreen = false },
                onImplement = {
                    planFullscreen = false
                    onImplement()
                },
            )
    }
    else if (entry.kind == "imageView" || entry.kind == "imageGeneration")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaGallery(entry.media, actions)
            if (entry.text.isNotBlank()) Text(entry.text, fontSize = 13.sp)
        }

}

@Composable
private fun FullscreenPlan(
    text: String,
    actions: ConversationActions,
    canImplement: Boolean,
    onDismiss: () -> Unit,
    onImplement: () -> Unit,
) {
    val cover = LocalAppWindowClass.current.coverScreen
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("plan-fullscreen"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
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
                        .padding(
                            horizontal = if (cover) 12.dp else 24.dp,
                            vertical = if (cover) 10.dp else 20.dp,
                        ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                        SelectionContainer { FileAwareMarkdown(text.take(100000), actions) }
                    }
                }
                if (canImplement) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Button(
                        onClick = onImplement,
                        modifier =
                            Modifier.fillMaxWidth().padding(
                                    horizontal = if (cover) 12.dp else 20.dp,
                                    vertical = if (cover) 8.dp else 12.dp,
                                )
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
    val cover = LocalAppWindowClass.current.coverScreen
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
    ) {
        Column(
            Modifier.padding(if (cover) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (cover) 8.dp else 10.dp),
        ) {
            Text(
                if (d.blocksUser) "Your input is needed" else "Question available",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val p = d.params
            if (d.method == McpElicitation.METHOD) {
                McpElicitationInput(d, st.ready) { result -> actions.answer(d, result) }
            } else if (d.method == "item/tool/requestUserInput") {
                val selections = remember(d.key, d.epoch) { mutableStateMapOf<String, String>() }
                val notes = remember(d.key, d.epoch) { mutableStateMapOf<String, String>() }
                var confirmUnanswered by remember(d.key, d.epoch) { mutableStateOf(false) }
                val questions = p.list("questions")
                val unanswered = questions.any {
                    selections[it.str("id")] == null && notes[it.str("id")].isNullOrBlank()
                }
                if (confirmUnanswered) {
                    AlertDialog(
                        onDismissRequest = { confirmUnanswered = false },
                        title = { Text("Submit with unanswered questions?") },
                        text = { Text("Unanswered questions will be sent without an answer.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmUnanswered = false
                                    actions.answer(d, Decisions.questionAnswers(questions, selections.toMap(), notes.toMap()))
                                },
                                enabled = st.ready,
                                modifier = Modifier.testTag("confirm-unanswered"),
                            ) { Text("Submit") }
                        },
                        dismissButton = {
                            TextButton({ confirmUnanswered = false }) { Text("Keep answering") }
                        },
                    )
                }
                questions.forEach { q ->
                    val id = q.str("id")
                    val options = q.list("options")
                    Text(q.str("question"))
                    val choices = if (options.isNotEmpty() && q["isOther"] == JsonPrimitive(true))
                        options + obj("label" to s(Decisions.OTHER_ANSWER)) else options
                    choices.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selections[id] = option.str("label")
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selections[id] == option.str("label"),
                                { selections[id] = option.str("label") },
                            )
                            Column {
                                Text(option.str("label"))
                                if (option.str("description").isNotBlank())
                                    Text(option.str("description"), fontSize = 12.sp)
                            }
                        }
                    }
                    OutlinedTextField(
                        notes[id] ?: "",
                        { notes[id] = it },
                        Modifier.fillMaxWidth().testTag("question-text-$id"),
                        label = { Text(if (options.isEmpty()) "Your answer" else "Additional notes (optional)") },
                        visualTransformation =
                            if (q.str("isSecret") == "true") PasswordVisualTransformation()
                            else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
                Button(
                    {
                        if (unanswered) confirmUnanswered = true
                        else actions.answer(d, Decisions.questionAnswers(questions, selections.toMap(), notes.toMap()))
                    },
                    enabled = st.ready,
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
                val fileContext = st.fileApprovalContexts[d.key] ?: FileApprovalContext()
                val context =
                    when (d.method) {
                        "item/fileChange/requestApproval" -> fileContext.text
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
                if (p.str("kind") == "writeStdin") Text("Send input to an existing terminal")
                SelectionContainer {
                    Text(
                        context.ifBlank {
                            if (d.method == "item/fileChange/requestApproval" && fileContext.loading)
                                "Loading file changes…"
                            else "Approval details unavailable. Open this task on desktop."
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                if (d.method == "item/fileChange/requestApproval" && context.isBlank()) {
                    TextButton(
                        { actions.refreshApprovalContext(d) },
                        enabled = st.ready && !fileContext.loading,
                    ) { Text("Retry details") }
                }
                if (d.method == "item/permissions/requestApproval")
                    PermissionApproval(d, st.ready, actions)
                else ApprovalDecisionButtons(d, st.ready, context.isNotBlank(), actions)
            }
        }
    }
}

@Composable
private fun ApprovalDecisionButtons(d: Decision, ready: Boolean, hasContext: Boolean, actions: ConversationActions) {
    val choices = remember(d) { ApprovalChoices.choices(d) }
    var expanded by remember(d) { mutableStateOf(false) }
    var confirmation by remember(d) { mutableStateOf<ApprovalChoice?>(null) }
    fun enabled(choice: ApprovalChoice) = ready && (!choice.grantsAccess || hasContext)
    Row {
        choices.firstOrNull { it.value == s("decline") }?.let { choice ->
            TextButton({ actions.answer(d, choice.result) }, enabled = enabled(choice)) { Text(choice.label) }
        }
        choices.firstOrNull { it.value == s("accept") }?.let { choice ->
            Button({ actions.answer(d, choice.result) }, enabled = enabled(choice)) { Text(choice.label) }
        }
    }
    val extended = choices.filter { it.value != s("decline") && it.value != s("accept") }
    if (extended.isNotEmpty()) {
        TextButton({ expanded = !expanded }) { Text(if (expanded) "Fewer choices" else "More choices") }
        if (expanded) extended.forEach { choice ->
            TextButton({
                if (choice.consequence.isNotEmpty()) confirmation = choice else actions.answer(d, choice.result)
            }, enabled = enabled(choice)) { Text(choice.label) }
        }
    }
    if (choices.isEmpty()) Text("No supported approval choices. Open this task on desktop.")
    confirmation?.let { choice ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(choice.label) },
            text = { SelectionContainer { Text(choice.consequence, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = {
                TextButton({ confirmation = null; actions.answer(d, choice.result) }, enabled = enabled(choice)) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton({ confirmation = null }) { Text("Back") } },
        )
    }
}

@Composable
private fun PermissionApproval(d: Decision, ready: Boolean, actions: ConversationActions) {
    val selection = remember(d) { PermissionSelection(d) }
    var selected by remember(d) { mutableStateOf(selection.options.map { it.id }.toSet()) }
    var session by remember(d) { mutableStateOf(false) }
    var confirmSession by remember(d) { mutableStateOf(false) }
    selection.options.forEach { option ->
        Row(Modifier.fillMaxWidth().clickable {
            selected = if (option.id in selected) selected - option.id else selected + option.id
        }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(option.id in selected, { checked ->
                selected = if (checked) selected + option.id else selected - option.id
            })
            Text(option.label, Modifier.weight(1f), fontSize = 12.sp)
        }
    }
    Row(Modifier.fillMaxWidth().clickable { session = !session }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(session, { session = it })
        Text("Keep selected permissions for this session", Modifier.weight(1f))
    }
    Text(if (session) "Selected permissions will remain available for subsequent turns in this session."
        else "Selected permissions apply only to this turn.", fontSize = 12.sp)
    Row {
        TextButton({ actions.answer(d, selection.result(emptySet())) }, enabled = ready) { Text("Decline") }
        Button({
            if (session) confirmSession = true else actions.answer(d, selection.result(selected))
        }, enabled = ready && selected.isNotEmpty()) { Text(if (session) "Approve for session" else "Approve once") }
    }
    if (selection.options.isEmpty()) Text("No supported permissions to grant. Open this task on desktop.")
    if (confirmSession) AlertDialog(
        onDismissRequest = { confirmSession = false },
        title = { Text("Approve for session") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("These permissions remain available for subsequent turns in this session:")
            selection.options.filter { it.id in selected }.forEach { Text(it.label, fontSize = 12.sp) }
        } },
        confirmButton = {
            TextButton({ confirmSession = false; actions.answer(d, selection.result(selected, "session")) },
                enabled = ready && selected.isNotEmpty()) { Text("Confirm") }
        },
        dismissButton = { TextButton({ confirmSession = false }) { Text("Back") } },
    )
}
