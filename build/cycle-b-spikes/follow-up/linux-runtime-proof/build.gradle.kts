plugins {
    kotlin("multiplatform") version "2.3.21"
}

kotlin {
    linuxX64 {
        binaries.all {
            linkerOpts("-ldl")
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
