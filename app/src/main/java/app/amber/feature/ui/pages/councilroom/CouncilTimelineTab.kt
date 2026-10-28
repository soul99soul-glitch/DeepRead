package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import app.amber.feature.ui.hooks.ImeLazyListAutoScroller
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.COUNCIL_ROOM_USER_ID
import app.amber.feature.modelcouncil.CouncilMessageKind
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.running

/**
 * 群聊时间线 — merges messages + phase markers by timestamp.
 *
 * Alignment mirrors the main chat: user right-aligned, host/guest left-aligned.
 * Host bubbles get an amber avatar; guests get a coloured pixel avatar. Guest-
 * to-guest reference graph (reply / continues / invited-by) renders as small
 * footnotes under the bubble.
 *
 * Streaming auto-follow: while any message is STREAMING and the user has not
 * scrolled away from the tail, keep the timeline pinned to the true content
 * bottom. Content growth and newly appended turns do not disable follow; an
 * actual user scroll away from the bottom does.
 */
@Composable
fun CouncilTimelineTab(
    room: CouncilRoom,
    onAnswer: ((String, String) -> Unit)?,
    modifier: Modifier = Modifier,
    composer: @Composable () -> Unit = {},
) {
    val entries = remember(room.messages, room.phaseMarkers) {
        buildCouncilTimelineEntries(room.messages, room.phaseMarkers)
    }
    val isStreaming = remember(room.messages) {
        room.messages.any { it.status.running }
    }
    // A token that changes whenever the streaming content actually changes
    // (message count OR the running message's text length). Used as a
    // LaunchedEffect key to *request* a follow — but the request goes through a
    // conflate'd SharedFlow (one scroll per frame max), NOT a direct scroll,
    // which is what stops the per-chunk jitter the old `streamingTail`-keyed
    // effect caused.
    val streamingChangeToken = remember(room.messages) {
        val running = room.messages.lastOrNull { it.status.running }
        "${room.messages.size}#${running?.text?.length ?: 0}"
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── Three-state follow mode (mirrors chat's TimelineFollowMode) ─────────
    // Idle: not streaming, no follow needed.
    // FollowingBottom: stick to the bottom; content growth scrolls to keep tail pinned.
    // PausedForUser: the user dragged away; stay put until they scroll back to bottom.
    // The sticky PausedForUser state is the key fix: the old bool `followStreaming`
    // couldn't tell "programmatic scroll in progress" from "user dragged away", so
    // isScrollInProgress races flipped it off mid-stream → the jitter the user saw.
    var followMode by remember { mutableStateOf(CouncilFollowMode.Idle) }

    // Token counter so overlapping programmatic scrolls don't clear the flag
    // out from under each other (mirrors chat's programmaticScrollToken). An
    // older `endProgrammaticScroll` whose token no longer matches is a no-op,
    // so a fast follow-up follow can't prematurely mark "done scrolling".
    var programmaticScrollToken by remember { mutableIntStateOf(0) }
    var programmaticScrollInProgress by remember { mutableStateOf(false) }

    // Ground-truth user gesture detection. `isScrollInProgress` fires for BOTH
    // user drags and our own scrollBy; only a real pointer-down in the list
    // proves the user initiated it. Mirrors chat's pointerInput/awaitFirstDown.
    var userScrollInTimeline by remember { mutableStateOf(false) }
    var userDragInTimeline by remember { mutableStateOf(false) }

    // Token captured by the IME auto-scroller's start so its end callback can
    // release the programmatic-scroll flag via endProgrammaticScroll(token).
    // Stored (not discarded) so a cancelled/replaced scroll still pairs cleanly
    // — if we discarded it (the old buggy approach) and the start's launch got
    // cancelled, programmaticScrollInProgress would stick at true and the
    // PausedForUser → FollowingBottom recovery would never re-arm.
    var imeScrollToken by remember { mutableIntStateOf(0) }

    // One-shot event flow: a streaming chunk requests "please follow", and the
    // collector drains at most one per frame (conflate + DROP_OLDEST). This
    // replaces the old per-chunk LaunchedEffect restart, which fired scrollBy
    // once per chunk with no coalescing — the direct cause of the jitter.
    val followEvents = remember(room.conversationId) {
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    // Match the main chat: about one CJK text line. The explicit follow state
    // keeps streaming growth from disabling follow, so the buffer can stay tight.
    val bottomFollowBufferPx = with(LocalDensity.current) { 24.dp.toPx().toInt() }

    // "Is the viewport at the bottom?" — the single source of truth for follow.
    // Two-stage check, matching the main chat's ChatListSupport.isAtTimelineBottom:
    //   1) index check: the last VISIBLE item must be the LAST item (guards against
    //      "the tail got pushed below the fold" — a pure pixel check can't see that
    //      and falsely reports bottom during fast scrolls).
    //   2) pixel check with a small buffer: the last item's bottom edge is within
    //      bufferPx of the viewport's bottom.
    fun isAtBottom(bufferPx: Int): Boolean {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return true
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
        if (lastVisible.index < total - 1) return false
        val contentBottom = lastVisible.offset + lastVisible.size + info.afterContentPadding
        return contentBottom <= info.viewportEndOffset + bufferPx
    }

    /**
     * Loose "near bottom" check based on item COUNT, not pixels — mirrors the
     * chat page's `isNearListEnd(bufferItems=2)`. True if the last visible item
     * is within [bufferItems] of the true last item.
     *
     * This is the key to reliable follow re-arm: a pixel-based isAtBottom (even
     * with a 24dp buffer) fails the moment streaming growth pushes content a
     * few px below the fold, so the user can scroll to what *looks* like the
     * bottom yet never trip the "resume follow" condition. An index-based check
     * is immune to per-frame pixel jitter — if the last couple items are
     * visible, we're "near enough" and should follow.
     */
    fun isNearBottom(bufferItems: Int = 2): Boolean {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return true
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
        return lastVisible.index >= total - 1 - bufferItems
    }

    /**
     * Pixels from the current viewport to the true content bottom — null when the
     * last item isn't measured yet (off-screen). Same approach as the main chat's
     * distanceToTimelineBottomPx: we scrollBy this exact distance, NOT scrollToItem,
     * because scrollToItem(last) pins to the item's TOP edge — and when a streaming
     * message grows taller than one screen, that leaves the newest text scrolling
     * off below the fold (the "doesn't follow" symptom).
     */
    fun distanceToBottomPx(): Int? {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return 0
        val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == total - 1 }
            ?: return null
        val contentBottom = bottomItem.offset + bottomItem.size + info.afterContentPadding
        return (contentBottom - info.viewportEndOffset).coerceAtLeast(0)
    }

    fun beginProgrammaticScroll(): Int {
        val token = programmaticScrollToken + 1
        programmaticScrollToken = token
        programmaticScrollInProgress = true
        return token
    }

    fun endProgrammaticScroll(token: Int) {
        scope.launch {
            // One frame is enough for the isScrollInProgress flicker right after
            // scrollBy settles to clear; matching the chat's M0.3 reasoning. The
            // token check prevents an older follow's end from clobbering a newer
            // one's "still scrolling" flag.
            withFrameNanos { }
            if (programmaticScrollToken == token) {
                programmaticScrollInProgress = false
            }
        }
    }

    suspend fun scrollToBottom() {
        val token = beginProgrammaticScroll()
        try {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) return
            // Single-frame jump to the bottom. Used for one-shot events (new
            // content arrival, generation-end settle). CONTINUOUS follow during
            // streaming is handled by the frame-pinning effect below, NOT by
            // re-calling this every chunk — re-measuring distance every frame
            // and scrollBy-ing it is what caused the jitter (content grows
            // between measure and scroll, so the scroll overshoots/undershoots
            // every frame). The pin effect compensates the *increment* of
            // growth instead, which is jitter-free.
            val distance = distanceToBottomPx()
            if (distance == null) {
                listState.scrollToItem(total - 1)
                withFrameNanos { }
                val settleDistance = distanceToBottomPx()
                if (settleDistance != null && settleDistance > 0) {
                    listState.scrollBy(settleDistance.toFloat())
                }
            } else if (distance > 0) {
                listState.scrollBy(distance.toFloat())
            }
        } finally {
            endProgrammaticScroll(token)
        }
    }

    fun bottomFollowAllowed(): Boolean =
        followMode == CouncilFollowMode.FollowingBottom && !userScrollInTimeline

    // ── Streaming bottom-follow (mirrors chat's follow state machine) ───────
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            // Streaming started: arm follow if we're already near the bottom.
            val atBottom = isAtBottom(bottomFollowBufferPx)
            android.util.Log.i("CouncilFollow", "arm check: isStreaming=true atBottom=$atBottom total=${listState.layoutInfo.totalItemsCount}")
            if (atBottom) {
                followMode = CouncilFollowMode.FollowingBottom
                android.util.Log.i("CouncilFollow", "armed FollowingBottom")
            } else {
                android.util.Log.i("CouncilFollow", "NOT armed (not at bottom)")
            }
        } else {
            // Streaming just ended. Mirror the chat page's "generation-end"
            // settle: if we were following, fire one last bottom-follow so the
            // final chunk's tail (often left 1-2 lines below the fold by the
            // last incremental scrollBy) gets pulled fully into view.
            //
            // CRITICAL: do NOT flip to Idle here. tryEmit only enqueues the
            // event; the collector consumes it asynchronously on a later
            // frame. If we set Idle now, the collector would see
            // bottomFollowAllowed()==false and drop the generation-end scroll
            // — leaving the last lines hidden, the exact symptom this fixes.
            // Instead we keep FollowingBottom and let the collector flip to
            // Idle after it has serviced the generation-end scroll (see the
            // collector below). This matches the chat page, which does NOT
            // enter Idle on generation-end either.
            if (followMode == CouncilFollowMode.FollowingBottom) {
                followEvents.tryEmit("generation-end")
            }
        }
    }

    // The single scroll executor: one collector, conflate'd, so a burst of
    // follow requests in the same frame coalesces into one scrollBy. This is
    // the core anti-jitter fix — the old code re-launched a new scroll per chunk.
    LaunchedEffect(followEvents, room.conversationId) {
        followEvents.conflate().collect { reason ->
            val allowed = bottomFollowAllowed()
            android.util.Log.i("CouncilFollow", "collector: reason=$reason allowed=$allowed mode=$followMode")
            if (!allowed) return@collect
            withFrameNanos { }
            val stillAllowed = bottomFollowAllowed()
            // One-shot jumps only. Continuous per-chunk follow is handled by the
            // frame-pinning effect above, which is jitter-free; having the
            // collector ALSO scrollBy every chunk would fight the pin and
            // re-introduce the jitter. So:
            //  - "new-content": jump to bottom (first arm / big content arrival)
            //  - "generation-end": final settle, then flip to Idle
            //  - "chunk" / "visible" / "visual-active": no-op here (pin handles them)
            if (stillAllowed && (reason == "new-content" || reason == "generation-end")) {
                scrollToBottom()
            }
            if (reason == "generation-end") {
                followMode = CouncilFollowMode.Idle
            }
        }
    }

    // ── Frame-pinning follow (the anti-jitter core) ────────────────────────
    // While FollowingBottom, run a continuous per-frame loop that PINS the last
    // item's bottom edge to a fixed on-screen Y, instead of chasing the content
    // bottom with repeated "scrollBy distance-to-bottom" (which jitters because
    // content grows between measure and scroll). Here we compensate only the
    // *increment*: if the last item's bottom moved down by N px this frame (the
    // content just grew), we scrollBy(N). The bubble's lower edge therefore
    // stays glued to the same screen position — no oscillation, because we never
    // overshoot (we move exactly what the content moved). Negative deltas
    // (content shrinking) are ignored so the list never scrolls back up on its
    // own.
    LaunchedEffect(followMode, room.conversationId) {
        if (followMode != CouncilFollowMode.FollowingBottom) return@LaunchedEffect
        var pinnedBottomY: Int? = null
        while (followMode == CouncilFollowMode.FollowingBottom) {
            withFrameNanos { }
            if (followMode != CouncilFollowMode.FollowingBottom) break
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) {
                pinnedBottomY = null
                continue
            }
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == total - 1 }
            if (bottomItem == null) {
                // Last item scrolled out of view (e.g. just appended) — re-pin
                // with a one-shot jump so the pin loop resumes from bottom.
                if (bottomFollowAllowed()) {
                    scrollToBottom()
                    withFrameNanos { }
                }
                pinnedBottomY = null
                continue
            }
            val currentBottomY = bottomItem.offset + bottomItem.size + info.afterContentPadding
            val prev = pinnedBottomY
            pinnedBottomY = currentBottomY
            if (prev != null) {
                val delta = currentBottomY - prev
                if (delta > 1) {
                    // Content grew this frame — compensate exactly the growth
                    // so the bubble's bottom edge stays at the same screen Y.
                    val token = beginProgrammaticScroll()
                    try {
                        listState.scrollBy(delta.toFloat())
                    } finally {
                        endProgrammaticScroll(token)
                    }
                }
            }
        }
    }

    // Force-arm follow when content arrives and we're not PausedForUser.
    // This mirrors the chat page's "new USER message / new message row lands →
    // resumeBottomFollow()" safety net (ChatListNormalSection.kt:606-619). Without
    // it, arm relied SOLELY on LaunchedEffect(isStreaming), which requires
    // isAtBottom() at the exact moment streaming starts — if the viewport is a
    // few px above bottom (very common right after sending a topic, due to the
    // composer + contentPadding), arm fails and follow stays Idle for the entire
    // host opening + first guest turn, only recovering by accident later.
    // Keyed on message count so every new turn (host opening, each guest, phase
    // markers) re-evaluates; PausedForUser is sticky so a real user scroll-up
    // still wins.
    val lastMessageId = room.messages.lastOrNull()?.id
    LaunchedEffect(room.messages.size, lastMessageId, isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        if (followMode == CouncilFollowMode.PausedForUser) return@LaunchedEffect
        // Arm (or re-arm) follow when new content arrives — but ONLY if we're
        // near the bottom (don't yank the user away while reading history).
        // Use the index-based isNearBottom (not pixel isAtBottom) so streaming
        // growth that pushes content a few px below the fold doesn't prevent
        // re-arm — the user scrolled to "looks like bottom" and that should count.
        if (followMode == CouncilFollowMode.Idle && !isNearBottom()) {
            return@LaunchedEffect
        }
        if (followMode != CouncilFollowMode.FollowingBottom) {
            followMode = CouncilFollowMode.FollowingBottom
            android.util.Log.i("CouncilFollow", "force-armed FollowingBottom (msgCount=${room.messages.size} lastMsg=$lastMessageId nearBottom=${isNearBottom()})")
        }
        followEvents.tryEmit("new-content")
    }

    // Content-changed → request a follow (emit, not scroll). The collector above
    // drains and coalesces. Keyed on streamingChangeToken so it only fires when
    // the streaming tail actually grew, not on every unrelated recomposition.
    LaunchedEffect(streamingChangeToken, room.conversationId, isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        val allowed = bottomFollowAllowed()
        android.util.Log.i("CouncilFollow", "chunk emit: token=$streamingChangeToken allowed=$allowed mode=$followMode userScroll=$userScrollInTimeline")
        if (allowed) {
            followEvents.tryEmit("chunk")
        }
    }

    // User-gesture vs programmatic-scroll disambiguation. This is the other half
    // of the jitter fix: `isScrollInProgress` alone can't tell our scrollBy from a
    // user drag, so we tag real pointer-downs here and gate the pause on that.
    // Mirrors chat's LaunchedEffect(isScrollInProgress, userDragInTimeline).
    LaunchedEffect(listState.isScrollInProgress, userDragInTimeline) {
        if (listState.isScrollInProgress) {
            val userDriven = userScrollInTimeline &&
                (!programmaticScrollInProgress || userDragInTimeline)
            if (userDriven && followMode == CouncilFollowMode.FollowingBottom) {
                followMode = CouncilFollowMode.PausedForUser
            }
        } else {
            // M0.3-style gate: only decide "user released at bottom → resume follow"
            // when we were actually PausedForUser. A programmatic scroll that just
            // ended (followMode still FollowingBottom) must NOT be misread as a
            // user release — that was the old code's race and the root of the jitter.
            if (!programmaticScrollInProgress &&
                followMode == CouncilFollowMode.PausedForUser
            ) {
                // Resume follow when the user has scrolled back to "near bottom".
                // Use isNearBottom (index-based, immune to pixel jitter) so that
                // streaming growth doesn't make "looks like bottom" fail the
                // strict pixel isAtBottom check — which was why follow wouldn't
                // re-arm even after the user scrolled down.
                if (isNearBottom()) {
                    followMode = CouncilFollowMode.FollowingBottom
                }
                // else: user released away from bottom → stay PausedForUser.
            }
        }
    }

    // The first user message is the room's "topic"; it gets the 议题 · 发起人 label.
    val topicMessageId = remember(room.messages) {
        room.messages.firstOrNull { it.authorId == COUNCIL_ROOM_USER_ID }?.id
    }

    // Whether an ask_user card is still awaiting input. The host can pause the
    // council with a question embedded in the timeline (not the bottom composer),
    // so when the soft keyboard rises for that card we must scroll the list to
    // keep it visible — otherwise the TextField is hidden behind the keyboard.
    val hasPendingAskUser = remember(room.messages) {
        room.messages.any { msg ->
            msg.kind == CouncilMessageKind.ASK_USER &&
                room.messages.none {
                    it.authorId == COUNCIL_ROOM_USER_ID &&
                        it.replyToMessageId == msg.id
                }
        }
    }
    ImeLazyListAutoScroller(
        lazyListState = listState,
        shouldScroll = {
            // Scroll on IME rise while following the bottom, OR whenever an
            // ask_user card needs to be kept on screen — the latter applies even
            // when the user has scrolled up (PausedForUser), because the question
            // is the only actionable control at that moment.
            followMode == CouncilFollowMode.FollowingBottom || hasPendingAskUser ||
                isAtBottom(bottomFollowBufferPx)
        },
        onProgrammaticScrollStart = {
            // Capture the token so end can release the flag via the same
            // endProgrammaticScroll(token) path the streaming follow uses.
            // Mirrors chat's imeProgrammaticScrollToken pairing.
            imeScrollToken = beginProgrammaticScroll()
        },
        onProgrammaticScrollEnd = {
            endProgrammaticScroll(imeScrollToken)
            imeScrollToken = 0
        },
    )
    // Who is mid-turn — drives the live "正在发言" strip above the composer.
    val speaking = remember(room.participants, streamingChangeToken) {
        room.participants.firstOrNull { it.status == CouncilParticipantStatus.SPEAKING }
    }

    // Timeline keys whose entrance "pop" has already played. Pre-seeded with the
    // entries present at first composition, so opening a room with history does
    // NOT replay the pop for every past message — only turns that arrive after
    // open pop, and each only once (scrolling a popped item back into view, which
    // re-creates its composition, finds the key here and skips re-animating).
    val poppedKeys = remember { entries.mapTo(mutableSetOf<String>()) { it.key } }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                // Tag real user pointer-downs BEFORE the scroll-progress effect
                // runs, so isScrollInProgress can't be misread as user-driven
                // during our own follow scrollBy. Initial pass = earliest point
                // we can observe the gesture. Mirrors the chat page's pointerInput.
                .pointerInput(room.conversationId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        userScrollInTimeline = true
                        userDragInTimeline = false
                        try {
                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                if (event.changes.any { it.positionChange().getDistance() > 0.5f }) {
                                    userDragInTimeline = true
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            userScrollInTimeline = false
                            userDragInTimeline = false
                        }
                    }
                },
            // Extra bottom headroom so the streaming tail (and a freshly appended
            // line) stays comfortably above the composer instead of hugging the edge.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 64.dp),
            // Per-item spacing (not spacedBy): the chat page uses spacedBy(0) +
            // per-item padding because spacedBy's uniform spacing is managed by
            // the LazyList and, combined with a streaming item whose height
            // changes every frame, can make the "last item bottom offset"
            // measurement jitter — which feeds back into isAtBottom / distance
            // and amplifies the scroll phase difference. Per-item padding fixes
            // the gap into each item's own layout slot, decoupling it from the
            // list-level arrangement.
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    CouncilTimelineEmpty(room.objective)
                }
            }
            items(items = entries, key = { it.key }) { entry ->
                // A user message that arrives after the room is open gets a one-shot
                // scale "pop" from its trailing edge — the same send feedback as the
                // main chat. firstAppearance is false for history (pre-seeded) and
                // for items scrolled back into view, so neither replays the pop.
                //
                // We deliberately do NOT use Modifier.animateItem() here. The chat
                // page avoids it too: animateItem's placement animation chases the
                // per-frame auto-scroll during streaming and the two fight, which
                // reads as flicker/jitter at the anchor point. The pop is done with
                // a pure graphicsLayer scale (no layout slot change), so it doesn't
                // interfere with scroll placement at all.
                val isUserMsg = entry is TimelineEntry.Message &&
                    entry.msg.authorId == COUNCIL_ROOM_USER_ID
                val firstAppearance = remember(entry.key) { entry.key !in poppedKeys }
                val popIn = isUserMsg && firstAppearance
                val popScale = remember(entry.key) { Animatable(if (popIn) 0.8f else 1f) }
                LaunchedEffect(entry.key) {
                    if (popIn) {
                        poppedKeys += entry.key
                        popScale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Per-item bottom gap (replaces the old spacedBy(14.dp) —
                        // see the LazyColumn verticalArrangement comment above).
                        .padding(bottom = 14.dp)
                        .graphicsLayer {
                            scaleX = popScale.value
                            scaleY = popScale.value
                            // User bubble is right-aligned; pop from its trailing edge.
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        },
                ) {
                    when (entry) {
                        is TimelineEntry.Phase -> PhaseDivider(entry.marker)
                        is TimelineEntry.Message -> {
                            if (entry.msg.kind == CouncilMessageKind.ASK_USER) {
                                val answered = room.messages.any { msg ->
                                    msg.authorId == COUNCIL_ROOM_USER_ID && msg.replyToMessageId == entry.msg.id
                                }
                                CouncilAskUserCard(
                                    msg = entry.msg,
                                    answered = answered,
                                    onAnswer = onAnswer?.let { submit -> { answer -> submit(entry.msg.id, answer) } },
                                )
                            } else {
                                // Only the actively-streaming turn drives bottom-follow,
                                // and it does so off the renderer's frame-release callback
                                // (onStreamingVisibleFrame) so scroll stays in phase with
                                // the characters actually appearing on screen, not the raw
                                // text length. The lambdas close over followEvents /
                                // followMode, which live in this composable's scope, so
                                // they must be built here and threaded down to MessageBubble.
                                val isStreamingTurn = entry.msg.status == CouncilMessageStatus.STREAMING
                                val modelLabel = remember(room.participants, entry.msg.authorId) {
                                    room.participantById(entry.msg.authorId)?.modelLabel().orEmpty()
                                }
                                TimelineMessageRow(
                                    msg = entry.msg,
                                    modelLabel = modelLabel,
                                    referenceAuthors = councilReferenceAuthors(entry.msg, room.messages),
                                    isTopic = entry.msg.id == topicMessageId,
                                    onStreamingVisibleFrame = if (isStreamingTurn) {
                                        { followEvents.tryEmit("visible") }
                                    } else null,
                                    onStreamingVisualActiveChange = if (isStreamingTurn) {
                                        { active ->
                                            if (active && followMode == CouncilFollowMode.FollowingBottom) {
                                                followEvents.tryEmit("visual-active")
                                            }
                                        }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Live speaking strip — slides/fades in while a guest turn streams.
        AnimatedVisibility(
            visible = isStreaming && speaking != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            speaking?.let { p ->
                CouncilSpeakingStrip(
                    participant = p,
                    onClick = {
                        val idx = entries.indexOfLast {
                            it is TimelineEntry.Message && it.msg.authorId == p.id
                        }
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    },
                )
            }
        }
        // An interrupted room may still receive an answer through its card,
        // while the regular composer remains exclusive to active deliberation.
        if (room.status.running) {
            composer()
        }
    }
}

/**
 * Three-state bottom-follow mode for the council timeline, mirroring the chat
 * page's TimelineFollowMode. The sticky [PausedForUser] state is what lets us
 * tell a programmatic follow-scroll from a real user drag — the core fix for
 * the old bool-flag jitter.
 */
private enum class CouncilFollowMode {
    /** Not streaming; no auto-follow needed. */
    Idle,

    /** Stick to the bottom; streaming growth scrolls to keep the tail pinned. */
    FollowingBottom,

    /**
     * The user dragged away from the bottom. Stay put (don't yank back) until
     * they scroll back within the bottom buffer, which re-arms [FollowingBottom].
     */
    PausedForUser,
}
