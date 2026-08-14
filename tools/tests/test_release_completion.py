from __future__ import annotations

import hashlib
import json
import unittest

from tools.release_completion import (
    COMPLETION_RECORD_SCHEMA_VERSION,
    CompletionRecord,
    CompletionRecordError,
    completion_record_key,
)


VERSION = "1.2.3"
SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
MANIFEST = (
    b"com/rohittp/reng/kmp/1.2.3/kmp-1.2.3.module\n"
    b"com/rohittp/reng/kmp/1.2.3/kmp-1.2.3.pom\n"
)


class ReleaseCompletionTests(unittest.TestCase):
    def test_create_hashes_exact_manifest_and_round_trips_canonical_record(self) -> None:
        record = CompletionRecord.create(VERSION, SOURCE_COMMIT, MANIFEST)

        self.assertEqual(COMPLETION_RECORD_SCHEMA_VERSION, record.schema_version)
        self.assertEqual(VERSION, record.maven_version)
        self.assertEqual(SOURCE_COMMIT, record.source_commit_sha)
        manifest_hash = hashlib.sha256(MANIFEST).hexdigest()
        self.assertEqual(manifest_hash, record.manifest_sha256)
        self.assertEqual(
            (
                '{"manifestSha256":"'
                + manifest_hash
                + '","mavenVersion":"1.2.3","schemaVersion":1,'
                + '"sourceCommitSha":"'
                + SOURCE_COMMIT
                + '"}\n'
            ).encode("ascii"),
            record.serialize(),
        )
        self.assertEqual(record, CompletionRecord.parse(record.serialize(), VERSION))
        self.assertEqual(
            f"com/rohittp/reng/kmp/{VERSION}/reng-release-completion-v1.json",
            completion_record_key(VERSION),
        )

    def test_parse_rejects_noncanonical_or_mismatched_records(self) -> None:
        valid = json.loads(
            CompletionRecord.create(VERSION, SOURCE_COMMIT, MANIFEST).serialize()
        )
        cases = (
            b"<html>not json</html>",
            json.dumps({
                key: value
                for key, value in valid.items()
                if key != "manifestSha256"
            }).encode(),
            json.dumps({**valid, "extra": "field"}).encode(),
            json.dumps({**valid, "schemaVersion": True}).encode(),
            json.dumps({**valid, "schemaVersion": 2}).encode(),
            json.dumps({**valid, "mavenVersion": 123}).encode(),
            json.dumps({**valid, "mavenVersion": "1.2.4"}).encode(),
            json.dumps({**valid, "sourceCommitSha": 123}).encode(),
            json.dumps({**valid, "sourceCommitSha": SOURCE_COMMIT.upper()}).encode(),
            json.dumps({**valid, "manifestSha256": 123}).encode(),
            json.dumps({**valid, "manifestSha256": "f" * 63}).encode(),
            (
                b'{"schemaVersion":1,"schemaVersion":1,'
                b'"mavenVersion":"1.2.3","sourceCommitSha":"'
                + SOURCE_COMMIT.encode()
                + b'","manifestSha256":"'
                + (b"f" * 64)
                + b'"}'
            ),
        )
        for document in cases:
            with self.subTest(document=document):
                with self.assertRaises(CompletionRecordError):
                    CompletionRecord.parse(document, VERSION)

    def test_create_rejects_invalid_inputs(self) -> None:
        cases = (
            ("01.2.3", SOURCE_COMMIT, MANIFEST),
            (VERSION, "abc", MANIFEST),
            (VERSION, SOURCE_COMMIT, "not bytes"),
        )
        for version, source_commit, manifest in cases:
            with self.subTest(version=version, source_commit=source_commit):
                with self.assertRaises(CompletionRecordError):
                    CompletionRecord.create(version, source_commit, manifest)  # type: ignore[arg-type]

        with self.assertRaises(CompletionRecordError):
            CompletionRecord(
                schema_version=2,
                maven_version=VERSION,
                source_commit_sha=SOURCE_COMMIT,
                manifest_sha256="f" * 64,
            )


if __name__ == "__main__":
    unittest.main()
