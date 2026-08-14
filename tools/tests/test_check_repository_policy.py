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
""")
    write(root, "docs/kmp.html", """
<link rel="canonical" href="https://rohittp.com/reng/kmp.html">
<link rel="stylesheet" href="style.css">
<script defer src="versions.js"></script>
<span data-maven-version="kmp">latest</span>
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


if __name__ == "__main__":
    unittest.main()
