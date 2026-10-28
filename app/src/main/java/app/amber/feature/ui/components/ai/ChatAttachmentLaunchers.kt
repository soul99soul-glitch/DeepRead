package app.amber.feature.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.common.android.appTempFolder
import app.amber.feature.ui.hooks.ChatInputAttachmentKind
import java.io.File
import kotlin.uuid.Uuid

internal data class ChatAttachmentLaunchers(
    val takePicture: () -> Unit,
    val pickImages: () -> Unit,
    val pickFiles: () -> Unit,
)

@Composable
internal fun rememberChatAttachmentLaunchers(
    conversationId: String,
    skipCropImage: Boolean,
    importer: ChatAttachmentImporter,
    onImported: () -> Unit,
): ChatAttachmentLaunchers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun deleteTempFileAsync(file: File?) {
        if (file == null) return
        scope.launch(Dispatchers.IO) { file.delete() }
    }

    // Camera launcher
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    var cameraOwner by remember { mutableStateOf(conversationId) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            importer.importAttachment(
                ownerConversationId = cameraOwner,
                sourceUri = croppedUri,
                kind = ChatInputAttachmentKind.IMAGE,
            ) {
                onImported()
            }
        },
        onCleanup = {
            deleteTempFileAsync(cameraOutputFile)
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (skipCropImage) {
                val capturedUri = cameraOutputUri!!
                val capturedFile = cameraOutputFile
                importer.importAttachment(
                    ownerConversationId = cameraOwner,
                    sourceUri = capturedUri,
                    kind = ChatInputAttachmentKind.IMAGE,
                ) {
                    deleteTempFileAsync(capturedFile)
                    if (cameraOutputFile == capturedFile) {
                        cameraOutputFile = null
                    }
                    if (cameraOutputUri == capturedUri) {
                        cameraOutputUri = null
                    }
                    onImported()
                }
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            deleteTempFileAsync(cameraOutputFile)
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        cameraOwner = conversationId
        cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
        cameraOutputUri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", cameraOutputFile!!
        )
        cameraLauncher.launch(cameraOutputUri!!)
    }

    // Image picker launcher
    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    var imagePickerOwner by remember { mutableStateOf(conversationId) }
    var imageCropOwner by remember { mutableStateOf(conversationId) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            importer.importAttachment(
                ownerConversationId = imageCropOwner,
                sourceUri = croppedUri,
                kind = ChatInputAttachmentKind.IMAGE,
            ) {
                onImported()
            }
        },
        onCleanup = {
            deleteTempFileAsync(preCropTempFile)
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (skipCropImage) {
                    selectedUris.forEach { uri ->
                        importer.importAttachment(
                            ownerConversationId = imagePickerOwner,
                            sourceUri = uri,
                            kind = ChatInputAttachmentKind.IMAGE,
                        ) {
                            onImported()
                        }
                    }
                } else {
                    if (selectedUris.size == 1) {
                        val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(selectedUris.first())?.use { input ->
                                        tempFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                                preCropTempFile = tempFile
                                imageCropOwner = imagePickerOwner
                                launchImageCrop(tempFile.toUri())
                            }.onFailure {
                                Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                                imageCropOwner = imagePickerOwner
                                launchImageCrop(selectedUris.first())
                            }
                        }
                    } else {
                        selectedUris.forEach { uri ->
                            importer.importAttachment(
                                ownerConversationId = imagePickerOwner,
                                sourceUri = uri,
                                kind = ChatInputAttachmentKind.IMAGE,
                            ) {
                                onImported()
                            }
                        }
                    }
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    // Retained video/audio result handlers have no current tray action.
    // Video picker launcher
    var videoPickerOwner by remember { mutableStateOf(conversationId) }
    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                selectedUris.forEach { uri ->
                    importer.importAttachment(
                        ownerConversationId = videoPickerOwner,
                        sourceUri = uri,
                        kind = ChatInputAttachmentKind.VIDEO,
                    ) {
                        onImported()
                    }
                }
            }
        }

    // Audio picker launcher
    var audioPickerOwner by remember { mutableStateOf(conversationId) }
    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                selectedUris.forEach { uri ->
                    importer.importAttachment(
                        ownerConversationId = audioPickerOwner,
                        sourceUri = uri,
                        kind = ChatInputAttachmentKind.AUDIO,
                    ) {
                        onImported()
                    }
                }
            }
        }

    // File picker launcher
    var filePickerOwner by remember { mutableStateOf(conversationId) }
    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    importer.importAttachment(
                        ownerConversationId = filePickerOwner,
                        sourceUri = uri,
                        kind = ChatInputAttachmentKind.DOCUMENT,
                    ) {
                        onImported()
                    }
                }
            }
        }

    return ChatAttachmentLaunchers(
        takePicture = onLaunchCamera,
        pickImages = {
            imagePickerOwner = conversationId
            imagePickerLauncher.launch("image/*")
        },
        pickFiles = {
            filePickerOwner = conversationId
            filePickerLauncher.launch(arrayOf("*/*"))
        },
    )
}
