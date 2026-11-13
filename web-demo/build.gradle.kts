import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

abstract class UnpackSkikoWasmRuntime : DefaultTask() {
    @get:InputFiles
    abstract val runtimeJar: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @TaskAction
    fun unpack() {
        fileSystemOperations.sync {
            from(archiveOperations.zipTree(runtimeJar.singleFile)) {
                include("skiko.mjs", "skiko.wasm")
            }
            into(outputDir)
        }
    }
}

val skikoWasmRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    skikoWasmRuntime("org.jetbrains.skiko:skiko-js-wasm-runtime:0.150.2")
}

val unpackSkikoWasmRuntime = tasks.register<UnpackSkikoWasmRuntime>("unpackSkikoWasmRuntime") {
    runtimeJar.from(skikoWasmRuntime)
    outputDir.set(layout.buildDirectory.dir("generated/skikoWasmRuntime"))
}

rootProject.plugins.withType<WasmNodeJsRootPlugin>().configureEach {
    rootProject.extensions.configure<WasmNodeJsEnvSpec> {
        download = false
    }
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            binaries.executable()
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":kmp"))
            implementation("com.rohittp.rentile:kmp:0.19.0-wasm2")
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("wasmJsMain").resources.srcDir(unpackSkikoWasmRuntime)
    }
}

// The project-level Node spec is what webpack runs with; use the host Node, as :kmp does.
extensions.configure<WasmNodeJsEnvSpec> {
    download = false
}
