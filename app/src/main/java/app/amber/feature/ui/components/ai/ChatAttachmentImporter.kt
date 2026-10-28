package app.amber.feature.ui.components.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.transformers.DocumentAsPromptTransformer
import app.amber.agent.R
import app.amber.core.files.FilesManager
import app.amber.feature.ui.hooks.ChatInputAttachmentImport
import app.amber.feature.ui.hooks.ChatInputAttachmentKind
import app.amber.feature.ui.hooks.ChatInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.uuid.Uuid

private fun Uri.attachmentFallbackName(kind: ChatInputAttachmentKind): String = when (kind) {
    ChatInputAttachmentKind.IMAGE -> "image"
    ChatInputAttachmentKind.VIDEO -> "video"
    ChatInputAttachmentKind.AUDIO -> "audio"
    ChatInputAttachmentKind.DOCUMENT -> "file"
}

private fun attachmentMimeFallback(kind: ChatInputAttachmentKind): String = when (kind) {
    ChatInputAttachmentKind.IMAGE -> "image/*"
    ChatInputAttachmentKind.VIDEO -> "video/*"
    ChatInputAttachmentKind.AUDIO -> "audio/*"
    ChatInputAttachmentKind.DOCUMENT -> "application/octet-stream"
}

private fun attachmentPart(
    uri: Uri,
    kind: ChatInputAttachmentKind,
    displayName: String,
    mimeType: String,
): UIMessagePart = when (kind) {
    ChatInputAttachmentKind.IMAGE -> UIMessagePart.Image(uri.toString())
    ChatInputAttachmentKind.VIDEO -> UIMessagePart.Video(uri.toString(), mime = mimeType)
    ChatInputAttachmentKind.AUDIO -> UIMessagePart.Audio(
        url = uri.toString(),
        fileName = displayName,
        mime = mimeType,
    )
    ChatInputAttachmentKind.DOCUMENT -> UIMessagePart.Document(
        url = uri.toString(),
        fileName = displayName,
        mime = mimeType,
    )
}

internal fun attachmentReadWarning(content: String, readFailureMessage: String): String? {
    val parserFailure = content.startsWith("[ERROR,") ||
        content.startsWith("Error parsing PDF file:", ignoreCase = true) ||
        content.startsWith("Error parsing DOCX file:", ignoreCase = true) ||
        content.startsWith("Error parsing document XML:", ignoreCase = true) ||
        content.startsWith("Error parsing PPTX file:", ignoreCase = true) ||
        content.startsWith("Error parsing EPUB file:", ignoreCase = true) ||
        content.startsWith("Unable to find document content in DOCX file") ||
        content.startsWith("No slides found in PPTX file") ||
        content.startsWith("No readable slides in PPTX file") ||
        content.startsWith("Unable to find OPF file in EPUB") ||
        content.startsWith("Unable to read OPF file in EPUB") ||
        content.startsWith("No readable content found in EPUB file")
    return readFailureMessage.takeIf { parserFailure }
}

private fun attachmentWasTruncated(content: String): Boolean =
    "[TRUNCATED:" in content

/** Owns one composer's attachment import transactions; accepted parts remain in ChatInputState. */
internal class ChatAttachmentImporter(
    private val state: ChatInputState,
    private val filesManager: FilesManager,
    private val context: Context,
    private val scope: CoroutineScope,
    private val attachmentReadFailureMessage: String,
    private val showError: (String) -> Unit,
) {
    private suspend fun sourceSizeBytes(uri: Uri): Long? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            return@withContext runCatching { uri.toFile() }
                .getOrNull()
                ?.takeIf { it.isFile }
                ?.length()
        }
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun importAttachment(
        ownerConversationId: String,
        sourceUri: Uri,
        kind: ChatInputAttachmentKind,
        displayName: String? = null,
        mimeType: String? = null,
        existingImport: ChatInputAttachmentImport? = null,
        onComplete: () -> Unit = {},
    ): Job {
        return scope.launch {
            var import: ChatInputAttachmentImport? = null
            var copiedUri: Uri? = null

            fun markFailed(message: String, sizeBytes: Long? = null): Boolean {
                val currentImport = import ?: return false
                return state.failAttachmentImport(
                    conversationId = ownerConversationId,
                    importId = currentImport.id,
                    errorMessage = message,
                    sizeBytes = sizeBytes,
                )
            }

            fun deleteCopiedFile() {
                copiedUri?.let { uri ->
                    // FilesManager deletion is app-scoped, so it remains
                    // valid even when the composable scope is cancelled.
                    filesManager.deleteChatFiles(listOf(uri))
                    copiedUri = null
                }
            }

            try {
                val resolvedName = displayName ?: withContext(Dispatchers.IO) {
                    filesManager.getFileNameFromUri(sourceUri)
                        ?: sourceUri.lastPathSegment
                        ?: sourceUri.attachmentFallbackName(kind)
                }
                val resolvedMime = mimeType ?: withContext(Dispatchers.IO) {
                    filesManager.getFileMimeType(sourceUri)
                        ?: attachmentMimeFallback(kind)
                }
                val sourceSize = existingImport?.sizeBytes ?: sourceSizeBytes(sourceUri)
                import = existingImport ?: state.beginAttachmentImport(
                    conversationId = ownerConversationId,
                    sourceUri = sourceUri,
                    displayName = resolvedName,
                    mimeType = resolvedMime,
                    kind = kind,
                    sizeBytes = sourceSize,
                )
                if (import == null) return@launch

                // Record the URI before returning to the composer: cancellation can
                // interrupt that return before an outer assignment receives the copy.
                withContext(NonCancellable + Dispatchers.IO) {
                    copiedUri = filesManager.createChatFilesByContents(listOf(sourceUri)).firstOrNull()
                }
                currentCoroutineContext().ensureActive()

                if (copiedUri == null) {
                    val accepted = markFailed(
                        message = context.getString(R.string.parity_attachment_import_failed_detail),
                        sizeBytes = sourceSize,
                    )
                    if (accepted) {
                        showError(context.getString(R.string.parity_attachment_import_failed, resolvedName))
                    }
                    return@launch
                }

                val part = attachmentPart(
                    uri = copiedUri!!,
                    kind = kind,
                    displayName = import!!.displayName,
                    mimeType = import!!.mimeType,
                )
                val documentReadReport = if (part is UIMessagePart.Document) {
                    try {
                        DocumentAsPromptTransformer.extractText(part)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        "[ERROR, ${error.message.orEmpty()}]"
                    }
                } else {
                    null
                }
                currentCoroutineContext().ensureActive()

                val applied = state.completeAttachmentImport(
                    conversationId = ownerConversationId,
                    importId = import!!.id,
                    part = part,
                    sizeBytes = withContext(Dispatchers.IO) {
                        runCatching { copiedUri!!.toFile().takeIf { it.isFile }?.length() }.getOrNull()
                    },
                    textWasTruncated = documentReadReport?.let(::attachmentWasTruncated) == true,
                    readWarning = documentReadReport?.let {
                        attachmentReadWarning(it, attachmentReadFailureMessage)
                    },
                )
                if (applied) {
                    // The state now owns this URI. A later cancellation must
                    // not delete a ready attachment that has already landed.
                    copiedUri = null
                    onComplete()
                } else {
                    // The import finished after the item was removed or its
                    // chat changed. Do not leak its private copy into the next chat.
                    deleteCopiedFile()
                }
            } catch (error: CancellationException) {
                deleteCopiedFile()
                val currentImport = import
                if (currentImport != null) {
                    withContext(NonCancellable) {
                        state.failAttachmentImport(
                            conversationId = ownerConversationId,
                            importId = currentImport.id,
                            errorMessage = context.getString(R.string.parity_attachment_import_failed_detail),
                        )
                    }
                }
                throw error
            } catch (error: Throwable) {
                deleteCopiedFile()
                val accepted = markFailed(
                    message = context.getString(R.string.parity_attachment_import_failed_detail),
                )
                if (accepted) {
                    showError(context.getString(
                        R.string.parity_attachment_import_failed,
                        import?.displayName ?: sourceUri.lastPathSegment ?: "file",
                    ))
                }
            }
        }
    }

    fun retryAttachmentImport(conversationId: String, import: ChatInputAttachmentImport) {
        val retried = state.retryAttachmentImport(conversationId, import.id) ?: return
        importAttachment(
            ownerConversationId = conversationId,
            sourceUri = Uri.parse(retried.sourceUri),
            kind = retried.kind,
            displayName = retried.displayName,
            mimeType = retried.mimeType,
            existingImport = retried,
        )
    }

    fun importPastedTextFile(ownerConversationId: String, text: String) {
        scope.launch {
            val import = state.beginAttachmentImport(
                conversationId = ownerConversationId,
                sourceUri = Uri.parse("amber-paste://${Uuid.random()}"),
                displayName = "pasted_text.txt",
                mimeType = "text/plain",
                kind = ChatInputAttachmentKind.DOCUMENT,
            ) ?: return@launch
            var document: UIMessagePart.Document? = null
            try {
                // A long paste is materialized as an app-owned upload. Keep it
                // behind the same import token so clear/switch invalidates the
                // completion before it can append a late document. Record its URI
                // inside the block so a cancelled return cannot hide it from cleanup.
                withContext(NonCancellable + Dispatchers.IO) {
                    document = filesManager.createChatTextFile(text)
                }
                currentCoroutineContext().ensureActive()
                val documentReadReport = try {
                    DocumentAsPromptTransformer.extractText(document!!)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    "[ERROR, ${error.message.orEmpty()}]"
                }
                currentCoroutineContext().ensureActive()
                val applied = state.completeAttachmentImport(
                    conversationId = ownerConversationId,
                    importId = import.id,
                    part = document!!,
                    sizeBytes = withContext(Dispatchers.IO) {
                        runCatching { document!!.url.toUri().toFile().takeIf { it.isFile }?.length() }.getOrNull()
                    },
                    textWasTruncated = attachmentWasTruncated(documentReadReport),
                    readWarning = attachmentReadWarning(
                        documentReadReport,
                        attachmentReadFailureMessage,
                    ),
                )
                if (applied) {
                    // The composer now owns the part and its discard path can
                    // release it if the user clears the unsent draft.
                    document = null
                } else {
                    filesManager.deleteChatFiles(listOf(document!!.url.toUri()))
                    document = null
                }
            } catch (error: CancellationException) {
                document?.let { filesManager.deleteChatFiles(listOf(it.url.toUri())) }
                state.removeAttachmentImport(import.id)
                throw error
            } catch (_: Throwable) {
                document?.let { filesManager.deleteChatFiles(listOf(it.url.toUri())) }
                state.removeAttachmentImport(import.id)
            }
        }
    }
}
