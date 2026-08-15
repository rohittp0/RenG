# Cycle A Build and Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the placeholder Android application with a six-target KMP library, prove its aggregate Maven coordinate resolves in isolation, and make the first public release fail closed from version selection through anonymous verification.

**Architecture:** One `:kmp` module contains only an internal Rentile linkage proof and publishes seven Maven publications: one aggregate plus six targets. A standalone `consumer-smoke` build proves target resolution, while Python-standard-library tools own deterministic version selection, repository policy, publication manifests, R2 collision checks, and public verification. GitHub workflows orchestrate those tested pieces without defining any Cycle B runtime API.

**Tech Stack:** Kotlin 2.3.21, AGP 9.3.1, Gradle 9.5.0, Vanniktech Maven Publish 0.36.0, Rentile 0.1.5, Python 3.12 standard library, GitHub Actions, AWS CLI/R2, static HTML/CSS/JavaScript.

**Spec:** `docs/superpowers/specs/2026-08-14-cycle-a-build-publication-design.md`

## Global Constraints

- Publish exactly `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`; never add `jvm`, `macosX64`, or `iosX64`.
- Publish the aggregate coordinate `com.rohittp.reng:kmp` with target publications under the same group.
- Use compile SDK 37, Android minimum SDK 30, and JVM bytecode target 21.
- Keep Kotlin 2.3.21, AGP 9.3.1, Gradle 9.5.0, and Vanniktech Maven Publish 0.36.0.
- Set the initial checked-in input to `VERSION_NAME=0.1.0`; it is the sole checked-in RenG version input.
- Depend on `com.rohittp.rentile:kmp:0.1.5` through `implementation` only, from `https://maven.rohittp.com`, with no `mavenLocal()`.
- Add no public RenG declaration and leak no Rentile type into the ABI.
- Add no direct Wire, serialization, Skiko, Ktor, corpus, or JVM dependency.
- Enable `explicitApi()` and KLIB ABI validation with unsupported targets omitted; generate no JVM ABI dump.
- Use Apache-2.0 consistently in `LICENSE`, POMs, README, and the static site.
- Run every workflow Gradle command with `--no-configuration-cache`.
- Reject snapshots before network access. For routine next-patch selection, require HTTP 200 plus a strict matching version-scoped completion record for the newest metadata-listed release; explicit upward recovery bypasses the prior record. Select one candidate, probe its aggregate POM exactly once, and never skip an occupied candidate.
- Define schema version 1 at `com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json` with Maven version, source commit SHA, and local-manifest SHA-256. Create it conditionally only after all anonymous public gates, then verify it anonymously.
- Keep aggregate-after-target R2 ordering as defense in depth, not completion proof. Never overwrite or delete R2 objects. Recover from partial publication only through an explicit upward `VERSION_NAME` change.
- Do not push, merge, change repository settings, or trigger the first public release without separate explicit approval.

## File Structure

### Delete

- `app/` — remove the complete Android Studio placeholder module, including generated source, tests, resources, manifest, and build script.

### Create

- `kmp/build.gradle.kts` — six targets, ABI, Rentile dependency, POM metadata, and local publication.
- `kmp/.gitignore` — module build outputs.
- `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/RentileLinkage.kt` — internal dependency linkage proof.
- `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/RentileLinkageTest.kt` — common linkage test.
- `kmp/api/kmp.klib.api` — generated empty public KLIB ABI baseline.
- `consumer-smoke/settings.gradle.kts` — exclusive repository-under-test resolution.
- `consumer-smoke/build.gradle.kts` — standalone six-target aggregate consumer.
- `consumer-smoke/src/commonMain/kotlin/com/rohittp/reng/smoke/ConsumerProof.kt` — API-neutral compilation source.
- `tools/release_completion.py` — shared completion-record path, schema, creation, manifest hashing, serialization, and strict parsing.
- `tools/resolve_release_version.py` — fail-closed version resolver and CLI.
- `tools/verify_publication.py` — local publication manifest, authoritative R2 preflight, and anonymous public verification.
- `tools/check_repository_policy.py` — Cycle A structural and dependency policy checks.
- `tools/tests/test_release_completion.py` — completion-record schema, path, creation, parsing, and manifest-hash tests.
- `tools/tests/test_resolve_release_version.py` — resolver tests.
- `tools/tests/test_verify_publication.py` — publication verifier tests.
- `tools/tests/test_check_repository_policy.py` — policy checker tests.
- `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md` — public project and legal material.
- `docs/.nojekyll`, `docs/index.html`, `docs/kmp.html`, `docs/style.css`, `docs/versions.js`, `docs/robots.txt`, `docs/sitemap.xml`, `docs/llms.txt` — dependency-free static site.

### Modify

- `.gitignore` — KMP, Python, and native build outputs.
- `settings.gradle.kts` — include only `:kmp` and centralize filtered repositories.
- `build.gradle.kts` — plugin aliases, group/version, and optional R2 repository.
- `gradle.properties` — add `VERSION_NAME=0.1.0` while retaining configuration cache.
- `gradle/libs.versions.toml` — reduce to the Cycle A toolchain and Rentile dependency.
- `.github/workflows/ci.yml` — tool, policy, ABI, target, local publication, and smoke gates.
- `.github/workflows/publish.yml` — tested resolution, self-contained Linux gate, manifest collision preflight, upload, anonymous artifact/metadata and clean public smoke gates, conditional completion-record create, and anonymous record verification.
- `CLAUDE.md`, `HANDOFF.md` — replace skeleton-state guidance with the implemented Cycle A structure and Cycle B handoff.

## Dependency Graph and Parallel Execution

At implementation time, obey the repository instruction to use parallel subagents for independent work. Use isolated worktrees for concurrently mutating agents, then review and integrate each commit in dependency order.

```text
Wave 1 (parallel)
  Task 1 release resolver
  Task 2 KMP skeleton
  Task 7 static docs/legal

Wave 2
  Task 3 ABI/publication       <- Task 2

Wave 3 (parallel)
  Task 4 consumer smoke       <- Task 3
  Task 5 publication verifier <- Task 3 contract

Wave 4
  Task 6 repository policy   <- Tasks 2, 3, 4, 7

Wave 5 (parallel)
  Task 8 ordinary CI         <- Tasks 1-7
  Task 9 publish workflow    <- Tasks 1, 3, 4, 5, 6

Wave 6
  Task 10 final docs/gates   <- Tasks 1-9
```

---

### Task 1: Implement Durable Completion Records and Fail-Closed Release Resolution

**Files:**
- Create: `tools/release_completion.py`
- Create: `tools/resolve_release_version.py`
- Create: `tools/tests/test_release_completion.py`
- Create: `tools/tests/test_resolve_release_version.py`

**Interfaces:**
- Consumes: root `gradle.properties`, anonymous Maven repository URL, released Maven version, source commit SHA, and exact serialized local-manifest bytes.
- Produces:
  - `CompletionRecord(schema_version: int, maven_version: str, source_commit_sha: str, manifest_sha256: str)` with canonical creation, strict parsing, and serialization.
  - `completion_record_key(maven_version: str) -> str`.
  - `Version(major: int, minor: int, patch: int)`
  - `HttpResponse(status: int, body: bytes)`
  - `ResolutionError`
  - `parse_declared_version(value: str) -> Version`
  - `parse_metadata_versions(document: bytes) -> tuple[Version, ...]`
  - `read_declared_version(properties_file: Path) -> str`
  - `request_http(method: str, url: str) -> HttpResponse`
  - `select_candidate(declared: Version, published: Sequence[Version]) -> Version`
  - `resolve_release_version(declared: Version, repository_url: str, request: Callable | None = None) -> Version`
  - CLI success: stdout contains only `MAJOR.MINOR.PATCH\n`; failure: exit 1, empty stdout, `error:` diagnostic on stderr.

- [ ] **Step 1: Write the completion-record and resolver tests before the modules exist**

Create `tools/tests/test_release_completion.py` first. Require canonical JSON with exactly integer `schemaVersion` equal to 1, canonical stable `mavenVersion`, lowercase 40-character `sourceCommitSha`, and lowercase 64-character `manifestSha256`. Require SHA-256 over the exact supplied manifest bytes, reject duplicate or extra fields and invalid types/values, and require the exact key `com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`.

Create `tools/tests/test_resolve_release_version.py` with these concrete cases:

```python
from __future__ import annotations

import io
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, call, patch
from urllib.error import URLError

from tools.release_completion import CompletionRecord, completion_record_key
from tools.resolve_release_version import (
    HttpResponse,
    ResolutionError,
    Version,
    main,
    parse_declared_version,
    parse_metadata_versions,
    request_http,
    resolve_release_version,
    select_candidate,
)

REPOSITORY_URL = "https://repo.example"
BASE_URL = f"{REPOSITORY_URL}/com/rohittp/reng/kmp"
SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"


def completion_document(version: str) -> bytes:
    return CompletionRecord.create(
        version,
        SOURCE_COMMIT,
        f"manifest for {version}\n".encode(),
    ).serialize()


class ResolveReleaseVersionTests(unittest.TestCase):
    def test_first_release_uses_declared_version(self) -> None:
        requester = Mock(side_effect=[HttpResponse(404, b""), HttpResponse(404, b"")])
        resolved = resolve_release_version(
            parse_declared_version("0.1.0"), REPOSITORY_URL, request=requester
        )
        self.assertEqual(Version(0, 1, 0), resolved)
        self.assertEqual(
            [
                call("GET", f"{BASE_URL}/maven-metadata.xml"),
                call("HEAD", f"{BASE_URL}/0.1.0/kmp-0.1.0.pom"),
            ],
            requester.call_args_list,
        )

    def test_explicit_upward_version_governs(self) -> None:
        self.assertEqual(
            Version(2, 0, 0),
            select_candidate(
                Version(2, 0, 0),
                (Version(1, 9, 99), Version(1, 10, 0)),
            ),
        )

    def test_routine_release_selects_exactly_next_patch(self) -> None:
        self.assertEqual(
            Version(1, 10, 10),
            select_candidate(
                Version(1, 0, 0),
                (Version(1, 9, 99), Version(1, 10, 9)),
            ),
        )

    def test_occupied_candidate_stops_without_skipping(self) -> None:
        metadata = b"""
        <metadata><versioning><versions><version>1.2.3</version></versions>
        </versioning></metadata>
        """
        requester = Mock(
            side_effect=[
                HttpResponse(200, metadata),
                HttpResponse(200, completion_document("1.2.3")),
                HttpResponse(200, b"candidate aggregate POM"),
            ]
        )
        with self.assertRaisesRegex(
            ResolutionError, "Selected release candidate is already occupied: 1.2.4"
        ):
            resolve_release_version(Version(1, 2, 3), REPOSITORY_URL, request=requester)
        self.assertEqual(3, requester.call_count)
        self.assertEqual(
            1,
            requester.call_args_list.count(
                call("HEAD", f"{BASE_URL}/1.2.4/kmp-1.2.4.pom")
            ),
        )
        self.assertNotIn("1.2.5", repr(requester.call_args_list))

    def test_metadata_parser_orders_stable_versions_and_ignores_snapshots(self) -> None:
        versions = parse_metadata_versions(
            b"""
            <metadata xmlns="urn:test"><versioning><versions>
              <version>1.10.0</version><version>1.2.0-SNAPSHOT</version>
              <version>1.9.9</version>
            </versions></versioning></metadata>
            """
        )
        self.assertEqual((Version(1, 9, 9), Version(1, 10, 0)), versions)

    def test_malformed_or_stable_empty_metadata_fails(self) -> None:
        for body, message in (
            (b"<metadata>", "malformed XML"),
            (b"<metadata><versioning><versions><version>1.0.0-SNAPSHOT</version>"
             b"</versions></versioning></metadata>", "no parseable stable versions"),
        ):
            with self.subTest(body=body):
                requester = Mock(return_value=HttpResponse(200, body))
                with self.assertRaisesRegex(ResolutionError, message):
                    resolve_release_version(Version(0, 1, 0), REPOSITORY_URL, request=requester)

    def test_unexpected_metadata_and_candidate_statuses_fail(self) -> None:
        with self.assertRaisesRegex(ResolutionError, "status 503 fetching"):
            resolve_release_version(
                Version(0, 1, 0),
                REPOSITORY_URL,
                request=Mock(return_value=HttpResponse(503, b"")),
            )
        requester = Mock(side_effect=[HttpResponse(404, b""), HttpResponse(403, b"")])
        with self.assertRaisesRegex(ResolutionError, "status 403 probing"):
            resolve_release_version(Version(0, 1, 0), REPOSITORY_URL, request=requester)

    def test_transport_failure_is_not_absence(self) -> None:
        with patch("tools.resolve_release_version.urlopen", side_effect=URLError("offline")):
            with self.assertRaisesRegex(ResolutionError, "Transport failure during GET"):
                request_http("GET", f"{BASE_URL}/maven-metadata.xml")

    def test_snapshot_is_rejected_before_http(self) -> None:
        with TemporaryDirectory() as directory:
            properties = Path(directory) / "gradle.properties"
            properties.write_text("VERSION_NAME=1.2.3-SNAPSHOT\n", encoding="utf-8")
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch("tools.resolve_release_version.request_http") as requester:
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    result = main([
                        "--properties-file", str(properties),
                        "--repository-url", REPOSITORY_URL,
                    ])
        self.assertEqual(1, result)
        self.assertEqual("", stdout.getvalue())
        self.assertIn("Snapshot versions cannot be published", stderr.getvalue())
        requester.assert_not_called()

    def test_cli_prints_only_resolved_version(self) -> None:
        with TemporaryDirectory() as directory:
            properties = Path(directory) / "gradle.properties"
            properties.write_text("VERSION_NAME=0.1.0\n", encoding="utf-8")
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch(
                "tools.resolve_release_version.request_http",
                side_effect=[HttpResponse(404, b""), HttpResponse(404, b"")],
            ):
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    result = main([
                        "--properties-file", str(properties),
                        "--repository-url", REPOSITORY_URL,
                    ])
        self.assertEqual(0, result)
        self.assertEqual("0.1.0\n", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
```

The final resolver test set must prove routine advancement directly: metadata GET, newest-version completion-record GET returning a strict matching record, and exactly one candidate aggregate-POM HEAD. A missing, malformed, mismatched, redirected, transport-failed, or otherwise unsuccessful completion-record read stops before any candidate probe and instructs explicit upward `VERSION_NAME` recovery. A declaration above every metadata-listed version performs no prior-record request and probes only the explicit candidate. The successful routine request sequence is:

```text
GET  com/rohittp/reng/kmp/maven-metadata.xml
GET  com/rohittp/reng/kmp/<newest>/reng-release-completion-v1.json
HEAD com/rohittp/reng/kmp/<candidate>/kmp-<candidate>.pom
```

- [ ] **Step 2: Run the tests and verify the import failures**

Run:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.tests.test_release_completion \
  tools.tests.test_resolve_release_version -v
```

Expected: import failures for the two production modules.

- [ ] **Step 3: Implement the shared record and resolver core/CLI**

Create `tools/release_completion.py` with only Python standard-library dependencies. Serialize canonical JSON with sorted keys, compact separators, ASCII output, and one trailing newline. Hash the exact manifest byte sequence. Parse JSON with duplicate-key detection and reject any shape other than schema version 1's exact four fields. Validate creation inputs with the same strict rules as parsing.

Create `tools/resolve_release_version.py` using only the Python standard library. Use these exact version-selection rules:

```python
@dataclass(frozen=True, order=True)
class Version:
    major: int
    minor: int
    patch: int

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


def select_candidate(declared: Version, published: Sequence[Version]) -> Version:
    if not published:
        return declared
    latest = max(published)
    if declared > latest:
        return declared
    return Version(latest.major, latest.minor, latest.patch + 1)
```

`resolve_release_version` must GET `<repository>/com/rohittp/reng/kmp/maven-metadata.xml` and interpret only 200 and 404. When metadata returns 200 and the declaration is not newer than its highest stable version, GET `completion_record_key(<highest-version>)` and require HTTP 200 plus a strict record whose Maven version matches that highest version before automatic next-patch selection. A 404, malformed or mismatched record, redirect, transport failure, or any other status stops automatic advancement with an explicit upward-recovery instruction. A declaration newer than the public line is deliberate upward recovery and bypasses the prior completion record. After selection, HEAD exactly one candidate aggregate POM. Candidate HEAD 404 returns the candidate; HEAD 200 raises `Selected release candidate is already occupied: <version>`; every other outcome stops. Never skip, overwrite, delete, or reuse a partial version. Aggregate POM and metadata availability are not completion proof. `parse_declared_version` must detect `-SNAPSHOT` before applying the canonical stable regex. `parse_metadata_versions` must compare XML local names so default namespaces work and must reject HTTP-200 metadata with no stable versions.

The CLI arguments are exactly:

```text
--properties-file PATH
--repository-url URL
```

- [ ] **Step 4: Run the completion-record and resolver tests**

Run the Step 2 command again.

Expected: all tests pass and output ends with `OK`.

- [ ] **Step 5: Commit the completion record and resolver**

```bash
git add \
  tools/release_completion.py \
  tools/resolve_release_version.py \
  tools/tests/test_release_completion.py \
  tools/tests/test_resolve_release_version.py
git commit -m $'feat: add fail-closed release completion and resolution\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 2: Replace the Placeholder App with the Six-Target KMP Skeleton

**Files:**
- Delete: `app/`
- Modify: `.gitignore`
- Modify: `settings.gradle.kts`
- Modify: `gradle.properties`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Create: `kmp/.gitignore`
- Create: `kmp/build.gradle.kts`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/RentileLinkage.kt`
- Create: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/RentileLinkageTest.kt`

**Interfaces:**
- Consumes: Rentile `RenderOptions` from `com.rohittp.rentile:kmp:0.1.5`.
- Produces: Gradle project `:kmp`, six target compilations, internal `rentileLinkageAnchor(): Int`, Android host/Linux/macOS test tasks.

- [ ] **Step 1: Verify the new module does not exist**

Run:

```bash
./gradlew --no-configuration-cache :kmp:tasks
```

Expected: failure stating project `kmp` is not found.

- [ ] **Step 2: Replace the root module and dependency configuration**

Use `git rm -r app`. Replace the version catalog with:

```toml
[versions]
agp = "9.3.1"
kotlin = "2.3.21"
mavenPublish = "0.36.0"
rentile = "0.1.5"

[libraries]
rentile-kmp = { module = "com.rohittp.rentile:kmp", version.ref = "rentile" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "mavenPublish" }
```

Replace `settings.gradle.kts` with the filtered plugin repositories already used by the project, a `Rentile` `exclusiveContent` repository for `com.rohittp.rentile`, Google, Central, and the JetBrains Compose repository filtered to `org.jetbrains.skiko`. Finish with:

```kotlin
rootProject.name = "RenG"
include(":kmp")
```

Add `VERSION_NAME=0.1.0` to `gradle.properties`. Keep `org.gradle.configuration-cache=true`, the current JVM arguments, and official Kotlin style.

Set root plugin aliases and group/version:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "com.rohittp.reng"
    version = providers.gradleProperty("VERSION_NAME").get()
}
```

Extend `.gitignore` with:

```gitignore
.kotlin/
**/build/
__pycache__/
*.py[cod]
```

- [ ] **Step 3: Create the minimal six-target module**

Create `kmp/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
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
```

Create `kmp/.gitignore` containing `/build/`.

- [ ] **Step 4: Write the linkage test first**

```kotlin
package com.rohittp.reng.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class RentileLinkageTest {
    @Test
    fun rentileImplementationIsLinked() {
        assertEquals(512, rentileLinkageAnchor())
    }
}
```

Run:

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest
```

Expected: Kotlin compilation fails because `rentileLinkageAnchor` is unresolved.

- [ ] **Step 5: Implement the internal anchor**

```kotlin
package com.rohittp.reng.internal

import com.rohittp.rentile.RenderOptions

internal fun rentileLinkageAnchor(): Int = RenderOptions().outputSizePx
```

Run:

```bash
./gradlew --no-configuration-cache \
  :kmp:testAndroidHostTest \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:macosArm64Test \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64
```

Expected: both tests pass and all six target compilations succeed. No public RenG declaration exists.

- [ ] **Step 6: Commit the skeleton**

```bash
git add \
  .gitignore \
  settings.gradle.kts \
  gradle.properties \
  gradle/libs.versions.toml \
  build.gradle.kts \
  kmp
git add -u -- app
git commit -m $'build: replace placeholder app with KMP skeleton\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 3: Add ABI and Maven Publication

**Files:**
- Modify: `build.gradle.kts`
- Modify: `kmp/build.gradle.kts`
- Create: `kmp/api/kmp.klib.api` through the ABI task

**Interfaces:**
- Consumes: `:kmp`, `VERSION_NAME`, and optional R2 environment.
- Produces: `LocalTest` and `R2` repositories, seven publications, seven POM checks, empty public KLIB ABI, and an aggregate R2 task that depends on all six target R2 tasks.

- [ ] **Step 1: Verify publication tasks are absent**

Run:

```bash
./gradlew --no-configuration-cache :kmp:tasks --all | \
  grep 'publishAllPublicationsToLocalTestRepository'
```

Expected: grep exits 1 because the publication plugin/repository is not configured.

- [ ] **Step 2: Add root R2 configuration**

Add the complete root publication configuration while retaining Task 2's group/version block:

```kotlin
import org.gradle.api.credentials.AwsCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.maven.publish) apply false
}

val r2Endpoint = providers.environmentVariable("R2_ENDPOINT")
val r2Bucket = providers.environmentVariable("R2_BUCKET")

r2Endpoint.orNull?.let {
    System.setProperty("org.gradle.s3.endpoint", it)
}

allprojects {
    group = "com.rohittp.reng"
    version = providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "R2"
                    url = uri("s3://${r2Bucket.orNull ?: "r2-publishing-not-configured"}")
                    credentials(AwsCredentials::class) {
                        accessKey = providers.environmentVariable("R2_ACCESS_KEY_ID").orNull
                        secretKey = providers.environmentVariable("R2_SECRET_ACCESS_KEY").orNull
                    }
                }
            }
        }
    }

    tasks.withType(PublishToMavenRepository::class.java)
        .matching { it.name.endsWith("ToR2Repository") }
        .configureEach {
            notCompatibleWithConfigurationCache(
                "Remote Maven publishing is not configuration-cache compatible.",
            )
        }
}
```

Ordinary builds must configure successfully when every R2 value is absent.

- [ ] **Step 3: Add POM and LocalTest publication**

Apply `alias(libs.plugins.maven.publish)` in `kmp/build.gradle.kts`. Add:

```kotlin
mavenPublishing {
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
                distribution.set("repo")
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

val targetR2PublicationTasks = listOf(
    "publishAndroidPublicationToR2Repository",
    "publishIosArm64PublicationToR2Repository",
    "publishIosSimulatorArm64PublicationToR2Repository",
    "publishMacosArm64PublicationToR2Repository",
    "publishLinuxX64PublicationToR2Repository",
    "publishLinuxArm64PublicationToR2Repository",
)

tasks.withType<PublishToMavenRepository>()
    .matching { it.name == "publishKotlinMultiplatformPublicationToR2Repository" }
    .configureEach {
        dependsOn(targetR2PublicationTasks)
    }
```

Import `org.gradle.api.publish.maven.tasks.PublishToMavenRepository`. The aggregate dependency is defense in depth: target publication tasks must complete before aggregate artifacts and metadata are attempted. Validate the graph with `--dry-run`; do not contact R2. Neither that ordering nor aggregate POM/metadata availability proves release completion; only the final valid completion record does.

- [ ] **Step 4: Generate and inspect the ABI baseline**

Run:

```bash
./gradlew --no-configuration-cache :kmp:updateKotlinAbi
```

Expected: `kmp/api/kmp.klib.api` is created for `iosArm64`, `iosSimulatorArm64`, `linuxArm64`, `linuxX64`, and `macosArm64`; it contains no public declaration or `com.rohittp.rentile`, and `kmp/api/jvm/` does not exist.

- [ ] **Step 5: Publish and validate all seven local publications**

Run:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:publishAllPublicationsToLocalTestRepository \
  :kmp:checkPomFileForAndroidPublication \
  :kmp:checkPomFileForIosArm64Publication \
  :kmp:checkPomFileForIosSimulatorArm64Publication \
  :kmp:checkPomFileForKotlinMultiplatformPublication \
  :kmp:checkPomFileForLinuxArm64Publication \
  :kmp:checkPomFileForLinuxX64Publication \
  :kmp:checkPomFileForMacosArm64Publication
```

Expected: all tasks pass and `build/local-maven/com/rohittp/reng/` contains version `0.1.0` under `kmp`, `kmp-android`, `kmp-iosarm64`, `kmp-iossimulatorarm64`, `kmp-macosarm64`, `kmp-linuxx64`, and `kmp-linuxarm64`.

- [ ] **Step 6: Commit ABI and publication**

```bash
git add build.gradle.kts kmp/build.gradle.kts kmp/api/kmp.klib.api
git commit -m $'build: add RenG ABI and Maven publication\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 4: Add the Standalone Six-Target Consumer

**Files:**
- Create: `consumer-smoke/settings.gradle.kts`
- Create: `consumer-smoke/build.gradle.kts`
- Create: `consumer-smoke/src/commonMain/kotlin/com/rohittp/reng/smoke/ConsumerProof.kt`

**Interfaces:**
- Consumes: `com.rohittp.reng:kmp:<rengVersion>` from `rengRepositoryUrl`.
- Produces: `compileAndroidMain`, five native compile tasks, and a configuration failure when neither parent `VERSION_NAME` nor `-PrengVersion` exists.

- [ ] **Step 1: Create standalone exclusive repository settings**

Create `consumer-smoke/settings.gradle.kts`:

```kotlin
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
```

- [ ] **Step 2: Create the standalone build**

Use this exact version provider and target block:

```kotlin
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
```

Create API-neutral source:

```kotlin
package com.rohittp.reng.smoke

internal fun consumerCompilationProof(): String = "com.rohittp.reng:kmp"
```

- [ ] **Step 3: Prove repository isolation fails before a valid publication**

Create an empty temporary Maven directory and fresh Gradle home, then run:

```bash
empty_repo="$(mktemp -d)"
fresh_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$fresh_home" \
  -p consumer-smoke \
  -PrengRepositoryUrl="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve().as_uri())' "$empty_repo")" \
  compileKotlinMacosArm64
```

Expected: dependency resolution fails for `com.rohittp.reng:kmp:0.1.0`; it must not fall back to Central.

- [ ] **Step 4: Compile all targets from LocalTest**

Run Task 3's local publication, then force a new dependency resolution with an empty Gradle home:

```bash
fresh_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$fresh_home" \
  --refresh-dependencies \
  -p consumer-smoke \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

Expected: all six compile tasks pass.

- [ ] **Step 5: Prove the copied public-consumer contract**

Create an empty temporary project and copy only checked-in smoke inputs, never `consumer-smoke/.gradle`, `.kotlin`, or `build`:

```bash
smoke_project="$(mktemp -d)"
smoke_home="$(mktemp -d)"
cp consumer-smoke/settings.gradle.kts "$smoke_project/"
cp consumer-smoke/build.gradle.kts "$smoke_project/"
cp -R consumer-smoke/src "$smoke_project/src"
cp gradlew "$smoke_project/gradlew"
cp -R gradle "$smoke_project/gradle"
```

Without `-PrengVersion`, run one task with `--gradle-user-home "$smoke_home"` and expect the explicit `Cannot determine the RenG version` error. Recreate both temporary directories, repeat with `-PrengVersion=0.1.0`, `--refresh-dependencies`, and an absolute `file:` URL for `build/local-maven`, and expect all six target compilations to pass.

- [ ] **Step 6: Commit the smoke consumer**

```bash
git add consumer-smoke
git commit -m $'test: add isolated six-target consumer\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 5: Implement Publication Manifest and Verification Tooling

**Files:**
- Create: `tools/verify_publication.py`
- Create: `tools/tests/test_verify_publication.py`

**Interfaces:**
- Consumes: local Maven repository, selected stable version, R2 endpoint/bucket through AWS CLI, anonymous public repository.
- Produces:
  - `VerificationError(RuntimeError)`
  - `Manifest(entries: tuple[str, ...])` with `parse(text: str, version: Version) -> Manifest` and `serialize() -> str`
  - `discover_local_manifest(repository: Path, version: Version) -> Manifest`
  - `read_manifest(path: Path, version: Version) -> Manifest`
  - `check_r2_collisions(manifest: Manifest, endpoint: str, bucket: str, run: Callable = subprocess.run) -> None`
  - `verify_public(manifest: Manifest, repository_url: str, version: Version, fetch: Callable[[str], HttpResponse], attempts: int, retry_delay: float, sleep: Callable = time.sleep) -> None`
  - `create_completion_record(manifest: Manifest, version: Version, source_commit: str) -> CompletionRecord`
  - `verify_public_completion(manifest: Manifest, repository_url: str, version: Version, source_commit: str, fetch: Callable[[str], HttpResponse], attempts: int, retry_delay: float, sleep: Callable = time.sleep) -> None`
  - CLI subcommands `local`, `r2-preflight`, `public`, `completion-create`, and `completion-public`.

- [ ] **Step 1: Write verifier tests with a seven-publication fixture**

The fixture must create POMs for exactly:

```python
EXPECTED_ARTIFACTS = frozenset({
    "kmp",
    "kmp-android",
    "kmp-iosarm64",
    "kmp-iossimulatorarm64",
    "kmp-macosarm64",
    "kmp-linuxx64",
    "kmp-linuxarm64",
})
```

Write `tools/tests/test_verify_publication.py` with concrete fixture helpers and cases:

```python
from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock

from tools.resolve_release_version import HttpResponse, Version
from tools.verify_publication import (
    EXPECTED_ARTIFACTS,
    Manifest,
    VerificationError,
    check_r2_collisions,
    discover_local_manifest,
    verify_public,
)

VERSION = Version(0, 1, 0)
POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.rohittp.reng</groupId><artifactId>{artifact}</artifactId>
  <version>0.1.0</version><url>https://rohittp.com/reng/</url>
  <licenses><license><name>The Apache License, Version 2.0</name>
  <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
  <scm><url>https://github.com/rohittp0/RenG</url></scm>
</project>
"""


class VerifyPublicationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = TemporaryDirectory()
        self.repository = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_publication(self, artifact: str) -> Path:
        directory = self.repository / "com/rohittp/reng" / artifact / "0.1.0"
        directory.mkdir(parents=True)
        pom = directory / f"{artifact}-0.1.0.pom"
        pom.write_text(POM.format(artifact=artifact), encoding="utf-8")
        (directory / f"{artifact}-0.1.0.module").write_text("{}\n", encoding="utf-8")
        return pom

    def write_all(self) -> None:
        for artifact in EXPECTED_ARTIFACTS:
            self.write_publication(artifact)

    def test_local_manifest_is_sorted_and_version_scoped(self) -> None:
        self.write_all()
        other = self.repository / "com/rohittp/reng/kmp/0.2.0/ignored.pom"
        other.parent.mkdir(parents=True)
        other.write_text("ignored", encoding="utf-8")
        manifest = discover_local_manifest(self.repository, VERSION)
        self.assertEqual(tuple(sorted(manifest.entries)), manifest.entries)
        self.assertTrue(all("/0.1.0/" in entry for entry in manifest.entries))
        self.assertEqual(manifest.serialize(), "".join(f"{entry}\n" for entry in manifest.entries))

    def test_local_verification_rejects_missing_or_extra_publication(self) -> None:
        for artifact in EXPECTED_ARTIFACTS - {"kmp-linuxarm64"}:
            self.write_publication(artifact)
        with self.assertRaisesRegex(VerificationError, "missing.*kmp-linuxarm64"):
            discover_local_manifest(self.repository, VERSION)
        self.write_publication("kmp-linuxarm64")
        self.write_publication("kmp-jvm")
        with self.assertRaisesRegex(VerificationError, "unexpected.*kmp-jvm"):
            discover_local_manifest(self.repository, VERSION)

    def test_poms_require_canonical_url_license_and_scm(self) -> None:
        self.write_all()
        pom = next(self.repository.rglob("*.pom"))
        pom.write_text(
            pom.read_text(encoding="utf-8").replace(
                "https://rohittp.com/reng/", "https://wrong.example/"
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(VerificationError, "project URL"):
            discover_local_manifest(self.repository, VERSION)

    def test_manifest_rejects_malformed_entries(self) -> None:
        invalid = (
            "",
            "b\na\n",
            "a\na\n",
            "/com/rohittp/reng/kmp/0.1.0/a\n",
            "com/rohittp/reng/../kmp/0.1.0/a\n",
            "com/rohittp/reng/kmp/0.2.0/a\n",
        )
        for text in invalid:
            with self.subTest(text=text):
                with self.assertRaises(VerificationError):
                    Manifest.parse(text, VERSION)

    def test_r2_preflight_checks_exact_keys_and_rejects_collision(self) -> None:
        manifest = Manifest(("com/rohittp/reng/kmp/0.1.0/kmp-0.1.0.pom",))
        runner = Mock(return_value=subprocess.CompletedProcess(
            args=[], returncode=0,
            stdout='{"KeyCount":1,"Contents":[{"Key":"com/rohittp/reng/kmp/0.1.0/kmp-0.1.0.pom"}]}',
            stderr="",
        ))
        with self.assertRaisesRegex(VerificationError, "already exists"):
            check_r2_collisions(manifest, "https://r2.example", "bucket", run=runner)
        command = runner.call_args.args[0]
        self.assertIn("--prefix", command)
        self.assertIn(manifest.entries[0], command)

    def test_r2_preflight_accepts_zero_keycount_without_contents(self) -> None:
        manifest = Manifest(("com/rohittp/reng/kmp/0.1.0/kmp-0.1.0.pom",))
        runner = Mock(return_value=subprocess.CompletedProcess(
            args=[], returncode=0, stdout='{"KeyCount":0}', stderr="",
        ))
        check_r2_collisions(
            manifest, "https://r2.example", "bucket", run=runner
        )
        self.assertEqual(1, runner.call_count)

    def test_r2_preflight_fails_on_aws_error_or_bad_json(self) -> None:
        manifest = Manifest(("com/rohittp/reng/kmp/0.1.0/kmp-0.1.0.pom",))
        for completed in (
            subprocess.CompletedProcess([], 2, "", "denied"),
            subprocess.CompletedProcess([], 0, "not-json", ""),
            subprocess.CompletedProcess([], 0, '{"KeyCount":1}', ""),
        ):
            with self.subTest(completed=completed):
                with self.assertRaises(VerificationError):
                    check_r2_collisions(
                        manifest, "https://r2.example", "bucket",
                        run=Mock(return_value=completed),
                    )

    def test_public_verification_fetches_every_key_and_metadata(self) -> None:
        manifest = Manifest((
            "com/rohittp/reng/kmp/0.1.0/a.module",
            "com/rohittp/reng/kmp/0.1.0/kmp-0.1.0.pom",
        ))
        requested: list[str] = []
        metadata = b"<metadata><versioning><versions><version>0.1.0</version>" \
                   b"</versions></versioning></metadata>"

        def fetch(url: str) -> HttpResponse:
            requested.append(url)
            body = metadata if url.endswith("maven-metadata.xml") else b"artifact"
            return HttpResponse(200, body)

        verify_public(
            manifest, "https://repo.example", VERSION, fetch,
            attempts=1, retry_delay=0, sleep=Mock(),
        )
        self.assertEqual(3, len(requested))
        self.assertTrue(requested[-1].endswith("kmp/maven-metadata.xml"))

    def test_public_verification_fails_after_retry_budget(self) -> None:
        manifest = Manifest(("com/rohittp/reng/kmp/0.1.0/a.module",))
        fetch = Mock(return_value=HttpResponse(503, b""))
        sleep = Mock()
        with self.assertRaisesRegex(VerificationError, "after 2 attempts"):
            verify_public(
                manifest, "https://repo.example", VERSION, fetch,
                attempts=2, retry_delay=0, sleep=sleep,
            )
        self.assertEqual(2, fetch.call_count)

    def test_public_metadata_must_list_selected_version(self) -> None:
        manifest = Manifest(("com/rohittp/reng/kmp/0.1.0/a.module",))
        responses = iter((
            HttpResponse(200, b"artifact"),
            HttpResponse(200, b"<metadata><versioning><versions>"
                              b"<version>0.0.9</version></versions>"
                              b"</versioning></metadata>"),
        ))
        with self.assertRaisesRegex(VerificationError, "does not list 0.1.0"):
            verify_public(
                manifest, "https://repo.example", VERSION,
                lambda _: next(responses), attempts=1, retry_delay=0,
                sleep=Mock(),
            )

    def test_local_cli_runs_as_a_direct_script(self) -> None:
        self.write_all()
        manifest = self.repository / "manifest.txt"
        script = Path(__file__).resolve().parents[1] / "verify_publication.py"
        result = subprocess.run(
            [
                sys.executable,
                str(script),
                "local",
                "--repository", str(self.repository),
                "--version", "0.1.0",
                "--manifest", str(manifest),
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(manifest.is_file())


if __name__ == "__main__":
    unittest.main()
```

Use `TemporaryDirectory`, injected subprocess/network functions, and no live network. Add metadata cases where a stale HTTP 200 is followed by current metadata and succeeds within the budget, and where malformed HTTP 200 metadata is retried until the budget is exhausted. Add completion-record cases that prove `completion-create` writes the manifest-bound canonical record and exact stable key, and that `completion-public` retries missing, malformed, and logically mismatched responses until the exact expected source SHA and manifest hash are anonymously visible or the budget is exhausted. Assert fetch and sleep counts so transport-only retry loops cannot satisfy the tests.

- [ ] **Step 2: Run the verifier tests and observe the import failure**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.tests.test_verify_publication -v
```

Expected: import failure for `tools.verify_publication`.

- [ ] **Step 3: Implement local discovery and manifest validation**

A valid manifest contains every regular file under:

```text
<repository>/com/rohittp/reng/<artifact>/<version>/**
```

Store repository-relative POSIX paths, sorted lexically, one per line with one trailing newline. Reject an empty manifest and any entry that is blank, duplicate, unsorted, absolute, contains `..`, lies outside `com/rohittp/reng`, or names another version. Local verification must require the exact seven artifact directories and exact canonical POM path for each.

Parse POM XML by local element name and require:

```text
url = https://rohittp.com/reng/
license name = The Apache License, Version 2.0
license url = https://www.apache.org/licenses/LICENSE-2.0.txt
scm url = https://github.com/rohittp0/RenG
```

- [ ] **Step 4: Implement exact-key R2 preflight**

For each manifest key, execute:

```text
aws --endpoint-url <endpoint> s3api list-objects-v2
    --bucket <bucket> --prefix <exact key> --output json
```

Parse `KeyCount` as a nonnegative integer. Accept an omitted or empty `Contents` field only when `KeyCount == 0`. When `KeyCount > 0`, require `Contents` to be a list of key objects and fail if the exact manifest key appears. A nonzero AWS exit, malformed JSON, inconsistent `KeyCount`/`Contents`, or malformed key is a verification error rather than absence. Do not delete or write any object.

- [ ] **Step 5: Implement anonymous public verification**

GET every manifest URL, consuming the response body. Retry only within the fixed `attempts`/`retry_delay` budget and fail after exhaustion. Then verify `com/rohittp/reng/kmp/maven-metadata.xml` within its own fixed retry budget. An HTTP 200 response is not yet success: parse stable `<version>` entries with Task 1's parser and retry valid-but-stale metadata that lacks the selected version, as well as malformed metadata, until a valid document lists the selected version. Fail closed after exhaustion. Do not inspect only `<latest>` or `<release>`. Preserve the no-follow redirect behavior of the shared HTTP adapter.

Because workflows invoke the file directly, make sibling imports work in both direct-script and package-test modes before importing Task 1:

```python
if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.resolve_release_version import HttpResponse, Version, parse_metadata_versions
```

The completion helpers derive their expected record from the parsed manifest's exact serialization, the selected version, and the supplied source commit. `completion-create` writes canonical JSON and prints only the stable object key. `completion-public` GETs that key anonymously with the same no-follow redirect behavior and retries non-200, malformed, or logically mismatched records until the exact expected record is visible or the fixed budget is exhausted.

The CLI contracts are:

```text
verify_publication.py local --repository PATH --version VERSION --manifest PATH
verify_publication.py r2-preflight --endpoint URL --bucket NAME --version VERSION --manifest PATH
verify_publication.py public --repository-url URL --version VERSION --manifest PATH [--attempts 12] [--retry-delay 5]
verify_publication.py completion-create --version VERSION --manifest PATH --source-commit SHA --output PATH
verify_publication.py completion-public --repository-url URL --version VERSION --manifest PATH --source-commit SHA [--attempts 12] [--retry-delay 5]
```

- [ ] **Step 6: Run the verifier suite**

Run the Step 2 command again.

Expected: all tests pass and output ends with `OK`.

- [ ] **Step 7: Commit publication verification**

```bash
git add tools/verify_publication.py tools/tests/test_verify_publication.py
git commit -m $'test: add fail-closed publication verification\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 6: Implement Cycle A Repository Policy Checks

**Files:**
- Create: `tools/check_repository_policy.py`
- Create: `tools/tests/test_check_repository_policy.py`

**Interfaces:**
- Consumes: repository root.
- Produces: `Violation(code: str, path: Path, line: int, message: str)`, `check_repository(root: Path) -> list[Violation]`, CLI `--root PATH`, deterministic `path:line:code:message` diagnostics.

- [ ] **Step 1: Write fixture-based policy tests**

Create `tools/tests/test_check_repository_policy.py` with a complete clean fixture and table-driven mutations:

```python
from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.check_repository_policy import check_repository

TARGETS = """
android { compileSdk = 37; minSdk = 30 }
iosArm64()
iosSimulatorArm64()
macosArm64()
linuxX64()
linuxArm64()
"""

PUBLISH_WORKFLOW = """steps:
      - name: Verify exact public artifacts and aggregate metadata
        run: python3 tools/verify_publication.py public
      - name: Resolve six targets from the public repository without credentials
        run: >-
          ./gradlew --gradle-user-home "$PUBLIC_HOME" --refresh-dependencies
          compileAndroidMain compileKotlinIosArm64 compileKotlinIosSimulatorArm64
          compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
      - id: completion
        name: Create immutable release completion record
        env:
          SOURCE_COMMIT: ${{ github.sha }}
        run: python3 tools/verify_publication.py completion-create --source-commit "$SOURCE_COMMIT"
      - name: Create release completion record in R2
        env:
          R2_ENDPOINT: ${{ vars.R2_ENDPOINT }}
          R2_BUCKET: ${{ vars.R2_BUCKET }}
          RECORD_KEY: ${{ steps.completion.outputs.record_key }}
          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}
        run: >-
          aws --endpoint-url "$R2_ENDPOINT" s3api put-object
          --bucket "$R2_BUCKET" --key "$RECORD_KEY"
          --body completion.json --content-type application/json --if-none-match '*'
      - name: Verify public release completion record without credentials
        env:
          SOURCE_COMMIT: ${{ github.sha }}
        run: >-
          python3 tools/verify_publication.py completion-public
          --source-commit "$SOURCE_COMMIT" --attempts 12 --retry-delay 5
"""


def write(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def create_clean_fixture(root: Path) -> None:
    write(root, "gradle.properties", "VERSION_NAME=0.1.0\n")
    write(root, "settings.gradle.kts", "repositories { mavenCentral() }\n")
    write(root, "build.gradle.kts", """
url.set("https://rohittp.com/reng/")
name.set("The Apache License, Version 2.0")
url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
scm { url.set("https://github.com/rohittp0/RenG") }
""")
    write(root, "gradle/libs.versions.toml", """
[versions]
kotlin = "2.3.21"
agp = "9.3.1"
rentile = "0.1.5"
[libraries]
rentile-kmp = { module = "com.rohittp.rentile:kmp", version.ref = "rentile" }
""")
    write(root, "kmp/build.gradle.kts", TARGETS + "\n" +
          "commonMain.dependencies { implementation(libs.rentile.kmp) }\n")
    write(root, "consumer-smoke/build.gradle.kts", TARGETS + "\n" +
          "implementation(\"com.rohittp.reng:kmp:$rengVersion\")\n")
    write(root, "kmp/api/kmp.klib.api", "// Targets: [iosArm64, iosSimulatorArm64, linuxArm64, linuxX64, macosArm64]\n")
    write(root, "README.md", "RenG Apache-2.0 com.rohittp.reng:kmp:<version>\n")
    write(root, "LICENSE", "Apache License\nVersion 2.0, January 2004\n")
    for name in (".nojekyll", "robots.txt", "sitemap.xml", "llms.txt"):
        write(root, f"docs/{name}", "https://rohittp.com/reng/ Apache-2.0\n")
    write(root, "docs/index.html", """
<link rel="canonical" href="https://rohittp.com/reng/">
<link rel="stylesheet" href="style.css">
<script defer src="versions.js"></script>
<span data-maven-version="kmp">pending</span>
""")
    write(root, "docs/kmp.html", """
<link rel="canonical" href="https://rohittp.com/reng/kmp.html">
<link rel="stylesheet" href="style.css">
<script defer src="versions.js"></script>
<span data-maven-version="kmp">pending</span>
""")
    write(root, "docs/style.css", ":focus-visible { outline: 2px solid currentColor; }\n")
    write(root, "docs/versions.js", "https://maven.rohittp.com/com/rohittp/reng\n")
    write(root, ".github/workflows/publish.yml", PUBLISH_WORKFLOW)
    write(root, "docs/adr/9999-history.md", "Historical com.rohittp.reng:kmp:9.9.9\n")


class RepositoryPolicyTests(unittest.TestCase):
    def test_clean_fixture_passes(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            self.assertEqual([], check_repository(root))

    def test_each_mutation_reports_expected_policy(self) -> None:
        mutations = (
            ("settings.gradle.kts", "\nmavenLocal()\n", "MAVEN_LOCAL"),
            ("gradle.properties", "VERSION_NAME=0.1.0-SNAPSHOT\n", "RENG_SNAPSHOT"),
            ("other.properties", "VERSION_NAME=0.2.0\n", "DUPLICATE_VERSION_INPUT"),
            ("kmp/build.gradle.kts", "\njvm()\n", "TARGET_SET"),
            ("kmp/api/kmp.klib.api", "final class com.rohittp.reng/Public\n", "CYCLE_A_PUBLIC_ABI"),
            ("kmp/api/kmp.klib.api", "com.rohittp.rentile/RenderOptions\n", "ABI_RENTILE_LEAK"),
            ("kmp/build.gradle.kts", "\nimplementation(\"org.jetbrains.skiko:skiko:1\")\n", "FORBIDDEN_CYCLE_A_DEPENDENCY"),
            ("kmp/build.gradle.kts", "\napi(libs.rentile.kmp)\n", "RENTILE_API_DEPENDENCY"),
            ("README.md", "\ncom.rohittp.reng:kmp:0.1.0\n", "HARDCODED_RENG_VERSION"),
            ("docs/index.html", "\n<script src=\"https://cdn.example/app.js\"></script>\n", "DOCS_EXTERNAL_DEPENDENCY"),
            ("LICENSE", "MIT\n", "LICENSE_MISMATCH"),
        )
        for relative, mutation, expected in mutations:
            with self.subTest(expected=expected):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    if path.exists():
                        path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    else:
                        write(root, relative, mutation)
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn(expected, codes)

    def test_missing_docs_and_wrong_canonical_are_reported(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            (root / "docs/kmp.html").unlink()
            index = root / "docs/index.html"
            index.write_text(
                index.read_text(encoding="utf-8").replace(
                    "https://rohittp.com/reng/", "https://wrong.example/"
                ),
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("DOCS_STRUCTURE", codes)
            self.assertIn("DOCS_CANONICAL", codes)


if __name__ == "__main__":
    unittest.main()
```

The clean fixture proves dynamic workflow `$VERSION`, Kotlin/AGP/Rentile numeric versions, and historical ADR version text are accepted.

- [ ] **Step 2: Run tests and observe import failure**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.tests.test_check_repository_policy -v
```

Expected: import failure for `tools.check_repository_policy`.

- [ ] **Step 3: Implement deterministic policy checks**

Implement this concrete orchestration shape, with one focused function for each numbered rule below:

```python
@dataclass(frozen=True)
class Violation:
    code: str
    path: Path
    line: int
    message: str


EXPECTED_TARGETS = frozenset({
    "android", "iosArm64", "iosSimulatorArm64",
    "macosArm64", "linuxX64", "linuxArm64",
})


def check_repository(root: Path) -> list[Violation]:
    checks = (
        check_maven_local,
        check_version_inputs,
        check_targets,
        check_dependencies,
        check_abi,
        check_public_version_literals,
        check_docs,
        check_license,
        check_completion_record_workflow,
    )
    violations = [item for check in checks for item in check(root)]
    return sorted(
        violations,
        key=lambda item: (item.path.as_posix(), item.line, item.code, item.message),
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    arguments = parser.parse_args(argv)
    violations = check_repository(arguments.root.resolve())
    for item in violations:
        relative = item.path.relative_to(arguments.root.resolve())
        print(f"{relative}:{item.line}:{item.code}:{item.message}")
    if violations:
        return 1
    print("Cycle A repository policy passed")
    return 0
```

The checker must:

1. Scan production Gradle scripts for `mavenLocal()`.
2. Require one stable `VERSION_NAME` assignment in root `gradle.properties` and none elsewhere.
3. Require the exact six target factory calls once each in `kmp/build.gradle.kts` and `consumer-smoke/build.gradle.kts`.
4. Require `implementation(libs.rentile.kmp)` and reject `api` exposure.
5. Reject direct Wire, serialization, Skiko, Ktor, or corpus dependencies/plugins from the KMP build/catalog while permitting Rentile's transitive graph.
6. Require `kmp/api/kmp.klib.api`, reject `kmp/api/jvm`, reject `com.rohittp.rentile` in ABI, and reject non-comment ABI declarations during Cycle A.
7. Reject literal RenG coordinate versions and `rengVersion` semantic literals in README, top-level static docs, smoke, production Gradle, catalog, and workflows. Exclude ADRs, superpowers docs, decomposition, and third-party notices.
8. Require `docs/.nojekyll`, `index.html`, `kmp.html`, `style.css`, `versions.js`, `robots.txt`, `sitemap.xml`, and `llms.txt`; require canonical `https://rohittp.com/reng/`, local-only CSS/JS, at least one `data-maven-version="kmp"`, and no CSS `@import`.
9. Require Apache-2.0 consistency in `LICENSE`, README, docs, and POM build metadata.
10. Require the publish workflow to order public artifact/metadata verification, credential-free six-target smoke, local completion-record derivation from `${{ github.sha }}`, credential-scoped authoritative R2 `aws --endpoint-url "$R2_ENDPOINT" s3api put-object --if-none-match '*'`, and credential-free anonymous record verification. Reject missing endpoint/atomic-write arguments, credentials in record creation or anonymous verification, and ordering regressions.

- [ ] **Step 4: Run policy tests and the real repository check**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.tests.test_check_repository_policy -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

Expected: all tests pass; the real check exits 0 and prints a clean summary.

- [ ] **Step 5: Commit policy checks**

```bash
git add tools/check_repository_policy.py tools/tests/test_check_repository_policy.py
git commit -m $'test: enforce Cycle A repository policy\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 7: Add Static Documentation and Legal Material

**Files:**
- Create: `README.md`
- Create: `LICENSE`
- Create: `THIRD_PARTY_NOTICES.md`
- Create: `docs/.nojekyll`
- Create: `docs/index.html`
- Create: `docs/kmp.html`
- Create: `docs/style.css`
- Create: `docs/versions.js`
- Create: `docs/robots.txt`
- Create: `docs/sitemap.xml`
- Create: `docs/llms.txt`

**Interfaces:**
- Consumes: settled coordinate, targets, release rules, canonical/source URLs, Apache-2.0.
- Produces: dependency-free Pages source with dynamic Maven version display and no runtime API promise.

- [ ] **Step 1: Invoke the frontend-design skill before site implementation**

The implementation agent must use `frontend-design:frontend-design`, then retain Rentile's restrained documentation character rather than introducing a framework, package manager, web font, dashboard, or app shell.

- [ ] **Step 2: Add README and legal files**

Copy the full Apache-2.0 license text from `/Users/rohittp/Data/Other/rentile/LICENSE`; it already names `Copyright 2026 Rohit T P`.

README sections are: current pre-runtime and first-release-pending status, configured coordinate and anonymous repository after verification, exact six targets and exclusions, property-driven dependency example, local gate commands, documentation version convention, and Apache-2.0. The dependency example must use `${rengVersion.get()}` or `<version>`, never a concrete RenG semantic version. Include JetBrains Compose filtered to `org.jetbrains.skiko` so Rentile's Skiko platform artifacts resolve.

`THIRD_PARTY_NOTICES.md` lists Kotlin 2.3.21 and Rentile 0.1.5 under Apache-2.0 and links Rentile's own third-party notices for its transitive runtime graph. Do not copy direct Wire, schema, Skiko, Ktor, corpus, or Vanniktech rows into RenG.

- [ ] **Step 3: Create the static pages**

`index.html` contains canonical metadata, local `style.css`, deferred local `versions.js`, `SoftwareSourceCode` JSON-LD, a pre-runtime hero/status, configured coordinate and six-target summary, release behavior, source/ADR links, and Apache-2.0. `kmp.html` contains the repositories and property-driven dependency syntax that become usable after anonymous first-release verification, the configured target table, and the same explicit statement that Cycle A exposes no runtime API and renders nothing. Both pages state that the first public release is pending and must not claim that the coordinate or targets already resolve publicly.

Every browser-rendered version uses:

```html
<span data-maven-version="kmp">pending</span>
```

No HTML file contains a concrete RenG semantic version, external stylesheet, external script, OG image reference, or future API signature.

- [ ] **Step 4: Add dynamic metadata loading and crawler files**

Create `docs/versions.js`:

```javascript
(() => {
  const nodes = document.querySelectorAll('[data-maven-version="kmp"]');
  if (nodes.length === 0) return;

  fetch(
    "https://maven.rohittp.com/com/rohittp/reng/kmp/maven-metadata.xml",
    { cache: "no-store" },
  )
    .then((response) => {
      if (!response.ok) {
        throw new Error(`Metadata request failed with HTTP ${response.status}`);
      }
      return response.text();
    })
    .then((metadata) => {
      const xml = new DOMParser().parseFromString(metadata, "application/xml");
      if (xml.querySelector("parsererror")) {
        throw new Error("Metadata response is not valid XML");
      }
      const release = xml.querySelector("versioning > release")?.textContent?.trim();
      if (!release) {
        throw new Error("Metadata does not contain a release version");
      }
      nodes.forEach((node) => {
        node.textContent = release;
      });
    })
    .catch((error) => {
      console.error("Unable to load RenG release metadata.", error);
    });
})();
```

Create `docs/robots.txt`:

```text
User-agent: *
Allow: /

Sitemap: https://rohittp.com/reng/sitemap.xml
```

Create `docs/sitemap.xml` with exactly the canonical URLs `https://rohittp.com/reng/` and `https://rohittp.com/reng/kmp.html`. `docs/llms.txt` states canonical/source URLs, coordinate, configured targets that become available after first-release verification, pending release status, Apache-2.0, immutable fail-closed releases, and pre-runtime scope. `.nojekyll` is empty.

Document the post-release procedure without executing it: only after the exact merged CI commit and first public completion record verify may an authorized documentation-only follow-up remove pending wording from `CLAUDE.md`, README, `docs/index.html`, `docs/kmp.html`, and `docs/llms.txt`, adjust the `docs/versions.js` fallback from `pending` if applicable, and update `HANDOFF.md` plus `docs/decomposition.md`. ADR 0013 and the Cycle A design spec and implementation plan remain historical decision records unless the release exposes a contract error. The dynamic Maven metadata display remains the version source; do not replace it with a checked-in RenG version literal.

- [ ] **Step 5: Serve and inspect locally**

Run:

```bash
python3 -m http.server 8000 --directory docs
```

Inspect `/` and `/kmp.html` at desktop and narrow widths, keyboard through every link, and verify visible focus. Stop the server after inspection. Run:

```bash
rg -n 'com\.rohittp\.reng:kmp:[0-9]+\.[0-9]+\.[0-9]+' README.md docs/index.html docs/kmp.html
rg -n '<link[^>]+https?://|<script[^>]+https?://|@import' docs
```

Expected: both searches return no matches.

- [ ] **Step 6: Commit documentation and legal files**

```bash
git add README.md LICENSE THIRD_PARTY_NOTICES.md docs
git commit -m $'docs: add Cycle A publication guidance\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 8: Wire Cycle A into Ordinary CI

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: all Python tools, `:kmp`, `consumer-smoke`.
- Produces: green `android-linux` and `apple-publication` jobs for the exact feature commit.

- [ ] **Step 1: Add deterministic Python setup and checks to Ubuntu**

After JDK/Gradle setup, add `actions/setup-python@v6` for Python 3.12, then:

```yaml
      - name: Test release and policy tooling
        run: >-
          python3 -m unittest discover
          -s tools/tests
          -p 'test_*.py'
          -v

      - name: Check Cycle A repository policy
        run: python3 tools/check_repository_policy.py --root .
```

- [ ] **Step 2: Preserve and extend exact Gradle gates**

Ubuntu runs:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

macOS runs both iOS compilations, `macosArm64Test`, and `publishAllPublicationsToLocalTestRepository`. Its smoke step must force resolution rather than reuse the publication job's project state:

```bash
smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$smoke_home" \
  --refresh-dependencies \
  -p consumer-smoke \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

Do not add JVM, Intel Apple, corpus, or credential-bearing steps.

- [ ] **Step 3: Validate workflow syntax and local equivalents**

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
python3 tools/check_repository_policy.py --root .
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml", aliases: true)'
git diff --check
```

Expected: unit tests end in `OK`; policy, YAML parse, and diff check exit 0.

- [ ] **Step 4: Commit ordinary CI**

```bash
git add .github/workflows/ci.yml
git commit -m $'ci: gate Cycle A build and local publication\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 9: Make Public Publication Fail Closed

**Files:**
- Modify: `.github/workflows/publish.yml`

**Interfaces:**
- Consumes: Task 1 resolver/record CLI contracts, Task 5 verifier CLI, Task 6 policy CLI, Gradle publications, smoke consumer, and existing R2 values.
- Produces: `resolve-version`, Linux release gate, macOS publication, authoritative manifest collision preflight, anonymous artifact/metadata verification, clean public smoke, immutable conditional completion-record creation, and anonymous record verification.

- [ ] **Step 1: Replace inline resolution with tested one-candidate resolution**

At the start of `resolve-version`, fail an explicit manual dispatch when `github.ref != 'refs/heads/main'`. Check out, set up Python 3.12, run all tool tests and repository policy, require `R2_PUBLIC_URL`, and invoke:

```bash
version="$(
  python3 tools/resolve_release_version.py \
    --properties-file gradle.properties \
    --repository-url "$R2_PUBLIC_URL"
)"
printf 'version=%s\n' "$version" >> "$GITHUB_OUTPUT"
```

Delete the inline shell metadata parser and candidate loop. There is no occupied-candidate loop in the replacement.

- [ ] **Step 2: Add a self-contained Linux release job**

Add an Ubuntu job depending on `resolve-version`. Set up JDK 21/Gradle and run with the resolved `-PVERSION_NAME`:

```bash
./gradlew --no-configuration-cache \
  -PVERSION_NAME="$VERSION" \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Make the macOS publish job depend on both version resolution and this Linux gate. Do not rely on the concurrently running ordinary CI workflow.

- [ ] **Step 3: Keep the complete local macOS gate**

Retain validation of the five R2 values. Run `clean`, ABI, Android host, macOS tests, Apple/Linux compilations, Android AAR, local publication, and all seven `checkPomFileFor*Publication` tasks. Add `linuxX64Test` only to the Ubuntu job, because it cannot execute on macOS.

Replace inline publication scanning with:

```bash
python3 tools/verify_publication.py local \
  --repository build/local-maven \
  --version "$VERSION" \
  --manifest "$RUNNER_TEMP/reng-release-manifest.txt"
```

Then force six-target local smoke resolution before any R2 read/write:

```bash
local_smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$local_smoke_home" \
  --refresh-dependencies \
  -p consumer-smoke \
  -PrengVersion="$VERSION" \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

- [ ] **Step 4: Replace aggregate-POM preflight with manifest-derived exact checks**

Delete the old pre-build `Reject an existing release` step. After local verification and local smoke, run:

```bash
python3 tools/verify_publication.py r2-preflight \
  --endpoint "$R2_ENDPOINT" \
  --bucket "$R2_BUCKET" \
  --version "$VERSION" \
  --manifest "$RUNNER_TEMP/reng-release-manifest.txt"
```

Supply AWS credentials only to this step. Any exact key collision or AWS uncertainty stops before upload. Do not select another version, delete keys, or overwrite them.

- [ ] **Step 5: Upload and verify the exact public set**

Run the existing `publishAllPublicationsToR2Repository` once. Its aggregate KotlinMultiplatform R2 task must depend on all six target R2 tasks as defense in depth against partial state; neither the aggregate POM nor metadata is a completion witness. Then verify:

```bash
python3 tools/verify_publication.py public \
  --repository-url "$R2_PUBLIC_URL" \
  --version "$VERSION" \
  --manifest "$RUNNER_TEMP/reng-release-manifest.txt" \
  --attempts 12 \
  --retry-delay 5
```

This must fetch every manifest key anonymously and require the release in aggregate metadata.

- [ ] **Step 6: Preserve clean credential-free public resolution**

Create fresh temporary smoke and Gradle-home directories and copy only checked-in inputs:

```bash
smoke_project="$(mktemp -d "$RUNNER_TEMP/reng-r2-consumer.XXXXXX")"
public_gradle_home="$(mktemp -d "$RUNNER_TEMP/reng-r2-gradle-home.XXXXXX")"
cp consumer-smoke/settings.gradle.kts "$smoke_project/"
cp consumer-smoke/build.gradle.kts "$smoke_project/"
cp -R consumer-smoke/src "$smoke_project/src"
cp gradlew "$smoke_project/gradlew"
cp -R gradle "$smoke_project/gradle"

"$smoke_project/gradlew" --no-configuration-cache \
  --gradle-user-home "$public_gradle_home" \
  --refresh-dependencies \
  -p "$smoke_project" \
  -PrengRepositoryUrl="${R2_PUBLIC_URL%/}" \
  -PrengVersion="$VERSION" \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

Do not copy `consumer-smoke/.gradle`, `.kotlin`, or `build`, root `gradle.properties`, or any publishing credential. This step cannot become up-to-date from the local smoke run.

- [ ] **Step 7: Create and anonymously verify the immutable completion record**

Only after Steps 5 and 6 succeed, derive the canonical record and stable key without credentials:

```bash
record_key="$(
  python3 tools/verify_publication.py completion-create \
    --version "$VERSION" \
    --manifest "$RUNNER_TEMP/reng-release-manifest.txt" \
    --source-commit "$GITHUB_SHA" \
    --output "$RUNNER_TEMP/reng-release-completion.json"
)"
```

Of the three completion-record stages, give R2 credentials only to the separate conditional-write step and create the exact key:

```bash
aws --endpoint-url "$R2_ENDPOINT" s3api put-object \
  --bucket "$R2_BUCKET" \
  --key "$RECORD_KEY" \
  --body "$RUNNER_TEMP/reng-release-completion.json" \
  --content-type "application/json" \
  --if-none-match '*'
```

An existing key or uncertain write stops the workflow; never overwrite it. A following step has no AWS or R2 credentials and runs:

```bash
python3 tools/verify_publication.py completion-public \
  --repository-url "$R2_PUBLIC_URL" \
  --version "$VERSION" \
  --manifest "$RUNNER_TEMP/reng-release-manifest.txt" \
  --source-commit "$GITHUB_SHA" \
  --attempts 12 \
  --retry-delay 5
```

This final command retries anonymous retrieval and requires the exact record derived from the selected version, exact `${{ github.sha }}`, and exact local-manifest bytes.

- [ ] **Step 8: Validate workflow and failure behavior**

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
python3 tools/check_repository_policy.py --root .
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/publish.yml", aliases: true)'
rg -n 'linuxX64Test|verify_publication.py|resolve_release_version.py' .github/workflows/publish.yml
git diff --check
```

Expected: tests/policy/YAML/diff pass. The workflow contains one-candidate resolution, Linux testing, local manifest, R2 preflight, public artifact/metadata verification, fresh public smoke, conditional completion-record creation, and credential-free anonymous record verification in that order. It contains no old candidate loop, aggregate-POM completion claim, or aggregate-POM-only preflight.

- [ ] **Step 9: Commit publication hardening**

```bash
git add .github/workflows/publish.yml
git commit -m $'ci: enforce fail-closed public publication\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

---

### Task 10: Align Project Guidance and Run Final Gates

**Files:**
- Modify: `CLAUDE.md`
- Modify: `HANDOFF.md`
- Modify: `docs/decomposition.md` only if gate wording is no longer exact after implementation.

**Interfaces:**
- Consumes: Tasks 1-9.
- Produces: accurate Cycle B handoff, local verification evidence, merge-ready feature branch, separately approved public-release procedure.

- [ ] **Step 1: Update repository-state guidance**

Replace statements that `:kmp`, `consumer-smoke`, static docs, or `VERSION_NAME` do not exist. Record exact implemented commands, policy/verifier tools, aggregate-after-target R2 ordering as defense in depth, the stable completion-record path/schema and final workflow order, record-gated routine advancement, one candidate aggregate-POM probe, and explicit upward recovery. State that neither POM nor metadata availability proves completion. Keep Cycle A's public release pending until the exact merged commit passes both CI jobs and its public workflow anonymously verifies the exact completion record. Record that Cycle B preparation starts only after that outcome and proceeds in this order: read the governing docs, run feasibility spikes, invoke `/grill-with-docs`, then write any implementation plan.

- [ ] **Step 2: Commit guidance before verification and review**

```bash
git add CLAUDE.md HANDOFF.md docs/decomposition.md
git commit -m $'docs: hand off the Cycle A publication skeleton\n\nCo-Authored-By: Claude <noreply@anthropic.com>'
```

The committed guidance must say “public release pending” until the workflow has actually succeeded.

- [ ] **Step 3: Run Python and policy gates**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

Expected: all tests pass and policy exits 0.

- [ ] **Step 4: Run Ubuntu-equivalent Gradle gates**

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Run this on Linux/CI for executable `linuxX64Test`. On macOS, run every compilable task and rely on CI for the Linux binary.

- [ ] **Step 5: Run macOS publication and fresh smoke gates**

```bash
./gradlew --no-configuration-cache \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:macosArm64Test \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:publishAllPublicationsToLocalTestRepository

final_smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$final_smoke_home" \
  --refresh-dependencies \
  -p consumer-smoke \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

Expected: all tasks pass using a fresh smoke dependency cache.

- [ ] **Step 6: Run workflow and repository integrity checks**

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml", aliases: true); YAML.load_file(".github/workflows/publish.yml", aliases: true)'
git diff --check
git status --short
git log --oneline --decorate -12
```

Expected: YAML/diff checks pass, `git status --short` is empty, and commits match the task boundaries.

- [ ] **Step 7: Request code review before integration**

Invoke `superpowers:requesting-code-review`, review every finding technically, fix confirmed issues one at a time, commit each fix, and rerun the affected test plus the complete local gates. Repeat Step 6 after fixes and require a clean tree.

- [ ] **Step 8: Push the feature branch only with explicit approval**

After approval, push the feature branch and open a PR against `main`. Wait for both CI jobs on the exact SHA. A passing PR proves merge readiness, not public publication.

- [ ] **Step 9: Trigger the first public release only with fresh explicit approval**

After CI-tested fast-forward integration into local `main`, request a separate approval immediately before `git push origin main`. That push triggers the first public candidate. Observe both CI and publish workflows on the exact merged commit. Cycle A is complete only when both CI jobs pass that exact commit and the publish run proves local publication, no authoritative R2 collision, anonymous retrieval of every manifest artifact, valid aggregate metadata containing the selected version, fresh credential-free six-target resolution, conditional creation of the exact completion record, and final credential-free anonymous verification of that record.

- [ ] **Step 10: Record the observed public outcome without mutating artifacts**

If failure occurs before upload, fix on a new commit and seek fresh push approval. If any versioned object was uploaded, do not retry the same version, overwrite, or delete it. Commit an explicit upward `VERSION_NAME`, rerun every gate, and seek fresh push approval; preserve the version gap.

After a successful public run and anonymous completion-record verification, make an authorized documentation-only follow-up that removes or revises every pending claim in `CLAUDE.md`, `README.md`, `docs/index.html`, `docs/kmp.html`, and `docs/llms.txt`; changes the `docs/versions.js` fallback from `pending` only if applicable; and records the observed exact merged CI/public outcome in `HANDOFF.md` and `docs/decomposition.md`. ADR 0013 and the Cycle A design spec and implementation plan remain historical decision records unless the release exposes a contract error. Keep version display metadata-driven and do not check a RenG semantic version literal into README or served docs. This is a separate commit and push requiring explicit approval; do not include it in the CI-reviewed feature commit.

Only after that exact merged CI and public completion outcome may Cycle B preparation begin: read `CONTEXT.md`, the governing ADRs, `docs/decomposition.md`, and `HANDOFF.md`; run the required feasibility spikes; invoke `/grill-with-docs` with those findings; then write any implementation plan. Do not start Cycle B implementation as part of the release follow-up.
