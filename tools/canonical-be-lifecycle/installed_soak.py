#!/usr/bin/env python3
"""C4.1 actual NeoForge 1.21.1 installed-server lifecycle/soak; no observer mod or new admission."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools/block-entity-resource-ab'))
import server_ab as base
import mixed_workload as mixed
from server_ab import dump, graph, metrics_summary
from heap_scopes import read_scopes

PREFIX = 'net.neoforged.neoforge.server.threading.region.'
EPHEMERAL = ['BlockEntityExecutionEngine', 'BlockEntityExecutionEngine$InvocationTicket',
             'BlockEntityExecutionEngine$Permit', 'BlockEntityExecutionEngine$RegionGuard', 'BlockEntityExecutionEngine$OwnedEffect',
             'RegionBlockEntityTickBatch', 'FurnaceWorkBatch', 'CanonicalBlockEntityCoordinator',
             'RegionBlockEntityTickPlan', 'FurnaceResourcePreparation', 'FurnaceWorkScope',
             'BlockEntityResourceOwnership$Lease', 'BlockEntityResourceOwnership$Scope']


def block_contents(response):
    marker = 'has the following block data: '
    if marker not in response:
        raise ValueError('Missing complete block-data response: '+response)
    return mixed.Snbt.parse(response.split(marker, 1)[1])


def raw_number(raw, name):
    match = re.search(r'\b' + re.escape(name) + r'=(\d+)', raw)
    if not match:
        raise ValueError('Missing actual diagnostic counter ' + name)
    return int(match[1])


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(4*1024*1024), b''):
            digest.update(block)
    return digest.hexdigest()


def histogram(text):
    result = {}
    for line in text.splitlines():
        match = re.match(r'\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)', line)
        if match:
            result[match[3]] = {'instances': int(match[1]), 'bytes': int(match[2])}
    if not result:
        raise ValueError('Missing live class histogram')
    return result


class LifecycleInstalled(mixed.MixedInstalled):
    def __init__(self, args):
        super().__init__(args, 'regions', 4, args.resources, 0)
        self.root = args.runs / 'server'
        self.checkpoints = []
        self.cooling = [(24 + (i % 4) * 48, 148, 24 + (i // 4) * 48) for i in range(8)]
        self.mod_positions = []
        self.total_started = time.monotonic()

    def prepare(self):
        super().prepare()
        # Eight additional real powered/disabled original Hoppers form a stable Native prefix.
        # This private-world fixture changes no production whitelist or API.
        lines = []
        for x, y, z in self.cooling:
            lines += [f'setblock {x+1} {y} {z} minecraft:redstone_block',
                      f'setblock {x} {y} {z} minecraft:hopper[enabled=false]']
        setup = self.functions / 'native_prefix.mcfunction'
        setup.write_text('\n'.join(lines) + '\n')
        self.command('reload')
        self.advance(20)
        self.command('function forgia_mixed:native_prefix')
        if self.args.mod_jars:
            for x in (20, 68):
                for command in [f'fill {x-3} 98 17 {x+5} 98 25 minecraft:stone',
                                f'setblock {x} 100 20 create:water_wheel[facing=south]',
                                f'setblock {x} 100 21 create:gearbox[axis=x]',
                                f'setblock {x} 101 21 create:millstone',
                                f'setblock {x+1} 102 20 minecraft:water',
                                f'data merge block {x} 101 21 {{InputInventory:{{Size:1,Items:[{{Slot:0,id:"minecraft:wheat",count:16}}]}}}}']:
                    self.command(command)
                self.mod_positions += [(x, 100, 20), (x, 100, 21), (x, 101, 21)]
        self.advance(self.args.warmup_ticks)
        self.command('function forgia_mixed:refill')
        self.checkpoint('initial')

    def cooling_state(self):
        result = [block_contents(self.command(f'data get block {mixed.xyz(p)}')) for p in self.cooling]
        if any(be.get('Items') or be.get('TransferCooldown', 0) != 0 for be in result):
            raise AssertionError('Disabled Native Hopper inventory/cooldown changed')
        return result

    def mod_state(self):
        return [block_contents(self.command(f'data get block {mixed.xyz(p)}')) for p in self.mod_positions]

    def safety(self, raw, delta):
        for name in ['furnace.failed', 'furnace.skipped', 'furnace.rejectedTasks']:
            if delta[name]:
                raise AssertionError((name, delta[name]))
        # Fresh/resource refusals can legitimately occur under mixed predicates; never count them as
        # successful Worker work. Every actual body and all inventory state are checked independently.
        for name in ['ownershipViolationCount', 'droppedDeferredEntityAdditions', 'droppedDeferredEntityRemovals']:
            if raw_number(raw, name):
                raise AssertionError(name)
        match = re.search(r'tickFailures=(.*?), deferredUpdateBudgetChunkTicks=', raw, re.S)
        if not match or any(int(v) for v in re.findall(r'\b\w+=(\d+)', match[1])):
            raise AssertionError('Missing/nonzero actual tick failures')
        if f'persistentWorkers={str(self.args.persistent_workers).lower()}' not in raw:
            raise AssertionError('Actual pool mode mismatch')
        if self.enabled:
            if delta['resource.issuedLeases'] != delta['furnace.nativeCompleted']:
                raise AssertionError('Fine Lease/body mismatch')
        elif any(v for k, v in delta.items() if k.startswith('resource.')):
            raise AssertionError('Resource operations while OFF')

    def window(self, index, ticks, output_per_machine=None):
        if getattr(self, '_unload_active', False):
            index = 'unload-' + str(index)
        before = self.state()
        native_before = self.cooling_state()
        mods_before = self.mod_state()
        bg = graph(self.command('neoforge regionthreading workgraph'))
        raw_before = self.command('neoforge regionthreading status raw')
        start = self.gametime()
        began = time.monotonic()
        self.advance(ticks)
        a, b = self.active_bounds
        after_graph = graph(self.command('neoforge regionthreading workgraph'))
        raw_after = self.command('neoforge regionthreading status raw')
        after = self.state()
        native_after = self.cooling_state()
        mods_after = self.mod_state()
        delta = {k: v-bg[k] for k, v in after_graph.items() if k != 'furnace.maxSimultaneousBodies'}
        self.safety(raw_after, delta)
        output = mixed.validate_window(before, after, ticks, self.spec, output_per_machine)
        worker = raw_number(raw_after, 'blockEntityTicksOnWorkers') - raw_number(raw_before, 'blockEntityTicksOnWorkers')
        serial = raw_number(raw_after, 'fallbackBlockEntityTicks') - raw_number(raw_before, 'fallbackBlockEntityTicks')
        expected_bodies = (120 + len(self.mod_positions)) * ticks
        if worker + serial != expected_bodies:
            raise AssertionError(f'All actual BE bodies: Worker{worker}+serial{serial}!={expected_bodies}')
        native = worker - delta['furnace.nativeCompleted']
        if not self.args.mod_jars and (native <= 0 or delta['furnace.nativeCompleted'] <= 0):
            raise AssertionError('Healthy core must actually run both proven Native and Furnace bodies on Workers')
        samples = [json.loads(line) for line in (self.root/'metrics.jsonl').read_text().splitlines()]
        # Reject gaps inside this active window; prior frozen full-GC diagnostics are separate evidence.
        samples = [s for s in samples if s['beforeNs'] >= a and s['afterNs'] <= b]
        measured = metrics_summary(samples, a+100_000_000, b-100_000_000)
        selected = [s for s in samples if 'threads' in s and a+100_000_000 <= s['beforeNs'] and s['afterNs'] <= b-100_000_000]
        if not selected or any(sum(t['name'].startswith('NeoForge Region Worker #') for t in s['threads']) != self.workers for s in selected):
            raise AssertionError('Actual Worker count changed')
        result = {'window': index, 'ticks': ticks, 'gameTimeStart': start, 'elapsedControlSeconds': time.monotonic()-began,
                  'workerBodies': worker, 'serialBodies': serial, 'nativeWorkerBodies': native,
                  'furnaceWorkerBodies': delta['furnace.nativeCompleted'], 'expectedAllBodies': expected_bodies,
                  'outputs': output, 'metrics': measured, 'deltas': delta,
                  'allFullStateConservationAndExactlyOnceTickCountersVerified': True,
                  'note': 'Exactly-once counters and state cover this controlled installed fixture; no generic Mod callback certification.'}
        dump(self.root/f'window-{index}.json', {'result': result, 'beforeState': before, 'afterState': after,
             'disabledBefore': native_before, 'disabledAfter': native_after, 'modsBefore': mods_before, 'modsAfter': mods_after,
             'beforeRaw': raw_before, 'afterRaw': raw_after, 'beforeGraph': bg, 'afterGraph': after_graph})
        self.results.append(result)
        dump(self.root/'result.json', {'outcome': 'RUNNING', 'results': self.results})
        print(json.dumps({'event': 'C41_INSTALLED_WINDOW', 'index': index, 'ticks': ticks, 'workerBodies': worker,
                          'serialBodies': serial, 'native': native, 'furnace': delta['furnace.nativeCompleted'],
                          'mspt': measured['mspt'], 'activeTicksSoFar': sum(v['ticks'] for v in self.results)}), flush=True)

    def checkpoint(self, name):
        folder = self.root/'checkpoints'
        folder.mkdir(exist_ok=True)
        raw = self.command('neoforge regionthreading status raw')
        if raw_number(raw, 'runningRegions'):
            raise AssertionError('Region lease still running at frozen quiescent checkpoint')
        hist_path = folder/(name+'-live-histogram.txt')
        thread_path = folder/(name+'-threads.txt')
        command = [str(self.args.java.with_name('jcmd')), str(self.proc.pid)]
        # This diagnostic forces a full GC only while the world is frozen, outside active windows.
        with hist_path.open('w') as out:
            subprocess.run([*command, 'GC.class_histogram'], stdout=out, stderr=subprocess.STDOUT, check=True, timeout=45)
        with thread_path.open('w') as out:
            subprocess.run([*command, 'Thread.print', '-l'], stdout=out, stderr=subprocess.STDOUT, check=True, timeout=30)
        classes = histogram(hist_path.read_text())
        counts = {k: classes.get(PREFIX+k, {'instances': 0, 'bytes': 0}) for k in EPHEMERAL}
        if any(v['instances'] for v in counts.values()):
            raise AssertionError('Live canonical invocation authority or batch retained after phase: '+str(counts))
        if classes.get(PREFIX+'RegionState', {'instances': 0})['instances'] != raw_number(raw, 'regions'):
            raise AssertionError('Live RegionState instances exceed or differ from the actual regionizer set')
        idle_scopes = classes.get(PREFIX+'RegionBlockEntityTickScope', {'instances': 0, 'bytes': 0})
        if idle_scopes['instances'] > self.workers:
            raise AssertionError('Reusable Native scope cache exceeded one object per actual Worker')
        scope_audit = None
        if name in ('initial', 'final', 'after-restart'):
            heap_path = folder/(name+'.hprof')
            with (folder/(name+'-heapdump-command.txt')).open('w') as out:
                subprocess.run([*command, 'GC.heap_dump', str(heap_path)], stdout=out, stderr=subprocess.STDOUT, check=True, timeout=60)
            scopes = read_scopes(heap_path)
            if len(scopes) != idle_scopes['instances']:
                raise AssertionError('Heap scope field audit count differs from live histogram')
            scope_audit = {'instances': scopes, 'allInactiveAndAllAdmissionRegionPreviousReferencesNull': True,
                           'heapDumpBytes': heap_path.stat().st_size,
                           'heapDumpSha256': file_hash(heap_path),
                           'heapDumpPrivateMayContainCredentialsNeverPublish': True}
        threads = thread_path.read_text()
        if re.search(r'Found (?:one|\d+) Java-level deadlock', threads):
            raise AssertionError('JVM reported Java-level deadlock')
        worker_names = re.findall(r'^"(NeoForge Region Worker #[^\"]+)"', threads, re.M)
        if len(worker_names) != self.workers:
            raise AssertionError('Quiescent thread dump Worker count mismatch')
        record = {'name': name, 'gameTime': self.gametime(), 'liveAfterFullGc': counts,
                  'regionObjects': classes.get(PREFIX+'RegionState', {'instances': 0, 'bytes': 0}),
                  'boundedIdleNativeScopeCache': idle_scopes, 'idleNativeScopeFieldAudit': scope_audit,
                  'diagnosticRegionCount': raw_number(raw, 'regions'), 'runningRegions': 0,
                  'namedThreadDumpEntries': len(re.findall(r'^"', threads, re.M)), 'workerThreads': worker_names,
                  'liveObjectShallowBytesAfterFullGc': sum(v['bytes'] for v in classes.values()),
                  'deadlocksReported': 0, 'histogramSha256': hashlib.sha256(hist_path.read_bytes()).hexdigest(),
                  'threadDumpSha256': hashlib.sha256(thread_path.read_bytes()).hexdigest(),
                  'raw': raw, 'diagnosticFullGcExcludedFromActiveWindows': True}
        self.checkpoints.append(record)
        dump(folder/(name+'.json'), record)
        print('C41_LIVE_CLEANUP_CHECKPOINT '+name, flush=True)

    def restart_saved(self):
        self.command('scoreboard players set active fm 0')
        before = self.state()
        uuids = [mixed.contents(self.command(f'data get entity @e[tag={row["tag"]},limit=1] UUID')) for row in self.spec['sheep']]
        start = self.gametime()
        self.command('save-on')
        self.command('save-all flush')
        self.checkpoint('before-stop')
        self.close()
        primary_exit = self.proc.returncode
        self.observer = self.client = None
        self.log = (self.root/'server-restart.log').open('w')
        vm_args = self.root/f'libraries/net/neoforged/neoforge/{self.args.version}/unix_args.txt'
        self.proc = subprocess.Popen([str(self.args.java), *base.VM,
            f'-Xlog:gc*,safepoint=info:file={self.root}/gc-restart.log:uptimemillis,level,tags',
            '@'+str(vm_args), 'nogui'], cwd=self.root, stdout=self.log, stderr=subprocess.STDOUT)
        deadline = time.monotonic()+180
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError('Saved-world restart failed')
            if 'Done (' in (self.root/'server-restart.log').read_text(errors='replace'):
                try:
                    self.client = base.Rcon('127.0.0.1', self.port, self.password, timeout=90)
                    break
                except OSError:
                    pass
            time.sleep(.5)
        else:
            raise TimeoutError('Saved-world restart readiness')
        self.command('tick freeze')
        self.advance(200)
        after = self.state()
        elapsed = self.gametime()-start
        after_uuids = [mixed.contents(self.command(f'data get entity @e[tag={row["tag"]},limit=1] UUID')) for row in self.spec['sheep']]
        if uuids != after_uuids or len({tuple(u) for u in after_uuids}) != 32:
            raise AssertionError('Saved-world restart duplicated/lost original entities')
        for a, b in zip(before['machines'], after['machines']):
            spent = a['BurnTime']-b['BurnTime']
            made = mixed.amount(b, 2)-mixed.amount(a, 2)
            if not 0 <= spent <= elapsed or not 0 <= made <= elapsed//200+1:
                raise AssertionError('Saved-world restart lost or repeated furnace work')
        self.cooling_state()
        current_graph = graph(self.command('neoforge regionthreading workgraph'))
        self.safety(self.command('neoforge regionthreading status raw'), current_graph)
        self.checkpoint('after-restart')
        audit = {'primaryServerExit': primary_exit, 'elapsedGameTicksIncludingBootstrap': elapsed,
                 'additionalPacedTicksAfterRestart': 200, 'all32OriginalEntityUuidsPreservedUnique': True,
                 'fullStateConservationAndBoundedFurnaceProgress': True, 'beforeState': before, 'afterState': after,
                 'noBootstrapExactlyOnceOrTimingClaim': True}
        dump(self.root/'restart-audit.json', audit)

    def unload_reload(self):
        self._unload_active = True
        try:
            super().unload_reload()
            self.checkpoint('after-unload-reload')
        finally:
            self._unload_active = False


def main():
    signal.signal(signal.SIGTERM, base.request_stop)
    signal.signal(signal.SIGINT, base.request_stop)
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ['runs', 'installation', 'eula', 'classes']:
        parser.add_argument('--'+key, type=Path, required=True)
    parser.add_argument('--java', type=Path, default=base.JAVA)
    parser.add_argument('--version', required=True)
    parser.add_argument('--installer-sha256', required=True)
    parser.add_argument('--universal-sha256', required=True)
    parser.add_argument('--windows', type=int, default=60)
    parser.add_argument('--window-ticks', type=int, default=1200)
    parser.add_argument('--warmup-ticks', type=int, default=200)
    parser.add_argument('--checkpoint-every', type=int, default=10)
    parser.add_argument('--resources', action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument('--persistent-workers', action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument('--unload-reload', action='store_true')
    parser.add_argument('--restart-saved', action='store_true')
    parser.add_argument('--mod', action='append', nargs=2, default=[], metavar=('JAR', 'SHA256'))
    args = parser.parse_args()
    for key in ['runs', 'installation', 'eula', 'classes', 'java']:
        setattr(args, key, getattr(args, key).resolve())
    if args.windows < 1 or not 200 <= args.window_ticks <= 1200 or args.window_ticks % 200 or args.warmup_ticks < 200 or args.warmup_ticks % 200 or args.checkpoint_every < 1:
        raise ValueError('Positive windows/checkpoints; controlled windows200..1200 multiple200 ticks')
    if not re.search(r'^\s*eula\s*=\s*true\s*$', args.eula.read_text(), re.M|re.I):
        raise ValueError('Use a previously accepted EULA file')
    args.mod_jars = []
    mods = []
    for name, expected in args.mod:
        path = Path(name).resolve()
        if not re.fullmatch(r'[a-f0-9]{64}', expected) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Actual Mod binary pin mismatch')
        args.mod_jars.append(path)
        mods.append({'file': path.name, 'sha256': expected})
    args.counts = [64]
    args.mode = 'c41-lifecycle-soak'
    args.jfr = None
    args.fence_groups = 8
    base.verify_artifacts(args)
    import zipfile
    with zipfile.ZipFile(args.installation/f'libraries/net/neoforged/neoforge/{args.version}/neoforge-{args.version}-universal.jar') as core:
        names = set(core.namelist())
        for kind in EPHEMERAL:
            if (PREFIX+kind).replace('.', '/')+'.class' not in names:
                raise ValueError('Requested leak monitor class absent from this exact binary: '+kind)
    args.runs.mkdir(parents=True, exist_ok=False)
    began = time.monotonic()
    manifest = {'startedAt': datetime.now(timezone.utc).isoformat(), 'minecraft': '1.21.1', 'version': args.version,
                'installerSha256': args.installer_sha256, 'universalSha256': args.universal_sha256,
                'sourceHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
                'parameters': {k: str(v) if isinstance(v, Path) else [str(p) for p in v] if k == 'mod_jars' else v for k, v in vars(args).items()},
                'mods': mods, 'jvm': base.VM,
                'toolHashes': {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__).resolve(), REPO/'tools/block-entity-resource-ab/server_ab.py', REPO/'tools/block-entity-resource-ab/mixed_workload.py', REPO/'tools/block-entity-resource-ab/JvmMetrics.java', Path(__file__).resolve().with_name('heap_scopes.py')]},
                'scope': 'Real installed DedicatedServer, original tickers, mixed Vanilla datapack; additional Create assemblies when explicitly supplied. No Create adapter or observer mod.'}
    dump(args.runs/'manifest.json', manifest)
    run = LifecycleInstalled(args)
    ok = False
    try:
        run.prepare()
        for index in range(args.windows):
            run.command('function forgia_mixed:refill')
            run.window(index, args.window_ticks)
            if (index+1) % args.checkpoint_every == 0:
                run.checkpoint(f'window-{index+1}')
        if args.unload_reload:
            run.unload_reload()
        if args.mod_jars:
            for row in run.mod_state():
                if row['id'] == 'create:millstone':
                    text = json.dumps(row)
                    if not row.get('Speed') or 'create:wheat_flour' not in text:
                        raise AssertionError('Real powered Create millstone did not process')
        run.checkpoint('final')
        if args.restart_saved:
            run.restart_saved()
        ok = True
    except BaseException as error:
        if run.root.exists():
            dump(run.root/'failure.json', {'error': repr(error), 'elapsedSeconds': time.monotonic()-began})
        raise
    finally:
        close_error = None
        try:
            run.close()
        except BaseException as error:
            close_error = error
        code = run.proc.returncode if run.proc else None
        receipt = {'outcome': 'PASS' if ok and close_error is None and code == 0 else 'FAIL', 'finishedAt': datetime.now(timezone.utc).isoformat(),
                   'elapsedWallSeconds': time.monotonic()-began, 'exactCheckedActiveTicks': sum(v['ticks'] for v in run.results),
                   'checkedPacedActiveSecondsAt20Tps': sum(v['ticks'] for v in run.results)/20,
                   'results': run.results, 'checkpoints': run.checkpoints, 'serverExit': code,
                   'closeError': repr(close_error) if close_error else None,
                   'noInstrumentationMod': True, 'fullGcDiagnosticsOutsideActiveWindows': True}
        dump(args.runs/'result.json', receipt)
        if close_error is not None:
            raise close_error
        if ok and receipt['serverExit'] != 0:
            raise RuntimeError('Installed server did not stop cleanly')
    print('C41_INSTALLED_SOAK_COMPLETE', flush=True)


if __name__ == '__main__':
    main()
