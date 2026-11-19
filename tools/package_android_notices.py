#!/usr/bin/env python3
"""Collect POM licenses and shipped notices from the resolved runtime artifacts."""
from pathlib import Path
import hashlib
import io
import json
import urllib.request
import xml.etree.ElementTree as E
import zipfile

ROOT=Path(__file__).resolve().parents[1]

def main():
    inventory=ROOT/'.artifacts/runtime-components.tsv'
    cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
    output=ROOT/'ThirdParty/AndroidPoms';output.mkdir(parents=True,exist_ok=True)
    records=[];notices=[];seen=set()
    ns={'m':'http://maven.apache.org/POM/4.0.0'}
    def read_pom(group,name,version):
        candidates=list((cache/group/name/version).glob('**/*.pom'))
        if candidates: data=candidates[0].read_bytes()
        else:
            base='https://dl.google.com/dl/android/maven2/' if group.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/'
            with urllib.request.urlopen(base+group.replace('.','/')+f'/{name}/{version}/{name}-{version}.pom',timeout=20) as response:data=response.read(1024*1024)
        filename=f'{group}-{name}-{version}.pom';(output/filename).write_bytes(data)
        return data,f'AndroidPoms/{filename}'
    def licenses_from(pom,depth=0):
        licenses=[{'name':node.findtext('m:name',default='',namespaces=ns),'url':node.findtext('m:url',default='',namespaces=ns)} for node in pom.findall('m:licenses/m:license',ns)]
        if licenses:return licenses,[]
        parent=pom.find('m:parent',ns)
        if parent is None or depth>=4:return [],[]
        fields=[parent.findtext('m:'+key,default='',namespaces=ns) for key in ('groupId','artifactId','version')]
        if any(not value or '/' in value or '$' in value for value in fields):return [],[]
        raw,path=read_pom(*fields);inherited,ancestors=licenses_from(E.fromstring(raw),depth+1)
        return inherited,[{'pom':path,'sha256':hashlib.sha256(raw).hexdigest()}]+ancestors
    for line in inventory.read_text().splitlines():
        group,name,version,path=line.split('\t')
        coordinate=f'{group}:{name}:{version}'
        if coordinate in seen:continue
        seen.add(coordinate)
        data,pom_path=read_pom(group,name,version)
        licenses,parents=licenses_from(E.fromstring(data))
        if not licenses: raise ValueError('Missing runtime component license: '+coordinate)
        record={'component':coordinate,'pom':pom_path,'pomSHA256':hashlib.sha256(data).hexdigest(),'inheritedFrom':parents,'licenses':licenses,'notices':[]}
        artifact=Path(path)
        def collect(z,prefix=''):
            for entry in z.namelist():
                upper=entry.upper();base=upper.rsplit('/',1)[-1]
                if any(base.startswith(word) for word in ('LICENSE','NOTICE','COPYING','COPYRIGHT')) and not entry.endswith('/'):
                    raw=z.read(entry)
                    if len(raw)<1024*1024:
                        record['notices'].append({'file':prefix+entry,'sha256':hashlib.sha256(raw).hexdigest()})
                        notices.append(coordinate+' — '+prefix+entry+'\n'+raw.decode('utf-8',errors='replace'))
        if zipfile.is_zipfile(artifact):
            with zipfile.ZipFile(artifact) as z:
                collect(z)
                if 'classes.jar' in z.namelist():
                    with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as jar:collect(jar,'classes.jar/')
        records.append(record)
    manifest={'scope':'Resolved Android debugRuntimeClasspath artifacts and declared POM licenses; native engine is inventoried separately.','components':records}
    (ROOT/'ThirdParty/AndroidNotices.json').write_text(json.dumps(manifest,indent=2)+'\n')
    header='QWIP Android runtime components\n\n'+'\n'.join(r['component']+' — '+', '.join(l['name'] for l in r['licenses']) for r in records)
    apache=(ROOT/'ThirdParty/MaterialIcons-LICENSE.txt').read_text()
    text=header+'\n\nMaterial icons: Google; pinned source in Config/Icons.lock.json.\n\n'+apache+'\n\n'+'\n\n'.join(notices)
    (ROOT/'app/src/main/assets/licenses/AndroidNotices.txt').write_text(text)
    print(json.dumps({'runtimeComponents':len(records),'embeddedNotices':len(notices),'missingLicenses':0}))

if __name__=='__main__':main()
