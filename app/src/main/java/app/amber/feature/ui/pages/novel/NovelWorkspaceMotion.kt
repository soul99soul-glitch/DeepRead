package app.amber.feature.ui.pages.novel

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Short translation and opacity changes connect pages without scaling long text. */
internal fun workspacePageMotion(forward: Boolean, offsetPx: Int): ContentTransform =
    (fadeIn(tween(NovelMotion.MediumMs)) + slideInHorizontally(
        animationSpec = tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing),
        initialOffsetX = { if (forward) offsetPx else -offsetPx },
    )) togetherWith (fadeOut(tween(NovelMotion.FastMs)) + slideOutHorizontally(
        animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
        targetOffsetX = { if (forward) -offsetPx else offsetPx },
    ))

/** During overlap only the current page may receive pointer, keyboard or accessibility actions. */
internal fun Modifier.workspaceTransitionInput(active: Boolean): Modifier =
    this
        .onPreviewKeyEvent { !active }
        .pointerInput(active) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!active) event.changes.forEach { it.consume() }
                }
            }
        }
        .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
