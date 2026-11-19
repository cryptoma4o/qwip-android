#!/usr/bin/env python3
"""Build the pinned public libXray source with its official Android builder.

The generated AAR stays local. Source/module hashes are checked before and after
the upstream builder, which temporarily edits and restores its module files.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--go-bin', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--module-cache', type=Path)
    parser.add_argument('--geo-dir', type=Path)
    args = parser.parse_args()
    lock = json.loads((ROOT/'Config/Engine.lock.json').read_text())
    lib = lock['libXray']
    support = ROOT/'Support'
    support.mkdir(exist_ok=True)
    archive = support/f"libXray-{lib['commit']}.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(f"https://codeload.github.com/XTLS/libXray/tar.gz/{lib['commit']}", timeout=30) as response:
            archive.write_bytes(response.read(2 * 1024 * 1024))
    if digest(archive) != lib['sourceArchiveSHA256']:
        raise ValueError('libXray source archive hash mismatch')
    vendor = ROOT/'VendorSources'
    vendor.mkdir(exist_ok=True)
    source = vendor/f"libXray-{lib['commit']}"
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(vendor, filter='data')
    original_files = set()
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            if not member.isfile(): continue
            relative = Path(member.name).relative_to(source.name)
            original_files.add(relative)
            file = source/relative
            if file.is_symlink() or not file.is_file() or hashlib.sha256(tar.extractfile(member).read()).hexdigest() != digest(file):
                raise ValueError('Vendor source changed: '+str(relative))
    for file in source.rglob('*'):
        if file.is_file() and file.suffix in {'.go','.s','.c','.h','.py'} and file.relative_to(source) not in original_files:
            raise ValueError('Unexpected vendor build source: '+str(file.relative_to(source)))
    for name, field in [('go.mod','goModSHA256'), ('go.sum','goSumSHA256')]:
        if digest(source/name) != lib[field]:
            raise ValueError(f'{name} hash mismatch')
    assets = ROOT/'app/src/main/assets'
    (assets/'geo').mkdir(parents=True, exist_ok=True)
    (source/'dat').mkdir(exist_ok=True)
    for geo in json.loads((ROOT/'Config/GeoData.lock.json').read_text()):
        dest = assets/'geo'/f"{geo['name']}.dat"
        cached = args.geo_dir/f"{geo['name']}.dat" if args.geo_dir else None
        if not dest.exists():
            if cached and cached.exists():
                shutil.copyfile(cached, dest)
            else:
                with urllib.request.urlopen(geo['downloadURL'], timeout=60) as response:
                    dest.write_bytes(response.read(64 * 1024 * 1024))
        if digest(dest) != geo['sha256']:
            raise ValueError('GeoData hash mismatch')
        shutil.copyfile(dest, source/'dat'/dest.name)
    cache = support/'GoCache'
    env = os.environ.copy()
    env.update({
        'GOTOOLCHAIN':'local', 'GOWORK':'off', 'GOSUMDB':'sum.golang.org',
        'GOPATH':str(cache/'work'), 'GOCACHE':str(cache/'build'),
        'GOMODCACHE':str(args.module_cache or cache/'modules'),
        'ANDROID_HOME':str(args.sdk), 'ANDROID_SDK_ROOT':str(args.sdk),
        'ANDROID_NDK_HOME':str(args.sdk/'ndk'/lock['android']['ndkVersion']),
        'LIBXRAY_GOMOBILE_VERSION':lock['android']['gomobileVersion'],
    })
    env['PATH'] = str(args.go_bin) + os.pathsep + str(cache/'work/bin') + os.pathsep + env['PATH']
    version = subprocess.check_output([str(args.go_bin/'go'),'version'], env=env, text=True)
    if 'go'+lock['goVersion']+' ' not in version:
        raise ValueError('Go toolchain does not match lock')
    print(version.strip(), flush=True)
    try:
        subprocess.run([os.sys.executable,'build/main.py','android'], cwd=source, env=env, check=True)
    finally:
        for name, field in [('go.mod','goModSHA256'), ('go.sum','goSumSHA256')]:
            if digest(source/name) != lib[field]:
                raise ValueError(f'{name} was not restored')
    dest = ROOT/'app/libs/libXray.aar'
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source/'libXray.aar', dest)
    with zipfile.ZipFile(dest) as aar:
        abis = sorted({p.split('/')[1] for p in aar.namelist() if p.startswith('jni/') and p.endswith('.so')})
    if abis != ['arm64-v8a','armeabi-v7a','x86','x86_64']:
        raise ValueError('Expected all four Android ABIs')
    artifact = {'schema':1, 'libXray':lib['commit'], 'xrayCore':lock['xrayCore']['commit'],
                'goVersion':lock['goVersion'], 'gomobileVersion':lock['android']['gomobileVersion'],
                'ndkVersion':lock['android']['ndkVersion'], 'sha256':digest(dest),
                'bytes':dest.stat().st_size, 'abis':abis, 'pageSize':16384,
                'moduleFilesRestored':True}
    (ROOT/'Config/EngineArtifact.json').write_text(json.dumps(artifact,indent=2)+'\n')
    print(json.dumps(artifact,indent=2))

if __name__ == '__main__':
    main()
