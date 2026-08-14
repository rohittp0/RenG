from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
from typing import Iterable, Sequence


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


_ROOT_GRADLE_SCRIPTS = ("build.gradle.kts", "settings.gradle.kts")
_MODULE_GRADLE_SCRIPTS = (
    "kmp/build.gradle.kts",
    "consumer-smoke/build.gradle.kts",
    "consumer-smoke/settings.gradle.kts",
)
_REQUIRED_DOCS = (
    ".nojekyll", "index.html", "kmp.html", "style.css", "versions.js",
    "robots.txt", "sitemap.xml", "llms.txt",
)
_VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
_STABLE_VERSION = re.compile(rf"^{_VERSION_PATTERN}$")
_VERSION_ASSIGNMENT = re.compile(r"^\s*VERSION_NAME\s*[=:]\s*(?P<value>.*?)\s*$")
_RENG_COORDINATE_VERSION = re.compile(
    rf"com\.rohittp\.reng:kmp:(?:v?{_VERSION_PATTERN})(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?"
)
_RENG_VERSION_LITERAL = re.compile(
    rf"\brengVersion\b\s*(?:=|:)\s*[\"']?(?:v?{_VERSION_PATTERN})(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?",
    re.IGNORECASE,
)
_KNOWN_TARGETS = frozenset({
    "android", "androidTarget", "iosArm32", "iosArm64", "iosX64",
    "iosSimulatorArm64", "iosSimulatorX64", "jvm", "js", "linuxArm64",
    "linuxX64", "macosArm64", "macosX64", "mingwX64", "tvosArm64",
    "tvosSimulatorArm64", "tvosX64", "wasmJs", "wasmWasi", "watchosArm32",
    "watchosArm64", "watchosDeviceArm64", "watchosSimulatorArm64", "watchosX64",
})
_FORBIDDEN_DEPENDENCY = re.compile(r"\b(?:wire|serialization|skiko|ktor|corpus)\b", re.IGNORECASE)
_EXTERNAL_RESOURCE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:|^//")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _line(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _violation(code: str, path: Path, line: int, message: str) -> Violation:
    return Violation(code=code, path=path, line=line, message=message)


def _production_gradle_scripts(root: Path) -> tuple[Path, ...]:
    paths = [root / relative for relative in _ROOT_GRADLE_SCRIPTS + _MODULE_GRADLE_SCRIPTS]
    return tuple(path for path in paths if path.is_file())


def _top_level_docs(root: Path) -> tuple[Path, ...]:
    docs = root / "docs"
    if not docs.is_dir():
        return ()
    return tuple(sorted((path for path in docs.iterdir() if path.is_file()), key=lambda path: path.name))


def _without_comments(text: str) -> str:
    result = list(text)
    index = 0
    state = "code"
    while index < len(text):
        if state == "code" and text.startswith("//", index):
            end = text.find("\n", index)
            if end == -1:
                end = len(text)
            for position in range(index, end):
                result[position] = " "
            index = end
            continue
        if state == "code" and text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = len(text) if end == -1 else end + 2
            for position in range(index, end):
                if result[position] != "\n":
                    result[position] = " "
            index = end
            continue
        if state == "code" and text.startswith('"""', index):
            state = "triple"
            index += 3
            continue
        if state == "triple" and text.startswith('"""', index):
            state = "code"
            index += 3
            continue
        if state == "code" and text[index] in {'\"', "'"}:
            state = text[index]
        elif state in {'\"', "'"} and text[index] == "\\":
            index += 2
            continue
        elif state in {'\"', "'"} and text[index] == state:
            state = "code"
        index += 1
    return "".join(result)


def check_maven_local(root: Path) -> list[Violation]:
    violations = []
    pattern = re.compile(r"\bmavenLocal\s*\(\s*\)")
    for path in _production_gradle_scripts(root):
        text = _read(path)
        match = pattern.search(_without_comments(text))
        if match is not None:
            violations.append(_violation(
                "MAVEN_LOCAL", path, _line(text, match.start()),
                "production Gradle scripts must not use mavenLocal()",
            ))
    return violations


def _version_input_files(root: Path) -> tuple[Path, ...]:
    ignored_parts = {".git", ".gradle", "build", ".superpowers"}
    return tuple(sorted(
        (
            path for path in root.rglob("*.properties")
            if not any(part in ignored_parts for part in path.relative_to(root).parts)
        ),
        key=lambda path: path.as_posix(),
    ))


def check_version_inputs(root: Path) -> list[Violation]:
    root_properties = root / "gradle.properties"
    assignments: list[tuple[Path, int, str]] = []
    for path in _version_input_files(root):
        for number, source_line in enumerate(_read(path).splitlines(), start=1):
            match = _VERSION_ASSIGNMENT.match(source_line)
            if match is not None:
                assignments.append((path, number, match.group("value")))

    violations = []
    root_assignments = [item for item in assignments if item[0] == root_properties]
    if len(root_assignments) != 1:
        violations.append(_violation(
            "VERSION_NAME_INPUT", root_properties, 1,
            "root gradle.properties must declare exactly one VERSION_NAME",
        ))
    for _, line, value in root_assignments:
        if "-SNAPSHOT" in value:
            violations.append(_violation(
                "RENG_SNAPSHOT", root_properties, line,
                "VERSION_NAME must not be a snapshot version",
            ))
        elif len(root_assignments) == 1 and _STABLE_VERSION.fullmatch(value) is None:
            violations.append(_violation(
                "VERSION_NAME_INPUT", root_properties, line,
                "VERSION_NAME must be a stable MAJOR.MINOR.PATCH version",
            ))

    for path, line, _ in assignments:
        if path != root_properties:
            violations.append(_violation(
                "DUPLICATE_VERSION_INPUT", path, line,
                "VERSION_NAME may only be assigned in root gradle.properties",
            ))
    return violations


def _target_occurrences(text: str, target: str) -> tuple[re.Match[str], ...]:
    if target == "android":
        pattern = re.compile(r"\bandroid\s*(?:\(\s*\))?\s*\{")
    else:
        pattern = re.compile(rf"\b{re.escape(target)}\s*\(")
    return tuple(pattern.finditer(text))


def check_targets(root: Path) -> list[Violation]:
    violations = []
    for relative in ("kmp/build.gradle.kts", "consumer-smoke/build.gradle.kts"):
        path = root / relative
        if not path.is_file():
            violations.append(_violation(
                "TARGET_SET", path, 1,
                "required target build script is missing",
            ))
            continue
        text = _read(path)
        source = _without_comments(text)
        counts = {target: len(_target_occurrences(source, target)) for target in EXPECTED_TARGETS}
        unexpected = [
            target for target in _KNOWN_TARGETS - EXPECTED_TARGETS
            if _target_occurrences(source, target)
        ]
        if any(count != 1 for count in counts.values()) or unexpected:
            first_offset = 0
            for target in tuple(sorted(EXPECTED_TARGETS)) + tuple(sorted(unexpected)):
                matches = _target_occurrences(source, target)
                if matches:
                    first_offset = matches[0].start()
                    break
            violations.append(_violation(
                "TARGET_SET", path, _line(text, first_offset),
                "must declare exactly android, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, and linuxArm64",
            ))
    return violations


def _first_match(text: str, patterns: Iterable[re.Pattern[str]]) -> re.Match[str] | None:
    matches = [match for pattern in patterns if (match := pattern.search(text)) is not None]
    return min(matches, key=lambda match: match.start()) if matches else None


def check_dependencies(root: Path) -> list[Violation]:
    path = root / "kmp/build.gradle.kts"
    if not path.is_file():
        return [_violation(
            "RENTILE_IMPLEMENTATION_DEPENDENCY", path, 1,
            "KMP build script must declare implementation(libs.rentile.kmp)",
        )]

    text = _read(path)
    source = _without_comments(text)
    violations = []
    implementation = re.compile(r"\bimplementation\s*\(\s*libs\.rentile\.kmp\s*\)")
    if implementation.search(source) is None:
        violations.append(_violation(
            "RENTILE_IMPLEMENTATION_DEPENDENCY", path, 1,
            "KMP build script must declare implementation(libs.rentile.kmp)",
        ))

    rentile_api = re.compile(
        r"\bapi\s*\(\s*(?:libs\.rentile\.kmp|[\"'][^\"']*com\.rohittp\.rentile:[^\"']*[\"'])"
    )
    api_match = rentile_api.search(source)
    if api_match is not None:
        violations.append(_violation(
            "RENTILE_API_DEPENDENCY", path, _line(text, api_match.start()),
            "Rentile must remain an implementation dependency",
        ))

    catalog = root / "gradle/libs.versions.toml"
    for candidate in (path, catalog):
        if not candidate.is_file():
            continue
        candidate_text = _read(candidate)
        match = _FORBIDDEN_DEPENDENCY.search(_without_comments(candidate_text))
        if match is not None:
            violations.append(_violation(
                "FORBIDDEN_CYCLE_A_DEPENDENCY", candidate, _line(candidate_text, match.start()),
                "Cycle A must not declare Wire, serialization, Skiko, Ktor, or corpus dependencies or plugins",
            ))
    return violations


def check_abi(root: Path) -> list[Violation]:
    api_directory = root / "kmp/api"
    api_file = api_directory / "kmp.klib.api"
    violations = []
    if not api_file.is_file():
        violations.append(_violation(
            "KLIB_ABI", api_file, 1,
            "Cycle A requires kmp/api/kmp.klib.api",
        ))
    else:
        text = _read(api_file)
        rentile = re.search(r"com\.rohittp\.rentile", text)
        if rentile is not None:
            violations.append(_violation(
                "ABI_RENTILE_LEAK", api_file, _line(text, rentile.start()),
                "Cycle A ABI must not expose Rentile types",
            ))
        for number, source_line in enumerate(text.splitlines(), start=1):
            if source_line.strip() and not source_line.lstrip().startswith("//"):
                violations.append(_violation(
                    "CYCLE_A_PUBLIC_ABI", api_file, number,
                    "Cycle A ABI must contain comments only",
                ))
                break

    if api_directory.is_dir():
        for path in sorted(api_directory.glob("jvm*"), key=lambda item: item.as_posix()):
            violations.append(_violation(
                "JVM_ABI", path, 1,
                "Cycle A must not publish a JVM ABI dump",
            ))
    return violations


def _public_version_files(root: Path) -> tuple[Path, ...]:
    paths = [root / "README.md", root / "gradle/libs.versions.toml"]
    paths.extend(_production_gradle_scripts(root))
    paths.extend(_top_level_docs(root))
    workflows = root / ".github/workflows"
    if workflows.is_dir():
        paths.extend(sorted(workflows.glob("*.yml"), key=lambda path: path.as_posix()))
        paths.extend(sorted(workflows.glob("*.yaml"), key=lambda path: path.as_posix()))
    return tuple(sorted({path for path in paths if path.is_file()}, key=lambda path: path.as_posix()))


def check_public_version_literals(root: Path) -> list[Violation]:
    violations = []
    for path in _public_version_files(root):
        text = _read(path)
        match = _first_match(text, (_RENG_COORDINATE_VERSION, _RENG_VERSION_LITERAL))
        if match is not None:
            violations.append(_violation(
                "HARDCODED_RENG_VERSION", path, _line(text, match.start()),
                "public RenG versions must remain property- or metadata-driven",
            ))
    return violations


def _html_tags(text: str, name: str) -> Iterable[re.Match[str]]:
    return re.finditer(rf"<{name}\b[^>]*>", text, re.IGNORECASE | re.DOTALL)


def _attribute(tag: str, name: str) -> str | None:
    match = re.search(rf"\b{re.escape(name)}\s*=\s*[\"']([^\"']*)[\"']", tag, re.IGNORECASE)
    return None if match is None else match.group(1)


def check_docs(root: Path) -> list[Violation]:
    docs = root / "docs"
    violations = []
    for name in _REQUIRED_DOCS:
        path = docs / name
        if not path.is_file():
            violations.append(_violation(
                "DOCS_STRUCTURE", path, 1,
                "required static documentation file is missing",
            ))

    html_paths = tuple(docs / name for name in ("index.html", "kmp.html") if (docs / name).is_file())
    maven_version_marker = False
    for path in html_paths:
        text = _read(path)
        expected_canonical = "https://rohittp.com/reng/" if path.name == "index.html" else f"https://rohittp.com/reng/{path.name}"
        canonical_tags = [
            tag for tag in _html_tags(text, "link")
            if (_attribute(tag.group(0), "rel") or "").lower() == "canonical"
        ]
        canonical = _attribute(canonical_tags[0].group(0), "href") if canonical_tags else None
        if canonical != expected_canonical:
            offset = canonical_tags[0].start() if canonical_tags else 0
            violations.append(_violation(
                "DOCS_CANONICAL", path, _line(text, offset),
                f"canonical URL must be {expected_canonical}",
            ))

        for tag in _html_tags(text, "link"):
            source = tag.group(0)
            if (_attribute(source, "rel") or "").lower() == "stylesheet":
                href = _attribute(source, "href")
                if href is None or _EXTERNAL_RESOURCE.search(href):
                    violations.append(_violation(
                        "DOCS_EXTERNAL_DEPENDENCY", path, _line(text, tag.start()),
                        "stylesheets must be local documentation files",
                    ))
        for tag in _html_tags(text, "script"):
            source = tag.group(0)
            src = _attribute(source, "src")
            if src is not None and _EXTERNAL_RESOURCE.search(src):
                violations.append(_violation(
                    "DOCS_EXTERNAL_DEPENDENCY", path, _line(text, tag.start()),
                    "scripts must be local documentation files",
                ))
        if re.search(r"data-maven-version\s*=\s*[\"']kmp[\"']", text):
            maven_version_marker = True

    if html_paths and not maven_version_marker:
        violations.append(_violation(
            "DOCS_VERSION_MARKER", docs, 1,
            "static documentation must include data-maven-version=\"kmp\"",
        ))

    style = docs / "style.css"
    if style.is_file():
        text = _read(style)
        match = re.search(r"@import\b", text, re.IGNORECASE)
        if match is not None:
            violations.append(_violation(
                "DOCS_EXTERNAL_DEPENDENCY", style, _line(text, match.start()),
                "documentation CSS must not use @import",
            ))
    return violations


def check_license(root: Path) -> list[Violation]:
    violations = []
    license_file = root / "LICENSE"
    if not license_file.is_file():
        return [_violation("LICENSE_MISMATCH", license_file, 1, "Apache-2.0 LICENSE is required")]

    license_text = _read(license_file)
    if (
        "apache license" not in license_text.lower()
        or "version 2.0" not in license_text.lower()
        or re.search(r"\bMIT\b", license_text)
    ):
        violations.append(_violation(
            "LICENSE_MISMATCH", license_file, 1,
            "LICENSE must contain only the Apache License, Version 2.0",
        ))

    readme = root / "README.md"
    if not readme.is_file() or "Apache-2.0" not in _read(readme):
        violations.append(_violation(
            "LICENSE_MISMATCH", readme, 1,
            "README must identify the project as Apache-2.0",
        ))

    docs_text = "\n".join(_read(path) for path in _top_level_docs(root))
    if "Apache-2.0" not in docs_text:
        violations.append(_violation(
            "LICENSE_MISMATCH", root / "docs", 1,
            "static documentation must identify the project as Apache-2.0",
        ))

    metadata = "\n".join(_read(path) for path in _production_gradle_scripts(root))
    if (
        "The Apache License, Version 2.0" not in metadata
        or "https://www.apache.org/licenses/LICENSE-2.0.txt" not in metadata
    ):
        violations.append(_violation(
            "LICENSE_MISMATCH", root / "build.gradle.kts", 1,
            "POM build metadata must declare the Apache 2.0 license name and URL",
        ))
    return violations


def check_repository(root: Path) -> list[Violation]:
    root = root.resolve()
    checks = (
        check_maven_local,
        check_version_inputs,
        check_targets,
        check_dependencies,
        check_abi,
        check_public_version_literals,
        check_docs,
        check_license,
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
    root = arguments.root.resolve()
    violations = check_repository(root)
    for item in violations:
        relative = item.path.relative_to(root)
        print(f"{relative}:{item.line}:{item.code}:{item.message}")
    if violations:
        return 1
    print("Cycle A repository policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
