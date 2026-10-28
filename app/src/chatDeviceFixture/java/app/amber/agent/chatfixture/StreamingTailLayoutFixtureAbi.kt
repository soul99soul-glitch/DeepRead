package app.amber.agent.chatfixture

import androidx.annotation.Keep
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.amber.feature.ui.pages.chat.streamingTailLayout as productionStreamingTailLayout

/**
 * Fixture-only streamingTailLayout overloads. This source set is wired into
 * the `debug` build type unconditionally, so default fullDebug app and
 * androidTest builds resolve it. The `baseline` and `benchmarkRelease`
 * build types include it only with `-PchatDeviceFixture=true`. It is never
 * compiled into `main` or `release`. Device tests import this package so
 * HEAD and the b4477f42b worktree baseline resolve the same JVM class.
 */
@Keep
@Composable
fun Modifier.streamingTailLayout(
    state: LazyListState,
    lazyIndex: Int,
    followBottomPx: Int,
): Modifier = productionStreamingTailLayout(
    state, lazyIndex, followBottomPx, animate = true, itemKey = null,
)

@Keep
@Composable
fun Modifier.streamingTailLayout(
    state: LazyListState,
    lazyIndex: Int,
    followBottomPx: Int,
    animate: Boolean,
    itemKey: Any?,
): Modifier = productionStreamingTailLayout(
    state, lazyIndex, followBottomPx, animate, itemKey,
)
