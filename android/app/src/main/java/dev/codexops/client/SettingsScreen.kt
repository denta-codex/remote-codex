package dev.codexops.client

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
internal fun SettingsScreen(st: ScreenState, actions: SettingsActions) {
    var credential by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Connection", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
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
    }
}
