package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.lazy.LazyColumn
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilPhaseMarker

/**
 * One entry in the merged timeline (a message or a phase marker), with a stable
 * key for [LazyColumn].
 */
internal sealed interface TimelineEntry {
    val key: String
    val sortMs: Long

    data class Message(val msg: CouncilMessage) : TimelineEntry {
        override val key get() = msg.id
        override val sortMs get() = msg.createdAtMs
    }

    data class Phase(val marker: CouncilPhaseMarker) : TimelineEntry {
        override val key get() = marker.id
        override val sortMs get() = marker.createdAtMs
    }
}

internal fun buildCouncilTimelineEntries(
    messages: List<CouncilMessage>,
    phaseMarkers: List<CouncilPhaseMarker>,
): List<TimelineEntry> = buildList {
    messages.forEach { add(TimelineEntry.Message(it)) }
    phaseMarkers.forEach { add(TimelineEntry.Phase(it)) }
}.sortedBy { it.sortMs }
