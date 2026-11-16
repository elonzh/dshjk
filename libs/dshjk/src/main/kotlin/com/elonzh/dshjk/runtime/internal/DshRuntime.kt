package com.elonzh.dshjk.runtime.internal

import com.elonzh.dshjk.configuration.HarnessConfig
import com.elonzh.dshjk.configuration.HarnessPatch
import com.elonzh.dshjk.configuration.ProfileInvocation
import com.elonzh.dshjk.configuration.patchDocument
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Shared process launch settings and lifetime for the owner's one-shot commands. */
internal class DshRuntime(private val config: HarnessConfig) {
    private val admission = Any()
    private val commands = SupervisorJob()
    private val commandScope = CoroutineScope(commands + Dispatchers.IO)

    fun checkOpen() = check(commands.isActive) { "Harness closed" }

    fun stopAccepting() = synchronized(admission) { commands.cancel() }

    suspend fun awaitCommands() = commands.join()

    suspend fun <T> command(action: suspend () -> T): T {
        val task = synchronized(admission) {
            checkOpen()
            commandScope.async { action() }
        }
        try {
            return task.await()
        } finally {
            withContext(NonCancellable) { task.cancelAndJoin() }
        }
    }

    fun launch(
        invocation: ProfileInvocation,
        arguments: List<String> = invocation.arguments,
        overlays: List<HarnessPatch> = emptyList(),
        configure: ProcessBuilder.() -> Unit = {},
    ): DshProcess {
        checkOpen()
        val files = RuntimeFiles("dshjk-patches-")
        try {
            val runtime = BundledRuntime.resolve(config.runtime)
            val argv = buildList {
                add(runtime.executable)
                addAll(runtime.arguments)
                addAll(listOf("--profile", invocation.profile))
                if (invocation.patches.isNotEmpty()) {
                    val patch = Files.writeString(files.path("launch.json"), invocation.patchDocument)
                    addAll(listOf("--patch", patch.toString()))
                }
                for (patch in invocation.patchFiles) addAll(listOf("--patch", patch.toString()))
                if (overlays.isNotEmpty()) {
                    val patch = Files.writeString(files.path("overlays.json"), patchDocument(overlays))
                    addAll(listOf("--patch", patch.toString()))
                }
                if (arguments.isNotEmpty()) {
                    add("--")
                    addAll(arguments)
                }
            }
            val builder = ProcessBuilder(argv)
                .directory(config.processCwd.toAbsolutePath().normalize().toFile())
            builder.environment().apply {
                if (!config.inheritEnvironment) clear()
                putAll(config.environment)
                config.dshHome?.let { put("DSH_HOME", it.toAbsolutePath().normalize().toString()) }
            }
            builder.configure()
            val process = synchronized(admission) {
                checkOpen()
                builder.start()
            }
            return DshProcess(process, config.terminateGrace, files)
        } catch (error: Throwable) {
            try { files.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}

/** Owns the started process and its launch files. The caller supplies any protocol shutdown. */
internal class DshProcess(
    val process: Process,
    private val grace: Duration,
    private val files: RuntimeFiles,
) : AutoCloseable {
    private val handle = process.toHandle()
    private val descendants = linkedSetOf<ProcessHandle>()

    fun trackDescendants() {
        handle.descendants().use { it.forEach(descendants::add) }
    }

    override fun close() {
        trackDescendants()
        // Use ProcessHandle to avoid synchronous pipe closure while the RPC writer is blocked.
        if (handle.isAlive) handle.destroy()
        if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            trackDescendants()
        }
        for (child in descendants) if (child.isAlive) child.destroyForcibly()
        if (handle.isAlive) handle.destroyForcibly()
        if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            throw IOException("Could not confirm termination of dsh process ${process.pid()}")
        }
        val deadline = System.nanoTime() + grace.toNanos()
        while (descendants.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        val remaining = descendants.filter(ProcessHandle::isAlive)
        if (remaining.isNotEmpty()) {
            throw IOException("Could not confirm termination of dsh descendants ${remaining.map(ProcessHandle::pid)}")
        }
        files.close()
    }
}
