# RenG

[Public Maven repository](https://maven.rohittp.com)

## Current status

RenG is a pre-runtime Kotlin Multiplatform publication. Cycle A establishes its published coordinate,
target surface, and release gates. It exposes no runtime API and renders nothing.

## Coordinate and repository

RenG publishes one common-code coordinate:

```text
com.rohittp.reng:kmp
```

It resolves anonymously from `https://maven.rohittp.com`; consumer credentials are not required.
Add that repository through `dependencyResolutionManagement` before the standard repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://maven.rohittp.com")
        mavenCentral()
        google()
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

Once the KMP module is present, the local gates are:

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
checked-in version input.

## Static documentation version convention

Release versions are not hardcoded in HTML. Every displayed RenG release uses
`data-maven-version="kmp"`, and every applicable page loads `docs/versions.js`. The browser reads
`<versioning><release>` from
`https://maven.rohittp.com/com/rohittp/reng/kmp/maven-metadata.xml`; if it cannot load the metadata,
the readable `latest` fallback remains. Publishing a release requires no documentation version update.

Architecture decisions and the evolving publication contract are in [`docs/`](docs/). Public documentation
is prepared for [https://rohittp.com/reng/](https://rohittp.com/reng/).

## License

RenG is licensed under Apache-2.0. See [`LICENSE`](LICENSE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
