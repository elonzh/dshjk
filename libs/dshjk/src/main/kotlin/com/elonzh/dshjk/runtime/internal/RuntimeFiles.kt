package com.elonzh.dshjk.runtime.internal

import java.nio.file.Files
import java.nio.file.Path

/** Lazily creates a private temporary directory for files owned by one operation. */
internal class RuntimeFiles(private val prefix: String, private val parent: Path? = null) : AutoCloseable {
    private var directory: Path? = null

    fun path(name: String): Path {
        val root = directory ?: if (parent == null) Files.createTempDirectory(prefix)
            else Files.createTempDirectory(parent, prefix)
        directory = root
        return root.resolve(name)
    }

    override fun close() {
        val root = directory ?: return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        directory = null
    }
}
