# Use versioned canonical bytes for content identities

Frame and resource identities use a dependency-free canonical binary encoding rather than data-class hashes,
JSON, platform serialization, or Rentile's credential-redacted URL key. Every root begins ASCII `RNGC`, schema
version byte `1`, and one root-kind byte: frame `1`, external resource `2`, geometry program `3`, internal
pipeline `4`, or offscreen surface `5`. A root or nested object is a strictly increasing sequence of unique
fields encoded as unsigned 16-bit big-endian tag, unsigned 32-bit big-endian payload length, and payload.
The Cycle B specification owns the permanent field-tag table for every Frame Plan field.

Strings are exact UTF-8 with no Unicode, URL, or path normalization. Enums have explicitly declared unsigned
16-bit wire values. Booleans are one byte. Fixed integers are big-endian. A Double is finite IEEE-754 binary64
in big-endian order after negative zero is canonicalized to positive zero. Lists carry an unsigned 32-bit
count and each element's unsigned 32-bit byte length; order and duplicates remain content. Optional fields
carry an explicit presence byte, so later fields never change position when a value is absent. Malformed,
duplicate, out-of-order, truncated, or overflowing encodings fail rather than decode or allocate.

A Frame Identity is `reng-frame-v1:` plus lowercase SHA-256 over the complete frame root. A Resource Key's
stable id is lowercase SHA-256 over its resource root. The external-resource preimage includes the external
kind, exhaustive Resource Class, and exact Resource Locator. Geometry-program identity includes the shader
profile version and exact vertex and fragment sources, including whitespace. Pipeline and offscreen roots
are domain-separated now, but their owning cycles must freeze their descriptor fields before those resource
kinds can appear; the throwaway descriptor fields used to prove root separation are not product contracts.

The registry retains a defensive copy of canonical bytes beside every digest. Equal bytes may share work. If
one digest names different bytes, RenG fails `IDENTITY_COLLISION` without replacing the first entry or sharing
state. This check remains mandatory even though a SHA-256 collision is not expected in normal operation.

A pure common-Kotlin SHA-256 implementation was selected because the six published targets have no shared
platform crypto API and adding a crypto dependency solely for internal identities is unnecessary. Known-answer
vectors and the complete 1,431-byte representative Frame Plan encoding produced the same identity on Android
host and macOS, while all six target sources compiled and the public ABI remained free of platform-crypto and
Rentile types.

JSON was rejected because equivalent parser/writer settings, escaping, and number formatting would become an
implicit compatibility contract. Platform hashes were rejected because they are not collision-resistant or
cross-target stable. Rentile's key was rejected because it deliberately removes credential query values,
while RenG's exact opaque Resource Locator is content identity and only diagnostics—not identity—redact it.
