pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // Kotlin/Wasm's production executable runs Binaryen. KGP adds the same Ivy repository at the
    // project level, so prefer this settings-owned declaration and ignore that duplicate.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        exclusiveContent {
            forRepositories(
                maven {
                    name = "Rentile"
                    url = uri("https://maven.rohittp.com")
                },
                mavenLocal(),
            )
            filter {
                includeGroup("com.rohittp.rentile")
            }
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                includeGroup("org.jetbrains.skiko")
            }
        }
        ivy {
            name = "Binaryen"
            url = uri("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.github.webassembly", "binaryen")
            }
        }
    }
}

rootProject.name = "RenG"
include(":kmp")
include(":web-demo")

