#!/usr/bin/env python3
"""Correctness tests for the measurement protocol, not elapsed-time speed assertions."""
import unittest
from server_ab import graph, positions, split_compounds, tick_samples, percentile, verify_artifacts
from compare_builds import compatible, equivalent
from analyze_jfr import epoch_ns, duration_ns, analyze
import json
import tempfile
from pathlib import Path


class MeasurementsTest(unittest.TestCase):
    def test_layouts_are_distinct_and_current_region_grid_is_respected(self):
        for count in [2,64,256]:
            for layout, chunks, regions in [('chunk',1,1),('chunks',min(count,9),1),('regions',min(count,8),min(count,8))]:
                p=positions(layout,count)
                self.assertEqual(len(set(p)),count)
                self.assertEqual(len({(x//16,z//16) for x,y,z in p}),chunks)
                self.assertEqual(len({(x//48,z//48) for x,y,z in p}),regions)
                self.assertTrue(all(0<y<319 for x,y,z in p))
                for x,y,z in p:
                    self.assertEqual(x//48,(x-2)//48)
                    self.assertEqual(z//48,(z+2)//48)

    def test_no_notification_neighbour_within_two_blocks(self):
        for layout in ['chunk','chunks','regions']:
            p=positions(layout,256)
            for index,(x,y,z) in enumerate(p):
                self.assertFalse(any(abs(x-a)+abs(y-b)+abs(z-c)<=2 for a,b,c in p[index+1:]))

    def test_completion_notifications_fall_inside_every_steady_window(self):
        # The active inner interval may omit up to~1s at either end.200tick windows that
        # start at phase0 would lose all their completion ticks; phase100centres them.
        for window in [200,400,1200]:
            for warmup in [200,400,1200]:
                completion=[tick for tick in range(1,window+1) if (100+warmup+tick)%200==0]
                self.assertEqual(len(completion),window//200)
                self.assertTrue(all(20<tick<window-20 for tick in completion))

    def test_complete_nbt_not_just_output_count(self):
        text='[{BurnTime: 42s, Items: [{count: 2, Slot: 2b}], RecipesUsed: {"minecraft:a}": 2}}, {Items: []}]'
        self.assertEqual(len(split_compounds(text)),2)
        self.assertIn('RecipesUsed',split_compounds(text)[0])
        for bad in ['[{abc:', '[{a: "unterminated}]', '[{} } ]', '[{}, <...>]']:
            with self.assertRaises(ValueError): split_compounds(bad)
        self.assertEqual(split_compounds('[]'),[])

    def test_ring_wrap_and_unchanged_intermediate_value(self):
        a={'beforeNs':0,'afterNs':10,'ticksNs':list(range(100))}
        b={'beforeNs':100,'afterNs':110,'ticksNs':list(range(100))}
        b['ticksNs'][99]=200
        # Index0 happened to write the same duration as100ticks earlier: retain that interior cell.
        b['ticksNs'][1]=400
        self.assertEqual(tick_samples([a,b],0,110),[.0002,0,.0004])

    def test_boundary_trimming_and_ring_overwrite_fail_closed(self):
        a={'beforeNs':0,'afterNs':10,'ticksNs':list(range(100))}
        b={'beforeNs':100,'afterNs':110,'ticksNs':list(range(100))}
        b['ticksNs'][8]=900
        self.assertEqual(tick_samples([a,b],5,110),[])
        b['afterNs']=4_000_000_001
        with self.assertRaises(ValueError): tick_samples([a,b],0,5_000_000_000)

    def test_jfr_nanoseconds_and_minutes_are_not_float_or_unit_guesses(self):
        self.assertEqual(epoch_ns('1970-01-01T08:00:01.123456789+08:00'),1_123_456_789)
        self.assertEqual(epoch_ns('1970-01-01T00:00:00Z'),0)
        self.assertEqual(duration_ns('PT1M0.001S'),60_001_000_000)
        self.assertEqual(duration_ns('PT0.000000001S'),1)
        with self.assertRaises(ValueError): duration_ns('one millisecond')

    def test_streamed_jfr_events_match_small_json_and_reject_large_expansion(self):
        with tempfile.TemporaryDirectory() as root:
            values={'startTime':'1970-01-01T00:00:01Z','duration':'PT0.020S'}
            event={'type':'jdk.GCPhasePause','values':values}
            path=Path(root)/'events.json'
            stream=Path(root)/'events.ndjson'
            path.write_text(json.dumps({'recording':{'events':[event]}}))
            stream.write_text(json.dumps(event)+'\n')
            interval=[(1_010_000_000,1_015_000_000)]
            self.assertEqual(analyze(path,interval),analyze(stream,interval))
            with path.open('wb') as large:
                large.seek(50_000_001)
                large.write(b'0')
            with self.assertRaises(ValueError): analyze(path,interval)

    def test_jfr_gc_overlap_is_clipped_to_measured_interval(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'events.json'
            path.write_text(json.dumps({'recording':{'events':[{'type':'jdk.GCPhasePause','values':{
                'startTime':'1970-01-01T00:00:01Z','duration':'PT0.020S'}}]}}))
            result=analyze(path,[(1_010_000_000,1_015_000_000)])
            self.assertEqual(result['gcPauseOverlapMs'],5)
            self.assertEqual(analyze(path,[(2_000_000_000,3_000_000_000)])['eventCounts'],{})

    def test_candidate_build_requires_both_exact_hashes_and_a_safe_version(self):
        import hashlib
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as root:
            repo=Path(root)
            args=SimpleNamespace(version='21.1.250.59-beta',installation=repo/'installed',
                                 installer_sha256=hashlib.sha256(b'installer').hexdigest(),
                                 universal_sha256=hashlib.sha256(b'core').hexdigest())
            installer=repo/f'projects/neoforge/build/libs/neoforge-{args.version}-installer.jar'
            core=args.installation/f'libraries/net/neoforged/neoforge/{args.version}/neoforge-{args.version}-universal.jar'
            installer.parent.mkdir(parents=True)
            core.parent.mkdir(parents=True)
            installer.write_bytes(b'installer')
            core.write_bytes(b'core')
            verify_artifacts(args,repo)
            core.write_bytes(b'changed')
            with self.assertRaises(ValueError): verify_artifacts(args,repo)
            core.write_bytes(b'core')
            installer.write_bytes(b'changed')
            with self.assertRaises(ValueError): verify_artifacts(args,repo)
            args.version='../escape'
            with self.assertRaises(ValueError): verify_artifacts(args,repo)

    def test_build_comparison_refuses_workload_state_and_completion_changes(self):
        import copy
        manifest={'jvm':['same'],'java':'jdk21','seed':813584,'parameters':{
            'counts':[64],'layouts':['chunk'],'workers':[4],'rounds':1,'windows':2,
            'warmup_ticks':1200,'window_ticks':600,'conditions':['on'],'jfr':None}}
        compatible(manifest,copy.deepcopy(manifest))
        other=copy.deepcopy(manifest)
        other['parameters']['conditions']=['off']
        with self.assertRaises(ValueError): compatible(manifest,other)
        row=dict(stateBeforeSha256='before',stateAfterSha256='after',ticks=600,outputItems=192,initialCookTime=100,
                 deltas={key:0 for key in ['furnace.nativeCompleted','furnace.changes','furnace.serial','furnace.failed',
                                          'furnace.skipped','furnace.rejectedTasks','resource.issuedLeases','resource.refusedWaves']})
        equivalent(row,copy.deepcopy(row))
        other=copy.deepcopy(row)
        other['stateAfterSha256']='different'
        with self.assertRaises(ValueError): equivalent(row,other)
        other=copy.deepcopy(row)
        other['deltas']['furnace.nativeCompleted']=1
        with self.assertRaises(ValueError): equivalent(row,other)

    def test_mixed_snbt_parser_reads_components_arrays_and_rejects_partial_data(self):
        from mixed_workload import Snbt, contents
        self.assertEqual(contents('Sheep has the following entity data: [I;1,2,3,4]'),[1,2,3,4])
        self.assertEqual(contents('Storage has the following contents: {Age: -12}'),{'Age':-12})
        self.assertEqual(Snbt.parse('{id:"minecraft:potion",components:{"minecraft:potion_contents":{potion:"minecraft:awkward"}},Age:-1000,UUID:[I;1,-2,3,4],Pos:[1.5d,64.0d,0.5d]}'),
                         {'id':'minecraft:potion','components':{'minecraft:potion_contents':{'potion':'minecraft:awkward'}},
                          'Age':-1000,'UUID':[1,-2,3,4],'Pos':[1.5,64.0,.5]})
        for bad in ['{a:1,a:2}', '{a:[1,2,]}', '{a:1}extra', '[{},<...>]', '{a:']:
            with self.assertRaises(ValueError): Snbt.parse(bad)

    def test_mixed_fixture_keeps_inventory_loops_and_notification_receivers_in_the_same_home(self):
        from mixed_workload import specification, build_functions
        for layout in ['chunks','regions']:
            spec=specification(layout)
            self.assertEqual([sum(m['kind']==kind for m in spec['machines']) for kind in ['furnace','blast_furnace','smoker']], [32,16,16])
            self.assertEqual(len(spec['hoppers']),32)
            for i in range(8):
                corners={tuple(v['pos']) for v in spec['hoppers'][i*4:i*4+4]}
                self.assertEqual(len(corners),4)
                self.assertEqual(len({(x//48,z//48) for x,y,z in corners}),1)
            functions=build_functions(spec)
            self.assertTrue(any('minecraft:hopper' in cmd for cmd in functions['setup']))
            self.assertTrue(any('redstone_lamp[lit=false]' in cmd for cmd in functions['active_tick']))
            self.assertEqual(sum('Age:-100000' in cmd for cmd in functions['setup']),32)

    def test_mixed_correctness_oracle_rejects_missing_world_entity_and_conservation_effects(self):
        import copy
        from mixed_workload import specification, validate_window
        spec=specification('chunks')
        machines=[]
        for m in spec['machines']:
            x,y,z=m['pos']
            machines.append(dict(id='minecraft:'+m['kind'],x=x,y=y,z=z,BurnTime=30000,Items=[
                {'Slot':0,'id':'minecraft:'+m['input'],'count':64},{'Slot':1,'id':'minecraft:coal','count':64}]))
        before=dict(machines=machines,hoppers=[{'Items':[{'Slot':0,'count':64}]} if i%4==0 else {} for i in range(32)],
                    brewing=[{'Items':[{'Slot':j,'count':1} for j in range(3)]} for _ in range(8)],
                    chests=[{'Items':[{'Slot':0,'count':64}]} for _ in range(8)],
                    sheep=[{'Age':-100000} for _ in range(32)],entityCount=32,ticks=0,pulses=0,redstone=0)
        after=copy.deepcopy(before)
        for m,be in zip(spec['machines'],after['machines']):
            be['BurnTime']-=200
            be['Items'][0]['count']-=1
            be['Items'].append({'Slot':2,'id':'minecraft:'+m['output'],'count':1})
        for sheep in after['sheep']:sheep['Age']+=200
        after.update(ticks=200,pulses=10,redstone=160)
        self.assertEqual(validate_window(before,after,200,spec),64)
        for kind in ['redstone','sheep','hoppers','machines']:
            bad=copy.deepcopy(after)
            if kind=='redstone':bad['redstone']-=1
            elif kind=='sheep':bad['sheep'][0]['Age']-=1
            elif kind=='hoppers':bad['hoppers'][0]['Items'][0]['count']+=1
            else:bad['machines'][0]['Items'][0]['count']+=1
            with self.assertRaises(AssertionError):validate_window(before,bad,200,spec)

    def test_mixed_export_refuses_partial_processes_and_state_or_route_mismatches(self):
        import copy
        from export_mixed import load, equivalent
        manifest={'parameters':{'rounds':1,'layouts':['chunks'],'conditions':['off','on'],'windows':2,'mode':'steady'}}
        with tempfile.TemporaryDirectory() as name:
            root=Path(name)
            (root/'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaises(AssertionError):load(root)
            directory=root/'r0-chunks-w4-off'
            directory.mkdir()
            (directory/'result.json').write_text(json.dumps({'outcome':'RUNNING','serverExit':0,'results':[]}))
            with self.assertRaises(ValueError):load(root)
        row=dict(window=0,ticks=600,outputs=192,fixtureSha256='fixture',beforeStateSha256='before',afterStateSha256='after',
                 deltas={k:0 for k in ['furnace.nativeCompleted','furnace.serial','furnace.changes','furnace.failed','furnace.skipped','furnace.rejectedTasks']})
        equivalent(row,copy.deepcopy(row))
        for key in ['beforeStateSha256','afterStateSha256','fixtureSha256']:
            other=copy.deepcopy(row)
            other[key]='changed'
            with self.assertRaises(AssertionError):equivalent(row,other)
        other=copy.deepcopy(row)
        other['deltas']['furnace.serial']=1
        with self.assertRaises(AssertionError):equivalent(row,other)

    def test_no_missing_diagnostics_becomes_zero(self):
        with self.assertRaises(ValueError): graph('Block entity resource ownership: enabled=false')
        with self.assertRaises(ValueError): percentile([], .99)
        self.assertEqual(percentile([1,2,3,4],.95),4)


if __name__=='__main__': unittest.main()
