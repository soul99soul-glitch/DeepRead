package app.amber.feature.ui.pages.profile

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.core.files.FilesManager
import app.amber.core.model.Avatar
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.launch

/** Field-level profile commands; SettingsAggregator remains the durable owner. */
class ProfileVM(
    private val settingsStore: SettingsAggregator,
    private val filesManager: FilesManager,
) : ViewModel() {
    fun updateNickname(nickname: String) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(displaySetting = current.displaySetting.copy(userNickname = nickname.trim()))
            }
        }
    }

    fun updateAvatar(avatar: Avatar) {
        viewModelScope.launch {
            settingsStore.update { current ->
                val previous = current.displaySetting.userAvatar
                if (previous is Avatar.Image && previous != avatar) {
                    filesManager.deleteChatFiles(listOf(previous.url.toUri()))
                }
                current.copy(displaySetting = current.displaySetting.copy(userAvatar = avatar))
            }
        }
    }
}
