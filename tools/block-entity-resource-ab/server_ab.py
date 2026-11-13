#!/usr/bin/env python3
"""Steady, paced installed-server A/B with pinned binaries and private worlds; no instrumentation mod."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tools/modpack-matrix'))
from rcon import Rcon, RconError

VERSION = '21.1.250.58-beta'
SHA256 = '7d736190ccd2c97c949368a3a57af35a31fe71a5f48e7814517494b78d7da34f'
UNIVERSAL_SHA256 = '0c137cdaa9a88b160c8c146633eb1e39700d42ecc39ac4dd17cd45bd3d441e45'
JAVA = Path('/home/yc/.local/opt/jdk-21.0.13+9/bin/java')
VM = ['-Xms2G', '-Xmx2G', '-XX:+UseG1GC', '-Dfile.encoding=UTF-8']
STOP_REQUESTED = False


def request_stop(signum, frame):
    # Stop only after the current complete RCON exchange. A second concurrent RCON controller
    # can deadlock Vanilla shutdown against another command awaiting the server thread.
    global STOP_REQUESTED
    STOP_REQUESTED = True


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def positions(layout, count):
    result = []
    for i in range(count):
        if layout == 'chunk':
            result.append((18 + i % 4 * 4, 64 + i // 16 * 4, 18 + i // 4 % 4 * 4))
        elif layout == 'chunks':
            result.append((8 + i % 3 * 16, 64 + i // 9 * 4, 8 + i // 3 % 3 * 16))
        elif layout == 'regions':
            result.append((24 + i % 4 * 48, 64 + i // 8 * 4, 24 + i // 4 % 2 * 48))
        else:
            raise ValueError(layout)
    return result


def split_compounds(text):
    """Split a vanilla SNBT list, preserving quoted recipe IDs and nested Items/RecipesUsed."""
    if '<...>' in text:
        raise ValueError('Vanilla SNBT display truncated; request bounded sublists')
    items, depth, quote, escaped, start = [], 0, None, False, None
    for i, char in enumerate(text):
        if quote:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == quote:
                quote = None
        elif char in ['"', "'"]:
            quote = char
        elif char == '{':
            if depth == 0:
                start = i
            depth += 1
        elif char == '}':
            depth -= 1
            if depth < 0:
                raise ValueError('Unbalanced SNBT')
            if depth == 0:
                items.append(text[start:i+1])
    if depth or quote:
        raise ValueError('Incomplete SNBT')
    return items


def percentile(values, p):
    if not values:
        raise ValueError('No samples; never substitute zero')
    return sorted(values)[min(len(values) - 1, math.ceil(len(values) * p) - 1)]


def timing(values):
    return dict(samples=len(values), mean=sum(values)/len(values), p50=percentile(values, .5),
                p95=percentile(values, .95), p99=percentile(values, .99), maximum=max(values))


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def graph(text):
    result = {}
    for line in text.splitlines():
        if line.startswith('Executed furnace work waves:'):
            prefix = 'furnace.'
        elif line.startswith('Block entity resource ownership:'):
            prefix = 'resource.'
        else:
            continue
        for name, val in re.findall(r'\b([A-Za-z]+)=([0-9]+(?:\.[0-9]+)?)(?:ms)?', line):
            result[prefix + name] = float(val) if '.' in val else int(val)
    for key in ['furnace.nativeCompleted', 'furnace.serial', 'furnace.failed', 'furnace.skipped',
                'furnace.changes', 'furnace.rejectedTasks', 'resource.issuedLeases', 'resource.refusedWaves',
                'resource.revalidation', 'resource.fullPhase']:
        if key not in result:
            raise ValueError('Incomplete workgraph: ' + key)
    return result


def tick_samples(samples, start_ns, end_ns):
    """Reconstruct the native rolling ring. Equal-valued intermediate cells remain included.

    A slot unchanged between polls at the end cannot establish a tick boundary, so strict interval
    trimming is applied; exact world/body windows are reported separately. Gaps >=4s are refused.
    """
    result = []
    for previous, current in zip(samples, samples[1:]):
        if current['afterNs'] - previous['beforeNs'] >= 4_000_000_000:
            raise ValueError('JMX gap risks ring overwrite')
        if previous['beforeNs'] < start_ns or current['afterNs'] > end_ns:
            continue
        changed = [i for i, (a, b) in enumerate(zip(previous['ticksNs'], current['ticksNs'])) if a != b]
        if not changed:
            continue
        # At 20TPS with <4s gaps, the largest circular gap is outside the newly written sequence.
        gaps = [(changed[(i+1) % len(changed)] - item) % 100 for i, item in enumerate(changed)]
        last = max(range(len(changed)), key=lambda i: gaps[i])
        first = changed[(last+1) % len(changed)]
        final = changed[last]
        length = (final - first) % 100 + 1
        # A gap near the ring wrap is normal; >=80 writes in a 100ms poll is not a paced run.
        if length >= 80:
            raise ValueError('Unresolvable native tick ring advance')
        result.extend(current['ticksNs'][(first+j) % 100] / 1e6 for j in range(length))
    return result


def metrics_summary(samples, start_ns, end_ns):
    selected = [s for s in samples if s['beforeNs'] >= start_ns and s['afterNs'] <= end_ns]
    if len(selected) < 10:
        raise ValueError('Insufficient JMX samples')
    first, last = selected[0], selected[-1]
    seconds = (last['beforeNs'] - first['beforeNs']) / 1e9
    ticks = tick_samples(samples, start_ns, end_ns)
    cpu = (last['processCpuNs'] - first['processCpuNs']) / (seconds * 1e9) * 100
    threads = [s for s in selected if 'threads' in s]
    allocation = 0
    worker_cpu = 0
    for old in threads[0]['threads']:
        new = next((v for v in threads[-1]['threads'] if v['id'] == old['id']), None)
        if new:
            allocation += new['allocatedBytes'] - old['allocatedBytes']
            if old['name'].startswith('NeoForge Region Worker #'):
                worker_cpu += new['cpuNs'] - old['cpuNs']
    return dict(mspt=timing(ticks), cpuPercentOfOneCore=cpu, cpuPercentOfEightLogicalCPUs=cpu/8,
                cpuSeconds=(last['processCpuNs']-first['processCpuNs'])/1e9,
                cpuObservationSeconds=seconds, observedTps=len(ticks)/((last['afterNs']-first['beforeNs'])/1e9),
                gcCount=last['gcCount']-first['gcCount'],
                gcMillis=last['gcMillis']-first['gcMillis'],
                serverAndWorkerAllocatedBytes=allocation,
                allocationObservationSeconds=(threads[-1]['beforeNs']-threads[0]['beforeNs'])/1e9,
                workerCpuSeconds=worker_cpu/1e9,
                furnaceStacksSampled=sum(v['furnaceBodyOnStack'] for s in threads for v in s['threads']),
                heapBytes=timing([s['heapBytes'] for s in selected]),
                observerPollMs=timing([(s['afterNs']-s['beforeNs'])/1e6 for s in selected]))


class Installed:
    def __init__(self, args, layout, workers, enabled, round_):
        self.args, self.layout, self.workers, self.enabled = args, layout, workers, enabled
        self.root = args.runs / f'r{round_}-{layout}-w{workers}-{"on" if enabled else "off"}'
        self.round = round_
        self.proc = self.client = self.observer = None
        self.command_log = []
        self.results = []

    def command(self, text):
        response = self.client.command(text)
        self.command_log.append(dict(command=text, response=response))
        if any(s in response for s in ['Unknown or incomplete command', 'Incorrect argument', 'That position is not loaded', 'Unknown function']):
            raise ValueError(response)
        return response

    def gametime(self):
        return int(re.search(r'\d+', self.command('time query gametime'))[0])

    def prepare(self):
        self.root.mkdir(parents=True, exist_ok=False)
        (self.root/'libraries').symlink_to(self.args.installation/'libraries', target_is_directory=True)
        shutil.copy2(self.args.eula, self.root/'eula.txt')
        (self.root/'config').mkdir()
        (self.root/'config/forgia.toml').write_text(f'''enableRegionThreadingFurnaceWorkWaves = true
 enableRegionThreadingBlockEntityResources = {str(self.enabled).lower()}
 enableRegionThreadingPersistentWorkers = true
 enableRegionThreadingBridge = true
 enableRegionThreadingWorkers = true
 enableRegionThreadingEntityTicks = false
 enableRegionThreadingBlockEntityTicks = true
 enableRegionThreadingRandomTicks = false
 enableRegionThreadingSpawning = false
 enableRegionThreadingPoi = false
 regionThreadingMode = "FIXED_3X3"
 regionThreadingFixedRegionChunks = 3
 regionThreadingWorkerThreads = {self.workers}
''')
        self.port, game_port = free_port(), free_port()
        self.password = secrets.token_hex(24)
        (self.root/'server.properties').write_text(f'''server-ip=127.0.0.1
server-port={game_port}
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
enable-jmx-monitoring=true
function-permission-level=4
enable-rcon=true
broadcast-rcon-to-ops=false
rcon.port={self.port}
rcon.password={self.password}
''')
        (self.root/'server.properties').chmod(0o600)
        functions = self.root/'world/datapacks/forgia_ab/data/forgia_ab/function'
        functions.mkdir(parents=True)
        dump(functions.parents[2]/'pack.mcmeta', dict(pack=dict(pack_format=48, description='Private fixed vanilla furnace fixture')))
        maximum = positions(self.layout, max(self.args.counts))
        clear = [f'setblock {x} {y} {z} minecraft:air' for x,y,z in maximum]
        for count in self.args.counts:
            lines = list(clear)
            for x,y,z in positions(self.layout,count):
                lines.append(f'setblock {x} {y} {z} minecraft:furnace[lit=true]{{BurnTime:30000s,CookTime:100s,CookTimeTotal:200s,Items:[{{Slot:0b,id:"minecraft:iron_ore",count:64}},{{Slot:1b,id:"minecraft:coal",count:64}}]}}')
            (functions/f'setup{count}.mcfunction').write_text('\n'.join(lines)+'\n')
            collect = []
            points = positions(self.layout,count)
            for group in range(max(1,math.ceil(count/64))):
                collect.append(f'data modify storage forgia_ab:state part{group} set value []')
                collect += [f'data modify storage forgia_ab:state part{group} append from block {x} {y} {z}'
                            for x,y,z in points[group*64:(group+1)*64]]
            (functions/f'snapshot{count}.mcfunction').write_text('\n'.join(collect)+'\n')
        (functions/'freeze.mcfunction').write_text('tick freeze\n')
        self.log=(self.root/'server.log').open('w')
        vm_args = self.root/f'libraries/net/neoforged/neoforge/{self.args.version}/unix_args.txt'
        self.proc=subprocess.Popen([str(self.args.java),*VM,
            f'-Xlog:gc*,safepoint=info:file={self.root}/gc.log:uptimemillis,level,tags',
            '@'+str(vm_args),'nogui'],cwd=self.root,stdout=self.log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            if self.proc.poll() is not None: raise RuntimeError('Server boot failure')
            if 'Done (' in (self.root/'server.log').read_text(errors='replace'):
                try:
                    self.client=Rcon('127.0.0.1',self.port,self.password,timeout=90)
                    break
                except OSError:
                    # Done precedes DedicatedServer's RCON listener startup; a refused connection
                    # is startup readiness, never a completed fixture or a zero performance sample.
                    pass
            time.sleep(.5)
        else: raise TimeoutError('Server startup')
        self.command('tick freeze')
        for cmd in ['gamerule doMobSpawning false','gamerule doWeatherCycle false','gamerule doDaylightCycle false',
                    'gamerule randomTickSpeed 0','gamerule spawnChunkRadius 0','difficulty peaceful','save-off']:
            self.command(cmd)
        chunks={(x//16,z//16) for x,y,z in maximum}
        # Explicit 3x3 loaded neighbourhood for every active chunk, all within vanilla's256chunk limit.
        for cx,cz in sorted(chunks):
            self.command(f'forceload add {(cx-1)*16} {(cz-1)*16} {(cx+1)*16} {(cz+1)*16}')
        self.command('reload')
        self.advance(60)
        self.observer_log=(self.root/'observer.log').open('w')
        self.observer=subprocess.Popen([str(self.args.java),'--add-modules','jdk.attach','-cp',str(self.args.classes),
                    'JvmMetrics',str(self.proc.pid),str(self.root/'metrics.jsonl')],stdin=subprocess.PIPE,stdout=self.observer_log,
                    stderr=subprocess.STDOUT,text=True)
        deadline=time.monotonic()+20
        while 'READY' not in (self.root/'observer.log').read_text():
            if self.observer.poll() is not None: raise RuntimeError('External observer failed')
            if time.monotonic()>deadline: raise TimeoutError('Observer startup')
            time.sleep(.1)

    def advance(self, ticks):
        start=self.gametime()
        self.command(f'schedule function forgia_ab:freeze {ticks}t replace')
        self.command('tick unfreeze')
        active_start = time.monotonic_ns()
        previous_query = active_start
        deadline=time.monotonic()+max(60,ticks*.15)
        while True:
            time.sleep(.5)
            query_start = time.monotonic_ns()
            now=self.gametime()
            if STOP_REQUESTED or (self.args.runs/'STOP').exists():
                raise InterruptedError('Graceful experiment stop requested')
            if now>=start+ticks:
                # Last below-target query is a conservative active lower bound, excluding all freeze/control latency.
                self.active_bounds = (active_start, previous_query)
                break
            previous_query = query_start
            if time.monotonic()>deadline: raise TimeoutError('Paced tick progress')
        a=self.gametime();time.sleep(.12);b=self.gametime()
        if a!=b or a-start!=ticks: raise AssertionError(f'Scheduled exact freeze failed: {start}->{a}->{b}, expected{ticks}')
        return a-start

    def state(self, count):
        # Quiescent vanilla command function collects exact complete NBT in one RCON round-trip.
        # No per-tick observer mod, transformation, world callback or inventory mutation is installed.
        self.command(f'function forgia_ab:snapshot{count}')
        result = []
        for group in range(max(1,math.ceil(count/64))):
            response = self.command(f'data get storage forgia_ab:state part{group}')
            marker = 'has the following contents: '
            if marker not in response:
                raise ValueError('Missing furnace snapshot storage')
            result.extend(split_compounds(response.split(marker,1)[1]))
        if len(result) != count:
            raise ValueError(f'Incomplete furnace snapshot: {len(result)} != {count}')
        return result

    def measure(self):
        for count in self.args.counts:
            self.command(f'function forgia_ab:setup{count}')
            self.advance(self.args.warmup_ticks)
            for window in range(self.args.windows):
                if self.args.jfr and not hasattr(self, 'jfr_started'):
                    # Diagnostic runs only; recorder startup and another200ticks precede measurements.
                    with (self.root/'jfr-start.txt').open('w') as stream:
                        subprocess.run([str(self.args.java.with_name('jcmd')),str(self.proc.pid),'JFR.start',
                                        'name=forgia_ab','settings='+str(self.args.jfr),
                                        'filename='+str(self.root/'steady.jfr'),'dumponexit=true'],
                                       check=True,stdout=stream,stderr=subprocess.STDOUT)
                    self.jfr_started = True
                    self.advance(200)
                before_state=self.state(count)
                before_graph=graph(self.command('neoforge regionthreading workgraph'))
                before_raw=self.command('neoforge regionthreading status raw')
                begin=time.monotonic_ns()
                start_tick=self.gametime()
                ticks=self.advance(self.args.window_ticks)
                ended=time.monotonic_ns()
                after_graph=graph(self.command('neoforge regionthreading workgraph'))
                after_raw=self.command('neoforge regionthreading status raw')
                after_state=self.state(count)
                delta={k:after_graph[k]-v for k,v in before_graph.items() if k not in ['furnace.maxSimultaneousBodies']}
                if count > 1 and delta['furnace.nativeCompleted'] != count*ticks:
                    raise AssertionError('Native body count did not cover the complete exact window')
                for field in ['failed','skipped','rejectedTasks']:
                    if delta['furnace.'+field]: raise AssertionError(delta)
                if delta['resource.refusedWaves']: raise AssertionError(delta)
                if self.enabled and count>1 and delta['resource.issuedLeases']!=delta['furnace.nativeCompleted']: raise AssertionError(delta)
                for field in ['ownershipViolationCount','droppedDeferredEntityAdditions','droppedDeferredEntityRemovals']:
                    match=re.search(r'\b'+field+r'=(\d+)',after_raw)
                    if not match or int(match[1]): raise AssertionError(field)
                failure=re.search(r'tickFailures=(.*?), deferredUpdateBudgetChunkTicks=',after_raw,re.S)
                if not failure or any(int(v) for v in re.findall(r'\b\w+=(\d+)',failure[1])): raise AssertionError('Tick failure')
                def counts(states,kind):
                    return sum(sum(int(m[1]) for m in re.finditer(r'count:\s*(\d+),\s*Slot:\s*'+('2b' if kind=='output' else '0b'),s)) for s in states)
                before_output,after_output=counts(before_state,'output'),counts(after_state,'output')
                if after_output-before_output!=count*(ticks//200): raise AssertionError('Smelting output window mismatch')
                if counts(after_state,'input')+after_output!=count*64: raise AssertionError('Item conservation')
                time.sleep(.2)
                metrics=[json.loads(line) for line in (self.root/'metrics.jsonl').read_text().splitlines()]
                # Use only the proven-active inner interval. Frozen polling/snapshot time is excluded.
                active_start,active_end=self.active_bounds
                measured_start,measured_end=active_start+100_000_000,active_end-100_000_000
                observed=metrics_summary(metrics,measured_start,measured_end)
                result=dict(layout=self.layout,workers=self.workers,resources=self.enabled,round=self.round,count=count,
                    window=window,warmupTicks=self.args.warmup_ticks,gameTimeStart=start_tick,ticks=ticks,
                    elapsedSeconds=(ended-begin)/1e9,controlWindowTps=ticks/((ended-begin)/1e9),
                    tps=observed['observedTps'],deltas=delta,
                    maxSimultaneousOriginalBodiesSinceBoot=after_graph.get('furnace.maxSimultaneousBodies'),
                    outputItems=after_output-before_output,metrics=observed,initialCookTime=100,
                    stateBeforeSha256=hashlib.sha256('\n'.join(before_state).encode()).hexdigest(),
                    stateAfterSha256=hashlib.sha256('\n'.join(after_state).encode()).hexdigest(),
                    activeInnerStartNs=measured_start,activeInnerEndNs=measured_end,
                    activeInnerStartEpochNs=measured_start+time.time_ns()-time.monotonic_ns(),
                    activeInnerEndEpochNs=measured_end+time.time_ns()-time.monotonic_ns())
                # Detailed state and counter receipts are local evidence, never publish server logs/credentials.
                dump(self.root/f'c{count}-window{window}.json',dict(result=result,beforeState=before_state,afterState=after_state,
                    beforeGraph=before_graph,afterGraph=after_graph,beforeRaw=before_raw,afterRaw=after_raw))
                self.results.append(result)
                dump(self.root/'result.json',dict(outcome='RUNNING',results=self.results))
                print(json.dumps(dict(event='WINDOW',layout=self.layout,workers=self.workers,resources=self.enabled,
                     count=count,window=window,mspt=observed['mspt'],cpu=observed['cpuPercentOfOneCore'])),flush=True)

    def close(self):
        if self.observer is not None:
            self.observer.stdin.close()
            self.observer.wait(timeout=15)
            self.observer_log.close()
        if self.client is not None:
            try:
                self.command('save-on')
                self.command('stop')
            except (OSError,RconError): pass
            self.client.close()
        if self.proc is not None:
            try: code=self.proc.wait(timeout=40)
            except subprocess.TimeoutExpired:
                self.proc.terminate()
                try: code=self.proc.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    code=self.proc.wait(timeout=10)
            self.log.close()
            if code: raise RuntimeError(f'Server exit {code}')
        dump(self.root/'commands.json',self.command_log)


def verify_artifacts(args, repository=REPO):
    # A candidate must supply its own exact hashes; changing version never disables binary pinning.
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)*(?:-[A-Za-z0-9.-]+)?',args.version):
        raise ValueError('Invalid pinned version')
    installer=repository/f'projects/neoforge/build/libs/neoforge-{args.version}-installer.jar'
    core=args.installation/f'libraries/net/neoforged/neoforge/{args.version}/neoforge-{args.version}-universal.jar'
    for path,expected in [(installer,args.installer_sha256),(core,args.universal_sha256)]:
        if not re.fullmatch(r'[a-f0-9]{64}',expected) or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:
            raise ValueError('Pinned binary hash mismatch: '+path.name)


def main():
    signal.signal(signal.SIGTERM,request_stop)
    signal.signal(signal.SIGINT,request_stop)
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runs',type=Path,required=True)
    p.add_argument('--installation',type=Path,required=True,help='Previously installed pinned build, immutable libraries')
    p.add_argument('--version',default=VERSION,help='Pinned installed NeoForge version (defaults to historical .58)')
    p.add_argument('--installer-sha256',default=SHA256)
    p.add_argument('--universal-sha256',default=UNIVERSAL_SHA256)
    p.add_argument('--eula',type=Path,required=True)
    p.add_argument('--java',type=Path,default=JAVA)
    p.add_argument('--classes',type=Path,required=True)
    p.add_argument('--counts',type=int,nargs='+',default=[2,64,256])
    p.add_argument('--layouts',nargs='+',choices=['chunk','chunks','regions'],default=['chunk','chunks','regions'])
    p.add_argument('--workers',type=int,nargs='+',default=[1,2,4,8])
    p.add_argument('--rounds',type=int,default=1)
    p.add_argument('--windows',type=int,default=2)
    p.add_argument('--warmup-ticks',type=int,default=600)
    p.add_argument('--window-ticks',type=int,default=600)
    p.add_argument('--resume-completed',action='store_true',help='Keep only complete PASS processes with identical protocol; refuse partial worlds')
    p.add_argument('--jfr',type=Path,help='Diagnostic run only: JFC settings, additional200tick recorder warmup')
    p.add_argument('--conditions',nargs='+',choices=['off','on'],default=['off','on'])
    args=p.parse_args()
    for key in ['runs','installation','eula','java','classes']: setattr(args,key,getattr(args,key).resolve())
    if not re.search(r'^\s*eula\s*=\s*true\s*$',args.eula.read_text(),re.M|re.I): raise ValueError('Previously accepted EULA required')
    if (any(n<0 or n>256 for n in args.counts) or len(set(args.counts)) != len(args.counts)
            or args.window_ticks<200 or args.window_ticks%200 or args.warmup_ticks<200 or args.warmup_ticks%200
            or any(n not in (1,2,4,8) for n in args.workers)):
        raise ValueError('Unique counts0..256; warmup/window positive multiples of200ticks; workers1/2/4/8')
    verify_artifacts(args)
    if args.jfr: args.jfr = args.jfr.resolve()
    args.runs.mkdir(parents=True,exist_ok=True)
    previous_manifest = None
    previous_hash = None
    if (args.runs/'manifest.json').exists():
        if not args.resume_completed:
            raise ValueError('Existing experiment requires explicit --resume-completed; never overwrite a world')
        previous_manifest=json.loads((args.runs/'manifest.json').read_text())
        if (previous_manifest['version']!=args.version or previous_manifest['installerSha256']!=args.installer_sha256
                or previous_manifest.get('universalSha256',UNIVERSAL_SHA256)!=args.universal_sha256):
            raise ValueError('Resuming different pinned binaries is refused')
        for key in ['counts','layouts','workers','rounds','windows','warmup_ticks','window_ticks','conditions','java','installation','jfr']:
            current=getattr(args,key)
            if isinstance(current,Path): current=str(current)
            if previous_manifest['parameters'].get(key)!=current:
                raise ValueError('Resuming a different protocol is refused: '+key)
        previous_hash=hashlib.sha256((args.runs/'manifest.json').read_bytes()).hexdigest()
        dump(args.runs/f'manifest-before-resume-{time.time_ns()}.json',previous_manifest)
    manifest=dict(version=args.version,installerSha256=args.installer_sha256,universalSha256=args.universal_sha256,jvm=VM,java=str(args.java),seed=813584,
        previousManifestSha256=previous_hash,
        parameters={k:str(v) if isinstance(v,Path) else v for k,v in vars(args).items()},
        note='Normal20TPS, unmodified installed binary, external read-only native JMX rings; metrics use only proven-active inner interval (+/-100ms) between unfreeze acknowledgment and last below-target gameTime query. Seed/layout/inputs/VM fixed per pair; no cold startup included. CPU100%=one logical core,8logical/4physical host.')
    dump(args.runs/'manifest.json',manifest)
    all_results=[]
    for round_ in range(args.rounds):
        for layout in args.layouts:
            for workers in args.workers:
                order=args.conditions if (round_+args.workers.index(workers)+args.layouts.index(layout))%2==0 else list(reversed(args.conditions))
                for condition in order:
                    run=Installed(args,layout,workers,condition=='on',round_)
                    if args.resume_completed and run.root.exists():
                        receipt=json.loads((run.root/'result.json').read_text())
                        values=receipt['results']
                        if receipt['outcome']!='PASS' or receipt['serverExit']!=0 or len(values)!=len(args.counts)*args.windows:
                            raise ValueError('Refusing to reuse a partial/failed world: '+str(run.root))
                        keys={(r['count'],r['window']) for r in values}
                        if keys!={(count,window) for count in args.counts for window in range(args.windows)}:
                            raise ValueError('Incomplete or duplicate saved shape/window')
                        for row in values:
                            if (row['layout'],row['workers'],row['resources'],row['round'],row['warmupTicks'],row['ticks'],row['initialCookTime']) != (
                                    layout,workers,condition=='on',round_,args.warmup_ticks,args.window_ticks,100):
                                raise ValueError('Saved runtime does not match the protocol')
                        all_results.extend(values)
                        print('RESUMED_ROWS',len(values),run.root.name,flush=True)
                        continue
                    try:
                        run.prepare();run.measure()
                    except BaseException as exc:
                        if run.root.exists(): dump(run.root/'failure.json',dict(error=repr(exc)))
                        raise
                    finally: run.close()
                    dump(run.root/'result.json',dict(outcome='PASS',serverExit=0,results=run.results))
                    all_results.extend(run.results)
                    dump(args.runs/'all-results.json',all_results)
    print('MATRIX_COMPLETE',len(all_results),flush=True)

if __name__=='__main__': main()
