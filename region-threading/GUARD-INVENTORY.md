# Guard inventory: every operation a region worker may reach

A native region worker ticks entities, block entities and chunks of one region. Everything it touches is either
its own region's storage, a published snapshot, or a refusal. This file is the checklist: **every new guard, and
every new vanilla entry point a worker can reach, must be added here with its class and its test before the
change is committed.** An entry point that is not in this file is an untested crash waiting for a mod to find it.

Classification:

| Class | Meaning |
|---|---|
| **LOCAL** | answered from the leased region's own storage; out-of-region parts are clamped or truncated and counted |
| **PUBLISHED** | answered from an immutable snapshot the server thread publishes once per tick; at most one tick stale, counted |
| **DEFERRED** | the worker records the intent, the server thread applies it at the batch barrier (mutations only) |
| **REFUSED** | no answer exists; the guard names the operation and throws, and the counter records it |

## Entity queries

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| `LevelEntityGetter.get(int)` / `ServerLevel.getEntity(int)`, `getEntityOrPart(int)` | LOCAL | `RegionAwareEntityGetter.regionScopedLookup` + `RegionState.entityById` | `RegionEntityQueryPathsTest.byIdLookupOnAWorkerAnswersFromItsRegion`, `byIdLookupOfAnEntityWhoseRemovalIsDeferredIsRefused` |
| `LevelEntityGetter.get(UUID)` / `ServerLevel.getEntity(UUID)` | LOCAL | same, `RegionState.entityByUuid` | `byUuidLookupOnAWorkerAnswersFromItsRegion`, `byUuidLookupOfAForeignEntityIsRefused` |
| `LevelEntityGetter.getAll()` / `ServerLevel.getAllEntities()` | LOCAL, truncated | `RegionScopedEntityQuery.leasedEntityIndex` | `getAllOnAWorkerAnswersOnlyItsRegion`, `getAllOnACompleteRegionIsNotTruncated`, `getAllOnAWorkerOfALevelWithoutAPublishedIndexKeepsTheLegacyRejection` |
| `LevelEntityGetter.get(EntityTypeTest, consumer)` / `ServerLevel.getEntities(EntityTypeTest, ...)` / `getDragons()` | LOCAL, truncated | same | `typedLookupOnAWorkerAnswersOnlyItsRegion`, `typedLookupOnTheServerThreadKeepsVanillaSemantics` |
| `LevelEntityGetter.get(AABB, Consumer)` | LOCAL, clamped | `RegionScopedEntityQuery.tryGetInBox` | `boxConsumerOnAWorkerAnswersTheClampedLease` |
| `LevelEntityGetter.get(EntityTypeTest, AABB, AbortableIterationConsumer)` | LOCAL, clamped, player union | `tryGetByTypeInBox` | `typedBoxConsumerOnAWorkerAnswersAPlayerOfTheLeaseOnce`, `typedBoxQueryOnAWorkerStopsAtMaxWhileItAppendsThePlayerUnion` |
| `Level.getEntities(Entity, AABB, Predicate)` | LOCAL, clamped | `tryQuery` (no player union: physics stays clamped) | `boxQueryOnAWorkerAppendsThePartsOfTheOwnersItAnswers`, existing range tests |
| `Level.getEntities(EntityTypeTest, AABB, Predicate[, List, int])` | LOCAL, clamped, player union | `tryQuery` | `playerTypedBoxQueryUsesTheBoundingBoxOfTheSnapshot`, `playerTypedBoxQueryOnAWorkerAddsAPlayerOfAnotherRegion` |
| `players()`, `getPlayers(Predicate, int)`, `getNearestPlayer` ×5, `getPlayerByUUID`, `getNearbyPlayers`, `hasNearbyAlivePlayer`, `getRandomPlayer` | PUBLISHED | `RegionPlayerSnapshots` + `RegionThreadingHooks.playersOfLevel` + the `ServerLevel.players()` patch | `workerReadsThePlayerSnapshotInsteadOfTheLiveList`, `playerSnapshotIsReplacedOncePerTick`, `playerSnapshotIsDetachedWithTheLevel` |
| query from a worker **without a lease**, level with **no published region index**, **another region's** entity, `EntityStorageGuardedGetter` | REFUSED | `RegionThreadingHooks.rejectLegacyEntityQuery(operation, path)` | the `*IsRefused` tests above |

## Entity mutations

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| `Entity.discard`, `remove`, `setRemoved`, `LivingEntity.remove`, `Slime.remove`, `Boat/ChestBoat/AbstractMinecartContainer.remove`, `FishingHook.remove` | DEFERRED (subclass removals refused) | deferred removal set, applied at the barrier | `RegionEntityRemoval*`, `RegionEntityTickPlanTest` |
| `Entity.move`, `moveTo`, `absMoveTo`, `setPosRaw`, `BlockAttachedEntity.setPos/move` | LOCAL when the entity is admitted; grantable while it is not yet admitted | `RegionEntityOwnershipScope` | `RegionEntityOwnershipLeaseTest` (incl. `aHangingEntityThatIsNotAddedYetMayBePositionedWhileItIsConstructed`) |
| entity admission (`addFreshEntity`, spawn), default worker mode | DEFERRED | lease batch admission (overlapping graphs admit each member once), or a per-level server-thread queue when a spawn/chunk worker has no entity batch; applied after region work | `RegionEntityAdmissionDeferralTest`, `RegionSpawnTest` |
| opted-in natural-spawn finalization/admission | PARKED SERVER | plan-level ordered mode; all executing spawn groups pause before full native finalization, passenger joins and cap/group callbacks; prior effects publish first, confirmed join result precedes next check, no candidate replay | `RegionSpawnAdmissionBarrierTest`, target-mod ordered integration, `ParallelApiTest`; see `NEARBY-SPAWN-ADMISSION.md` |
| required worker chunk request missing vanilla holder, mod-injected loaded storage | read-only server-thread lookup followed by existing foreign scalar guards | `findLoadedChunkForWorker` consults public `getChunkNow` through serviced batch; optional Sable-owned gap sentinel only within its claimed plot domain; no ticket/load/future and no mutation lease | `RegionWorkerChunkGuardTest`, `RegionSelfDrivenEntityTickTest` (real Enderman gaze/LOS), `SableLoadedChunkLookupTest`; see `SABLE-LOADED-CHUNKS.md` |
| entity tick mid-body chunk refusal | TRUNCATED, no whole-tick replay | `RegionThreadingEngine.truncateIfChunkUnavailable`: the tick is lost and counted in `truncatedEntityTicks`, the runtime class is declared server-thread-only with reason `chunk_unavailable`, the group continues with its remaining entities, and only a chunk refusal is truncated - every other throwable keeps the reported-failure path | `RegionWorkerChunkGuardTest` (`entityWhoseTickRefusesAChunkIsTruncatedWithoutReplayAndTheGroupContinues`, `missingChunkAfterInventoryAndDropEffectsIsTruncatedWithoutReplayingTheTick`, `unknownMissingChunkIsStillRefusedWithoutRequestingALoadOrFuture`), `RegionSelfDrivenEntityTickTest`, `RegionEntityTickPolicyTest` (truncate once, then the class ticks on the server thread) |
| block-entity tick mid-body chunk refusal | REPORTED FAILURE; no whole-tick replay | failed ticker stops its group, accepted effects drain and the report occurs after the workers; the real bound ticker carries the report context to the server thread; only pre-body checks may fall back | `RegionBlockEntityTickTest`, `RegionBlockEntityTickEligibilityTest` (inventory/output/event counts and real chunk guard/ticker) |
| `submitToOwnerAfterChunkLoad` | explicit owner operation at quiescent server completion | shared admission/cancellation budget; immutable position, source not retained, temporary FULL ticket, original server loading, post-load lifecycle/owner check, callback once and ticket cleanup; synchronous, not automatic Java resumption | `RegionSelfDrivenEntityTickTest` (retirement, cancellation, failure/drain, cleanup/ref release); real nested-ticket GameTest; see `CHUNK-OWNER-OPERATIONS.md` |
| source-authorized owner operations through `entityInteractions().submitToOwner()` | DEFERRED from a source-owning entity worker; quiescent server thread inline | source checked only at admission and not retained; callback receives owner only plus caller-captured immutable input; owner removal/world change skips; same cancellation/capacity/shutdown/failure protocol as strict interactions | `RegionSelfDrivenEntityTickTest` (source consumption, captured position, owner retirement/world change, cancellation/ref release, failure, async/source authorization and shutdown) |
| whole source/target operations submitted through `RegionThreading.entityInteractions()` | DEFERRED from a source-owning entity worker; inline only on a quiescent server thread; other contexts rejected | bounded per-batch requests, execution-time entity/level revalidation, explicit cancellation/retirement/failure states; common path used by melee admission | `RegionSelfDrivenEntityTickTest` (completion, cancellation/ref release, removal/dimension change, source ownership, capacity, shutdown and failure) |
| `Mob.aiStep` virtual item pickup dispatch (including Fox/mod overrides) | DEFERRED before candidate ItemStack/predicate/body access on native workers | strict source/target task, live eligibility at quiescent completion; all native candidates deferred, including non-ticking local items; original grief event and inline/virtual bodies retained | `RegionSelfDrivenEntityTickTest` (real Fox remainder/spit counts, competition, delay/removal/world/death, denial, override failure and ordinary partial equipment pickup); see `MOB-ITEM-PICKUP.md` |
| vanilla Brain `MeleeAttack`, `MeleeAttackGoal`, `OcelotAttackGoal` virtual attack calls | LOCAL for pairs in the same entity lease; DEFERRED otherwise (players always deferred) | `deferMeleeAttack` admits the entire virtual `doHurtTarget` call before its side effects and runs it once at the entity-batch barrier; cooldown/swing remain in the source tick; target state is live at application, not snapshotted | `RegionSelfDrivenEntityTickTest` (foreign pufferfish position callback, both goal paths, local/unindexed/player targets, removal/dimension-change skip, failure accounting) |
| direct mod attacks outside these AI call sites, projectiles, explosions and arbitrary target field writes | NOT COVERED by the melee admission hook | callers still need ownership-safe entry points; never bypass `setPosRaw` or replay a partially applied attack | guard tests; further combat API work required |
| region transfer (`record/commit/abortEntityRegionTransfer`), `EntityIdentityLedger`, `FixedEntitySectionPartitions` | REFUSED | they mutate identity and section storage; the caller defers to the barrier | `RegionEntityTransferTest` |
| pearl end-gateway owner notification | owner operation, immediate on proven server-thread completion or queued from a source-owning entity worker | `submitPearlGatewayOwnerOperation` forwards immutable BlockState to the live owner through `submitToOwner`; no-engine and quiescent removed-source continuations retain vanilla call; no parallel pearl flight enabled | `RegionSelfDrivenEntityTickTest` (real onInsideBlock dispatch, source consumption, nested owner callbacks, no-engine/removed-source behavior, unowned-source refusal) |
| `ThrownEnderpearl` tick, impact and end-gateway contact (including subclasses) | SERVER THREAD ONLY | class admission policy `ender_pearl_owner_interaction` refuses worker collection before the complete tick begins; preserves impact position, events, dismount, owner teleport/damage, endermite roll and pearl discard in vanilla order; flight also stays serial | `RegionSelfDrivenEntityTickTest` (real guarded connection teleport; real onHit event/cancel/damage/discard; gateway callback; subclass/non-player owner and both admission paths) |
| all `Projectile` subclasses | SERVER THREAD ONLY | pre-body `projectile_impact_callbacks`: impact hurt, enchantments and arbitrary mod callbacks may mutate owner/victim/third entities; more-specific projectile policies retain their own diagnostic reasons | `RegionEntityTickPolicyTest` (seven projectile families and passenger bypass), `RegionSelfDrivenEntityTickTest` (real spit and ShulkerBullet impact); see `SHULKER-PROJECTILE-FOLLOWUP.md` |
| `ShulkerBullet` impact and post-attack enchantments (including subclasses) | SERVER THREAD ONLY | pre-body `shulker_bullet_damage_and_enchantment_effects` retains damage/secondary owner effects; pinning Shulker itself does not cover another worker's reflected damage/teleport callbacks | `RegionSelfDrivenEntityTickTest` (real impact, foreign owner mutation probe, subclass admission); see `BLOCK-ENTITY-AND-SHULKER-SECOND-AUDIT.md` |
| `Shulker` lid opening (including subclasses) | SERVER THREAD ONLY | pre-body `shulker_peek_push` policy: real `onPeekAmountChange` directly calls foreign/player `Entity.move(MoverType.SHULKER)`; preserve the complete tick and pushing without replay or weakening guards | `RegionEntityTickPolicyTest` (real peek + move/position update across a border, subclass refusal and negative worker guard control); see `SHULKER-PEEK-CRASH.md` |
| `FireworkRocketEntity` attached flight and explosion; `WitherSkull` impact/heal/explosion (including subclasses) | SERVER THREAD ONLY | pre-body class policies retain complete vanilla tick until an ownership protocol covers every involved entity; vehicle admission recursively considers these passengers | `RegionSelfDrivenEntityTickTest` (real explosion/impact bodies with foreign-entity mutation probes, subclass and vehicle admission); see `CRASH-AND-LOGIC-AUDIT.md` |
| delayed out-of-lease `handOffEntityAddition` | DEFERRED with execution-time level check | recheck removal and `entity.level() == requestedLevel` at the barrier before original admission | `RegionSelfDrivenEntityTickTest.handedOffAdmissionRevalidatesTheEntityLevelBeforePublishingIt` |
| `ServerPlayer.teleportTo/dismountTo/teleportRelative`, `ServerGamePacketListenerImpl.teleport`, `ServerLevel.addPlayer` | REFUSED | a player is ticked by the server thread only; the transition is a recorded event | `PlayerRegionTransitionTest` |

## Block, chunk and block-entity storage

Deferred admission counters count records, not entities. A record can contain a riding graph, and the legacy
`droppedDeferredEntityAdditions` total includes intentional removals and vanilla admission refusals (including
cancelled join events). The status command reports separate reasons and one bounded string sample per reason;
`PARTIAL_ADMISSION` means some members were admitted and must not be reported as an entirely lost graph.

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| `Level.setBlock` inside the lease | LOCAL | vanilla write on the region's own chunk | redstone equivalence suite |
| `Level.setBlock` outside the lease | DEFERRED | recorded write, applied by the target region's worker in the same tick's fixpoint | `deferred-cross-region-writes` tests |
| block entity with data, block-entity removal, heightmap-only write, multi-position move, non-representable write | REFUSED (no-replay) | `RegionWorkerChunkUnavailableException` reason | same |
| `LevelChunk.getBlockState` / `getBlockEntity` of a foreign chunk | LOCAL read via seqlock, or foreign block-entity read answers `null` (counted) | `chunkVersion` seqlock | `versioned-foreign-chunk-reads` tests |
| `LevelChunk.setBlockState`, `setBlockEntity`, `removeBlockEntity`, `Heightmap.set` on a foreign chunk | REFUSED before mutation | `refuseForeignChunkMutation` | `RegionChunkTickSharedStateTest` |
| worker chunk load / unload | REFUSED; a neighbor update whose own-region target is unloaded transfers the remaining cascade to the server thread | `RegionWorkerChunkUnavailableException`; detached `RegionCollectingNeighborUpdater` continuation, preserving stack progress, scope and chained-update count; real callback failures still propagate | `RegionChunkTickTest` (handoff, progress, reentrancy, budget, failure), `RegionBorderUpdateTest` (propagation and capture priority), `worker-chunk-guard` tests |
| `Block.dropResources` ×3 and `popResource` drop capture | LOCAL call scope, isolated per thread and nested loot invocation | `BlockDropCapture` stack with suspended collection during event/after-break callbacks and finally cleanup; event cancellation/mutable drops preserved, exceptions not replayed | `BlockDropCaptureTest` (native-worker interleave/null reproduction, all overloads, nesting, cancel, exact successful counts, listener/loot/after-break failures and worker reuse) |
| block entity tick | LOCAL for admitted types, server thread otherwise | `RegionBlockEntityTickPlan` + admission cache; quiescent server-thread fallback retains one lease per touched region until its ordered fallback loop ends, with inline cross-region effects and no worker admission changes | `RegionBlockEntityTick*` (scope ordering, exception cleanup, cross-region visibility, concurrent-batch and nested-context refusal) |

## Level state

| Entry point | Class | Implementation | Test |
|---|---|---|---|
| weather, day time, `ServerLevel` global fields | REFUSED from a region phase | `LevelTickPhaseState` | `RegionGlobalPhaseTest` |
| cross-region messages | DEFERRED | `RegionMailbox`, bounded, at-most-once | `RegionMailboxTest` |
| cross-region game events (sculk) | DEFERRED, replayed in the same tick | `GameEventDispatcher` capture + replay | sculk equivalence tests |
| `ServerLevel.getFreeMapId/getMapData/setMapData` | main-thread registry operation from a serviced worker batch | complete original call executes via `runMapStorageOperation`; unserviced native workers reject before cache access; returned map data is live and lookup grants no mutation authority | `RegionSelfDrivenEntityTickTest` (native-worker map allocation/publication/read, failure identity, outside-batch rejection) |
| `MapIndex.getFreeAuxValueForMap/save` | synchronized global compound counter | counter allocation, encoding and dirty access share the counter map monitor; numbering and NBT schema retained; generic file-save acknowledgement remains separate | `MapIndexConcurrencyTest`; see `MAP-AND-IO-CONCURRENCY.md` |
| `IOUtilities.withIOWorker/waitUntilIOWorkerComplete` | ordered global I/O task queue | atomic append and prefix wait; no executor/task callbacks under queue monitor; failed tasks do not skip successors; self-wait rejected | `OrderedIOQueueTest`, `IOUtilitiesWorkerTest` |
| named loot random sequences (`RandomSequences.get/reset/clear/save`, DirtyMarkingRandomSource) | registry/default tuple synchronized; each generator's mutations and NBT encoding mutually exclusive | registry snapshot before encoding/callbacks; underlying-generator monitors shared by wrappers; raw callback generator access and generic file-save acknowledgement outside this contract | `RandomSequencesConcurrencyTest` (sequential equivalence, native worker loot contexts, concurrency, reset/default/save, callback reentrancy and codec roundtrip); see `NAMED-RANDOM-SEQUENCES.md` |
| POI queries | LOCAL + PUBLISHED snapshots | `RegionPoiStorage` | `RegionPoiStorage*` |
| lighting, block updates, section updates, spawns, scheduled ticks | DEFERRED | batch queues, applied in a fixed order at the barrier | `RegionTickBatch*` |

## Experimental mod API

The supported facade for mod registration and natural-spawn preparation is documented in
[MOD-PARALLEL-API.md](MOD-PARALLEL-API.md). Registration does not override a conflict-table action.

| Entry point | Contract | Test |
|---|---|---|
| `RegisterParallelAdaptersEvent` / `ParallelApi.Registrar` | server-scoped, frozen after registration; duplicate id, API-major mismatch and registration overflow reject publication; stopped services are invalidated | `ParallelApiTest` |
| `ParallelApi.Server.currentPhase` | main-thread service while a worker batch is active is SERVER_SERVICE, not quiescent; no ownership is granted by the phase | `RegionSpawnTest.preparedSpawnDataReachesTheRealWorkerBodyButNotMainThreadService` |
| `NaturalSpawnPreparation` at `RegionSpawnPlan.accept` | only before the whole chunk spawn body; Ready snapshots are scoped to the body, ServerRequired selects the existing complete serial body; preparation failures propagate, no automatic replay | `ParallelApiTest`, `RegionSpawnTest.adapterFallbackRunsOneCompleteSerialSpawnWithoutWorkerSubmission` |
| `ParallelApi.prepared` | immutable data contract; thread-local to the active unit, hidden in serial fallback, cleaned on exception; retired or wrong-server/unit work cannot execute | `ParallelApiTest` |

## Third-party reflection

Forgia reaches into a mod's own API in exactly one place: the optional Sable plot-gap adapter. The rule that
applies to every such place, added after `crash-2026-11-09_17.01.56-server.txt` stopped the 178-mod pack 56
minutes after start:

| Rule | Why | Test |
|---|---|---|
| Resolve one member by name and descriptor (`MethodHandles.publicLookup().findStatic/findVirtual`), never `Class.getMethod`, `getDeclaredMethod`, `getMethods` or `getDeclaredMethods` | Every one of those builds the class's whole member array, and building it resolves the types in *every* signature. On a dedicated server the runtime dist cleaner refuses a signature mentioning a client-only class, so the enumeration throws inside the binding | `SableLoadedChunkLookupTest.aSignatureTheLoaderCannotResolveDisablesEnumerationButNotTheBinding` |
| A binding that cannot be resolved is "compatibility off", not an error: absent mod, other API version, non-public container, unresolvable member or field type | The adapter exists to avoid a crash; it may not become one. The caller keeps the plain answer it had before the adapter existed | `SableLoadedChunkLookupTest` (wrong API, other descriptors, inaccessible members) |
| Only a binding that *did* resolve and then answers with a wrong-typed object is an explicit compatibility error | A wrong sentinel is an active misconfiguration that would otherwise be substituted for real terrain | `SableLoadedChunkLookupTest.wrongSentinelTypeIsAnExplicitCompatibilityFailure` |
| No reflection into a mod class from a worker thread | The lookup runs on the server thread through the serviced batch; the worker receives the finished chunk | `RegionWorkerChunkGuardTest` |
