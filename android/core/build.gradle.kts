plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.json:json:20240303")
}
application { mainClass.set("luces.CliKt") }
