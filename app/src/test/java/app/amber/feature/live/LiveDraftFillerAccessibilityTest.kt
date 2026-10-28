package app.amber.feature.live

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.amber.core.automation.AmberAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Uses the real service capture/locate/set/refresh/readback path, with Android nodes supplied by shadows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveDraftFillerAccessibilityTest {
    @Test
    fun `the default adapter fills only after real service readback matches`() = withService { context, editable, calls ->
        shadowOf(editable).setOnPerformActionListener { action, arguments ->
            calls += action
            editable.text = arguments.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
            true
        }
        val filler = LiveDraftFiller(context)
        assertEquals(LiveFillResult.FILLED, filler.fill(state(), setting()))
        assertEquals("draft reply", editable.text.toString())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SET_TEXT), calls)
    }

    @Test
    fun `the real service readback mismatch triggers copy and suppresses another target write`() = withService { context, editable, calls ->
        shadowOf(editable).setOnPerformActionListener { action, _ ->
            calls += action
            true // Target reports acceptance but retains its original text.
        }
        val filler = LiveDraftFiller(context)
        assertEquals(LiveFillResult.COPIED, filler.fill(state(), setting()))
        assertTrue(filler.isDenied("chat.target"))
        assertEquals(LiveFillResult.COPIED, filler.fill(state(), setting()))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_SET_TEXT), calls)
        assertEquals("draft reply", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
    }

    private fun withService(test: (Context, AccessibilityNodeInfo, MutableList<Int>) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(AmberAccessibilityService::class.java).create()
        val service = controller.get()
        val root = node("android.widget.LinearLayout", "A visible chat conversation with the other participant")
        val editable = node("android.widget.EditText", "").apply { isEditable = true }
        shadowOf(root).addChild(editable)
        // Service-delivered roots are sealed; obtain() creates an editable construction node.
        ReflectionHelpers.callInstanceMethod<Unit>(
            root, "setSealed", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
        )
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setRoot(root)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setTitle("Conversation")
            setBoundsInScreen(Rect(0, 0, 1080, 2000))
            setActive(true)
            setFocused(true)
        }
        shadowOf(service).setWindows(listOf(window))
        AmberAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            test(context, editable, mutableListOf())
        } finally {
            controller.destroy()
            editable.recycle()
            root.recycle()
            window.recycle()
        }
    }

    private fun node(type: String, body: String) = AccessibilityNodeInfo.obtain().apply {
        packageName = "chat.target"
        className = type
        text = body
        isEnabled = true
        isVisibleToUser = true
        setBoundsInScreen(Rect(0, 0, 1080, 2000))
    }

    private fun state() = LiveModeUiState(
        currentPackage = "chat.target", currentTitle = "Conversation",
        card = LiveModeCard(suggestions = listOf("draft reply")),
    )
    private fun setting() = LiveModeSetting(sceneOverrides = mapOf("chat.target" to "chat"))
}
