plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
description = "DeepSeek Harness SDK for Java and Kotlin"
if (providers.gradleProperty("releaseVersion").isPresent) {
    apply(plugin = "com.vanniktech.maven.publish.base")
}
java { withJavadocJar() }
tasks.named<Jar>("javadocJar") {
    from(rootProject.files("docs/sdk.md", "docs/sdk.zh.md"))
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.coroutines.jdk9)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}
