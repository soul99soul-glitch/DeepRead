package app.amber.core.memory

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.entity.MemoryEntity
import app.amber.core.memory.dream.MemoryDreamApplier
import app.amber.core.memory.dream.MemoryDreamPlan
import app.amber.core.memory.dream.MemoryMergeSuggestion
import app.amber.core.memory.dream.MemorySupersedeSuggestion
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.memory.telemetry.MemoryEventLogger
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoryDreamAtomicityTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun mergeCasFailureRollsBackTargetAndEarlierArchivesButAllowsNextSuggestion() = runBlocking {
        val memoryDao = database.memoryDao()
        listOf(
            memory(1, "用户偏好中文回复。", pinned = true),
            memory(2, "用户偏好简洁回复。"),
            memory(3, "用户偏好分点回复。"),
            memory(4, "用户正在学习 Kotlin。", pinned = true),
            memory(5, "用户正在学习 Kotlin 协程。"),
        ).forEach { memoryDao.insertMemory(it) }
        val failingDao = FailingMemoryCasDao(memoryDao, conflictId = 3)
        val repository = repository(failingDao)
        val before = repository.getAllRecords().associateBy { it.id }
        val applier = MemoryDreamApplier(repository, MemoryEventLogger(repository))

        val applied = applier.apply(
            MemoryDreamPlan(
                mergeSuggestions = listOf(
                    MemoryMergeSuggestion(1, listOf(2, 3), "用户偏好中文简洁分点回复。"),
                    MemoryMergeSuggestion(4, listOf(5), "用户正在学习 Kotlin 和协程。"),
                ),
            ),
        )

        assertEquals(1, failingDao.conflicts)
        assertTrue("the first archive reached SQLite before the later CAS failed", 2 in failingDao.archivedIds)
        val after = repository.getAllRecords().associateBy { it.id }
        assertEquals(5, after.size)
        listOf(1, 2, 3).forEach { id -> assertEquals("failed merge changed memory #$id", before[id], after[id]) }
        assertEquals("用户正在学习 Kotlin 和协程。", after.getValue(4).content)
        assertFalse(after.getValue(4).archived)
        assertTrue(after.getValue(5).archived)
        assertEquals(listOf(4), applied.mergeSuggestions.map { it.targetMemoryId })
    }

    @Test
    fun supersedeCasFailureRollsBackReplacementAndEarlierArchivesButAllowsNextSuggestion() = runBlocking {
        val memoryDao = database.memoryDao()
        listOf(
            memory(1, "用户偏好中文回复。"),
            memory(2, "用户偏好简洁回复。"),
            memory(3, "用户目前使用 Java 开发。"),
        ).forEach { memoryDao.insertMemory(it) }
        val failingDao = FailingMemoryCasDao(memoryDao, conflictId = 2)
        val repository = repository(failingDao)
        val before = repository.getAllRecords().associateBy { it.id }
        val applier = MemoryDreamApplier(repository, MemoryEventLogger(repository))
        val rejectedContent = "用户现在偏好英文详细解释。"
        val acceptedContent = "用户现在使用 Kotlin 开发。"

        val applied = applier.apply(
            MemoryDreamPlan(
                supersedeSuggestions = listOf(
                    supersede(listOf(1, 2), rejectedContent),
                    supersede(listOf(3), acceptedContent),
                ),
            ),
        )

        assertEquals(1, failingDao.conflicts)
        assertTrue("the first archive reached SQLite before the later CAS failed", 1 in failingDao.archivedIds)
        val after = repository.getAllRecords().associateBy { it.id }
        assertEquals("only the independent replacement may be inserted", 4, after.size)
        listOf(1, 2).forEach { id -> assertEquals("failed supersede changed memory #$id", before[id], after[id]) }
        assertFalse(after.values.any { it.content == rejectedContent })
        assertTrue(after.getValue(3).archived)
        val replacement = after.values.single { it.content == acceptedContent }
        assertEquals(listOf(3), replacement.supersedesIds)
        assertFalse(replacement.archived)
        assertEquals(listOf(acceptedContent), applied.supersedeSuggestions.map { it.newContent })
    }

    private fun repository(memoryDao: MemoryDAO) = MemoryRepository(
        memoryDAO = memoryDao,
        candidateDAO = database.memoryCandidateDao(),
        eventDAO = database.memoryEventDao(),
        appDatabase = database,
        restoreWriteGate = SyncRestoreWriteGate(),
    )

    private fun memory(id: Int, content: String, pinned: Boolean = false) = MemoryEntity(
        id = id,
        assistantId = MemoryRepository.LONG_TERM_MEMORY_ID,
        content = content,
        scope = MemoryScope.LONG_TERM.wireName,
        kind = MemoryKind.USER.wireName,
        pinned = pinned,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private fun supersede(ids: List<Int>, content: String) = MemorySupersedeSuggestion(
        oldMemoryIds = ids,
        newContent = content,
        scope = MemoryScope.LONG_TERM,
        kind = MemoryKind.USER,
        confidence = 0.9f,
    )
}

/** Real SQLite executes every write, including the deliberately stale CAS. */
private class FailingMemoryCasDao(
    private val delegate: MemoryDAO,
    private val conflictId: Int,
) : MemoryDAO by delegate {
    var conflicts = 0
        private set
    val archivedIds = mutableListOf<Int>()

    override suspend fun updateRecordCas(
        id: Int,
        assistantId: String,
        content: String,
        scope: String,
        kind: String,
        sourceConversationId: String?,
        sourceMessageIdsJson: String,
        supersedesIdsJson: String,
        expiresAt: Long?,
        confidence: Float,
        pinned: Boolean,
        archived: Boolean,
        createdAt: Long,
        updatedAt: Long,
        lastUsedAt: Long?,
        sourceRunId: String?,
        sourceTrigger: String?,
        topicTitle: String?,
        memberIdsJson: String,
        useCount: Int,
        expectedRevision: Long,
    ): Int {
        val affected = delegate.updateRecordCas(
            id = id,
            assistantId = assistantId,
            content = content,
            scope = scope,
            kind = kind,
            sourceConversationId = sourceConversationId,
            sourceMessageIdsJson = sourceMessageIdsJson,
            supersedesIdsJson = supersedesIdsJson,
            expiresAt = expiresAt,
            confidence = confidence,
            pinned = pinned,
            archived = archived,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastUsedAt = lastUsedAt,
            sourceRunId = sourceRunId,
            sourceTrigger = sourceTrigger,
            topicTitle = topicTitle,
            memberIdsJson = memberIdsJson,
            useCount = useCount,
            expectedRevision = if (id == conflictId) expectedRevision - 1 else expectedRevision,
        )
        if (id == conflictId && affected == 0) conflicts++
        if (archived && affected == 1) archivedIds += id
        return affected
    }
}
