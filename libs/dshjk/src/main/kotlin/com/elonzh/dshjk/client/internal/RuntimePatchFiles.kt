package com.elonzh.dshjk.client.internal

import com.elonzh.dshjk.configuration.HarnessConfig
import java.nio.file.Files
import java.nio.file.Path

/** Launch-scoped files; created lazily and retained until the child has exited. */
internal class RuntimePatchFiles(config: HarnessConfig) : AutoCloseable {
    private var directory: Path? = null
    val paths: List<Path>

    init {
        try {
            paths = buildList {
                if (config.patches.isNotEmpty()) {
                    val json = config.patchDocument
                    val root = Files.createTempDirectory("dshjk-patches-")
                    directory = root
                    val file = root.resolve("launch.json")
                    Files.writeString(file, json)
                    add(file)
                }
                addAll(config.patchFiles)
            }
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun close() {
        directory?.let { root ->
            Files.deleteIfExists(root.resolve("launch.json"))
            Files.deleteIfExists(root)
            directory = null
        }
    }
}
