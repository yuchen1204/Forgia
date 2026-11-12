# Parallel API compile-only example

`ContainerLinkExample.java` depends only on Minecraft/NeoForge types and the public Forgia facade. It
establishes a target reference while the server is quiescent, submits one complete bounded transfer from
the source's own admitted ticker, and polls its explicit outcome without blocking a worker.

Build the facade with `./gradlew --offline :neoforge:parallelApiJar` and use the resulting
`projects/neoforge/build/libs/*-parallel-api.jar` as `compileOnly` alongside your normal NeoForge compile
dependencies. Do not bundle a second copy of the API classes. Portable mods must load this Forgia-specific
entrypoint only after confirming the optional capability is present.

The example does not claim the rest of a machine's tick is worker-safe. Audit its local callbacks and
configure the explicit type admission separately. Re-establish a link after target replacement; do not
retry partially applied failed operations or treat queue acceptance as a completed transfer.

See [the full protocol](../../region-threading/CROSS-REGION-OPERATIONS.md) for side/slot rules, lifetime,
failure and threading contracts. The real-server `blockEntityOwnerOperations` GameTest supplies a separate
registered example block entity and verifies native worker submission through the same public API.
