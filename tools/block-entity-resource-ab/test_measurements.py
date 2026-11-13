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

    def test_no_missing_diagnostics_becomes_zero(self):
        with self.assertRaises(ValueError): graph('Block entity resource ownership: enabled=false')
        with self.assertRaises(ValueError): percentile([], .99)
        self.assertEqual(percentile([1,2,3,4],.95),4)


if __name__=='__main__': unittest.main()
