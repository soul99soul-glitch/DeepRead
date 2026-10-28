package app.amber.feature.board

import app.amber.agent.data.db.entity.BoardSignalEntity
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.board.aggregator.SignalAggregator
import app.amber.feature.board.collector.RawBoardSignal
import app.amber.feature.runtime.DurableRuntimeTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BoardSignalSourceGateTest : DurableRuntimeTestBase() {
    private lateinit var repository: BoardRepository
    private lateinit var aggregator: SignalAggregator
    private var settings = TodayBoardSetting(enabled = true)

    @Before
    fun setUpSignals() {
        repository = BoardRepository(
            database.boardSignalDao(), database.boardItemDao(), database.boardFocusRuleDao(),
            database.boardWeightDao(), database.dailyReviewDao(), SyncRestoreWriteGate(),
        )
        aggregator = SignalAggregator(repository, emptyList(), { settings })
    }

    @Test
    fun `disabled notification source rejects push ingress without persistence`() = runBlocking {
        settings = settings.copy(enabledSources = setOf(BoardSignalSourceType.CALENDAR))

        assertFalse(aggregator.ingest(notification("private-message")))
        assertEquals(0, repository.countUnprocessedSignals())

        settings = settings.copy(enabledSources = setOf(BoardSignalSourceType.NOTIFICATION))
        assertTrue(aggregator.ingest(notification("allowed-message")))
        assertEquals(listOf("allowed-message"), aggregator.getFilteredSignals().map { it.signal.sourceRef })
    }

    @Test
    fun `turning Board off rejects already queued notification callbacks`() = runBlocking {
        settings = settings.copy(enabled = false)

        assertFalse(aggregator.ingest(notification("callback-after-stop")))
        assertEquals(0, repository.countUnprocessedSignals())
    }

    @Test
    fun `source filter precedes SQL limit and leaves disabled queue unconsumed`() = runBlocking {
        val now = System.currentTimeMillis()
        database.boardSignalDao().insert(signal("disabled-oldest", BoardSignalSourceType.NOTIFICATION, now - 2_000))
        database.boardSignalDao().insert(signal("enabled-later", BoardSignalSourceType.CALENDAR, now - 1_000))
        settings = settings.copy(enabledSources = setOf(BoardSignalSourceType.CALENDAR))

        val batch = aggregator.getFilteredSignalBatch(limit = 1)

        assertEquals(listOf("enabled-later"), batch.surfaced.map { it.signal.id })
        assertEquals(listOf("enabled-later"), batch.consideredSignalIds)
        repository.markSignalsProcessed(batch.consideredSignalIds)
        assertFalse(requireNotNull(database.boardSignalDao().getById("disabled-oldest")).processed)

        settings = settings.copy(enabledSources = setOf(BoardSignalSourceType.NOTIFICATION))
        assertEquals(listOf("disabled-oldest"), aggregator.getFilteredSignals(limit = 1).map { it.signal.id })
    }

    private fun notification(ref: String) = RawBoardSignal(
        sourceType = BoardSignalSourceType.NOTIFICATION,
        sourceRef = ref,
        title = ref,
        content = "A private message body for $ref",
        signalTime = System.currentTimeMillis(),
        metadataJson = """{"priority":"high"}""",
    )

    private fun signal(id: String, source: String, time: Long) = BoardSignalEntity(
        id = id, sourceType = source, sourceRef = id, title = id, content = "Content $id",
        contentHash = id, signalTime = time, metadataJson = """{"priority":"high"}""",
        processed = false, processedAt = null, createdAt = time,
    )
}
