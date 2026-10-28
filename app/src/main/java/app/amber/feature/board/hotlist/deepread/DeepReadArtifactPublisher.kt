package app.amber.feature.board.hotlist.deepread

import android.util.Log
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.core.utils.JsonInstant
import app.amber.feature.board.hotlist.HotListRepository

internal class DeepReadArtifactPublisher(
    private val artifactRepository: ArtifactRepository?,
    private val hotListRepository: HotListRepository,
) {
    suspend fun publish(
        topicId: String,
        topicTitle: String,
        output: DeepReadOutput,
    ) {
        if (!output.isComplete()) return
        val repository = artifactRepository ?: return
        runCatching {
            // Synthesis stores its body separately from the completion marker output.
            // Publish that existing article format so the artifact is self-contained.
            val content = hotListRepository.getDeepReadEntry(topicId)?.structuredJson
                ?.takeIf { it.isNotBlank() }
                ?: JsonInstant.encodeToString(DeepReadOutput.serializer(), output)
            repository.saveDeepRead(
                topicId = topicId,
                title = topicTitle,
                content = content,
            )
        }.onFailure { error ->
            // Workspace sync must not turn an otherwise completed DeepRead
            // into a failed generation; the registry can be retried from the
            // cached completion on the next open/run.
            Log.w(TAG, "completed deep read workspace sync failed", error)
        }
    }

    private companion object { const val TAG = "DeepReadAgentRunManager" }
}
