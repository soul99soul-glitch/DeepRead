package app.amber.core.settings

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFlowExtTest {

    @Test
    fun `stopping the settings owner cancels collection normally`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure ->
            failures += failure
        })
        val state = flow {
            emit(7)
            awaitCancellation()
        }.toMutableStateFlow(scope, initial = 0)

        assertEquals(7, state.value)
        assertEquals(1, owner.children.count())
        owner.cancelAndJoin()

        assertTrue(owner.isCancelled)
        assertTrue(owner.children.none())
        assertTrue("Normal cancellation must not become a collection failure", failures.isEmpty())
        assertEquals(7, state.value)
    }

    @Test
    fun `shared raw flow decodes once for state and aggregator collectors`() = runBlocking {
        val subscriptions = AtomicInteger()
        val source = flow {
            subscriptions.incrementAndGet()
            emit(7)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val shared = source.shareSettingsRawFlow(scope)
        val firstValue = withTimeout(5_000) { shared.first() }
        val secondValue = withTimeout(5_000) { shared.first() }

        assertEquals(7, firstValue)
        assertEquals(7, secondValue)
        assertEquals(1, subscriptions.get())

        scope.cancel()
    }
}
