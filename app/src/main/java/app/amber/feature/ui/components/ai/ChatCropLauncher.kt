package app.amber.feature.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.Job
import app.amber.common.android.appTempFolder
import java.io.File

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Job, onCleanup: (() -> Unit)? = null
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val outputUri = cropOutputUri
        cropOutputUri = null
        if (result.resultCode == android.app.Activity.RESULT_OK && outputUri != null) {
            importCroppedImage(outputUri, onCroppedImageReady)
        } else {
            outputUri?.toFile()?.delete()
        }
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
        cropOutputUri = Uri.fromFile(outputFile)

        val cropIntent = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            setCompressionFormat(Bitmap.CompressFormat.PNG)
        }).withMaxResultSize(4096, 4096).getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}

internal fun importCroppedImage(uri: Uri, importImage: (Uri) -> Job) {
    val file = uri.toFile()
    try {
        // Import reads asynchronously; retain the source until its job finishes,
        // including any non-cancellable copy during composer disposal.
        importImage(uri).invokeOnCompletion { file.delete() }
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}
