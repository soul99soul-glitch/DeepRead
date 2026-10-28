package app.amber.core.service

import android.content.ContextWrapper
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.ai.ui.UIMessage
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.chat.impl.ChatEventProjector
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.review.ReflectionFixture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatFinalizedProjectionRaceTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private var cleanup: (() -> Unit)? = null

    @Before
    fun pauseMainDispatcher() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun closeOwners() {
        cleanup?.invoke()
        Dispatchers.resetMain()
    }

    @Test
    fun finalizedEventWaitingForRenameCannotRestoreThePreviousTitle() = runTest {
        val gate = SyncRestoreWriteGate()
        val appScope = AppScope()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope, gate)
        val delegate = database.conversationDao()
        val armed = AtomicBoolean(false)
        val titleWriteEntered = CompletableDeferred<Unit>()
        val releaseTitleWrite = CompletableDeferred<Unit>()
        val dao = object : ConversationDAO by delegate {
            override suspend fun updateTitle(id: String, title: String, updatedAt: Long) {
                if (armed.compareAndSet(true, false)) {
                    titleWriteEntered.complete(Unit)
                    releaseTitleWrite.await()
                }
                delegate.updateTitle(id, title, updatedAt)
            }
        }
        val repo = ConversationRepository(
            dao, database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database), gate,
        )
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings()))
        val queue = PendingMessageQueue(scope, PendingMessageStore(object : ContextWrapper(context) {
            override fun getFilesDir() = directory.root
        }, Json), gate)
        val store = ConversationStore(repo, settings, scope, files, queue, ChatErrorStore(), gate)
        cleanup = {
            store.cleanup()
            queue.cleanup()
            scope.cancel()
            appScope.cancel()
        }
        val assistant = UIMessage.assistant("Completed answer")
        val node = MessageNode.of(assistant)
        val original = Conversation.ofId(Uuid.random()).copy(title = "Original title", messageNodes = listOf(node))
        repo.insertConversation(original)
        store.ensureInitialized(original.id)
        val projector = ChatEventProjector(InMemoryAgentEventStore(), repo, store, Json, gate)
        armed.set(true)
        val rename = async(start = CoroutineStart.UNDISPATCHED) { store.updateTitle(original.id, "User chosen title") }
        titleWriteEntered.await()
        val projection = async(start = CoroutineStart.UNDISPATCHED) {
            projector.projectFinalized(original.id, ChatEventPayload.AssistantMessageFinalized(
                messageNodeId = MessageNodeId(node.id.toString()), messageId = assistant.id.toString(),
                inputTokens = 1, outputTokens = 1, regenerateOf = null,
            ))
        }
        assertFalse("Final projection must be waiting for the metadata writer", projection.isCompleted)
        releaseTitleWrite.complete(Unit)
        rename.await()
        projection.await()

        val live = store.getConversationFlow(original.id).value
        val durable = repo.getConversationById(original.id)!!
        assertEquals(listOf("User chosen title", "User chosen title"), listOf(live.title, durable.title))
        assertEquals(listOf(original.messageNodes, original.messageNodes), listOf(live.messageNodes, durable.messageNodes))
    }
}
