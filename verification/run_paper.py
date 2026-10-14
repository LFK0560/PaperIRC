#!/usr/bin/env python3
"""Isolated Paper load + end-to-end smoke test; never uses a deployment's data directory."""
import argparse, hashlib, json, os, pathlib, re, shutil, socket, ssl, subprocess, threading, time, uuid

PROJECT = pathlib.Path(__file__).resolve().parents[1]
EXPECTED_PAPER = '5dd0c8fb20a96eb7c98f3c4b1c890e99ef6db56fd689a2cf06975a3471fda757'

def wait_for(condition, description, seconds=30):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if condition(): return
        time.sleep(.05)
    raise AssertionError('超时：'+description)

def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1',0));return s.getsockname()[1]

class TestIRC:
    def __init__(self, certificate, key):
        self.context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.load_cert_chain(certificate,key)
        self.socket=socket.socket();self.socket.bind(('127.0.0.1',0));self.socket.listen(64)
        self.port=self.socket.getsockname()[1];self.peers=[];self.messages=[];self.commands=[]
        self.lock=threading.RLock();self.closed=False
        threading.Thread(target=self.accept,daemon=True).start()
    def accept(self):
        while not self.closed:
            try: connection,_=self.socket.accept()
            except OSError: return
            threading.Thread(target=self.serve,args=(connection,),daemon=True).start()
    def write(self,p,line):
        try:
            with p['send']: p['socket'].sendall((line+'\r\n').encode())
        except OSError: pass
    def broadcast(self,line,skip=None):
        with self.lock: targets=[p for p in self.peers if p['joined'] and p is not skip]
        for p in targets: self.write(p,line)
    def external(self,nick,text): self.broadcast(f':{nick}!external@irc.test PRIVMSG #minecraft :{text}')
    def members(self):
        with self.lock: return {p['nick'] for p in self.peers if p['joined']}
    def serve(self,raw):
        p=None
        try:
            conn=self.context.wrap_socket(raw,server_side=True)
            p={'socket':conn,'nick':None,'ident':None,'registered':False,'joined':False,'send':threading.Lock()}
            with self.lock: self.peers.append(p)
            reader=conn.makefile('rb')
            while not self.closed:
                data=reader.readline(8193)
                if not data: break
                line=data.decode().rstrip('\r\n');self.commands.append(line)
                head,_,tail=line.partition(' :');args=head.split();cmd=args[0]
                if cmd=='NICK':
                    target=args[1];old=p['nick']
                    with self.lock: conflict=any(x is not p and x['nick'] and x['nick'].lower()==target.lower() for x in self.peers)
                    if conflict:
                        self.write(p,f':srv 433 {old or "*"} {target} :Nickname in use');continue
                    p['nick']=target
                    if p['registered']: self.broadcast(f':{old}!{p["ident"]}@localhost NICK :{target}')
                elif cmd=='USER': p['ident']=args[1]
                elif cmd=='JOIN':
                    p['joined']=True;self.broadcast(f':{p["nick"]}!{p["ident"]}@localhost JOIN :#minecraft')
                    self.write(p,f':srv 366 {p["nick"]} #minecraft :End of names')
                elif cmd=='PRIVMSG':
                    self.messages.append({'nick':p['nick'],'text':tail,'target':args[1]})
                    self.broadcast(f':{p["nick"]}!{p["ident"]}@localhost PRIVMSG {args[1]} :{tail}',p)
                elif cmd=='PING': self.write(p,':srv PONG srv :'+tail)
                elif cmd=='WHO':
                    with self.lock: members=[x for x in self.peers if x['joined']]
                    for member in members:
                        self.write(p,f':srv 352 {p["nick"]} #minecraft {member["ident"]} localhost srv {member["nick"]} H :0 Minecraft')
                    self.write(p,f':srv 315 {p["nick"]} #minecraft :End of WHO')
                elif cmd=='QUIT': break
                if p['nick'] and p['ident'] and not p['registered']:
                    p['registered']=True
                    for line in [f':srv 001 {p["nick"]} :Welcome',f':srv 005 {p["nick"]} NICKLEN=32 CASEMAPPING=rfc1459 :Supported','',f':srv 372 {p["nick"]} :- 欢迎 😀\r','\r',f':srv 376 {p["nick"]} :End MOTD']:
                        self.write(p,line)
        except (OSError,UnicodeError): pass
        finally:
            if p:
                with self.lock:
                    if p in self.peers: self.peers.remove(p)
                p['socket'].close()
            else: raw.close()
    def disconnect(self,nick):
        with self.lock: targets=[p for p in self.peers if p['nick']==nick]
        for p in targets:
            try: p['socket'].shutdown(socket.SHUT_RDWR)
            except OSError: pass
            p['socket'].close()
    def close(self):
        self.closed=True;self.socket.close()
        with self.lock: names=[p['nick'] for p in self.peers]
        for name in names:self.disconnect(name)

def create_tls(folder, java_home):
    def run(*command): subprocess.run(command,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    ca=folder/'ca.pem';cakey=folder/'ca.key';key=folder/'server.key';csr=folder/'server.csr';cert=folder/'server.pem';store=folder/'test-trust.p12'
    run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-keyout',str(cakey),'-out',str(ca),'-subj','/CN=PaperIRC Test CA')
    run('openssl','req','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(csr),'-subj','/CN=localhost')
    ext=folder/'extensions.txt';ext.write_text('subjectAltName=IP:127.0.0.1,DNS:localhost\nextendedKeyUsage=serverAuth\n')
    run('openssl','x509','-req','-in',str(csr),'-CA',str(ca),'-CAkey',str(cakey),'-CAcreateserial','-days','1','-extfile',str(ext),'-out',str(cert))
    run(str(java_home/'bin/keytool'),'-importkeystore','-noprompt','-srckeystore',str(java_home/'lib/security/cacerts'),'-srcstorepass','changeit','-destkeystore',str(store),'-deststoretype','PKCS12','-deststorepass','test-password')
    run(str(java_home/'bin/keytool'),'-importcert','-noprompt','-alias','test-ca','-file',str(ca),'-keystore',str(store),'-storetype','PKCS12','-storepass','test-password')
    return cert,key,store

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--java-home',required=True,type=pathlib.Path)
    parser.add_argument('--paper-jar',required=True,type=pathlib.Path)
    parser.add_argument('--cache-from',type=pathlib.Path,help='Optional read-only copy source for Paper libraries/cache/versions')
    parser.add_argument('--accepted-eula-file',required=True,type=pathlib.Path,help='Existing eula=true file you have accepted')
    args=parser.parse_args()
    assert 'eula=true' in args.accepted_eula_file.read_text().replace(' ','').lower(), '需要已接受的测试服 EULA 文件'
    assert hashlib.sha256(args.paper_jar.read_bytes()).hexdigest()==EXPECTED_PAPER, '请使用官方 Paper 26.3 build 178'
    folder=PROJECT/'verification/runtime'/('paper-'+uuid.uuid4().hex[:8]);folder.mkdir(parents=True)
    cert,key,trust=create_tls(folder,args.java_home);irc=TestIRC(cert,key)
    paper=None;lines=[];results=[]
    def result(name):results.append(name);print('PASS '+name,flush=True)
    try:
        shutil.copy2(args.paper_jar,folder/'paper.jar');shutil.copy2(args.accepted_eula_file,folder/'eula.txt')
        if args.cache_from:
            for name in ('cache','libraries','versions'):
                if (args.cache_from/name).is_dir():shutil.copytree(args.cache_from/name,folder/name)
        plugins=folder/'plugins';(plugins/'PaperIRC').mkdir(parents=True)
        shutil.copy2(PROJECT/'target/PaperIRC-1.0.1.jar',plugins/'PaperIRC-1.0.1.jar')
        config=(PROJECT/'src/main/resources/config.yml').read_text()
        config=config.replace('enabled: false','enabled: true').replace('irc.example.invalid','127.0.0.1').replace('port: 6697',f'port: {irc.port}')
        (plugins/'PaperIRC/config.yml').write_text(config)
        port=free_port()
        (folder/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nmax-players=20\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n')
        paper=subprocess.Popen([str(args.java_home/'bin/java'),'-Xms256M','-Xmx1536M',f'-Djavax.net.ssl.trustStore={trust}','-Djavax.net.ssl.trustStorePassword=test-password','-Djavax.net.ssl.trustStoreType=PKCS12','-jar','paper.jar','--nogui'],cwd=folder,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        log=(PROJECT/'verification/paper-smoke.log').open('w')
        def read_paper():
            for line in paper.stdout:lines.append(line);log.write(line);log.flush()
        threading.Thread(target=read_paper,daemon=True).start()
        def console(command):paper.stdin.write(command+'\n');paper.stdin.flush()
        wait_for(lambda:any('Done (' in line for line in lines),'Paper 启动',90)
        wait_for(lambda:irc.members()=={'MCBot'},'无人在线常驻机器人')
        wait_for(lambda:any('机器人已加入' in line for line in lines),'机器人完成频道身份核对')
        assert any('26.3-178' in line for line in lines);assert any('PaperIRC 已启用' in line for line in lines)
        result('官方 Paper 26.3 build 178 加载 JAR，无玩家时只有机器人')
        irc.external('Alice','!list');wait_for(lambda:any(m['text']=='在线人数：0/20' for m in irc.messages),'无人查询')
        result('无人在线 IRC 查询返回 0/20')
        time.sleep(1.1);irc.external('Bob','!status')
        wait_for(lambda:any('TPS（1 分钟）' in m['text'] for m in irc.messages),'状态查询')
        reply=next(m['text'] for m in irc.messages if 'TPS（1 分钟）' in m['text'])
        assert '在线：0/20' in reply and '平均 MSPT：' in reply and '运行：' in reply
        assert not re.search('内存|版本|UUID',reply)
        result('真实人数/TPS/MSPT/JVM 运行时间查询，隐私字段未出现')
        time.sleep(1.1);irc.external('Carol','!help')
        wait_for(lambda:any('!status：人数' in m['text'] for m in irc.messages),'帮助查询')
        result('IRC !help 查询正常')
        console('irc help');console('irc adminstatus')
        wait_for(lambda:any('个人连接：0' in line for line in lines),'整体管理状态')
        result('控制台游戏命令及权限描述已注册')
        config_path=plugins/'PaperIRC/config.yml'
        config_path.write_text(config.replace("channel: '#minecraft'", "channel: 'bad channel'"))
        console('irc reload')
        wait_for(lambda:any('重载失败，保留原有效配置' in line for line in lines),'无效配置不替换现有配置')
        assert irc.members()=={'MCBot'}
        config_path.write_text(config);console('irc reload')
        wait_for(lambda:any('配置校验通过' in line for line in lines),'有效重载')
        console('irc reconnect');wait_for(lambda:sum(c.startswith('USER ') for c in irc.commands)>=2,'机器人手动重连')
        wait_for(lambda:irc.members()=={'MCBot'},'重连后只有机器人')
        result('无效重载保留原配置，有效重载与手动重连成功')
        console('stop');paper.wait(timeout=20);wait_for(lambda:not irc.members(),'停服关闭全部连接')
        assert any('PaperIRC 已停用' in line for line in lines)
        assert any(line.startswith('QUIT ') for line in irc.commands)
        result('停服清理全部连接并尽力发送 QUIT')
        (PROJECT/'verification/paper-result.json').write_text(json.dumps({'paper':'26.3-178','paper_sha256':EXPECTED_PAPER,'checks':results,'status':'PASS','runtime':str(folder.relative_to(PROJECT))},ensure_ascii=False,indent=2))
        print('验证完成；测试运行目录：'+str(folder),flush=True)
    finally:
        if paper and paper.poll() is None:
            try:paper.stdin.write('stop\n');paper.stdin.flush();paper.wait(timeout=15)
            except Exception:paper.kill()
        irc.close()

if __name__=='__main__':main()
