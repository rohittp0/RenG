# The GLB subset Cycle C parses, and the container rules that guard it

`docs/decomposition.md` records GLB parsing as one of Cycle C's two open technical decisions and says the
tractable part is the container while the risky part is the feature subset, which "must be written down
rather than discovered". This document writes it down. It settles the container layer by construction and
measurement, reports one unwelcome finding about the JSON layer, and proposes an accept/reject list keyed to
the two class gates Cycle B already declares.

Everything below separates three kinds of claim. **Measured** claims came out of a throwaway spike or a
command whose output is quoted. **Specified** claims are quotations or close paraphrases of the glTF 2.0
specification text and JSON schema, fetched at the revision hashed under "Provenance". **Proposed** claims
are this document's recommendations; they bind nothing until Cycle C's specification adopts them, and the
closing checklist lists the ones that are genuinely unsettled rather than merely unwritten.

## Provenance

The specification was read from its AsciiDoc source rather than the rendered HTML, because
`registry.khronos.org` answered `403` to an automated fetch.

| Input | Where it came from | Digest |
|---|---|---|
| glTF 2.0 `Specification.adoc` | `https://raw.githubusercontent.com/KhronosGroup/glTF/main/specification/2.0/Specification.adoc` | `55986799907693d3f51b0a474497852c0d6318b85084811fdc05ff0db4b27967` |
| `accessor`, `mesh.primitive`, `animation.sampler`, `animation.channel.target`, `sampler` schemas | `.../specification/2.0/schema/*.schema.json` | fetched, not hashed |
| Container and JSON spike | `spike.kt`, 41 hand-built fixtures, `make_fixtures.py` | session scratchpad only |

The spike is a single-file Kotlin/Native program with no dependency beyond `kotlin.stdlib` and
`platform.posix` for reading fixture files. It was compiled and run entirely outside Gradle:

```
/root/.konan/kotlin-native-prebuilt-linux-x86_64-2.3.21/bin/kotlinc-native -o spike spike.kt
./spike.kexe fixtures $(ls fixtures | sort)
```

`kotlinc-native 2.3.21`, `Kotlin/Native 2.3.21`, `linuxX64`. Nothing from the spike belongs in the
repository: it is a throwaway that exists to make the claims below checkable, and it lives only in the
session scratchpad.

## Verified facts

| Fact | How it was established |
|---|---|
| The 12-byte header is `uint32 magic` = `0x46546C67`, `uint32 version` (this specification defines 2), `uint32 length` = "the total length of the Binary glTF, including _header_ and all _chunks_, in bytes" | Specification, `Header` section |
| A chunk is `uint32 chunkLength`, `uint32 chunkType`, `ubyte[] chunkData`, where `chunkLength` is the length of `chunkData` | Specification, `Chunks` section |
| "The start and the end of each chunk **MUST** be aligned to a 4-byte boundary" and "Chunks **MUST** appear in exactly the order given" | Specification, `Chunks` section |
| JSON chunk type `0x4E4F534A`, occurrences exactly `1`; BIN chunk type `0x004E4942`, occurrences `0 or 1` | Specification, chunk-types table |
| "Client implementations **MUST** ignore chunks with unknown types … following the first two chunks" | Specification, `Chunks` section |
| The JSON chunk "**MUST** be padded with trailing `Space` chars (`0x20`)"; the BIN chunk "**MUST** be padded with trailing zeros (`0x00`)" | Specification, `Structured JSON Content` and `Binary buffer` |
| Binary glTF is little endian | Specification, `Overview` |
| "The byte length of the `BIN` chunk **MAY** be up to 3 bytes bigger than JSON-defined `buffer.byteLength`" | Specification, `GLB-stored Buffer` |
| A GLB-backed buffer "**MUST** be the first element of `buffers` array and it **MUST** have its `buffer.uri` property undefined. When such a buffer exists, a `BIN` chunk **MUST** be present." | Specification, `GLB-stored Buffer` |
| A container reader implementing exactly those rules classifies 41 hand-built fixtures as intended — the full table is below | Spike run quoted under "The container layer" |
| The GLB padding **bytes** cannot be validated at the container layer at all, only inside the JSON chunk | Spike fixtures 19–23; reasoning under "Padding is not a container-layer check" |
| RenG's one dependency, `com.rohittp.rentile:kmp:0.1.5`, exposes no public JSON reader — its only JSON-shaped API is `StyleInput.InlineJson(json: String)` and `LabelLayerDescriptor.layerJson: String` | `klib dump-abi` of `kmp-linuxX64Main-0.1.5.klib`, then `grep -i json` |
| `kotlinx-serialization-json:1.11.0` is already in RenG's resolved graph transitively, but asymmetrically: it appears in Rentile's `linuxX64ApiElements-published` (compile-visible on native) and only in `androidRuntimeElements-published`, not `androidApiElements-published` (runtime-only on Android) | Parsed `kmp-linuxx64-0.1.5.module` and `kmp-android-0.1.5.module` from the Gradle module cache |
| `tools/check_repository_policy.py` pins `:kmp` to exactly `commonMain implementation(libs.rentile.kmp)` plus `commonTest implementation(kotlin("test"))`, and its `_FORBIDDEN_DEPENDENCY` pattern matches the literal `serialization` | `check_dependencies` and `_FORBIDDEN_DEPENDENCY` in `tools/check_repository_policy.py` |
| A strict pure-Kotlin JSON reader with exact-integer, duplicate-key, lone-surrogate, depth, and strict-UTF-8 handling is roughly 200 lines and compiles for `linuxX64` with no dependency | The spike itself; batteries quoted under "The JSON layer" |
| Parsing 8 MiB of JSON (`[1,1,1,…]`, 4 194 304 values) into a boxed value tree peaked at 236 720 KiB resident against a 10 620 KiB empty-run baseline — about 27× the input, in 0.83 s wall | `resource.getrusage(RUSAGE_CHILDREN).ru_maxrss` around `./spike.kexe --amplify` at four input sizes |
| `accessor.count` has schema `minimum: 1`, so a zero-keyframe animation sampler input is already structurally invalid | `accessor.schema.json` |
| `mesh.primitive.mode` defaults to `4`; `animation.sampler.interpolation` defaults to `LINEAR` | `mesh.primitive.schema.json`, `animation.sampler.schema.json` |
| "Before and after the provided input range, output **MUST** be clamped to the nearest end of the input range" | Specification, animation samplers |
| "Animation sampler's `input` accessor **MUST** have its `min` and `max` properties defined", and "Values stored in glTF JSON **MUST** match actual minimum and maximum binary values stored in buffers" | Specification, animation samplers and accessors |
| `ResourceLimits.maximumModelGlbBytes` defaults to `256 MiB` and is constrained to `[1, 2147483647]` | `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt:73` and its `init` block |

## The container layer

The container is small enough to state completely. A conforming reader needs, in order: at least 12 bytes;
magic exactly `glTF`; version exactly `2`; a declared total length equal to the actual byte count; that
length a multiple of four; then a walk of chunks in which each chunk needs eight readable bytes of header, a
`chunkLength` that is a multiple of four, and a `chunkLength` that does not run past the end of the file.
The first chunk must be the JSON chunk and must be non-empty. A BIN chunk is permitted only as the second
chunk. Chunks from the third position on with types other than JSON or BIN are ignored.

Two of those are proposed rather than specified. Requiring the declared `length` to *equal* the file size,
rather than merely not exceed it, is stricter than the wording; the specification defines `length` as the
total including header and chunks but does not say what a reader does with extra bytes after it. Requiring
`length % 4 == 0` is implied rather than stated: the header is 12 bytes and every chunk starts and ends
4-aligned, so a conforming total is necessarily a multiple of four. Both are the fail-closed reading, both
are cheap, and both catch real corruption early. Rejecting an unknown chunk in the *second* position is
also proposed: the specification lets extensions add chunks "following the first two chunks", so a BIN chunk
found after an unknown chunk is not in its mandated second position, and RenG rejects rather than scanning
for it.

The spike implements exactly this and classifies 41 hand-built fixtures. The column after each name is the
gate-1 verdict; where a fixture reached the JSON layer, the structural sub-checks it also ran are shown.

```
01-valid-json-and-bin.glb                      accept OK_BIN_CONSISTENT(buffer=36,chunk=36) OK_ACCESSORS(count=1,elementBytes=36) animations[0=Idle 1=Wave é😀]
02-valid-json-only.glb                         accept OK_NO_BUFFERS OK_NO_ACCESSORS
03-bad-magic.glb                               reject BAD_MAGIC
04-version-1.glb                               reject UNSUPPORTED_CONTAINER_VERSION
05-version-3.glb                               reject UNSUPPORTED_CONTAINER_VERSION
06-truncated-chunk-data.glb                    reject DECLARED_LENGTH_MISMATCH
07-declared-length-too-large.glb               reject DECLARED_LENGTH_MISMATCH
08-declared-length-not-multiple-of-4.glb       reject DECLARED_LENGTH_MISMATCH
09-misaligned-chunk-length.glb                 reject DECLARED_LENGTH_MISALIGNED
10-json-chunk-second.glb                       reject BIN_CHUNK_NOT_SECOND
11-trailing-garbage.glb                        reject CHUNK_LENGTH_MISALIGNED
12-header-only-11-bytes.glb                    reject HEADER_TOO_SHORT
13-truncated-chunk-header.glb                  reject DECLARED_LENGTH_MISMATCH
14-empty-json-chunk.glb                        reject EMPTY_JSON_CHUNK
15-two-json-chunks.glb                         reject JSON_CHUNK_NOT_FIRST
16-two-bin-chunks.glb                          reject BIN_CHUNK_NOT_SECOND
17-unknown-chunk-third.glb                     accept OK_BIN_CONSISTENT(buffer=36,chunk=36) OK_ACCESSORS(count=1,elementBytes=36) +1 ignored chunk animations[0=Idle 1=Wave é😀]
18-bin-after-unknown-chunk.glb                 reject UNKNOWN_CHUNK_IN_BIN_POSITION
19-json-padded-with-spaces.glb                 accept OK_NO_BUFFERS OK_NO_ACCESSORS
20-json-padded-with-nulls.glb                  reject JSON_TRAILING_CONTENT
21-json-padded-with-tabs.glb                   accept OK_NO_BUFFERS OK_NO_ACCESSORS
22-bin-padded-with-zeros.glb                   accept OK_BIN_CONSISTENT(buffer=36,chunk=36) OK_ACCESSORS(count=1,elementBytes=36) animations[0=Idle 1=Wave é😀]
23-bin-padded-with-spaces.glb                  accept OK_BIN_CONSISTENT(buffer=36,chunk=36) OK_ACCESSORS(count=1,elementBytes=36) animations[0=Idle 1=Wave é😀]
24-bin-chunk-but-buffer-has-uri.glb            reject BUFFER_ZERO_HAS_URI_WITH_BIN_CHUNK
25-buffer-longer-than-bin-chunk.glb            reject BUFFER_LONGER_THAN_BIN_CHUNK
26-buffer-4-shorter-than-bin-chunk.glb         reject BIN_CHUNK_PADDING_ABOVE_3_BYTES
27-buffer-3-shorter-than-bin-chunk.glb         accept OK_BIN_CONSISTENT(buffer=33,chunk=36) OK_NO_ACCESSORS
28-no-bin-chunk-but-buffer-embedded.glb        reject EMBEDDED_BUFFER_WITHOUT_BIN_CHUNK
29-chunk-length-overflows-file.glb             reject DECLARED_LENGTH_MISALIGNED
30-empty-file.glb                              reject HEADER_TOO_SHORT
31-trailing-garbage-length-unchanged.glb       reject DECLARED_LENGTH_MISMATCH
32-chunk-length-misaligned-total-aligned.glb   reject CHUNK_LENGTH_MISALIGNED
33-truncated-first-chunk-header.glb            reject TRUNCATED_CHUNK_HEADER
34-chunk-length-overflow-aligned.glb           reject TRUNCATED_CHUNK_DATA
35-json-with-leading-bom.glb                   reject JSON_UNEXPECTED_CHARACTER_65279
36-accessor-count-2-pow-40.glb                 reject ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW(0,span=13194139533312,view=36)
37-accessor-byte-offset-past-view.glb          reject ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW(0,span=1099511627788,view=36)
38-bufferview-past-buffer.glb                  reject BUFFER_VIEW_EXCEEDS_BUFFER(0)
39-accessor-fits-exactly.glb                   accept OK_BIN_CONSISTENT(buffer=36,chunk=36) OK_ACCESSORS(count=1,elementBytes=36)
40-accessor-interleaved-stride.glb             accept OK_BIN_CONSISTENT(buffer=36,chunk=36) OK_ACCESSORS(count=2,elementBytes=36)
41-accessor-missing-bufferview-draco-shape.glb reject ACCESSOR_WITHOUT_BUFFER_VIEW(0)
```

Three details in that table are worth naming, because they show the checks interacting rather than each
firing in isolation.

Fixtures 06, 07, 08, 13 and 31 all reduce to `DECLARED_LENGTH_MISMATCH`. Truncation, an inflated declared
length, a length that is not a multiple of four, a file that ends inside the first chunk header, and
appended garbage are five different authoring accidents that the same single comparison catches, provided
the comparison is equality against the actual byte count. That is the argument for the strict reading: one
check subsumes five malformed cases. The cases only reach the later checks when the declared length is made
consistent with the damage, which is what fixtures 11, 32, 33 and 34 do — 32 exercises a misaligned
`chunkLength` behind an aligned total, 33 a genuinely truncated chunk header, 34 a `chunkLength` of
`0xFFFFFFF0` that overflows the file.

Fixture 36 is the memory-safety case. A `count` of `2^40` on a 36-byte buffer view is rejected by
arithmetic in `Long`, before any array is allocated, because the span it demands is 13 194 139 533 312
bytes. No allocation is attempted at any point.

Fixture 41 is an ordering trap. It has the shape a Draco-compressed asset has: `extensionsRequired` lists
`KHR_draco_mesh_compression` and the accessor has no `bufferView`, which is legal glTF meaning "all zeros".
Because `PARSE_GLB` runs before `VALIDATE_GLB_FEATURES`, a parser that treats a missing `bufferView` as
structurally invalid reports `RESOURCE_PARSE_FAILED` for a file whose real problem is an unsupported
extension. The recommendation below is therefore that `PARSE_GLB` tolerates a missing `accessor.bufferView`
as the specification does, and `VALIDATE_GLB_FEATURES` is the gate that rejects it — so a Draco asset gets
`UNSUPPORTED_RESOURCE_FEATURE`, which is the code that tells the consumer something true.

### Padding is not a container-layer check

The task framing treats "the exact padding bytes (`0x20` for JSON, `0x00` for BIN)" as a container rule to
enforce. It is not enforceable there, and the spike shows why.

`chunkLength` includes the padding, and nothing records the unpadded length. A reader therefore cannot tell
which trailing bytes of a chunk are padding and which are payload. For the BIN chunk this is terminal:
fixtures 22 and 23 are byte-identical except that one pads a 34-byte payload with `0x00 0x00` and the other
with `0x20 0x20`, and both are accepted, because `buffers[0].byteLength` defines the used prefix and the
remaining one to three bytes are simply unread. The `0x00` requirement binds producers and is invisible to
consumers. The only BIN-chunk check available is the length relation, and it is exact: fixture 27
(`byteLength` three below the chunk) is accepted, fixture 26 (four below) is rejected, fixture 25
(`byteLength` above the chunk) is rejected.

For the JSON chunk the situation is better but the check still belongs to the JSON reader, because only the
reader knows where the document ended. Fixture 20, padded with `0x00`, is rejected as trailing content —
NUL is not JSON whitespace. Fixture 21, padded with `0x09`, is *accepted* under an ordinary JSON reader,
because tab is JSON whitespace even though `0x09` is not the mandated pad byte. Running both fixtures under
two padding policies makes the difference explicit:

```
19-json-padded-with-spaces.glb     JSON_WHITESPACE=accept   STRICT_SPACE=accept
20-json-padded-with-nulls.glb      JSON_WHITESPACE=reject JSON_TRAILING_CONTENT   STRICT_SPACE=reject JSON_PADDING_NOT_SPACE
21-json-padded-with-tabs.glb       JSON_WHITESPACE=accept   STRICT_SPACE=reject JSON_PADDING_NOT_SPACE
```

Proposed: adopt `STRICT_SPACE`. After the JSON document's closing token, every remaining byte inside the
JSON chunk must be exactly `0x20`. It is one line, it is a specification `MUST`, and a producer that emits
tab padding is emitting something no other rule would catch. This is a `PARSE_GLB` check, owned by the JSON
reader rather than by the chunk walk.

## The JSON layer

RenG has no JSON dependency, and the finding is that it cannot borrow one either.

Rentile is RenG's only dependency, and it publishes no JSON reader. Dumping its ABI shows the entire
JSON-shaped public surface is `StyleInput.InlineJson(json: String, baseUri: String?)` and
`LabelLayerDescriptor.layerJson: String` — JSON carried as opaque text, in and out, with no parser, no
value model, and no tokenizer exposed:

```
/root/.konan/…/bin/klib dump-abi …/kmp-linuxX64Main-0.1.5.klib | grep -i json
```

`kotlinx-serialization-json:1.11.0` is nevertheless already in RenG's resolved dependency graph, pulled in
transitively by Rentile, so adding it directly would introduce no new coordinate to a consumer's
resolution. That is the only comfortable part of the picture. Everything else argues against it. Rentile
publishes it into `linuxX64ApiElements-published` but only into `androidRuntimeElements-published`, never
`androidApiElements-published`, so it is compile-visible on the native targets and runtime-only on Android
— exactly the asymmetry that makes it unusable from `commonMain`, which must compile for all six targets.
Using it would mean declaring it directly, which means owning its version, adding the
`kotlinx-serialization` compiler plugin, and amending `tools/check_repository_policy.py` twice: its
`check_dependencies` pins `:kmp` to exactly two dependency calls, and its `_FORBIDDEN_DEPENDENCY` pattern
matches the literal string `serialization`.

Proposed: Cycle C writes its own reader. The spike is the evidence that this is a small job rather than a
brave one — roughly 200 lines of `commonMain`-compatible Kotlin covering strict UTF-8 decoding, the RFC
8259 grammar, and every glTF-relevant strictness decision, compiled and run with no dependency at all. The
subset it must implement, with the spike's measured verdicts:

```
{"a":1}                        -> accept Integer(1)
{"a":1,"a":2}                  -> reject DUPLICATE_MEMBER_NAME
{"a":9007199254740993}         -> accept Integer(9007199254740993)
{"a":1e2}                      -> accept Real(100.0)
{"a":01}                       -> reject LEADING_ZERO
{"a":.5}                       -> reject UNEXPECTED_CHARACTER_46
{"a":5.}                       -> reject BAD_FRACTION
{"a":+5}                       -> reject UNEXPECTED_CHARACTER_43
{"a":-0}                       -> accept Integer(0)
{"a":1E+308}                   -> accept Real(1.0E308)
{"a":1E+400}                   -> reject NON_FINITE_NUMBER
{"a":NaN}                      -> reject UNEXPECTED_CHARACTER_78
{"a":Infinity}                 -> reject UNEXPECTED_CHARACTER_73
{"a":"😀"}                     -> accept Text(utf16=2,scalars=1)
{"a":"\uD800"}                 -> reject LONE_HIGH_SURROGATE_ESCAPE
{"a":"\uDC00x"}                -> reject LONE_LOW_SURROGATE_ESCAPE
{"a":"\\uD83D\\uDE00"}           -> accept Text(utf16=2,scalars=1)
{"a":"\x"}                     -> reject BAD_ESCAPE_120
{"a":"tab<0x09>inside"}        -> reject UNESCAPED_CONTROL_CHARACTER
{"a":'single'}                 -> reject UNEXPECTED_CHARACTER_39
{"a":1,}                       -> reject EXPECTED_MEMBER_NAME
[1,2,3]                        -> accept Arr(size=3)
{}                             -> accept Obj(empty)
{}{}                           -> reject TRAILING_CONTENT
// comment\n{}                 -> reject UNEXPECTED_CHARACTER_47
{"a":"A\/\b\f\n\r\t"}          -> accept Text(utf16=7,scalars=7)
<U+FEFF>{}                     -> reject UNEXPECTED_CHARACTER_65279
{"a":1} \t\r\n                 -> accept Integer(1)
{"a":1}<0x00>                  -> reject TRAILING_CONTENT
```

Three inputs in that listing contain bytes that cannot be shown literally and appear as
`<0x09>`, `<0x00>` and `<U+FEFF>`; the spike's own source holds the real bytes.

Four of those lines carry the design decisions.

**Numbers need an exact integer path, not `Double`.** `9007199254740993` is `2^53 + 1`; parsed as a
`Double` it becomes `9007199254740992`, silently off by one. glTF's own text acknowledges the hazard —
buffers "**SHOULD NOT**" exceed `2^53` bytes "because some JSON parsers may be unable to parse their
`byteLength` correctly". Proposed: the reader classifies a number token by spelling. A token with no
fraction and no exponent that fits in `Long` becomes an integer value; anything else becomes a `Double`,
and a non-finite result is rejected. Every glTF field the specification types as an integer — indices,
`count`, `componentType`, `byteOffset`, `byteLength`, `byteStride`, `mode` — is then read only from the
integer form, so `1e2` is a number but is not an index. That is stricter than JSON Schema's `integer`,
which would accept `1e2` as `100`, and it is the right kind of strict for a component that never repairs.

**Duplicate member names are rejected.** RFC 8259 says names "SHOULD be unique" and leaves the rest
undefined, which means last-wins and first-wins readers disagree about what a document means. A GLB with
two `animations` members, or an animation object with two `name` members, would resolve differently under
two conforming readers. `CONTEXT.md` already makes a GLB with duplicate non-blank exact animation names
invalid; rejecting duplicate keys is the same principle one level down.

**Lone surrogates are rejected at both layers.** `\uD800` unpaired in an escape is rejected, and so is the
UTF-8 encoding of a surrogate (`ED A0 80`, the CESU-8 form). This is not fastidiousness: `AnimationSelector.Name`
is validated by `requireUnicodeScalars` in `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt`,
which rejects isolated UTF-16 surrogates, so a GLB animation name containing one could never be selected by
any legal selector. Reject it where it enters rather than leaving it unaddressable.

`requireUnicodeScalars` is partially reusable, and the boundary is worth stating precisely. Its scanning
logic — walk UTF-16 units, require every high surrogate to be followed by a low one, reject any bare low
surrogate — is exactly what the JSON reader needs and should be shared. Its *failure mode* is not: it
throws `IllegalArgumentException`, which `CONTEXT.md` reserves for public value-constructor violations that
occur "before renderer work" and whose messages are explicitly not caller contracts. A malformed GLB is not
a caller programming error; it is a `RenGException` with a stable code and a redacted diagnostic. So Cycle C
needs a non-throwing predicate form of the same scan — `containsOnlyUnicodeScalars(value: String): Boolean`
or equivalent — with `requireUnicodeScalars` refactored to call it. `canonicalDouble` and `requireFinite`
in the same file are similarly reusable for numeric fields once a value has been read.

**A BOM is rejected and depth is bounded.** The UTF-8 decoder accepts `EF BB BF` as `U+FEFF`, and the JSON
reader then rejects it, because RFC 8259 does not permit a byte order mark and `U+FEFF` is not JSON
whitespace. Nesting is capped; the spike's limit of 64 accepts depth 64 and rejects 65. A glTF document's
own structure is about eight levels deep, so 64 is generous for anything RenG reads and finite for
`extras`, which may contain arbitrary consumer JSON.

The strict UTF-8 decoder is its own small battery, and it matters because the JSON chunk is raw bytes from
an untrusted producer:

```
valid ascii 7B 7D              -> accept (utf16 units=2)
valid 2-byte C3 A9             -> accept (utf16 units=1)
overlong C0 80                 -> reject UTF8_INVALID_LEAD_BYTE
overlong E0 80 80              -> reject UTF8_OVERLONG
CESU-8 ED A0 80                -> reject UTF8_ENCODED_SURROGATE
above max F5 80 80 80          -> reject UTF8_INVALID_LEAD_BYTE
truncated E2 82                -> reject UTF8_TRUNCATED_SEQUENCE
bad continuation C3 41         -> reject UTF8_INVALID_CONTINUATION
astral F0 9F 98 80             -> accept (utf16 units=2)
leading BOM EF BB BF           -> accept (utf16 units=1)
```

Replacement characters are deliberately absent. A decoder that substitutes `U+FFFD` for malformed bytes is
repairing input, which RenG does not do, and it would silently change an animation name.

## Which gate owns which check

Cycle B declares `PARSE_GLB` and `VALIDATE_GLB_FEATURES` as the two ordered gates for `MODEL_GLB` in
`ordinaryResourceClassGates`, and `classGateFailure` maps them to `RESOURCE_PARSE_FAILED` and
`UNSUPPORTED_RESOURCE_FEATURE`, both at `PipelineStage.RESOURCE_PARSING`. Since the stage is the same, the
split is entirely about which of two true statements the consumer is told: *your file is malformed* or
*your file is fine and RenG does not render that*. That is the assignment principle, and it is
actionability, not difficulty:

- `PARSE_GLB` owns every check whose failure means the bytes are not a well-formed glTF 2.0 binary asset:
  the container, the JSON document, `asset.version`, every index reference being in range, all accessor and
  buffer-view arithmetic, the node hierarchy being a set of disjoint trees, and RenG's own catalog
  invariants such as unique non-blank animation names. Its output is a fully parsed, internally consistent
  document — with no judgement yet about whether RenG can draw it.
- `VALIDATE_GLB_FEATURES` owns every check whose failure means the document is valid but outside the
  supported subset: `extensionsRequired`, primitive modes, attribute semantics, sparse accessors, skins,
  morph targets, image media types, external URI references, interpolation modes, and animation target
  paths.

Two consequences follow. First, `PARSE_GLB` must be permissive about anything the specification permits
even when RenG will refuse it — the fixture 41 case. Second, the split is only observable for
Transport-produced content: Task 14A's rule is that a failed class gate on `ContentProvenance.STORE`
selects `STORE_INTEGRITY_FAILED / STORE_VALIDATION` regardless of which gate failed, so a corrupt stored
GLB and an unsupported stored GLB report identically. That is correct — both mean the stored record cannot
be trusted — and it means the gate-assignment argument above is about fresh network content only.

## The supported subset

Driven by what RenG actually draws: a `Model` is one `Placement`, one GLB, an optional single base-colour
texture override, and animation tracks sampled at `timeSeconds`. Everything below is proposed.

| Feature | Verdict | Gate | Reason |
|---|---|---|---|
| `asset.version` major 2, `minVersion` ≤ 2.0 | accept | `PARSE_GLB` | The specification requires a GLB reader to check the JSON asset version separately from the container version |
| Component types `5120`–`5123`, `5125`, `5126` | accept | `PARSE_GLB` | The complete specified set; each has a known size, so bounds arithmetic is total |
| Any other `componentType` | reject | `PARSE_GLB` | Unknown size makes accessor arithmetic undecidable, so this is malformation, not an unsupported feature |
| `normalized` on integer attribute types where the specification permits it | accept | `VALIDATE_GLB_FEATURES` | Decoding is four documented affine formulas and it is how real exporters store `TEXCOORD` and `COLOR` |
| `normalized` on `5125`/`5126`, or on `indices` | reject | `VALIDATE_GLB_FEATURES` | Not permitted by the specification for those uses; accepting it would require inventing a meaning |
| Sparse accessors | reject | `VALIDATE_GLB_FEATURES` | A second indexed patch pass over every accessor, for a feature whose main use is morph targets, which are also rejected |
| `mode` 4 (`TRIANGLES`), including the schema default | accept | — | The only topology a textured 3D model needs |
| `mode` 5 / 6 (strip, fan) | reject | `VALIDATE_GLB_FEATURES` | Convertible to triangles by the producer; converting inside RenG is a repair |
| `mode` 0–3 (points, lines, loop, strip) | reject | `VALIDATE_GLB_FEATURES` | Needs a second shading path and a line-width policy RenG has no vocabulary for |
| `POSITION` | accept, required | `PARSE_GLB` when absent | Without positions there is nothing to draw; a primitive missing it is malformed, not unsupported |
| `NORMAL` | accept, optional | — | Needed for any lit shading; absent means flat |
| `TEXCOORD_0` | accept, optional | — | The single set the base-colour texture and the override sample |
| `TEXCOORD_n` for n ≥ 1 | reject | `VALIDATE_GLB_FEATURES` | RenG binds one texture, so further sets have no consumer and silently ignoring them changes appearance |
| `TANGENT` | accept and ignore | — | Legal, cheap to parse, unused until a normal-map path exists; ignoring an optional attribute changes no pixel |
| `COLOR_0` | accept | — | A per-vertex multiplier on base colour, no extra binding, common in exports |
| `COLOR_n` for n ≥ 1 | reject | `VALIDATE_GLB_FEATURES` | No defined combination rule |
| `JOINTS_n` / `WEIGHTS_n` | reject | `VALIDATE_GLB_FEATURES` | Only meaningful with skins, which are rejected |
| Unrecognised attribute semantic (including `_CUSTOM`) | reject | `VALIDATE_GLB_FEATURES` | Fail closed; an attribute RenG cannot name may carry geometry it should not silently drop |
| `indices` accessor, `SCALAR` unsigned byte/short/int | accept | — | The normal case; index count validated as a non-zero multiple of three |
| Non-indexed primitives | accept | — | Attribute `count` gives the vertex count directly |
| Index value ≥ attribute `count`, or the reserved maximum for its component type | reject | `PARSE_GLB` | Both are specification `MUST`s and both are out-of-bounds reads |
| Node `matrix` | accept | — | Consumed as a matrix; no TRS decomposition is needed to place geometry |
| Node `translation` / `rotation` / `scale` | accept | — | Composed as `T * R * S` per the specification |
| Both `matrix` and TRS on one node, or `matrix` on an animated node | reject | `PARSE_GLB` | The specification forbids both; the asset is contradictory rather than unsupported |
| Node hierarchy, arbitrary breadth, depth ≤ a fixed bound | accept | — | A plain tree walk, needed for any real export |
| Cyclic or multi-parent node graph, or depth above the bound | reject | `PARSE_GLB` | "The node hierarchy **MUST** be a set of disjoint strict trees"; a cycle is malformation and would not terminate |
| `scene` present, naming a valid scene | accept | — | The unambiguous case |
| `scene` absent with exactly one scene | accept | — | Proposed: no ambiguity exists, so requiring `scene` would reject working assets for no gain |
| `scene` absent with zero or ≥ 2 scenes | reject | `VALIDATE_GLB_FEATURES` | The specification lets a client "delay rendering until a particular scene is requested"; RenG has no one to ask and will not pick |
| Multiple mesh-bearing nodes; multiple primitives per mesh | accept | — | Every real export has them; each primitive is one draw |
| `pbrMetallicRoughness.baseColorFactor` and `baseColorTexture` | accept | — | The material properties a base-colour override must preserve or replace |
| `metallicRoughnessTexture`, `normalTexture`, `occlusionTexture`, `emissiveTexture` and their factors | parse and retain, do not shade yet | — | `CONTEXT.md` says an override "replaces every rendered primitive's base-colour texture while preserving other material properties", so they must survive parsing; when they start affecting pixels is Cycle F's decision |
| `alphaMode`, `alphaCutoff`, `doubleSided` | parse and retain | — | Same reasoning; they are blend and cull state, not resources |
| Any `KHR_materials_*` extension in `extensionsRequired` | reject | `VALIDATE_GLB_FEATURES` | Covered by the blanket `extensionsRequired` rule below |
| Texture `sampler` with specified `wrapS`/`wrapT`/`minFilter`/`magFilter` values | accept | — | They map directly onto GL enums Cycle D will set |
| Unspecified sampler enum values | reject | `PARSE_GLB` | Not in the schema's enumeration; a value RenG cannot map is malformed |
| Image as `bufferView` + `mimeType: image/png` | accept | — | Embedded, and PNG is the format Cycle C's decoder handles |
| Image as `bufferView` + `mimeType: image/jpeg` | reject | `VALIDATE_GLB_FEATURES` | Valid glTF; RenG has no JPEG decoder, and every RenG image class fixes `Accept: image/png` |
| Image or buffer with a `uri` | reject | `VALIDATE_GLB_FEATURES` | The purity case, expanded below |
| `buffers[0]` embedded, with the BIN length relation satisfied | accept | — | The one buffer form RenG reads |
| Any buffer beyond `buffers[0]`, or `buffers[0]` with a `uri` alongside a BIN chunk | reject | `PARSE_GLB` for the contradiction, `VALIDATE_GLB_FEATURES` for an external reference | A `uri` next to a BIN chunk is a contradiction; a `uri` on its own is an unsupported delivery mode |
| `bufferView.byteStride`, interleaved attributes | accept | — | A vertex-buffer layout parameter, not a data transformation; fixture 40 validates the arithmetic |
| `byteStride` below the element size, or not a multiple of the component size | reject | `PARSE_GLB` | Both are specification `MUST`s and both make element addressing incoherent |
| Animation channels targeting `translation`, `rotation`, `scale` | accept | — | Exactly what a `Placement`-hosted model needs |
| Animation channels targeting `weights` | reject | `VALIDATE_GLB_FEATURES` | Morph targets are rejected, so the target does not exist |
| Channel with no `target.node` | accept and ignore | — | Legal, defined as a no-op for extension use |
| `LINEAR` (including the schema default) and `STEP` interpolation | accept | — | Both are a handful of lines; `LINEAR` on a rotation uses slerp |
| `CUBICSPLINE` interpolation | reject in Cycle C | `VALIDATE_GLB_FEATURES` | Fail closed rather than approximate: substituting `LINEAR` would silently change motion. Purely additive later, behind the same gate and error code |
| Skins, `inverseBindMatrices`, joint hierarchies | reject | `VALIDATE_GLB_FEATURES` | A whole skinning pipeline; nothing in RenG's vocabulary refers to it |
| Morph targets (`primitive.targets`, `mesh.weights`) | reject | `VALIDATE_GLB_FEATURES` | Needs per-frame vertex blending; `AnimationTrack` has no weight vocabulary |
| Non-empty `extensionsRequired` | reject | `VALIDATE_GLB_FEATURES` | Covers Draco, `EXT_meshopt_compression`, `KHR_texture_basisu` and every future compression extension with one rule that cannot go stale |
| `extensionsUsed` without `extensionsRequired`, and unknown `extensions` / `extras` objects | accept and ignore | — | The specification's own contract: optional extensions are ignorable, so ignoring them is conformance, not leniency |
| `cameras`, `KHR_lights_*`, and other non-geometry entities | accept and ignore | — | A `FramePlan` supplies the camera; authored cameras and lights are not RenG's inputs |
| Accessor with no `bufferView` | tolerate in parse, reject in validate | `VALIDATE_GLB_FEATURES` | Legal ("all zeros") and the Draco signature; rejecting it at parse time would report the wrong error, as fixture 41 shows |

### The URI case deserves its own paragraph

`CONTEXT.md` already states it for `Model`: "GLB buffers and images must be embedded; external URI
references are rejected." The purity argument is why, and it is stronger than a convenience argument. A
`FramePlan` is a complete definition of on-screen state, and RenG performs no network I/O of its own — every
byte arrives through the consumer's injected Transport, keyed by a `ResourceLocator` the consumer wrote. A
`uri` inside a GLB is a resource RenG never planned for: it has no `ResourceLocator`, no `ResourceClass`, no
selected `ResourceLimits` ceiling, no route ordinal, and no place in the Resource Operation's preregistered
or discovery-frontier route set. RenG cannot fetch it — that would be an unplanned exchange — and cannot
resolve it, because a `ResourceLocator` is opaque text that RenG "never parses as a URL or opens as a file
path", so there is no base against which a relative `uri` could be resolved. There is no correct behaviour
available, and drawing an untextured model instead would be a silent fallback. Rejecting is the only
option consistent with the contract.

Data URIs are a separate question with the same recommended answer. A `data:` URI is embedded rather than
external, so the purity argument does not reach it; the arguments against are that it needs a base64
decoder, that it duplicates the BIN path, and that any producer can trivially convert it. Proposed:
reject in Cycle C, on the "must be embedded" reading of `CONTEXT.md`, with `VALIDATE_GLB_FEATURES` as the
gate. This one is genuinely a judgement call and is listed in the checklist.

## Animation selection

`AnimationSelector` resolves against the parsed `animations` array. `Index(i)` resolves to `animations[i]`
and is unresolvable when `i >= animations.size`; since `i` is a `Long` and the array is bounded by the
document, any `i` above `Int.MAX_VALUE` is out of range by construction. `Name(n)` resolves by exact
comparison against `animation.name` — exact UTF-16 code-unit equality, with no Unicode normalization, which
is what `CONTEXT.md` requires and what the strict JSON reader guarantees is well-formed.

Three properties of glTF's own model shape the rules. `animation.name` is optional, so an animation may
have none. It is not required to be unique, so a conforming GLB may name two animations `Walk`. And blank
names are legal glTF but can never be produced by `AnimationSelector.Name`, whose `requireUnicodeScalars`
call passes `nonBlank = true`. Hence:

- Animations with an absent or blank `name` are addressable only by `Index`. That is not an error; it is a
  reachability fact worth documenting for consumers.
- A GLB with two or more animations sharing the same non-blank exact name is invalid, per `CONTEXT.md`. This
  belongs to `PARSE_GLB`, because it is a property of the GLB alone and needs no plan to detect.
- A selector that resolves to nothing — index out of range, or a name matching no animation — is a
  preparation failure at plan resolution, not a gate failure. The gates are per-resource-class and never
  see the `FramePlan`. Likewise for two selectors in one `Model` resolving to the same animation, which
  `CONTEXT.md` also rejects.

`timeSeconds` maps onto sampler inputs in two stages, and the split matters because the two stages have
different authorities. `CONTEXT.md` fixes the outer stage: a positive-duration animation samples
`timeSeconds % durationSeconds`, and a zero-duration animation samples time zero. The specification fixes
the inner one: sampler inputs "are relative to `t = 0`, defined as the beginning of the parent `animations`
entry", and "before and after the provided input range, output **MUST** be clamped to the nearest end of the
input range". So `durationSeconds` is the largest input value across all of that animation's samplers, and
within each sampler the wrapped time is clamped into `[firstInput, lastInput]`. An animation whose earliest
keyframe is at `t = 10` therefore holds its first value from `t = 0`, which is the specification's own worked
example, and samplers with differing input ranges inside one animation are explicitly permitted and each
clamp independently.

An empty sampler needs no special rule: `accessor.count` has schema `minimum: 1`, so a zero-keyframe input
accessor is already invalid at `PARSE_GLB`.

There is one open question here. `min` and `max` are required on an animation input accessor, and the
specification says JSON-stored min/max "**MUST** match actual … values stored in buffers", so duration
could be read from JSON without touching the BIN chunk. Reading the actual last input value from the buffer
is more robust — RenG must read the inputs to sample anyway — but then the declared `max` is a value RenG
has validated nothing about. Proposed: require `min` and `max` to be present, derive duration from the
buffer, and do not require exact equality, because JSON round-tripping of floats makes a hard equality check
reject working exports. Listed in the checklist.

## Limits and hostile input

The transport and store layers already bound the input: `maximumModelGlbBytes` defaults to 256 MiB and is
capped at `2147483647`, and a request carries its selected ceiling so oversize content fails before decode
or use. So the parser's input length is bounded before it starts. Three further bounds are needed, because a
bounded input does not imply a bounded parse.

**The container gives geometry memory for free.** Because every buffer must be embedded, total vertex and
index bytes are at most the BIN chunk, which is at most the GLB, which is at most
`maximumModelGlbBytes`. That is the quiet payoff of rejecting external URIs: RenG's CPU-side geometry
footprint is bounded by a number the consumer already configured. Two caveats. Expanded forms are not
bounded the same way — de-interleaving, widening normalized shorts to floats, or expanding indices can cost
up to about 4× the stored bytes — so `decodedCpuBytes` in the `ResourceReport` should carry the expanded
figure, not the raw one. And the bound only holds if accessor arithmetic is checked *before* allocation,
which is fixtures 36, 37 and 38: a `count` of `2^40` on a 36-byte view is rejected by `Long` arithmetic with
nothing allocated. The complete rule is that for every accessor,
`byteOffset + (count - 1) * effectiveStride + elementSize <= bufferView.byteLength`, and
`bufferView.byteOffset + byteLength <= buffer.byteLength`, and `buffers[0].byteLength <= binChunkLength`,
all in `Long`, before any array exists.

**The JSON tree is the real amplifier, and it is measured.** A boxed JSON value tree costs far more than its
text. Parsing `[1,1,1,…]` at four sizes:

| Input | Values | Child peak RSS | Marginal ratio over the 10 620 KiB empty-run baseline |
|---|---|---|---|
| 3 B | 1 | 10 620 KiB | baseline |
| 262 145 B | 131 072 | 16 108 KiB | ≈ 21× |
| 2 097 153 B | 1 048 576 | 53 364 KiB | ≈ 21× |
| 8 388 609 B | 4 194 304 | 236 720 KiB | ≈ 27× |

Measured with `resource.getrusage(RUSAGE_CHILDREN).ru_maxrss` around `./spike.kexe --amplify`; the 8 MiB case
took 0.83 s wall. Inferred from those figures: a 256 MiB JSON chunk of the same shape would demand roughly
6–7 GiB, which is a denial of service on every one of RenG's six targets from a file the transport layer
happily accepts. Proposed: bound the JSON chunk independently of the whole-GLB ceiling — a real glTF JSON
chunk is kilobytes to low megabytes even for a large model, because the geometry is in the BIN chunk — and
additionally bound the total parsed value count. A JSON-chunk ceiling is the simpler of the two and probably
sufficient; the exact number is a spec decision. A streaming reader that never materialises a full tree
would avoid the problem differently, at the cost of a much less pleasant parser.

**Structural depth needs its own bound, twice.** JSON nesting is bounded at parse time — the spike caps at
64, accepting depth 64 and rejecting 65 — which protects the reader's own recursion. The node hierarchy
needs a separate bound, because node count is bounded by the JSON size but a chain of 100 000 nodes is a
small document and a deep recursion. Proposed: a fixed node-depth ceiling with an iterative walk, plus the
disjoint-trees check, which also makes the walk terminate on a cyclic graph.

Two limits are deliberately *not* proposed. No time budget: the parse is linear in the input and the input
is bounded, and a wall-clock limit would make failure depend on the host. And no partial parse or salvage:
a GLB that fails any check yields no model, which is the same fail-closed posture the rest of RenG takes.

## What Cycle C's spec must decide

- **JSON-chunk byte ceiling.** Whether to add one, and what value. The 27× amplification measurement says
  something is needed; the number is a judgement call.
- **Whether to bound total parsed JSON value count** in addition to, or instead of, a chunk ceiling.
- **`data:` URI buffers and images.** Recommended reject; the purity argument does not actually cover them,
  so this is a scope decision that should be made explicitly rather than inherited.
- **`CUBICSPLINE`.** Recommended reject in Cycle C. If Cycle F wants it, decide now whether it lands there
  or is deferred, so the error code a consumer sees does not change meaning between releases.
- **`TANGENT` and `COLOR_0`: accept-and-ignore, or reject.** Recommended accept, but "parse a thing nothing
  reads" and "reject a thing exporters emit by default" are both defensible, and the choice affects how
  many real assets load.
- **Animation `min`/`max` strictness.** Recommended: require presence, derive duration from the buffer,
  tolerate inexact declared values. The alternative — requiring exact equality — is more principled and
  will reject real exports.
- **Exact node-depth and JSON-depth ceilings**, and whether they are configurable or fixed.
- **How much of the material model Cycle C retains.** This document recommends parsing all of
  `pbrMetallicRoughness` plus the four secondary texture slots and the alpha/cull state, because
  `CONTEXT.md` promises an override "preserves other material properties", but which of them Cycle F
  actually shades is not settled and the parsed representation should not pretend otherwise.
- **Whether the strict `STRICT_SPACE` JSON padding policy is adopted.** Recommended yes; it is a
  specification `MUST` that nothing else catches, and it will reject a producer that pads with tabs.
- **Where the shared Unicode-scalar predicate lives.** Extracting a non-throwing form out of
  `requireUnicodeScalars` touches a Cycle B file, so it is a small planned refactor rather than an
  incidental edit.
- **Whether the GLB feature subset earns an ADR.** The subset is a long-lived compatibility promise —
  every accepted feature is a commitment and every rejection is a file some consumer cannot load — which
  is the same reasoning ADR 0010 applies to published targets.

## What this document does not establish

The spike proves the container and the JSON layer. It does not prove the feature subset: no real exporter
output was parsed, because building a full glTF parser was out of scope for a spike whose job was to settle
the container. The accept/reject table is reasoned from the specification and from RenG's own vocabulary,
not measured against a corpus. The obvious next check, before Cycle C's spec is final, is to run the
container and JSON layers over the Khronos sample-model set and count how many assets the proposed subset
would reject and why — that is a measurement this document lacks, and it is the one most likely to move a
row from reject to accept.

## Erratum (2026-08-19, recorded during Cycle C Task 7)

The classification table above names `DECLARED_LENGTH_MISALIGNED` for fixtures 09 and 29. **No such
reject code exists.** `GlbReject` never defined one, and the implemented scanner does not draw that
distinction.

The survey was written before the container design settled on a single strict equality comparison for
the header's declared total length: it must equal the actual byte count exactly and be a multiple of
four. That one check deliberately collapses five different authoring accidents — truncation, an
inflated declared length, a misaligned length, a file ending inside a chunk header, and appended
garbage — into `DECLARED_LENGTH_MISMATCH`. The table's finer name records a distinction the design
intentionally chose not to make. It is not a missing feature.

Consequently fixture 09 (`09-misaligned-chunk-length`) cannot isolate the chunk-level
`CHUNK_LENGTH_MISALIGNED` gate: a misaligned chunk length inside a 4-aligned file necessarily
misaligns the total as well, so the header gate fires first. Fixture 32
(`32-chunk-length-misaligned-total-aligned`) is the only fixture that reaches that gate, and it does
so by adding compensating padding — which is exactly what its name describes. `GlbContainerTest`
asserts fixture 09's true outcome, `DECLARED_LENGTH_MISMATCH`.

The table is left unedited: it is the record of what the parse survey observed, and correcting it in
place would erase the fact that the survey proposed a distinction the implementation later folded.
