# Named loot random sequences: concurrency contract

This implements the first bounded candidate from
[FOLIA-PRESERVING-ADAPTATIONS.md](FOLIA-PRESERVING-ADAPTATIONS.md). It concerns
`RandomSequences`, used through `LootContext.Builder` -> `ServerLevel.getRandomSequence(id)`, not the
separate region-aware `level.random` sources. No online fault is inferred solely from source inspection.
The implementation is written for Forgia's NeoForge source shape; Folia bodies are not copied.

## Defects reproduced before the patch

Two wrappers obtained for one named sequence refer to the same mutable Xoroshiro generator. Synchronizing a
wrapper alone would be insufficient. With the old implementation a two-wrapper probe recorded concurrent
entry into the same generator. A second deterministic test held the actual generator's monitor during a
mutation while `RandomSequences.save(CompoundTag, Provider)` still encoded it without coordination. Encoding
reads multiple state words, so synchronization must cover both mutation and encoding.

The original eight-party lookup test initially used a common pool smaller than its barrier party count;
that test-fixture timeout is separate from the reproduced production defects. The fixture now uses an
explicit eight-thread executor. Do not cite its original timeout as a server bug.

## Implementation and lock boundaries

- A registry monitor protects get-or-create, seed defaults as one tuple, reset, clear and copying the current
  entries. The original map type, seeding formula and sequence ID semantics are retained.
- Each DirtyMarkingRandomSource operation locks the **underlying generator**. Different wrappers therefore
  coordinate, while unrelated named generators may draw concurrently. The algorithm and draw methods are
  unchanged. `triangle` and `consumeCount` also retain a single generator lock for their complete default-method
  operation, so another caller cannot interleave its draw into those calls.
- `fork` and `forkPositional` consume the original generator under its lock and return vanilla's new independent
  generator/factory. Those returned objects are not globally shared/thread-safe by virtue of this wrapper;
  callers must retain their own ownership discipline.
- NBT creation copies registry entries and the defaults under the registry monitor, then releases it. Each
  selected generator is encoded under its own monitor, the same one its draw wrappers use. A concurrently
  reset ID may have an old entry selected by the snapshot; it is never silently rebound halfway through encoding.
- `forAllSequences` traverses a copied registry outside locks. Its callback can submit/reset/create entries
  without deadlocking or corrupting an active iterator. The existing raw RandomSequence callback value remains
  available for API compatibility. Direct concurrent mutation of `value.random()` bypasses this wrapper's
  contract; concurrent callers should obtain the protected source through `get(id)`.
- Dirty reads/marks on this class coordinate on a separate short-lived monitor, never held while acquiring
  registry/generator locks. Draws mark dirty in finally after their operation, and resets/clear/default changes
  mark pending state. Loading the saved data finishes clean. No world callback or I/O is newly invoked while
  holding the registry lock; traversal callbacks run outside generator locks too.

Lock order: registry -> short dirty marking for registry mutations; generator -> short dirty marking for draws;
NBT snapshot releases registry before taking a generator monitor. Dirty access never acquires registry or
generator monitors. This avoids the registry/generator lock inversion that a global synchronized save could
create. Vanilla primitive generation is the only work performed by ordinary draw wrappers under generator lock.

## What is preserved

- Existing random algorithm, seed defaults and named stream identities.
- Existing NBT schema and generator-state codec; save/reload continues with the same next encoded state.
- Sequential mixed calls, bounded integers, Gaussian, reseeding, fork and positional-factory behavior compared
  with the unwrapped vanilla generator.
- Reset replaces the registry entry; a previously obtained wrapper **still refers to its old generator**,
  as it did before. Clear likewise does not invalidate an already obtained wrapper.
- LootContext's explicit seed override takes priority and does not consume the named stream.
- A shared primitive draw stream neither duplicates nor loses values when many callers draw from the same ID.

## Deliberate limits

This does **not** promise which concurrently ticking block gets each value. Scheduling determines the
linearized call order. Exact single-threaded per-block allocation would require a defined ordered loot protocol,
not merely a lock. It does not reseed per worker, fork a new stream for each region, or claim a whole loot-table
execution is atomic; loot can make many individually protected calls separated by callbacks.

The snapshot is consistent **per selected stream**, not a global freeze of every stream at one instant. Registry
membership/defaults correspond to its initial copy; different generators may be encoded at different moments.
NBT iteration order is not a byte-order guarantee. The field names and stream values remain compatible.

The original generator codec does not serialize every derived cache (for example an outstanding Gaussian spare).
The patch retains that codec rather than inventing an incompatible save format; do not promise that the next
post-reload Gaussian always equals a pre-reload cached Gaussian. Sequential Gaussian behavior is tested without
reloading; reload tests compare the serialized generator state and primitive continuation.

`SavedData.save(File, Provider)` still needs the separate revision/write-acknowledgement work described in the
candidate audit. This patch makes direct random-state NBT encoding coherent and dirty visibility defined for
RandomSequences; it does not prevent a later generic file-save acknowledgement from clearing a newer dirty mark,
nor solve serialization/I/O failure retry for all mod SavedData. Normal save hooks and quiescence requirements
remain in force. No asynchronous file durability or recovery guarantee is added here.

## Verification results

The final combined tree passed 887 JUnit tests (including all 12 named-sequence regressions), formatting and
build checks. A fresh-world GameTest run passed all 169 required tests and exited successfully. The existing
auxiliary testframework `testFeatureGating` failure remains separate and unchanged. Patch reapplication had
zero rejects, and forced patch regeneration was byte-identical after the roundtrip without a patch-cache hit.
These are local checks; the repair has not been validated on a deployed modpack or measured for performance.

## Validation approach

`RandomSequencesConcurrencyTest` covers:

- sequential mixed output against the original RandomSequence for all world/name seed-default combinations;
- eight concurrent first lookups and total primitive output/state advancement;
- two wrappers sharing a generator, deterministic encoding-versus-mutation coordination, and independent streams;
- defaults changed while other threads first-create named entries, without mixed default tuples;
- lookup/reset/clear/save interleaving, with every resulting stream codec-decodable;
- reset and old-wrapper bindings, save schema/roundtrip, mutation dirty marks and clean loading;
- snapshot traversal with callbacks which reenter from another thread;
- two actual native region workers building real LootContexts for one named stream, plus explicit seed priority.

These are concurrency/data-state checks, not a live modpack performance comparison or proof that every mod's
raw generator access is safe. Do not claim a TPS gain from this repair without measurement.
