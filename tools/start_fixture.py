#!/usr/bin/env python3
"""Local TLS VLESS + HTTP fixture. No host VPN, live provider or private account.

Build tools/fixture first. The generated client config contains only synthetic
credentials and a public certificate pin, and is packaged in the test APK only.
"""
from pathlib import Path
import argparse
import hashlib
import http.server
import json
import signal
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); return sock.getsockname()[1]

class Handler(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        if self.path.startswith('/delay/'):
            value=self.path.removeprefix('/delay/').split('?',1)[0]
            if not value.isdecimal() or not 0<=int(value)<=1000:
                self.send_error(400);return
            time.sleep(int(value)/1000)
        self.send_response(200);self.send_header('Connection','close');self.end_headers()
    def do_GET(self):
        report=ROOT/'.artifacts/verification/tls-http-request.json';report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps({'fixtureHttpRequestReceived':True,'time':time.time()}))
        data=b'QWIP encrypted tunnel fixture v1\n'
        self.send_response(200); self.send_header('Content-Length',str(len(data))); self.send_header('Connection','close'); self.end_headers(); self.wfile.write(data)
    def log_message(self,*args): pass

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--reuse',action='store_true');parser.add_argument('--debug',action='store_true');args=parser.parse_args()
    directory=ROOT/'Support/TestFixture';directory.mkdir(parents=True,exist_ok=True)
    cert=directory/'certificate.pem';key=directory/'private-key.pem'
    if not args.reuse or not cert.exists():
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','2','-subj','/CN=qwip-fixture.local','-addext','subjectAltName=DNS:qwip-fixture.local'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
    key.chmod(0o600)
    der=subprocess.check_output(['openssl','x509','-in',str(cert),'-outform','DER'])
    pin=hashlib.sha256(der).hexdigest()
    existing=directory/'server.json'
    port=json.loads(existing.read_text())['inbounds'][0]['port'] if args.reuse and existing.exists() else free_port()
    web=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);target=web.server_address[1]
    closed=socket.socket();closed.bind(('127.0.0.1',0))
    identity='00000000-0000-4000-8000-000000000001'
    config={'log':{'loglevel':'debug' if args.debug else 'warning','access':'none'},'inbounds':[{
        'listen':'127.0.0.1','port':port,'protocol':'vless','settings':{'clients':[{'id':identity}],'decryption':'none'},
        'streamSettings':{'network':'tcp','security':'tls','tlsSettings':{'minVersion':'1.3','certificates':[{'certificateFile':str(cert),'keyFile':str(key)}]}}}],
        'outbounds':[{'protocol':'freedom','settings':{'redirect':f'127.0.0.1:{target}',
            'finalRules':[{'action':'allow','ip':['127.0.0.1/32'],'port':str(target)}]}}]}
    config_path=directory/'server.json';config_path.write_text(json.dumps(config));config_path.chmod(0o600)
    client={'remarks':'TLS fixture','dns':{'hosts':{'qwip-fixture.invalid':'198.51.100.9'},'servers':['1.1.1.1']},
        'outbounds':[{'tag':'fixture-proxy','protocol':'vless','settings':{'vnext':[{'address':'10.0.2.2','port':port,'users':[{'id':identity,'encryption':'none'}]}]},
        'streamSettings':{'network':'tcp','security':'tls','tlsSettings':{'serverName':'qwip-fixture.local','minVersion':'1.3','pinnedPeerCertSha256':pin}}}]}
    generated=ROOT/'app/build/generated/fixtureAssets';generated.mkdir(parents=True,exist_ok=True)
    (generated/'fixture.json').write_text(json.dumps(client))
    (generated/'probe-target.json').write_text(json.dumps({'url':f'http://10.0.2.2:{target}/fixture','closedHost':'10.0.2.2','closedPort':closed.getsockname()[1]}))
    log_dir=ROOT/'.artifacts/bootstrap';log_dir.mkdir(parents=True,exist_ok=True)
    log=(log_dir/'tls-fixture.log').open('w')
    process=subprocess.Popen([str(ROOT/'Support/fixture-server'),str(config_path)],stdout=log,stderr=subprocess.STDOUT)
    def stop(*_): raise KeyboardInterrupt
    signal.signal(signal.SIGTERM,stop)
    threading.Thread(target=web.serve_forever,daemon=True).start()
    try:
        for _ in range(100):
            if process.poll() is not None: raise RuntimeError('TLS fixture exited')
            try:
                with socket.create_connection(('127.0.0.1',port),timeout=.1): break
            except OSError: time.sleep(.1)
        else: raise RuntimeError('TLS fixture did not listen')
        print(json.dumps({'fixture':'ready','transport':'VLESS/TLS 1.3','certificatePin':pin,'port':port}),flush=True)
        process.wait()
    except KeyboardInterrupt: pass
    finally:
        process.terminate()
        try: process.wait(timeout=5)
        except subprocess.TimeoutExpired: process.kill();process.wait()
        web.shutdown();web.server_close();closed.close();log.close()

if __name__=='__main__': main()
