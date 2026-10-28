package app.amber.feature.terminal

import app.amber.core.infra.AppScope
import app.amber.feature.workspace.WorkspaceManager
import app.amber.feature.workspace.WorkspaceMirrorLease
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Owns interactive shell sessions, their IO and the matching workspace lease. */
internal class TerminalSessionManager(
    private val appScope: AppScope,
    private val workspaceManager: WorkspaceManager,
    private val alpineRuntimeInstaller: AlpineRuntimeInstaller,
) {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val sessionMutex = Mutex()

    suspend fun startSession(): TerminalSessionInfo = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            pruneDeadSessionsLocked()
            val activeSessions = sessions.values.count { it.process.isAlive }
            check(activeSessions < MAX_CONCURRENT_SESSIONS) {
                "Too many active terminal sessions ($activeSessions/$MAX_CONCURRENT_SESSIONS). Stop an existing session first."
            }
            val id = Uuid.random().toString()
            val installStatus = alpineRuntimeInstaller.ensureInstalled()
            require(installStatus.success) { installStatus.message }
            var mirrorLease: WorkspaceMirrorLease? = null
            try {
                mirrorLease = workspaceManager.acquireTerminalMirror(refreshFromWorkspace = false)
                val workingDir = mirrorLease.directory
                val processBuilder = ProcessBuilder(
                    "/system/bin/sh",
                    alpineRuntimeInstaller.localBinDir.resolve("init-host").absolutePath,
                    "/bin/sh",
                    "-l",
                )
                    .directory(workingDir)
                    .redirectErrorStream(true)
                alpineRuntimeInstaller.environment(
                    workspacePath = workingDir.absolutePath,
                    sessionId = id,
                ).forEach { (key, value) -> processBuilder.environment()[key] = value }
                val process = processBuilder.start()
                val session = TerminalSession(
                    id = id,
                    process = process,
                    writer = BufferedWriter(OutputStreamWriter(process.outputStream)),
                    workingDir = workingDir,
                    runtime = TerminalRuntimeKind.BUILTIN_ALPINE,
                    mirrorLease = mirrorLease,
                    output = TerminalSessionOutput(MAX_SESSION_OUTPUT_CHARS),
                    lastActivityMs = System.currentTimeMillis(),
                )
                sessions[id] = session
                session.reader = appScope.launch(Dispatchers.IO) { readSessionOutput(session) }
                TerminalSessionInfo(
                    id = id,
                    runtime = TerminalRuntimeKind.BUILTIN_ALPINE.wireName,
                    workspace = "/workspace",
                )
            } catch (error: Throwable) {
                mirrorLease?.release(syncBack = false)
                throw error
            }
        }
    }

    suspend fun execSession(id: String, command: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        check(session.process.isAlive) { "Terminal session has exited: $id. Read it to collect final output." }
        session.lastActivityMs = System.currentTimeMillis()
        session.writer.write(command)
        session.writer.newLine()
        session.writer.flush()
        readSession(id)
    }

    suspend fun readSession(id: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        TerminalReadResult(
            id = id,
            output = session.output.drain(),
            running = session.process.isAlive,
        )
    }

    suspend fun stopSession(id: String): TerminalReadResult = withContext(Dispatchers.IO) {
        val session = sessions[id] ?: error("Unknown terminal session: $id")
        withContext(NonCancellable) {
            var terminated = false
            var syncNote = ""
            try {
                runCatching { session.writer.close() }
                session.process.destroy()
                session.process.waitFor(1, TimeUnit.SECONDS)
                if (session.process.isAlive) {
                    session.process.destroyForcibly()
                    session.process.waitFor(1, TimeUnit.SECONDS)
                }
                check(!session.process.isAlive) { "Unable to stop terminal session: $id" }
                terminated = true
            } finally {
                if (terminated) {
                    sessions.remove(id, session)
                    withTimeoutOrNull(1_000L) { session.reader?.join() }
                    syncNote = runCatching {
                        session.mirrorLease.release(syncBack = workspaceManager.state.value.configured)
                    }.getOrElse { "workspace sync failed: ${it.message}" }
                }
            }
            TerminalReadResult(
                id = id,
                output = listOf(session.output.drain(), "session stopped", syncNote)
                    .filter { it.isNotBlank() }
                    .joinToString("; "),
                running = false,
            )
        }
    }

    private suspend fun readSessionOutput(session: TerminalSession) {
        val buffer = CharArray(DEFAULT_READ_BYTES)
        val reader = InputStreamReader(session.process.inputStream, Charsets.UTF_8)
        try {
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                session.output.append(String(buffer, 0, count))
                session.lastActivityMs = System.currentTimeMillis()
            }
        } catch (error: Throwable) {
            if (session.process.isAlive) {
                session.output.append("Terminal output stream closed: ${error.message.orEmpty()}\n")
            }
        } finally {
            if (session.process.isAlive) {
                runCatching { session.process.waitFor() }
            }
            if (!session.process.isAlive) {
                runCatching { session.writer.close() }
                val syncNote = runCatching {
                    session.mirrorLease.release(syncBack = workspaceManager.state.value.configured)
                }.getOrElse { "workspace sync failed: ${it.message}" }
                if (syncNote.isNotBlank()) session.output.append("$syncNote\n")
            }
        }
    }

    private fun pruneDeadSessionsLocked() {
        val deadSessions = sessions.values
            .filterNot { it.process.isAlive }
            .sortedBy { it.lastActivityMs }
        val removeCount = (deadSessions.size - MAX_RETAINED_SESSIONS + 1).coerceAtLeast(0)
        deadSessions.take(removeCount).forEach { session -> sessions.remove(session.id, session) }
    }

    private companion object {
        private const val DEFAULT_READ_BYTES = 64 * 1024
        private const val MAX_CONCURRENT_SESSIONS = 4
        private const val MAX_RETAINED_SESSIONS = 8
        private const val MAX_SESSION_OUTPUT_CHARS = 256 * 1024
    }
}

private data class TerminalSession(
    val id: String,
    val process: Process,
    val writer: BufferedWriter,
    val workingDir: File,
    val runtime: TerminalRuntimeKind,
    val mirrorLease: WorkspaceMirrorLease,
    val output: TerminalSessionOutput,
    @Volatile var reader: Job? = null,
    @Volatile var lastActivityMs: Long,
)

private class TerminalSessionOutput(private val maxChars: Int) {
    private val buffer = StringBuilder()
    private var truncated = false

    @Synchronized
    fun append(text: String) {
        buffer.append(text)
        if (buffer.length > maxChars) {
            buffer.delete(0, buffer.length - maxChars)
            truncated = true
        }
    }

    @Synchronized
    fun drain(): String {
        val output = buildString {
            if (truncated) append("[Session output truncated]\n")
            append(buffer)
        }
        buffer.clear()
        truncated = false
        return output
    }
}
