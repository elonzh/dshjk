plugins { application; alias(libs.plugins.kotlin.jvm) }
val bootVersion = providers.gradleProperty("springBootVersion").orElse(libs.versions.spring.boot)
dependencies {
    implementation(project(":dshjk-spring-boot-starter"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${bootVersion.get()}"))
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.ai.mcp.server.webmvc)
    implementation(libs.kotlin.reflect)
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.ai.openai)
    testRuntimeOnly(libs.junit.launcher)
}
application { mainClass.set("example.spring.HarnessApplicationKt") }
kotlin { compilerOptions.javaParameters.set(true) }
