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
    write(root, ".github/workflows/publish.yml", "-PrengVersion=\"$VERSION\"\n")
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
                None,
            ),
            (
                "gradle/libs.versions.toml",
                "\nother-kmp = { group = \"example.org\", name = \"kmp\", version = \"0.2.0\" }\n",
                None,
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
            self.assertIn("RENTILE_IMPLEMENTATION_DEPENDENCY", codes)

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
            self.assertIn("RENTILE_API_DEPENDENCY", codes)

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
                    self.assertIn("FORBIDDEN_CYCLE_A_DEPENDENCY", codes)

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
