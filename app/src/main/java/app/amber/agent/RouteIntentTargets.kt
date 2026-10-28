package app.amber.agent

import android.content.Intent
import app.amber.feature.board.hotlist.deepread.DeepReadNotifier
import app.amber.feature.runtime.RunTerminal
import app.amber.core.utils.base64Encode
import kotlin.uuid.Uuid

internal fun standaloneStartScreen(intent: Intent, surface: String = StandaloneSurfaces.current): Screen? = when (surface) {
    StandaloneSurfaces.NOVEL ->
        app.amber.feature.novel.workspace.NovelWorkspaceNotificationRoute.screenFrom(intent)
            ?: Screen.NovelProjects
    StandaloneSurfaces.DEEP_READ ->
        deepReadScreenFromIntent(intent) ?: Screen.TodayBoard
    else -> null
}

internal fun deepReadScreenFromIntent(intent: Intent): Screen.DeepRead? {
    if (!intent.getBooleanExtra(DeepReadNotifier.EXTRA_OPEN_DEEP_READ, false)) return null
    val topicId = intent.getStringExtra(DeepReadNotifier.EXTRA_TOPIC_ID)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val title = intent.getStringExtra(DeepReadNotifier.EXTRA_TITLE)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val sourceUrl = intent.getStringExtra(DeepReadNotifier.EXTRA_SOURCE_URL)
        ?.takeIf { it.isNotBlank() }
    return Screen.DeepRead(
        topicId = topicId,
        title = title,
        sourceUrl = sourceUrl,
        fromHistory = intent.getBooleanExtra(DeepReadNotifier.EXTRA_FROM_HISTORY, true),
    )
}

internal fun taskSessionScreenFromIntent(intent: Intent): Screen.Chat? {
    // Shortcut intents carry their own prompt extra and are consumed by
    // DynamicShortcutPublisher.screenFromIntent - never route them twice.
    if (intent.hasExtra(app.amber.feature.tools.DynamicShortcutPublisher.EXTRA_SHORTCUT_KIND)) return null
    val prompt = intent.getStringExtra(RouteActivity.EXTRA_OPEN_CHAT_PROMPT)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return Screen.Chat(
        id = Uuid.random().toString(),
        text = prompt.base64Encode(),
    )
}

/** Parsed identity shared by cold-start and warm notification delivery. */
internal data class NotificationDeepLink(
    val conversationId: String,
    val runId: String?,
)

internal fun notificationDeepLinkFrom(
    conversationId: String?,
    runId: String?,
): NotificationDeepLink? {
    val normalizedConversationId = conversationId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw -> runCatching { Uuid.parse(raw).toString() }.getOrNull() }
        ?: return null
    return NotificationDeepLink(
        conversationId = normalizedConversationId,
        runId = runId?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun notificationDeepLinkFrom(intent: Intent): NotificationDeepLink? =
    notificationDeepLinkFrom(
        conversationId = intent.getStringExtra("conversationId"),
        runId = intent.getStringExtra("runId"),
    )

/** Run ownership/freshness predicate kept pure for the notification canary. */
internal fun notificationRunIsOwned(link: NotificationDeepLink, run: RunTerminal?): Boolean =
    run != null && run.conversationId == link.conversationId && !run.state.isTerminal
