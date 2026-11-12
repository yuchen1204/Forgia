#!/usr/bin/env python3
"""Validate opt-in block-entity work-graph observation on a private installed Forgia server."""
import argparse
import json
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'tools/modpack-matrix'))
from rcon import Rcon, RconError

VERSION = '21.1.250.56-beta'


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True, help='New private directory; existing directories are refused')
    parser.add_argument('--libraries', type=Path, required=True, help='Installed libraries to copy as a private installer cache')
    parser.add_argument('--eula', type=Path, required=True, help='An already-accepted eula.txt, copied into the private instance')
    parser.add_argument('--java', type=Path, required=True, help='JDK 21 java executable')
    parser.add_argument('--furnace-waves', action='store_true', help='Enable and verify actual fine-grained furnace waves')
    parser.add_argument('--inert-layout', action='store_true', help='Space furnaces four blocks apart without active notification receivers; verify native completion commits')
    parser.add_argument('--furnace-count', type=int, choices=(2, 6), default=2, help='Use six inert furnaces to exercise work stealing within one chunk')
    parser.add_argument('--persistent-workers', action='store_true', help='Enable persistent workers and verify work-stealing batch dispatch')
    parser.add_argument('--version', default=VERSION, help='Installed core version; defaults to the latest validated fixture version')
    parser.add_argument('--installer', type=Path, help='Installer jar; defaults to the selected version in projects/neoforge/build/libs')
    return parser.parse_args()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    args = arguments()
    root = args.run_dir.resolve()
    installer = args.installer or REPO / f'projects/neoforge/build/libs/neoforge-{args.version}-installer.jar'
    if args.furnace_count == 6 and not args.inert_layout:
        raise ValueError('--furnace-count 6 requires --inert-layout')
    positions = [(x, 20) for x in ((20, 24) if args.inert_layout else (20, 22))]
    if args.furnace_count == 6:
        positions = [(x, z) for z in (17, 21) for x in (17, 21, 25)]
    furnace_count = len(positions)
    if not re.search(r'^\s*eula\s*=\s*true\s*$', args.eula.read_text(), re.M | re.I):
        raise ValueError('--eula must name an already-accepted eula.txt')
    root.mkdir(parents=True, exist_ok=False)
    shutil.copytree(args.libraries.resolve(), root / 'libraries', symlinks=False)
    shutil.copy2(args.eula, root / 'eula.txt')
    with (root / 'installer.log').open('w') as log:
        result = subprocess.run([str(args.java.resolve()), '-jar', str(installer.resolve()), '--installServer', str(root)],
                                cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    if result.returncode:
        raise RuntimeError(f'private installation exited {result.returncode}; see installer.log')
    (root / 'config').mkdir(exist_ok=True)
    (root / 'config/forgia.toml').write_text(f'''enableRegionThreadingFurnaceWorkWaves = {str(args.furnace_waves).lower()}
enableRegionThreadingPersistentWorkers = {str(args.persistent_workers).lower()}
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = false
enableRegionThreadingBlockEntityTicks = true
enableRegionThreadingRandomTicks = false
enableRegionThreadingSpawning = false
enableRegionThreadingPoi = false
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 2
''')
    port, rcon_port = free_port(), free_port()
    while rcon_port == port:
        rcon_port = free_port()
    password = secrets.token_hex(24)
    properties = root / 'server.properties'
    properties.write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
level-name=world
level-seed=813584
generate-structures=false
level-type=minecraft:flat
generator-settings={{"layers":[{{"block":"minecraft:bedrock","height":1}},{{"block":"minecraft:dirt","height":2}},{{"block":"minecraft:grass_block","height":1}}],"biome":"minecraft:plains"}}
view-distance=2
simulation-distance=2
spawn-protection=0
max-tick-time=120000
enable-rcon=true
broadcast-rcon-to-ops=false
rcon.port={rcon_port}
rcon.password={password}
''')
    properties.chmod(0o600)
    vm_args = root / f'libraries/net/neoforged/neoforge/{args.version}/unix_args.txt'
    log = (root / 'server.log').open('w')
    proc = subprocess.Popen([str(args.java.resolve()), '-Xms512M', '-Xmx2G', '-XX:+UseZGC', '-XX:+ZGenerational',
                             '@' + str(vm_args), 'nogui'], cwd=root, stdout=log, stderr=subprocess.STDOUT)
    client = None
    commands = []
    summary = {'outcome': 'STARTING', 'core': args.version, 'scope': 'same-chunk vanilla furnaces; shadow observation and optional real furnace waves',
               'furnaceWavesEnabled': args.furnace_waves, 'inertLayout': args.inert_layout, 'persistentWorkersEnabled': args.persistent_workers,
               'furnaceCount': furnace_count}
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(f'server exited {proc.returncode}; see server.log')
            try:
                client = Rcon('127.0.0.1', rcon_port, password, timeout=45)
                break
            except OSError:
                time.sleep(1)
        if client is None:
            raise TimeoutError('RCON startup deadline exceeded')

        def command(text):
            response = client.command(text)
            commands.append({'command': text, 'response': response})
            if any(error in response for error in ['Unknown or incomplete command', 'Incorrect argument', 'That position is not loaded']):
                raise AssertionError(response)
            return response

        def game_time():
            return int(re.search(r'(\d+)', command('time query gametime')).group(1))

        def wait_ticks(target):
            deadline = time.monotonic() + 90
            while game_time() < target:
                if time.monotonic() > deadline:
                    raise TimeoutError('normal tick progress deadline exceeded')
                time.sleep(0.5)

        initial = command('neoforge regionthreading workgraph')
        if 'remainingPhases=0, completedPhases=0' not in initial:
            raise AssertionError('observation did not start disabled')
        command('gamerule doMobSpawning false')
        command('gamerule doWeatherCycle false')
        command('gamerule randomTickSpeed 0')
        command('gamerule spawnChunkRadius 0')
        command('forceload add 16 16 31 31')
        time.sleep(4)
        command('tick freeze')
        command('fill 16 99 16 26 99 22 minecraft:stone')
        for x, z in positions:
            command(f'setblock {x} 100 {z} minecraft:furnace')
            command(f'item replace block {x} 100 {z} container.0 with minecraft:iron_ore 2')
            command(f'item replace block {x} 100 {z} container.1 with minecraft:coal 1')
        if not args.inert_layout:
            command('setblock 20 100 21 minecraft:comparator[facing=north]')
        command('neoforge regionthreading workgraph start 20')
        start = game_time()
        command('tick unfreeze')
        wait_ticks(start + 45)
        sample = command('neoforge regionthreading workgraph')
        (root / 'workgraph.txt').write_text(sample)
        if 'remainingPhases=0, completedPhases=20' not in sample or 'shadow, partial coverage' not in sample:
            raise AssertionError('observation budget or coverage label missing: ' + sample)
        shadow = sample.split('Block entity work graph', 1)[-1]
        for field, expected in [('planned', furnace_count), ('invoked', furnace_count), ('native', furnace_count), ('serial', 0), ('failed', 0),
                                ('repeated', 0), ('omitted', 0), ('overflowed', 0), ('waves', 1), ('maxWidth', furnace_count)]:
            found = re.search(r'\b' + field + r'=(\d+)', shadow)
            if found is None or int(found.group(1)) != expected:
                raise AssertionError(f'unexpected {field}, expected {expected}: ' + sample)
        wait_ticks(start + 430)
        command('tick freeze')
        for x, z in positions:
            state = command(f'data get block {x} 100 {z}')
            (root / f'furnace-{x}-{z}.txt').write_text(state)
            if 'minecraft:iron_ore' in state or 'minecraft:coal' in state or 'minecraft:iron_ingot' not in state:
                raise AssertionError('real smelting did not conserve/process the two inputs: ' + state)
            output = re.search(r'\{[^{}]*Slot:\s*2b[^{}]*\}', state)
            if output is None or not re.search(r'count:\s*2\b', output.group(0)):
                raise AssertionError('expected exactly two ingots in output slot: ' + state)
        command('neoforge regionthreading workgraph stop')
        command('neoforge regionthreading workgraph start 1')
        command('tick unfreeze')
        wait_ticks(game_time() + 5)
        command('tick freeze')
        final_graph = command('neoforge regionthreading workgraph')
        (root / 'workgraph-final.txt').write_text(final_graph)
        if 'remainingPhases=0, completedPhases=1' not in final_graph:
            raise AssertionError('start did not reset the sampling generation')
        if args.furnace_waves:
            body_count = re.search(r'\bnativeCompleted=(\d+)', final_graph)
            overlap = re.search(r'\bmaxSimultaneousBodies=(\d+)', final_graph)
            if body_count is None or int(body_count.group(1)) == 0 or overlap is None or int(overlap.group(1)) < 2:
                raise AssertionError('actual original furnace body completion/overlap was not demonstrated: ' + final_graph)
            summary.update(nativeFurnaceBodies=int(body_count.group(1)), simultaneousOriginalBodies=int(overlap.group(1)))
            if args.inert_layout:
                changes = re.search(r'\bchanges=(\d+)', final_graph)
                if changes is None or int(changes.group(1)) != 2 * furnace_count:
                    raise AssertionError(f'expected {2 * furnace_count} native processing completion notifications: ' + final_graph)
                summary['nativeCompletionChanges'] = int(changes.group(1))
        raw = command('neoforge regionthreading status raw')
        (root / 'diagnostics.raw.txt').write_text(raw)
        if args.persistent_workers:
            if 'persistentWorkers=true' not in raw:
                raise AssertionError('persistent worker pool was not installed')
            for field in ['dispatchBatches', 'batchedTasks', 'batchRunners', 'batchSteals']:
                found = re.search(r'\b' + field + r'=(\d+)', raw)
                if found is None or (args.furnace_waves and field != 'batchSteals' and int(found.group(1)) == 0):
                    raise AssertionError('missing/inactive work-stealing dispatcher counter ' + field)
                summary[field] = int(found.group(1))
        for field in ['ownershipViolationCount', 'droppedDeferredEntityAdditions', 'droppedDeferredEntityRemovals']:
            found = re.search(r'\b' + field + r'=(\d+)', raw)
            if found is None or int(found.group(1)):
                raise AssertionError('missing/nonzero safety counter ' + field)
        failure = re.search(r'tickFailures=(.*?), deferredUpdateBudgetChunkTicks=', raw, re.S)
        if failure is None or any(int(value) for value in re.findall(r'\b\w+=(\d+)', failure.group(1))):
            raise AssertionError('missing/nonzero tick failure counters')
        summary.update(outcome='PASS', observedPhases=20, nativeInvocationsInLastSample=furnace_count, observedWidth=furnace_count,
                       observedDepth=1, normalTicks=game_time()-start, outputIngots=2 * furnace_count)
    except BaseException as failure:
        summary.update(outcome='FAIL', error=str(failure))
        raise
    finally:
        (root / 'commands.json').write_text(json.dumps(commands, ensure_ascii=False, indent=2))
        if client is not None:
            try:
                client.command('stop')
            except (OSError, RconError):
                pass
            client.close()
        try:
            code = proc.wait(timeout=45)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                code = proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                code = proc.wait(timeout=10)
        log.close()
        summary['serverExit'] = code
        if code and summary['outcome'] == 'PASS':
            summary['outcome'] = 'FAIL'
        (root / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
        print(json.dumps(summary, ensure_ascii=False), flush=True)
        if summary['outcome'] != 'PASS' and sys.exc_info()[0] is None:
            raise RuntimeError('Installed server smoke failed; see summary.json')


if __name__ == '__main__':
    main()
