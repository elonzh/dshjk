import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish.base) apply false
}
val releaseVersion = providers.gradleProperty("releaseVersion").orNull
require(releaseVersion == null || releaseVersion.matches(Regex("""[0-9]+\.[0-9]+\.[0-9]+"""))) {
    "releaseVersion must be a stable version such as 0.1.0"
}
allprojects { group = "com.elonzh"; version = releaseVersion ?: "0.1.0-SNAPSHOT" }
val bundledTestRuntime = providers.gradleProperty("dshjkTestRuntimeJar").map { rootProject.file(it) }
val npmTestRuntime = providers.environmentVariable("DSHJK_TEST_CLI").orElse("")
val npmRuntimeLock = layout.projectDirectory.file("libs/dshjk-runtime/package-lock.json")
subprojects {
    val testsNativeRuntime = path in setOf(":dshjk", ":examples:spring-boot")
    if (bundledTestRuntime.isPresent && testsNativeRuntime) {
        val runtimeJar = bundledTestRuntime.get()
        require(runtimeJar.isFile) { "Bundled test runtime JAR does not exist: $runtimeJar" }
        pluginManager.withPlugin("java") {
            dependencies.add("testRuntimeOnly", files(runtimeJar))
        }
        tasks.withType<Test>().configureEach {
            environment("DSHJK_TEST_CLI", "")
            systemProperty("dshjk.test.bundledRuntime", true)
            systemProperty("dshjk.runtime.cacheDir", rootProject.layout.buildDirectory.dir("test-dsh-runtime").get().asFile)
        }
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            withSourcesJar()
        }
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        inputs.property("dshCli", npmTestRuntime)
        if (testsNativeRuntime && !bundledTestRuntime.isPresent && npmTestRuntime.get().isNotBlank()) {
            inputs.file(npmRuntimeLock).withPropertyName("dshRuntimeLock").withPathSensitivity(PathSensitivity.RELATIVE)
        }
        val testJavaVersion = providers.gradleProperty("testJavaVersion").orElse("17")
        inputs.property("testJavaVersion", testJavaVersion)
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(testJavaVersion.map { JavaLanguageVersion.of(it.toInt()) })
        })
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    }
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish.base") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()
        }
        extensions.configure<SigningExtension> {
            setRequired { gradle.taskGraph.allTasks.any { it.project == project && "MavenCentral" in it.name } }
        }
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(provider { project.description ?: "DeepSeek Harness SDK for Java and Kotlin" })
                    val repositoryUrl = "https://github.com/elonzh/dshjk"
                    url.set(repositoryUrl)
                    scm {
                        url.set(repositoryUrl)
                        connection.set("scm:git:$repositoryUrl.git")
                        developerConnection.set("scm:git:ssh://git@github.com/elonzh/dshjk.git")
                    }
                    developers { developer {
                        id.set("elonzh")
                        name.set("elonzh")
                        url.set("https://github.com/elonzh")
                    } }
                    licenses { license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        distribution.set("repo")
                    } }
                }
            }
            repositories { maven {
                name = "localDevelopment"
                url = uri(rootProject.layout.buildDirectory.dir("repository"))
            } }
        }
        tasks.withType<Zip>().configureEach {
            from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
        }
    }
}
