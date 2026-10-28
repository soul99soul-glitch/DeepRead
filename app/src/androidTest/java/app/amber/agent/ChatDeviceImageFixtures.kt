package app.amber.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.File

/**
 * Local fixture PNG + contentDescription matcher used by chat device tests.
 *
 * JVM markdown `linkLabel` is `[ALT]`; native is `ALT`. Round-4 reported 0×0
 * because tests looked up only the unbracketed form while the Image node was
 * already 480×320 (see emulator `image-math-frames.semantics.txt`).
 */
internal object ChatDeviceImageFixtures {
    const val ALT = "FIXTURE_IMAGE_ALT"

    fun imageMatcher(): SemanticsMatcher =
        SemanticsMatcher("fixture-image-alt") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { ALT in it } == true
        }

    fun writePng(context: Context, width: Int = 480, height: Int = 320, name: String = "amber-fixture-image.png"): String {
        val dirs = listOf(context.filesDir, context.applicationContext.filesDir)
            .map { it.canonicalFile }
            .distinctBy { it.path }
        var written: File? = null
        for (dir in dirs) {
            dir.mkdirs()
            val file = File(dir, name)
            if (!file.exists() || file.length() == 0L) {
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
            }
            if (written == null) written = file
        }
        return "file://${requireNotNull(written).absolutePath}"
    }

    fun bounds(compose: ComposeTestRule): androidx.compose.ui.geometry.Rect? {
        val matcher = imageMatcher()
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = false).fetchSemanticsNodes() +
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        val best = nodes.maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }
        if (best != null && best.boundsInRoot.height > 0f) return best.boundsInRoot
        val images = compose.onAllNodes(
            SemanticsMatcher("Role.Image") { it.config.getOrNull(SemanticsProperties.Role) == Role.Image },
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        android.util.Log.i(
            "AmberDeviceImage",
            "alt-miss images=" + images.joinToString { node ->
                val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)
                val b = node.boundsInRoot
                "desc=$desc ${b.width}x${b.height}"
            },
        )
        return images.maxByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }?.boundsInRoot
    }
}
