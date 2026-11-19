#!/usr/bin/env python3
"""Fail the build when the native AAR or shipped pinned GeoData has drifted."""
from pathlib import Path
import hashlib
import json
import struct
import zipfile

ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def verify():
    lock=json.loads((ROOT/'Config/Engine.lock.json').read_text())
    artifact=json.loads((ROOT/'Config/EngineArtifact.json').read_text())
    aar=ROOT/'app/libs/libXray.aar'
    if not aar.is_file():raise ValueError('Build the pinned engine with tools/build_engine.py first')
    assert sha(aar)==artifact['sha256'],'Native AAR hash mismatch'
    assert artifact['libXray']==lock['libXray']['commit']
    assert artifact['xrayCore']==lock['xrayCore']['commit']
    assert artifact['goVersion']==lock['goVersion']
    assert artifact['gomobileVersion']==lock['android']['gomobileVersion']
    assert artifact['ndkVersion']==lock['android']['ndkVersion'],'Native AAR NDK version differs from the build lock'
    assert artifact['ndkVersion']==lock['android']['ndkVersion']
    architectures={}
    with zipfile.ZipFile(aar) as archive:
        for name in archive.namelist():
            if not name.endswith('.so'):continue
            data=archive.read(name);assert data[:4]==b'\x7fELF'
            is64=data[4]==2;assert data[5]==1
            offset=struct.unpack_from('<Q' if is64 else '<I',data,32 if is64 else 28)[0]
            size,count=struct.unpack_from('<HH',data,54 if is64 else 42)
            alignments=[]
            for i in range(count):
                start=offset+i*size
                if struct.unpack_from('<I',data,start)[0]==1:
                    alignment=struct.unpack_from('<Q' if is64 else '<I',data,start+(48 if is64 else 28))[0]
                    assert alignment>=16384,'Native library requires 16 KiB LOAD alignment'
                    alignments.append(alignment)
            assert alignments
            architectures[name.split('/')[1]]=alignments
    assert sorted(architectures)==artifact['abis']
    for entry in json.loads((ROOT/'Config/GeoData.lock.json').read_text()):
        assert sha(ROOT/f'app/src/main/assets/geo/{entry["name"]}.dat')==entry['sha256'],'GeoData drifted'
        assert sha(ROOT/f'ThirdParty/{entry["name"]}-LICENSE.txt')==entry['licenseSHA256']
    assert sha(ROOT/'ThirdParty/libXray-LICENSE.txt')==lock['libXray']['licenseSHA256']
    assert sha(ROOT/'ThirdParty/Xray-core-LICENSE.txt')==lock['xrayCore']['licenseSHA256']
    return {'engineArtifactVerified':True,'abis':sorted(architectures),'nativePageAlignment':16384,'geoDataVerified':True}

if __name__=='__main__':print(json.dumps(verify(),indent=2))
