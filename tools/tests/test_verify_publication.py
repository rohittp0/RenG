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
SIGNATURE = "-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n"


class VerifyPublicationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = TemporaryDirectory()
        self.repository = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_publication(self, artifact: str, *, signed: bool = True) -> Path:
        directory = self.repository / "com/rohittp/reng" / artifact / "0.1.0"
        directory.mkdir(parents=True)
        pom = directory / f"{artifact}-0.1.0.pom"
        pom.write_text(POM.format(artifact=artifact), encoding="utf-8")
        (directory / f"{artifact}-0.1.0.module").write_text("{}\n", encoding="utf-8")
        if signed:
            Path(f"{pom}.asc").write_text(SIGNATURE, encoding="utf-8")
        return pom

    def write_all(self) -> None:
        for artifact in EXPECTED_ARTIFACTS:
            self.write_publication(artifact)

    def test_local_manifest_is_sorted_and_version_scoped(self) -> None:
        self.write_all()
        other = self.repository / "com/rohittp/reng/kmp/0.2.0/ignored.pom"
        other.parent.mkdir(parents=True)
        other.write_text("ignored", encoding="utf-8")
        manifest = discover_local_manifest(self.repository, VERSION, True)
        self.assertEqual(tuple(sorted(manifest.entries)), manifest.entries)
        self.assertTrue(all("/0.1.0/" in entry for entry in manifest.entries))
        self.assertEqual(manifest.serialize(), "".join(f"{entry}\n" for entry in manifest.entries))

    def test_local_verification_rejects_missing_or_extra_publication(self) -> None:
        for artifact in EXPECTED_ARTIFACTS - {"kmp-linuxarm64"}:
            self.write_publication(artifact)
        with self.assertRaisesRegex(VerificationError, "missing.*kmp-linuxarm64"):
            discover_local_manifest(self.repository, VERSION, True)
        self.write_publication("kmp-linuxarm64")
        self.write_publication("kmp-jvm")
        with self.assertRaisesRegex(VerificationError, "unexpected.*kmp-jvm"):
            discover_local_manifest(self.repository, VERSION, True)

    def test_signed_mode_requires_armored_pom_signatures(self) -> None:
        self.write_all()
        signature = next(self.repository.rglob("*.pom.asc"))
        signature.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(VerificationError, "signature"):
            discover_local_manifest(self.repository, VERSION, True)

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
            discover_local_manifest(self.repository, VERSION, False)

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
            subprocess.CompletedProcess([], 0, '{"KeyCount":0,"Contents":null}', ""),
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
