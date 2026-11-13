#!/usr/bin/env python3
"""Assemble reproducible, qualified tail evidence; accepts only the frozen investigation receipts."""
import argparse
import json
from pathlib import Path
import statistics
import re
import xml.etree.ElementTree as ET
from server_ab import dump


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.startswith('{')]


def export(root, output, final_quiet=False):
    prefix='final-' if final_quiet else ''
    historical=json.loads((Path(__file__).resolve().parents[2]/'region-threading/BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION-PROFILE.json').read_text())
    comp=ET.parse(root/'timestamp.compiler.xml').getroot()
    native={v.attrib['compile_id']:v.attrib for v in comp.iter('nmethod')}
    tasks=[]
    for task in comp.iter('task'):
        if task.attrib.get('method','').startswith(('java.util.List of ','java.util.ImmutableCollections listCopy ')):
            tasks.append(dict(task=task.attrib,done=[v.attrib for v in task.findall('task_done')],
                              nmethod=native.get(task.attrib['compile_id'])))
    import csv
    measured=[]
    for fields in csv.reader((root/'timestamp.samples.csv').open()):
        units,shared,sample,start,capture,plan=map(int,fields)
        if (units,shared)==(64,64):
            measured.append(dict(sample=sample,startMonotonicNs=start,captureNs=capture,planNs=plan))
    clock=json.loads((root/'clock.json').read_text())
    vm_entries=[]
    for line in (root/'timestamp.vm.log').read_text().splitlines():
        match=re.match(r'\[(\d+)ms\]\[(\d+)ns\]',line)
        if match:
            vm_entries.append((int(match[1])*1_000_000,int(match[2]),line))
    epoch_origin=int(statistics.median(epoch-uptime for epoch,uptime,line in vm_entries))
    spike_evidence=[]
    for sample in measured:
        if sample['planNs']<1_000_000:
            continue
        start=sample['startMonotonicNs']+sample['captureNs']+clock['epochMinusMonotonicNs']
        end=start+sample['planNs']
        overlaps=[]
        for task in tasks:
            if task['task'].get('blocking')!='1' or task['task'].get('level')=='3':
                continue
            begin=epoch_origin+round(float(task['task']['stamp'])*1e9)
            finish=epoch_origin+round(float(task['done'][0]['stamp'])*1e9)
            if begin<end+1_000_000 and finish>start-1_000_000:
                overlaps.append(dict(method=task['task']['method'],compileId=task['task']['compile_id'],
                    blocking=True,beginEpochNsRounded=begin,endEpochNsRounded=finish,
                    durationNsRounded=finish-begin))
        gc_safepoints=[line for epoch,uptime,line in vm_entries if start-1_000_000<=epoch<=end+1_000_000
                       and ('[gc' in line or 'Safepoint ' in line)]
        if not overlaps or gc_safepoints:
            raise AssertionError('The specific JIT-only spike attribution no longer matches its evidence')
        spike_evidence.append(dict(sample=sample['sample'],planNs=sample['planNs'],
            startEpochNs=start,endEpochNs=end,c2BlockingOverlaps=overlaps,gcOrSafepointLogEntries=gc_safepoints,
            clockAndCompilationStampResolutionNs=1_000_000))
    repeats=[]
    for index,condition in enumerate(['before','after','after','before','before','after']):
        result=rows(root/f'{prefix}repeat-{index}-{condition}.jsonl')
        if len(result)!=12: raise ValueError('Incomplete repeat')
        repeats.append(dict(round=index,condition=condition,results=result))
    summaries=[]
    for units in [2,16,64,256]:
        for shared in [4,64,256]:
            row=dict(units=units,sharedReads=shared)
            for label in ['before','after']:
                case=[v for r in repeats if r['condition']==label for v in r['results'] if (v['units'],v['sharedReads'])==(units,shared)]
                row[label]={k:statistics.median(v[k] for v in case) for k in case[0] if k.endswith(('Ns','P50','P95'))}
            summaries.append(row)
    result=dict(baselineCommit='512a055fe867a82784e37ca4dc75814f5b929685',version='21.1.250.58-beta',
        frozenProductionClassHashes=json.loads((root/'frozen-class-hashes.json').read_text()),
        environment=json.loads((root/'environment.json').read_text()),
        historical64x64=[dict(round=r['round'],condition=r['condition'],result=next(v for v in r['results'] if (v['units'],v['sharedReads'])==(64,64))) for r in historical['rounds']],
        originalUnprofiledRepeats=[dict(round=i,results=rows(root/f'original-{i}.jsonl')) for i in range(3)],
        timestampOnlyOriginalRun=dict(results=rows(root/'timestamp.jsonl'),samples64x64=measured,
            c2Tasks=tasks,clockAnchor=clock,spikeEvidence=spike_evidence,
            semantics='Original timed run bytecode retained. Additional timestamps and stderr output outside measured run; unified log timemillis granularity1ms, LogCompilation stamps rounded milliseconds. Sample8/11 planner spikes overlap two blockingC2tasks; no corresponding GC/safepoint in logs.'),
        originalHarnessJfrObserverControls=[dict(round=i,results=rows(root/f'original-jfr-{i}.jsonl')) for i in range(2)],
        originalHarnessAsyncCompilerControl=rows(root/'original-async.jsonl'),
        originalHarnessLongWarmupControl=dict(caseWarmup=2000,samples=101,results=rows(root/'original-warm.jsonl')),
        traceJfrControls=[dict(label=label,results=rows(root/f'{label}.jsonl')) for label in ['exact','warm','exact21-0','exact21-1','exact21-2']],
        rerunOriginalPairedProbe=dict(scope='Unmodified original .58 common harness compiled separately against frozen .57/.58 ownership classes. Same original32shape warmups/21samples/-Xbatch; diagnostic reproduction, not a corrected steady-state server estimate. No production code changed.',
            acceptedFilePrefix=prefix+'repeat-',competingBuildsTestsServersOrJfrAnalysis=False,
            order=['before','after','after','before','before','after'],rounds=repeats,summary=summaries),
        correctedWarmupControl=dict(scope='One paired mechanism control after the execution environment reboot; separately compiled common harness, frozen .57/.58 classes. No simultaneous server/build/test. Extra warmup changes the measurement protocol, not production code. Not an independent three-round speedup claim.',
            caseWarmup=2000,samples=101,acceptedFilePrefix=prefix+'corrected-',
            competingBuildsTestsServersOrJfrAnalysis=False,
            resultsBefore=rows(root/f'{prefix}corrected-before.jsonl'),resultsAfter=rows(root/f'{prefix}corrected-after.jsonl')),
        conclusion='The reproduced historical two~5-7ms planner spikes are blockingC2compilation of JDK List.of/listCopy under-Xbatch after insufficient per-shape warmup. No production conflict/validator bypass or cross-tick cache was introduced. JFR itself changes the compilation threshold timeline; absence of the same spike under recording is not a fix.',
        limitations=['Attribution applies to the reproduced specific synthetic spikes, not all real-server tails',
                     'Original21sample p95 is the second largest sample, not a long-run percentile',
                     'Warm/async/JFR controls change runtime conditions; do not report them as a production speedup',
                     'Single-thread constant predicates and sequential scopes cannot measure real worker contention or dynamic Minecraft proof cost',
                     'Other non-compilation scheduling/CPU/GC spikes are retained; no unsupported cause is assigned'])
    dump(output,result)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--final-quiet',action='store_true',help='Use final isolated repeats taken after all regressions/analysis stopped')
    args=parser.parse_args()
    export(args.evidence,args.output,args.final_quiet)
