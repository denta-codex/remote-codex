package dev.codexops.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.codexops.core.*
import kotlinx.serialization.json.*

class AssistantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction("dev.codexops.client.NEW_CHAT")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        finish()
    }
}

class MainActivity : ComponentActivity() {
    private val model: ClientModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RemoteTheme { App(model) } }
        if (savedInstanceState == null && intent.action == "dev.codexops.client.NEW_CHAT")
            model.newChat()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "dev.codexops.client.NEW_CHAT") model.newChat()
    }

    override fun onStart() {
        super.onStart()
        model.foreground(true)
    }

    override fun onStop() {
        model.foreground(false)
        super.onStop()
    }
}

@Composable
fun RemoteTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors =
        if (darkTheme) darkColorScheme(
            primary = Color(0xFFE6E6E2), onPrimary = Color(0xFF20211F),
            background = Color(0xFF171816), surface = Color(0xFF171816),
            onBackground = Color(0xFFE8E9E4), onSurface = Color(0xFFE8E9E4),
            surfaceVariant = Color(0xFF262723), onSurfaceVariant = Color(0xFFA6AAA0),
            outline = Color(0xFF55594F), outlineVariant = Color(0xFF34372F),
            secondaryContainer = Color(0xFF29392F), onSecondaryContainer = Color(0xFFD6EBDD),
        ) else lightColorScheme(
            primary = Color(0xFF292D27), onPrimary = Color.White,
            background = Color(0xFFFAFAF7), surface = Color(0xFFFAFAF7),
            onBackground = Color(0xFF22251F), onSurface = Color(0xFF22251F),
            surfaceVariant = Color(0xFFEEEFE9), onSurfaceVariant = Color(0xFF676D61),
            outline = Color(0xFF818779), outlineVariant = Color(0xFFDDDFD5),
            secondaryContainer = Color(0xFFE5EEE4), onSecondaryContainer = Color(0xFF263D2C),
        )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun Glyph(resource: Int, description: String? = null, modifier: Modifier = Modifier.size(20.dp)) {
    Icon(painterResource(resource), contentDescription = description, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun App(model: ClientModel) {
    val st by model.state.collectAsStateWithLifecycle()
    BackHandler(st.page != "home") { model.home() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (st.page) { "chat" -> st.title; "settings" -> "Settings"; else -> "Remote Codex" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(
                                if (st.ready) Color(0xFF669477) else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ))
                            Text(st.connection, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    if (st.page != "home") IconButton(onClick = model::home) { Glyph(R.drawable.ic_back, "Tasks") }
                },
                actions = {
                    if (st.page != "settings")
                        IconButton(onClick = model::settings) { Glyph(R.drawable.ic_settings, "Settings") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            st.error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        it,
                        Modifier.fillMaxWidth().padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp,
                    )
                }
            }
            if (!st.ready && st.configured && st.page != "settings")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Drafts stay on this phone.", Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(onClick = model::connect) {
                        Glyph(R.drawable.ic_refresh)
                        Spacer(Modifier.width(8.dp))
                        Text("Reconnect")
                    }
                }
            when (st.page) {
                "settings" -> Settings(st, model)
                "chat" -> key(st.thread) { Chat(st, model) }
                else -> Home(st, model)
            }
        }
    }
}

@Composable
private fun Home(st: ScreenState, model: ClientModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Your tasks", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("Pick up where you left off", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = model::newChat, modifier = Modifier.size(48.dp)) {
                Glyph(R.drawable.ic_compose, "New chat")
            }
        }
        OutlinedTextField(
            st.query,
            model::query,
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            placeholder = { Text("Search tasks") },
            leadingIcon = { Glyph(R.drawable.ic_search) },
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (st.archived) "Archived" else "Recent", Modifier.weight(1f),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            FilterChip(st.archived, { model.archived(!st.archived) },
                label = { Text("Archived") },
                leadingIcon = { Glyph(if (st.archived) R.drawable.ic_check else R.drawable.ic_archive, modifier = Modifier.size(16.dp)) },
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = st.archived,
                    borderColor = MaterialTheme.colorScheme.outlineVariant),
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (st.tasks.isEmpty())
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(R.drawable.ic_chat, modifier = Modifier.size(32.dp))
                        Text(
                            if (st.ready) "No tasks found" else "Your tasks will appear here",
                            fontWeight = FontWeight.Medium,
                        )
                        Text(if (st.ready) "Start a new chat to get going." else "Connect to Grace in Settings.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            items(st.tasks, key = { it.str("id") }) { task ->
                val status = task.map("status").str("type")
                val statusLabel = when (status) {
                    "active" -> "Working"
                    "idle" -> "Ready"
                    "notLoaded" -> "Saved"
                    "systemError" -> "Needs attention"
                    else -> status.replaceFirstChar { it.uppercase() }.ifBlank { "Task" }
                }
                Surface(
                    onClick = { model.openTask(task.str("id")) },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Glyph(R.drawable.ic_chat)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(task.str("name").ifBlank {
                                task.str("preview").take(100).ifBlank { "Untitled task" }
                            }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Glyph(R.drawable.ic_folder, modifier = Modifier.size(13.dp))
                                Text(task.str("cwd").trimEnd('/').substringAfterLast('/').ifBlank { "Workspace" } + " · " + statusLabel,
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Glyph(R.drawable.ic_chevron, modifier = Modifier.size(16.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (st.listCursor != null)
                item {
                    TextButton(onClick = model::moreTasks, modifier = Modifier.fillMaxWidth()) {
                        Text("Load more tasks")
                        Spacer(Modifier.width(8.dp))
                        Glyph(R.drawable.ic_down)
                    }
                }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun Settings(st: ScreenState, model: ClientModel) {
    var credential by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Connection", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Your conversations run on Grace. Turn on Tailscale, then enter your connection credential once.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                ENDPOINT,
                Modifier.padding(16.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        OutlinedButton(
            onClick = {
                try {
                    GmsBarcodeScanning.getClient(context).startScan()
                        .addOnSuccessListener { barcode ->
                            val token = parseSetupQr(barcode.rawValue)
                            if (token == null) scanError = "Not a Remote Codex setup QR."
                            else {
                                scanError = null
                                model.saveCredential(token)
                            }
                        }
                        .addOnCanceledListener { }
                        .addOnFailureListener {
                            scanError = "Scanner unavailable. Enter the credential manually."
                        }
                } catch (_: Exception) {
                    scanError = "Scanner unavailable. Enter the credential manually."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Glyph(R.drawable.ic_qr)
            Spacer(Modifier.width(8.dp))
            Text("Scan setup QR")
        }
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            credential,
            { credential = it },
            Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (st.configured) "Replace connection credential" else "Connection credential"
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            leadingIcon = { Glyph(R.drawable.ic_key) },
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
        )
        Button(
            onClick = {
                model.saveCredential(credential)
                credential = ""
            },
            enabled = credential.trim().length >= 43,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Glyph(R.drawable.ic_check)
            Spacer(Modifier.width(8.dp))
            Text("Save and connect")
        }
        HorizontalDivider()
        Text("Assistant shortcut", fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        ) {
            Glyph(R.drawable.ic_settings)
            Spacer(Modifier.width(8.dp))
            Text("Choose default assistant")
        }
        Text(
            "Select Remote Codex as your digital assistant. Its gesture opens New chat.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Remote Codex ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nText-first preview · Grace / agent",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.Chat(st: ScreenState, model: ClientModel) {
    var confirmUnlock by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    val messages = st.entries.filter { it.kind != "reasoning" }
    // Reverse layout anchors new history at the latest message, even when that
    // message is taller than the viewport. Stable keys preserve reading position.
    LaunchedEffect(scroll) {
        snapshotFlow {
            Triple(scroll.isScrollInProgress, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        }.collect { (scrolling, index, offset) ->
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
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(if (st.busy) "Updating…" else "Working on Grace", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            "This operation will not be sent again automatically. Check the task on Grace before unlocking the composer.",
                            Modifier.padding(vertical = 8.dp),
                            fontSize = 13.sp,
                        )
                        SelectionContainer {
                            Text(
                                "Task: ${journal.str("threadId").ifEmpty{"ID not received"}}\nWorkspace: ${journal.str("cwd")}",
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
        items(st.decisions.reversed(), key = { it.key }) { decision -> DecisionCard(decision, st, model) }
        items(messages.asReversed(), key = { it.key }) { entry -> Message(entry) }
        if (st.historyCursor != null)
            item(key = "history") {
                TextButton(onClick = model::older, modifier = Modifier.fillMaxWidth()) {
                    Glyph(R.drawable.ic_up)
                    Spacer(Modifier.width(8.dp))
                    Text("Load earlier messages")
                }
            }
        if (st.entries.isEmpty() && st.thread == null)
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().fillParentMaxHeight().padding(vertical = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_compose, modifier = Modifier.size(26.dp)) }
                    }
                    Text("What shall we work on?", fontSize = 28.sp, lineHeight = 34.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("Ask a question, investigate an issue,\nor pick up an idea.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
    }
    Surface(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(8.dp)) {
            TextField(
                st.draft,
                model::draft,
                Modifier.fillMaxWidth().testTag("composer"),
                placeholder = { Text("Message Grace…") },
                minLines = 1,
                maxLines = 6,
                enabled = !st.busy,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (st.activeTurn != null) "Follow-up guides the active turn" else "Grace defaults",
                    Modifier.weight(1f), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (st.activeTurn != null)
                    IconButton(model::stop, enabled = st.ready) { Glyph(R.drawable.ic_stop, "Stop") }
                FilledIconButton(
                    {
                        followLatest = true
                        keyboard?.hide()
                        model.send()
                    },
                    modifier = Modifier.testTag("send").size(48.dp),
                    enabled = st.ready && !st.busy && st.draft.isNotBlank() && st.journal == null,
                ) {
                    Glyph(R.drawable.ic_send, if (st.activeTurn != null) "Follow up" else "Send")
                }
            }
        }
    }
    if (confirmUnlock)
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("Unlock the composer?") },
            text = {
                Text(
                    "Only continue after checking whether Grace already received this message. Sending it again could duplicate the work."
                )
            },
            confirmButton = {
                TextButton({
                    confirmUnlock = false
                    model.unlockAfterReview()
                }) {
                    Text("Unlock")
                }
            },
            dismissButton = { TextButton({ confirmUnlock = false }) { Text("Keep checking") } },
        )
}

@Composable
private fun Message(entry: Entry) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    if (entry.kind == "userMessage")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(.9f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            ) {
                SelectionContainer { Text(entry.text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), lineHeight = 23.sp) }
            }
        }
    else if (entry.kind == "agentMessage" || entry.kind == "plan")
        SelectionContainer { Markdown(entry.text.take(100000)) }
    else
        Surface(shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Glyph(if (entry.kind == "commandExecution") R.drawable.ic_terminal else R.drawable.ic_file)
                    Text(when (entry.kind) {
                        "commandExecution" -> "Command"
                        "fileChange" -> "File changes"
                        else -> entry.kind
                    }, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Glyph(if (expanded) R.drawable.ic_up else R.drawable.ic_down,
                        if (expanded) "Collapse details" else "Expand details", Modifier.size(16.dp))
                }
                if (expanded)
                    SelectionContainer {
                        Text(entry.text.take(60000), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                    }
            }
        }
}

@Composable
private fun DecisionCard(d: Decision, st: ScreenState, model: ClientModel) {
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
                    { model.answer(d, Decisions.answers(answers.toMap())) },
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
                    (p["availableDecisions"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content }
                Row {
                    TextButton(
                        { model.answer(d, Decisions.result(d, false)) },
                        enabled = st.ready && (available == null || "decline" in available),
                    ) {
                        Text("Decline")
                    }
                    Button(
                        { model.answer(d, Decisions.result(d, true)) },
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
