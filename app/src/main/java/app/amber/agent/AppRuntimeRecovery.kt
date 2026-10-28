package app.amber.agent

import app.amber.feature.runtime.ColdStartRuntimeRecoveryGate
import kotlinx.coroutines.CancellationException

/** Recovery ordering and failure policy for the existing process-wide runner admission gate. */
internal class AppRuntimeRecovery(
    private val gate: ColdStartRuntimeRecoveryGate,
    private val recoverDurableRuns: suspend () -> Unit,
    private val replayChatEvents: suspend () -> Unit,
    private val recoverJsCells: suspend () -> Unit,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    suspend fun recover() {
        try {
            // The ledger settles prior activations before checkpoints are projected.
            // Only then may the actual runner append a new durable RUNNING row.
            recoverDurableRuns()
            replayChatEvents()
            gate.complete()
        } catch (error: CancellationException) {
            gate.fail(error)
            throw error
        } catch (error: Throwable) {
            gate.fail(error)
            reportFailure("Failed to recover durable runtime state", error)
        }

        // Persisted JS cells are terminated independently of agent admission.
        runCatching { recoverJsCells() }
            .onFailure { reportFailure("Failed to recover persisted JS cells", it) }
    }
}
