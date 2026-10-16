package com.multiviewer.ui.ai

import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

enum class AiFailure { AUTH_REQUIRED, EXIT_ERROR, EMPTY_OUTPUT, LAUNCH_FAILED }

sealed interface AiCliResult {
    data class Answer(val text: String, val truncated: Boolean) : AiCliResult
    data class Failed(val reason: AiFailure, val exitCode: Int?, val detail: String) : AiCliResult
    data object TimedOut : AiCliResult
    /** The file no longer matches the captured context; nothing was sent. */
    data object StaleFile : AiCliResult
}

const val DEFAULT_AI_TIMEOUT_MILLIS = 180_000L
const val DEFAULT_AI_MAX_OUTPUT_BYTES = 512 * 1024
private const val MAX_STDERR_BYTES = 16 * 1024

private val AUTH_HINT = Regex("(?i)/login|not logged in|log ?in required|please log ?in|unauthori[sz]ed|authenticat|api key|credential|sign ?in|\\b401\\b|\\b403\\b")
private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

// The first lines usually say what went wrong (stack traces follow), the last lines how it ended.
private fun excerpt(text: String): String {
    val t = text.trim()
    return if (t.length <= 1_200) t else t.take(800) + "\n…\n" + t.takeLast(400)
}

private class BoundedCollector(private val input: InputStream, private val cap: Int) : Thread("ai-cli-reader") {
    val bytes = ByteArrayOutputStream()
    @Volatile var truncated = false
    init { isDaemon = true }
    override fun run() {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val room = cap - bytes.size()
                if (room > 0) bytes.write(buf, 0, minOf(n, room))
                // Keep draining past the cap so the CLI never blocks on a full pipe.
                if (n > room) truncated = true
            }
        } catch (_: Exception) {
        }
    }
    fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8) }
}

private const val AI_WORK_DIR_PREFIX = "unwrapmedia-ai-"

/**
 * Removes AI work directories left behind when the app was killed mid-run (the runner deletes its own in a
 * finally, but a crash or force-quit skips it, and a dir can hold a screen crop). Only directories with the
 * runner's prefix and older than [olderThanMillis] are touched, so a run in another app instance survives.
 * Blocking: call off the UI thread. Returns how many directories were removed.
 */
fun cleanStaleAiWorkDirs(
    tempDir: File = File(System.getProperty("java.io.tmpdir")),
    olderThanMillis: Long = 60 * 60 * 1000L,
    now: Long = System.currentTimeMillis(),
): Int {
    val candidates = tempDir.listFiles { f -> f.isDirectory && f.name.startsWith(AI_WORK_DIR_PREFIX) } ?: return 0
    var removed = 0
    for (dir in candidates) {
        val newest = dir.walkTopDown().maxOfOrNull { it.lastModified() } ?: dir.lastModified()
        if (now - newest >= olderThanMillis && dir.deleteRecursively()) removed++
    }
    return removed
}

/**
 * Runs one request: [prompt] on stdin, in a fresh app-owned temp directory (deleted afterwards), with an
 * enforced wall-clock [timeoutMillis], bounded stdout/stderr, and process-tree termination on timeout or
 * coroutine cancellation (which propagates as CancellationException). [fileStillCurrent] is checked right
 * before launch so a context describing old bytes is never sent.
 */
suspend fun runAiCli(
    adapter: AiCliAdapter,
    executable: File,
    prompt: String,
    timeoutMillis: Long = DEFAULT_AI_TIMEOUT_MILLIS,
    maxOutputBytes: Int = DEFAULT_AI_MAX_OUTPUT_BYTES,
    fileStillCurrent: () -> Boolean = { true },
    images: List<java.awt.image.BufferedImage> = emptyList(),
): AiCliResult = runInterruptible(Dispatchers.IO) {
    if (!fileStillCurrent()) return@runInterruptible AiCliResult.StaleFile
    val workDir = Files.createTempDirectory(AI_WORK_DIR_PREFIX).toFile()
    var process: Process? = null
    try {
        try {
            adapter.prepareWorkDirectory(workDir)
        } catch (e: Exception) {
            return@runInterruptible AiCliResult.Failed(AiFailure.LAUNCH_FAILED, null, "Could not prepare CLI configuration: ${e.message ?: e}")
        }
        // Images are re-encoded crops (no source metadata). CLIs that take paths get them as files inside the
        // app-owned temp dir (deleted below); inline CLIs get the bytes in stdin and no file is written.
        val attachments = images.mapIndexed { i, image ->
            val png = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
            AiImageAttachment(File(workDir, "view-${i + 1}.png"), png)
        }
        val invocation = adapter.invocation(prompt, attachments)
            ?: return@runInterruptible AiCliResult.Failed(AiFailure.LAUNCH_FAILED, null, "${adapter.provider.displayName} cannot take images from this app")
        if (!invocation.imagesInline) attachments.forEach { it.file.writeBytes(it.png) }
        val command = try {
            launchCommand(executable, invocation.arguments)
        } catch (e: IllegalArgumentException) {
            return@runInterruptible AiCliResult.Failed(AiFailure.LAUNCH_FAILED, null, e.message ?: e.toString())
        }
        val builder = ProcessBuilder(command).directory(workDir)
        // npm shims need node next to them; GUI launches often lack the user's shell PATH.
        val env = builder.environment()
        val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        env[pathKey] = (listOfNotNull(executable.parentFile?.path) + cliSearchDirectories().map { it.path } + listOfNotNull(env[pathKey]))
            .distinct().joinToString(File.pathSeparator)
        env["NO_COLOR"] = "1"
        val started = try {
            builder.start()
        } catch (e: Exception) {
            return@runInterruptible AiCliResult.Failed(AiFailure.LAUNCH_FAILED, null, e.message ?: e.toString())
        }
        process = started
        ProcessManager.register(started)
        val out = BoundedCollector(started.inputStream, maxOutputBytes).also { it.start() }
        val err = BoundedCollector(started.errorStream, MAX_STDERR_BYTES).also { it.start() }
        Thread({
            try {
                started.outputStream.use { it.write(invocation.stdin.toByteArray(Charsets.UTF_8)) }
            } catch (_: Exception) {
                // The CLI may exit before reading everything; its exit status tells the story.
            }
        }, "ai-cli-writer").apply { isDaemon = true }.start()

        val finished = try {
            started.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            ProcessManager.terminate(started)
            throw e
        }
        if (!finished) {
            ProcessManager.terminate(started)
            return@runInterruptible AiCliResult.TimedOut
        }
        out.join(2_000)
        err.join(2_000)
        // A cap can split a multi-byte character; drop the replacement char it leaves at the cut.
        val stdout = ANSI.replace(out.text(), "").let { if (out.truncated) it.trimEnd('\uFFFD') else it }
        val stderr = ANSI.replace(err.text(), "")
        val exit = started.exitValue()
        when {
            exit != 0 && (AUTH_HINT.containsMatchIn(stderr) || AUTH_HINT.containsMatchIn(stdout.takeLast(2_000))) ->
                AiCliResult.Failed(AiFailure.AUTH_REQUIRED, exit, excerpt(stderr.ifBlank { stdout }))
            exit != 0 -> AiCliResult.Failed(AiFailure.EXIT_ERROR, exit, excerpt(stderr.ifBlank { stdout }))
            else -> when (val parsed = invocation.parse(stdout)) {
                is AiParsedOutput.Answer ->
                    if (parsed.text.isBlank()) AiCliResult.Failed(AiFailure.EMPTY_OUTPUT, exit, excerpt(stderr))
                    else AiCliResult.Answer(parsed.text, out.truncated)
                // The CLI exited 0 but said in its own output that the run failed (stream-json `is_error`).
                is AiParsedOutput.Error -> if (out.truncated) AiCliResult.Failed(
                    AiFailure.EXIT_ERROR,
                    exit,
                    "Output exceeded the ${maxOutputBytes / 1024} KB limit before the CLI finished its answer",
                ) else AiCliResult.Failed(
                    if (AUTH_HINT.containsMatchIn(parsed.message)) AiFailure.AUTH_REQUIRED else AiFailure.EXIT_ERROR,
                    exit,
                    excerpt(parsed.message),
                )
            }
        }
    } finally {
        process?.let { if (it.isAlive) ProcessManager.terminate(it) else ProcessManager.unregister(it) }
        // Windows may hold a just-killed process's handles briefly.
        // Cancellation leaves this thread interrupted; clear it while retrying, then restore it.
        val interrupted = Thread.interrupted()
        try {
            var attempt = 0
            while (!workDir.deleteRecursively() && attempt < 5) Thread.sleep(100L * ++attempt)
        } catch (_: InterruptedException) {
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

/** `<cli> --version` (local, no network), bounded to a few seconds; null if it cannot be run. */
suspend fun probeCliVersion(executable: File, timeoutMillis: Long = 10_000): String? = runInterruptible(Dispatchers.IO) {
    val process = try {
        ProcessBuilder(launchCommand(executable, listOf("--version"))).redirectErrorStream(true).start()
    } catch (_: Exception) {
        return@runInterruptible null
    }
    ProcessManager.register(process)
    val out = BoundedCollector(process.inputStream, 4_096).also { it.start() }
    try {
        process.outputStream.close()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return@runInterruptible null
        out.join(1_000)
        ANSI.replace(out.text(), "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(120)
    } finally {
        if (process.isAlive) ProcessManager.terminate(process) else ProcessManager.unregister(process)
    }
}
