package app.amber.feature.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.model.Lorebook
import app.amber.core.model.PromptInjection
import kotlin.uuid.Uuid

class PromptVM(
    private val settingsStore: SettingsAggregator
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(transform)
        }
    }

    fun saveModeInjection(injection: PromptInjection.ModeInjection) = updateSettings { current ->
        val modes = current.modeInjections
        current.copy(modeInjections = if (modes.any { it.id == injection.id }) {
            modes.map { if (it.id == injection.id) injection else it }
        } else modes + injection)
    }

    fun toggleModeInjection(id: Uuid) = updateSettings { current ->
        current.copy(modeInjections = current.modeInjections.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        })
    }

    fun deleteModeInjection(id: Uuid) = updateSettings { current ->
        current.copy(modeInjections = current.modeInjections.filterNot { it.id == id })
    }

    fun importModeInjection(imported: PromptInjection.ModeInjection) = updateSettings { current ->
        current.copy(modeInjections = current.modeInjections + imported)
    }

    fun moveModeInjection(fromId: Uuid, toId: Uuid) = updateSettings { current ->
        val modes = current.modeInjections.toMutableList()
        val from = modes.indexOfFirst { it.id == fromId }
        val to = modes.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) current else {
            modes.add(to, modes.removeAt(from))
            current.copy(modeInjections = modes)
        }
    }

    fun saveLorebook(book: Lorebook) = updateSettings { current ->
        val books = current.lorebooks
        current.copy(lorebooks = if (books.any { it.id == book.id }) {
            books.map { if (it.id == book.id) book else it }
        } else books + book)
    }

    fun deleteLorebook(id: Uuid) = updateSettings { current ->
        current.copy(lorebooks = current.lorebooks.filterNot { it.id == id })
    }

    fun importLorebook(imported: Lorebook) = updateSettings { current ->
        current.copy(lorebooks = current.lorebooks + imported)
    }

    fun moveLorebook(fromId: Uuid, toId: Uuid) = updateSettings { current ->
        val books = current.lorebooks.toMutableList()
        val from = books.indexOfFirst { it.id == fromId }
        val to = books.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0) current else {
            books.add(to, books.removeAt(from))
            current.copy(lorebooks = books)
        }
    }
}
