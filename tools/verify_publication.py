from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import math
from pathlib import Path
import stat
import subprocess
import sys
import time
from typing import Callable, Sequence
import xml.etree.ElementTree as ElementTree

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.release_completion import (
    CompletionRecord,
    CompletionRecordError,
    completion_record_key,
)
from tools.resolve_release_version import (
    HttpResponse,
    ResolutionError,
    Version,
    parse_declared_version,
    parse_metadata_versions,
    request_http,
)


EXPECTED_ARTIFACTS = frozenset({
    "kmp",
    "kmp-android",
    "kmp-iosarm64",
    "kmp-iossimulatorarm64",
    "kmp-macosarm64",
    "kmp-linuxx64",
    "kmp-linuxarm64",
})
_GROUP_PATH = ("com", "rohittp", "reng")
_PROJECT_URL = "https://rohittp.com/reng/"
_LICENSE_NAME = "The Apache License, Version 2.0"
_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt"
_SCM_URL = "https://github.com/rohittp0/RenG"
_METADATA_KEY = "com/rohittp/reng/kmp/maven-metadata.xml"


class VerificationError(RuntimeError):
    pass


@dataclass(frozen=True)
class Manifest:
    entries: tuple[str, ...]

    def __post_init__(self) -> None:
        _validate_manifest_entries(self.entries)

    @staticmethod
    def parse(text: str, version: Version) -> Manifest:
        if not isinstance(text, str) or not text.endswith("\n"):
            raise VerificationError("Manifest must have one trailing newline")
        entries = tuple(text[:-1].split("\n"))
        manifest = Manifest(entries)
        version_text = str(version)
        for entry in manifest.entries:
            if entry.split("/")[4] != version_text:
                raise VerificationError("Manifest entry names another version")
        return manifest

    def serialize(self) -> str:
        return "".join(f"{entry}\n" for entry in self.entries)


def _validate_manifest_entries(entries: tuple[str, ...]) -> None:
    if not isinstance(entries, tuple) or not entries:
        raise VerificationError("Manifest must contain at least one entry")
    if len(set(entries)) != len(entries):
        raise VerificationError("Manifest contains duplicate entries")
    if tuple(sorted(entries)) != entries:
        raise VerificationError("Manifest entries must be sorted")

    for entry in entries:
        if not isinstance(entry, str) or not entry:
            raise VerificationError("Manifest contains a blank entry")
        if "\n" in entry or "\r" in entry or "\\" in entry or entry.startswith("/"):
            raise VerificationError("Manifest contains an invalid path")
        parts = entry.split("/")
        if (
            len(parts) < 6
            or tuple(parts[:3]) != _GROUP_PATH
            or any(part in {"", ".", ".."} for part in parts)
        ):
            raise VerificationError("Manifest entry is outside the publication path")
        try:
            parse_declared_version(parts[4])
        except ResolutionError:
            raise VerificationError("Manifest entry has an invalid version") from None


def _publication_root(repository: Path) -> Path:
    return repository.joinpath(*_GROUP_PATH)


def _require_regular_file(path: Path, description: str) -> None:
    try:
        mode = path.lstat().st_mode
    except OSError:
        raise VerificationError(f"Unable to read {description}") from None
    if not stat.S_ISREG(mode):
        raise VerificationError(f"{description} must be a regular file")


def _local_name(element: ElementTree.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def _single_child(parent: ElementTree.Element, name: str, description: str) -> ElementTree.Element:
    matches = [child for child in parent if _local_name(child) == name]
    if len(matches) != 1:
        raise VerificationError(f"POM must contain exactly one {description}")
    return matches[0]


def _required_text(parent: ElementTree.Element, name: str, description: str) -> str:
    element = _single_child(parent, name, description)
    if element.text is None:
        raise VerificationError(f"POM {description} is empty")
    value = element.text.strip()
    if not value:
        raise VerificationError(f"POM {description} is empty")
    return value


def _require_pom_value(parent: ElementTree.Element, name: str, description: str, expected: str) -> None:
    if _required_text(parent, name, description) != expected:
        raise VerificationError(f"POM {description} is not canonical")


def _verify_pom(path: Path, artifact: str, version: Version) -> None:
    _require_regular_file(path, "POM")
    try:
        root = ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError):
        raise VerificationError("POM XML is malformed") from None
    if _local_name(root) != "project":
        raise VerificationError("POM XML has an invalid project root")

    _require_pom_value(root, "groupId", "groupId", "com.rohittp.reng")
    _require_pom_value(root, "artifactId", "artifactId", artifact)
    _require_pom_value(root, "version", "version", str(version))
    _require_pom_value(root, "url", "project URL", _PROJECT_URL)

    licenses = _single_child(root, "licenses", "licenses")
    license_element = _single_child(licenses, "license", "license")
    _require_pom_value(license_element, "name", "license name", _LICENSE_NAME)
    _require_pom_value(license_element, "url", "license URL", _LICENSE_URL)

    scm = _single_child(root, "scm", "scm")
    _require_pom_value(scm, "url", "SCM URL", _SCM_URL)


def _discover_publication_files(directory: Path, repository: Path) -> tuple[str, ...]:
    entries: list[str] = []
    try:
        paths = tuple(directory.rglob("*"))
    except OSError:
        raise VerificationError("Unable to read local publication directory") from None
    for path in paths:
        try:
            mode = path.lstat().st_mode
        except OSError:
            raise VerificationError("Unable to read local publication entry") from None
        if stat.S_ISDIR(mode):
            continue
        if not stat.S_ISREG(mode):
            raise VerificationError("Local publication contains a non-regular file")
        try:
            entries.append(path.relative_to(repository).as_posix())
        except ValueError:
            raise VerificationError("Local publication entry is outside the repository") from None
    return tuple(sorted(entries))


def discover_local_manifest(repository: Path, version: Version) -> Manifest:
    root = _publication_root(repository)
    try:
        root_mode = root.lstat().st_mode
    except OSError:
        raise VerificationError("Local publication root is unavailable") from None
    if not stat.S_ISDIR(root_mode):
        raise VerificationError("Local publication root is unavailable")

    try:
        artifact_paths = tuple(root.iterdir())
    except OSError:
        raise VerificationError("Unable to read local publication root") from None
    for path in artifact_paths:
        try:
            if not stat.S_ISDIR(path.lstat().st_mode):
                raise VerificationError("Local publication root contains a non-directory entry")
        except OSError:
            raise VerificationError("Unable to read local publication artifact") from None

    artifacts = {path.name for path in artifact_paths}
    missing = sorted(EXPECTED_ARTIFACTS - artifacts)
    unexpected = sorted(artifacts - EXPECTED_ARTIFACTS)
    if missing:
        raise VerificationError(f"Local publication is missing artifacts: {', '.join(missing)}")
    if unexpected:
        raise VerificationError(f"Local publication has unexpected artifacts: {', '.join(unexpected)}")

    entries: list[str] = []
    for artifact in sorted(EXPECTED_ARTIFACTS):
        directory = root / artifact / str(version)
        try:
            directory_mode = directory.lstat().st_mode
        except OSError:
            raise VerificationError(f"Local publication is missing version {version} for {artifact}") from None
        if not stat.S_ISDIR(directory_mode):
            raise VerificationError(f"Local publication is missing version {version} for {artifact}")

        pom = directory / f"{artifact}-{version}.pom"
        _verify_pom(pom, artifact, version)
        entries.extend(_discover_publication_files(directory, repository))

    return Manifest.parse("".join(f"{entry}\n" for entry in sorted(entries)), version)


def read_manifest(path: Path, version: Version) -> Manifest:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        raise VerificationError("Unable to read manifest") from None
    return Manifest.parse(text, version)


def create_completion_record(
    manifest: Manifest,
    version: Version,
    source_commit: str,
) -> CompletionRecord:
    validated = Manifest.parse(manifest.serialize(), version)
    try:
        return CompletionRecord.create(
            str(version),
            source_commit,
            validated.serialize().encode("utf-8"),
        )
    except CompletionRecordError:
        raise VerificationError("Unable to create release completion record") from None


def _validate_aws_response(key: str, completed: object) -> None:
    if getattr(completed, "returncode", None) != 0:
        raise VerificationError("AWS list-objects-v2 command failed")
    stdout = getattr(completed, "stdout", None)
    if not isinstance(stdout, str):
        raise VerificationError("AWS list-objects-v2 output is malformed")
    try:
        response = json.loads(stdout)
    except json.JSONDecodeError:
        raise VerificationError("AWS list-objects-v2 output is malformed") from None
    if not isinstance(response, dict):
        raise VerificationError("AWS list-objects-v2 output is malformed")

    key_count = response.get("KeyCount")
    if isinstance(key_count, bool) or not isinstance(key_count, int) or key_count < 0:
        raise VerificationError("AWS KeyCount is malformed")
    contents = response.get("Contents", None)
    if key_count == 0:
        if "Contents" in response and response["Contents"] != []:
            raise VerificationError("AWS KeyCount and Contents are inconsistent")
        return
    if not isinstance(contents, list) or len(contents) != key_count:
        raise VerificationError("AWS KeyCount and Contents are inconsistent")

    for item in contents:
        if not isinstance(item, dict) or not isinstance(item.get("Key"), str) or not item["Key"]:
            raise VerificationError("AWS object key is malformed")
        if item["Key"] == key:
            raise VerificationError(f"R2 object already exists: {key}")


def check_r2_collisions(
    manifest: Manifest,
    endpoint: str,
    bucket: str,
    run: Callable = subprocess.run,
) -> None:
    if not isinstance(endpoint, str) or not endpoint or not isinstance(bucket, str) or not bucket:
        raise VerificationError("R2 endpoint and bucket are required")
    for key in manifest.entries:
        command = [
            "aws",
            "--endpoint-url",
            endpoint,
            "s3api",
            "list-objects-v2",
            "--bucket",
            bucket,
            "--prefix",
            key,
            "--output",
            "json",
        ]
        try:
            completed = run(command, capture_output=True, text=True, check=False)
        except (OSError, subprocess.SubprocessError):
            raise VerificationError("AWS list-objects-v2 command failed") from None
        _validate_aws_response(key, completed)


def _validate_retry_options(attempts: int, retry_delay: float) -> None:
    if isinstance(attempts, bool) or not isinstance(attempts, int) or attempts < 1:
        raise VerificationError("Public verification attempts must be a positive integer")
    if (
        isinstance(retry_delay, bool)
        or not isinstance(retry_delay, (int, float))
        or not math.isfinite(retry_delay)
        or retry_delay < 0
    ):
        raise VerificationError("Public verification retry delay must be nonnegative")


def _fetch_after_retries(
    key: str,
    repository_url: str,
    fetch: Callable[[str], HttpResponse],
    attempts: int,
    retry_delay: float,
    sleep: Callable,
) -> HttpResponse:
    url = f"{repository_url.rstrip('/')}/{key}"
    for attempt in range(1, attempts + 1):
        try:
            response = fetch(url)
        except Exception:
            response = None
        if (
            isinstance(response, HttpResponse)
            and isinstance(response.status, int)
            and not isinstance(response.status, bool)
            and response.status == 200
            and isinstance(response.body, bytes)
        ):
            return response
        if attempt < attempts:
            sleep(retry_delay)
    raise VerificationError(f"Public verification failed for {key} after {attempts} attempts")


def _verify_metadata_after_retries(
    repository_url: str,
    version: Version,
    fetch: Callable[[str], HttpResponse],
    attempts: int,
    retry_delay: float,
    sleep: Callable,
) -> None:
    url = f"{repository_url.rstrip('/')}/{_METADATA_KEY}"
    failure = f"Public verification failed for {_METADATA_KEY}"
    for attempt in range(1, attempts + 1):
        try:
            response = fetch(url)
        except Exception:
            response = None
        if (
            isinstance(response, HttpResponse)
            and isinstance(response.status, int)
            and not isinstance(response.status, bool)
            and response.status == 200
            and isinstance(response.body, bytes)
        ):
            try:
                versions = parse_metadata_versions(response.body)
            except ResolutionError:
                failure = "Public Maven metadata is malformed"
            else:
                if version in versions:
                    return
                failure = f"Public Maven metadata does not list {version}"
        if attempt < attempts:
            sleep(retry_delay)
    raise VerificationError(f"{failure} after {attempts} attempts")


def verify_public(
    manifest: Manifest,
    repository_url: str,
    version: Version,
    fetch: Callable[[str], HttpResponse],
    attempts: int,
    retry_delay: float,
    sleep: Callable = time.sleep,
) -> None:
    if not isinstance(repository_url, str) or not repository_url:
        raise VerificationError("Public repository URL is required")
    _validate_retry_options(attempts, retry_delay)
    validated = Manifest.parse(manifest.serialize(), version)

    for key in validated.entries:
        _fetch_after_retries(key, repository_url, fetch, attempts, retry_delay, sleep)
    _verify_metadata_after_retries(
        repository_url, version, fetch, attempts, retry_delay, sleep
    )


def verify_public_completion(
    manifest: Manifest,
    repository_url: str,
    version: Version,
    source_commit: str,
    fetch: Callable[[str], HttpResponse],
    attempts: int,
    retry_delay: float,
    sleep: Callable = time.sleep,
) -> None:
    if not isinstance(repository_url, str) or not repository_url:
        raise VerificationError("Public repository URL is required")
    _validate_retry_options(attempts, retry_delay)
    expected = create_completion_record(manifest, version, source_commit)
    key = completion_record_key(str(version))
    url = f"{repository_url.rstrip('/')}/{key}"

    for attempt in range(1, attempts + 1):
        try:
            response = fetch(url)
        except Exception:
            response = None
        if (
            isinstance(response, HttpResponse)
            and isinstance(response.status, int)
            and not isinstance(response.status, bool)
            and response.status == 200
            and isinstance(response.body, bytes)
        ):
            try:
                actual = CompletionRecord.parse(response.body, str(version))
            except CompletionRecordError:
                actual = None
            if actual == expected:
                return
        if attempt < attempts:
            sleep(retry_delay)
    raise VerificationError(
        f"Public completion record verification failed after {attempts} attempts"
    )


class _ArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise VerificationError(f"Invalid command-line arguments: {message}")


def _public_fetch(url: str) -> HttpResponse:
    return request_http("GET", url)


def main(argv: Sequence[str] | None = None) -> int:
    parser = _ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)

    local = commands.add_parser("local")
    local.add_argument("--repository", required=True, type=Path)
    local.add_argument("--version", required=True)
    local.add_argument("--manifest", required=True, type=Path)

    r2 = commands.add_parser("r2-preflight")
    r2.add_argument("--endpoint", required=True)
    r2.add_argument("--bucket", required=True)
    r2.add_argument("--version", required=True)
    r2.add_argument("--manifest", required=True, type=Path)

    public = commands.add_parser("public")
    public.add_argument("--repository-url", required=True)
    public.add_argument("--version", required=True)
    public.add_argument("--manifest", required=True, type=Path)
    public.add_argument("--attempts", type=int, default=12)
    public.add_argument("--retry-delay", type=float, default=5)

    completion_create = commands.add_parser("completion-create")
    completion_create.add_argument("--version", required=True)
    completion_create.add_argument("--manifest", required=True, type=Path)
    completion_create.add_argument("--source-commit", required=True)
    completion_create.add_argument("--output", required=True, type=Path)

    completion_public = commands.add_parser("completion-public")
    completion_public.add_argument("--repository-url", required=True)
    completion_public.add_argument("--version", required=True)
    completion_public.add_argument("--manifest", required=True, type=Path)
    completion_public.add_argument("--source-commit", required=True)
    completion_public.add_argument("--attempts", type=int, default=12)
    completion_public.add_argument("--retry-delay", type=float, default=5)

    try:
        arguments = parser.parse_args(argv)
        version = parse_declared_version(arguments.version)
        if arguments.command == "local":
            manifest = discover_local_manifest(arguments.repository, version)
            arguments.manifest.write_text(manifest.serialize(), encoding="utf-8")
        elif arguments.command == "r2-preflight":
            manifest = read_manifest(arguments.manifest, version)
            check_r2_collisions(manifest, arguments.endpoint, arguments.bucket)
        elif arguments.command == "public":
            manifest = read_manifest(arguments.manifest, version)
            verify_public(
                manifest,
                arguments.repository_url,
                version,
                _public_fetch,
                arguments.attempts,
                arguments.retry_delay,
            )
        elif arguments.command == "completion-create":
            manifest = read_manifest(arguments.manifest, version)
            record = create_completion_record(
                manifest, version, arguments.source_commit
            )
            arguments.output.write_bytes(record.serialize())
            print(completion_record_key(str(version)))
        else:
            manifest = read_manifest(arguments.manifest, version)
            verify_public_completion(
                manifest,
                arguments.repository_url,
                version,
                arguments.source_commit,
                _public_fetch,
                arguments.attempts,
                arguments.retry_delay,
            )
    except (VerificationError, ResolutionError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
