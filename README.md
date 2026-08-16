# RenG

[Public Maven repository](https://maven.rohittp.com)

## Current status

RenG is a pre-runtime Kotlin Multiplatform publication skeleton. Cycle A established its public coordinate,
six-target surface, and immutable release gates; its first public completion record has verified anonymously
from the exact CI-passing source commit. It exposes no runtime API and renders nothing.

## Coordinate and repository

RenG is configured to publish one common-code coordinate:

```text
com.rohittp.reng:kmp
```

The coordinate resolves without consumer credentials from `https://maven.rohittp.com`. Configure that
repository and JetBrains Compose, filtered exclusively to `org.jetbrains.skiko` for Rentile's transitive
platform artifacts, before the standard repositories:

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

RenG publishes exactly these six Kotlin Multiplatform targets:

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
checked-in version input. Routine next-patch advancement requires a strict completion record for the newest
metadata-listed release at
`com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`; neither aggregate POM nor metadata
availability proves completion. The workflow conditionally creates that record only after anonymous
artifact/metadata verification and credential-free six-target resolution, then verifies it anonymously.
An explicit upward `VERSION_NAME` bypasses the prior record and is the recovery path for a partial release.

## Static documentation version convention

Release versions are not hardcoded in HTML. Every displayed RenG release uses
`data-maven-version="kmp"`, and every applicable page loads `docs/versions.js`. The browser reads
`<versioning><release>` from
`https://maven.rohittp.com/com/rohittp/reng/kmp/maven-metadata.xml`. When metadata is temporarily
unavailable, served pages retain a readable `available` fallback rather than embedding a semantic version.

The first public completion record has verified anonymously. ADR 0013 and the Cycle A design spec and
implementation plan remain historical decision records. Version display remains metadata-driven, so RenG
release versions are not hardcoded in this README or served documentation.

Architecture decisions and the evolving publication contract are in [`docs/`](docs/). Public documentation
is prepared for [https://rohittp.com/reng/](https://rohittp.com/reng/).

## License

RenG is licensed under Apache-2.0. See [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
