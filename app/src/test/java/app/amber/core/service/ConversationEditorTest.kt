package app.amber.core.service

import android.content.ContextWrapper
import androidx.core.net.toFile
import androidx.core.net.toUri
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.core.context.ConversationContextRepository
import app.amber.core.files.FilesManager
import app.amber.core.files.FileFolders
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.review.ReflectionFixture
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationEditorTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun closeEditors() {
        fixtures.forEach { it.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `editing a paged conversation commits a new selected variant without losing earlier history`() = runBlocking {
        val fixture = fixture()
        val nodes = List(125) { MessageNode.of(UIMessage.user("Message $it")) }
        val original = conversation(nodes)
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        assertEquals(80, fixture.store.getConversationFlow(original.id).value.messageNodes.size)
        assertTrue(fixture.editor.editMessage(original.id, nodes[100].currentMessage.id, listOf(UIMessagePart.Text("Edited"))))
        val committed = fixture.repo.getConversationById(original.id)!!
        assertEquals(nodes.take(100), committed.messageNodes.take(100))
        assertEquals(101, committed.messageNodes.size)
        val editedNode = committed.messageNodes.last()
        assertEquals(nodes[100].messages.single(), editedNode.messages.first())
        assertEquals(1, editedNode.selectIndex)
        assertEquals(listOf(UIMessagePart.Text("Edited")), editedNode.currentMessage.parts)
        assertEquals(committed.messageNodes, fixture.store.getConversationFlow(original.id).value.messageNodes)
    }

    @Test
    fun `edit and variant selection reject an active generation without cancelling it or writing`() = runBlocking {
        val fixture = fixture()
        val node = MessageNode(messages = listOf(UIMessage.user("Original"), UIMessage.user("Variant")), selectIndex = 0)
        val original = conversation(listOf(node))
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val generation = Job()
        fixture.store.session(original.id).setJob(generation)
        try {
            assertFalse(fixture.editor.editMessage(original.id, node.currentMessage.id, listOf(UIMessagePart.Text("Rejected"))))
            fixture.editor.selectMessageNode(original.id, node.id, 1)
            assertTrue(generation.isActive)
            assertEquals(original.messageNodes, fixture.repo.getConversationById(original.id)!!.messageNodes)
            assertEquals(original.messageNodes, fixture.store.getConversationFlow(original.id).value.messageNodes)
            assertEquals(2, fixture.errors.errors.value.size)
            assertTrue(fixture.errors.errors.value.all { it.conversationId == original.id })
        } finally {
            generation.cancel()
        }
    }

    @Test
    fun `selecting a user variant commits the selected branch and drops downstream reply`() = runBlocking {
        val fixture = fixture()
        val first = UIMessage.user("Original")
        val second = UIMessage.user("Edited")
        val userNode = MessageNode(messages = listOf(first, second), selectIndex = 1)
        val original = conversation(listOf(userNode, MessageNode.of(UIMessage.assistant("Answer to edited"))))
        fixture.repo.insertConversation(original)
        fixture.editor.selectMessageNode(original.id, userNode.id, 0)
        val committed = fixture.repo.getConversationById(original.id)!!
        assertEquals(1, committed.messageNodes.size)
        assertEquals(listOf(first, second), committed.messageNodes.single().messages)
        assertEquals(0, committed.messageNodes.single().selectIndex)
    }

    @Test
    fun `deleting a variant retains other variants and deleting its last variant removes the node`() = runBlocking {
        val fixture = fixture()
        val first = UIMessage.user("First")
        val second = UIMessage.user("Second")
        val node = MessageNode(messages = listOf(first, second), selectIndex = 1)
        val reply = MessageNode.of(UIMessage.assistant("Reply"))
        val original = conversation(listOf(node, reply))
        fixture.repo.insertConversation(original)
        fixture.editor.deleteMessage(original.id, second.id)
        val retained = fixture.repo.getConversationById(original.id)!!
        assertEquals(listOf(first), retained.messageNodes.first().messages)
        assertEquals(0, retained.messageNodes.first().selectIndex)
        assertEquals(reply, retained.messageNodes.last())
        fixture.editor.deleteMessage(original.id, first.id)
        assertEquals(listOf(reply), fixture.repo.getConversationById(original.id)!!.messageNodes)
        fixture.editor.deleteMessage(original.id, Uuid.random(), failIfMissing = false)
        assertEquals(listOf(reply), fixture.repo.getConversationById(original.id)!!.messageNodes)
    }

    @Test
    fun `forked attachments receive their own durable conversation and physical file ownership`() = runBlocking {
        val fixture = fixture()
        val source = File(fixture.root, "source.txt").apply { writeText("retained document") }
        val message = UIMessage.user("Document").copy(parts = listOf(UIMessagePart.Document(source.toUri().toString(), "source.txt")))
        val node = MessageNode.of(message)
        val original = conversation(listOf(node, MessageNode.of(UIMessage.assistant("Later reply"))))
        fixture.repo.insertConversation(original)
        val fork = fixture.editor.forkConversationAtMessage(original.id, message.id)
        assertNotEquals(original.id, fork.id)
        assertEquals(1, fork.messageNodes.size)
        assertNotEquals(node.id, fork.messageNodes.single().id)
        val copiedUrl = (fork.messageNodes.single().currentMessage.parts.single() as UIMessagePart.Document).url
        assertNotEquals(source.toUri().toString(), copiedUrl)
        assertEquals("retained document", copiedUrl.toUri().toFile().readText())
        assertTrue(source.exists())
        assertEquals(fork.messageNodes, fixture.repo.getConversationById(fork.id)!!.messageNodes)
        assertEquals(original.messageNodes, fixture.repo.getConversationById(original.id)!!.messageNodes)
    }

    @Test
    fun `failed fork reclaims only new copies and never deletes the source attachment`() = runBlocking {
        val fixture = fixture()
        val source = File(fixture.root, "source.txt").apply { writeText("keep source") }
        val missing = File(fixture.root, "missing.txt")
        val message = UIMessage.user("Documents").copy(parts = listOf(
            UIMessagePart.Document(source.toUri().toString(), "source.txt"),
            UIMessagePart.Document(missing.toUri().toString(), "missing.txt"),
        ))
        val original = conversation(listOf(MessageNode.of(message)))
        fixture.repo.insertConversation(original)
        val failure = runCatching { fixture.editor.forkConversationAtMessage(original.id, message.id) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(original.id), fixture.repo.getConversations().first().map { it.id })
        assertEquals("keep source", source.readText())
        assertTrue(File(fixture.root, FileFolders.UPLOAD).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `stale edit after restore is rejected before it can overwrite the restored conversation`() = runBlocking {
        val fixture = fixture()
        val message = UIMessage.user("Original")
        val original = conversation(listOf(MessageNode.of(message)))
        fixture.repo.insertConversation(original)
        fixture.store.ensureInitialized(original.id)
        val oldEpoch = fixture.gate.currentEpoch()
        fixture.gate.withRestore {
            fixture.repo.updateConversation(original.copy(title = "Restored"))
            fixture.gate.markDataCommitted()
        }
        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(oldEpoch)) {
                fixture.editor.editMessage(original.id, message.id, listOf(UIMessagePart.Text("Old edit")))
            }
        }.exceptionOrNull()
        assertTrue(failure is SyncRestoreWriteRejectedException)
        val committed = fixture.repo.getConversationById(original.id)!!
        assertEquals("Restored", committed.title)
        assertEquals(original.messageNodes, committed.messageNodes)
    }

    @Test
    fun `message deletion commits references before deleting its generated image`() = runBlocking {
        val fixture = fixture()
        val id = Uuid.random()
        val image = File(fixture.root, "chat_images/$id/image.png").apply { parentFile.mkdirs(); writeText("image") }
        val message = UIMessage.user("Image").copy(parts = listOf(UIMessagePart.Image(image.toUri().toString())))
        val original = conversation(listOf(MessageNode.of(message))).copy(id = id)
        fixture.repo.insertConversation(original)
        fixture.editor.deleteMessage(id, message.id)
        assertTrue(fixture.repo.getConversationById(id)!!.messageNodes.isEmpty())
        assertFalse(image.exists())
    }

    private fun fixture(): Fixture {
        val root = directory.newFolder()
        val fileContext = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val scope = AppScope()
        val gate = SyncRestoreWriteGate()
        val files = FilesManager(fileContext, FilesRepository(database.managedFileDao()), scope, gate)
        val repo = ConversationRepository(
            database.conversationDao(), database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database), gate,
        )
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings()))
        val queue = PendingMessageQueue(scope, PendingMessageStore(fileContext, Json), gate)
        val errors = ChatErrorStore()
        val store = ConversationStore(repo, settings, scope, files, queue, errors, gate)
        val contextRepository = ConversationContextRepository(
            database.conversationCompactDao(), database.conversationContextEventDao(), repo, gate,
        )
        val editor = ConversationEditor(fileContext, store, contextRepository, UserInputPreprocessor(settings), files, gate)
        return Fixture(editor, store, queue, repo, errors, gate, scope, root).also(fixtures::add)
    }

    private fun conversation(nodes: List<MessageNode>): Conversation = Conversation.ofId(Uuid.random()).copy(
        title = "Conversation",
        createAt = Instant.ofEpochMilli(1_000),
        updateAt = Instant.ofEpochMilli(2_000),
        messageNodes = nodes,
    )

    private class Fixture(
        val editor: ConversationEditor,
        val store: ConversationStore,
        val queue: PendingMessageQueue,
        val repo: ConversationRepository,
        val errors: ChatErrorStore,
        val gate: SyncRestoreWriteGate,
        val scope: AppScope,
        val root: File,
    ) {
        fun close() {
            store.cleanup()
            queue.cleanup()
            scope.cancel()
        }
    }
}
