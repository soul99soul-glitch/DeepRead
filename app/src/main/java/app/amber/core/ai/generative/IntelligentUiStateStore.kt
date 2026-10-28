package app.amber.core.ai.generative

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime store for `task-ui` card states so LazyColumn recycling never loses
 * user edits, active tabs, or study quiz progress.
 */
object IntelligentUiStateStore {
    private val states = ConcurrentHashMap<String, IntelligentUiCardState>()

    fun getOrCreate(cardKey: String, document: IntelligentUiDocument): IntelligentUiCardState {
        return states.computeIfAbsent(cardKey) {
            IntelligentUiCardState(cardKey = cardKey, document = document)
        }.also { state ->
            state.syncWithDocument(document)
        }
    }

    fun stateFor(cardKey: String, document: IntelligentUiDocument): IntelligentUiCardState =
        getOrCreate(cardKey, document)

    fun peek(cardKey: String): IntelligentUiCardState? = states[cardKey]

    internal fun clearForTesting() {
        states.clear()
    }

    internal fun clearAll() {
        states.clear()
    }
}
