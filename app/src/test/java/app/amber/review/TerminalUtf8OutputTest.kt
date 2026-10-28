package app.amber.review

import app.amber.feature.terminal.TerminalOutputBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.Continuation

class TerminalUtf8OutputTest {
    private val expected = "ASCII 中文🙂\nline two: café\n"

    @Test
    fun processReaderPreservesUtf8AcrossSingleByteReads() {
        val processOwnerType = Class.forName("app.amber.feature.terminal.LocalTerminalProcess")
        val processOwner = ReflectionFixture.allocate(processOwnerType)
        val output = TerminalOutputBuffer(1024)
        val method = processOwnerType.getDeclaredMethod(
            "readOutput", Process::class.java, kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function1::class.java,
        ).apply { isAccessible = true }

        method.invoke(processOwner, OutputProcess(expected), { true }, { text: String -> output.append(text) })

        assertEquals(expected, output.snapshot())
    }

    @Test
    fun persistentSessionReaderPreservesUtf8AcrossSingleByteReads() = runBlocking {
        val sessionOwnerType = Class.forName("app.amber.feature.terminal.TerminalSessionManager")
        val sessionOwner = ReflectionFixture.allocate(sessionOwnerType)
        val sessionType = Class.forName("app.amber.feature.terminal.TerminalSession")
        val outputType = Class.forName("app.amber.feature.terminal.TerminalSessionOutput")
        val output = outputType.getDeclaredConstructor(Int::class.javaPrimitiveType).let {
            it.isAccessible = true
            it.newInstance(1024)
        }
        val session = ReflectionFixture.allocate(sessionType)
        ReflectionFixture.set(session, "process", OutputProcess(expected))
        ReflectionFixture.set(session, "output", output)
        val method = sessionOwnerType.getDeclaredMethod("readSessionOutput", sessionType, Continuation::class.java)

        ReflectionFixture.invokeSuspend(sessionOwner, method, session)

        val actual = outputType.getDeclaredMethod("drain").let { it.isAccessible = true; it.invoke(output) }
        assertEquals(expected, actual)
    }

    /** Keeps the session alive so the test exercises reading without workspace cleanup. */
    private class OutputProcess(text: String) : Process() {
        private val bytes = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        private val input = object : InputStream() {
            override fun read(): Int = bytes.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                bytes.read(buffer, offset, minOf(length, 1))
        }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = true
    }
}
