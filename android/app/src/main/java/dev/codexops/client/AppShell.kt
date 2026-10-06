package dev.codexops.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RemoteTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors =
        if (darkTheme)
            darkColorScheme(
                primary = Color(0xFFE6E6E2), onPrimary = Color(0xFF20211F),
                background = Color(0xFF171816), surface = Color(0xFF171816),
                onBackground = Color(0xFFE8E9E4), onSurface = Color(0xFFE8E9E4),
                surfaceVariant = Color(0xFF262723), onSurfaceVariant = Color(0xFFA6AAA0),
                outline = Color(0xFF55594F), outlineVariant = Color(0xFF34372F),
                secondaryContainer = Color(0xFF29392F), onSecondaryContainer = Color(0xFFD6EBDD),
            )
        else
            lightColorScheme(
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
internal fun Glyph(
    resource: Int,
    description: String? = null,
    modifier: Modifier = Modifier.size(20.dp),
) {
    Icon(painterResource(resource), contentDescription = description, modifier = modifier)
}

@Composable
internal fun App(model: ClientModel) {
    val st by model.state.collectAsStateWithLifecycle()
    HapticProvider(st.hapticsLoaded && st.hapticsEnabled, model::hapticResumed) { AppContent(model) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(model: ClientModel) {
    val st by model.state.collectAsStateWithLifecycle()
    val report by model.reports.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    BackHandler(st.page != "home") { model.back() }
    AdaptiveWindow {
        Scaffold(
            // The system owns the usable cover-screen bounds. Reserve cutouts and
            // system bars once, including when the camera band is at the bottom.
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            data.visuals.actionLabel?.let { label ->
                                TextButton(
                                    onClick = data::performAction,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.inversePrimary,
                                    ),
                                ) { Text(label) }
                            }
                            Text(data.visuals.message, Modifier.weight(1f))
                        }
                    }
                }
            },
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    // Measure the two-line title naturally, including enlarged system text.
                    // Keep cutout padding outside the content's minimum height.
                    Row(
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ))
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (st.page != "home")
                            IconButton(onClick = model::back) {
                                Glyph(R.drawable.ic_back, "Back")
                            }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                when (st.page) {
                                    "chat" -> st.title
                                    "settings" -> "Settings"
                                    "archives" -> "Archived chats"
                                    "snoozed" -> "Snoozed chats"
                                    "todo" -> "Todo"
                                    else -> "Chats"
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = if (st.page == "chat") 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier.size(6.dp).background(
                                        if (st.ready) Color(0xFF669477)
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    )
                                )
                                Text(
                                    st.connection,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (st.page == "home") {
                            TextButton(onClick = model::openTodo) { Text("Todo") }
                            IconButton(onClick = model::newChat) { Glyph(R.drawable.ic_compose, "New chat") }
                        }
                        if (st.page !in setOf("settings", "chat"))
                            IconButton(onClick = model::settings) {
                                Glyph(R.drawable.ic_settings, "Settings")
                            }
                        BugReportMenu(model)
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
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
                        Text(if (st.page == "todo") "Todo requires Grace." else "Drafts stay on this phone.", Modifier.weight(1f), fontSize = 12.sp)
                        TextButton(onClick = model::connect) {
                            Glyph(R.drawable.ic_refresh)
                            Spacer(Modifier.width(8.dp))
                            Text("Reconnect")
                        }
                    }
                when (st.page) {
                    "todo" -> TodoScreen(st, model)
                    "settings" -> SettingsScreen(st, model,
                        screenshotEnabled = report.screenshotEnabled,
                        onScreenshotChanged = model.reports::screenshotEnabled)
                    "chat" -> key(st.thread) { ConversationScreen(st, model) }
                    else -> HomeScreen(st, model)
                }
            }
        }
        BugReportHost(model, st, snackbar)
    }
}

internal fun threadDeeplink(threadId: String): String =
    Uri.Builder().scheme("codex").authority("threads").appendPath(threadId).build().toString()

internal fun copyThreadDeeplink(context: Context, threadId: String) {
    context
        .getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Codex thread deeplink", threadDeeplink(threadId)))
    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
}
