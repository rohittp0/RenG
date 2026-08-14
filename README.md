# RenG

[Public Maven repository](https://maven.rohittp.com)

## Current status

RenG is a pre-runtime Kotlin Multiplatform publication skeleton. Cycle A establishes its coordinate,
target surface, and release gates, but its first public release is still pending anonymous verification.
It exposes no runtime API and renders nothing.

## Coordinate and repository

RenG is configured to publish one common-code coordinate:

```text
com.rohittp.reng:kmp
```

After the first release passes anonymous verification, the coordinate will resolve without consumer
credentials from `https://maven.rohittp.com`. Configure that repository and JetBrains Compose, filtered
exclusively to `org.jetbrains.skiko` for Rentile's transitive platform artifacts, before the standard
repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://maven.rohittp.com")
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
            content { includeGroup("org.jetbrains.skiko") }
        }
    }
}
```

## Targets

RenG is configured to publish exactly these six Kotlin Multiplatform targets after the first release gate
succeeds:

- `android`
- `iosArm64`
- `iosSimulatorArm64`
- `macosArm64`
- `linuxX64`
- `linuxArm64`

There is deliberately no `jvm`, `iosX64`, or `macosX64` target. Apple support is Apple Silicon only.

## Dependency

Keep the version in a Gradle property rather than embedding it in the dependency declaration:

```properties
# gradle.properties
rengVersion=<version>
```

```kotlin
val rengVersion = providers.gradleProperty("rengVersion")

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rohittp.reng:kmp:${rengVersion.get()}")
        }
    }
}
```

## Build and verify

The local gates are:

```text
./gradlew :kmp:checkKotlinAbi
./gradlew :kmp:testAndroidHostTest
./gradlew :kmp:linuxX64Test
./gradlew :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
./gradlew :kmp:macosArm64Test
./gradlew :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64
./gradlew :kmp:publishAllPublicationsToLocalTestRepository
./gradlew -p consumer-smoke compileAndroidMain compileKotlinIosArm64 \
    compileKotlinIosSimulatorArm64 compileKotlinMacosArm64 compileKotlinLinuxX64 \
    compileKotlinLinuxArm64
```

Every non-documentation push to `main` releases only after its gates pass. Releases are immutable and
fail closed: an occupied coordinate, incomplete remote response, or validation failure stops publication
rather than overwriting or skipping a version. `VERSION_NAME` in the root `gradle.properties` is the sole
checked-in version input. Routine next-patch advancement requires the newest metadata-listed aggregate
POM as a completion witness. That aggregate publishes only after all six target publications complete.
An explicit upward `VERSION_NAME` is the recovery path for a partial release.

## Static documentation version convention

Release versions are not hardcoded in HTML. Every displayed RenG release uses
`data-maven-version="kmp"`, and every applicable page loads `docs/versions.js`. The browser reads
`<versioning><release>` from
`https://maven.rohittp.com/com/rohittp/reng/kmp/maven-metadata.xml`; if it cannot load the metadata,
the readable `pending` fallback remains until release metadata is available. Publishing a release requires
no documentation version update.

Architecture decisions and the evolving publication contract are in [`docs/`](docs/). Public documentation
is prepared for [https://rohittp.com/reng/](https://rohittp.com/reng/).

## License

RenG is licensed under Apache-2.0. See [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
