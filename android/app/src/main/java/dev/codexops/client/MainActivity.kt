package dev.codexops.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
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
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Color(0xFFB6F568),
                onPrimary = Color(0xFF152008),
                background = Color(0xFF101410),
                surface = Color(0xFF101410),
                surfaceVariant = Color(0xFF202820),
                onSurface = Color(0xFFE7ECE2),
                onSurfaceVariant = Color(0xFFA7B1A1),
                outline = Color(0xFF414C3D),
            ),
        content = content,
    )
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
                            "REMOTE CODEX",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            st.connection,
                            fontSize = 12.sp,
                            color =
                                if (st.ready) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (st.page != "home") TextButton(onClick = model::home) { Text("Tasks") }
                },
                actions = {
                    if (st.page != "settings")
                        TextButton(onClick = model::settings) { Text("Settings") }
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
                    TextButton(onClick = model::connect) { Text("Reconnect") }
                }
            when (st.page) {
                "settings" -> Settings(st, model)
                "chat" -> Chat(st, model)
                else -> Home(st, model)
            }
        }
    }
}

@Composable
private fun Home(st: ScreenState, model: ClientModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Your work,\nwherever you are.",
            fontSize = 30.sp,
            lineHeight = 35.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "GRACE / AGENT",
            Modifier.padding(top = 12.dp, bottom = 24.dp),
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Button(onClick = model::newChat, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("＋  New chat", fontSize = 16.sp)
        }
        OutlinedTextField(
            st.query,
            model::query,
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
            placeholder = { Text("Search Grace tasks") },
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RECENT TASKS", Modifier.weight(1f), fontSize = 11.sp, letterSpacing = 1.sp)
            FilterChip(st.archived, { model.archived(!st.archived) }, label = { Text("Archived") })
        }
        LazyColumn(Modifier.weight(1f)) {
            if (st.tasks.isEmpty())
                item {
                    Text(
                        if (st.ready) "No tasks found. Start a conversation above."
                        else "Connect to Grace to see your tasks.",
                        Modifier.padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            items(st.tasks, key = { it.str("id") }) { task ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { model.openTask(task.str("id")) }
                        .padding(vertical = 16.dp)
                ) {
                    Text(
                        task.str("name").ifBlank {
                            task.str("preview").take(100).ifBlank { "Untitled task" }
                        },
                        maxLines = 2,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        task.map("status").str("type").ifBlank { "Task" } +
                            "  ·  " +
                            task.str("cwd").substringAfterLast('/'),
                        Modifier.padding(top = 6.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
            }
            if (st.listCursor != null)
                item {
                    TextButton(onClick = model::moreTasks, modifier = Modifier.fillMaxWidth()) {
                        Text("Load more tasks")
                    }
                }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun Settings(st: ScreenState, model: ClientModel) {
    var credential by remember { mutableStateOf("") }
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
            Text("Save and connect")
        }
        HorizontalDivider()
        Text("Assistant shortcut", fontWeight = FontWeight.SemiBold)
        val context = androidx.compose.ui.platform.LocalContext.current
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        ) {
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
    val nearBottom by remember {
        derivedStateOf {
            scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                it >= scroll.layoutInfo.totalItemsCount - 3
            } ?: true
        }
    }
    LaunchedEffect(st.entries.size, st.entries.lastOrNull()?.text?.length) {
        if (nearBottom && st.entries.isNotEmpty()) {
            withFrameNanos {}
            scroll.scrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
    Text(
        st.title,
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        maxLines = 2,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth(),
        state = scroll,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (st.historyCursor != null)
            item { TextButton(onClick = model::older) { Text("Load earlier messages") } }
        if (st.entries.isEmpty() && st.thread == null)
            item {
                Column(Modifier.padding(vertical = 40.dp)) {
                    Text("What shall we work on?", fontSize = 28.sp, lineHeight = 34.sp)
                    Text(
                        "Ask a question, investigate an issue,\nor pick up an idea.",
                        Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        items(st.entries.filter { it.kind != "reasoning" }, key = { it.key }) { entry ->
            Message(entry)
        }
        items(st.decisions, key = { it.key }) { decision -> DecisionCard(decision, st, model) }
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
        if (st.busy || st.activeTurn != null)
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(if (st.busy) "  Updating…" else "  Working on Grace", fontSize = 13.sp)
                }
            }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                st.draft,
                model::draft,
                Modifier.fillMaxWidth().testTag("composer"),
                placeholder = { Text("Message Grace…") },
                minLines = 2,
                maxLines = 6,
                enabled = !st.busy,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (st.activeTurn != null) "Follow-up guides the active turn"
                    else "Grace defaults",
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (st.activeTurn != null)
                    TextButton(model::stop, enabled = st.ready) { Text("Stop") }
                Button(
                    {
                        keyboard?.hide()
                        model.send()
                    },
                    modifier = Modifier.testTag("send"),
                    enabled = st.ready && !st.busy && st.draft.isNotBlank() && st.journal == null,
                ) {
                    Text(if (st.activeTurn != null) "Follow up" else "Send")
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            SelectionContainer { Text(entry.text, Modifier.padding(16.dp)) }
        }
    else if (entry.kind == "agentMessage" || entry.kind == "plan")
        SelectionContainer { Markdown(entry.text.take(100000)) }
    else
        Column {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    (if (expanded) "▾ " else "▸ ") +
                        when (entry.kind) {
                            "commandExecution" -> "Command"
                            "fileChange" -> "File changes"
                            else -> entry.kind
                        }
                )
            }
            if (expanded)
                SelectionContainer {
                    Text(
                        entry.text.take(60000),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
        }
}

@Composable
private fun DecisionCard(d: Decision, st: ScreenState, model: ClientModel) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF26301E))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Your input is needed",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
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
