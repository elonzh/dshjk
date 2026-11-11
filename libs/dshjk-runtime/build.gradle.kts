import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    base
    `maven-publish`
}

description = "Bundled DeepSeek Harness runtime for dshjk"

val cliManifest = layout.projectDirectory.file("package.json")
val cliPackage = JsonSlurper().parseText(providers.fileContents(cliManifest).asText.get()) as Map<*, *>
val dependencies = cliPackage["dependencies"] as Map<*, *>
val dshVersion = dependencies["@deepseek-ai/dsh"] as String
val packagingRevision = 1
val runtimeVersion = "$dshVersion-$packagingRevision"
version = runtimeVersion

val upstreamCheckout = providers.gradleProperty("dshRuntimeSourceDir").map { rootProject.file(it) }
if (upstreamCheckout.isPresent) {
    val upstream = upstreamCheckout.get()
    val dist = upstream.resolve("dist-exe")
    val notices = listOf(upstream.resolve("LICENSE"), upstream.resolve("THIRD_PARTY_NOTICES.md"))
    require(notices.all(File::isFile)) { "DeepSeek Harness license and notices are missing in $upstream" }

    val platforms = listOf("linux-x64", "linux-arm64", "macos-x64", "macos-arm64", "windows-x64")
    val executables = platforms.mapNotNull { platform ->
        val upstreamPlatform = platform.replace("windows-", "win-")
        val binary = "deepseek-harness-sdk-runtime-$upstreamPlatform"
        val suffix = if (upstreamPlatform == "win-x64") ".exe" else ""
        val executable = dist.resolve("$binary$suffix")
        (platform to executable).takeIf { executable.isFile }
    }
    require(executables.isNotEmpty()) { "No DeepSeek Harness executables found in $dist" }

    val runtimeJars = executables.map { (platform, executable) ->
        val upstreamPlatform = platform.replace("windows-", "win-")
        val binary = executable.nameWithoutExtension

        val helpers = buildList {
            val ripgrep = if (upstreamPlatform == "win-x64") "$binary-rg.exe" else "$binary-rg"
            add(dist.resolve(ripgrep))
            if (upstreamPlatform.startsWith("macos-")) add(dist.resolve("$binary-spawn-helper"))
        }
        val resources = dist.resolve(upstreamPlatform)
        val downloads = resources.resolve("downloads.json")
        val payload = files(executable, helpers, fileTree(resources))
        val manifest = layout.buildDirectory.file("runtime/$platform/manifest.json")
        val index = tasks.register("indexRuntime-$platform") {
            inputs.files(payload).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.property("dshVersion", dshVersion)
            inputs.property("runtimeVersion", runtimeVersion)
            outputs.file(manifest)
            doLast {
                val missing = (helpers + downloads).filterNot(File::isFile)
                require(missing.isEmpty()) { "Incomplete DeepSeek Harness runtime for $platform: $missing" }
                val release = JsonSlurper().parse(downloads) as Map<*, *>
                val target = upstreamPlatform.replace("macos-", "mac-")
                require(release["version"] == dshVersion && release["target"] == target) {
                    "$downloads must describe DeepSeek Harness $dshVersion for $target"
                }
                val digests = payload.files.associateBy { it.relativeTo(dist).invariantSeparatorsPath }
                    .toSortedMap().mapValues { (_, file) ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        DigestInputStream(file.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
                        HexFormat.of().formatHex(digest.digest())
                    }
                val output = manifest.get().asFile
                output.parentFile.mkdirs()
                output.writeText(JsonOutput.toJson(mapOf(
                    "version" to runtimeVersion,
                    "executable" to executable.name,
                    "helpers" to helpers.map(File::getName),
                    "files" to digests,
                )))
            }
        }
        tasks.register<Jar>("runtimeJar-$platform") {
            dependsOn(index)
            destinationDirectory.set(base.libsDirectory)
            archiveClassifier.set(platform)
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            into("META-INF/deepseek-harness") { from(notices) }
            into("META-INF/dshjk/runtime/$platform") {
                from(manifest)
                into("files") {
                    from(executable, helpers)
                    from(resources) { into(upstreamPlatform) }
                }
            }
        }
    }
    tasks.named("assemble") { dependsOn(runtimeJars) }
    publishing {
        publications.create<MavenPublication>("maven") {
            runtimeJars.forEach { artifact(it) }
            pom {
                packaging = "jar"
                licenses { license {
                    name.set("MIT License (bundled DeepSeek Harness)")
                    url.set("https://opensource.org/license/mit")
                } }
            }
        }
    }
}
