from __future__ import annotations

import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.check_repository_policy import check_repository, main

TARGETS = """
android { compileSdk = 37; minSdk = 30 }
iosArm64()
iosSimulatorArm64()
macosArm64()
linuxX64()
linuxArm64()
"""

PUBLIC_SMOKE_STEP = """      - name: Resolve six targets from the public repository without credentials
        run: >-
          ./gradlew --gradle-user-home "$PUBLIC_HOME" --refresh-dependencies
          compileAndroidMain compileKotlinIosArm64 compileKotlinIosSimulatorArm64
          compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
"""
COMPLETION_CREATE_STEP = """      - name: Create immutable release completion record
        env:
          SOURCE_COMMIT: ${{ github.sha }}
        run: >-
          python3 tools/verify_publication.py completion-create
          --source-commit "$SOURCE_COMMIT"
"""
COMPLETION_WRITE_STEP = """      - name: Create release completion record in R2
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
"""
COMPLETION_PUBLIC_STEP = """      - name: Verify public release completion record without credentials
        env:
          R2_PUBLIC_URL: ${{ vars.R2_PUBLIC_URL }}
          SOURCE_COMMIT: ${{ github.sha }}
        run: >-
          python3 tools/verify_publication.py completion-public
          --source-commit "$SOURCE_COMMIT" --attempts 12 --retry-delay 5
"""
PUBLISH_WORKFLOW = (
    "steps:\n"
    "      - name: Verify exact public artifacts and aggregate metadata\n"
    "        run: python3 tools/verify_publication.py public\n"
    + PUBLIC_SMOKE_STEP
    + COMPLETION_CREATE_STEP
    + COMPLETION_WRITE_STEP
    + COMPLETION_PUBLIC_STEP
)

LIBRARY_CREATED_DATA_CLASSES = (
    ("Diagnostic", "kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt"),
    ("RawResourceKey", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceAdapters.kt"),
    ("ResourceKey", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
    ("ResourceUsage", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
    ("ResourceReportEntry", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
    ("ResourceFreeResult", "kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt"),
)


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
          "commonMain.dependencies { implementation(libs.rentile.kmp) }\n" +
          "commonTest.dependencies { implementation(kotlin(\"test\")) }\n")
    write(root, "consumer-smoke/build.gradle.kts", TARGETS + "\n" +
          "implementation(\"com.rohittp.reng:kmp:$rengVersion\")\n")
    write(root, "consumer-smoke/settings.gradle.kts", "rootProject.name = \"consumer-smoke\"\n")
    abi_classes = "\n".join(
        f"final class com.rohittp.reng/{class_name} {{\n}}\n"
        for class_name, _ in LIBRARY_CREATED_DATA_CLASSES
    )
    write(
        root,
        "kmp/api/kmp.klib.api",
        "// Targets: [iosArm64, iosSimulatorArm64, linuxArm64, linuxX64, macosArm64]\n"
        "final class com.rohittp.reng/Public\n\n"
        + abi_classes,
    )
    declarations_by_path: dict[str, list[str]] = {}
    for class_name, relative in LIBRARY_CREATED_DATA_CLASSES:
        declarations_by_path.setdefault(relative, []).append(
            "@ConsistentCopyVisibility\n"
            f"public data class {class_name} internal constructor(public val value: String)\n"
        )
    for relative, declarations in declarations_by_path.items():
        write(
            root,
            relative,
            "package com.rohittp.reng\n\n" + "\n".join(declarations),
        )
    write(root, "README.md", "RenG Apache-2.0 com.rohittp.reng:kmp:<version>\n")
    write(root, "LICENSE", "Apache License\nVersion 2.0, January 2004\n")
    for name in (".nojekyll", "robots.txt", "sitemap.xml", "llms.txt"):
        write(root, f"docs/{name}", "https://rohittp.com/reng/ Apache-2.0\n")
    write(root, "docs/index.html", """
<link rel="canonical" href="https://rohittp.com/reng/">
<link rel="stylesheet" href="style.css">
<script defer src="versions.js"></script>
<span data-maven-version="kmp">latest</span>
<footer>Apache-2.0</footer>
""")
    write(root, "docs/kmp.html", """
<link rel="canonical" href="https://rohittp.com/reng/kmp.html">
<link rel="stylesheet" href="style.css">
<script defer src="versions.js"></script>
<span data-maven-version="kmp">latest</span>
<footer>Apache-2.0</footer>
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

    def test_clean_fixture_cli_reports_cycle_b_success_exactly(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            output = StringIO()
            with redirect_stdout(output):
                status = main(["--root", str(root)])
            self.assertEqual(0, status)
            self.assertEqual("Cycle B repository policy passed\n", output.getvalue())

    def test_cycle_b_abi_mutations_fail_closed(self) -> None:
        replacement_cases = (
            ("", "CYCLE_B_PUBLIC_ABI"),
            ("// comments only\n", "CYCLE_B_PUBLIC_ABI"),
        )
        for contents, expected in replacement_cases:
            with self.subTest(contents=contents):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    write(root, "kmp/api/kmp.klib.api", contents)
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn(expected, codes)

        append_cases = (
            ("com.rohittp.rentile/RenderOptions\n", "ABI_RENTILE_LEAK"),
            ("final class platform.posix/FILE\n", "ABI_PLATFORM_LEAK"),
            ("final fun com.rohittp.reng/createRenderer(): com.rohittp.reng/Renderer\n", "CYCLE_B_RENDERER_CONSTRUCTION"),
            ("final class com.rohittp.reng/RendererFactory\n", "CYCLE_B_RENDERER_CONSTRUCTION"),
            ("final object com.rohittp.reng/RenG\n", "CYCLE_B_RENDERER_CONSTRUCTION"),
        )
        for mutation, expected in append_cases:
            with self.subTest(expected=expected, mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    abi = root / "kmp/api/kmp.klib.api"
                    abi.write_text(abi.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn(expected, codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "kmp/api/jvm.api", "final class com.rohittp.reng/JvmOnly\n")
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("JVM_ABI", codes)

    def test_library_created_data_classes_require_source_copy_visibility_and_closed_abi(self) -> None:
        for class_name, relative in LIBRARY_CREATED_DATA_CLASSES:
            declaration = (
                "@ConsistentCopyVisibility\n"
                f"public data class {class_name} internal constructor(public val value: String)"
            )
            without_annotation = (
                f"public data class {class_name} internal constructor(public val value: String)"
            )
            exposed_annotation = (
                "@ExposedCopyVisibility\n"
                f"public data class {class_name} internal constructor(public val value: String)"
            )
            for replacement, expected in (
                (without_annotation, "CONSISTENT_COPY_VISIBILITY"),
                (exposed_annotation, "EXPOSED_COPY_VISIBILITY"),
            ):
                with self.subTest(class_name=class_name, source_expected=expected):
                    with TemporaryDirectory() as directory:
                        root = Path(directory)
                        create_clean_fixture(root)
                        source = root / relative
                        source.write_text(
                            source.read_text(encoding="utf-8").replace(declaration, replacement),
                            encoding="utf-8",
                        )
                        codes = {violation.code for violation in check_repository(root)}
                        self.assertIn(expected, codes)

            generated_leaks = (
                (
                    f"    constructor <init>(kotlin/String) // "
                    f"com.rohittp.reng/{class_name}.<init>|<init>(kotlin.String){{}}[0]\n",
                    "constructor",
                ),
                (
                    f"    final fun copy(kotlin/String = ...): com.rohittp.reng/{class_name} // "
                    f"com.rohittp.reng/{class_name}.copy|copy(kotlin.String){{}}[0]\n",
                    "copy",
                ),
            )
            for leak, shape in generated_leaks:
                with self.subTest(class_name=class_name, abi_leak=shape):
                    with TemporaryDirectory() as directory:
                        root = Path(directory)
                        create_clean_fixture(root)
                        abi = root / "kmp/api/kmp.klib.api"
                        header = f"final class com.rohittp.reng/{class_name} {{\n"
                        abi.write_text(
                            abi.read_text(encoding="utf-8").replace(header, header + leak),
                            encoding="utf-8",
                        )
                        codes = {violation.code for violation in check_repository(root)}
                        self.assertIn("LIBRARY_CREATED_DATA_CLASS_ABI", codes)

    def test_internal_package_public_abi_fails_closed_for_dot_and_slash_dumps(self) -> None:
        mutations = (
            "final class com.rohittp.reng.internal/Leaked\n",
            "final class com/rohittp/reng/internal/Leaked\n",
            "final class com.rohittp.reng.internal.Leaked\n",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    abi = root / "kmp/api/kmp.klib.api"
                    abi.write_text(abi.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("ABI_INTERNAL_LEAK", codes)

    def test_cycle_b_dependency_allowlist_rejects_every_addition(self) -> None:
        mutations = (
            ("commonMain", 'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1")'),
            ("commonMain", 'implementation("org.kotlincrypto.hash:sha2:1")'),
            ("commonMain", 'implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1")'),
            ("commonMain", 'implementation("io.ktor:ktor-client-core:1")'),
            ("commonMain", 'implementation("org.jetbrains.skiko:skiko:1")'),
            ("commonMain", 'implementation("com.squareup.wire:wire-runtime:1")'),
            ("commonMain", 'implementation("example:corpus:1")'),
            ("commonMain", 'runtimeOnly("com.example:unknown-runtime:1")'),
            ("commonTest", 'implementation("com.example:test-helper:1")'),
        )
        for source_set, mutation in mutations:
            with self.subTest(source_set=source_set, mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    marker = f"{source_set}.dependencies {{ "
                    build.write_text(
                        build.read_text(encoding="utf-8").replace(
                            marker,
                            marker + mutation + " ",
                        ),
                        encoding="utf-8",
                    )
                    violations = check_repository(root)
                    matching = [
                        violation for violation in violations
                        if violation.code == "FORBIDDEN_CYCLE_B_DEPENDENCY"
                    ]
                    self.assertTrue(matching)
                    self.assertTrue(all("Cycle B" in violation.message for violation in matching))

    def test_cycle_b_dependency_allowlist_requires_both_exact_entries(self) -> None:
        removals = (
            "commonMain.dependencies { implementation(libs.rentile.kmp) }\n",
            'commonTest.dependencies { implementation(kotlin("test")) }\n',
        )
        for removal in removals:
            with self.subTest(removal=removal):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    build.write_text(
                        build.read_text(encoding="utf-8").replace(removal, ""),
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_allowlist_rejects_gradle_indirection(self) -> None:
        script_mutations = (
            (
                "kmp/build.gradle.kts",
                "\ndependencies.addProvider(\"commonMainImplementation\", "
                "providers.provider { \"com.example:runtime:1\" })\n",
            ),
            (
                "kmp/build.gradle.kts",
                "\nconfigurations.named(\"commonMainImplementation\") { "
                "dependencies += project.dependencies.create(\"com.example:runtime:1\") }\n",
            ),
            (
                "kmp/build.gradle.kts",
                "\ncommonMainImplementation(\"com.example:runtime:1\")\n",
            ),
            (
                "build.gradle.kts",
                "\nsubprojects { dependencies { "
                "add(\"commonMainImplementation\", \"com.example:runtime:1\") } }\n",
            ),
            (
                "build.gradle.kts",
                "\nallprojects { configurations.configureEach { withDependencies { "
                "add(project.dependencies.create(\"com.example:runtime:1\")) } } }\n",
            ),
            (
                "build.gradle.kts",
                "\nconfigurations.configureEach { resolutionStrategy.dependencySubstitution { "
                "substitute(module(\"com.rohittp.rentile:kmp\"))"
                ".using(module(\"com.example:replacement:1\")) } }\n",
            ),
        )
        for relative, mutation in script_mutations:
            with self.subTest(relative=relative, mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "gradle/injected.gradle.kts",
                "dependencies { add(\"commonMainImplementation\", \"com.example:runtime:1\") }\n",
            )
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8")
                + "\napply(from = rootProject.file(\"gradle/injected.gradle.kts\"))\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_cycle_b_dependency_catalog_is_exact_and_custom_catalogs_are_rejected(self) -> None:
        catalog_mutations = (
            lambda text: text.replace("com.rohittp.rentile:kmp", "com.example:replacement"),
            lambda text: text.replace('rentile = "0.1.5"', 'rentile = "9.9.9"'),
            lambda text: text + '\nextra = { module = "com.example:runtime", version = "1" }\n',
        )
        for mutate in catalog_mutations:
            with self.subTest(mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    catalog = root / "gradle/libs.versions.toml"
                    catalog.write_text(mutate(catalog.read_text(encoding="utf-8")), encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(
                root,
                "gradle/injected.versions.toml",
                '[versions]\nextra = "1"\n[libraries]\n'
                'extra = { module = "com.example:runtime", version.ref = "extra" }\n',
            )
            settings = root / "settings.gradle.kts"
            settings.write_text(
                settings.read_text(encoding="utf-8")
                + "\ndependencyResolutionManagement { versionCatalogs { "
                "create(\"injected\") { from(files(\"gradle/injected.versions.toml\")) } } }\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

    def test_each_mutation_reports_expected_policy(self) -> None:
        mutations = (
            ("settings.gradle.kts", "\nmavenLocal()\n", "MAVEN_LOCAL"),
            ("gradle.properties", "VERSION_NAME=0.1.0-SNAPSHOT\n", "RENG_SNAPSHOT"),
            ("other.properties", "VERSION_NAME=0.2.0\n", "DUPLICATE_VERSION_INPUT"),
            ("kmp/build.gradle.kts", "\njvm()\n", "TARGET_SET"),
            ("kmp/api/kmp.klib.api", "com.rohittp.rentile/RenderOptions\n", "ABI_RENTILE_LEAK"),
            ("kmp/build.gradle.kts", "\nimplementation(\"org.jetbrains.skiko:skiko:1\")\n", "FORBIDDEN_CYCLE_B_DEPENDENCY"),
            ("kmp/build.gradle.kts", "\napi(libs.rentile.kmp)\n", "FORBIDDEN_CYCLE_B_DEPENDENCY"),
            ("kmp/build.gradle.kts", "\nsignAllPublications()\n", "ARTIFACT_SIGNING"),
            (".github/workflows/publish.yml", "\nSIGNING_KEY: configured\n", "ARTIFACT_SIGNING"),
            ("tools/verify_publication.py", "--require-signed-poms\n", "ARTIFACT_SIGNING"),
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

    def test_completion_record_workflow_requires_atomic_order_and_credential_scope(self) -> None:
        mutations = (
            lambda text: text.replace(" --if-none-match '*'", ""),
            lambda text: text.replace('--endpoint-url "$R2_ENDPOINT" ', ""),
            lambda text: text.replace(" compileKotlinLinuxArm64", ""),
            lambda text: text.replace(
                "          SOURCE_COMMIT: ${{ github.sha }}\n",
                "          SOURCE_COMMIT: ${{ github.sha }}\n"
                "          AWS_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}\n",
                1,
            ),
            lambda text: text.replace(
                PUBLIC_SMOKE_STEP,
                PUBLIC_SMOKE_STEP.replace(
                    "        run:",
                    "        env:\n"
                    "          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}\n"
                    "        run:",
                ),
            ),
            lambda text: text.replace(
                "          R2_PUBLIC_URL: ${{ vars.R2_PUBLIC_URL }}\n",
                "          AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}\n",
            ),
            lambda text: text.replace(
                PUBLIC_SMOKE_STEP + COMPLETION_CREATE_STEP,
                COMPLETION_CREATE_STEP + PUBLIC_SMOKE_STEP,
            ),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    workflow = root / ".github/workflows/publish.yml"
                    workflow.write_text(
                        mutate(workflow.read_text(encoding="utf-8")),
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("COMPLETION_RECORD_WORKFLOW", codes)

    def test_target_checks_reject_real_extra_factory_and_ignore_non_code(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8") + "\nandroidNativeArm64()\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("TARGET_SET", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8") +
                "\n// androidNativeArm64()\nval example = \"jvm()\"\n",
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

    def test_maven_local_in_applied_production_script_is_reported(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            write(root, "gradle/repositories.gradle.kts", "repositories { mavenLocal() }\n")
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("MAVEN_LOCAL", codes)

    def test_version_inputs_and_literals_cover_all_production_surfaces(self) -> None:
        cases = (
            (".github/workflows/ci.yml", "VERSION_NAME=0.2.0\n", "DUPLICATE_VERSION_INPUT"),
            (
                "consumer-smoke/src/commonMain/kotlin/Version.kt",
                "val coordinate = \"com.rohittp.reng:kmp:0.2.0\"\n",
                "HARDCODED_RENG_VERSION",
            ),
            (
                "gradle/libs.versions.toml",
                "\nreng-kmp = { module = \"com.rohittp.reng:kmp\", version = \"0.2.0\" }\n",
                "HARDCODED_RENG_VERSION",
            ),
            (
                "gradle/libs.versions.toml",
                "\nreng-kmp = { group = \"com.rohittp.reng\", name = \"kmp\", version = \"0.2.0\" }\n",
                "HARDCODED_RENG_VERSION",
            ),
            (
                "gradle/libs.versions.toml",
                "\nreng-kmp = { group = \"com.rohittp.reng\", name = \"kmp\", version.ref = \"reng\" }\n",
                "FORBIDDEN_CYCLE_B_DEPENDENCY",
            ),
            (
                "gradle/libs.versions.toml",
                "\nother-kmp = { group = \"example.org\", name = \"kmp\", version = \"0.2.0\" }\n",
                "FORBIDDEN_CYCLE_B_DEPENDENCY",
            ),
            (
                "consumer-smoke/src/commonMain/kotlin/Comment.kt",
                "// com.rohittp.reng:kmp:0.2.0\n",
                None,
            ),
        )
        for relative, mutation, expected in cases:
            with self.subTest(expected=expected):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(
                        (path.read_text(encoding="utf-8") if path.exists() else "") + mutation,
                        encoding="utf-8",
                    )
                    codes = {violation.code for violation in check_repository(root)}
                    if expected is None:
                        self.assertEqual(set(), codes)
                    else:
                        self.assertIn(expected, codes)

    def test_dependency_checks_ignore_literals_and_toml_comments(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8").replace(
                    "implementation(libs.rentile.kmp)",
                    "val example = \"implementation(libs.rentile.kmp)\"",
                ),
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            build = root / "kmp/build.gradle.kts"
            build.write_text(
                build.read_text(encoding="utf-8") +
                "\nval dependency = libs.rentile.kmp\napi(dependency)\n",
                encoding="utf-8",
            )
            codes = {violation.code for violation in check_repository(root)}
            self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        forbidden_shapes = (
            "\nval serializationPlugin = kotlin(\"plugin.serialization\")\n",
            "\nadd(\"commonMainImplementation\", \"io.ktor:ktor-client-core:1\")\n",
        )
        for mutation in forbidden_shapes:
            with self.subTest(mutation=mutation):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    build = root / "kmp/build.gradle.kts"
                    build.write_text(build.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("FORBIDDEN_CYCLE_B_DEPENDENCY", codes)

        with TemporaryDirectory() as directory:
            root = Path(directory)
            create_clean_fixture(root)
            catalog = root / "gradle/libs.versions.toml"
            catalog.write_text(
                catalog.read_text(encoding="utf-8") +
                "\n# implementation(\"org.jetbrains.skiko:skiko:1\")\n",
                encoding="utf-8",
            )
            self.assertEqual([], check_repository(root))

    def test_docs_parser_enforces_active_resources_and_ignores_comments(self) -> None:
        cases = (
            ("docs/index.html", "\n<script src=https://cdn.example/app.js></script>\n", "DOCS_EXTERNAL_DEPENDENCY"),
            ("docs/index.html", "\n<link rel=\"preload stylesheet\" href=\"https://cdn.example/app.css\">\n", "DOCS_EXTERNAL_DEPENDENCY"),
            ("docs/index.html", "\n<!-- <script src=https://cdn.example/app.js></script> -->\n", None),
            ("docs/style.css", "\n/* @import url(https://cdn.example/app.css); */\n", None),
        )
        for relative, mutation, expected in cases:
            with self.subTest(expected=expected):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    if expected is None:
                        self.assertEqual(set(), codes)
                    else:
                        self.assertIn(expected, codes)

    def test_license_check_rejects_conflicts_on_each_public_surface(self) -> None:
        cases = (
            ("LICENSE", "\nmit\n"),
            ("README.md", "\nMIT\n"),
            ("docs/kmp.html", "\nMIT\n"),
            (
                "build.gradle.kts",
                "// name.set(\"The Apache License, Version 2.0\")\n"
                "// url.set(\"https://www.apache.org/licenses/LICENSE-2.0.txt\")\n",
            ),
        )
        for relative, mutation in cases:
            with self.subTest(relative=relative):
                with TemporaryDirectory() as directory:
                    root = Path(directory)
                    create_clean_fixture(root)
                    path = root / relative
                    if relative == "build.gradle.kts":
                        path.write_text(mutation, encoding="utf-8")
                    else:
                        path.write_text(path.read_text(encoding="utf-8") + mutation, encoding="utf-8")
                    codes = {violation.code for violation in check_repository(root)}
                    self.assertIn("LICENSE_MISMATCH", codes)


if __name__ == "__main__":
    unittest.main()
