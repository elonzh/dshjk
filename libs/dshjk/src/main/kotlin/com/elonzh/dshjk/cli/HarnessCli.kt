package com.elonzh.dshjk.cli

import com.elonzh.dshjk.configuration.HarnessPatch
import com.elonzh.dshjk.configuration.ProfileInvocation
import com.elonzh.dshjk.error.CliException
import com.elonzh.dshjk.error.RequestTimeoutException
import com.elonzh.dshjk.runtime.internal.DshRuntime
import com.elonzh.dshjk.runtime.internal.RuntimeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.time.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The exit status and captured UTF-8 output of a profile. Stdout is null when redirected to a file. */
data class CliResult(val exitCode: Int, val stdout: String?, val stderr: String)

/** Executes profiles with the owning harness's runtime, home, working directory and environment. */
class HarnessCli internal constructor(private val runtime: DshRuntime) {
    /**
     * Runs one profile to completion. Stdin is empty unless supplied as a file.
     * A supplied output file is written directly and may contain partial output on failure.
     * Captured stdout and stderr are limited to 1 MiB each.
     */
    @JvmSynthetic suspend fun execute(
        invocation: ProfileInvocation,
        input: Path? = null,
        output: Path? = null,
        timeout: Duration = DEFAULT_CLI_TIMEOUT,
    ): CliResult = runtime.command { runtime.executeProfile(invocation, inputFile = input, output = output, timeout = timeout) }

    internal suspend fun executeWithOverlays(
        invocation: ProfileInvocation,
        arguments: List<String>,
        overlays: List<HarnessPatch>,
        stdin: InputStream? = null,
    ): CliResult = runtime.command {
        runtime.executeProfile(invocation, stdin = stdin, arguments = arguments, overlays = overlays)
    }

    @JvmName("execute") @JvmOverloads fun executeBlocking(
        invocation: ProfileInvocation,
        input: Path? = null,
        output: Path? = null,
        timeout: Duration = DEFAULT_CLI_TIMEOUT,
    ): CliResult = runBlocking { execute(invocation, input, output, timeout) }
}

internal val DEFAULT_CLI_TIMEOUT: Duration = Duration.ofSeconds(30)
private const val CAPTURE_LIMIT = 1024 * 1024
// Keep command teardown runnable even when concurrent uploads are blocked on pipe writes.
private val stdinDispatcher = Dispatchers.IO.limitedParallelism(64)

/** Runs inside the owner's command scope, including when called by a typed API. */
private suspend fun DshRuntime.executeProfile(
    invocation: ProfileInvocation,
    inputFile: Path? = null,
    stdin: InputStream? = null,
    output: Path? = null,
    timeout: Duration = DEFAULT_CLI_TIMEOUT,
    arguments: List<String> = invocation.arguments,
    overlays: List<HarnessPatch> = emptyList(),
): CliResult {
    require(inputFile == null || stdin == null) { "Provide at most one stdin source" }
    require(timeout.toMillis() > 0) { "CLI timeout must be at least one millisecond" }
    return try {
        RuntimeFiles("dshjk-cli-").use { files ->
            val stdout = output?.toAbsolutePath()?.normalize() ?: files.path("stdout")
            val stderr = files.path("stderr")
            withTimeoutOrNull(timeout) {
                coroutineScope {
                    launch(invocation, arguments, overlays) {
                        if (stdin == null) {
                            val path = inputFile?.toAbsolutePath()?.normalize() ?: Files.createFile(files.path("stdin"))
                            redirectInput(path.toFile())
                        }
                        redirectOutput(stdout.toFile())
                        redirectError(stderr.toFile())
                    }.use { child ->
                        val process = child.process
                        val writer = stdin?.let { stream ->
                            async(stdinDispatcher) { writeStdin(stream, process.outputStream) }
                        }
                        try {
                            while (process.isAlive) {
                                if (output == null) checkCaptureSize(stdout, invocation.profile, "stdout")
                                checkCaptureSize(stderr, invocation.profile, "stderr")
                                delay(25)
                            }
                            val exitCode = process.exitValue()
                            // A command can reject the input and close stdin early. Preserve its error response.
                            if (exitCode == 0) writer?.await()?.let { throw it }
                            CliResult(
                                exitCode,
                                if (output == null) readCapture(stdout, invocation.profile, "stdout") else null,
                                readCapture(stderr, invocation.profile, "stderr"),
                            )
                        } finally {
                            writer?.cancel()
                            // The process is closed by use before the scope waits for the writer to stop.
                        }
                    }
                }
            } ?: throw RequestTimeoutException("dsh profile '${invocation.profile}' timed out after $timeout")
        }
    } catch (error: IOException) {
        throw CliException(invocation.profile, "Cannot execute dsh profile '${invocation.profile}': ${error.message}", error)
    }
}

/** Reports pipe failures separately so the exit response can take precedence. */
private suspend fun writeStdin(source: InputStream, pipe: OutputStream): IOException? {
    val context = currentCoroutineContext()
    return runInterruptible {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            context.ensureActive()
            val count = try { source.read(buffer) } catch (error: IOException) {
                context.ensureActive()
                throw error
            }
            context.ensureActive()
            if (count < 0) break
            try { pipe.write(buffer, 0, count) } catch (error: IOException) { return@runInterruptible error }
        }
        // Only send EOF after reading successfully. On source failure, tear down the child instead.
        try { pipe.close(); null } catch (error: IOException) { error }
    }
}

private fun checkCaptureSize(path: Path, profile: String, stream: String) {
    if (Files.size(path) > CAPTURE_LIMIT) throw captureLimit(profile, stream)
}

private fun readCapture(path: Path, profile: String, stream: String): String =
    Files.newInputStream(path).use {
        val bytes = it.readNBytes(CAPTURE_LIMIT + 1)
        if (bytes.size > CAPTURE_LIMIT) throw captureLimit(profile, stream)
        String(bytes, Charsets.UTF_8)
    }

private fun captureLimit(profile: String, stream: String) =
    CliException(profile, "dsh profile '$profile' exceeded the $CAPTURE_LIMIT byte $stream capture limit")
