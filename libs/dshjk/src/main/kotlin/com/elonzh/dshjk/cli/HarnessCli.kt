package com.elonzh.dshjk.cli

import com.elonzh.dshjk.configuration.HarnessPatch
import com.elonzh.dshjk.configuration.ProfileInvocation
import com.elonzh.dshjk.error.CliException
import com.elonzh.dshjk.error.RequestTimeoutException
import com.elonzh.dshjk.runtime.internal.DshRuntime
import com.elonzh.dshjk.runtime.internal.RuntimeFiles
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.time.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

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
    ): CliResult = runtime.command { runtime.executeProfile(invocation, input, output, timeout) }

    internal suspend fun executeWithOverlays(
        invocation: ProfileInvocation,
        arguments: List<String>,
        overlays: List<HarnessPatch>,
        input: Path?,
    ): CliResult = runtime.command {
        runtime.executeProfile(invocation, input = input, arguments = arguments, overlays = overlays)
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

/** Runs inside the owner's command scope, including when called by a typed API. */
private suspend fun DshRuntime.executeProfile(
    invocation: ProfileInvocation,
    input: Path? = null,
    output: Path? = null,
    timeout: Duration = DEFAULT_CLI_TIMEOUT,
    arguments: List<String> = invocation.arguments,
    overlays: List<HarnessPatch> = emptyList(),
): CliResult {
    require(timeout.toMillis() > 0) { "CLI timeout must be at least one millisecond" }
    return try {
        RuntimeFiles("dshjk-cli-").use { files ->
            val stdin = input?.toAbsolutePath()?.normalize() ?: Files.createFile(files.path("stdin"))
            val stdout = output?.toAbsolutePath()?.normalize() ?: files.path("stdout")
            val stderr = files.path("stderr")
            launch(invocation, arguments, overlays) {
                redirectInput(stdin.toFile())
                redirectOutput(stdout.toFile())
                redirectError(stderr.toFile())
            }.use { child ->
                withTimeoutOrNull(timeout) {
                    runInterruptible {
                        val process = child.process
                        do {
                            val exited = process.waitFor(25, TimeUnit.MILLISECONDS)
                            if (output == null) checkCaptureSize(stdout, invocation.profile, "stdout")
                            checkCaptureSize(stderr, invocation.profile, "stderr")
                        } while (!exited)
                        CliResult(
                            process.exitValue(),
                            if (output == null) readCapture(stdout, invocation.profile, "stdout") else null,
                            readCapture(stderr, invocation.profile, "stderr"),
                        )
                    }
                } ?: throw RequestTimeoutException("dsh profile '${invocation.profile}' timed out after $timeout")
            }
        }
    } catch (error: IOException) {
        throw CliException(invocation.profile, "Cannot execute dsh profile '${invocation.profile}': ${error.message}", error)
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
