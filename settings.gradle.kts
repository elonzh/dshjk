pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "dshjk"
include(":dshjk", ":dshjk-runtime", ":dshjk-spring-boot-starter", ":examples:java", ":examples:kotlin", ":examples:spring-boot")
project(":dshjk").projectDir = file("libs/dshjk")
project(":dshjk-runtime").projectDir = file("libs/dshjk-runtime")
project(":dshjk-spring-boot-starter").projectDir = file("libs/dshjk-spring-boot-starter")
