#!/usr/bin/env python3
"""Installed correctness-only Hopper/Furnace pipeline. All original admission and serial fences retained."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess

import server_ab as base
from server_ab import dump, graph, verify_artifacts
from mixed_workload import MixedInstalled, canonical, contents, inventory, amount, xyz


class Pipeline(MixedInstalled):
    def prepare(self):
        super().prepare()
        self.pipeline = []
        for machine in self.spec['machines'][:8]:
            x, y, z = machine['pos']
            # Install Hopper tickers before replacing the furnace's ticker, exercising a real serial fence.
            self.command(f'data merge block {x} {y} {z} {{Items:[]}}')
            self.command(f'setblock {x} {y} {z} minecraft:air')
            self.command(f'setblock {x} {y-2} {z} minecraft:chest')
            self.command(f'setblock {x} {y-1} {z} minecraft:hopper[facing=down]')
            self.command(f'setblock {x} {y+1} {z} minecraft:hopper[facing=down]{{Items:[{{Slot:0b,id:"minecraft:iron_ore",count:64}}]}}')
            self.command(f'setblock {x} {y} {z} minecraft:furnace[lit=true]{{BurnTime:30000,CookTime:0,CookTimeTotal:200,Items:[{{Slot:1b,id:"minecraft:coal",count:64}}]}}')
            self.pipeline += [(x, y+1, z), (x, y-1, z), (x, y-2, z)]
        snapshot = ['function forgia_mixed:snapshot', 'data modify storage forgia_mixed:state pipeline set value []']
        snapshot += [f'data modify storage forgia_mixed:state pipeline append from block {xyz(pos)}' for pos in self.pipeline]
        (self.functions/'pipeline_snapshot.mcfunction').write_text('\n'.join(snapshot)+'\n')
        previous = self.score('loads')
        self.command('reload')
        for _ in range(10):
            self.advance(200)
            if self.score('loads') > previous:
                break
        else:
            raise AssertionError('Pipeline function reload incomplete')
        self.advance(400)

    def snapshot(self):
        self.command('function forgia_mixed:pipeline_snapshot')
        state = {key: [] for key in ['machines', 'hoppers', 'brewing', 'chests', 'daylight']}
        for kind, key, count in self.groups:
            values = contents(self.command('data get storage forgia_mixed:state '+key))
            if len(values) != count:
                raise AssertionError('Incomplete pipeline base snapshot')
            state[kind].extend(values)
        for key in ['sheep', 'entityCount', 'ticks', 'pulses', 'redstone', 'events', 'loads', 'pipeline']:
            state[key] = contents(self.command('data get storage forgia_mixed:state '+key))
        if len(state['pipeline']) != 24 or len(state['machines']) != 64 or len(state['sheep']) != 32 or state['entityCount'] != 32:
            raise AssertionError('Incomplete pipeline snapshot')
        for i in range(8):
            bes = [state['machines'][i], *state['pipeline'][i*3:i*3+3]]
            stacks = [s for be in bes for s in be.get('Items', [])]
            if sum(s['count'] for s in stacks if s['id'] in ['minecraft:iron_ore', 'minecraft:iron_ingot']) != 64:
                raise AssertionError('Pipeline ore/ingot conservation')
            if amount(state['machines'][i], 1) != 64:
                raise AssertionError('Unexpected pipeline fuel consumption')
        for be in state['machines'][8:]:
            if amount(be, 0)+amount(be, 2) != 64 or amount(be, 1) != 64:
                raise AssertionError('Isolated machine conservation')
        for i in range(8):
            if sum(sum(s['count'] for s in be.get('Items', [])) for be in state['hoppers'][i*4:i*4+4]) != 64:
                raise AssertionError('Independent loop conservation')
        return state

    def measure(self):
        before = self.snapshot()
        after = self.snapshot()
        if canonical(before) != canonical(after):
            raise AssertionError('Frozen pipeline snapshot changed without a world tick')
        for index in range(2):
            before = self.snapshot()
            bg = graph(self.command('neoforge regionthreading workgraph'))
            self.advance(1200)
            after = self.snapshot()
            ag = graph(self.command('neoforge regionthreading workgraph'))
            delta = {key: value-bg[key] for key, value in ag.items() if key != 'furnace.maxSimultaneousBodies'}
            raw = self.command('neoforge regionthreading status raw')
            self.safety(raw, delta)
            if delta['furnace.nativeCompleted']+delta['furnace.serial'] != 128*1200:
                raise AssertionError('Not exactly128 mixed tickable BEs perTick')
            if after['ticks']-before['ticks'] != 1200 or after['redstone']-before['redstone'] != 960:
                raise AssertionError('World/scheduled ticks missing')
            if any(b['Age']-a['Age'] != 1200 for a, b in zip(before['sheep'], after['sheep'])):
                raise AssertionError('Ordinary entity ticks missing')
            for i in range(8):
                def output(state):
                    bes=[state['machines'][i], *state['pipeline'][i*3:i*3+3]]
                    return sum(s['count'] for be in bes for s in be.get('Items', []) if s['id']=='minecraft:iron_ingot')
                if output(after)-output(before) != 6:
                    raise AssertionError('Pipeline processing count')
                sink=after['pipeline'][i*3+2]
                if not any(s['id']=='minecraft:iron_ingot' for s in sink.get('Items', [])):
                    raise AssertionError('Output Hopper never extracted furnace product')
            for a, b in zip(before['machines'][8:], after['machines'][8:]):
                if amount(b, 2)-amount(a, 2) != 6:
                    raise AssertionError('Other mixed furnace output missing')
            result=dict(window=index,layout=self.layout,resources=self.enabled,ticks=1200,deltas=delta,
                        beforeStateSha256=canonical(before),afterStateSha256=canonical(after),
                        allPipelineConservationAndTransfersVerified=True,allWorldAndEntityTicksVerified=True,
                        notPerformanceEvidence=True)
            dump(self.root/f'pipeline-{index}.json', dict(result=result,beforeState=before,afterState=after,
                                                        beforeGraph=bg,afterGraph=ag,afterRaw=raw))
            self.results.append(result)
            dump(self.root/'result.json',dict(outcome='RUNNING',results=self.results))
            print('PIPELINE_WINDOW_PASS',self.enabled,index,delta['furnace.nativeCompleted'],delta['furnace.serial'],flush=True)


def main():
    signal.signal(signal.SIGINT,base.request_stop)
    signal.signal(signal.SIGTERM,base.request_stop)
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['runs','installation','eula','classes']:
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--java',type=Path,default=base.JAVA)
    parser.add_argument('--version',required=True)
    parser.add_argument('--installer-sha256',required=True)
    parser.add_argument('--universal-sha256',required=True)
    args=parser.parse_args()
    for name in ['runs','installation','eula','classes','java']:
        setattr(args,name,getattr(args,name).resolve())
    if not re.search(r'^\s*eula\s*=\s*true\s*$',args.eula.read_text(),re.M|re.I):
        raise ValueError('Existing accepted EULA required')
    args.counts=[64]
    args.jfr=None
    verify_artifacts(args)
    args.runs.mkdir(parents=True,exist_ok=False)
    dump(args.runs/'manifest.json',dict(parameters={k:str(v) if isinstance(v,Path) else v for k,v in vars(args).items()},
                                      jvm=base.VM,note='Separate correctness-only pipeline; no new admission, 8additional input and8output Hopper tickers,8non-ticking sinks;128tickableBEs,32ordinaryentities.'))
    for enabled in [False,True]:
        run=Pipeline(args,'regions',4,enabled,0)
        try:
            run.prepare()
            run.measure()
        except BaseException as exc:
            if run.root.exists():dump(run.root/'failure.json',dict(error=repr(exc)))
            raise
        finally:
            run.close()
        dump(run.root/'result.json',dict(outcome='PASS',serverExit=0,results=run.results))
        print('PIPELINE_PROCESS_COMPLETE',enabled,flush=True)
    a=json.loads((args.runs/'r0-regions-w4-off/result.json').read_text())
    b=json.loads((args.runs/'r0-regions-w4-on/result.json').read_text())
    for x,y in zip(a['results'],b['results']):
        for key in ['beforeStateSha256','afterStateSha256','ticks']:
            if x[key]!=y[key]:raise AssertionError('Pipeline ON/OFF state inequivalence '+key)
    print('PIPELINE_COMPLETE_FULL_STATE_EQUIVALENT',flush=True)


if __name__=='__main__':
    main()
