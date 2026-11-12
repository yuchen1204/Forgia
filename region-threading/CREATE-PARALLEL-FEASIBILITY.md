# Create 6.0.10 parallel-execution feasibility audit

Verdict: **feasible as a staged adaptation; unsafe as a block-entity type allowlist change**. There is no
verified Create worker ticker added by this investigation. Useful parallelism must isolate computations
or own a complete interaction component, not merely the chunk containing the ticker.

## Evidence and scope

Inspected the exact installed `create-1.21.1-6.0.10.jar`, SHA-256
`ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`.
No upstream source checkout/source archive was available locally; method-level inspection used CFR views
of the actual jar. Decompiled code remains in ignored `tmp/create-parallel-audit/decompiled/` and is not
copied into tracked files or redistributed. Paths below are relative to `com/simibubi/create/` there.
Also inspected the installed Create Addition 1.7.0 for the separately implemented electrical system.
This is a code audit, not runtime profiling, concurrency certification or a measured speedup.

## Concrete findings

| Area | Actual method/path | Shared effects and implication |
|---|---|---|
| Ticker wrapper | `foundation/blockEntity/SmartBlockEntityTicker.tick` | Sets level if absent, then directly invokes SmartBlockEntity.tick. No universal server-thread check and no serialization lock; the wrapper is not the principal obstacle. |
| Common base | `SmartBlockEntity.initialize/tick/lazyTick/attachBehaviourLate` | Initialization publishes BlockEntityBehaviourEvent; behaviour map is mutable, extensible by events, ticked on every common-base tick. Checking just the concrete machine's override misses behaviour/lifecycle callbacks. |
| Common kinetic tick | `kinetics/base/KineticBlockEntity.initialize/tick/validateKinetics/updateFromNetwork` | Initialization joins a network; tick can attach propagation, validate/remove sources and update an entire dirty network. updateFromNetwork changes member state and can invoke onSpeedChanged/sendData. A shaft with a simple subclass still inherits this path. |
| Kinetic registry | `kinetics/TorquePropagator.getOrCreateNetworkFor/onLoadWorld/onUnloadWorld` | Static ordinary HashMap of worlds to ordinary HashMaps of network IDs. Even different networks need safe registry publication/ownership; a lock per network alone does not fix concurrent outer-map mutation. |
| Stress/capacity | `kinetics/KineticNetwork.add/remove/updateNetwork/sync/calculateCapacity/calculateStress` | HashMaps keyed by live BE objects. Traversals can remove members, query world BEs and directly update all members. One remote member is enough to cross a Forgia region lease. ConcurrentHashMap would not make the multi-step world/state operation atomic. |
| Rotation topology | `kinetics/RotationPropagator.propagateNewSource/propagateMissingSource` | Recursively/iteratively follows connected neighbours, sets sources/speeds, triggers callbacks/synchronization and may destroy blocks. Local edges do not bound the transitive component to one chunk or region. |
| Belts | `kinetics/belt/BeltBlockEntity.tick/initializeItemHandler` and `belt/transport/BeltInventory.tick` | Controller owns shared inventory; segment handlers reference that same inventory. Controller processes funnels/tunnels/crushers/processing behaviours and transports live entities. Endpoints reach other machines and capabilities. One belt/controller is not necessarily the entire conflict component. |
| Funnels | `logistics/funnel/FunnelBlockEntity.tick/activateExtractor/activateExtractingBeltFunnel` | Simulates and executes inventory extraction, scans entities, creates/adopts ItemEntity and invokes belt insertion callbacks. Simulation is not a transaction; another writer between simulate/commit can change the outcome. |
| Presses | `kinetics/press/MechanicalPressBlockEntity.tryProcessInBasin/tryProcessInWorld/tryProcessOnBelt` | Mutates basin inputs, transported stacks or world ItemEntity; world processing can spawn outputs; awards/recipe callbacks remain in the path. Not a self-contained arithmetic tick. |
| Mixers/basins | `kinetics/mixer/MechanicalMixerBlockEntity.tick/getMatchingRecipes`; `processing/basin/BasinOperatingBlockEntity.getMatchingRecipes/applyBasinRecipe`; `BasinRecipe.apply` | Queries live item/fluid capabilities, checks heat/filter, simulates then executes extraction/drain/output and notifies contents changes. Machine and basin two blocks below form a coupled operation; external capability callers also matter. |
| Recipe search | `foundation/recipe/RecipeFinder.get`, `recipe/trie/RecipeTrieFinder.get` | Existing Guava caches already exist and are not plain unsynchronized HashMaps. That does not prove cached recipe objects, predicates or arbitrary third-party matches are pure, nor that offloading matching improves an already cached hot path. Profile first. |
| Multiblock storage | `logistics/vault/ItemVaultBlockEntity.tick/updateConnectivity/getControllerBE`; `fluids/tank/FluidTankBlockEntity.tick/updateConnectivity` | Formation/controller resolution and capability invalidation span blocks; tanks additionally tick boilers. Geometry of the local ticker is not geometry of the complete inventory/fluid resource. |
| Fluid networks | `fluids/FluidPropagator.propagateChangedPipe/resetAffectedFluidNetworks` | Walks connected pipes, wipes pressure, finds pumps and resets connection networks. Even though local traversal lists are method-local, the visited pipe/pump state is shared live state. |
| Deployer | `kinetics/deployer/DeployerBlockEntity.initialize/tick/startFistBump/activate` | Uses a fake player for world interactions, manipulates inventory, executes USE/PUNCH actions; fist-bump updates another deployer's fields. Generic item/event callbacks have a larger footprint than the arm's location. Keep full operations on server initially. |
| Wireless redstone | `content/redstone/link/RedstoneLinkNetworkHandler.getNetworkOf/updateNetworkOf` | World/frequency HashMaps and mutable link sets, iterates transmitters and calls receiving links. Atomic globalPowerVersion does not protect surrounding maps or remote writes. Geometric/different-kinetic-network ownership cannot prove independence. |
| Trains | `content/trains/GlobalRailwayManager.tick` | Separate global manager iterates/reorders moving/waiting trains and track/signal graphs. Not all industrial load lives in BE tickers; BE dispatch counts cannot measure or solve this load. |

## Electrical add-on is a separate dependency domain

Create's mechanical network is not its add-ons' FE network. Inspected Create Addition
`com/mrh0/createaddition/energy/network/EnergyNetworkManager`: static WeakHashMap of per-world managers,
mutable ArrayList of networks; manager.tick swaps/updates networks. `EnergyNetwork.push/pull/demand/tick`
mutates shared integer buffers and demand state with no synchronization.

`blocks/alternator/AlternatorBlockEntity.tick` produces energy then calls adjacent IEnergyStorage
receiveEnergy(simulate), extracts local energy, then calls receiveEnergy(execute). The receiver may be a
completely different mod. `blocks/electric_motor/ElectricMotorBlockEntity.tick` consumes energy and can
change generated rotation, coupling FE ownership back into Create's mechanical topology.

Thus assigning independent kinetic networks to separate workers is insufficient if an electrical wire,
shared inventory or wireless receiver connects them. At minimum the claimed resources must cover all
these external endpoints or those complete operations must stay serial. Other electrical add-ons were
not audited; no finding here certifies them.

## Feasible implementation routes

### 1. Profile, reduce serial work and isolate pure computations first

Record actual per-type/per-path wall and CPU time, cold initialization versus steady ticks, topology churn
and capability calls. The current BE worker/fallback ratio alone does not tell whether a simple shaft,
a recipe search, deployer initialization, train manager or mechanical network traversal dominates time.

Potential first experiment: prepare detached item/fluid inputs, recipe reload revision, heat/filter and
output capacity; run only **audited pure** candidate filtering on workers; validate all inputs/revisions
before applying the original operation once. Main-thread validation must include every external writer,
not just an adapter-owned revision that hoppers/capabilities bypass. Generic modded Recipe.matches,
mutable ItemStack/NBT/components and level-dependent predicates are not automatically pure.

Preserve original tie-breaking, callback order, RNG consumption and input/output/fluid conservation.
If the snapshot is stale, discard the uncommitted computation before effects. Never replay a machine
operation that already extracted inputs, emitted outputs or fired callbacks. Cache/pre-index improvement
may beat task offloading; no numerical benefit is asserted without measurement.

### 2. Parallel independent complete machine components

Longer-term route: assign each complete coupled machine component to one owner. All BEs within that
component run serially in their original order; genuinely independent components can run concurrently.
A component includes shared kinetic network, belts/controllers/basins/storage, and interacting fluid/FE
capabilities. Wireless signals and global manager effects require a defined serial phase or explicit
messages; indiscriminately joining everything risks creating one giant component and eliminating benefit.

Before admitting a component, atomically acquire all affected Forgia regions in canonical order and
exclude concurrent incompatible slices/external writers. Network registry mutation, initialization,
formation/split/merge, unloading and player block changes need a lifecycle phase or equivalent ownership
protocol. Discover the footprint while quiescent; an operation cannot acquire arbitrary extra regions
halfway through ticking or fall back by replay after effects. Unknown endpoints mean pre-body fallback.

Start with pinned-version, small, stable, self-contained assemblies entirely within one region; audit
exact behaviours and external capability endpoints. This is narrower than admitting all BEs by registry
ID. A full containment/dirty-state proof is required before each body; a steady-looking graph is not proof
that no validation/behaviour path can change it.

### 3. Preserve serial high-footprint operations initially

Deployers/fake-player actions, moving contraptions, trains, topology rebuilds and arbitrary third-party
capability interactions should remain serial in the initial adaptation. Their lightweight detached
calculations might later be isolated, but moving their whole event-producing body is not justified.
A global lock around every Create tick would preserve serialization and add scheduling cost; it would
not meet the performance objective. Repeating the ordered-spawn stop-all-workers approach for every
machine tick must not be presented as useful parallelism without evidence.

## Acceptance criteria before widening Forgia's allowlist

- Same inputs/outputs, fluids, FE/stress capacity, cancellations and relevant callback order against serial.
- Cross-region shaft/belt, shared controller, split/merge, unload, two producers racing for one input/output,
  mixed mod capability and player interaction tests, with zero foreign writes and no dropped tick bodies.
- Real transformed Create/add-on jars and all installed behaviour-changing mixins, not only mocked tickers.
- Simultaneous execution of independent components demonstrated separately from scheduler submissions.
- Paired repeated performance runs without concurrent Gradle/tests, warmup excluded, same initial world and
  workload, all preparation/validation/commit costs included. No claimed speedup from worker counters alone.

## Practical conclusion for this server

The current absence of a default Create allowlist is justified by actual shared writes, not by its ticker
wrapper. It also means today's region engine has not delivered industrial Create parallelism. A meaningful
adaptation is possible, but should start by measuring the most expensive concrete Create paths and selecting
one complete, bounded processing operation/component. Whole-mod or whole-type enablement would reproduce
the cross-entity/cross-resource failures already observed elsewhere in the server.
