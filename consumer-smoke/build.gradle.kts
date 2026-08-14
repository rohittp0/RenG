import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.3.21"
    id("com.android.kotlin.multiplatform.library") version "9.3.1"
}

val declaredLibraryVersion = providers
    .fileContents(layout.projectDirectory.file("../gradle.properties"))
    .asText
    .map { text ->
        text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("VERSION_NAME=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
    }

val rengVersion: String = providers.gradleProperty("rengVersion")
    .orElse(declaredLibraryVersion)
    .orNull
    ?.takeIf(String::isNotEmpty)
    ?: error(
        "Cannot determine the RenG version to consume: pass " +
            "-PrengVersion=<version> or declare VERSION_NAME in the parent gradle.properties.",
    )

kotlin {
    android {
        namespace = "com.rohittp.reng.smoke"
        compileSdk = 37
        minSdk = 30
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.rohittp.reng:kmp:$rengVersion")
        }
    }
}
