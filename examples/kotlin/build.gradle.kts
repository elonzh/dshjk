plugins { application; alias(libs.plugins.kotlin.jvm) }
dependencies { implementation(project(":dshjk")) }
application { mainClass.set("example.KotlinExampleKt") }
