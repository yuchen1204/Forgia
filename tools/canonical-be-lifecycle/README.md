# Canonical Coordinator lifecycle / installed-server soak

This runner exercises an **installed Minecraft 1.21.1 DedicatedServer** with the pinned Forgia NeoForge installer/universal. It uses the existing mixed Vanilla datapack, RCON, and external JVM MXBeans. It installs no observer/test mod, does not register an Adapter, and does not change a production whitelist. JUnit, GameTest and installed-server evidence must be reported separately.

The world contains 64 furnaces/blast furnaces/smokers, 32 transporting Hoppers in eight closed loops, eight powered disabled Hoppers for the existing Native subset, brewing stands, chests, daylight detectors, real redstone clocks/lamps, and 32 persistent NoAI sheep. Eight furnace registration groups are separated by real Hopper fences. Every exact paced window checks full furnace NBT and burn/cook/item conservation, hopper-ring conservation, ordinary entity age progression and UUID identity, datapack clock/redstone transitions, and the combined actual Worker/serial body counters. Healthy core runs require positive original Native **and** Furnace Worker completion counts.

Optional `--mod JAR SHA256` is currently a **Create 6.0.10 load fixture**, not a generic modpack generator. It copies the original pinned Create jar without the Forgia Create adapter and builds two real waterwheel/gearbox/millstone assemblies. It does not grant those BEs Worker admission. Runtime transformation/proof or compatibility refusals remain refusals, recorded honestly; an unsupported mod combination is not promoted to a parallel success. Additional mod fixtures require their own state and callback audits.

Use an isolated installation built from the exact current production source. The runner refuses mismatched installer/universal hashes, missing authority classes, existing output worlds, and an EULA file that was not already accepted. It binds game/RCON ports to localhost and generates a private RCON password. Typical commands, with the actual SHA256 values substituted from the local build receipt:

```sh
python3 tools/canonical-be-lifecycle/installed_soak.py \
  --runs tmp/my-c41-core \
  --installation tmp/my-c41-installation \
  --classes tmp/my-c41-observer-classes \
  --eula /path/to/previously-accepted/eula.txt \
  --version 21.1.250.60-beta \
  --installer-sha256 INSTALLER_SHA256 --universal-sha256 UNIVERSAL_SHA256 \
  --windows 60 --window-ticks 1200 --checkpoint-every 10 \
  --unload-reload --restart-saved
```

The existing standalone observer compiles with JDK21:

```sh
javac --add-modules jdk.attach -d tmp/my-c41-observer-classes \
  tools/block-entity-resource-ab/JvmMetrics.java
```

Add `--no-persistent-workers --no-resources` for the actual fixed-pool/resource-off control. These switches belong only to a separate private test process. They do not change defaults, disable the BE slice, or substitute a serial control for the healthy parallel soak. The core run still requires actual Native and Furnace Workers in either resource mode.

`--unload-reload` reuses the existing saved chunk unload protocol: remove forced tickets, wait actual ticks, inspect the Vanilla diagnostic `chunks.csv` to establish that original fixture holders are absent/not in memory, then load them again and verify inventories and all32 unique entity UUIDs. This diagnostic interval is separate from checked steady windows. `--restart-saved` stops the actual server, opens the saved world with a new JVM, checks full inventory conservation/bounded progress and entity identity, advances another200 paced ticks, and repeats live authority checks. Bootstrap ticks are not presented as an exact per-BE window.

At frozen checkpoints the runner obtains actual thread dumps and a **live class histogram after full GC**. It requires zero live canonical kernel/Ticket/Permit/Guard/effect/phase batch/resource Lease/Scope objects and zero running Regions, and compares live RegionState objects with the actual regionizer count. Full-GC diagnostics are outside the active tick windows. The original `RegionBlockEntityTickScope` intentionally keeps one reusable idle object per Worker; this is tracked separately and bounded by the actual pool size. At initial/final/restart checkpoints `heap_scopes.py` reads the actual HPROF field layout and verifies `active=false` and `admission=region=previous=null` for every cached scope. Unknown HPROF records/layouts fail rather than produce a partial cleanup claim.

**Heap dumps can contain world data and RCON credentials.** Keep all worlds, properties, raw logs, JMX data, dumps, perf ZIPs and jar files in ignored local `tmp/`; never commit or upload them. Reports may publish hashes and selected nonsecret counters/idle-scope field results. The heap parser only emits the selected scope fields/object IDs, not world strings.

`result.json` records wall time, exact checked active ticks, actual process exit, separate checkpoints and failure details. Every failed pilot must remain in a separate output directory. A malformed observation is not a production tick failure; it still remains a failed pilot. Tick timing is diagnostic progress/stall evidence, not a throughput or MSPT gain claim. Heap/authority snapshots at finite checkpoints cannot prove absence of every JVM/native-memory leak, unobserved writer, future failure, or arbitrarily long run. Hoppers' per-invocation ordering/exactly-once fault traces come from the real-kernel JUnit tests; the installed fixture provides aggregate invocation counters and independent world/inventory state checks without a ticker-instrumentation mod.
