package dev.codexops.client

import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Presentation only. Secret fields and approval decisions remain owned by the activity. */
internal class CredentialRequestLayout(
    private val activity: ComponentActivity,
    close: () -> Unit,
    refresh: () -> Unit,
    back: () -> Unit,
    choose: () -> Unit,
    release: () -> Unit,
    deny: () -> Unit,
) {
    private val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val foregroundColor = Color.parseColor(if (dark) "#E8E9E4" else "#22251F")
    private val secondary = Color.parseColor(if (dark) "#A6AAA0" else "#676D61")
    private val surface = Color.parseColor(if (dark) "#171816" else "#FAFAF7")
    private val border = Color.parseColor(if (dark) "#34372F" else "#DDDFD5")
    private val inputSurface = Color.parseColor(if (dark) "#262723" else "#EEEFE9")
    private val accent = Color.parseColor(if (dark) "#A7C5AF" else "#557962")
    private val onAccent = Color.parseColor(if (dark) "#17231B" else "#FFFFFF")
    private val metadataRows = mutableListOf<LinearLayout>()
    private val page = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(surface) }
    val connection = text("Connecting…", 13, secondary)
    val heading = text("Credential requests", 28).apply { setTypeface(typeface, Typeface.BOLD) }
    val status = text("Waiting for a credential request.", 15, secondary)
    val requests = column()
    val card = column().apply {
        tag = "credential-request-card"
        background = rounded(surface, outlined = true)
        setPadding(dp(16), dp(20), dp(16), dp(20))
        visibility = View.GONE
    }
    val title = text("", 22).apply { setTypeface(typeface, Typeface.BOLD) }
    val subtitle = text("", 15, secondary)
    val field = metadata("Requested field", R.drawable.ic_file)
    val requester = metadata("Requester", R.drawable.ic_terminal)
    val account = metadata("Account", R.drawable.ic_key)
    val expires = metadata("Expires in", R.drawable.ic_snooze)
    val details = text("", 15, secondary)
    val singlePanel = column()
    val batchPanel = column()
    val progress = text("", 14, secondary)
    val releaseButton = button("Release once", primary = true, release)
    val denyButton = button("Deny", action = deny)
    val refreshButton = button("Refresh", action = refresh)
    val backButton = button("Back to requests", action = back)
    val chooseButton = button("Choose in 1Password", primary = true, choose)
    val scroll = ScrollView(activity).apply { isSaveEnabled = false; isFillViewport = true; addView(page) }

    init {
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(foregroundColor)
            background = rounded(surface)
            contentDescription = "Close"
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(connection, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        connection.gravity = Gravity.END
        top.addView(refreshButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        page.addView(top)
        add(page, heading, 12)
        add(page, status, 12)
        add(page, requests, 12)
        val identity = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        identity.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_key)
            imageTintList = ColorStateList.valueOf(accent)
            background = rounded(inputSurface)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        identity.addView(column().apply { addView(title); add(this, subtitle, 4) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        card.addView(identity)
        // Metadata rows follow the identity, then the selection control and masked value.
        for (row in metadataRows) add(card, row, 16)
        add(singlePanel, chooseButton, 20)
        add(card, singlePanel, 0)
        add(card, batchPanel, 20)
        add(card, progress, 12)
        add(card, text("Match the account, item and field before releasing. Autofill cannot verify them.", 13, secondary), 12)
        add(card, details, 12)
        add(page, card, 16)
        add(page, releaseButton, 16)
        add(page, denyButton, 8)
        add(page, backButton, 12)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }
    }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, outlined: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat()
        if (outlined) setStroke(dp(1).coerceAtLeast(1), border)
    }
    private fun text(value: String, size: Int, color: Int = foregroundColor) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        includeFontPadding = false
        isSaveEnabled = false
    }
    private fun add(parent: LinearLayout, child: View, top: Int) {
        parent.addView(child, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
    }
    private fun metadata(label: String, icon: Int): TextView {
        val value = text("", 16)
        val row = LinearLayout(activity).apply { gravity = Gravity.TOP }
        row.addView(ImageView(activity).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(secondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14); topMargin = dp(2) })
        row.addView(column().apply { addView(text(label, 13, secondary)); add(this, value, 3) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metadataRows.add(row)
        return value
    }
    fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = label; textSize = 15f; isAllCaps = false
        minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = rounded(if (primary) accent else surface, outlined = !primary)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(secondary, if (primary) onAccent else foregroundColor)))
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(inputSurface, if (primary) accent else surface))
        setOnClickListener { action() }
    }
    fun addSingleField(input: EditText) {
        add(singlePanel, text("Selected value", 13, secondary), 12)
        styleSecret(input)
        add(singlePanel, input, 6)
    }
    fun styleSecret(input: EditText) {
        input.setTextColor(foregroundColor); input.setHintTextColor(secondary)
        input.background = rounded(inputSurface)
        input.setPadding(dp(14), dp(12), dp(14), dp(12))
        input.minHeight = dp(52)
    }
    fun group(vault: String, item: String): LinearLayout = column().apply {
        background = rounded(inputSurface); setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(text(item, 17).apply { setTypeface(typeface, Typeface.BOLD) })
        add(this, text("Vault: $vault", 13, secondary), 4)
        add(batchPanel, this, 12)
    }
    fun fieldLabel(parent: LinearLayout, field: String, occurrences: Int) {
        add(parent, text(field, 16).apply { setTypeface(typeface, Typeface.BOLD) }, 16)
        if (occurrences > 1) add(parent, text("Used $occurrences times", 13, secondary), 3)
    }
    fun addControl(parent: LinearLayout, control: View) = add(parent, control, 8)
}
