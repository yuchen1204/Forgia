#!/usr/bin/env python3
"""Compare pinned builds using identical installed-server workloads and exact work receipts."""
import argparse
from pathlib import Path
from export_results import export
from server_ab import dump, timing


def compatible(before, after):
    for key in ['jvm','java','seed']:
        if before[key]!=after[key]: raise ValueError('Build comparison changes '+key)
    for key in ['counts','layouts','workers','rounds','windows','warmup_ticks','window_ticks','conditions','jfr']:
        if before['parameters'].get(key)!=after['parameters'].get(key):
            raise ValueError('Build comparison changes workload: '+key)
    if before['parameters'].get('jfr'):
        raise ValueError('Keep profiled diagnostics separate from unprofiled build comparisons')


def equivalent(before, after):
    for key in ['stateBeforeSha256','stateAfterSha256','ticks','outputItems','initialCookTime']:
        if before[key]!=after[key]: raise ValueError('Different completed work: '+key)
    for key in ['furnace.nativeCompleted','furnace.changes','furnace.serial','furnace.failed','furnace.skipped',
                'furnace.rejectedTasks','resource.issuedLeases','resource.refusedWaves']:
        if before['deltas'][key]!=after['deltas'][key]: raise ValueError('Different completion counter: '+key)


def aggregate(rows):
    result={'mspt':timing([v for row in rows for v in row['nativeActiveTickMs']])}
    result['observedGameTicks']=sum(row['ticks'] for row in rows)
    for source,target in [('furnace.nativeCompleted','originalBodies'),('furnace.changes','notifications'),
                          ('resource.issuedLeases','leases')]:
        result[target]=sum(row['deltas'][source] for row in rows)
    for name in ['proofAndPlan','revalidation','reservation','fullPhase']:
        result[name+'MsPerTick']=sum(row['deltas']['resource.'+name] for row in rows)/result['observedGameTicks']
    cpu_seconds=sum(row['metrics']['cpuSeconds'] for row in rows)
    result['cpuPercentOneCore']=100*cpu_seconds/sum(row['metrics']['cpuObservationSeconds'] for row in rows)
    result['allocatedBytesPerSecond']=sum(row['metrics']['serverAndWorkerAllocatedBytes'] for row in rows)/sum(row['metrics']['allocationObservationSeconds'] for row in rows)
    result['gcCount']=sum(row['metrics']['gcCount'] for row in rows)
    result['gcMillis']=sum(row['metrics']['gcMillis'] for row in rows)
    result['maxBodiesSinceBoot']=max(row['maxSimultaneousOriginalBodiesSinceBoot'] for row in rows)
    return result


def compare(before_root, after_root, output):
    before=export(before_root,before_root/'export.json',True)
    after=export(after_root,after_root/'export.json',True)
    compatible(before['manifest'],after['manifest'])
    index=lambda row:(row['round'],row['layout'],row['workers'],row['count'],row['resources'],row['window'])
    left={index(row):row for row in before['windows']}
    right={index(row):row for row in after['windows']}
    if len(left)!=len(before['windows']) or len(right)!=len(after['windows']) or left.keys()!=right.keys():
        raise ValueError('Incomplete or duplicate build comparison windows')
    for key in left: equivalent(left[key],right[key])
    comparisons=[]
    for identity in sorted({key[:-1] for key in left}):
        a=aggregate([row for key,row in left.items() if key[:-1]==identity])
        b=aggregate([row for key,row in right.items() if key[:-1]==identity])
        ratios={key:b['mspt'][key]/a['mspt'][key] for key in ['mean','p50','p95','p99']}
        comparisons.append(dict(round=identity[0],layout=identity[1],workers=identity[2],count=identity[3],resources=identity[4],
                                before=a,after=b,msptAfterBeforeRatios=ratios,completeStateAndWorkEquivalent=True))
    record=dict(before=before,after=after,comparisons=comparisons,
                note='Same resource setting across pinned builds. Quantiles pool only consecutive windows in one JVM per build/shape. Independent JVM/order repeats are separate records. CPU100%=one logical core; server+worker allocation excludes other JVM/native threads. Default-off/admission unchanged.')
    dump(output,record)
    for row in comparisons:
        print('BUILD_PAIR',row['layout'],row['count'],row['workers'],row['resources'],row['msptAfterBeforeRatios'],flush=True)
    return record


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before',type=Path)
    parser.add_argument('after',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    compare(args.before,args.after,args.output)
