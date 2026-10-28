package app.amber.feature.ui.components.ai

import android.app.Application
import android.net.Uri
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.ui.hooks.ChatInputAttachmentKind
import app.amber.feature.ui.hooks.ChatInputState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatInputSubmissionOwnerTest {
    private fun input(text: String = "first draft") = ChatInputState().apply {
        bindToConversation("conversation-a")
        setMessageText(text)
    }

    @Test
    fun secondSendDuringValidationDoesNotValidateOrSubmitAgain() = runTest {
        val input = input()
        val owner = ChatInputSubmissionOwner(input)
        val releaseValidation = CompletableDeferred<Unit>()
        var validations = 0
        var submissions = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            owner.submit(
                conversationId = "conversation-a",
                validate = {
                    validations++
                    releaseValidation.await()
                    true
                },
                send = { _, _ ->
                    submissions++
                    true
                },
            )
        }

        assertEquals(1, validations)
        assertFalse(owner.submit(
            conversationId = "conversation-a",
            validate = {
                validations++
                true
            },
            send = { _, _ ->
                submissions++
                true
            },
        ))
        assertEquals(1, validations)
        assertEquals(0, submissions)

        releaseValidation.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, submissions)
        assertTrue(input.isEmpty())
    }

    @Test
    fun editConfirmationSharesTheSubmissionAlreadyWaitingForValidation() = runTest {
        val input = input("edited historical message")
        val messageId = Uuid.random()
        input.editingMessage = messageId
        input.setContents(listOf(UIMessagePart.Text("edited historical message")))
        val owner = ChatInputSubmissionOwner(input)
        val releaseValidation = CompletableDeferred<Unit>()
        var submissions = 0
        val pendingSend = async(start = CoroutineStart.UNDISPATCHED) {
            owner.submit(
                conversationId = "conversation-a",
                validate = { releaseValidation.await(); true },
                send = { parts, target ->
                    assertEquals(messageId, target)
                    assertEquals(listOf(UIMessagePart.Text("edited historical message")), parts)
                    submissions++
                    true
                },
            )
        }

        assertFalse(owner.confirmEdit(regenerate = false) { _, _, _ ->
            submissions++
            true
        })
        assertEquals(0, submissions)
        assertEquals(messageId, input.editingMessage)
        releaseValidation.complete(Unit)
        assertTrue(pendingSend.await())
        assertEquals(1, submissions)
        assertTrue(input.isEmpty())
    }

    @Test
    fun nextDraftTypedDuringValidationIsPreservedWhileTheClickedSnapshotIsSent() = runTest {
        val input = input()
        val owner = ChatInputSubmissionOwner(input)
        val releaseValidation = CompletableDeferred<Unit>()
        var submitted: List<UIMessagePart>? = null
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.submit(
                conversationId = "conversation-a",
                validate = {
                    releaseValidation.await()
                    true
                },
                send = { parts, editingMessage ->
                    assertNull(editingMessage)
                    submitted = parts
                    true
                },
            )
        }

        input.setMessageText("next draft")
        releaseValidation.complete(Unit)

        assertTrue(sending.await())
        assertEquals(listOf(UIMessagePart.Text("first draft")), submitted)
        assertEquals("next draft", input.textContent.text.toString())
    }

    @Test
    fun changingTheEditTargetDuringValidationNeverWritesTheNewTargetOrClearsItsDraft() = runTest {
        val input = input()
        val firstMessage = Uuid.random()
        val nextMessage = Uuid.random()
        input.editingMessage = firstMessage
        input.setContents(listOf(UIMessagePart.Text("edited first message")))
        val owner = ChatInputSubmissionOwner(input)
        val releaseValidation = CompletableDeferred<Unit>()
        var submittedMessage: Uuid? = null
        var submittedParts: List<UIMessagePart>? = null
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.submit(
                conversationId = "conversation-a",
                validate = {
                    releaseValidation.await()
                    true
                },
                send = { parts, editingMessage ->
                    submittedMessage = editingMessage
                    submittedParts = parts
                    true
                },
            )
        }

        input.editingMessage = nextMessage
        input.setContents(listOf(UIMessagePart.Text("editing another message")))
        releaseValidation.complete(Unit)

        assertTrue(sending.await())
        assertEquals(firstMessage, submittedMessage)
        assertEquals(listOf(UIMessagePart.Text("edited first message")), submittedParts)
        assertEquals(nextMessage, input.editingMessage)
        assertEquals("editing another message", input.textContent.text.toString())
    }

    @Test
    fun attachmentChosenDuringValidationIsNotDiscardedByThePreviousSubmission() = runTest {
        val input = input()
        val owner = ChatInputSubmissionOwner(input)
        val releaseValidation = CompletableDeferred<Unit>()
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.submit(
                conversationId = "conversation-a",
                validate = {
                    releaseValidation.await()
                    true
                },
                send = { _, _ -> true },
            )
        }
        val added = input.beginAttachmentImport(
            conversationId = "conversation-a",
            sourceUri = Uri.parse("content://picker/next.pdf"),
            displayName = "next.pdf",
            mimeType = "application/pdf",
            kind = ChatInputAttachmentKind.DOCUMENT,
        )
        assertNotNull(added)

        releaseValidation.complete(Unit)

        assertTrue(sending.await())
        assertEquals(added, input.attachmentImports.single())
        assertTrue(input.hasUnresolvedAttachmentImports())
    }

    @Test
    fun changingConversationDuringValidationPreventsSubmission() = runTest {
        val input = input()
        val owner = ChatInputSubmissionOwner(input)
        val releaseValidation = CompletableDeferred<Unit>()
        var submissions = 0
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.submit(
                conversationId = "conversation-a",
                validate = {
                    releaseValidation.await()
                    true
                },
                send = { _, _ ->
                    submissions++
                    true
                },
            )
        }

        input.bindToConversation("conversation-b")
        input.setMessageText("conversation b draft")
        releaseValidation.complete(Unit)

        assertFalse(sending.await())
        assertEquals(0, submissions)
        assertEquals("conversation b draft", input.textContent.text.toString())
    }

    @Test
    fun rejectedValidationPreservesInputAndAllowsTheNextSend() = runTest {
        val input = input()
        val owner = ChatInputSubmissionOwner(input)
        var submissions = 0

        assertFalse(owner.submit(
            conversationId = "conversation-a",
            validate = { false },
            send = { _, _ ->
                submissions++
                true
            },
        ))
        assertEquals("first draft", input.textContent.text.toString())
        assertEquals(0, submissions)

        assertTrue(owner.submit(
            conversationId = "conversation-a",
            validate = { true },
            send = { _, _ ->
                submissions++
                true
            },
        ))
        assertEquals(1, submissions)
        assertTrue(input.isEmpty())
    }

    @Test
    fun rejectedSubmissionPreservesInputAndAllowsRetry() = runTest {
        val input = input()
        val owner = ChatInputSubmissionOwner(input)

        assertFalse(owner.submit(
            conversationId = "conversation-a",
            validate = { true },
            send = { _, _ -> false },
        ))
        assertEquals("first draft", input.textContent.text.toString())

        assertTrue(owner.submit(
            conversationId = "conversation-a",
            validate = { true },
            send = { _, _ -> true },
        ))
        assertTrue(input.isEmpty())
    }

    @Test
    fun cancellingSuspendedValidationDoesNotSubmitOrLeaveTheOwnerInFlight() = runTest {
        val input = input()
        val owner = ChatInputSubmissionOwner(input)
        val releaseValidation = CompletableDeferred<Unit>()
        var submissions = 0
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            owner.submit(
                conversationId = "conversation-a",
                validate = {
                    releaseValidation.await()
                    true
                },
                send = { _, _ ->
                    submissions++
                    true
                },
            )
        }

        sending.cancelAndJoin()
        releaseValidation.complete(Unit)

        assertEquals(0, submissions)
        assertEquals("first draft", input.textContent.text.toString())
        assertTrue(owner.submit(
            conversationId = "conversation-a",
            validate = { true },
            send = { _, _ ->
                submissions++
                true
            },
        ))
        assertEquals(1, submissions)
    }

    @Test
    fun removingTheCapturedAttachmentDuringValidationPreventsSubmissionOfItsDeletedFile() = runTest {
        val input = input()
        val file = File.createTempFile("chat-submit-removed", ".txt").apply { writeText("attachment") }
        try {
            val (importId, part) = addReadyDocument(input, file)
            val owner = ChatInputSubmissionOwner(input)
            val release = CompletableDeferred<Unit>()
            var submitted = false
            val sending = async(start = CoroutineStart.UNDISPATCHED) {
                owner.submit("conversation-a", validate = { release.await(); true }, send = { _, _ ->
                    submitted = true
                    true
                })
            }

            assertEquals(part, input.removeAttachmentImport(importId)?.part)
            // This is the real chip's deletion policy; release the actual file before validation returns.
            assertTrue(input.shouldDeleteFileOnRemove(part))
            assertTrue(file.delete())
            release.complete(Unit)

            assertFalse(sending.await())
            assertFalse(submitted)
            assertEquals("first draft", input.textContent.text.toString())
        } finally {
            file.delete()
        }
    }

    @Test
    fun removingAnUntrackedLocalAttachmentDuringValidationPreventsSendingItsDeletedFile() = runTest {
        val input = input()
        val file = File.createTempFile("chat-submit-untracked", ".txt").apply { writeText("attachment") }
        try {
            val part = UIMessagePart.Document(Uri.fromFile(file).toString(), file.name, "text/plain")
            input.addFiles(listOf(part))
            assertTrue(input.attachmentImports.isEmpty())
            val release = CompletableDeferred<Unit>()
            var submitted = false
            val sending = async(start = CoroutineStart.UNDISPATCHED) {
                ChatInputSubmissionOwner(input).submit(
                    "conversation-a", validate = { release.await(); true },
                    send = { _, _ -> submitted = true; true },
                )
            }
            input.messageContent = input.messageContent.filterNot { it == part }
            assertTrue(input.shouldDeleteFileOnRemove(part))
            assertTrue(file.delete())
            release.complete(Unit)
            assertFalse(sending.await())
            assertFalse(submitted)
            assertEquals("first draft", input.textContent.text.toString())
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptedSnapshotConsumesItsAttachmentsWhilePreservingLaterTextAndNewAttachments() = runTest {
        val input = input()
        val submittedFile = File.createTempFile("chat-submit-first", ".txt")
        val nextFile = File.createTempFile("chat-submit-next", ".txt")
        try {
            val (_, submittedPart) = addReadyDocument(input, submittedFile)
            val release = CompletableDeferred<Unit>()
            var received: List<UIMessagePart>? = null
            val sending = async(start = CoroutineStart.UNDISPATCHED) {
                ChatInputSubmissionOwner(input).submit(
                    "conversation-a", validate = { release.await(); true },
                    send = { parts, _ -> received = parts; true },
                )
            }
            input.setMessageText("next draft")
            val (nextImport, nextPart) = addReadyDocument(input, nextFile)
            release.complete(Unit)

            assertTrue(sending.await())
            assertEquals(listOf(UIMessagePart.Text("first draft"), submittedPart), received)
            assertEquals(listOf(UIMessagePart.Text("next draft"), nextPart), input.getContents())
            assertEquals(listOf(nextImport), input.attachmentImports.map { it.id })
            assertEquals(listOf(Uri.fromFile(nextFile)), input.drainAttachmentFilesForDiscard())
            assertTrue("Discarding the later draft must leave the submitted file intact", submittedFile.exists())
        } finally {
            submittedFile.delete()
            nextFile.delete()
        }
    }

    @Test
    fun editConfirmationNeverAcceptsOrClearsAnUnresolvedAttachment() {
        listOf(false, true).forEach { regenerate ->
            listOf(false, true).forEach { failed ->
                val input = input("historical edit").apply {
                    editingMessage = Uuid.random()
                    setContents(listOf(UIMessagePart.Text("historical edit")))
                }
                val pending = input.beginAttachmentImport(
                    "conversation-a", Uri.parse("content://picker/pending.pdf"), "pending.pdf",
                    "application/pdf", ChatInputAttachmentKind.DOCUMENT,
                )!!
                if (failed) input.failAttachmentImport("conversation-a", pending.id, "read failed")
                var submissions = 0

                val accepted = ChatInputSubmissionOwner(input).confirmEdit(regenerate) { _, _, _ ->
                    submissions++
                    true
                }

                assertFalse("regenerate=$regenerate failed=$failed", accepted)
                assertEquals(0, submissions)
                assertEquals(pending.id, input.attachmentImports.single().id)
                assertEquals("historical edit", input.textContent.text.toString())
                assertNotNull(input.editingMessage)
            }
        }
    }

    @Test
    fun widgetActionSendsAnIndependentInstructionAndPreservesTheHistoricalEditOwner() {
        val input = input()
        val historicalMessage = Uuid.random()
        val original = UIMessagePart.Document("file:///tmp/historical.pdf", "historical.pdf")
        input.editingMessage = historicalMessage
        input.setContents(listOf(UIMessagePart.Text("editing history"), original))
        val action = listOf(UIMessagePart.Text("Explain the chart"))
        var received: List<UIMessagePart>? = null

        assertTrue(ChatInputSubmissionOwner(input).sendWidgetAction(action) { parts ->
            received = parts
            true
        })

        assertEquals(action, received)
        assertEquals(historicalMessage, input.editingMessage)
        assertEquals(listOf(UIMessagePart.Text("editing history"), original), input.getContents())
        assertFalse(input.shouldDeleteFileOnRemove(original))
        assertTrue(input.drainAttachmentFilesForDiscard().isEmpty())
    }

    private fun addReadyDocument(input: ChatInputState, file: File): Pair<String, UIMessagePart.Document> {
        val import = input.beginAttachmentImport(
            "conversation-a", Uri.parse("content://picker/${file.name}"), file.name,
            "text/plain", ChatInputAttachmentKind.DOCUMENT,
        )!!
        val part = UIMessagePart.Document(Uri.fromFile(file).toString(), file.name, "text/plain")
        assertTrue(input.completeAttachmentImport("conversation-a", import.id, part, file.length()))
        return import.id to part
    }
}
