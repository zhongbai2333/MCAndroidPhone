#!/usr/bin/env python3
"""Compare complete immutable Android templates; never edits the input or release packages.

Linux developer tool: Python 3, xz, zstd, GNU time. Compression runs may overlap;
wall times include contention. Decompression/hash measurements run serially.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import time


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, type=Path)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--arch', required=True, choices=('amd64', 'arm64'))
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--jobs', type=int, choices=(1, 2), default=2)
    args = parser.parse_args()
    source = args.image.resolve(strict=True)
    baseline = args.baseline.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {'arch': args.arch, 'inputBytes': source.stat().st_size,
              'inputSha256': digest(source), 'compressionParallelJobs': args.jobs,
              'threadsPerCompressor': 2,
              'timingScope': 'Linux native tools, one serial decode+SHA256 pass; not Mod/Java or Android boot timing',
              'versions': {}, 'results': []}
    for tool in ('xz', 'zstd'):
        report['versions'][tool] = subprocess.check_output([tool, '--version'], text=True).splitlines()[0]
    report_path = output / 'results.json'

    def save():
        temporary = output / 'results.json.partial'
        temporary.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        temporary.replace(report_path)

    def compress(profile):
        name, command, suffix, decoder, compatible = profile
        target = output / (name + suffix)
        partial = target.with_name(target.name + '.partial')
        metrics = output / (name + '-compression.json')
        print('COMPRESS_START', name, flush=True)
        with partial.open('xb') as stream, (output / (name + '-stderr.log')).open('wb') as errors:
            subprocess.run(['/usr/bin/time', '-o', str(metrics), '-f',
                            '{"wallSeconds":%e,"userSeconds":%U,"systemSeconds":%S,"maxRssKiB":%M}',
                            *command, str(source)], stdout=stream, stderr=errors,
                           check=True, timeout=3600)
        partial.replace(target)
        item = {'name': name, 'path': str(target), 'bytes': target.stat().st_size,
                'compression': json.loads(metrics.read_text()),
                'compressCommand': command, 'decoder': decoder,
                'currentModCodecCompatible': compatible}
        print('COMPRESS_DONE', name, item['bytes'], item['compression'], flush=True)
        return item

    bcj = '--x86' if args.arch == 'amd64' else '--arm64'
    context = ',lc=2,lp=2' if args.arch == 'arm64' else ''
    profiles = []
    for dictionary in (48, 128):
        profiles.append((f'xz-extreme-{dictionary}MiB',
                         ['xz', '-T2', '--memlimit-compress=6GiB', '--no-adjust', bcj,
                          f'--lzma2=preset=6e,dict={dictionary}MiB{context}', '-c'],
                         '.xz', ['xz', '-dc'], dictionary == 48))
    for level in (19, 22):
        profiles.append((f'zstd-{level}-long27',
                         ['zstd', '-q', '-T2', '--ultra', f'-{level}', '--long=27', '-c'],
                         '.zst', ['zstd', '-q', '-d', '-c', '--memory=256MB'], False))
    profiles = [profiles[2], profiles[0], profiles[3], profiles[1]]
    report['results'].append({'name': 'existing-xz', 'path': str(baseline),
                              'bytes': baseline.stat().st_size, 'decoder': ['xz', '-dc'],
                              'currentModCodecCompatible': True})
    save()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = [pool.submit(compress, profile) for profile in profiles]
        for future in concurrent.futures.as_completed(futures):
            report['results'].append(future.result())
            save()
    for item in sorted(report['results'], key=lambda value: value['name']):
        metrics = output / (item['name'] + '-decompression.json')
        command = ['/usr/bin/time', '-o', str(metrics), '-f',
                   '{"wallSeconds":%e,"userSeconds":%U,"systemSeconds":%S,"maxRssKiB":%M}',
                   *item['decoder'], item['path']]
        checksum = hashlib.sha256()
        count = 0
        start = time.monotonic()
        with (output / (item['name'] + '-decode-stderr.log')).open('wb') as errors:
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors) as process:
                try:
                    while block := process.stdout.read(1024 * 1024):
                        checksum.update(block)
                        count += len(block)
                        if count > report['inputBytes']:
                            raise ValueError('Decoded output exceeds original size')
                    if process.wait(timeout=30):
                        raise RuntimeError('Decoder failed: ' + item['name'])
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait()
        assert count == report['inputBytes'] and checksum.hexdigest() == report['inputSha256'], item['name']
        item['decompression'] = json.loads(metrics.read_text())
        item['decodeAndHashSeconds'] = round(time.monotonic() - start, 3)
        item['sha256Verified'] = True
        item['savedBytesVsBaseline'] = baseline.stat().st_size - item['bytes']
        save()
        print('VERIFIED', item['name'], 'bytes=', item['bytes'],
              'decodeAndHashSeconds=', item['decodeAndHashSeconds'], flush=True)
    report['complete'] = True
    save()
    print('COMPRESSION_COMPARISON_COMPLETE', report_path, flush=True)


if __name__ == '__main__':
    main()