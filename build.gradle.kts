import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.9"
}

group = "dev.etran.mapupdater"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    // Compile against the OLDEST supported API (1.13.2). Anything newer is reached
    // reflectively (see Compat.kt), so one jar runs on 1.13 -> 26.x.
    compileOnly("org.spigotmc:spigot-api:1.13.2-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("com.google.guava:guava:21.0")
    compileOnly("org.yaml:snakeyaml:1.21")

    implementation(kotlin("stdlib"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8) // 1.13 servers can run on Java 8
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Relocate so we never clash with another plugin that also bundles Kotlin.
    relocate("kotlin", "dev.mapupdater.libs.kotlin")
    relocate("org.jetbrains.annotations", "dev.mapupdater.libs.jetbrains")
    relocate("org.intellij.lang.annotations", "dev.mapupdater.libs.intellij")
    minimize()
}

tasks.build { dependsOn(tasks.shadowJar) }
tasks.jar { enabled = false }
