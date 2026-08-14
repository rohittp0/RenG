# Publish six Apple Silicon only targets and no JVM

RenG publishes `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`,
and nothing else. There is no `macosX64` and no `iosX64`, so an Intel Mac consumer gets a hard
resolution failure rather than a degraded render, and there is no `jvm` target even though Rentile
publishes one. Every published target is a permanent commitment, because removing one later breaks
resolution for anyone who adopted it, so the release surface starts as narrow as the supported hardware
and grows only when a real consumer appears.

This matches Rentile's release surface and its reasoning (rentile ADR 0022): Intel Macs are outside the
supported development and rendering hardware, and adding a target later is a compatible change while
removing one is not. Rentile publishes all six of these targets as of `0.1.5`, verified against
`https://maven.rohittp.com`, so RenG's dependency resolves for every target it ships without a local
publication or a temporary repository entry.

The JVM exclusion is deliberate rather than incidental. RenG's platform story is Android, iOS, macOS,
and Linux; a desktop JVM consumer would need a GL binding implementation that exists for none of the
three RenG has, and the published workflows do not build one. Android host tests still run on the JVM,
but that is a test source set rather than a published target. Adding `jvm` later means touching both
workflows and the smoke consumer, and is deferred until something needs it.
