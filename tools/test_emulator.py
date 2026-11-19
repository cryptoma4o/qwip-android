#!/usr/bin/env python3
"""Install and test only an explicitly named, owned QWIP AVD."""
from pathlib import Path
import argparse
import json
import re
import subprocess
import tempfile
import shutil
import hashlib

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--adb-port',type=int,default=5041)
    parser.add_argument('--adb',type=Path,default=Path.home()/'Library/Android/sdk/platform-tools/adb')
    parser.add_argument('--test-class',help='Optional instrumentation class for a targeted rerun')
    args=parser.parse_args()
    adb=[str(args.adb),'-P',str(args.adb_port),'-s',args.serial]
    def run(*command,timeout=30,check=True):
        result=subprocess.run(adb+list(command),capture_output=True,text=True,timeout=timeout)
        if check and result.returncode: raise RuntimeError(f'Owned emulator command failed: {command[0]}')
        return result
    name=run('emu','avd','name').stdout.splitlines()[0].strip()
    if name!='Codex_QWIP_API35' or run('shell','getprop','ro.kernel.qemu').stdout.strip()!='1':
        raise RuntimeError('Refusing to install on a personal emulator or physical device')
    if run('shell','getprop','sys.boot_completed').stdout.strip()!='1': raise RuntimeError('Owned emulator has not booted')
    main_apk=ROOT/'app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
    if not main_apk.exists(): main_apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    test_apk=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    run('install','-r','-t',str(main_apk),timeout=120)
    run('install','-r','-t',str(test_apk),timeout=90)
    run('shell','pm','grant','com.qwipvpn.app','android.permission.POST_NOTIFICATIONS')
    run('shell','pm','grant','com.qwipvpn.app','android.permission.CAMERA')
    run('shell','appops','set','com.qwipvpn.app','ACTIVATE_VPN','allow')
    # UI assertions use Russian explicitly; English and Chinese have their own coverage.
    run('shell','cmd','locale','set-app-locales','com.qwipvpn.app','--locales','ru')
    directory=ROOT/'.artifacts/verification';directory.mkdir(parents=True,exist_ok=True)
    selection=['-e','class',args.test_class] if args.test_class else []
    result=run('shell','am','instrument','-w','-r',*selection,'com.qwipvpn.app.test/androidx.test.runner.AndroidJUnitRunner',timeout=900,check=False)
    output=result.stdout+result.stderr
    (directory/'instrumentation.txt').write_text(output)
    matched=re.search(r'OK \((\d+) tests?\)',output)
    passed=bool(result.returncode==0 and matched and not any(marker in output for marker in ('FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed')))
    count=int(matched.group(1)) if matched else 0
    if count<(1 if args.test_class else 8): passed=False
    with tempfile.TemporaryDirectory(prefix='qwip-captures-') as staging:
        copied=run('pull','/sdcard/Android/data/com.qwipvpn.app/files/qa',str(Path(staging)/'qa'),check=False)
        if copied.returncode==0:
            shots=directory/'screenshots';shots.mkdir(exist_ok=True)
            for capture in (Path(staging)/'qa').glob('*.png'):shutil.copy2(capture,shots/capture.name)
    report={'avd':name,'serial':args.serial,'api':run('shell','getprop','ro.build.version.sdk').stdout.strip(),
            'abi':run('shell','getprop','ro.product.cpu.abi').stdout.strip(),'passed':passed,'tests':count,
            'apkSHA256':hashlib.sha256(main_apk.read_bytes()).hexdigest(),
            'testApkSHA256':hashlib.sha256(test_apk.read_bytes()).hexdigest(),
            'transportFixture':'VLESS / pinned TLS 1.3','hostVpnStarted':False,'personalDeviceUsed':False}
    (directory/'emulator-verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    if not passed: print(output[-6500:]);raise SystemExit(1)

if __name__=='__main__':main()
