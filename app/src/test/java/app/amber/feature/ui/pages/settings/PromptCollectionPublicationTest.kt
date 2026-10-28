package app.amber.feature.ui.pages.settings

import androidx.lifecycle.viewModelScope
import app.amber.core.model.Lorebook
import app.amber.core.model.PromptInjection
import app.amber.core.infra.PreferencesKeys
import app.amber.core.utils.JsonInstant
import app.amber.feature.ui.pages.extensions.PromptVM
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PromptCollectionPublicationTest : SettingsEditorTestBase() {
    @Test
    fun queuedChangesToTwoModeIdsPreserveBothEditsInTheRealPreferencesFile(): Unit = runBlocking {
        val first = PromptInjection.ModeInjection(name = "first mode", enabled = true)
        val second = PromptInjection.ModeInjection(name = "second mode", enabled = true)
        fixture.settings.update { it.copy(modeInjections = listOf(first, second)) }
        val vm = fixture.retain(PromptVM(fixture.settings))
        val rendered = withTimeout(5_000) {
            vm.settings.first { it.modeInjections.map { item -> item.id } == listOf(first.id, second.id) }
        }.modeInjections
        val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val pause = fixture.dataStore.pauseNextWrite()
        try {
            // Same two ID commands as PromptPage before another settings frame is published.
            vm.toggleModeInjection(rendered.first().id)
            withTimeout(5_000) { pause.entered.await() }
            vm.toggleModeInjection(rendered.last().id)
            pause.release.complete(Unit)
            awaitCommands(vm, observers)

            val raw = requireNotNull(fixture.dataStore.data.first()[PreferencesKeys.MODE_INJECTIONS])
            val durable = JsonInstant.decodeFromString(ListSerializer(PromptInjection.ModeInjection.serializer()), raw)
            assertEquals(listOf(first.id, second.id), durable.map { it.id })
            assertFalse("The second queued edit must not restore the first card", durable.first { it.id == first.id }.enabled)
            assertFalse(durable.first { it.id == second.id }.enabled)
        } finally {
            pause.release.complete(Unit)
            awaitCommands(vm, observers)
        }
    }

    @Test
    fun queuedDeletesOfTwoLorebookIdsCannotRestoreTheFirstDeletedBook(): Unit = runBlocking {
        val first = Lorebook(name = "first book")
        val second = Lorebook(name = "second book")
        fixture.settings.update { it.copy(lorebooks = listOf(first, second)) }
        val vm = fixture.retain(PromptVM(fixture.settings))
        val rendered = withTimeout(5_000) {
            vm.settings.first { it.lorebooks.map { item -> item.id } == listOf(first.id, second.id) }
        }.lorebooks
        val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val pause = fixture.dataStore.pauseNextWrite()
        try {
            vm.deleteLorebook(rendered.first().id)
            withTimeout(5_000) { pause.entered.await() }
            vm.deleteLorebook(rendered.last().id)
            pause.release.complete(Unit)
            awaitCommands(vm, observers)

            val raw = requireNotNull(fixture.dataStore.data.first()[PreferencesKeys.LOREBOOKS])
            val durable = JsonInstant.decodeFromString(ListSerializer(Lorebook.serializer()), raw)
            assertEquals(emptyList<Lorebook>(), durable)
        } finally {
            pause.release.complete(Unit)
            awaitCommands(vm, observers)
        }
    }

    @Test
    fun queuedModeEditReorderAndImportKeepLatestIdsAndContent(): Unit = runBlocking {
        val first = PromptInjection.ModeInjection(name = "first", content = "original")
        val second = PromptInjection.ModeInjection(name = "second")
        val third = PromptInjection.ModeInjection(name = "imported")
        fixture.settings.update { it.copy(modeInjections = listOf(first, second)) }
        val vm = fixture.retain(PromptVM(fixture.settings))
        withTimeout(5_000) { vm.settings.first { !it.init } }
        val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val pause = fixture.dataStore.pauseNextWrite()
        try {
            vm.saveModeInjection(first.copy(content = "edited"))
            withTimeout(5_000) { pause.entered.await() }
            vm.moveModeInjection(second.id, first.id)
            vm.importModeInjection(third)
            pause.release.complete(Unit)
            awaitCommands(vm, observers)

            val raw = requireNotNull(fixture.dataStore.data.first()[PreferencesKeys.MODE_INJECTIONS])
            val durable = JsonInstant.decodeFromString(ListSerializer(PromptInjection.ModeInjection.serializer()), raw)
            assertEquals(listOf(second.id, first.id, third.id), durable.map { it.id })
            assertEquals("edited", durable.first { it.id == first.id }.content)
        } finally {
            pause.release.complete(Unit)
            awaitCommands(vm, observers)
        }
    }

    @Test
    fun queuedLorebookEditImportAndMovePreserveOtherBookChanges(): Unit = runBlocking {
        val first = Lorebook(name = "first", description = "original")
        val second = Lorebook(name = "second")
        val third = Lorebook(name = "imported")
        fixture.settings.update { it.copy(lorebooks = listOf(first, second)) }
        val vm = fixture.retain(PromptVM(fixture.settings))
        withTimeout(5_000) { vm.settings.first { !it.init } }
        val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val pause = fixture.dataStore.pauseNextWrite()
        try {
            vm.saveLorebook(first.copy(description = "edited"))
            withTimeout(5_000) { pause.entered.await() }
            vm.importLorebook(third)
            vm.moveLorebook(second.id, first.id)
            pause.release.complete(Unit)
            awaitCommands(vm, observers)

            val raw = requireNotNull(fixture.dataStore.data.first()[PreferencesKeys.LOREBOOKS])
            val durable = JsonInstant.decodeFromString(ListSerializer(Lorebook.serializer()), raw)
            assertEquals(listOf(second.id, first.id, third.id), durable.map { it.id })
            assertEquals("edited", durable.first { it.id == first.id }.description)
        } finally {
            pause.release.complete(Unit)
            awaitCommands(vm, observers)
        }
    }

    private suspend fun awaitCommands(vm: PromptVM, observers: Set<Job>) {
        withTimeout(5_000) {
            vm.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in observers }.toList().joinAll()
        }
    }
}
