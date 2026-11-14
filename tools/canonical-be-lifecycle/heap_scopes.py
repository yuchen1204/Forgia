#!/usr/bin/env python3
"""Read only the reused Native scope fields from a local HotSpot HPROF, without dumping world strings."""
import json
from pathlib import Path
import struct
import sys

TARGET = 'net/neoforged/neoforge/server/threading/region/RegionBlockEntityTickScope'


def read_scopes(path):
    strings, loaded, classes, instances = {}, {}, {}, []
    with Path(path).open('rb') as stream:
        header = bytearray()
        while True:
            value = stream.read(1)
            if not value:
                raise ValueError('Incomplete HPROF header')
            if value == b'\0':
                break
            header.extend(value)
        if not header.startswith(b'JAVA PROFILE 1.0.'):
            raise ValueError('Unexpected heap format')
        ids = int.from_bytes(stream.read(4), 'big')
        if ids not in (4, 8):
            raise ValueError('Unsupported object ID size')
        stream.read(8)
        sizes = {2: ids, 4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
        while True:
            record = stream.read(9)
            if not record:
                break
            if len(record) != 9:
                raise ValueError('Incomplete HPROF record')
            tag, _, length = struct.unpack('>BII', record)
            if tag not in (1, 2, 0x0c, 0x1c):
                stream.seek(length, 1)
                continue
            data = memoryview(stream.read(length))
            if len(data) != length:
                raise ValueError('Truncated HPROF payload')
            def number(at, size=ids):
                return int.from_bytes(data[at:at+size], 'big')
            if tag == 1:
                strings[number(0)] = bytes(data[ids:]).decode('utf-8', errors='replace')
                continue
            if tag == 2:
                loaded[number(4)] = number(8+ids)
                continue
            at = 0
            while at < length:
                kind = data[at]
                at += 1
                if kind == 0x20:
                    cls = number(at)
                    at += ids+4
                    parent = number(at)
                    at += 6*ids+4
                    constants = number(at, 2)
                    at += 2
                    for _ in range(constants):
                        dtype = data[at+2]
                        at += 3+sizes[dtype]
                    statics = number(at, 2)
                    at += 2
                    for _ in range(statics):
                        dtype = data[at+ids]
                        at += ids+1+sizes[dtype]
                    count = number(at, 2)
                    at += 2
                    fields = []
                    for _ in range(count):
                        fields.append((number(at), data[at+ids]))
                        at += ids+1
                    classes[cls] = (parent, fields)
                elif kind == 0x21:
                    obj = number(at)
                    at += ids+4
                    cls = number(at)
                    count = number(at+ids, 4)
                    at += ids+4
                    name = strings.get(loaded.get(cls), '').replace('.', '/')
                    if name == TARGET:
                        instances.append((obj, cls, bytes(data[at:at+count])))
                    at += count
                elif kind == 0x22:
                    at += ids+4
                    count = number(at, 4)
                    at += 4+ids+count*ids
                elif kind == 0x23:
                    at += ids+4
                    count = number(at, 4)
                    dtype = data[at+4]
                    at += 5+count*sizes[dtype]
                elif kind in (0xff, 0x05, 0x07, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x90):
                    at += ids
                elif kind == 0x01:
                    at += 2*ids
                elif kind in (0x02, 0x03, 0x08, 0x8e):
                    at += ids+8
                elif kind in (0x04, 0x06):
                    at += ids+4
                elif kind == 0xfe:
                    at += 4+ids
                else:
                    raise ValueError(f'Unsupported heap subrecord0x{kind:02x}; refuse partial leak claims')
                if at > length:
                    raise ValueError('Heap subrecord length exceeded')
    result = []
    for obj, cls, payload in instances:
        fields, seen = [], set()
        while cls:
            if cls in seen or cls not in classes:
                raise ValueError('Missing or cyclic class field layout')
            seen.add(cls)
            parent, declared = classes[cls]
            fields += declared
            cls = parent
        at, values = 0, {}
        for name_id, dtype in fields:
            value = int.from_bytes(payload[at:at+sizes[dtype]], 'big')
            at += sizes[dtype]
            values[strings[name_id]] = bool(value) if dtype == 4 else None if dtype == 2 and value == 0 else value
        if at != len(payload) or set(values) != {'active', 'admission', 'region', 'previous'}:
            raise ValueError('Unexpected scope instance layout')
        if values != {'active': False, 'admission': None, 'region': None, 'previous': None}:
            raise AssertionError('Reused Native scope retained authority or world references: '+str(values))
        result.append({'objectId': hex(obj), **values})
    return result


if __name__ == '__main__':
    print(json.dumps(read_scopes(sys.argv[1]), indent=2))
