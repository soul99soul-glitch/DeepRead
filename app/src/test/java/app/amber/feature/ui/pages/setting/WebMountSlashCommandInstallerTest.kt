package app.amber.feature.ui.pages.setting

import app.amber.core.model.QuickMessage
import app.amber.feature.ui.pages.settings.SettingsEditorTestBase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls the actual production button's installer with the real SettingsAggregator/DataStore. */
class WebMountSlashCommandInstallerTest : SettingsEditorTestBase() {

    @Test
    fun `two install callbacks queued behind a settings write publish one durable command`() = runBlocking {
        withTimeout(20_000L) {
            val context = fixture.application
            val store = fixture.settings
            val pause = fixture.dataStore.pauseNextWrite()
            val blocker = async(Dispatchers.IO) { store.update { it } }
            try {
                pause.entered.await()
                // UNDISPATCHED reaches the actual install entry's update suspension before
                // launching the second callback; no sleep or scheduler timing is required.
                val first = async(start = CoroutineStart.UNDISPATCHED) { installWebMountSlashCommand(context, store) }
                val second = async(start = CoroutineStart.UNDISPATCHED) { installWebMountSlashCommand(context, store) }
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                pause.release.complete(Unit)
                blocker.await()
                val installed = awaitAll(first, second)
                assertEquals(1, store.settingsFlow.value.quickMessages.count { it.title.equals("webmount", ignoreCase = true) })
                assertEquals(listOf(false, true), installed.sorted())
                val command = store.settingsFlow.value.quickMessages.single { it.title.equals("webmount", ignoreCase = true) }
                assertTrue(command.content.endsWith("\n"))
            } finally {
                pause.release.complete(Unit)
            }
        }
    }

    @Test
    fun `an existing case insensitive command is preserved without replacing its user content`() = runBlocking {
        withTimeout(20_000L) {
            val context = fixture.application
            val store = fixture.settings
            val existing = QuickMessage(title = "WEBMOUNT", content = "user maintained template")
            store.update { it.copy(quickMessages = it.quickMessages + existing) }
            assertFalse(installWebMountSlashCommand(context, store))
            assertEquals(existing, store.settingsFlow.value.quickMessages.single { it.title.equals("webmount", ignoreCase = true) })
        }
    }

}
