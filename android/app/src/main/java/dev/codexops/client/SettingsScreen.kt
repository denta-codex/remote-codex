package dev.codexops.client

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
internal fun SettingsScreen(
    st: ScreenState,
    actions: SettingsActions,
    screenshotEnabled: Boolean = true,
    onScreenshotChanged: (Boolean) -> Unit = {},
) {
    var credential by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val cover = LocalAppWindowClass.current.coverScreen
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(if (cover) 12.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (cover) 12.dp else 18.dp),
    ) {
        LaunchedEffect(st.ready) { if (st.ready) actions.refreshWeeklyUsage() }
        WeeklyUsageCard(st.weeklyUsage, st.ready, actions::refreshWeeklyUsage)
        OutlinedButton(onClick = actions::openArchives, modifier = Modifier.fillMaxWidth()) {
            Glyph(R.drawable.ic_archive)
            Spacer(Modifier.width(12.dp))
            Text("Archived chats")
        }
        OutlinedButton(onClick = actions::openSnoozed, modifier = Modifier.fillMaxWidth().testTag("open-snoozed")) {
            Glyph(R.drawable.ic_snooze)
            Spacer(Modifier.width(12.dp))
            Text("Snoozed chats")
        }
        Text(
            "Connection",
            fontSize = if (cover) 22.sp else 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Your conversations run on ${st.host.displayName}. Turn on Tailscale, then enter your connection credential once.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                st.host.endpoint,
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
                                actions.saveCredential(token)
                            }
                        }
                        .addOnCanceledListener {}
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
                    if (st.configured) "Replace connection credential"
                    else "Connection credential"
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            leadingIcon = { Glyph(R.drawable.ic_key) },
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
        )
        Button(
            onClick = {
                actions.saveCredential(credential)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Haptic feedback", Modifier.weight(1f))
            Switch(checked = st.hapticsEnabled, onCheckedChange = actions::hapticFeedback,
                enabled = st.hapticsLoaded, modifier = Modifier.testTag("haptic-feedback-toggle"))
        }
        Text("Feel a light tick when sending and softer ticks as replies arrive.", fontSize = 13.sp)
        HorizontalDivider()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Offer a report after screenshots", Modifier.weight(1f))
                Switch(checked = screenshotEnabled, onCheckedChange = onScreenshotChanged,
                    modifier = Modifier.testTag("screenshot-report-toggle"))
            }
            Text("Take a screenshot while the app is open, then tap Report or request to save it with diagnostics.", fontSize = 13.sp)
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
            "Remote Codex ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nText and images · ${st.host.displayName} / agent",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        OutlinedButton(onClick = { context.startActivity(Intent(context, CredentialRequestsActivity::class.java)) }) {
            Text("Credential requests")
        }
        Text("Updates", fontWeight = FontWeight.SemiBold)
        Text(
            "Updates are checked only when you ask. Downloads come from ${st.host.displayName} over your private connection and are verified before Android opens its installer.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        st.update.manifest?.let { update ->
            Text(
                "Remote Codex ${update.versionName} (${update.versionCode})",
                fontWeight = FontWeight.Medium,
            )
            if (update.releaseNotes.isNotBlank())
                Text(
                    update.releaseNotes,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        st.update.message?.let {
            Text(
                it,
                fontSize = 13.sp,
                color =
                    if (st.update.stage == UpdateStage.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (st.update.stage) {
            UpdateStage.Idle,
            UpdateStage.Current,
            UpdateStage.Error ->
                OutlinedButton(
                    onClick = actions::checkForUpdates,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Glyph(R.drawable.ic_refresh)
                    Spacer(Modifier.width(8.dp))
                    Text(if (st.update.stage == UpdateStage.Error) "Check again" else "Check for updates")
                }
            UpdateStage.Checking ->
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…")
                }
            UpdateStage.Available ->
                Button(
                    onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) {
                            actions.downloadAndInstallUpdate()
                        } else {
                            actions.updateInstallPermissionRequired()
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Glyph(R.drawable.ic_down)
                    Spacer(Modifier.width(8.dp))
                    Text("Download and install")
                }
            UpdateStage.Downloading -> {
                LinearProgressIndicator(
                    progress = { st.update.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = actions::cancelUpdateDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel download")
                }
            }
            UpdateStage.Installing ->
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Waiting for Android…")
                }
        }
    }
}
