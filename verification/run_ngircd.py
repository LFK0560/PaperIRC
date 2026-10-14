#!/usr/bin/env python3
"""Start an installed ngIRCd in an isolated directory and run the opt-in real TLS integration test."""
import argparse, os, pathlib, socket, subprocess, time, uuid
from run_paper import PROJECT, create_tls, free_port, wait_for

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--java-home',required=True,type=pathlib.Path)
    parser.add_argument('--ngircd',required=True,type=pathlib.Path)
    parser.add_argument('--maven-repo',type=pathlib.Path)
    args=parser.parse_args()
    folder=PROJECT/'verification/runtime'/('ngircd-'+uuid.uuid4().hex[:8]);folder.mkdir(parents=True)
    cert,key,_=create_tls(folder,args.java_home);port=free_port();plain=free_port()
    config=folder/'ngircd.conf'
    config.write_text(f'''[Global]
Name = irc.paperirc.test
Listen = 127.0.0.1
Ports = {plain}
PidFile = {folder}/ngircd.pid
MotdFile = {folder}/motd.txt
[Limits]
MaxConnections = 64
MaxConnectionsIP = 32
MaxNickLength = 32
[Options]
DNS = no
Ident = no
PAM = no
RequireAuthPing = yes
[SSL]
Ports = {port}
CertFile = {cert}
KeyFile = {key}
[Channel]
Name = #minecraft
Modes = +nt
''')
    # Reproduce the production ngIRCd MOTD that retains CR from Windows line endings.
    (folder/'motd.txt').write_bytes('PaperIRC isolated integration test\r\n欢迎 😀\r\n'.encode('utf-8'))
    subprocess.run([str(args.ngircd),'--configtest','--config',str(config)],input='\n',text=True,check=True,stdout=subprocess.DEVNULL)
    log=(PROJECT/'verification/ngircd-server.log').open('w')
    server=subprocess.Popen([str(args.ngircd),'--nodaemon','--config',str(config)],stdout=log,stderr=subprocess.STDOUT)
    def listening():
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=.2):return True
        except OSError:return False
    try:
        wait_for(listening,'ngIRCd TLS 监听',15)
        env=os.environ.copy();env.update(JAVA_HOME=str(args.java_home),PAPERIRC_NGIRCD_PORT=str(port),PAPERIRC_NGIRCD_CA=str(folder/'ca.pem'))
        command=[str(PROJECT/'mvnw'),'-B','-Dtest=RealNgircdTest','test']
        if args.maven_repo:command.append('-Dmaven.repo.local='+str(args.maven_repo))
        result=subprocess.run(command,cwd=PROJECT,env=env)
        if result.returncode:raise SystemExit(result.returncode)
        print('PASS 真实 ngIRCd 多连接 TLS/频道/查询/去重/改名冲突/关闭/重连/清理；运行目录：'+str(folder))
    finally:
        server.terminate()
        try:server.wait(timeout=5)
        except subprocess.TimeoutExpired:server.kill();server.wait()
        log.close()

if __name__=='__main__':main()
