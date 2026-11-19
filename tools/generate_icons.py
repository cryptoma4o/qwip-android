#!/usr/bin/env python3
"""Pin only the Material vectors used by QWIP instead of shipping an icon catalog."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import urllib.request
import xml.etree.ElementTree as E

ROOT=Path(__file__).resolve().parents[1]
COMMIT='10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7'
ICONS={'Add':('content','add'),'Search':('action','search'),'Shield':('action','verified_user'),'VerifiedUser':('action','verified_user'),'Settings':('action','settings'),'PowerSettingsNew':('action','power_settings_new'),'AddCircleOutline':('content','add_circle_outline'),'Public':('social','public'),'Wifi':('notification','wifi'),'WifiOff':('notification','wifi_off'),'Star':('toggle','star'),'StarOutline':('toggle','star_outline'),'Check':('navigation','check'),'Refresh':('navigation','refresh'),'UploadFile':('file','upload_file'),'FolderOpen':('file','folder_open'),'Dns':('action','dns'),'Route':('maps','route'),'Palette':('image','palette'),'MonitorHeart':('device','monitor_heart'),'SaveAlt':('content','save_alt'),'Restore':('action','restore'),'Info':('action','info'),'DeleteOutline':('action','delete_outline'),'ArrowForwardIos':('navigation','arrow_forward_ios'),'ArrowBack':('navigation','arrow_back'),'KeyboardArrowRight':('hardware','keyboard_arrow_right')}

ICONS.update({"QrCodeScanner":("communication","qr_code_scanner"),"PhotoLibrary":("image","photo_library")})

def fetch(item):
    name,(category,icon)=item
    for cat in [category]+[c for c in ['action','content','navigation','file','image','device','maps','notification','social','toggle','hardware'] if c!=category]:
        url=f'https://raw.githubusercontent.com/google/material-design-icons/{COMMIT}/src/{cat}/{icon}/materialiconsoutlined/24px.svg'
        try:
            data=urllib.request.urlopen(url,timeout=8).read();svg=E.fromstring(data)
            assert svg.attrib['viewBox']=='0 0 24 24'
        except Exception: continue
        directory=ROOT/'Brand/MaterialIcons';directory.mkdir(parents=True,exist_ok=True);(directory/f'{icon}.svg').write_bytes(data)
        mirror=' android:autoMirrored="true"' if name in ('ArrowBack','ArrowForwardIos','KeyboardArrowRight') else ''
        vector=f'<!-- Google Material icons, Apache-2.0. SVG converted to Android VectorDrawable. -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"{mirror}>'
        count=0
        for path in svg.iter():
            if path.get('fill')=='none':continue
            tag=path.tag.split('}')[-1]
            if tag=='path': geometry=path.attrib['d']
            elif tag=='polygon': geometry='M'+path.attrib['points'].strip().replace(' ',' L')+'Z'
            elif tag=='circle':
                x,y,radius=(float(path.attrib[key]) for key in ('cx','cy','r'))
                geometry=f'M{x-radius:g},{y:g} a{radius:g},{radius:g} 0 1,0 {2*radius:g},0 a{radius:g},{radius:g} 0 1,0 {-2*radius:g},0 Z'
            elif tag in ('svg','g','title'):continue
            else:raise ValueError('Unsupported painted SVG shape: '+tag)
            vector+=f'<path android:fillColor="#FF000000" android:pathData="{geometry}"/>'
            count+=1
        if not count:raise ValueError('Empty vector: '+name)
        vector+='</vector>\n'
        (ROOT/f'app/src/main/res/drawable/mi_{icon}.xml').write_text(vector)
        return name,{'source':url,'resource':f'mi_{icon}','sha256':hashlib.sha256(data).hexdigest()}
    raise RuntimeError('No official vector: '+name)

def main():
    records={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        for name,record in executor.map(fetch,ICONS.items()): records[name]=record;print(name,'ok',flush=True)
    license=urllib.request.urlopen(f'https://raw.githubusercontent.com/google/material-design-icons/{COMMIT}/LICENSE',timeout=15).read()
    (ROOT/'ThirdParty/MaterialIcons-LICENSE.txt').write_bytes(license)
    (ROOT/'app/src/main/assets/licenses/MaterialIcons-LICENSE.txt').write_bytes(license)
    (ROOT/'Config/Icons.lock.json').write_text(json.dumps({'repository':'google/material-design-icons','commit':COMMIT,'license':'Apache-2.0','icons':records},indent=2)+'\n')
    source='// Google Material icons, Apache-2.0. See ThirdParty/MaterialIcons-LICENSE.txt.\npackage com.qwipvpn.app.ui\n\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.graphics.vector.ImageVector\nimport androidx.compose.ui.res.vectorResource\nimport com.qwipvpn.app.R\n\nobject QWIPIcons {\n'
    for name,record in records.items(): source+=f'    val {name}: ImageVector\n        @Composable get() = ImageVector.vectorResource(R.drawable.{record["resource"]})\n'
    (ROOT/'app/src/main/kotlin/com/qwipvpn/app/ui/QWIPIcons.kt').write_text(source+'}\n')
    for file in (ROOT/'app/src/main/kotlin/com/qwipvpn/app/ui').glob('*.kt'):
        if file.name=='QWIPIcons.kt':continue
        text='\n'.join(line for line in file.read_text().split('\n') if not line.startswith('import androidx.compose.material.icons'))
        file.write_text(text.replace('Icons.AutoMirrored.Outlined.','QWIPIcons.').replace('Icons.Outlined.','QWIPIcons.'))
    build=ROOT/'app/build.gradle.kts'
    build.write_text('\n'.join(line for line in build.read_text().split('\n') if 'material-icons-extended' not in line))
    print('Packaged only the required vector resources')

if __name__=='__main__':main()
