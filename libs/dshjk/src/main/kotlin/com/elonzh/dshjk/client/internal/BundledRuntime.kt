package com.elonzh.dshjk.client.internal

import com.elonzh.dshjk.configuration.RuntimeCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/** Resolves an optional Maven runtime JAR to files suitable for a native child process. */
internal object BundledRuntime {
    private const val RESOURCE_ROOT = "META-INF/dshjk/runtime"
    private const val MANIFEST_FILE = "manifest.json"
    private val runtimeVersionPattern = Regex("[0-9][0-9A-Za-z.+-]*-[0-9]+")

    @Serializable
    private data class Manifest(
        val version: String,
        val executable: String,
        val helpers: List<String>,
        val files: Map<String, String>,
    ) {
        val executables get() = listOf(executable) + helpers
    }

    fun resolve(command: RuntimeCommand): RuntimeCommand {
        if (command !== RuntimeCommand.automatic) return command
        val platform = currentPlatform() ?: return command
        val loader = BundledRuntime::class.java.classLoader
        val prefix = "$RESOURCE_ROOT/$platform"
        val manifests = loader.getResources("$prefix/$MANIFEST_FILE").toList()
        if (manifests.isEmpty()) return command
        check(manifests.size == 1) { "Multiple dshjk-runtime JARs for $platform are on the classpath" }
        val manifest = manifests.single().openStream().bufferedReader().use { Json.decodeFromString<Manifest>(it.readText()) }
        check(runtimeVersionPattern.matches(manifest.version)) { "Invalid dshjk-runtime version: ${manifest.version}" }
        check(manifest.files.keys.containsAll(manifest.executables)) { "Incomplete dshjk-runtime manifest" }
        for (name in manifest.files.keys) {
            check('\\' !in name && Path.of(name).root == null &&
                name.split('/').none { it.isEmpty() || it == "." || it == ".." }
            ) { "Invalid dshjk-runtime entry: $name" }
        }

        val root = System.getProperty("dshjk.runtime.cacheDir")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".cache", "dshjk", "runtime")
        val destination = root.toAbsolutePath().normalize().resolve(platform).resolve(manifest.version)
        val posix = platform != "windows-x64"
        if (Files.notExists(destination, NOFOLLOW_LINKS)) {
            install(destination, manifest, posix) { name ->
                checkNotNull(loader.getResourceAsStream("$prefix/files/$name")) {
                    "Missing dshjk-runtime file: $name"
                }
            }
        }
        validate(destination, manifest, posix)
        val cachedManifest = destination.resolve(MANIFEST_FILE)
        check(Files.isRegularFile(cachedManifest, NOFOLLOW_LINKS) &&
            Json.decodeFromString<Manifest>(Files.readString(cachedManifest)) == manifest
        ) { "Cached dshjk-runtime differs from its JAR for version ${manifest.version} at $destination" }
        return RuntimeCommand.executable(destination.resolve(manifest.executable).toString())
    }

    private fun install(destination: Path, manifest: Manifest, posix: Boolean, openResource: (String) -> InputStream) {
        Files.createDirectories(destination.parent)
        val staging = Files.createTempDirectory(destination.parent, ".extract-")
        try {
            for ((name, checksum) in manifest.files) {
                val path = staging.resolve(name)
                Files.createDirectories(path.parent)
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(openResource(name), digest).use { Files.copy(it, path) }
                check(HexFormat.of().formatHex(digest.digest()) == checksum) {
                    "dshjk-runtime checksum mismatch: $name"
                }
            }
            if (posix) {
                for (name in manifest.executables) {
                    check(staging.resolve(name).toFile().setExecutable(true, true)) {
                        "Cannot make bundled dsh file executable: $name"
                    }
                }
            }
            validate(staging, manifest, posix)
            Files.writeString(staging.resolve(MANIFEST_FILE), Json.encodeToString(manifest))
            try {
                Files.move(staging, destination, ATOMIC_MOVE)
            } catch (error: IOException) {
                // Another JVM may have installed this version while we copied it.
                if (!Files.isDirectory(destination, NOFOLLOW_LINKS)) throw error
            }
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    private fun validate(directory: Path, manifest: Manifest, posix: Boolean) {
        check(Files.isDirectory(directory, NOFOLLOW_LINKS) &&
            manifest.files.keys.all { Files.isRegularFile(directory.resolve(it), NOFOLLOW_LINKS) } &&
            (!posix || manifest.executables.all { Files.isExecutable(directory.resolve(it)) })
        ) { "Incomplete bundled dsh runtime in $directory" }
    }

    private fun currentPlatform(): String? {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val arch = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return when {
            os.startsWith("linux") -> "linux-$arch"
            os.startsWith("mac") -> "macos-$arch"
            os.startsWith("windows") && arch == "x64" -> "windows-x64"
            else -> null
        }
    }
}
