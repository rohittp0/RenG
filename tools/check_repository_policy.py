from __future__ import annotations

import argparse
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
import re
import tomllib
from typing import Iterable, Sequence


@dataclass(frozen=True)
class Violation:
    code: str
    path: Path
    line: int
    message: str


@dataclass(frozen=True)
class _Token:
    kind: str
    value: str
    start: int


@dataclass(frozen=True)
class _HtmlElement:
    name: str
    attributes: dict[str, str | None]
    line: int


EXPECTED_TARGETS = frozenset({
    "android", "iosArm64", "iosSimulatorArm64",
    "macosArm64", "linuxX64", "linuxArm64",
})


_REQUIRED_DOCS = (
    ".nojekyll", "index.html", "kmp.html", "style.css", "versions.js",
    "robots.txt", "sitemap.xml", "llms.txt",
)
_IGNORED_PATH_PARTS = frozenset({".git", ".gradle", "build", ".superpowers"})
_VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
_STABLE_VERSION = re.compile(rf"^{_VERSION_PATTERN}$")
_SEMANTIC_LITERAL = rf"(?:v?{_VERSION_PATTERN})(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?"
_VERSION_ASSIGNMENT = re.compile(
    r"^\s*VERSION_NAME\s*[=:]\s*(?P<value>.*?)\s*$", re.MULTILINE
)
_RENG_COORDINATE_VERSION = re.compile(
    rf"com\.rohittp\.reng:kmp:{_SEMANTIC_LITERAL}"
)
_RENG_VERSION_LITERAL = re.compile(
    rf"\brengVersion\b\s*(?:=|:)\s*[\"']?{_SEMANTIC_LITERAL}", re.IGNORECASE
)
_FORBIDDEN_DEPENDENCY = re.compile(r"\b(?:wire|serialization|skiko|ktor|corpus)\b", re.IGNORECASE)
_CONFLICTING_LICENSE = re.compile(
    r"\b(?:mit|bsd|gpl|lgpl|agpl|mozilla\s+public\s+license|eclipse\s+public\s+license|isc|unlicense|cc0)\b",
    re.IGNORECASE,
)
_EXTERNAL_RESOURCE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:|^//")
_KMP_TARGET_FACTORIES = frozenset({
    "android", "androidTarget", "androidNative", "androidNativeArm32",
    "androidNativeArm64", "androidNativeX86", "androidNativeX64", "ios",
    "iosArm64", "iosX64", "iosSimulatorArm64", "js", "jvm", "linux",
    "linuxArm64", "linuxX64", "macos", "macosArm64", "macosX64", "mingw",
    "mingwX64", "native", "tvos", "tvosArm64", "tvosSimulatorArm64", "tvosX64",
    "wasmJs", "wasmWasi", "watchos", "watchosArm32", "watchosArm64",
    "watchosDeviceArm64", "watchosSimulatorArm64", "watchosX64", "watchosX86",
})
_DEPENDENCY_CALLS = frozenset({
    "add", "api", "compileOnly", "implementation", "runtimeOnly", "testApi",
    "testCompileOnly", "testImplementation", "testRuntimeOnly",
})
_PLUGIN_CALLS = frozenset({"alias", "id", "kotlin"})


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _line(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _violation(code: str, path: Path, line: int, message: str) -> Violation:
    return Violation(code=code, path=path, line=line, message=message)


def _is_ignored(root: Path, path: Path) -> bool:
    return any(part in _IGNORED_PATH_PARTS for part in path.relative_to(root).parts)


def _files(root: Path, predicate) -> tuple[Path, ...]:
    return tuple(sorted(
        (path for path in root.rglob("*") if path.is_file() and not _is_ignored(root, path) and predicate(path)),
        key=lambda path: path.as_posix(),
    ))


def _production_gradle_scripts(root: Path) -> tuple[Path, ...]:
    return _files(root, lambda path: path.name.endswith((".gradle", ".gradle.kts")))


def _workflow_files(root: Path) -> tuple[Path, ...]:
    workflows = root / ".github/workflows"
    if not workflows.is_dir():
        return ()
    return tuple(sorted(
        (path for path in workflows.rglob("*") if path.is_file() and path.suffix in {".yaml", ".yml"}),
        key=lambda path: path.as_posix(),
    ))


def _top_level_docs(root: Path) -> tuple[Path, ...]:
    docs = root / "docs"
    if not docs.is_dir():
        return ()
    return tuple(sorted((path for path in docs.iterdir() if path.is_file()), key=lambda path: path.name))


def _quoted_end(text: str, start: int, quote: str) -> int:
    if quote == '"""':
        end = text.find(quote, start + 3)
        return len(text) if end == -1 else end + 3
    index = start + 1
    while index < len(text):
        if text[index] == "\\":
            index += 2
        elif text[index] == quote:
            return index + 1
        else:
            index += 1
    return len(text)


def _mask_kotlin_comments(text: str) -> str:
    result = list(text)
    index = 0
    while index < len(text):
        if text.startswith('"""', index):
            index = _quoted_end(text, index, '"""')
            continue
        if text[index] in {'"', "'"}:
            index = _quoted_end(text, index, text[index])
            continue
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = len(text) if end == -1 else end
            for position in range(index, end):
                result[position] = " "
            index = end
            continue
        if text.startswith("/*", index):
            start = index
            depth = 1
            index += 2
            while index < len(text) and depth:
                if text.startswith("/*", index):
                    depth += 1
                    index += 2
                elif text.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            for position in range(start, index):
                if result[position] != "\n":
                    result[position] = " "
            continue
        index += 1
    return "".join(result)


def _mask_hash_comments(text: str, also_bang: bool = False) -> str:
    result = list(text)
    index = 0
    markers = {"#"}
    if also_bang:
        markers.add("!")
    while index < len(text):
        if text.startswith('"""', index):
            index = _quoted_end(text, index, '"""')
            continue
        if text[index] in {'"', "'"}:
            index = _quoted_end(text, index, text[index])
            continue
        if text[index] in markers:
            end = text.find("\n", index)
            end = len(text) if end == -1 else end
            for position in range(index, end):
                result[position] = " "
            index = end
            continue
        index += 1
    return "".join(result)


def _active_config_text(path: Path, text: str) -> str:
    if path.name.endswith((".gradle", ".gradle.kts")) or path.suffix in {".kt", ".kts"}:
        return _mask_kotlin_comments(text)
    if path.suffix == ".properties":
        return _mask_hash_comments(text, also_bang=True)
    return _mask_hash_comments(text)


def _kotlin_tokens(text: str) -> tuple[_Token, ...]:
    source = _mask_kotlin_comments(text)
    tokens = []
    index = 0
    while index < len(source):
        character = source[index]
        if character.isspace():
            index += 1
            continue
        if source.startswith('"""', index):
            end = _quoted_end(source, index, '"""')
            tokens.append(_Token("string", source[index + 3:end - 3], index))
            index = end
            continue
        if character in {'"', "'"}:
            end = _quoted_end(source, index, character)
            tokens.append(_Token("string", source[index + 1:end - 1], index))
            index = end
            continue
        if character.isalpha() or character == "_":
            end = index + 1
            while end < len(source) and (source[end].isalnum() or source[end] == "_"):
                end += 1
            tokens.append(_Token("identifier", source[index:end], index))
            index = end
            continue
        tokens.append(_Token("symbol", character, index))
        index += 1
    return tuple(tokens)


def _call_arguments(tokens: Sequence[_Token], index: int) -> tuple[_Token, ...] | None:
    if index + 1 >= len(tokens) or tokens[index + 1].value != "(":
        return None
    depth = 1
    argument_start = index + 2
    for current in range(argument_start, len(tokens)):
        if tokens[current].value == "(":
            depth += 1
        elif tokens[current].value == ")":
            depth -= 1
            if depth == 0:
                return tuple(tokens[argument_start:current])
    return None


def _has_empty_call(tokens: Sequence[_Token], name: str) -> _Token | None:
    for index, token in enumerate(tokens):
        if token.value == name and _call_arguments(tokens, index) == ():
            return token
    return None


def _contains_forbidden(tokens: Iterable[_Token]) -> _Token | None:
    for token in tokens:
        if _FORBIDDEN_DEPENDENCY.search(token.value):
            return token
    return None


def check_maven_local(root: Path) -> list[Violation]:
    violations = []
    for path in _production_gradle_scripts(root):
        text = _read(path)
        token = _has_empty_call(_kotlin_tokens(text), "mavenLocal")
        if token is not None:
            violations.append(_violation(
                "MAVEN_LOCAL", path, _line(text, token.start),
                "production Gradle scripts must not use mavenLocal()",
            ))
    return violations


def _version_input_files(root: Path) -> tuple[Path, ...]:
    paths = set(_files(root, lambda path: path.suffix == ".properties"))
    paths.update(_production_gradle_scripts(root))
    paths.update(_workflow_files(root))
    return tuple(sorted(paths, key=lambda path: path.as_posix()))


def _version_assignments(path: Path) -> tuple[tuple[int, str], ...]:
    text = _read(path)
    active = _active_config_text(path, text)
    return tuple(
        (_line(text, match.start()), match.group("value").strip().strip('"\''))
        for match in _VERSION_ASSIGNMENT.finditer(active)
    )


def check_version_inputs(root: Path) -> list[Violation]:
    root_properties = root / "gradle.properties"
    assignments = [
        (path, line, value)
        for path in _version_input_files(root)
        for line, value in _version_assignments(path)
    ]
    root_assignments = [item for item in assignments if item[0] == root_properties]
    violations = []
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


def _target_call(tokens: Sequence[_Token], index: int) -> bool:
    if tokens[index].value == "android":
        return index + 1 < len(tokens) and tokens[index + 1].value in {"(", "{"}
    return index + 1 < len(tokens) and tokens[index + 1].value == "("


def check_targets(root: Path) -> list[Violation]:
    violations = []
    for relative in ("kmp/build.gradle.kts", "consumer-smoke/build.gradle.kts"):
        path = root / relative
        if not path.is_file():
            violations.append(_violation("TARGET_SET", path, 1, "required target build script is missing"))
            continue
        text = _read(path)
        tokens = _kotlin_tokens(text)
        calls = [
            token for index, token in enumerate(tokens)
            if token.value in _KMP_TARGET_FACTORIES and _target_call(tokens, index)
        ]
        names = [token.value for token in calls]
        counts = {target: names.count(target) for target in EXPECTED_TARGETS}
        extras = [token for token in calls if token.value not in EXPECTED_TARGETS]
        if any(count != 1 for count in counts.values()) or extras:
            first = min(calls, key=lambda token: token.start, default=_Token("", "", 0))
            violations.append(_violation(
                "TARGET_SET", path, _line(text, first.start),
                "must declare exactly android, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, and linuxArm64",
            ))
    return violations


def _direct_rentile_implementation(tokens: Sequence[_Token]) -> bool:
    expected = ("libs", ".", "rentile", ".", "kmp")
    for index, token in enumerate(tokens):
        if token.value == "implementation" and _call_arguments(tokens, index) is not None:
            arguments = _call_arguments(tokens, index)
            if arguments is not None and tuple(item.value for item in arguments) == expected:
                return True
    return False


def check_dependencies(root: Path) -> list[Violation]:
    path = root / "kmp/build.gradle.kts"
    if not path.is_file():
        return [_violation(
            "RENTILE_IMPLEMENTATION_DEPENDENCY", path, 1,
            "KMP build script must declare implementation(libs.rentile.kmp)",
        )]

    text = _read(path)
    tokens = _kotlin_tokens(text)
    violations = []
    if not _direct_rentile_implementation(tokens):
        violations.append(_violation(
            "RENTILE_IMPLEMENTATION_DEPENDENCY", path, 1,
            "KMP build script must declare implementation(libs.rentile.kmp)",
        ))

    for index, token in enumerate(tokens):
        arguments = _call_arguments(tokens, index)
        if token.value == "api" and arguments is not None:
            violations.append(_violation(
                "RENTILE_API_DEPENDENCY", path, _line(text, token.start),
                "Cycle A must not expose dependencies through api(...)",
            ))
            break

    for index, token in enumerate(tokens):
        arguments = _call_arguments(tokens, index)
        if arguments is None or token.value not in _DEPENDENCY_CALLS | _PLUGIN_CALLS:
            continue
        forbidden = _contains_forbidden(arguments)
        if forbidden is not None:
            violations.append(_violation(
                "FORBIDDEN_CYCLE_A_DEPENDENCY", path, _line(text, forbidden.start),
                "Cycle A must not declare Wire, serialization, Skiko, Ktor, or corpus dependencies or plugins",
            ))
            break

    catalog = root / "gradle/libs.versions.toml"
    if catalog.is_file():
        catalog_text = _read(catalog)
        match = _FORBIDDEN_DEPENDENCY.search(_mask_hash_comments(catalog_text))
        if match is not None:
            violations.append(_violation(
                "FORBIDDEN_CYCLE_A_DEPENDENCY", catalog, _line(catalog_text, match.start()),
                "Cycle A must not declare Wire, serialization, Skiko, Ktor, or corpus dependencies or plugins",
            ))
    return violations


def check_abi(root: Path) -> list[Violation]:
    api_directory = root / "kmp/api"
    api_file = api_directory / "kmp.klib.api"
    violations = []
    if not api_file.is_file():
        violations.append(_violation("KLIB_ABI", api_file, 1, "Cycle A requires kmp/api/kmp.klib.api"))
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


def _consumer_smoke_files(root: Path) -> tuple[Path, ...]:
    smoke = root / "consumer-smoke"
    if not smoke.is_dir():
        return ()
    return tuple(sorted(
        (path for path in smoke.rglob("*") if path.is_file() and not _is_ignored(root, path)),
        key=lambda path: path.as_posix(),
    ))


def _public_version_files(root: Path) -> tuple[Path, ...]:
    paths = {root / "README.md", root / "gradle/libs.versions.toml"}
    paths.update(_production_gradle_scripts(root))
    paths.update(_consumer_smoke_files(root))
    paths.update(_top_level_docs(root))
    paths.update(_workflow_files(root))
    return tuple(sorted((path for path in paths if path.is_file()), key=lambda path: path.as_posix()))


def _catalog_has_literal_reng_version(path: Path, text: str) -> int | None:
    try:
        catalog = tomllib.loads(_mask_hash_comments(text))
    except tomllib.TOMLDecodeError:
        return None
    libraries = catalog.get("libraries", {})
    if not isinstance(libraries, dict):
        return None
    for dependency in libraries.values():
        if not isinstance(dependency, dict):
            continue
        is_reng_coordinate = (
            dependency.get("module") == "com.rohittp.reng:kmp"
            or (
                dependency.get("group") == "com.rohittp.reng"
                and dependency.get("name") == "kmp"
            )
        )
        if not is_reng_coordinate:
            continue
        version = dependency.get("version")
        if isinstance(version, str) and re.fullmatch(_SEMANTIC_LITERAL, version):
            coordinate = re.search(
                r"(?:module\s*=\s*[\"']com\.rohittp\.reng:kmp[\"']|group\s*=\s*[\"']com\.rohittp\.reng[\"'])",
                text,
            )
            return 0 if coordinate is None else coordinate.start()
    return None


def check_public_version_literals(root: Path) -> list[Violation]:
    violations = []
    for path in _public_version_files(root):
        text = _read(path)
        active = _active_config_text(path, text)
        matches = [
            match for pattern in (_RENG_COORDINATE_VERSION, _RENG_VERSION_LITERAL)
            if (match := pattern.search(active)) is not None
        ]
        catalog_offset = _catalog_has_literal_reng_version(path, text) if path.name == "libs.versions.toml" else None
        if catalog_offset is not None:
            offset = catalog_offset
        elif matches:
            offset = min(matches, key=lambda match: match.start()).start()
        else:
            continue
        violations.append(_violation(
            "HARDCODED_RENG_VERSION", path, _line(text, offset),
            "public RenG versions must remain property- or metadata-driven",
        ))
    return violations


class _DocumentationParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.elements: list[_HtmlElement] = []
        self.text: list[str] = []

    def handle_starttag(self, tag: str, attributes) -> None:
        self.elements.append(_HtmlElement(tag.lower(), dict(attributes), self.getpos()[0]))

    def handle_startendtag(self, tag: str, attributes) -> None:
        self.handle_starttag(tag, attributes)

    def handle_data(self, data: str) -> None:
        self.text.append(data)


def _parse_html(text: str) -> _DocumentationParser:
    parser = _DocumentationParser()
    parser.feed(text)
    parser.close()
    return parser


def _is_external_resource(value: str | None) -> bool:
    return value is None or bool(_EXTERNAL_RESOURCE.search(value))


def _css_without_comments_and_strings(text: str) -> str:
    result = list(text)
    index = 0
    while index < len(text):
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = len(text) if end == -1 else end + 2
            for position in range(index, end):
                if result[position] != "\n":
                    result[position] = " "
            index = end
            continue
        if text[index] in {'"', "'"}:
            end = _quoted_end(text, index, text[index])
            for position in range(index, end):
                if result[position] != "\n":
                    result[position] = " "
            index = end
            continue
        index += 1
    return "".join(result)


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
        document = _parse_html(text)
        expected_canonical = "https://rohittp.com/reng/" if path.name == "index.html" else f"https://rohittp.com/reng/{path.name}"
        canonical = [
            element for element in document.elements
            if element.name == "link" and "canonical" in (element.attributes.get("rel") or "").lower().split()
        ]
        canonical_url = canonical[0].attributes.get("href") if canonical else None
        if canonical_url != expected_canonical:
            violations.append(_violation(
                "DOCS_CANONICAL", path, canonical[0].line if canonical else 1,
                f"canonical URL must be {expected_canonical}",
            ))
        for element in document.elements:
            if element.name == "link" and "stylesheet" in (element.attributes.get("rel") or "").lower().split():
                if _is_external_resource(element.attributes.get("href")):
                    violations.append(_violation(
                        "DOCS_EXTERNAL_DEPENDENCY", path, element.line,
                        "stylesheets must be local documentation files",
                    ))
            if element.name == "script" and "src" in element.attributes:
                if _is_external_resource(element.attributes.get("src")):
                    violations.append(_violation(
                        "DOCS_EXTERNAL_DEPENDENCY", path, element.line,
                        "scripts must be local documentation files",
                    ))
            if element.attributes.get("data-maven-version") == "kmp":
                maven_version_marker = True
    if html_paths and not maven_version_marker:
        violations.append(_violation(
            "DOCS_VERSION_MARKER", docs, 1,
            "static documentation must include data-maven-version=\"kmp\"",
        ))

    style = docs / "style.css"
    if style.is_file():
        text = _read(style)
        match = re.search(r"@import\b", _css_without_comments_and_strings(text), re.IGNORECASE)
        if match is not None:
            violations.append(_violation(
                "DOCS_EXTERNAL_DEPENDENCY", style, _line(text, match.start()),
                "documentation CSS must not use @import",
            ))
    return violations


def _has_conflicting_license(text: str) -> bool:
    return _CONFLICTING_LICENSE.search(text) is not None


def _apache_page_is_consistent(text: str) -> bool:
    return "Apache-2.0" in text and not _has_conflicting_license(text)


def check_license(root: Path) -> list[Violation]:
    violations = []
    license_file = root / "LICENSE"
    if not license_file.is_file():
        return [_violation("LICENSE_MISMATCH", license_file, 1, "Apache-2.0 LICENSE is required")]

    license_text = _read(license_file)
    if (
        "apache license" not in license_text.lower()
        or "version 2.0" not in license_text.lower()
        or _has_conflicting_license(license_text)
    ):
        violations.append(_violation(
            "LICENSE_MISMATCH", license_file, 1,
            "LICENSE must contain only the Apache License, Version 2.0",
        ))

    readme = root / "README.md"
    if not readme.is_file() or not _apache_page_is_consistent(_read(readme)):
        violations.append(_violation(
            "LICENSE_MISMATCH", readme, 1,
            "README must identify the project consistently as Apache-2.0",
        ))

    for path in (root / "docs/index.html", root / "docs/kmp.html"):
        if not path.is_file() or not _apache_page_is_consistent("".join(_parse_html(_read(path)).text)):
            violations.append(_violation(
                "LICENSE_MISMATCH", path, 1,
                "each served documentation page must identify the project consistently as Apache-2.0",
            ))

    metadata = "\n".join(
        _mask_kotlin_comments(_read(path)) for path in _production_gradle_scripts(root)
    )
    if (
        "The Apache License, Version 2.0" not in metadata
        or "https://www.apache.org/licenses/LICENSE-2.0.txt" not in metadata
        or _has_conflicting_license(metadata)
    ):
        violations.append(_violation(
            "LICENSE_MISMATCH", root / "build.gradle.kts", 1,
            "POM build metadata must declare only the Apache 2.0 license name and URL",
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
