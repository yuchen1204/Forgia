# Missing-chunk tick failures and explicit owner continuations

This follows the reproducible replay issue in [CRASH-AND-LOGIC-AUDIT.md](CRASH-AND-LOGIC-AUDIT.md).
It removes automatic replay for **entity and block-entity ticks** and introduces a bounded, ticket-backed
completion stage for explicitly submitted owner operations. It does not transparently resume arbitrary mod
Java code or implement asynchronous chunk loading.

## Partial ticks are never restarted

The old entity handler treated an ordinary chunk refusal as permission to invoke the whole entity tick again
on the server thread. The block-entity group similarly requeued an already-started ticker. Both could repeat
inventory consumption, output generation, damage and events. The absence of the `mustNotReplay` marker
never established that earlier effects were absent.

Both whole-tick replay handlers and the entity batch's replay queue are removed. Once either body starts,
a thrown failure follows normal reporting after worker completion. It is neither swallowed as successful
work nor turned into a new whole-tick invocation. The failed group stops at its first failure, like the normal
tick error path. Already accepted deferred effects still follow the batch's existing completion chain.

This intentionally changes behavior for unsupported worker callbacks: a mid-tick chunk request can now
produce the normal server failure instead of apparently recovering by duplicating effects. The configured
remove-erroring-entity/block-entity behavior remains applicable. This is a conservation/correctness repair,
not a claim that every previously failing mod now keeps running. A failed operation can still be partially
applied, exactly as with other tick errors; no rollback or arbitrary crash-recovery guarantee is introduced.

**Pre-body fallback remains valid.** Existing loaded-neighborhood, type declaration, stale admission and
capacity checks may keep a complete tick on the server thread when its body has not begun. Explicit server
thread type declarations cover callbacks with unbounded chunk access until their operations are adapted.
Unknown access requirements cannot be learned safely by trial, side effects and replay.

The legacy `chunkFallbackEntityTicks` diagnostic accessor remains for compatibility and stays zero.
Chunk request refusal counts and ordinary worker failure counters remain meaningful; a refused request is
not a completed continuation. Chunk/random/spawn/scheduled propagation truncation behavior is outside this
bounded change and still has the previously documented limitations.

## Real block-entity failures are reported on the server

The real LevelChunk bound ticker catches exceptions before the outer batch sees them. Its original catch
could build a crash report and apply the remove-erroring-block-entities policy on a worker. Native workers
now throw a small RegionBlockEntityTickFailure carrier containing the original cause and block-entity
context; the batch reports it after completion on the server thread. The original report title/category,
configuration and removal calls are retained. Main-thread ticker execution retains the existing path.

This applies to chunk refusals and other failures from a real bound worker ticker. No partially applied
machine tick is replayed. Reporting can still fail if a mod's report callback itself throws; the carrier does
not swallow such failures or replace them with an empty report.

## Explicit chunk-backed owner operation

`entityInteractions().submitToOwnerAfterChunkLoad(source, owner, position, action)` is an experimental
extension of the existing owner-operation scheduler:

1. The source must authorize admission before the new operation's effects start. Native callers need their
   source-owning entity tick batch. A proven quiescent server thread can execute immediately. Other contexts
   reject, as do shutdown, a retired level and the shared 4096-entry batch bound.
2. Capture the position immutably; the task retains the owner and callback, not the source. The source may be
   consumed after acceptance. The caller's remaining tick is not moved or rerun.
3. After all worker groups finish, validate owner removal/world identity and server quiescence. A cancelled
   or retired task acquires no ticket and performs no load.
4. Acquire a unique `forgia_owner_operation` ticket for the requested chunk at FULL/radius zero, without
   force-ticking the destination. Load through the original server-side `Level.getChunk` path.
5. Loading may invoke callbacks and process server work. Revalidate lifecycle/quiescence and owner identity
   **again** after it returns. If invalid, skip the owner callback with an explicit reason.
6. Invoke the callback at most once with the live owner and loaded chunk. Release the ticket in finally,
   including failed load/callback paths. A release exception is reported, with a distinct secondary failure
   suppressed without self-suppression. No operation is retried after its effects started.

Nested operations use distinct ticket keys, so the inner cleanup cannot remove the outer retention ticket.
The task enters RUNNING before loading; cancellation then returns false. Terminal states clear entity,
level, callback and position references. Failure handles retain their reported Throwable as before.

The engine checks its active batches and running/submitted leases both before loading and before the
callback. A main-thread service callback while workers still run is not sufficient. Loading is deliberately
not sent through the worker-barrier computation service while those workers hold their leases.

### Contract limits

- This stage loads **synchronously at server-thread completion**, so loading can delay the tick. It is not
  asynchronous loading, an independent regional clock, a general teleport transaction or destination recovery.
- It is a new opt-in operation boundary. No vanilla entity/mod tick is automatically rewritten to use it.
  Capturing a partially applied tick after an exception and submitting the whole body would still be replay
  and is invalid. Existing serial pearl/firework/skull policies remain.
- The action must use its supplied owner, chunk and immutable inputs. It must not retain the chunk/ticket for
  later unsynchronized writes or capture other live entities as assumed mutation authority.
- Owner validity and chunk availability do not freeze every arbitrary mod structure. The callback still
  needs a correct operation contract and event/result semantics.
- Failure to remove a ticket is reported; a throwing custom ticket implementation cannot be claimed to have
  successfully cleaned up simply because a finally block attempted it.
- The shared batch bound remains; this change does not make queue-full melee admission nonfatal.

## Folia reference and adaptation boundary

Pinned reference: Folia dev/1.21.1@`2e7bc0721af95196c85500c7bb136aeea0bc12ce`.
The inspected mechanisms are ServerChunkCache's ownership-checked sync load through Moonrise, regional task
queues, and entity teleport operations with ticket-backed destination preparation and revalidation. Forgia
independently uses its own batch completion, existing owner handles and vanilla chunk tickets.

No Folia/Moonrise body is copied, no unsupported Bukkit API is inserted, and no foreign projectile owner is
replaced with null. The full Moonrise async chunk system and Folia's independent clocks are not implemented
here. The adaptation is the operation boundary and lifetime discipline, not drop-in Folia behavior.

## Verification results

The final combined tree passed **922 JUnit tests**, with zero failures, errors or skips, formatting and build
checks (4m5s). A fresh-world GameTest run passed **all 170 required tests**, including the new
`chunkBackedOwnerOperation` real chunk load/nested ticket test (explicit PASSED result; Gradle exit zero,
1m14s). The preexisting auxiliary `testFeatureGating` diagnostic remains failed separately.

All 758 patches reapplied with zero rejects and no patch-cache hit; forced patch regeneration was byte-identical.
The new entity/machine replay safety tests failed before replay removal and passed afterward. Source and
patch-added lines have no trailing whitespace; canonical patch context-only space lines remain intact.
No live panel/server was modified or restarted, and no modpack performance/soak or TPS improvement is claimed.

## Regression evidence

Before the fix, the new entity/machine safety tests failed because the expected error was absent: the worker
attempt was silently replayed. They now verify real ItemStack consumption/output counts and exactly one tick
call; the entity test also checks one Pre event and no successful Post event after failure.

The self-driven regression invokes the actual ServerChunkCache worker guard for a missing required FULL
chunk after an inventory effect. It reports the refusal once and never consumes the second item. A real bound
furnace test checks actual inventory and verifies its crash-report callback runs on the main thread. Explicit
pre-body type fallback and the original loaded-neighborhood admission tests remain.

Owner-operation tests exercise actual native workers and the completion barrier: immutable position,
source consumption, cancellation, owner retirement before/during loading, world change during loading,
lost quiescence proof, load/callback/release failures, a reused load/release Throwable, continued draining of
later accepted requests, terminal reference cleanup, and shutdown retirement before loading.

A new GameTest uses a real server level to load a previously unloaded distant chunk and inspects actual
DistanceManager tickets during nested operations. It requires ticket counts 1 -> 2 -> 1 -> 0 and exactly one
callback per operation. It tests real loading/ticket behavior on the quiescent main-thread API path; native
worker transport is covered separately by the engine regressions, not falsely combined into one runtime test.
