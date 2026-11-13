#!/usr/bin/env python3
"""Export complete, non-secret installed-server paired metrics and exact equivalence assertions."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
from server_ab import dump, tick_samples, timing


def export(root, output, unpaired=False):
    manifest=json.loads((root/'manifest.json').read_text())
    labels=manifest['parameters'].get('conditions',['off','on'])
    if not unpaired and set(labels)!={'off','on'}:
        raise ValueError('ON/OFF export requires both conditions; use explicit unpaired export for a build comparison')
    rows=[]
    cases=[]
    for directory in sorted(root.glob('r*-*-w*-*')):
        receipt=json.loads((directory/'result.json').read_text())
        if receipt['outcome']!='PASS' or receipt['serverExit']!=0:
            raise ValueError('Incomplete/failed runtime: '+str(directory))
        samples=[json.loads(line) for line in (directory/'metrics.jsonl').read_text().splitlines()]
        for row in receipt['results']:
            normalized=dict(row)
            if 'activeInnerStartNs' not in row:
                raise ValueError('This exporter requires final active-interval protocol')
            times=tick_samples(samples,row['activeInnerStartNs'],row['activeInnerEndNs'])
            detail_path=directory/f'c{row["count"]}-window{row["window"]}.json'
            detail=json.loads(detail_path.read_text())
            if 'persistentWorkers=true' not in detail['afterRaw']:
                raise AssertionError('Configured persistent pool was not effective')
            selected=[s for s in samples if 'threads' in s and s['beforeNs']>=row['activeInnerStartNs'] and s['afterNs']<=row['activeInnerEndNs']]
            if not selected or any(len([t for t in s['threads'] if t['name'].startswith('NeoForge Region Worker #')])!=row['workers'] for s in selected):
                raise AssertionError('Configured worker count did not match live JVM threads')
            if row['deltas']['furnace.changes']!=row['outputItems'] or row['deltas']['furnace.serial']:
                raise AssertionError('Completion notifications or fully native fixture coverage differ')
            if row['resources']:
                if row['deltas']['resource.issuedLeases']!=row['deltas']['furnace.nativeCompleted']:
                    raise AssertionError('Resource-on route did not issue exact one-shot leases')
            elif any(value for key,value in row['deltas'].items() if key.startswith('resource.')):
                raise AssertionError('Resource-off route still performed resource work')
            controller_offset=row['activeInnerStartEpochNs']-row['activeInnerStartNs']
            # currentTimeMillis was read somewhere within [beforeNs,afterNs], rounded down1ms.
            # Account for preemption/observer acquisition instead of mistaking it for a clock-origin shift.
            clock_error=max(max(0,s['epochMillis']*1_000_000-s['afterNs']-controller_offset,
                                controller_offset-(s['epochMillis']*1_000_000+1_000_000-s['beforeNs'])) for s in selected)
            if clock_error>10_000_000:
                raise AssertionError('Controller/JVM monotonic clocks do not share the observed Linux timebase')
            normalized['observerControllerMaxClockOffsetErrorNs']=clock_error
            normalized['actualPoolModeAndWorkerCountVerified']=True
            if timing(times)!=row['metrics']['mspt']:
                raise AssertionError('Published quantiles do not match native samples')
            normalized['nativeActiveTickMs']=[round(v,6) for v in times]
            normalized['localReceiptSha256']=hashlib.sha256(detail_path.read_bytes()).hexdigest()
            rows.append(normalized)
    expected={(round_,layout,workers,count,label=='on',window)
              for round_ in range(manifest['parameters']['rounds']) for layout in manifest['parameters']['layouts']
              for workers in manifest['parameters']['workers'] for count in manifest['parameters']['counts']
              for label in labels for window in range(manifest['parameters']['windows'])}
    actual={(r['round'],r['layout'],r['workers'],r['count'],r['resources'],r['window']) for r in rows}
    if not expected or actual!=expected or len(actual)!=len(rows):
        raise ValueError('Incomplete/duplicate experiment windows')
    identities=sorted({(r['round'],r['layout'],r['workers'],r['count']) for r in rows})
    for round_,layout,workers,count in identities:
        conditions={}
        for flag,label in [(label=='on',label) for label in labels]:
            windows=sorted([r for r in rows if (r['round'],r['layout'],r['workers'],r['count'],r['resources'])==(round_,layout,workers,count,flag)],key=lambda r:r['window'])
            if len(windows)!=manifest['parameters']['windows']:
                raise ValueError('Missing paired steady window')
            values=[v for r in windows for v in r['nativeActiveTickMs']]
            conditions[label]=dict(mspt=timing(values),windows=len(windows),
                tpsMean=statistics.mean(r['tps'] for r in windows),
                cpuPercentOneCoreMean=statistics.mean(r['metrics']['cpuPercentOfOneCore'] for r in windows),
                gcCount=sum(r['metrics']['gcCount'] for r in windows),
                gcMillis=sum(r['metrics']['gcMillis'] for r in windows),
                originalBodies=sum(r['deltas']['furnace.nativeCompleted'] for r in windows),
                outputItems=sum(r['outputItems'] for r in windows),
                issuedLeases=sum(r['deltas']['resource.issuedLeases'] for r in windows),
                resourceProofAndPlanMs=sum(r['deltas']['resource.proofAndPlan'] for r in windows),
                resourceRevalidationMs=sum(r['deltas']['resource.revalidation'] for r in windows),
                resourceReservationMs=sum(r['deltas']['resource.reservation'] for r in windows),
                resourceFullPhaseMs=sum(r['deltas']['resource.fullPhase'] for r in windows),
                furnacePrepareMs=sum(r['deltas']['furnace.prepare'] for r in windows),
                furnaceExecuteAwaitMs=sum(r['deltas']['furnace.executeAwait'] for r in windows),
                furnaceCommitMs=sum(r['deltas']['furnace.commit'] for r in windows),
                serverWorkerAllocationBytes=sum(r['metrics']['serverAndWorkerAllocatedBytes'] for r in windows),
                allocationObservationSeconds=sum(r['metrics']['allocationObservationSeconds'] for r in windows),
                observedGameTicks=sum(r['ticks'] for r in windows),
                cumulativeMaxBodies=max(r['maxSimultaneousOriginalBodiesSinceBoot'] for r in windows))
        if unpaired:
            cases.append(dict(round=round_,layout=layout,workers=workers,count=count,conditions=conditions))
            continue
        for window in range(manifest['parameters']['windows']):
            off=next(r for r in rows if (r['round'],r['layout'],r['workers'],r['count'],r['resources'],r['window'])==(round_,layout,workers,count,False,window))
            on=next(r for r in rows if (r['round'],r['layout'],r['workers'],r['count'],r['resources'],r['window'])==(round_,layout,workers,count,True,window))
            for key in ['stateBeforeSha256','stateAfterSha256','ticks','outputItems']:
                if off[key]!=on[key]: raise AssertionError((layout,workers,count,window,'equivalence',key))
            for key in ['furnace.nativeCompleted','furnace.changes','furnace.serial','furnace.failed','furnace.skipped','furnace.rejectedTasks']:
                if off['deltas'][key]!=on['deltas'][key]: raise AssertionError((layout,workers,count,window,key))
        off,on=conditions['off'],conditions['on']
        ratios={key:on['mspt'][key]/off['mspt'][key] for key in ['mean','p50','p95','p99']}
        cases.append(dict(round=round_,layout=layout,workers=workers,count=count,off=off,on=on,
                          afterBeforeMsptRatios=ratios,completeStateEquivalent=True))
    record=dict(manifest=manifest,
        methodology='Per-condition MSPT pools the configured consecutive inner-active windows in one JVM; not independent JVM repetitions or confidence intervals. Complete per-tick samples below. CPU/GC are MXBean deltas in these inner intervals. Exact body/state counters use the separately configured gameTick window. Native time ring endpoint ambiguity and observer overhead documented. No full-modpack or arbitrary BE speedup.',
        pairs=[] if unpaired else cases,windows=rows)
    if unpaired: record['unpairedConditions']=cases
    dump(output,record)
    print('EXPORTED',len(cases),'unpaired cases' if unpaired else 'pairs',len(rows),'windows',sum(r['metrics']['mspt']['samples'] for r in rows),'native tick samples')
    for case in [] if unpaired else cases:
        a,b=case['off']['mspt'],case['on']['mspt']
        print(case['layout'],case['workers'],case['count'],
              f"mean {a['mean']:.3f}->{b['mean']:.3f},p95 {a['p95']:.3f}->{b['p95']:.3f}",
              'ratios',case['afterBeforeMsptRatios'])
    return record


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--unpaired',action='store_true',help='Explicit single-condition evidence for comparing pinned builds; no ON/OFF equivalence claim')
    args=parser.parse_args()
    export(args.runs,args.output,args.unpaired)
