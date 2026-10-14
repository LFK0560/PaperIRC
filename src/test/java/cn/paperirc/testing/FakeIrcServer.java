package cn.paperirc.testing;

import cn.paperirc.irc.*;
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real TLS sockets plus a controllable IRC protocol subset, deliberately not claimed as ngIRCd. */
public final class FakeIrcServer implements AutoCloseable {
    public record Sent(String nick,String target,String text) {}
    private final SSLServerSocket server;
    private final ExecutorService workers=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("test-irc-",0).factory());
    private final Set<Peer> peers=ConcurrentHashMap.newKeySet();
    private final Map<String,Peer> names=new ConcurrentHashMap<>();
    private final Set<String> occupied=ConcurrentHashMap.newKeySet();
    private final Object nameLock=new Object();
    public final Queue<Sent> sent=new ConcurrentLinkedQueue<>();
    public final Queue<String> commands=new ConcurrentLinkedQueue<>();
    public final AtomicInteger accepted=new AtomicInteger(),pong=new AtomicInteger();
    public volatile int nickLength=32;
    public volatile boolean echo,denyJoin,rejectRenames,holdRegistration,ignoreRenames;
    public volatile boolean holdBotMessages;
    public volatile boolean windowsMotd;
    private final Queue<String> delayed=new ConcurrentLinkedQueue<>();
    private volatile boolean stopped;
    public FakeIrcServer(SSLContext tls) throws Exception {
        server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,32,java.net.InetAddress.getLoopbackAddress());
        workers.execute(()->{
            while(!stopped)try{
                SSLSocket s=(SSLSocket)server.accept();accepted.incrementAndGet();
                workers.execute(()->serve(s));
            }catch(IOException e){if(!stopped)throw new UncheckedIOException(e);}
        });
    }
    public int port(){return server.getLocalPort();}
    public int members(){return (int)peers.stream().filter(p->p.joined).count();}
    public Set<String> memberNames(){Set<String> result=new HashSet<>();peers.stream().filter(p->p.joined).forEach(p->result.add(p.nick));return result;}
    public void occupy(String nickname){occupied.add(CaseMapping.RFC1459.fold(nickname));}
    public void external(String nickname,String text){broadcast(":"+nickname+"!external@irc.test PRIVMSG #minecraft :"+text,null);}
    public void releaseBotMessages(){holdBotMessages=false;String line;while((line=delayed.poll())!=null)for(Peer p:peers)if(p.joined&&p.nick.startsWith("MCBot"))p.write(line);}
    public void disconnect(String nickname){Peer p=names.get(CaseMapping.RFC1459.fold(nickname));if(p!=null)p.close();}
    public void disconnectAll(){for(Peer p:List.copyOf(peers))p.close();}
    public void ping(){for(Peer p:peers)p.write("PING :test-token");}
    private void serve(SSLSocket socket) {
        Peer p=null;
        try {
            socket.startHandshake();p=new Peer(socket);peers.add(p);LineReader in=new LineReader(socket.getInputStream());
            String line;while(!stopped&&(line=in.read())!=null) {commands.add(line);handle(p,IrcMessage.parse(line));}
        }catch(Exception ignored){}
        finally{if(p!=null)p.close();else try{socket.close();}catch(IOException ignored){}}
    }
    private void handle(Peer p,IrcMessage m) {
        switch(m.command()) {
            case "NICK" -> {
                String requested=m.trailing();
                if(ignoreRenames&&p.registered)return;
                synchronized(nameLock) {
                    String folded=CaseMapping.RFC1459.fold(requested);
                    if(requested.length()>nickLength||rejectRenames&&p.registered) {p.write(":srv 432 "+p.target()+" "+requested+" :Invalid nickname");return;}
                    Peer conflict=names.get(folded);
                    if(occupied.contains(folded)||conflict!=null&&conflict!=p){p.write(":srv 433 "+p.target()+" "+requested+" :Nickname in use");return;}
                    String old=p.nick;
                    if(old!=null)names.remove(CaseMapping.RFC1459.fold(old),p);
                    p.nick=requested;names.put(folded,p);
                    if(p.registered)broadcast(":"+old+"!"+p.ident+"@localhost NICK :"+requested,null);
                    else welcome(p);
                }
            }
            case "USER" -> {p.ident=m.params().getFirst();welcome(p);}
            case "JOIN" -> {
                if(denyJoin){p.write(":srv 474 "+p.target()+" #minecraft :Banned");return;}
                p.joined=true;broadcast(":"+p.prefix()+" JOIN :#minecraft",null);
                p.write(":srv 353 "+p.nick+" = #minecraft :"+String.join(" ",memberNames()));p.write(":srv 366 "+p.nick+" #minecraft :End of names");
            }
            case "PRIVMSG" -> {
                if(!p.joined)return;
                sent.add(new Sent(p.nick,m.params().getFirst(),m.trailing()));
                broadcast(":"+p.prefix()+" PRIVMSG "+m.params().getFirst()+" :"+m.trailing(),echo?null:p);
            }
            case "PING" -> p.write(":srv PONG srv :"+m.trailing());
            case "WHO" -> {
                for(Peer member:peers)if(member.joined)p.write(":srv 352 "+p.nick+" #minecraft "+member.ident+" localhost srv "+member.nick+" H :0 Minecraft");
                p.write(":srv 315 "+p.nick+" #minecraft :End of WHO");
            }
            case "WHOIS" -> {
                p.write(":srv 311 "+p.nick+" "+p.nick+" "+p.ident+" localhost * :Minecraft");
                p.write(":srv 318 "+p.nick+" "+m.trailing()+" :End of WHOIS");
            }
            case "PONG" -> pong.incrementAndGet();
            case "QUIT" -> p.close();
            default -> {}
        }
    }
    private void welcome(Peer p) {
        if(p.nick!=null&&p.ident!=null&&!p.registered&&!holdRegistration){
            p.registered=true;p.write(":srv 001 "+p.nick+" :Welcome");
            p.write(":srv 005 "+p.nick+" NICKLEN="+nickLength+" CASEMAPPING=rfc1459 :Supported");
            if(windowsMotd) {
                p.write("");p.write(":srv 372 "+p.nick+" :- 欢迎来到服务器 😀\r");p.write("\r");p.write("PING :test-token\r");
            }
            p.write(":srv 376 "+p.nick+" :End MOTD");
        }
    }
    private void broadcast(String line,Peer skip){for(Peer p:peers)if(p.joined&&p!=skip) {
        if(holdBotMessages&&p.nick.startsWith("MCBot")&&(line.contains(" PRIVMSG ")||line.contains(" NICK ")||line.contains(" QUIT ")))delayed.add(line);
        else p.write(line);
    }}
    private final class Peer {
        final SSLSocket socket;String nick,ident;volatile boolean joined,registered;
        Peer(SSLSocket socket){this.socket=socket;}
        String target(){return nick==null?"*":nick;}
        String prefix(){return nick+"!"+ident+"@localhost";}
        synchronized void write(String line) {try{socket.getOutputStream().write((line+"\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();}catch(IOException ignored){}}
        void close(){boolean wasJoined=joined;joined=false;peers.remove(this);if(wasJoined)broadcast(":"+prefix()+" QUIT :Disconnected",this);if(nick!=null)names.remove(CaseMapping.RFC1459.fold(nick),this);try{socket.close();}catch(IOException ignored){}}
    }
    @Override public void close() throws Exception {stopped=true;server.close();disconnectAll();workers.shutdownNow();workers.awaitTermination(2,TimeUnit.SECONDS);}
}
