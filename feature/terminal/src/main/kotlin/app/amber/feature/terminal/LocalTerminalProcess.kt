package app.amber.feature.terminal

import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Owns one admitted local job's native process, output reader and process-tree cleanup. */
internal class LocalTerminalProcess(
    private val jobId: String,
    private val timeoutMillis: Long,
) {
    @Volatile private var process: Process? = null
    private var reader: Thread? = null

    val isAlive: Boolean get() = process?.isAlive == true

    fun start(builder: ProcessBuilder, status: () -> TerminalJobStatus, onOutput: (String) -> Unit) {
        val started = builder.start()
        process = started
        reader = thread(name = "amberagent-terminal-output-$jobId") {
            readOutput(started, { status().running }, onOutput)
        }
    }

    fun await(status: () -> TerminalJobStatus, onOutput: (String) -> Unit): LocalTerminalProcessResult {
        val started = checkNotNull(process)
        val outcome = waitForProcess(started, status, onOutput)
        reader?.join(1_000)
        val exitCode = if (started.isAlive) null else runCatching { started.exitValue() }.getOrNull()
        return LocalTerminalProcessResult(outcome, exitCode)
    }

    fun terminate() {
        process?.let(::terminateProcess)
    }

    fun readOutput(process: Process, isRunning: () -> Boolean, onOutput: (String) -> Unit) {
        val buffer = CharArray(8 * 1024)
        val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
        try {
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                onOutput(String(buffer, 0, count))
            }
        } catch (error: Throwable) {
            if (process.isAlive && isRunning()) {
                onOutput("Terminal output stream closed: ${error.message.orEmpty()}\n")
            }
        }
    }

    private fun waitForProcess(process: Process, status: () -> TerminalJobStatus, onOutput: (String) -> Unit): TerminalJobStatus {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (status() == TerminalJobStatus.CANCELLED) {
                terminateProcess(process)
                return TerminalJobStatus.CANCELLED
            }
            if (process.waitFor(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                return TerminalJobStatus.COMPLETED
            }
        }
        onOutput("Command timed out after ${timeoutMillis}ms.\n")
        terminateProcess(process)
        return TerminalJobStatus.TIMED_OUT
    }

    private fun terminateProcess(process: Process) {
        val pid = processPid(process) ?: findJobProcessPid(jobId)
        pid?.let { killProcessTree(it, signal = "TERM") }
        runCatching { process.destroy() }
        runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        if (process.isAlive) {
            pid?.let { killProcessTree(it, signal = "KILL") }
            process.destroyForcibly()
            process.waitFor()
        }
        check(!process.isAlive) { "Terminal process did not stop; workspace lease remains held." }
    }

    private fun processPid(process: Process): Long? =
        processPidFromMethod(process) ?: processPidFromField(process)

    private fun processPidFromMethod(process: Process): Long? = runCatching {
        val value = process.javaClass.getMethod("pid").invoke(process)
        (value as? Number)?.toLong()
    }.getOrNull()

    private fun processPidFromField(process: Process): Long? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        (field.get(process) as? Number)?.toLong()
    }.getOrNull()

    private fun killProcessTree(pid: Long, signal: String) {
        val normalizedSignal = if (signal == "KILL") "9" else "15"
        collectDescendantPids(pid)
            .asReversed()
            .forEach { childPid ->
                runCatching {
                    ProcessBuilder("/system/bin/kill", "-$normalizedSignal", childPid.toString())
                        .start()
                        .waitFor(1, TimeUnit.SECONDS)
                }
            }
        runCatching {
            ProcessBuilder("/system/bin/pkill", "-$normalizedSignal", "-P", pid.toString())
                .start()
                .waitFor(1, TimeUnit.SECONDS)
        }
        runCatching {
            ProcessBuilder("/system/bin/kill", "-$normalizedSignal", pid.toString())
                .start()
                .waitFor(1, TimeUnit.SECONDS)
        }
    }

    private fun findJobProcessPid(jobId: String): Long? =
        processRows()
            .firstOrNull { row -> row.command.contains("AMBERAGENT_JOB_ID") && row.command.contains(jobId) }
            ?.pid

    private fun collectDescendantPids(rootPid: Long): List<Long> {
        val childrenByParent = processRows().groupBy { it.parentPid }
        val result = mutableListOf<Long>()
        fun visit(parentPid: Long) {
            childrenByParent[parentPid].orEmpty().forEach { child ->
                result += child.pid
                visit(child.pid)
            }
        }
        visit(rootPid)
        return result
    }

    private fun processRows(): List<ProcessRow> = runCatching {
        val process = ProcessBuilder("/system/bin/ps", "-ef").start()
        val rows = process.inputStream.bufferedReader().useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 8)
                val pid = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val parentPid = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                ProcessRow(
                    pid = pid,
                    parentPid = parentPid,
                    command = parts.getOrNull(7).orEmpty(),
                )
            }.toList()
        }
        process.waitFor(1, TimeUnit.SECONDS)
        rows
    }.getOrDefault(emptyList())

}

internal data class LocalTerminalProcessResult(val status: TerminalJobStatus, val exitCode: Int?)

private data class ProcessRow(
    val pid: Long,
    val parentPid: Long,
    val command: String,
)
