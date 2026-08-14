import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
        klib {
            keepUnsupportedTargets = false
        }
    }

    android {
        namespace = "com.rohittp.reng"
        compileSdk = 37
        minSdk = 30
        withHostTest {}
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
            implementation(libs.rentile.kmp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mavenPublishing {
    if (System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

    pom {
        name.set("RenG KMP")
        description.set(
            "Kotlin Multiplatform 3D renderer built on Rentile basemap tiles.",
        )
        inceptionYear.set("2026")
        url.set("https://rohittp.com/reng/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("rohittp0")
                name.set("Rohit T P")
                email.set("tprohit9@gmail.com")
                organization.set("rohittp.com")
                organizationUrl.set("https://rohittp.com")
                url.set("https://rohittp.com")
            }
        }
        scm {
            url.set("https://github.com/rohittp0/RenG")
            connection.set("scm:git:git://github.com/rohittp0/RenG.git")
            developerConnection.set("scm:git:ssh://git@github.com/rohittp0/RenG.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "LocalTest"
            url = uri(rootProject.layout.buildDirectory.dir("local-maven"))
        }
    }
}
