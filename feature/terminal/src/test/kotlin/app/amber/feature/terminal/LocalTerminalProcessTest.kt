package app.amber.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-process coverage of the real resource owner, not an Android/Alpine execution claim. */
class LocalTerminalProcessTest {
    @Test
    fun `actual process drains utf8 output before returning its exit code`() {
        val output = StringBuffer()
        val process = LocalTerminalProcess("host-output-fixture", 5_000)
        process.start(
            ProcessBuilder("/bin/sh", "-c", "printf 'host 中文🙂'; exit 7"),
            { TerminalJobStatus.RUNNING },
        ) { output.append(it) }

        val result = process.await({ TerminalJobStatus.RUNNING }) { output.append(it) }

        assertEquals(TerminalJobStatus.COMPLETED, result.status)
        assertEquals(7, result.exitCode)
        assertEquals("host 中文🙂", output.toString())
        assertFalse(process.isAlive)
    }

    @Test
    fun `timeout stops the actual process and preserves its timeout verdict`() {
        val output = StringBuffer()
        val process = LocalTerminalProcess("host-timeout-fixture", 50)
        process.start(
            ProcessBuilder("/bin/sh", "-c", "while :; do :; done"),
            { TerminalJobStatus.RUNNING },
        ) { output.append(it) }

        val result = try {
            process.await({ TerminalJobStatus.RUNNING }) { output.append(it) }
        } finally {
            if (process.isAlive) process.terminate()
        }

        assertEquals(TerminalJobStatus.TIMED_OUT, result.status)
        assertTrue(output.contains("Command timed out after 50ms."))
        assertFalse(process.isAlive)
    }
}
