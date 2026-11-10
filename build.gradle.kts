import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
allprojects { group = "com.elonzh"; version = "0.1.0-SNAPSHOT" }
subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            inputs.property("dshCli", providers.environmentVariable("DSHJK_TEST_CLI").orElse(""))
            inputs.property("testJavaVersion", providers.gradleProperty("testJavaVersion").orElse("17"))
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("testJavaVersion").orElse("17").get().toInt()))
            })
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    }
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set(provider { project.description ?: "DeepSeek Harness SDK for Java and Kotlin" })
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
