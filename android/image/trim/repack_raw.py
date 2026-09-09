#!/usr/bin/env python3
"""Developer-only, offline writeback to a new GPT raw disk; never changes LP/GPT metadata."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def digest(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    for flag in ('source', 'layout', 'parts', 'output'):
        ap.add_argument('--' + flag, type=Path, required=True)
    a = ap.parse_args()
    layout = json.loads(a.layout.read_text())
    report = json.loads((a.parts / 'report.json').read_text())
    if a.source.stat().st_size != layout['rawBytes'] or digest(a.source) != layout['rawSha256']:
        raise ValueError('Unexpected source disk')
    if set(layout['partitions']) != set(report['partitions']):
        raise ValueError('Incomplete partition build')
    ranges = []
    for part, extents in layout['partitions'].items():
        p = a.parts / (part + '.img')
        if p.stat().st_size != sum(e['bytes'] for e in extents):
            raise ValueError('Partition size mismatch: ' + part)
        if digest(p) != report['partitions'][part]['sha256']:
            raise ValueError('Partition hash mismatch: ' + part)
        for e in extents:
            start, end = e['offset'], e['offset'] + e['bytes']
            if start < 1048576 or end > layout['superBytes'] or e['bytes'] <= 0 or start % 512 or end % 512:
                raise ValueError('Invalid extent')
            ranges.append((start, end))
    ranges.sort()
    if any(a1 > b0 for (a0, a1), (b0, b1) in zip(ranges, ranges[1:])):
        raise ValueError('Overlapping extents')
    if layout['superOffset'] + layout['superBytes'] > layout['rawBytes']:
        raise ValueError('Invalid super range')
    with a.source.open('rb') as src, a.output.open('xb') as dst:
        shutil.copyfileobj(src, dst, 8 * 1024 * 1024)
        for part, extents in layout['partitions'].items():
            with (a.parts / (part + '.img')).open('rb') as fs:
                for e in extents:
                    dst.seek(layout['superOffset'] + e['offset'])
                    left = e['bytes']
                    while left:
                        block = fs.read(min(left, 8 * 1024 * 1024))
                        if not block:
                            raise IOError('Unexpected end of partition')
                        dst.write(block)
                        left -= len(block)
    # Exact comparison permits differences only in the declared logical extents.
    absolute = [(layout['superOffset'] + lo, layout['superOffset'] + hi) for lo, hi in ranges]
    cursor = 0
    with a.source.open('rb') as src, a.output.open('rb') as dst:
        for lo, hi in absolute + [(layout['rawBytes'], layout['rawBytes'])]:
            src.seek(cursor); dst.seek(cursor)
            left = lo - cursor
            while left:
                n = min(left, 8 * 1024 * 1024)
                if src.read(n) != dst.read(n):
                    raise IOError('Changed data outside logical partitions')
                left -= n
            cursor = hi
    if digest(a.source) != layout['rawSha256']:
        raise RuntimeError('Source changed')
    print('REPACK_OK', a.output, digest(a.output), flush=True)


if __name__ == '__main__':
    main()
