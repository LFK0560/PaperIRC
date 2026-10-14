package cn.paperirc.core;

import cn.paperirc.irc.*;
import cn.paperirc.testing.TestSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration against a real ngIRCd process; no Minecraft-client emulation is claimed. */
@EnabledIfEnvironmentVariable(named="PAPERIRC_NGIRCD_PORT",matches="[0-9]+")
class RealNgircdTest {
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)fail("真实 ngIRCd 等待超时");Thread.sleep(50);}
    }
    private static SSLSocketFactory tls() throws Exception {
        KeyStore roots=KeyStore.getInstance("PKCS12");roots.load(null,null);
        try(InputStream in=Files.newInputStream(Path.of(System.getenv("PAPERIRC_NGIRCD_CA")))) {
            roots.setCertificateEntry("test",CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(roots);
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,tm.getTrustManagers(),null);return context.getSocketFactory();
    }
    private static final class Port implements Bridge.GamePort {
        record Delivery(UUID id,String text){}
        final Queue<Delivery> deliveries=new ConcurrentLinkedQueue<>();
        public void tell(UUID id,String text){}
        public void deliver(Set<UUID> ids,String text){ids.forEach(id->deliveries.add(new Delivery(id,text)));}
        public void log(boolean error,String text){if(error)System.err.println(text);}
        long count(UUID id,String text){return deliveries.stream().filter(d->d.id().equals(id)&&d.text().contains(text)).count();}
    }
    private static final class Peer implements AutoCloseable {
        final SSLSocket socket;final Queue<IrcMessage> messages=new ConcurrentLinkedQueue<>();
        final Set<String> names=ConcurrentHashMap.newKeySet();final AtomicInteger namesDone=new AtomicInteger();
        final AtomicBoolean closed=new AtomicBoolean(),joined=new AtomicBoolean();final String nick;
        final Thread reader;
        Peer(SSLSocketFactory factory,int port,String nick) throws Exception {
            this.nick=nick;socket=(SSLSocket)factory.createSocket("127.0.0.1",port);
            SSLParameters p=socket.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(p);
            socket.setSoTimeout(1000);socket.startHandshake();
            reader=Thread.ofVirtual().name("ngircd-test-peer").start(()->{
                LineReader in=null;try{in=new LineReader(socket.getInputStream());}catch(IOException e){return;}
                while(!closed.get())try {
                    String line=in.read();if(line==null)return;IrcMessage m=IrcMessage.parse(line);messages.add(m);
                    if(m.command().equals("PING"))send("PONG :"+m.trailing());
                    if(m.command().equals("376")||m.command().equals("422"))send("JOIN #minecraft");
                    if(m.command().equals("353"))for(String name:m.trailing().split(" +"))names.add(name.replaceFirst("^[~&@%+]+",""));
                    if(m.command().equals("366")){namesDone.incrementAndGet();joined.set(true);}
                }catch(java.net.SocketTimeoutException ignored){}
                catch(Exception e){if(!closed.get())System.err.println(e.getMessage());return;}
            });
            send("NICK "+nick);send("USER probe 0 * :PaperIRC integration probe");await(joined::get);
        }
        synchronized void send(String line) throws IOException {socket.getOutputStream().write((line+"\r\n").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();}
        void say(String text) throws IOException {send("PRIVMSG #minecraft :"+text);}
        Set<String> names() throws Exception {int before=namesDone.get();names.clear();send("NAMES #minecraft");await(()->namesDone.get()>before);return Set.copyOf(names);}
        long messages(String nick,String text){return messages.stream().filter(m->m.command().equals("PRIVMSG")&&m.nick().equals(nick)&&m.trailing().equals(text)).count();}
        public void close() throws Exception {closed.set(true);try{send("QUIT :test complete");}finally{socket.close();reader.join(2000);}}
    }
    @Test void twentyPlayersUseRealNgircdTlsMembershipQueriesRenameAndOff() throws Exception {
        int port=Integer.parseInt(System.getenv("PAPERIRC_NGIRCD_PORT"));SSLSocketFactory factory=tls();
        Port game=new Port();List<UUID> ids=new ArrayList<>();
        Bridge bridge=new Bridge(TestSettings.settings(port),Map.of(),game,(id,p)->CompletableFuture.completedFuture(null),
                ()->new ServerSnapshot(20,20,20,12.34,1234567),factory);
        try(Peer alice=new Peer(factory,port,"Alice");Peer occupied=new Peer(factory,port,"MC_Target")) {
            for(int i=0;i<20;i++){UUID id=UUID.randomUUID();ids.add(id);bridge.join(id,"P"+i).get();}
            await(()->{try{return bridge.overallStatus().get(1,TimeUnit.SECONDS).joinedPlayers()==20;}catch(Exception e){return false;}});
            Set<String> names=alice.names();assertTrue(names.contains("MCBot"));for(int i=0;i<20;i++)assertTrue(names.contains("MC_P"+i),names.toString());
            alice.say("真实ngIRCd相同正文😀");occupied.say("真实ngIRCd相同正文😀");
            await(()->ids.stream().allMatch(id->game.count(id,"真实ngIRCd相同正文😀")==2));
            bridge.chat(ids.getFirst(),"本地不回流");bridge.chat(ids.getFirst(),"本地不回流");
            await(()->alice.messages("MC_P0","本地不回流")==2);assertEquals(0,game.count(ids.getFirst(),"本地不回流"));
            bridge.chat(ids.getFirst(),"!list");await(()->alice.messages("MCBot","在线人数：20/20")==1);
            await(()->ids.stream().allMatch(id->game.count(id,"在线人数：20/20")==1));
            bridge.nickname(ids.getFirst(),"Target").get();
            await(()->{try{return bridge.playerStatus(ids.getFirst()).get().actualNick().matches("MC_Target_[0-9a-f]{4}");}catch(Exception e){return false;}});
            assertEquals(20,bridge.overallStatus().get().joinedPlayers());
            bridge.enabled(ids.get(1),false).get();
            await(()->{try{return !alice.names().contains("MC_P1");}catch(Exception e){return false;}});
            alice.say("关闭后不接收");await(()->game.count(ids.getFirst(),"关闭后不接收")==1);assertEquals(0,game.count(ids.get(1),"关闭后不接收"));
            bridge.reconnect().get();
            await(()->{try{return bridge.overallStatus().get().joinedPlayers()==19;}catch(Exception e){return false;}});
            assertEquals(Bridge.Phase.DISABLED,bridge.playerStatus(ids.get(1)).get().phase());
            bridge.shutdown().get(6,TimeUnit.SECONDS);Set<String> remaining=alice.names();
            assertEquals(Set.of("Alice","MC_Target"),remaining);
        }finally{bridge.shutdown().get(6,TimeUnit.SECONDS);}
    }
}
