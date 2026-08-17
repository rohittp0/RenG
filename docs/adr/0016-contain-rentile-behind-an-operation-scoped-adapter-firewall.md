# Contain Rentile behind an operation-scoped adapter firewall

Rentile 0.1.5 is not a transparent pass-through resource boundary. Its tile paths may call Transport twice
for one thrown or transient outcome, and invalid stored content causes a private remove-then-fetch repair.
RenG's public contract instead permits at most one consumer exchange for one logical resource operation and
makes stored corruption terminal without consumer mutation. Actual proofs showed that a stateless adapter
violates those guarantees and that one long-lived Rentile instance with an operation-scoped firewall can
preserve them across all eight basemap resource classes.

A renderer owns one long-lived Rentile instance whose fixed adapters multiplex through the one active
preparation invocation. Each invocation preregisters static prelookup routes by access mode, exact locator,
exhaustive resource class, and response-byte limit. Preregistration establishes joins and detects static private
Rentile key collisions but assigns no execution order. Dynamic occurrences register at depth-first discovery frontiers;
the first logically eligible occurrence assigns the shared route's traversal ordinal, so an earlier discovered
child can activate a route preregistered from a later static segment. Equal routes join one freshness sample,
resident decision, and Store read. After that lookup determines the allowlisted request metadata, the final
Transport latch key is the route plus all three exact metadata values. The first consumer Transport result is
latched as a defensively copied response, a sanitized RenG failure, or an unwrapped `CancellationException`;
Kotlin stack recovery may copy that exception while preserving the original as its immediate cause. Concurrent
joins and Rentile's later adapter call replay that outcome. The registry is discarded when the invocation
terminates; it is neither a renderer-lifetime response cache nor permission to share work across access modes.

Rentile does not carry `ResourceAccessMode` through its `ResourceTransport` or `RawResourceStore` callbacks.
The firewall therefore binds mode from the explicit outer preparation invocation, includes it in RenG's
operation identity, and never reconstructs it from a Rentile key. It validates every consumer record's
shape, digest, metadata, freshness, byte limit, and class-specific encoded format before exposing it to
Rentile. A Rentile `remove` request is private and terminal: it performs no consumer removal, no repair, and
no follow-on exchange.

Rentile's write ordering is class-specific. TileJSON, vector-tile, raster-tile, and GeoJSON paths reach
Rentile's raw-store write only after their bounded parser or decoder validation. DEM reaches it after generic
bounded image validation, not terrain-encoding semantic validation. Style has no Rentile raw-store write.
Sprite JSON and image bytes are written before their joint atlas validation, and complete sprite-image
decoding may otherwise be deferred until rendering. RenG therefore never treats Rentile's callback as a
universal post-format-validation boundary.

RenG never rewrites a record selected directly from resident state or the consumer Store. Store-sourced style
bytes compile privately and may become preparation-visible only after compilation and whole-batch success,
without another consumer write. Fetched or `304`-metadata-refreshed style bytes stage privately for Rentile
compilation and write only after successful compilation and completion of all other work for the referencing
preparation items; staged bytes are not preparation-visible. RenG jointly prevalidates the complete sprite
JSON-and-PNG pair before writing either fetched member; the consumer writes are sequential, but no atlas becomes
visible unless both succeed. A fetched DEM write additionally requires RenG's terrain encoding validation. For
the remaining classes, a Rentile write callback may perform the consumer write only after RenG verifies that it
matches the latched response and the stricter RenG record rules. A valid `304` is first merged with its validated
stale baseline and presented to Rentile as a bounded full response; every other redirect or disallowed status
fails.

Tile substitution remains disabled, and RenG never invokes Rentile's exact-tile retry helper. Internal
Rentile adapter counts are not public retries: only consumer Transport and Store calls define RenG's
observable boundary. Adapter text, locators, validators, and causes never cross that boundary; cancellation
remains an unwrapped coroutine cancellation rather than a RenG failure.

A direct pass-through was rejected because it observably duplicates exchanges and mutates corrupt entries.
Creating a new Rentile instance for every resource operation was sufficient for the throwaway proof but
would discard renderer-lifetime planning state and make ownership needlessly expensive. Forking Rentile was
also rejected while the adapter firewall can enforce the contract against the published coordinate. The
operation-aware multiplexer is more stateful than a simple adapter, but its lifetime is bounded by the
already-serialized preparation contract. One long-lived Rentile instance passed sequential mode isolation,
actual raster/vector/DEM/TileJSON/GeoJSON/style/sprite paths, a 256-tile batch at concurrency eight,
cancellation/redaction checks, six-target compilation, and identical Android/macOS executable suites.
