package com.elonzh.dshjk.office

import com.elonzh.dshjk.configuration.RuntimeCommand
import com.elonzh.dshjk.protocol.HarnessProtocol
import com.elonzh.dshjk.protocol.boundary
import com.elonzh.dshjk.protocol.objectValue
import com.elonzh.dshjk.protocol.string
import com.elonzh.dshjk.runtime.internal.BundledRuntime
import com.elonzh.dshjk.runtime.internal.DshRuntime
import com.elonzh.dshjk.runtime.internal.RuntimeFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val KIT_CLI = "node_modules/@deepseek-ai/libreoffice-kit/lib/cli.js"

/** The bundled executable supplies the authoritative Office download identity and downloader. */
internal data class OfficeDownload(val command: RuntimeCommand, val manifest: Path)

internal fun DshRuntime.officeDownload(): OfficeDownload? {
    val command = try {
        BundledRuntime.resolve(config.runtime)
    } catch (error: IOException) {
        throw unavailable("Cannot prepare bundled DSH runtime: ${error.message}", error)
    } catch (error: IllegalStateException) {
        throw unavailable("Invalid bundled DSH runtime: ${error.message}", error)
    }
    if (command.arguments.isNotEmpty()) return null
    val executable = executablePath(command.executable)?.let(::realPathOrSelf) ?: return null
    val name = executable.fileName.toString().removeSuffix(".exe").removeSuffix(".EXE")
    val prefix = "deepseek-harness-sdk-runtime-"
    if (!name.startsWith(prefix)) return null
    val manifest = executable.resolveSibling(name.removePrefix(prefix)).resolve("downloads.json")
    return if (Files.isRegularFile(manifest)) OfficeDownload(RuntimeCommand.executable(executable.toString()), manifest)
        else null
}

internal fun DshRuntime.officeCommand(files: RuntimeFiles, allowBundledDsh: Boolean): RuntimeCommand {
    environmentValue("DSH_OFFICE_SIDECAR")?.let {
        return sidecarCommand(parsePath(it, "DSH_OFFICE_SIDECAR"), "DSH_OFFICE_SIDECAR")
    }
    if (allowBundledDsh) {
        val download = officeDownload()
        if (download != null) {
            val manifestText = try {
                Files.readString(download.manifest)
            } catch (error: IOException) {
                throw unavailable("Cannot read Office download manifest at ${download.manifest}: ${error.message}", error)
            }
            val identity = boundary("Office download manifest") {
                HarnessProtocol.json.parseToJsonElement(manifestText).objectValue().string("identity")
                    .also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            }
            val cache = resourceCacheDirectory()
            val sidecar = cache.resolve(identity).resolve("office")
            val complete = sidecar.resolve("complete")
            val marker = try {
                if (Files.isRegularFile(complete)) Files.readString(complete) else null
            } catch (error: IOException) {
                throw unavailable("Cannot read Office cache marker at $complete: ${error.message}", error)
            }
            if (marker != identity) {
                throw unavailable("Office resources are missing at $sidecar. Call office.downloadRuntime() first")
            }
            return sidecarCommand(sidecar, "Office sidecar")
        }
    }

    val runtime = config.runtime
    val project = config.processCwd.toAbsolutePath().normalize().resolve("package.json")
    val dsoffice = executablePath("dsoffice")
    val anchors = if (allowBundledDsh) {
        val module = runtime.nodeModule ?: executablePath(runtime.executable)
        listOfNotNull(module, dsoffice, project)
    } else {
        listOfNotNull(dsoffice, project, executablePath("dsh"))
    }.map { if (Files.isRegularFile(it)) realPathOrSelf(it) else it }.distinct()

    val preferredNode = if (allowBundledDsh && runtime.nodeModule != null) runtime.executable else "node"
    val executable = executablePath(preferredNode)
        ?: dsoffice?.parent?.let { siblingNode(it) }
        ?: throw unavailable("Cannot locate Node.js for LibreOffice Kit. Install Node.js, set DSH_OFFICE_SIDECAR, or configure an explicit Office command")
    val bootstrap = try {
        Files.writeString(files.path("office.mjs"), IMPORT_CLI)
    } catch (error: IOException) {
        throw OfficeException("failed", "Cannot create Office launcher script: ${error.message}", cause = error)
    }
    return RuntimeCommand.of(executable.toString(),
        listOf(bootstrap.toString(), anchors.size.toString()) + anchors.map(Path::toString))
}

private fun DshRuntime.resourceCacheDirectory(): Path {
    environmentValue("DSH_RESOURCE_CACHE")?.let { configured ->
        val cache = parsePath(configured, "DSH_RESOURCE_CACHE")
        if (!cache.isAbsolute) throw unavailable("DSH_RESOURCE_CACHE must be an absolute directory")
        return cache
    }
    val home = environmentValue(if (windows) "USERPROFILE" else "HOME")
        ?.takeIf(String::isNotBlank)
        ?: System.getProperty("user.home")
    val cache = parsePath(home, "user home").resolve(".cache").resolve("deepseek-harness").resolve("resources")
    if (!cache.isAbsolute) throw unavailable("Resolved Office cache directory must be absolute: $cache")
    return cache
}

private fun sidecarCommand(root: Path, source: String): RuntimeCommand {
    if (!root.isAbsolute) throw unavailable("$source must be an absolute directory")
    val node = root.resolve(if (windows) "node/bin/node.exe" else "node/bin/node")
    val cli = root.resolve(KIT_CLI)
    if (!isExecutableFile(node) || !Files.isRegularFile(cli)) {
        throw unavailable("Office sidecar is incomplete at $root")
    }
    return RuntimeCommand.node(cli, node.toString())
}

private fun siblingNode(directory: Path): Path? =
    executableNames("node").asSequence()
        .map(directory::resolve)
        .firstOrNull(::isExecutableFile)

private fun DshRuntime.environmentValue(name: String): String? {
    val configured = if (windows) {
        config.environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    } else {
        config.environment[name]
    }
    return configured ?: if (config.inheritEnvironment) System.getenv(name) else null
}

private fun DshRuntime.executablePath(name: String): Path? {
    val path = parsePathOrNull(name) ?: return null
    if (path.isAbsolute || path.nameCount > 1) {
        val resolved = config.processCwd.toAbsolutePath().resolve(path).normalize()
        return candidatePaths(resolved, name).firstOrNull(::isExecutableFile)
    }
    val names = executableNames(name)
    val cwd = config.processCwd.toAbsolutePath()
    return environmentValue("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
        .filter(String::isNotBlank)
        .flatMap { directory ->
            val root = parsePathOrNull(directory.trim('"'))?.let(cwd::resolve) ?: return@flatMap emptySequence()
            names.asSequence().mapNotNull { candidate -> parsePathOrNull(candidate)?.let(root::resolve) }
        }.firstOrNull(::isExecutableFile)
}

private fun isExecutableFile(path: Path): Boolean =
    Files.isRegularFile(path) && Files.isExecutable(path)

private fun executableNames(name: String): List<String> =
    if (windows && !name.contains('.')) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)

private fun candidatePaths(resolved: Path, originalName: String): List<Path> {
    val fileName = resolved.fileName?.toString() ?: return listOf(resolved)
    return if (windows && !fileName.contains('.') && !originalName.endsWith(File.separatorChar)) {
        val parent = resolved.parent
        if (parent != null) executableNames(fileName).map(parent::resolve) else listOf(resolved)
    } else listOf(resolved)
}

private fun parsePath(value: String, label: String): Path = try {
    Path.of(value)
} catch (error: InvalidPathException) {
    throw unavailable("Invalid $label path: ${error.message}", error)
}

private fun parsePathOrNull(value: String): Path? = try {
    Path.of(value)
} catch (_: InvalidPathException) {
    null
}

private fun realPathOrSelf(path: Path): Path = try {
    path.toRealPath()
} catch (_: IOException) {
    path
}

private val windows = File.separatorChar == '\\'

internal fun unavailable(message: String, cause: Throwable? = null): OfficeException =
    OfficeException("unavailable", message, cause = cause)

/** Resolve the installed dependency chain as Node does, including non-hoisted installs. */
private val IMPORT_CLI = """
    import { createRequire } from 'node:module'
    import { pathToFileURL } from 'node:url'
    const [, , count, ...values] = process.argv
    const anchors = values.slice(0, Number(count))
    let cli
    for (const anchor of anchors) {
      const require = createRequire(anchor)
      try {
        const dsh = createRequire(require.resolve('@deepseek-ai/dsh/package.json'))
        const sdk = createRequire(dsh.resolve('@deepseek-ai/dsh-sdk-app/package.json'))
        const office = createRequire(sdk.resolve('@deepseek-ai/dsh-office-kit/package.json'))
        cli = office.resolve('@deepseek-ai/libreoffice-kit/cli')
        break
      } catch (error) {
        if (error.code !== 'MODULE_NOT_FOUND' && error.code !== 'ERR_PACKAGE_PATH_NOT_EXPORTED') throw error
      }
      try {
        cli = require.resolve('@deepseek-ai/libreoffice-kit/cli')
        break
      } catch (error) {
        if (error.code !== 'MODULE_NOT_FOUND' && error.code !== 'ERR_PACKAGE_PATH_NOT_EXPORTED') throw error
      }
    }
    if (cli) {
      process.argv = [process.execPath, cli, ...values.slice(Number(count))]
      await import(pathToFileURL(cli).href)
    } else {
      console.error(JSON.stringify({
        code: 'unavailable',
        error: 'LibreOffice Kit was not found. Install @deepseek-ai/libreoffice-kit (dsoffice), install the DSH npm runtime, set DSH_OFFICE_SIDECAR, or configure an explicit Office command.',
      }))
      process.exitCode = 1
    }
""".trimIndent()
