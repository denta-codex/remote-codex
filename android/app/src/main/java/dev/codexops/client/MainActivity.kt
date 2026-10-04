package dev.codexops.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

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
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == "dev.codexops.client.NEW_CHAT") model.newChat()
        else if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val share = runCatching { intent.incomingShare() }.getOrNull()
            if (share != null) model.receiveShare(share)
            else android.widget.Toast.makeText(this, "This share could not be opened.", android.widget.Toast.LENGTH_LONG).show()
        }
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
