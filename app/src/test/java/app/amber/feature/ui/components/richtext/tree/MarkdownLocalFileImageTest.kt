package app.amber.feature.ui.components.richtext.tree

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.context.Navigator
import app.amber.highlight.Highlighter
import app.amber.highlight.LocalHighlighter
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Round-4b: the reported 0×0 "image" was a matcher miss. JVM markdown
 * `linkLabel` keeps `[alt]` (golden 06), so `hasContentDescription("ALT")`
 * finds nothing while the Image node is already 480×320. This test pins
 * that dump and that an Image role is composed for a local `file://` PNG.
 *
 * Bitmap decode / aspect is owned by the device tests: Robolectric Coil
 * does not have to finish loading for this pin to hold.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class MarkdownLocalFileImageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settledFileImageSemanticsKeepJvmLinkLabelBrackets() {
        val file = File.createTempFile("amber-fixture-image", ".png")
        val bmp = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.rgb(32, 110, 200))
            drawText(
                "FIXTURE",
                80f,
                180f,
                Paint().apply {
                    color = Color.WHITE
                    textSize = 64f
                    isAntiAlias = true
                },
            )
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val content = "![FIXTURE_IMAGE_ALT](file://${file.absolutePath})\n"
        compose.setContent {
            val highlighter = Highlighter(RuntimeEnvironment.getApplication())
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
                LocalHighlighter provides highlighter,
                LocalToaster provides ToasterState(CoroutineScope(Dispatchers.Unconfined)),
            ) {
                MaterialTheme { MarkdownBlock(content = content) }
            }
        }
        val dump = compose.onRoot().fetchSemanticsNode().dumpNormalized()
        assertTrue(
            "JVM settled image alt must keep LINK_TEXT brackets (golden 06):\n$dump",
            dump.contains("image=[FIXTURE_IMAGE_ALT]"),
        )
        val imageNodes = compose.onRoot().fetchSemanticsNode().findImageRoles()
        assertTrue("settled file image was not composed:\n$dump", imageNodes.isNotEmpty())
    }
}

private fun androidx.compose.ui.semantics.SemanticsNode.findImageRoles(): List<androidx.compose.ui.semantics.SemanticsNode> {
    val found = ArrayList<androidx.compose.ui.semantics.SemanticsNode>()
    fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
        if (node.config.getOrNull(SemanticsProperties.Role)?.toString() == Role.Image.toString()) {
            found += node
        }
        node.children.forEach(::walk)
    }
    walk(this)
    return found
}
