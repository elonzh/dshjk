pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "dshjk"
include(":dshjk", ":examples:java", ":examples:kotlin")
project(":dshjk").projectDir = file("libs/dshjk")
