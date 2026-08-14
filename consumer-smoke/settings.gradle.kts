pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val rengRepositoryUrl = providers.gradleProperty("rengRepositoryUrl")
            .orElse(uri(file("../build/local-maven")).toString())

        exclusiveContent {
            forRepository {
                maven {
                    name = "RenGUnderTest"
                    url = uri(rengRepositoryUrl.get())
                }
            }
            filter {
                includeGroup("com.rohittp.reng")
            }
        }

        exclusiveContent {
            forRepository {
                maven {
                    name = "Rentile"
                    url = uri("https://maven.rohittp.com")
                }
            }
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
    }
}

rootProject.name = "reng-consumer-smoke"
