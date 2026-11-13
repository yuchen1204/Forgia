#!/usr/bin/env python3
"""Pinned installed-server mixed BE fixture. Uses existing external JMX; no helper mod or new admission."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import signal
import sys
import time
import zipfile
import csv
import io

import server_ab as base
from server_ab import Installed, dump, graph, metrics_summary, positions, verify_artifacts


class Snbt:
    """Read complete vanilla command SNBT. Reject duplicates, truncation and trailing data."""
    def __init__(self, text):
        self.text, self.at = text, 0
        if '<...>' in text:
            raise ValueError('Truncated SNBT')

    def space(self):
        while self.at < len(self.text) and self.text[self.at].isspace():
            self.at += 1

    def char(self):
        self.space()
        if self.at >= len(self.text):
            raise ValueError('Incomplete SNBT')
        return self.text[self.at]

    def quoted(self):
        quote = self.text[self.at]
        self.at += 1
        result = ''
        while self.at < len(self.text):
            char = self.text[self.at]
            self.at += 1
            if char == quote:
                return result
            if char == '\\':
                if self.at >= len(self.text) or self.text[self.at] not in (quote, '\\'):
                    raise ValueError('Invalid SNBT escape')
                char = self.text[self.at]
                self.at += 1
            result += char
        raise ValueError('Unterminated SNBT string')

    def token(self, key=False):
        self.space()
        start = self.at
        while self.at < len(self.text) and self.text[self.at] not in (':,{}[] \t\n\r' if key else ',{}[] \t\n\r'):
            self.at += 1
        if start == self.at:
            raise ValueError('Missing SNBT token')
        return self.text[start:self.at]

    def take(self, wanted):
        if self.char() != wanted:
            raise ValueError('Expected SNBT ' + wanted)
        self.at += 1

    def value(self):
        char = self.char()
        if char in ('"', "'"):
            return self.quoted()
        if char == '{':
            self.at += 1
            result = {}
            while self.char() != '}':
                key = self.quoted() if self.char() in ('"', "'") else self.token(True)
                self.take(':')
                if key in result:
                    raise ValueError('Duplicate SNBT key')
                result[key] = self.value()
                if self.char() != ',':
                    break
                self.at += 1
                if self.char() == '}':
                    raise ValueError('Trailing SNBT comma')
            self.take('}')
            return result
        if char == '[':
            self.at += 1
            self.space()
            if self.text[self.at:self.at+2] in ('I;', 'B;', 'L;'):
                self.at += 2
            result = []
            while self.char() != ']':
                result.append(self.value())
                if self.char() != ',':
                    break
                self.at += 1
                if self.char() == ']':
                    raise ValueError('Trailing SNBT comma')
            self.take(']')
            return result
        token = self.token()
        if re.fullmatch(r'[+-]?\d+[bBsSlL]?', token):
            return int(token.rstrip('bBsSlL'))
        if re.fullmatch(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?[fFdD]?', token):
            return float(token.rstrip('fFdD'))
        return token

    @classmethod
    def parse(cls, text):
        parser = cls(text)
        value = parser.value()
        parser.space()
        if parser.at != len(text):
            raise ValueError('Trailing SNBT data')
        return value


def canonical(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def contents(response):
    marker = re.search(r'has the following (?:contents|entity data): ', response)
    if marker is None:
        raise ValueError('No complete command data response: ' + response)
    return Snbt.parse(response[marker.end():])


def inventory(be):
    result = {}
    for stack in be.get('Items', []):
        if stack['Slot'] in result:
            raise ValueError('Duplicate inventory slot')
        result[stack['Slot']] = stack
    return result


def amount(be, slot):
    return inventory(be).get(slot, {}).get('count', 0)


def specification(layout):
    machines = []
    for i, pos in enumerate(positions(layout, 64)):
        kind = 'furnace' if i < 32 else 'blast_furnace' if i < 48 else 'smoker'
        machines.append(dict(pos=pos, kind=kind, input='potato' if kind == 'smoker' else 'iron_ore',
                             output='baked_potato' if kind == 'smoker' else 'iron_ingot'))
    # These 8 notification receivers force conservative completion ticks on their nearby furnaces.
    watched = [dict(pos=(m['pos'][0]+1, m['pos'][1], m['pos'][2]), kind='comparator') for m in machines[:8]]
    hoppers, brewing, chests, daylight, lamps, sheep = [], [], [], [], [], []
    for i, m in enumerate(machines[:8]):
        x, _, z = m['pos']
        y = 112
        for dx, dz, face in [(0, 0, 'east'), (1, 0, 'south'), (1, 1, 'west'), (0, 1, 'north')]:
            hoppers.append(dict(pos=(x+dx, y, z+dz), face=face, filled=dx == dz == 0))
        brewing.append(dict(pos=(x, 120, z)))
        chests.append(dict(pos=(x, 124, z)))
        daylight.append(dict(pos=(x, 128, z)))
        lamps.append(dict(pos=(x, 132, z)))
        for j in range(4):
            sheep.append(dict(pos=(x+.5, 140+j, z+.5), tag=f'fm_sheep_{i*4+j}'))
    return dict(machines=machines, watched=watched, hoppers=hoppers, brewing=brewing,
                chests=chests, daylight=daylight, lamps=lamps, sheep=sheep)


def xyz(p):
    return ' '.join(map(str, p))


def build_functions(spec):
    setup = ['scoreboard players set ticks fm 0',
             'scoreboard players set pulses fm 0', 'scoreboard players set redstone fm 0', 'scoreboard players set events fm 0',
             'scoreboard players set active fm 1', 'kill @e[tag=forgia_mixed]', 'time set 1000']
    refill = []
    for machine in spec['machines']:
        nbt = ('{BurnTime:30000,CookTime:100,CookTimeTotal:200,Items:'
               '[{Slot:0b,id:"minecraft:' + machine['input'] + '",count:64},'
               '{Slot:1b,id:"minecraft:coal",count:64}]}')
        setup.append(f'setblock {xyz(machine["pos"])} minecraft:{machine["kind"]}[lit=true]{nbt}')
        refill.append(f'data merge block {xyz(machine["pos"])} {nbt}')
    for row in spec['watched']:
        x, y, z = row['pos']
        setup += [f'setblock {x} {y-1} {z} minecraft:stone',
                  f'setblock {x} {y} {z} minecraft:comparator[facing=east]']
    for hopper in spec['hoppers']:
        nbt = '{Items:[{Slot:0b,id:"minecraft:iron_nugget",count:64}]}' if hopper['filled'] else ''
        setup.append(f'setblock {xyz(hopper["pos"])} minecraft:hopper[facing={hopper["face"]}]{nbt}')
    for row in spec['brewing']:
        nbt = ('{Fuel:20b,BrewTime:0s,Items:[{Slot:0b,id:"minecraft:potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:water"}}},'
               '{Slot:1b,id:"minecraft:potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:water"}}},'
               '{Slot:2b,id:"minecraft:potion",count:1,components:{"minecraft:potion_contents":{potion:"minecraft:water"}}},'
               '{Slot:3b,id:"minecraft:nether_wart",count:1}]}')
        setup.append(f'setblock {xyz(row["pos"])} minecraft:brewing_stand{nbt}')
        refill.append(f'data merge block {xyz(row["pos"])} {nbt}')
    for row in spec['chests']:
        setup.append(f'setblock {xyz(row["pos"])} minecraft:chest{{Items:[{{Slot:0b,id:"minecraft:diamond",count:64}}]}}')
    for row in spec['daylight']:
        setup.append(f'setblock {xyz(row["pos"])} minecraft:daylight_detector')
    high, low = [], []
    for row in spec['lamps']:
        x, y, z = row['pos']
        setup += [f'setblock {x} {y} {z} minecraft:redstone_lamp', f'setblock {x+1} {y} {z} minecraft:air']
        high.append(f'setblock {x+1} {y} {z} minecraft:redstone_block')
        low.append(f'setblock {x+1} {y} {z} minecraft:air')
    for row in spec['sheep']:
        setup.append(f'summon minecraft:sheep {xyz(row["pos"])} {{NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b,Age:-100000,Tags:["forgia_mixed","{row["tag"]}"]}}')
    setup.append('function forgia_mixed:refill')
    # Minecraft tick tag runs only when unfrozen; this clock causes real redstone neighbour and scheduled ticks.
    tick = ['execute if score active fm matches 1 run function forgia_mixed:active_tick']
    active = ['scoreboard players add ticks fm 1', 'scoreboard players add phase fm 1',
              'execute if score phase fm matches 10 run function forgia_mixed:high',
              'execute if score phase fm matches 20 run function forgia_mixed:low',
              'execute if score phase fm matches 20 run scoreboard players add pulses fm 1',
              'execute if score phase fm matches 20 run scoreboard players set phase fm 0']
    for row in spec['lamps']:
        p = xyz(row['pos'])
        active += [f'execute if score phase fm matches 6 if block {p} minecraft:redstone_lamp[lit=false] run scoreboard players add redstone fm 1',
                   f'execute if score phase fm matches 14 if block {p} minecraft:redstone_lamp[lit=true] run scoreboard players add redstone fm 1']
    setup += ['scoreboard players set phase fm 0']
    return dict(setup=setup, refill=refill, tick=tick, active_tick=active, high=high, low=low)


def validate_state(state, spec):
    if len(state['machines']) != 64 or len(state['hoppers']) != 32 or len(state['brewing']) != 8:
        raise AssertionError('Incomplete BE state')
    for machine, be in zip(spec['machines'], state['machines']):
        if be['id'] != 'minecraft:'+machine['kind'] or (be['x'], be['y'], be['z']) != tuple(machine['pos']):
            raise AssertionError('Furnace binding/type changed')
        if amount(be, 0)+amount(be, 2) != 64 or amount(be, 1) != 64:
            raise AssertionError('Furnace input/output/fuel conservation')
        for slot, name in [(0, machine['input']), (1, 'coal'), (2, machine['output'])]:
            if slot in inventory(be) and inventory(be)[slot]['id'] != 'minecraft:'+name:
                raise AssertionError('Wrong furnace item')
    for i in range(8):
        if sum(sum(s['count'] for s in be.get('Items', [])) for be in state['hoppers'][i*4:i*4+4]) != 64:
            raise AssertionError('Hopper ring item conservation')
    if len(state['sheep']) != 32 or state['entityCount'] != 32:
        raise AssertionError('Missing/duplicate normal entity ticks')
    if len(state['chests']) != 8 or any(amount(be, 0) != 64 for be in state['chests']):
        raise AssertionError('Chest state changed')
    for be in state['brewing']:
        if sum(amount(be, slot) for slot in range(3)) != 3:
            raise AssertionError('Brewing bottle conservation')
    return True


def validate_window(before, after, ticks, spec, output_per_machine=None):
    validate_state(before, spec)
    validate_state(after, spec)
    if after['ticks']-before['ticks'] != ticks or after['pulses']-before['pulses'] != ticks//20:
        raise AssertionError('Normal world function ticks/pulses missing')
    if after['redstone']-before['redstone'] != ticks//20*16:
        raise AssertionError('Redstone neighbour/scheduled tick transitions missing')
    if any(b['Age']-a['Age'] != ticks for a, b in zip(before['sheep'], after['sheep'])):
        raise AssertionError('Ordinary main-thread entity ticks missing')
    expected = ticks//200 if output_per_machine is None else output_per_machine
    if any(amount(b, 2)-amount(a, 2) != expected for a, b in zip(before['machines'], after['machines'])):
        raise AssertionError('Complete mixed furnace output mismatch')
    if any(a['BurnTime']-b['BurnTime'] != ticks for a, b in zip(before['machines'], after['machines'])):
        raise AssertionError('Lost or repeated furnace tick')
    return 64*expected


class MixedInstalled(Installed):
    def prepare(self):
        super().prepare()
        self.spec = specification(self.layout)
        self.functions = self.root/'world/datapacks/forgia_ab/data/forgia_mixed/function'
        self.functions.mkdir(parents=True)
        for name, lines in build_functions(self.spec).items():
            (self.functions/(name+'.mcfunction')).write_text('\n'.join(lines)+'\n')
        tags = self.functions.parents[1]/'minecraft/tags/function'
        tags.mkdir(parents=True, exist_ok=True)
        dump(tags/'tick.json', {'values': ['forgia_mixed:tick']})
        dump(tags/'load.json', {'values': ['forgia_mixed:load']})
        (self.functions/'load.mcfunction').write_text('scoreboard players add loads fm 1\n')
        self.recipe_files = []
        recipes = self.functions.parents[1]/'minecraft/recipe'
        recipes.mkdir(parents=True, exist_ok=True)
        for name, kind, inp, out in [('iron_ingot_from_smelting_iron_ore', 'smelting', 'iron_ore', 'iron_ingot'),
                                     ('iron_ingot_from_blasting_iron_ore', 'blasting', 'iron_ore', 'iron_ingot'),
                                     ('baked_potato_from_smoking', 'smoking', 'potato', 'baked_potato')]:
            path = recipes/(name+'.json')
            dump(path, dict(type='minecraft:'+kind, category='misc', ingredient={'item': 'minecraft:'+inp},
                            result={'id': 'minecraft:'+out}, experience=0, cookingtime=200))
            self.recipe_files.append(path)
        snapshot = []
        self.groups = []
        for kind in ['machines', 'hoppers', 'brewing', 'chests', 'daylight']:
            rows = self.spec[kind]
            for start in range(0, len(rows), 16):
                key = f'{kind}{start//16}'
                self.groups.append((kind, key, len(rows[start:start+16])))
                snapshot.append(f'data modify storage forgia_mixed:state {key} set value []')
                snapshot += [f'data modify storage forgia_mixed:state {key} append from block {xyz(row["pos"])}'
                             for row in rows[start:start+16]]
        snapshot += ['data modify storage forgia_mixed:state sheep set value []']
        for row in self.spec['sheep']:
            snapshot += ['data modify storage forgia_mixed:state sheep append value {}']
            for field in ['Age', 'Health', 'Pos', 'Motion']:
                snapshot.append(f'data modify storage forgia_mixed:state sheep[-1].{field} set from entity @e[tag={row["tag"]},limit=1] {field}')
        snapshot.append('execute store result storage forgia_mixed:state entityCount int 1 if entity @e[tag=forgia_mixed]')
        for key in ['ticks', 'pulses', 'redstone', 'events', 'loads']:
            snapshot.append(f'execute store result storage forgia_mixed:state {key} int 1 run scoreboard players get {key} fm')
        for i, row in enumerate(self.spec['lamps']):
            x, y, z = row['pos']
            snapshot.append(f'execute store success storage forgia_mixed:state lamp{i} int 1 if block {x} {y} {z} minecraft:redstone_lamp[lit=false]')
        (self.functions/'snapshot.mcfunction').write_text('\n'.join(snapshot)+'\n')
        self.command('scoreboard objectives add fm dummy')
        self.command('reload')
        self.advance(40)
        self.command('function forgia_mixed:setup')
        self.command('gamerule randomTickSpeed 3')
        self.command('gamerule doDaylightCycle true')
        self.fixture_hash = canonical({str(p.relative_to(self.functions.parents[2])): hashlib.sha256(p.read_bytes()).hexdigest()
                                      for p in sorted(self.functions.parents[2].rglob('*')) if p.is_file()})

    def score(self, name):
        result = self.command(f'scoreboard players get {name} fm')
        match = re.search(r'has (-?\d+)', result)
        if not match:
            raise ValueError('Missing score: '+result)
        return int(match[1])

    def state(self, count=64):
        self.command('function forgia_mixed:snapshot')
        state = {key: [] for key in ['machines', 'hoppers', 'brewing', 'chests', 'daylight']}
        for kind, key, expected in self.groups:
            values = contents(self.command(f'data get storage forgia_mixed:state {key}'))
            if not isinstance(values, list) or len(values) != expected:
                raise ValueError('Incomplete mixed snapshot '+key)
            state[kind].extend(values)
        state['sheep'] = contents(self.command('data get storage forgia_mixed:state sheep'))
        for key in ['entityCount', 'ticks', 'pulses', 'redstone', 'events', 'loads']+[f'lamp{i}' for i in range(8)]:
            state[key] = contents(self.command(f'data get storage forgia_mixed:state {key}'))
        validate_state(state, self.spec)
        return state

    def safety(self, raw, delta):
        for key in ['furnace.failed', 'furnace.skipped', 'furnace.rejectedTasks', 'resource.refusedWaves']:
            if delta[key]:
                raise AssertionError((key, delta[key]))
        for key in ['ownershipViolationCount', 'droppedDeferredEntityAdditions', 'droppedDeferredEntityRemovals']:
            match = re.search(r'\b'+key+r'=(\d+)', raw)
            if not match or int(match[1]):
                raise AssertionError('Missing/nonzero '+key)
        failure = re.search(r'tickFailures=(.*?), deferredUpdateBudgetChunkTicks=', raw, re.S)
        if not failure or any(int(v) for v in re.findall(r'\b\w+=(\d+)', failure[1])):
            raise AssertionError('Missing/nonzero tick failures')
        if 'persistentWorkers=true' not in raw:
            raise AssertionError('Actual persistent route not active')
        if self.enabled:
            if delta['resource.issuedLeases'] != delta['furnace.nativeCompleted']:
                raise AssertionError('Lease/body mismatch')
        elif any(value for key, value in delta.items() if key.startswith('resource.')):
            raise AssertionError('Resource work while OFF')

    def window(self, index, ticks, output_per_machine=None):
        before = self.state()
        bg = graph(self.command('neoforge regionthreading workgraph'))
        raw_before = self.command('neoforge regionthreading status raw')
        start = self.gametime()
        self.advance(ticks)
        a, b = self.active_bounds
        after_graph = graph(self.command('neoforge regionthreading workgraph'))
        raw_after = self.command('neoforge regionthreading status raw')
        after = self.state()
        delta = {k: v-bg[k] for k, v in after_graph.items() if k != 'furnace.maxSimultaneousBodies'}
        self.safety(raw_after, delta)
        outputs = validate_window(before, after, ticks, self.spec, output_per_machine)
        if delta['furnace.nativeCompleted'] <= 0 or delta['furnace.serial'] < (32+8+8)*ticks:
            raise AssertionError('Native work or conservative mixed BE serial fences absent')
        time.sleep(.2)
        samples = [json.loads(line) for line in (self.root/'metrics.jsonl').read_text().splitlines()]
        active_start, active_end = a+100_000_000, b-100_000_000
        selected = [s for s in samples if 'threads' in s and s['beforeNs'] >= active_start and s['afterNs'] <= active_end]
        if not selected or any(len([t for t in s['threads'] if t['name'].startswith('NeoForge Region Worker #')]) != self.workers for s in selected):
            raise AssertionError('Actual worker count differs')
        observed = metrics_summary(samples, active_start, active_end)
        result = dict(window=index, mode=self.args.mode, layout=self.layout, resources=self.enabled, round=self.round,
                      workers=self.workers, gameTimeStart=start, ticks=ticks, outputs=outputs,
                      beforeStateSha256=canonical(before), afterStateSha256=canonical(after),
                      deltas=delta, metrics=observed, activeInnerStartNs=active_start, activeInnerEndNs=active_end,
                      maxSimultaneousOriginalBodiesSinceBoot=after_graph['furnace.maxSimultaneousBodies'],
                      allFullBEStateConservationAndWorldTicksVerified=True, actualPoolAndThreadsVerified=True,
                      fixtureSha256=self.fixture_hash)
        dump(self.root/f'window-{index}.json', dict(result=result, beforeState=before, afterState=after,
                                                  beforeGraph=bg, afterGraph=after_graph, beforeRaw=raw_before, afterRaw=raw_after))
        self.results.append(result)
        dump(self.root/'result.json', dict(outcome='RUNNING', results=self.results))
        print(json.dumps(dict(event='MIXED_WINDOW', window=index, layout=self.layout, resources=self.enabled,
                              mspt=observed['mspt'], gcMillis=observed['gcMillis'],
                              allocationMBps=observed['serverAndWorkerAllocatedBytes']/observed['allocationObservationSeconds']/1e6)), flush=True)

    def reload_recipes(self, cooking_ticks):
        previous = self.score('loads')
        for path in self.recipe_files:
            recipe = json.loads(path.read_text())
            recipe['cookingtime'] = cooking_ticks
            dump(path, recipe)
        self.command('reload')
        # Reload is asynchronous. Accept only an observed completed load tag, then discard a full200ticks.
        for _ in range(10):
            self.advance(200)
            if self.score('loads') > previous:
                return
        raise AssertionError('Recipe reload did not complete')

    def dynamic(self):
        self.advance(self.args.warmup_ticks)
        self.command('function forgia_mixed:refill')
        target = xyz(self.spec['machines'][0]['pos'])
        edits = {
            'inventory_change': [f'data modify block {target} Items[0].components set value {{"minecraft:custom_name":\'{{"text":"changed during ticking"}}\'}}',
                                 'scoreboard players add events fm 1'],
            # Recreate the same logical state after69ticks; old BE/ticker objects must not gain new leases.
            'replacement': [f'setblock {target} minecraft:air',
                            f'setblock {target} minecraft:furnace[lit=true]{{BurnTime:29931,CookTime:169,CookTimeTotal:200,Items:[{{Slot:0b,id:"minecraft:iron_ore",count:64}},{{Slot:1b,id:"minecraft:coal",count:64}}]}}',
                            'scoreboard players add events fm 1']}
        for name, lines in edits.items():
            (self.functions/(name+'.mcfunction')).write_text('\n'.join(lines)+'\n')
        # Both legal mutations run on the server thread before that gameTick's BE phase.
        self.command('reload')
        self.advance(200)
        for index, name in enumerate(edits):
            self.command('function forgia_mixed:refill')
            prior = self.score('events')
            self.command(f'schedule function forgia_mixed:{name} 70t replace')
            self.window(index, 200)
            if self.score('events') != prior+1:
                raise AssertionError('Scheduled dynamic event missing')
        self.reload_recipes(400)
        self.command('function forgia_mixed:refill')
        for machine in self.spec['machines']:
            self.command(f'data merge block {xyz(machine["pos"])} {{CookTime:200,CookTimeTotal:400}}')
        self.window(2, 400, 1)
        self.reload_recipes(200)
        self.command('function forgia_mixed:refill')
        self.window(3, 200)
        self.unload_reload()

    def unload_reload(self):
        # Diagnostic-only vanilla perf report proves holder absence, not merely lack of ticking tickets.
        before = self.state()
        uuids = [contents(self.command(f'data get entity @e[tag={row["tag"]},limit=1] UUID')) for row in self.spec['sheep']]
        self.command('scoreboard players set active fm 0')
        self.command('save-on')
        self.command('save-all flush')
        self.command('setworldspawn 10000 64 10000')
        self.command('forceload remove all')
        self.advance(800)
        existing = set((self.root/'debug/profiling').glob('*.zip'))
        self.command('perf start')
        self.advance(240)
        deadline = time.monotonic()+30
        reports = []
        while time.monotonic() < deadline:
            reports = list(set((self.root/'debug/profiling').glob('*.zip'))-existing)
            if reports and all(zipfile.is_zipfile(path) for path in reports):
                break
            time.sleep(.2)
        if len(reports) != 1:
            raise AssertionError('Vanilla chunk-holder audit report missing')
        with zipfile.ZipFile(reports[0]) as archive:
            name = next(name for name in archive.namelist() if name.endswith('/overworld/chunks.csv'))
            csv_text = archive.read(name).decode()
        holders = list(csv.DictReader(io.StringIO(csv_text)))
        targets = {(m['pos'][0]//16, m['pos'][2]//16) for m in self.spec['machines']}
        present = [r for r in holders if (int(r['x']), int(r['z'])) in targets and r['in_memory'] == 'true']
        if present:
            raise AssertionError('Chunk unload not established: fixture holders still in memory')
        for cx, cz in sorted(targets):
            self.command(f'forceload add {(cx-1)*16} {(cz-1)*16} {(cx+1)*16} {(cz+1)*16}')
        self.advance(100)
        reloaded = self.state()
        after_uuids = [contents(self.command(f'data get entity @e[tag={row["tag"]},limit=1] UUID')) for row in self.spec['sheep']]
        if uuids != after_uuids or len({tuple(value) for value in after_uuids}) != 32:
            raise AssertionError('Entity unload/reload identity loss or duplication')
        # The reload warmup legitimately cooks; verify conservation and no effects greater than elapsed ticks.
        for a, b in zip(before['machines'], reloaded['machines']):
            spent = a['BurnTime']-b['BurnTime']
            made = amount(b, 2)-amount(a, 2)
            if not 0 <= spent <= 1140 or not 0 <= made <= 7:
                raise AssertionError('Unload/reload lost state or repeated effects')
        audit = dict(chunkHolderReportSha256=hashlib.sha256(csv_text.encode()).hexdigest(),
                     fixtureChunks=[list(p) for p in sorted(targets)], allFixtureHoldersAbsentOrNotInMemory=True,
                     beforeStateSha256=canonical(before), reloadedStateSha256=canonical(reloaded),
                     all32EntityUuidsPreservedAndUnique=True,
                     beforeState=before, reloadedState=reloaded, vanillaPerfDiagnosticExcludedFromTiming=True)
        dump(self.root/'unload-audit.json', audit)
        self.command('scoreboard players set active fm 1')
        self.command('save-off')
        self.command('function forgia_mixed:refill')
        self.window(4, 200)

    def measure(self):
        if self.args.mode == 'dynamic':
            self.dynamic()
            return
        self.advance(self.args.warmup_ticks)
        for index in range(self.args.windows):
            # Quiescent input/output reset is identical on both conditions and exercises new stack identities.
            self.command('function forgia_mixed:refill')
            self.window(index, self.args.window_ticks)


def main():
    signal.signal(signal.SIGINT, base.request_stop)
    signal.signal(signal.SIGTERM, base.request_stop)
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ['runs', 'installation', 'eula', 'classes']:
        parser.add_argument('--'+key, type=Path, required=True)
    parser.add_argument('--java', type=Path, default=base.JAVA)
    parser.add_argument('--version', required=True)
    parser.add_argument('--installer-sha256', required=True)
    parser.add_argument('--universal-sha256', required=True)
    parser.add_argument('--layouts', nargs='+', choices=['chunks', 'regions'], default=['chunks', 'regions'])
    parser.add_argument('--conditions', nargs='+', choices=['off', 'on'], default=['off', 'on'])
    parser.add_argument('--rounds', type=int, default=2)
    parser.add_argument('--mode', choices=['steady', 'soak', 'dynamic'], default='steady')
    parser.add_argument('--warmup-ticks', type=int, default=1200)
    parser.add_argument('--window-ticks', type=int, default=600)
    parser.add_argument('--windows', type=int, default=2)
    args = parser.parse_args()
    for key in ['runs', 'installation', 'eula', 'classes', 'java']:
        setattr(args, key, getattr(args, key).resolve())
    if any(v <= 0 or v % 200 for v in [args.warmup_ticks, args.window_ticks]) or args.window_ticks > 1200 or args.windows < 1 or args.rounds < 1:
        raise ValueError('Positive warmup/window multiples of200; window <=1200, positive windows/rounds')
    if not re.search(r'^\s*eula\s*=\s*true\s*$', args.eula.read_text(), re.M|re.I):
        raise ValueError('Existing accepted EULA required')
    args.counts = [64]
    args.jfr = None
    verify_artifacts(args)
    args.runs.mkdir(parents=True, exist_ok=False)
    manifest = dict(version=args.version, installerSha256=args.installer_sha256, universalSha256=args.universal_sha256,
                    parameters={k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()}, jvm=base.VM,
                    head=__import__('subprocess').check_output(['git', 'rev-parse', 'HEAD'], cwd=base.REPO, text=True).strip(),
                    note='Private full mixed workload. Exact full BE state, selected deterministic entity fields, conservation and gameTick counters; external JMX native ring inner-active timing. No instrumentation mod or new admission.')
    dump(args.runs/'manifest.json', manifest)
    for round_ in range(args.rounds):
        for layout in args.layouts:
            order = args.conditions if round_ % 2 == 0 else list(reversed(args.conditions))
            for label in order:
                run = MixedInstalled(args, layout, 4, label == 'on', round_)
                try:
                    run.prepare()
                    run.measure()
                except BaseException as exc:
                    if run.root.exists():
                        dump(run.root/'failure.json', {'error': repr(exc)})
                    raise
                finally:
                    run.close()
                dump(run.root/'result.json', dict(outcome='PASS', serverExit=0, results=run.results))
                print('MIXED_PROCESS_COMPLETE', run.root.name, flush=True)
    print('MIXED_COMPLETE', flush=True)


if __name__ == '__main__':
    main()
