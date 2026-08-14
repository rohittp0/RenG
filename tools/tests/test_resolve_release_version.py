from __future__ import annotations

import io
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, call, patch
from urllib.error import URLError

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
            side_effect=[HttpResponse(200, metadata), HttpResponse(200, b"")]
        )
        with self.assertRaisesRegex(
            ResolutionError, "Selected release candidate is already occupied: 1.2.4"
        ):
            resolve_release_version(Version(1, 2, 3), REPOSITORY_URL, request=requester)
        self.assertEqual(2, requester.call_count)
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

    def test_cli_rejects_an_invalid_repository_url(self) -> None:
        with TemporaryDirectory() as directory:
            properties = Path(directory) / "gradle.properties"
            properties.write_text("VERSION_NAME=0.1.0\n", encoding="utf-8")
            stdout, stderr = io.StringIO(), io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                result = main([
                    "--properties-file", str(properties),
                    "--repository-url", "not-a-url",
                ])
        self.assertEqual(1, result)
        self.assertEqual("", stdout.getvalue())
        self.assertIn("error:", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
