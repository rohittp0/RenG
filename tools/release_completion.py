from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import re


COMPLETION_RECORD_SCHEMA_VERSION = 1
_COMPLETION_RECORD_NAME = "reng-release-completion-v1.json"
_MAVEN_BASE_PATH = "com/rohittp/reng/kmp"
_VERSION_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$"
)
_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_RECORD_FIELDS = frozenset({
    "schemaVersion",
    "mavenVersion",
    "sourceCommitSha",
    "manifestSha256",
})


class CompletionRecordError(ValueError):
    pass


def _require_version(value: object) -> str:
    if not isinstance(value, str) or _VERSION_PATTERN.fullmatch(value) is None:
        raise CompletionRecordError(
            "Completion record Maven version must be canonical MAJOR.MINOR.PATCH"
        )
    return value


def _require_source_commit(value: object) -> str:
    if not isinstance(value, str) or _COMMIT_PATTERN.fullmatch(value) is None:
        raise CompletionRecordError(
            "Completion record source commit must be a lowercase 40-character SHA"
        )
    return value


def _require_manifest_hash(value: object) -> str:
    if not isinstance(value, str) or _SHA256_PATTERN.fullmatch(value) is None:
        raise CompletionRecordError(
            "Completion record manifest hash must be a lowercase SHA-256"
        )
    return value


def _object_without_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise CompletionRecordError("Completion record contains a duplicate field")
        result[key] = value
    return result


@dataclass(frozen=True)
class CompletionRecord:
    schema_version: int
    maven_version: str
    source_commit_sha: str
    manifest_sha256: str

    def __post_init__(self) -> None:
        if (
            isinstance(self.schema_version, bool)
            or not isinstance(self.schema_version, int)
            or self.schema_version != COMPLETION_RECORD_SCHEMA_VERSION
        ):
            raise CompletionRecordError("Completion record schema version is unsupported")
        _require_version(self.maven_version)
        _require_source_commit(self.source_commit_sha)
        _require_manifest_hash(self.manifest_sha256)

    @classmethod
    def create(
        cls,
        maven_version: str,
        source_commit_sha: str,
        manifest_document: bytes,
    ) -> CompletionRecord:
        version = _require_version(maven_version)
        source_commit = _require_source_commit(source_commit_sha)
        if not isinstance(manifest_document, bytes):
            raise CompletionRecordError("Completion record manifest input must be bytes")
        return cls(
            schema_version=COMPLETION_RECORD_SCHEMA_VERSION,
            maven_version=version,
            source_commit_sha=source_commit,
            manifest_sha256=hashlib.sha256(manifest_document).hexdigest(),
        )

    @classmethod
    def parse(cls, document: bytes, expected_maven_version: str) -> CompletionRecord:
        expected_version = _require_version(expected_maven_version)
        if not isinstance(document, bytes):
            raise CompletionRecordError("Completion record document must be bytes")
        try:
            value = json.loads(
                document.decode("utf-8"),
                object_pairs_hook=_object_without_duplicates,
            )
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise CompletionRecordError("Completion record is not valid UTF-8 JSON") from None
        if not isinstance(value, dict) or frozenset(value) != _RECORD_FIELDS:
            raise CompletionRecordError("Completion record fields do not match schema version 1")

        schema_version = value["schemaVersion"]
        if (
            isinstance(schema_version, bool)
            or not isinstance(schema_version, int)
            or schema_version != COMPLETION_RECORD_SCHEMA_VERSION
        ):
            raise CompletionRecordError("Completion record schema version is unsupported")
        maven_version = _require_version(value["mavenVersion"])
        if maven_version != expected_version:
            raise CompletionRecordError(
                "Completion record Maven version does not match its versioned path"
            )
        return cls(
            schema_version=schema_version,
            maven_version=maven_version,
            source_commit_sha=_require_source_commit(value["sourceCommitSha"]),
            manifest_sha256=_require_manifest_hash(value["manifestSha256"]),
        )

    def serialize(self) -> bytes:
        value = {
            "schemaVersion": self.schema_version,
            "mavenVersion": self.maven_version,
            "sourceCommitSha": self.source_commit_sha,
            "manifestSha256": self.manifest_sha256,
        }
        return (
            json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
            + "\n"
        ).encode("ascii")


def completion_record_key(maven_version: str) -> str:
    version = _require_version(maven_version)
    return f"{_MAVEN_BASE_PATH}/{version}/{_COMPLETION_RECORD_NAME}"
