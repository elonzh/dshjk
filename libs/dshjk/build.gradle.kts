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
publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
        pom {
            name.set("dshjk")
            description.set("DeepSeek Harness SDK for Java and Kotlin")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    distribution.set("repo")
                }
            }
        }
    }
    repositories { maven { name = "localDevelopment"; url = uri(rootProject.layout.buildDirectory.dir("repository")) } }
}
tasks.withType<Zip>().configureEach {
    from(listOf(rootProject.file("LICENSE"), rootProject.file("NOTICE"))) { into("META-INF") }
}
