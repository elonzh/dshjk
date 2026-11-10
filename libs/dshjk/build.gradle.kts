plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
description = "DeepSeek Harness SDK for Java and Kotlin"
