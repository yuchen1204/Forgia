#!/usr/bin/env python3
"""Recompute mixed installed-server results and require complete deterministic state/work equivalence."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from server_ab import dump, metrics_summary, tick_samples, timing
from mixed_workload import canonical, validate_window, specification


def load(root):
    manifest=json.loads((root/'manifest.json').read_text())
    rows=[]
    parameters=manifest['parameters']
    for directory in sorted(root.glob('r*-*-w4-*')):
        receipt=json.loads((directory/'result.json').read_text())
        if receipt['outcome']!='PASS' or receipt['serverExit']!=0:
            raise ValueError('Partial/failed mixed process '+str(directory))
        samples=[json.loads(line) for line in (directory/'metrics.jsonl').read_text().splitlines()]
        for saved in receipt['results']:
            detail_path=directory/f'window-{saved["window"]}.json'
            detail=json.loads(detail_path.read_text())
            assert saved==detail['result']
            bg,ag=detail['beforeGraph'],detail['afterGraph']
            recomputed={key:value-bg[key] for key,value in ag.items() if key!='furnace.maxSimultaneousBodies'}
            if recomputed!=saved['deltas']:
                raise AssertionError('Counter deltas differ from complete receipts')
            raw=detail['afterRaw']
            if 'persistentWorkers=true' not in raw or 'entityTickWorkerExecution=false' not in raw:
                raise AssertionError('Unexpected worker/entity route')
            for key in ['ownershipViolationCount','droppedDeferredEntityAdditions','droppedDeferredEntityRemovals','rejectedWorkerTasks','runningRegions']:
                found=re.search(r'\b'+key+r'=(\d+)',raw)
                if not found or int(found[1]):
                    raise AssertionError('Missing/nonzero safety counter '+key)
            failures=re.search(r'tickFailures=(.*?), deferredUpdateBudgetChunkTicks=',raw,re.S)
            if not failures or any(int(v) for v in re.findall(r'\b\w+=(\d+)',failures[1])):
                raise AssertionError('Missing/nonzero tick failures')
            for key in ['furnace.failed','furnace.skipped','furnace.rejectedTasks','resource.refusedWaves']:
                if saved['deltas'][key]:
                    raise AssertionError('Nonzero failure/refusal '+key)
            if saved['resources']:
                if saved['deltas']['resource.issuedLeases']!=saved['deltas']['furnace.nativeCompleted']:
                    raise AssertionError('Missing/excess resource leases')
            elif any(value for key,value in saved['deltas'].items() if key.startswith('resource.')):
                raise AssertionError('Resource work while OFF')
            selected=[s for s in samples if 'threads' in s and s['beforeNs']>=saved['activeInnerStartNs'] and s['afterNs']<=saved['activeInnerEndNs']]
            if not selected or any(len([t for t in s['threads'] if t['name'].startswith('NeoForge Region Worker #')])!=saved['workers'] for s in selected):
                raise AssertionError('Actual worker count mismatch')
            for label,field in [('before','beforeState'),('after','afterState')]:
                assert canonical(detail[field])==saved[label+'StateSha256']
            expected_outputs=1 if saved['mode']=='dynamic' and saved['window']==2 else saved['ticks']//200
            if saved['outputs']!=64*expected_outputs:
                raise AssertionError('Configured recipe/window output mismatch')
            before,after=detail['beforeState'],detail['afterState']
            validate_window(before,after,saved['ticks'],specification(saved['layout']),expected_outputs)
            if saved['mode']!='dynamic':
                for a,b in zip(before['machines'],after['machines']):
                    if sum(b.get('RecipesUsed',{}).values())-sum(a.get('RecipesUsed',{}).values())!=expected_outputs:
                        raise AssertionError('Recipe use history did not match completed output')
            if saved['deltas']['furnace.nativeCompleted']+saved['deltas']['furnace.serial']!=112*saved['ticks']:
                raise AssertionError('Every mixed tickable BE must run exactly once')
            for be in after['brewing']:
                slots={stack['Slot']:stack for stack in be['Items']}
                completed=saved['ticks']>400
                potion='minecraft:awkward' if completed else 'minecraft:water'
                if be['Fuel']!=19 or be['BrewTime']!=(0 if completed else 401-saved['ticks']):
                    raise AssertionError('Brewing progress/one fuel consumption not exact')
                if (3 in slots)==completed or any(slots[slot]['components']['minecraft:potion_contents']['potion']!=potion for slot in range(3)):
                    raise AssertionError('Brewing reagent/output mismatch')
            for index in range(8):
                nonempty=sum(bool(be['Items']) for be in after['hoppers'][index*4:index*4+4])
                if nonempty<2 or any(stack['id']!='minecraft:iron_nugget' for be in after['hoppers'][index*4:index*4+4] for stack in be['Items']):
                    raise AssertionError('Hopper transport inactive or item replaced')
            if metrics_summary(samples,saved['activeInnerStartNs'],saved['activeInnerEndNs'])!=saved['metrics']:
                raise AssertionError('CPU/allocation/GC/heap or tick metrics differ from native observer records')
            values=tick_samples(samples,saved['activeInnerStartNs'],saved['activeInnerEndNs'])
            if timing(values)!=saved['metrics']['mspt']:
                raise AssertionError('Published quantiles differ from native ring')
            rows.append(dict(saved,nativeActiveTickMs=values,completeBeforeState=before,completeAfterState=after,
                             allTickableBodiesAndBrewingAndHopperTransportVerified=True,
                             localReceiptSha256=hashlib.sha256(detail_path.read_bytes()).hexdigest()))
    windows=5 if parameters['mode']=='dynamic' else parameters['windows']
    expected={(r,l,c=='on',w) for r in range(parameters['rounds']) for l in parameters['layouts'] for c in parameters['conditions'] for w in range(windows)}
    actual={(r['round'],r['layout'],r['resources'],r['window']) for r in rows}
    if expected!=actual or len(actual)!=len(rows):
        raise AssertionError('Missing/duplicate mixed windows')
    return dict(manifest=manifest,windows=rows)


def aggregate(rows):
    ticks=sum(r['ticks'] for r in rows)
    allocation_seconds=sum(r['metrics']['allocationObservationSeconds'] for r in rows)
    cpu_seconds=sum(r['metrics']['cpuObservationSeconds'] for r in rows)
    return dict(mspt=timing([v for r in rows for v in r['nativeActiveTickMs']]),
                ticks=ticks,windows=len(rows),outputs=sum(r['outputs'] for r in rows),
                allocatedBytesPerSecond=sum(r['metrics']['serverAndWorkerAllocatedBytes'] for r in rows)/allocation_seconds,
                cpuPercentOneCore=sum(r['metrics']['cpuSeconds'] for r in rows)/cpu_seconds*100,
                gcCount=sum(r['metrics']['gcCount'] for r in rows),gcMillis=sum(r['metrics']['gcMillis'] for r in rows),
                phaseMsPerTick={k:sum(r['deltas'][k] for r in rows)/ticks for k in ['furnace.prepare','furnace.executeAwait','furnace.commit','resource.proofAndPlan','resource.revalidation','resource.reservation','resource.fullPhase']},
                nativeBodies=sum(r['deltas']['furnace.nativeCompleted'] for r in rows),
                completeSerialTicks=sum(r['deltas']['furnace.serial'] for r in rows),
                leases=sum(r['deltas']['resource.issuedLeases'] for r in rows),
                maxBodiesSinceBoot=max(r['maxSimultaneousOriginalBodiesSinceBoot'] for r in rows))


def equivalent(a,b,full_state=True):
    for key in ['ticks','outputs','fixtureSha256']+(['beforeStateSha256','afterStateSha256'] if full_state else []):
        if a[key]!=b[key]:
            raise AssertionError(('mixed state/work inequivalence',key,a['window'],a[key],b[key]))
    for key in ['furnace.nativeCompleted','furnace.serial','furnace.changes','furnace.failed','furnace.skipped','furnace.rejectedTasks']:
        if a['deltas'][key]!=b['deltas'][key]:
            raise AssertionError(('mixed execution-route inequivalence',key,a['window']))


def export(root,output):
    record=load(root)
    params=record['manifest']['parameters']
    pairs=[]
    if set(params['conditions'])=={'off','on'}:
        for round_ in range(params['rounds']):
            for layout in params['layouts']:
                off=[r for r in record['windows'] if (r['round'],r['layout'],r['resources'])==(round_,layout,False)]
                on=[r for r in record['windows'] if (r['round'],r['layout'],r['resources'])==(round_,layout,True)]
                for a,b in zip(off,on):
                    equivalent(a,b)
                a,b=aggregate(off),aggregate(on)
                pairs.append(dict(round=round_,layout=layout,off=a,on=b,completeStateEquivalent=True,
                                  msptOnOffRatios={k:b['mspt'][k]/a['mspt'][k] for k in ['mean','p50','p95','p99']}))
    record['pairs']=pairs
    record['methodology']='Independent JVM rounds remain separate; inner-active native ticks, external JMX allocation/CPU/GC, exact full-window work/state. All complete paired receipts required; no dropped tails. No claim about arbitrary callback or admitted BE classes.'
    dump(output,record)
    for p in pairs:
        print('MIXED_PAIR',p['round'],p['layout'],'mean',p['off']['mspt']['mean'],'->',p['on']['mspt']['mean'],
              'allocation',p['off']['allocatedBytesPerSecond'],'->',p['on']['allocatedBytesPerSecond'],flush=True)
    return record


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    export(args.runs,args.output)
