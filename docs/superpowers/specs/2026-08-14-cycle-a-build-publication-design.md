# Cycle A build and publication design

Cycle A replaces Android Studio's placeholder application with RenG's publication skeleton. It delivers one Kotlin Multiplatform library coordinate, proves that coordinate resolves for every supported target, and makes the existing continuous-release workflow fail closed. It does not define RenG's runtime API or render anything.

## Settled inputs

The root build includes only `:kmp`; `:app` is deleted. The publication group and artifact are `com.rohittp.reng:kmp`, with exactly `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`. Android uses compile SDK 37, minimum SDK 30, and JVM bytecode target 21. The toolchain remains Kotlin 2.3.21, AGP 9.3.1, Gradle 9.5.0, and Vanniktech Maven Publish 0.36.0.

The initial checked-in version is `VERSION_NAME=0.1.0`. `VERSION_NAME` is the sole checked-in RenG version input; the release workflow may derive a later patch from public repository metadata as described below. RenG is Apache-2.0 licensed.

Rentile is an `implementation` dependency on `com.rohittp.rentile:kmp:0.1.5`. It resolves from `https://maven.rohittp.com` without `mavenLocal()`, and no Rentile type appears in RenG's public ABI. Cycle A adds no direct Wire, serialization, Skiko, Ktor, or corpus dependency and publishes no JVM, Intel macOS, or Intel iOS target.

## Build structure

Root settings centralize repositories with `FAIL_ON_PROJECT_REPOS`. Google and Maven Central remain available, the RenG build resolves `com.rohittp.rentile` exclusively from Rentile's public Maven repository, and JetBrains Compose is filtered to `org.jetbrains.skiko` for Rentile's transitive platform artifacts. The root build assigns the RenG group and version and configures the optional R2 publication repository lazily, so ordinary local and pull-request builds require no publishing credentials.

The `:kmp` module applies Kotlin Multiplatform, Android KMP Library, and Vanniktech Maven Publish. It configures the six targets, `explicitApi()`, and Kotlin KLIB ABI validation with unsupported targets omitted. Its source tree contains only minimal `commonMain` and `commonTest` code. An internal linkage anchor references Rentile without creating a consumer-facing RenG symbol, and a common test exercises that anchor so Android host, Linux x64, and macOS ARM64 test tasks do real work. The generated ABI baseline is RenG's own KLIB dump; there is no JVM ABI dump.

The module publishes POM metadata for RenG, Apache-2.0, `https://rohittp.com/reng/`, and
`https://github.com/rohittp0/RenG`; the license keeps the Apache URL in `url` and uses Maven's
conventional `distribution` value `repo`. In-memory signing is enabled only when signing properties are
present. The `LocalTest` repository is rooted at the repository's `build/local-maven`, while the optional
`R2` repository consumes the existing endpoint, bucket, and credential inputs. The aggregate
KotlinMultiplatform R2 publication task depends on all six target R2 publication tasks, so aggregate
artifacts and aggregate metadata cannot publish before the target publications complete. This is defense
in depth against partial state; aggregate POM and metadata availability are not completion proof.
Configuration-cache exclusions remain limited to remote Maven publishing tasks.

## Standalone consumer

`consumer-smoke` is a separate Gradle build and is not included by root settings. It pins its own Kotlin and Android plugin versions and declares the same six targets. Its repository under test defaults to `../build/local-maven`; `-PrengRepositoryUrl` replaces that location for public verification. Exclusive repository content ensures only `com.rohittp.reng` resolves there, preventing Maven Central or another repository from masking a missing RenG artifact.

The smoke build reads the checked-in version from the parent `gradle.properties` when present and accepts `-PrengVersion` as the release-validation override. The public verification copies the smoke build and root wrapper into an isolated directory where the parent properties file is absent, so the explicit override is mandatory there. Common smoke source need not import a RenG type: compiling a declared common dependency across all six targets forces Gradle to resolve the aggregate module metadata and each target publication without inventing a Cycle A public API.

## Release version resolution

Version selection moves from inline workflow shell into a Python standard-library tool with a pure selection core and a small HTTP adapter. A shared completion-record module defines strict creation, serialization, parsing, and the stable version-scoped key. Unit tests cover first publication, explicit upward recovery, routine patch advancement after a valid record, absent, malformed, mismatched, redirected, and uncertain records, one candidate probe, an occupied candidate, malformed metadata, transport and server failures, and snapshot rejection.

`VERSION_NAME` must match stable `MAJOR.MINOR.PATCH`; a `-SNAPSHOT` declaration always stops public publication before metadata is consulted. Aggregate metadata is interpreted as follows:

- HTTP 404 means no public version line exists, so the declaration governs.
- HTTP 200 must contain at least one parseable stable version; malformed or empty stable metadata stops publication.
- Transport failure, redirect, or any other HTTP status stops publication.

A stable declaration strictly greater than every public metadata version governs as an intentional upward release. This is the deliberate recovery path after partial publication and does not require the previous version to be complete. Otherwise, before selecting the next patch, the resolver GETs `com/rohittp/reng/kmp/<newest>/reng-release-completion-v1.json`. HTTP 200 is accepted only when strict schema version 1 JSON has exactly `schemaVersion`, matching `mavenVersion`, lowercase 40-character `sourceCommitSha`, and lowercase SHA-256 `manifestSha256`. Missing, malformed, mismatched, redirected, uncertain, or unsuccessful reads stop with an explicit upward-recovery instruction.

After that check, the resolver selects one candidate and probes that candidate's aggregate POM exactly once. Candidate HTTP 404 makes it available; HTTP 200 means occupied; redirects, transport failures, and other statuses stop resolution. The resolver never skips an occupied candidate. Recovery never overwrites, deletes, reuses, or automatically skips a partial version.

Manual dispatch is accepted only from `main`. Every non-documentation push to `main` continues to cut a release, and the existing concurrency group continues to serialize releases without cancelling one mid-upload.

## Signed local gate and authoritative collision check

The publish job first builds, tests, signs, validates POMs, and publishes all seven publications into `build/local-maven`. This local gate includes ABI validation, Android host tests, Linux x64 tests, macOS ARM64 tests, both iOS compilations, Linux ARM64 compilation, Android AAR creation, all seven POM checks, signed-POM verification, and six-target isolated consumer resolution.

After local publication, the workflow derives an immutable-key manifest from every path under
`build/local-maven/com/rohittp/reng` matching `*/<version>/*`. It checks each exact relative key against the authoritative R2 bucket before upload. This avoids hardcoding artifact IDs and covers the POMs, modules, archives, checksums, and signatures Gradle actually produced. If any key already exists, publication stops; no object is overwritten or deleted.

A partial release is deliberately not repaired automatically. Its surviving objects remain immutable evidence, and the next attempt fails the same authoritative preflight. Recovery is an explicit upward `VERSION_NAME` change, leaving a harmless version gap. The workflow never silently selects another version after an authoritative collision.

## Public verification

After upload, the workflow fetches every versioned artifact in the local manifest from its corresponding anonymous public URL. It then fetches aggregate `maven-metadata.xml` separately and spends the configured retry budget on semantic verification: stale HTTP 200 metadata that lacks the selected version and malformed HTTP 200 metadata are retried until a valid document lists the release, then fail closed after exhaustion. Next, it copies the standalone smoke build and wrapper into a clean directory and compiles all six targets with no credentials, a fresh Gradle home, `--refresh-dependencies`, the public repository override, and the resolved release version.

Only after those anonymous gates succeed does the workflow create `com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`. The record contains schema version 1, released Maven version, exact source commit SHA, and SHA-256 of the exact serialized local manifest. Of the three completion-record stages, only the dedicated conditional-write step receives R2 credentials; it uses an authoritative put with `If-None-Match: *`, and an existing object stops rather than overwrites. Local record derivation and the final retry-budgeted anonymous retrieval are credential-free. The final command requires an exact logical match to the locally derived record. POM and metadata availability remain necessary but insufficient.

All Gradle invocations in CI and publication pass `--no-configuration-cache`. Hosted-runner assumptions such as the installed AWS CLI and the architecture behind `macos-latest` change only if a real gate shows they are false; Cycle A does not speculate around runner behavior.

## Documentation and legal files

The dependency-free static site follows Rentile's structure but documents only the pending installation, the configured six-target surface, version and release behavior, and RenG's pre-runtime status. Installation and target availability are explicitly conditional on anonymous first-release completion-record verification. The site uses `https://rohittp.com/reng/` as its canonical base. Maven versions are loaded dynamically from public metadata; the site and README contain no checked-in RenG version literal. After the first public completion record verifies, an authorized documentation-only follow-up removes pending claims from `CLAUDE.md`, README, `docs/index.html`, `docs/kmp.html`, `docs/llms.txt`, and the `docs/versions.js` fallback as applicable, and updates `HANDOFF.md` plus `docs/decomposition.md`. ADR 0013 and this design spec and implementation plan remain historical decision records unless the release exposes a contract error. API documentation waits for Cycle B.

`LICENSE`, POM metadata, README, and site consistently identify Apache-2.0. `THIRD_PARTY_NOTICES.md` records the dependencies Cycle A actually distributes and does not copy Rentile's Wire, schema, or corpus entries without a corresponding RenG dependency.

## Tests and completion

Repository policy checks reject `mavenLocal()`, RenG snapshot dependencies, leaked Rentile types in the ABI, extra published targets, independently hardcoded RenG versions, and completion-record workflow regressions in ordering, conditional creation, credential scope, or anonymous verification. Completion-record, resolver, verifier, and policy unit tests run on the Ubuntu CI leg and before public version resolution. The existing Ubuntu and macOS Gradle gates then prove ABI, host tests, target compilation, local publication, and isolated resolution.

A branch is merge-ready only when both CI legs pass on its exact commit. Cycle A itself is complete only after the exact merged `main` commit passes both CI jobs, publishes the first public release, and anonymously verifies the exact completion record after signed local validation, authoritative collision checks, public artifact and metadata verification, and clean credential-free six-target resolution. Merging and pushing remain separate outward-facing actions requiring explicit approval.

## Deferred work

Cycle B owns every public renderer and domain type, the pure transform core, resource adapter interfaces, and typed runtime errors. Its preparation starts only after Cycle A's exact merged CI and first public completion record succeed. Preparation begins by reading `CONTEXT.md`, the governing ADRs, this decomposition, and the current handoff, then running the required feasibility spikes. Their findings feed `/grill-with-docs` before any implementation plan is written. Later cycles own acquisition, decoding, parsing, caching, GL, basemap drawing, drawn things, globe projection, mobile bring-up, the macOS harness, and golden-image corpus. Cycle A introduces no behavior from those cycles.
