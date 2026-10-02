package dev.codexops.client

import android.content.ComponentName
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillValue
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutofillTest {
    @Test
    fun formsDeliveryAndSanitizedReport() {
        ActivityScenario.launch(AutofillTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.selectForm(AutofillTestActivity.Form.LOGIN)
                val username = activity.fields.getValue("username")
                val secret = activity.fields.getValue("secret")
                assertFalse(activity.report().contains("text edited"))
                assertArrayEquals(arrayOf(View.AUTOFILL_HINT_USERNAME), username.autofillHints)
                assertTrue(secret.autofillHints.orEmpty().contains(View.AUTOFILL_HINT_PASSWORD))
                username.setText("DISTINCTIVE_FAKE_USERNAME")
                secret.autofill(AutofillValue.forText("DISTINCTIVE_FAKE_SECRET"))
                activity.requestFill()
                val report = activity.report()
                assertTrue(report.contains("username: text edited"))
                assertTrue(report.contains("secret: autofill delivered"))
                assertFalse(report.contains("secret: text edited"))
                assertTrue(report.contains("request issued"))
                assertTrue(report.contains("secret: nonempty"))
                assertFalse(report.contains("DISTINCTIVE"))
                activity.selectForm(AutofillTestActivity.Form.CONTROL)
                assertTrue(username.text.isNullOrEmpty())
                assertTrue(secret.text.isNullOrEmpty())
                // Android may supply passwordAuto even when the app specifies no hint.
                assertFalse(activity.fields.getValue("secret").autofillHints.orEmpty().contains(View.AUTOFILL_HINT_PASSWORD))
                activity.selectForm(AutofillTestActivity.Form.SECRET)
                assertEquals(setOf("secret"), activity.fields.keys)
                assertTrue(activity.fields.getValue("secret").autofillHints.orEmpty().contains(View.AUTOFILL_HINT_PASSWORD))
                activity.fields.getValue("secret").setText("ANOTHER_FAKE_VALUE")
                activity.reset()
                assertTrue(activity.fields.values.all { it.text.isNullOrEmpty() })
                assertFalse(activity.report().contains("ANOTHER_FAKE_VALUE"))
            }
        }
    }

    @Test
    fun pickerHandoffPreservesFieldsButRecreationAndExitClearThem() {
        ActivityScenario.launch(AutofillTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.fields.getValue("secret").setText("FAKE_HANDOFF_VALUE") }
            // External picker can pause/stop our activity; neither should erase its inputs.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertEquals("FAKE_HANDOFF_VALUE", it.fields.getValue("secret").text.toString()) }
            scenario.recreate()
            scenario.onActivity {
                assertTrue(it.fields.values.all { field -> field.text.isNullOrEmpty() })
                val field = it.fields.getValue("secret")
                field.setText("FAKE_EXIT_VALUE")
                it.finish()
                assertTrue(field.text.isNullOrEmpty())
            }
        }
    }

    @Test
    fun activityIsPrivateAndCannotBeCapturedOrRestored() {
        ActivityScenario.launch(AutofillTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val info = activity.packageManager.getActivityInfo(ComponentName(activity, AutofillTestActivity::class.java), 0)
                assertFalse(info.exported)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertTrue(activity.fields.values.all { !it.isSaveEnabled && !it.isSaveFromParentEnabled })
                runBlocking {
                    try {
                        captureBugReportScreenshot(activity)
                        fail("Protected experiment should reject screenshot collection")
                    } catch (_: IllegalStateException) { }
                }
            }
        }
    }
}
