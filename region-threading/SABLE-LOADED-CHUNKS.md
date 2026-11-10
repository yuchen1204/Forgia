# Sable plot chunks and worker line-of-sight reads

## Incident and verified cause

The .39 report `crash-2026-11-09_11.51.15-server.txt` fails in
`EnderMan.isLookingAtMe -> Player.hasLineOfSight -> BlockGetter.clip -> Sable.originalClip -> getBlockState`
when a worker requires FULL chunk `(1280065, 1280960)`. The enderman itself is at
`(-2733.28, 28, -3390.72)`, with nearby players around `(-2685, 65, -3335)`. The huge request is not evidence
of the enderman being at a corrupted world position. Sable projects rays into its distant plot storage.

Inspection of the installed-version Sable 2.0.5 bytecode establishes:

- BlockGetterMixin projects rays into SubLevel coordinates and reads through its originalClip path.
- ServerChunkCacheMixin injects getChunkNow to look up plot chunks in SubLevelContainer, independently of
  vanilla visible holders.
- Its getChunkFutureMainThread injection returns an available plot chunk, or Sable's existing
  `sable$emptyChunk` sentinel for a missing position **inside its own declared plot domain**.

Forgia's worker getChunk path queried only vanilla visible holders and refused the request before reaching
these integrations. The original main-thread getChunk path reaches Sable's injected future method instead.
Consequently, adding load radius around the enderman, disabling the guard or retrying its entire tick would
not repair this lookup incompatibility.

The report does not tell whether the requested plot column actually contained data or was a gap. Both
provider cases are handled below. This diagnosis does not attribute all Sable world/physics behavior to
these two methods or assert that every Sable API is now safe on workers.

## Worker lookup repair

The existing vanilla visible-holder fast path remains first. On a **required** request with no immediate
holder answer, Forgia now consults `findLoadedChunkForWorker` through the existing batch-aware main-thread
computation service:

1. Require an actual native worker in a serviced tick batch and a live server.
2. Execute the public `getChunkNow(x,z)` on the actual server thread. This retains a mod's injected loaded
   lookup and keeps its mutable registry access off workers.
3. If it supplies a chunk, return that exact instance. No new chunk is allocated and no load/future is issued.
4. If no chunk is supplied, try the narrowly scoped Sable plot-gap compatibility path below.
5. Back on the original worker, keep the existing version/foreign-read bookkeeping and scalar-read guards.
   The returned chunk is visibility for reads, not permission to mutate it.

A non-required probe retains its previous fast null behavior, rather than introducing a server-thread
roundtrip for every missing pathfinding cell. Ordinary off-thread callers, main-thread caching, chunk
future rejection, and required unknown/missing-world refusals retain their existing behavior. No main-thread
getChunk/load is invoked by this fallback. Exceptions from the provider propagate without replaying the
entity tick or substituting an absent result.

The computation service can run while other groups remain active, so it is restricted to non-loading lookup:
it does **not** run arbitrary chunk publication, generation, ticket lifecycle or a whole LOS callback on the
server during that interval. Custom lookup providers must themselves obey the contract of getChunkNow. Their
chunk content still requires the established read guards. A mod injection doing hidden loading/mutation
inside its supposedly non-loading lookup is not made safe by this transport.

## Sable's existing plot-gap result

`SableLoadedChunkLookup` is optional and has no compiled Sable dependency. Binding is enabled only if the
transformed ServerChunkCache contains Sable's `sable$emptyChunk` field, then resolves the known
SubLevelContainer.getContainer(ServerLevel) / inBounds(int,int) API. It caches resolved method handles,
not levels, containers or chunks.

### Binding rule: resolve one member, never enumerate them

The .40 build bound this API with `Class.getMethod`, which builds the class's whole member array, and
building the array resolves the types in *every* signature. On a dedicated server the runtime dist cleaner
refuses a signature that mentions a client-only class, so binding threw
`Attempted to load class net/minecraft/client/multiplayer/ClientLevel for invalid dist DEDICATED_SERVER`
from inside `computeValue`. That is not a compatibility failure a server may die on, and it did:
`crash-2026-11-09_17.01.56-server.txt` stopped the 178-mod pack 56 minutes after it started, and the operator
rolled the core back to vanilla NeoForge.

Two rules replace it:

- **Resolve by name and descriptor** (`MethodHandles.publicLookup().findStatic/findVirtual`), which does not
  touch the other signatures. `Class.getDeclaredMethod` is no better than `Class.getMethod` here: both build
  the same array. Verified on this JDK with a container whose unrelated signature is not resolvable -
  `getMethod` throws `NoClassDefFoundError` where `findStatic` succeeds.
- **`findStatic` matches the descriptor exactly; it does not widen a covariant return.** Sable declares three
  overloads of the same name:

  ```
  public static SubLevelContainer        getContainer(Level)
  public static ServerSubLevelContainer  getContainer(ServerLevel)
  public static ClientSubLevelContainer  getContainer(ClientLevel)
  public boolean inBounds(int, int)
  ```

  Asking for `(ServerLevel)SubLevelContainer` matches nothing (`NoSuchMethodException`), even though
  `getMethod` would have accepted it. Bind the `(Level)` overload, whose declared return is exactly
  `SubLevelContainer`, and `asType` it to `(ServerLevel)Object`. The .41 build shipped the wrong descriptor and
  the adapter disabled itself on the live server - silently and correctly, which is how it was noticed.
- **Every unavailable binding is "adapter off", never an error**: Sable absent, Sable present with another
  API version, a non-public container, a member that cannot be resolved, a field whose type cannot be
  resolved. The caller then keeps the plain loaded-chunk answer it had before this adapter existed. Only a
  container that *did* bind and then answers with a wrong-typed sentinel is an explicit compatibility error.

A missing required vanilla chunk still refuses normally when the adapter is off; that refusal is the .39
failure mode and is tracked separately, not fixed by this guard.

After public getChunkNow returned null, on the server thread:

- Obtain the current level's Sable container.
- Require its inBounds check to claim the requested coordinates.
- Return its already-created EmptyLevelChunk sentinel, matching Sable 2.0.5's main-thread gap semantics.

Outside that domain, with no container or with no Sable mixin, return no answer so the original missing-chunk
refusal remains. A mismatched API/sentinel is an explicit compatibility error, not an empty-world fallback.
The original provider exception/cause is preserved. Forgia does not manufacture an air chunk for missing
world terrain, and does not turn a loaded/solid plot chunk into the sentinel.

This uses Sable's injected field/API and therefore needs re-audit if Sable changes its storage contract.
Only the inspected 2.0.5 implementation is reference evidence here. Generic mods with getChunkNow-injected
loaded chunks can use the first stage without this Sable-specific gap behavior.

## Behavioral limits

- No automatic chunk loading, wider query, owner filtering, false LOS fallback, enderman serial type policy
  or whole-tick replay is added.
- Main-thread results are returned to the same suspended worker call, preserving its returned LOS boolean
  and avoiding replay of any earlier entity tick effects.
- The repair preserves the lookup paths inspected here. It does not solve foreign-owner mutation, Sable pose
  snapshots or every moving-structure concurrency interaction.
- Loaded chunk content can still become unstable and is subject to existing refusals. A chunk reference
  returned by a lookup is not a new lease or transaction.
- A missing required vanilla chunk still reports a normal failure. Existing explicit chunk-backed owner
  operations and pre-body type declarations remain the tools for operations requiring real loads.

## Verification results

The final local tree passed **944 JUnit tests**, with zero failures, errors or skips, formatting and build
checks (4m1s). A fresh-world GameTest run passed **all 170 required tests** and Gradle exited successfully
(1m13s). The preexisting auxiliary `testFeatureGating` diagnostic remains failed separately.

All 758 patches reapplied with zero rejects and no patch-cache hit; forced regeneration was byte-identical.
Three selected negative controls failed after removal of the worker fallback statement, and the restored
code passed the full worker chunk, adapter and entity suites. Documentation link and diff checks passed.
No panel core/config changes, restarts or deployment were performed in this follow-up. The real Sable
moving-plot runtime still needs a controlled pack re-test; the local GameTests do not include Sable.

## Tests and evidence boundaries

RegionWorkerChunkGuardTest uses actual native workers, the real patched ServerChunkCache worker method,
vanilla holder misses, and controlled public lookup injections. It covers a loaded chunk, a provider's own
empty instance, a truly missing chunk and provider failure; no chunk future or retention ticket may be
requested. The original worker resumes once and keeps the exact object/error.

RegionSelfDrivenEntityTickTest runs real EnderMan.isLookingAtMe and Player.hasLineOfSight methods with
nearby real-valued positions from the report. The clip boundary models a plot-storage read at the reported
huge chunk coordinates: an available solid chunk must remain occluding; air must retain visible results.
This is a controlled boundary regression, not a simulation of Sable's complete pose/projection math.

SableLoadedChunkLookupTest exercises the adapter's actual resolution path using test member bindings, without
installing fake Sable classes. It checks exact sentinel identity, domain and absent-container rejection, wrong
sentinel type and original cause propagation, and that a container with another API, other descriptors or
inaccessible members disables the adapter instead of throwing. One case defines a copy of the container
through a class loader that hides a type used only in an unrelated signature: enumerating that class throws
`NoClassDefFoundError` (the shape of the production dist-cleaner refusal) while the binding still succeeds.
It does not build or transform the full pack. Negative controls remove the worker fallback statement and
require the loaded-provider and LOS tests to fail.

The production lookup still needs a real-pack movement/LOS re-test to establish that all runtime injections
retain their inspected shapes. No server files, configuration, restart or rollback are part of the local repair.
