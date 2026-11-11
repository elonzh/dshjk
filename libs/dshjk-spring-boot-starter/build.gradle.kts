plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.kapt)
}
description = "Spring Boot 4 integration for DeepSeek Harness"
if (providers.gradleProperty("releaseVersion").isPresent) {
    apply(plugin = "com.vanniktech.maven.publish.base")
}
java { withJavadocJar() }
tasks.named<Jar>("javadocJar") {
    from(rootProject.files("docs/spring.md", "docs/spring.zh.md"))
}
val bootVersion = providers.gradleProperty("springBootVersion").orElse(libs.versions.spring.boot)
dependencies {
    api(project(":dshjk"))
    api(platform("org.springframework.boot:spring-boot-dependencies:${bootVersion.get()}"))
    api(libs.spring.boot.starter)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.kotlin.reflect)
    compileOnly(libs.spring.ai.model)
    compileOnly(libs.spring.ai.openai)
    compileOnly(libs.spring.ai.autoconfigure.openai)
    compileOnly(libs.spring.ai.anthropic)
    testImplementation(libs.spring.ai.autoconfigure.openai)
    testImplementation(libs.spring.ai.openai)
    testImplementation(libs.spring.ai.anthropic)
    kapt("org.springframework.boot:spring-boot-configuration-processor:${bootVersion.get()}")
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.junit.launcher)
}
sourceSets.test { resources.srcDir(rootProject.file("libs/dshjk/src/test/resources")) }
kotlin { compilerOptions.javaParameters.set(true) }

publishing {
    publications.create<MavenPublication>("maven") { from(components["java"]) }
}
