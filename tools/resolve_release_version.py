from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys
from typing import Callable, Sequence
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
import xml.etree.ElementTree as ElementTree


_VERSION_PATTERN = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
_METADATA_PATH = "com/rohittp/reng/kmp"


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_NO_REDIRECT_OPENER = build_opener(_NoRedirectHandler())


def urlopen(request: Request):
    return _NO_REDIRECT_OPENER.open(request)


@dataclass(frozen=True, order=True)
class Version:
    major: int
    minor: int
    patch: int

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


@dataclass(frozen=True)
class HttpResponse:
    status: int
    body: bytes


class ResolutionError(Exception):
    pass


def parse_declared_version(value: str) -> Version:
    if "-SNAPSHOT" in value:
        raise ResolutionError("Snapshot versions cannot be published")

    match = _VERSION_PATTERN.fullmatch(value)
    if match is None:
        raise ResolutionError("Declared version must be a canonical stable MAJOR.MINOR.PATCH version")

    return Version(*(int(component) for component in match.groups()))


def parse_metadata_versions(document: bytes) -> tuple[Version, ...]:
    try:
        root = ElementTree.fromstring(document)
    except ElementTree.ParseError:
        raise ResolutionError("Release metadata contains malformed XML") from None

    if root.tag.rsplit("}", 1)[-1] != "metadata":
        raise ResolutionError("Release metadata has an invalid Maven metadata structure")

    versioning_elements = [
        element for element in root if element.tag.rsplit("}", 1)[-1] == "versioning"
    ]
    if len(versioning_elements) != 1:
        raise ResolutionError("Release metadata has an invalid Maven metadata structure")

    versions_elements = [
        element
        for element in versioning_elements[0]
        if element.tag.rsplit("}", 1)[-1] == "versions"
    ]
    if len(versions_elements) != 1:
        raise ResolutionError("Release metadata has an invalid Maven metadata structure")

    versions = set()
    for element in versions_elements[0]:
        if element.tag.rsplit("}", 1)[-1] != "version" or element.text is None:
            continue
        match = _VERSION_PATTERN.fullmatch(element.text.strip())
        if match is not None:
            versions.add(Version(*(int(component) for component in match.groups())))

    if not versions:
        raise ResolutionError("Release metadata has no parseable stable versions")

    return tuple(sorted(versions))


def read_declared_version(properties_file: Path) -> str:
    try:
        content = properties_file.read_text(encoding="utf-8")
    except OSError:
        raise ResolutionError("Unable to read declared version properties file") from None

    values = []
    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            continue
        match = re.fullmatch(r"VERSION_NAME\s*[=:]\s*(.*)", stripped)
        if match is not None:
            values.append(match.group(1).strip())

    if len(values) != 1:
        raise ResolutionError("Properties file must declare exactly one VERSION_NAME")

    return values[0]


def request_http(method: str, url: str) -> HttpResponse:
    try:
        request = Request(url, method=method)
        with urlopen(request) as response:
            return HttpResponse(response.status, response.read())
    except HTTPError as error:
        try:
            body = error.read()
        finally:
            error.close()
        return HttpResponse(error.code, body)
    except (URLError, OSError, ValueError):
        raise ResolutionError(f"Transport failure during {method}") from None


def select_candidate(declared: Version, published: Sequence[Version]) -> Version:
    if not published:
        return declared
    latest = max(published)
    if declared > latest:
        return declared
    return Version(latest.major, latest.minor, latest.patch + 1)


def resolve_release_version(
    declared: Version,
    repository_url: str,
    request: Callable[[str, str], HttpResponse] | None = None,
) -> Version:
    base_url = f"{repository_url.rstrip('/')}/{_METADATA_PATH}"
    requester = request_http if request is None else request

    metadata_response = requester("GET", f"{base_url}/maven-metadata.xml")
    if metadata_response.status == 200:
        published = parse_metadata_versions(metadata_response.body)
    elif metadata_response.status == 404:
        published = ()
    else:
        raise ResolutionError(
            f"Unexpected status {metadata_response.status} fetching release metadata"
        )

    if published:
        latest = max(published)
        if declared <= latest:
            latest_text = str(latest)
            completion_url = f"{base_url}/{latest_text}/kmp-{latest_text}.pom"
            completion_response = requester("HEAD", completion_url)
            if completion_response.status != 200:
                raise ResolutionError(
                    "Newest metadata-listed release lacks an aggregate POM completion "
                    f"witness: {latest_text} (status {completion_response.status})"
                )

    candidate = select_candidate(declared, published)
    candidate_text = str(candidate)
    pom_url = f"{base_url}/{candidate_text}/kmp-{candidate_text}.pom"
    candidate_response = requester("HEAD", pom_url)
    if candidate_response.status == 404:
        return candidate
    if candidate_response.status == 200:
        raise ResolutionError(
            f"Selected release candidate is already occupied: {candidate_text}"
        )
    raise ResolutionError(
        f"Unexpected status {candidate_response.status} probing release candidate"
    )


class _ArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise ResolutionError(f"Invalid command-line arguments: {message}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = _ArgumentParser()
    parser.add_argument("--properties-file", required=True, type=Path)
    parser.add_argument("--repository-url", required=True)

    try:
        arguments = parser.parse_args(argv)
        declared = parse_declared_version(read_declared_version(arguments.properties_file))
        resolved = resolve_release_version(declared, arguments.repository_url)
    except ResolutionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(resolved)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
