#!/usr/bin/env python3
"""Summarise JFR evidence within the exact recorded active windows; never infer unobserved causes."""
import argparse
import collections
import datetime
import json
from pathlib import Path
import re
from server_ab import dump


def epoch_ns(text):
    # Fractional seconds may have9digits; float timestamp would discard useful event precision.
    match=re.fullmatch(r'(.+?)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)',text)
    if not match: raise ValueError(text)
    whole=datetime.datetime.fromisoformat(match[1]+match[3].replace('Z','+00:00'))
    return int(whole.timestamp())*1_000_000_000+int((match[2] or '0').ljust(9,'0'))


def duration_ns(text):
    match=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d+))?S)?',text)
    if not match: raise ValueError(text)
    h,m,s,f=match.groups()
    return ((int(h or 0)*60+int(m or 0))*60+int(s or 0))*1_000_000_000+int((f or '0').ljust(9,'0'))


def frames(value):
    return [frame['method']['type']['name'].replace('/','.')+'.'+frame['method']['name']
            for frame in (value.get('stackTrace') or {}).get('frames',[])]


def category(stack):
    families=[('FurnaceWorkPreparation','catalog/current furnace audit'),('FurnaceResourcePreparation','resource claims capture'),
              ('BlockEntityWorkPlanner','dependency planner'),('BlockEntityResourceOwnership','resource identity/reservation/scope'),
              ('AbstractFurnaceBlockEntity','original furnace body'),('RegionWorkStealingDispatcher','work dispatcher'),
              ('RegionThreadingEngine','other region engine'),('RMI','JMX/RMI'),('RMIConnection','JMX/RMI')]
    for frame in stack:
        for family,label in families:
            if family in frame: return label
    return 'other JVM/server code'


def ownership_stage(stack):
    """Attribute sampled callers, not assumed durations. Fresh and plan captures have different lifetimes."""
    def has(name):
        return any(name in frame for frame in stack)
    fresh = has('RegionThreadingEngine.executeFurnaceWave')
    if has('FurnaceWorkPreparation.pureCatalog') or has('FurnaceWorkPreparation.plainIngredient') or has('FurnaceWorkPreparation.captureIngredientReads'):
        return 'fresh catalog audit/publication' if fresh else 'initial catalog audit/publication'
    if has('FurnaceResourcePreparation.capture') or has('FurnaceResourcePreparation.recapture'):
        return 'fresh local claims capture' if fresh else 'initial local claims capture'
    if has('BlockEntityResourceOwnership.plan'):
        return 'whole-phase dependency and alias planning'
    if has('BlockEntityResourceOwnership.tryAcquireAll'):
        return 'fresh whole-wave reservation'
    if has('BlockEntityResourceOwnership$Claims.sameResources'):
        return 'fresh captured-set comparison'
    if has('FurnaceWorkPreparation.aliased'):
        return 'fresh whole-phase inventory alias audit' if fresh else 'initial whole-phase inventory alias audit'
    if has('FurnaceWorkPreparation.prepare'):
        return 'fresh local eligibility audit' if fresh else 'initial local eligibility audit'
    if has('RegionThreadingEngine.tickFurnaceSerial'):
        return 'canonical serial BE execution'
    if has('RegionThreadingEngine.runFurnaceWorkBody'):
        return 'worker body and scopes'
    return 'other server/JVM work'


def read_events(path):
    if path.suffix == '.ndjson':
        with path.open() as stream:
            for line in stream:
                yield json.loads(line)
    else:
        # Small diagnostic fixtures remain compatible; never expand/read a large JFR print as one JSON.
        if path.stat().st_size > 50_000_000:
            raise ValueError('Large JSON refused; use streaming JfrEvents on the binary recording')
        yield from json.loads(path.read_text())['recording']['events']


def analyze(path, intervals):
    events=read_events(path)
    counts=collections.Counter()
    samples=collections.Counter()
    samples_by_thread=collections.Counter()
    allocation=collections.Counter()
    allocation_by_thread=collections.Counter()
    allocation_classes=collections.Counter()
    stage_samples=collections.Counter()
    stage_allocations=collections.Counter()
    stage_classes=collections.Counter()
    stage_leaves=collections.Counter()
    tops=collections.Counter()
    native_tops=collections.Counter()
    locks=collections.Counter()
    parks=collections.Counter()
    compilations=[]
    gc=[]
    deopts=[]
    total_pause=0
    for event in events:
        value=event['values'];start=epoch_ns(value['startTime']);duration=duration_ns(value.get('duration','PT0S'))
        overlap=sum(max(0,min(end,start+duration)-max(begin,start)) for begin,end in intervals)
        in_window=any(begin<=start<end for begin,end in intervals)
        if not in_window and not overlap: continue
        kind=event['type'];counts[kind]+=1
        stack=frames(value)
        caller=value.get('normalizedCategory',category(stack))
        leaf=value.get('normalizedLeaf',stack[0] if stack else '')
        thread=value.get('eventThread') or value.get('sampledThread') or {}
        thread_name=thread.get('javaName','unknown')
        stage=ownership_stage(value.get('normalizedAttributionStack',stack))
        if kind=='jdk.ExecutionSample':
            stage_samples[(thread_name,stage)]+=1
            stage_leaves[(thread_name,stage,leaf)]+=1
            samples[caller]+=1
            samples_by_thread[(thread_name,caller)]+=1
            if leaf: tops[leaf]+=1
        elif kind=='jdk.NativeMethodSample':
            if leaf: native_tops[(thread_name,leaf)]+=1
        elif kind=='jdk.ObjectAllocationSample':
            allocation[caller]+=value['weight']
            allocation_by_thread[(thread_name,caller)]+=value['weight']
            klass=value.get('objectClass') or {}
            class_name=klass.get('name','unknown').replace('/','.')
            allocation_classes[class_name]+=value['weight']
            stage_allocations[(thread_name,stage)]+=value['weight']
            stage_classes[(thread_name,stage,class_name)]+=value['weight']
        elif kind=='jdk.JavaMonitorEnter':
            locks[thread_name]+=overlap
        elif kind=='jdk.ThreadPark':
            if 'normalizedParkLabel' in value: label=value['normalizedParkLabel']
            elif any('RegionWorkerPool' in frame for frame in stack): label='worker pool idle/dispatch park'
            elif any('RegionTickBarrier' in frame or 'managedBlock' in frame for frame in stack): label='server/worker barrier or managed block'
            else: label='other/20TPS limiter/JMX'
            parks[(thread_name,label)]+=overlap
        elif kind=='jdk.Compilation':
            method=value['method']
            compilations.append(dict(method=method['type']['name'].replace('/','.')+'.'+method['name'],
                durationNs=duration,overlapNs=overlap,level=value.get('compileLevel'),osr=value.get('isOsr')))
        elif kind=='jdk.GCPhasePause':
            total_pause+=overlap
            gc.append(dict(startEpochNs=start,durationNs=duration,overlapNs=overlap))
        elif kind=='jdk.Deoptimization':
            method=value['method']
            deopts.append(dict(method=method['type']['name'].replace('/','.')+'.'+method['name'],
                reason=value.get('reason'),action=value.get('action'),thread=thread_name))
    return dict(eventCounts=dict(counts),
        executionSamplesByStage=[dict(thread=t,stage=s,samples=n) for (t,s),n in stage_samples.items()],
        sampledAllocationWeightByStage=[dict(thread=t,stage=s,bytesWeight=n) for (t,s),n in stage_allocations.items()],
        sampledAllocationClassesByStage=[dict(thread=t,stage=s,className=c,bytesWeight=n) for (t,s,c),n in stage_classes.most_common()],
        sampledLeafMethodsByStage=[dict(thread=t,stage=s,leaf=l,samples=n) for (t,s,l),n in stage_leaves.most_common()],
        executionSamplesByNearestAuditedCaller=dict(samples),
        executionSamplesByThreadAndCaller=[dict(thread=t,caller=label,samples=n) for (t,label),n in samples_by_thread.items()],
        sampledAllocationWeightByNearestAuditedCaller=dict(allocation),
        sampledAllocationWeightByThreadAndCaller=[dict(thread=t,caller=label,bytesWeight=n) for (t,label),n in allocation_by_thread.items()],
        topSampledAllocationClasses=dict(allocation_classes.most_common(25)),
        topLeafSamples=dict(tops.most_common(25)),
        nativeLeafSamplesByThread=[dict(thread=t,leaf=leaf,samples=n) for (t,leaf),n in native_tops.most_common(30)],
        gcPauseOverlapMs=total_pause/1e6,gcPauses=gc,
        monitorEnterOverlapMsByThread={key:v/1e6 for key,v in locks.items()},
        parkOverlapMs=[dict(thread=t,label=l,millis=v/1e6) for (t,l),v in parks.items()],
        compilations=compilations,deoptimizations=deopts,
        limits=['JFR profile is a diagnostic workload, excluded from primary unprofiled A/B',
                'Sample counts/weighted allocation are not exact CPU durations or bytes',
                'Native sample stacks are recorded separately; these include JVM idle/park/syscalls and are not added to Java execution sample percentages',
                'Park time may include normal20TPS idle time, worker idle and managed joins; it is not evidence of resource-mutex contention',
                'Monitor threshold1ms; absence does not exclude shorter locks or native VM waits',
                'Native ring sample timing is bracketed by100ms polls, not exact JFR timestamped per-tick events; no unsupported pause-to-p99 causal attribution'])


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('events',type=Path)
    parser.add_argument('receipt',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    receipt=json.loads(args.receipt.read_text())
    intervals=[(r['activeInnerStartEpochNs'],r['activeInnerEndEpochNs']) for r in receipt['results']]
    dump(args.output,analyze(args.events,intervals))
