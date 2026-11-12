#!/usr/bin/env python3
"""Isolated real-server smoke for the built Create adapter. No performance claims or source-world writes."""
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

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'tools/modpack-matrix'))
from rcon import Rcon, RconError

VERSION = '21.1.250.52-beta'


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True, help='New private instance directory; must not already exist')
    parser.add_argument('--libraries', type=Path, required=True, help='Existing installed server libraries copied privately as installer cache')
    parser.add_argument('--create-jar', type=Path, required=True, help='Original Create 1.21.1 6.0.10 jar')
    parser.add_argument('--eula', type=Path, required=True, help='Existing eula.txt with eula=true; copied into the private instance')
    parser.add_argument('--java', type=Path, default=Path('/home/yc/.local/opt/jdk-21.0.13+9/bin/java'))
    parser.add_argument('--installer', type=Path, default=REPO / f'projects/neoforge/build/libs/neoforge-{VERSION}-installer.jar')
    parser.add_argument('--adapter', type=Path, default=REPO / 'adapters/create/build/libs/forgia-create-adapter-0.1.0.jar')
    return parser.parse_args()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    args = arguments()
    root = args.run_dir.resolve()
    if not re.search(r'^\s*eula\s*=\s*true\s*$', args.eula.read_text(), re.M | re.I):
        raise ValueError('--eula must name an already accepted eula.txt')
    for path in [args.java, args.installer, args.adapter, args.create_jar]:
        if not path.is_file():
            raise FileNotFoundError(path)
    root.mkdir(parents=True, exist_ok=False)
    shutil.copytree(args.libraries.resolve(), root / 'libraries', symlinks=False)
    shutil.copy2(args.eula, root / 'eula.txt')
    with (root / 'installer.log').open('w') as log:
        result = subprocess.run([str(args.java.resolve()), '-jar', str(args.installer.resolve()), '--installServer', str(root)],
                                cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    if result.returncode:
        raise RuntimeError(f'private installation exited {result.returncode}; see installer.log')
    (root / 'mods').mkdir()
    shutil.copy2(args.create_jar, root / 'mods' / args.create_jar.name)
    shutil.copy2(args.adapter, root / 'mods' / args.adapter.name)
    (root / 'config').mkdir(exist_ok=True)
    (root / 'config/forgia.toml').write_text('''enableRegionThreadingBridge = true
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
    props = root / 'server.properties'
    props.write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
level-name=world
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
    props.chmod(0o600)
    vm_args = root / f'libraries/net/neoforged/neoforge/{VERSION}/unix_args.txt'
    log = (root / 'server.log').open('w')
    proc = subprocess.Popen([str(args.java.resolve()), '-Xms512M', '-Xmx2G', '-XX:+UseZGC', '-XX:+ZGenerational',
                             '@' + str(vm_args), 'nogui'], cwd=root, stdout=log, stderr=subprocess.STDOUT)
    client = None
    commands = []
    summary = {'outcome': 'STARTING', 'core': VERSION, 'create': '6.0.10', 'scope': 'two isolated real waterwheel/gearbox/millstone assemblies; no players/addons'}
    try:
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(f'server exited {proc.returncode}; see server.log')
            try:
                client = Rcon('127.0.0.1', rcon_port, password, timeout=30)
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
        command('gamerule doMobSpawning false')
        command('gamerule doWeatherCycle false')
        command('gamerule randomTickSpeed 0')
        command('gamerule spawnChunkRadius 0')
        command('forceload add 0 0 95 47')
        # Wait for generation/tickets, without admitting a successful setup from an unloaded command.
        time.sleep(5)
        command('tick freeze')
        for x in (20, 68):
            command(f'fill {x-3} 98 17 {x+5} 98 25 minecraft:stone')
            command(f'setblock {x} 100 20 create:water_wheel[facing=south]')
            command(f'setblock {x} 100 21 create:gearbox[axis=x]')
            command(f'setblock {x} 101 21 create:millstone')
            command(f'setblock {x+1} 102 20 minecraft:water')
            command(f'data merge block {x} 101 21 {{InputInventory:{{Size:1,Items:[{{Slot:0,id:"minecraft:wheat",count:16}}]}}}}')
        before = command('neoforge regionthreading compat')
        (root / 'compat-before.txt').write_text(before)
        start = int(re.search(r'(\d+)', command('time query gametime')).group(1))
        command('tick unfreeze')
        deadline = time.monotonic() + 120
        current = start
        while time.monotonic() < deadline:
            current = int(re.search(r'(\d+)', command('time query gametime')).group(1))
            if current >= start + 800:
                break
            time.sleep(1)
        command('tick freeze')
        if current < start + 800:
            raise AssertionError(f'expected at least 800 natural ticks, got {current-start}')
        after = command('neoforge regionthreading compat')
        raw = command('neoforge regionthreading status raw')
        (root / 'compat-after.txt').write_text(after)
        (root / 'diagnostics.raw.txt').write_text(raw)
        completed = re.search(r'nativeCompleted=(\d+).*?checkMs=', after)
        if not completed or int(completed.group(1)) == 0:
            raise AssertionError('adapter has no real native completed bodies')
        for field in ['ownershipViolationCount', 'droppedDeferredEntityAdditions', 'droppedDeferredEntityRemovals']:
            value = re.search(r'\b' + field + r'=(\d+)', raw)
            if value is None or int(value.group(1)) != 0:
                raise AssertionError('missing/unsafe ' + field)
        failure = re.search(r'tickFailures=(.*?), deferredUpdateBudgetChunkTicks=', raw, re.S)
        if failure is None or any(int(n) for n in re.findall(r'\b\w+=(\d+)', failure.group(1))):
            raise AssertionError('missing/nonzero tick failures')
        for x in (20, 68):
            text = command(f'data get block {x} 101 21')
            (root / f'millstone-{x}.txt').write_text(text)
            speed = re.search(r'Speed:\s*([-\d.]+)f', text)
            if speed is None or float(speed.group(1)) == 0 or 'create:wheat_flour' not in text:
                raise AssertionError('millstone has not processed from its real waterwheel: ' + text)
        summary.update(outcome='PASS', naturalTicks=current-start, nativeCompleted=int(completed.group(1)))
    except BaseException as failure:
        summary.update(outcome='FAIL', error=str(failure))
        raise
    finally:
        (root / 'commands.json').write_text(json.dumps(commands, ensure_ascii=False, indent=2))
        if client is not None:
            try: client.command('stop')
            except (OSError, RconError): pass
            client.close()
        try: code = proc.wait(timeout=45)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try: code = proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill(); code = proc.wait(timeout=10)
        log.close()
        summary['serverExit'] = code
        if code != 0 and summary['outcome'] == 'PASS': summary['outcome'] = 'FAIL'
        (root / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
        print(json.dumps(summary, ensure_ascii=False), flush=True)
        if summary['outcome'] != 'PASS' and sys.exc_info()[0] is None:
            raise RuntimeError('Smoke validation failed; see summary.json')


if __name__ == '__main__':
    main()
