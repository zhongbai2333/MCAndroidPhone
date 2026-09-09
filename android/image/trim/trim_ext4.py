#!/usr/bin/env python3
"""Build-time only: trim extracted ext4 COPIES using an explicit, hash-pinned plan.
Does not mount images, execute guest code, or require Python on users' computers.
Requires e2fsprogs debugfs/e2fsck/e2image built by the developer.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def digest(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def run(cmd):
    p = subprocess.run([str(x) for x in cmd], capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(f'{cmd}: {p.stdout}\n{p.stderr}')
    return p.stdout


def inventory(dbg, image):
    result, todo = [], ['/']
    while todo:
        parent = todo.pop()
        for line in run([dbg, '-R', 'ls -p ' + parent, image]).splitlines():
            s = line.split('/')
            if len(s) < 8 or not s[1].isdigit() or s[5] in ('.', '..', ''):
                continue
            name = parent.rstrip('/') + '/' + s[5]
            if not re.fullmatch(r'/[A-Za-z0-9_.,+/@=:\[\]-]+', name):
                raise ValueError(f'Unsupported ext4 name: {name}')
            mode = int(s[2], 8)
            directory = mode & 0o170000 == 0o040000
            result.append(dict(path=name, inode=int(s[1]), mode=mode,
                               bytes=int(s[6] or 0), directory=directory))
            if directory:
                todo.append(name)
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--parts', type=Path, required=True)
    ap.add_argument('--plan', type=Path, required=True)
    ap.add_argument('--e2build', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--compact-only', action='store_true')
    a = ap.parse_args()
    plan = json.loads(a.plan.read_text())
    if plan.get('validationStatus', '').startswith('REJECTED'):
        raise ValueError('Refusing a rejected experimental plan: ' + plan['validationStatus'])
    dbg, fsck, image = (a.e2build / p for p in ['debugfs/debugfs', 'e2fsck/e2fsck', 'misc/e2image'])
    # All originals are validated before any output is created.
    for part, expected in plan['partitions'].items():
        if not re.fullmatch(r'[a-z_]+', part):
            raise ValueError(part)
        if digest(a.parts / (part + '.img')) != expected:
            raise ValueError('Source hash mismatch: ' + part)
    a.output.mkdir(parents=True, exist_ok=False)
    report = dict(compactOnly=a.compact_only, partitions={})
    for part in plan['partitions']:
        src, dst = a.parts / (part + '.img'), a.output / (part + '.img')
        check = run([fsck, '-fn', src])
        before = inventory(dbg, src)
        rules = [] if a.compact_only else plan['remove'].get(part, [])
        paths = {r['path'] for r in before}
        for rule in rules:
            if rule not in paths or rule == '/':
                raise ValueError('Missing/unsafe removal: ' + rule)
        selected = [r for r in before if any(r['path'] == p or r['path'].startswith(p + '/') for p in rules)]
        # e2image -ra copies ALL allocated data/metadata, zeroing free space.
        run([image, '-ra', src, dst])
        dst_original_size = dst.stat().st_size
        commands = [('rmdir ' if r['directory'] else 'rm ') + r['path']
                    for r in sorted(selected, key=lambda r: r['path'].count('/'), reverse=True)]
        if commands:
            script = a.output / (part + '-remove.txt')
            script.write_text('\n'.join(commands) + '\n')
            log = run([dbg, '-w', '-f', script, dst])
            (a.output / (part + '-debugfs.log')).write_text(log)
            after = inventory(dbg, dst)
            expected_paths = paths - {r['path'] for r in selected}
            if {r['path'] for r in after} != expected_paths:
                raise RuntimeError('Unexpected directory changes: ' + part)
            check += run([fsck, '-fn', dst])
            compact = a.output / (part + '.compact')
            run([image, '-ra', dst, compact])
            compact.replace(dst)
        # Preserve logical partition length. FS data ends before optional padding.
        if dst.stat().st_size > src.stat().st_size or dst.stat().st_size != dst_original_size:
            raise RuntimeError('Unexpected filesystem length: ' + part)
        with dst.open('r+b') as f:
            f.truncate(src.stat().st_size)
        check += run([fsck, '-fn', dst])
        (a.output / (part + '-fsck.log')).write_text(check)
        report['partitions'][part] = dict(sha256=digest(dst), removed=selected,
                                        removedFileBytes=sum(r['bytes'] for r in selected if not r['directory']))
        (a.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(part, 'removed bytes', report['partitions'][part]['removedFileBytes'], flush=True)
    # Detect accidental source mutation as well as input drift.
    for part, expected in plan['partitions'].items():
        if digest(a.parts / (part + '.img')) != expected:
            raise RuntimeError('Source changed during trimming: ' + part)


if __name__ == '__main__':
    main()
