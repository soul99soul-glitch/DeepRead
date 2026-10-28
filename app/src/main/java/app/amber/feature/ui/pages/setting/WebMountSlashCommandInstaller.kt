package app.amber.feature.ui.pages.setting

import android.content.Context
import app.amber.agent.R
import app.amber.core.model.QuickMessage
import app.amber.core.settings.prefs.SettingsAggregator

/** Installs once against the latest durable settings, preserving any user-maintained command. */
internal suspend fun installWebMountSlashCommand(
    context: Context,
    settingsStore: SettingsAggregator,
): Boolean {
    val template = context.getString(R.string.setting_webmount_quick_message_template).trimEnd()
    var installed = false
    settingsStore.update { current ->
        if (current.quickMessages.any { it.title.equals("webmount", ignoreCase = true) }) {
            current
        } else {
            installed = true
            current.copy(quickMessages = current.quickMessages + QuickMessage(
                title = "webmount",
                content = template + "\n",
            ))
        }
    }
    return installed
}
