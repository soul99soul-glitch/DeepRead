package app.amber.core.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The receiver waits only for the persisted decision. The same session-owned
 * job then continues the turn so Stop and later session operations cancel it.
 */
internal suspend fun ConversationSession.dispatchToolApproval(
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    onError: (Exception) -> Unit,
    process: suspend (decisionPersisted: () -> Unit) -> Unit,
): Boolean = withContext(scope.coroutineContext.minusKey(Job)) {
    // A pending card can be visible while the previous handler is still parking its run.
    // Let that owner finish normally: cancelling it can terminally cancel the run we must resume.
    val previous = getJob()
    previous?.join()
    if (previous?.isCancelled == true || getJob()?.let { it !== previous } == true) {
        return@withContext false
    }
    val accepted = CompletableDeferred<Boolean>()
    val job = scope.launch(context, start = CoroutineStart.LAZY) {
        try {
            process { accepted.complete(true) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(error)
        }
    }
    // Also settles when an already-cancelled scope prevents the lazy body
    // from running at all; its body/finally cannot handle that case.
    job.invokeOnCompletion { accepted.complete(false) }
    setJob(job)
    job.start()
    accepted.await()
}
