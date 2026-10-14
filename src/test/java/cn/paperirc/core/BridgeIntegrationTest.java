package cn.paperirc.core;

import cn.paperirc.config.Settings;
import cn.paperirc.persistence.*;
import cn.paperirc.testing.*;
import org.junit.jupiter.api.*;
import javax.net.ssl.SSLSocketFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class BridgeIntegrationTest {
    private static TlsFixture tls;
    private FakeIrcServer irc;
    private Bridge bridge;
    private final Port port=new Port();
    private final Map<UUID,PlayerPreference> saved=new ConcurrentHashMap<>();
    private static class Port implements Bridge.GamePort {
        record Delivery(UUID player,String text){}
        final Queue<Delivery> deliveries=new ConcurrentLinkedQueue<>();
        final Queue<String> feedback=new ConcurrentLinkedQueue<>(),logs=new ConcurrentLinkedQueue<>();
        @Override public void tell(UUID id,String text){feedback.add(text);}
        @Override public void deliver(Set<UUID> ids,String text){ids.forEach(id->deliveries.add(new Delivery(id,text)));}
        @Override public void log(boolean error,String text){logs.add(text);if(error)System.err.println(text);}
        long count(UUID id,String text){return deliveries.stream().filter(d->d.player().equals(id)&&d.text().contains(text)).count();}
    }
    @BeforeAll static void certs() throws Exception {tls=TlsFixture.get();}
    @BeforeEach void start() throws Exception {irc=new FakeIrcServer(tls.valid);}
    private void connect(Settings settings,Map<UUID,PlayerPreference> prefs,SSLSocketFactory factory) {
        saved.putAll(prefs);
        bridge=new Bridge(settings,prefs,port,(id,p)->{saved.put(id,p);return CompletableFuture.completedFuture(null);},
                ()->new ServerSnapshot(0,37,19.98,12.34,1234567),factory);
    }
    private void connect() throws Exception {connect(TestSettings.settings(irc.port()),Map.of(),tls.client.getSocketFactory());}
    @Test void windowsMotdAndEmptyFramesAllowBotAndPlayerToJoinAndRespond() throws Exception {
        irc.windowsMotd=true;connect();UUID id=UUID.randomUUID();joined(id,"Steve");
        await(()->irc.members()==2&&irc.pong.get()==2);
        irc.external("Alice","MOTD后消息 😀");await(()->port.count(id,"MOTD后消息 😀")==1);
        bridge.chat(id,"欢迎后发言");await(()->irc.sent.stream().anyMatch(m->m.nick().equals("MC_Steve")&&m.text().equals("欢迎后发言")));
        assertEquals(2,irc.accepted.get());assertFalse(port.logs.stream().anyMatch(s->s.contains("连接失败")));
    }
    @AfterEach void stop() throws Exception {if(bridge!=null)bridge.shutdown().get(6,TimeUnit.SECONDS);irc.close();}
    static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("等待条件超时");Thread.sleep(20);}
    }
    private void joined(UUID id,String name) throws Exception {
        bridge.join(id,name).get();
        try{await(()->{try{return bridge.playerStatus(id).get().phase()==Bridge.Phase.JOINED&&bridge.overallStatus().get().bot()==Bridge.Phase.JOINED;}catch(Exception e){return false;}});}
        catch(AssertionError e){System.err.println("STATUS "+bridge.playerStatus(id).get()+" BOT "+bridge.overallStatus().get()+" ACCEPTED "+irc.accepted+" COMMANDS "+irc.commands);throw e;}
    }
    @Test void zeroPlayersBotAndTwentyRealConnectionsDeduplicateByIdentity() throws Exception {
        connect();await(()->{try{return bridge.overallStatus().get().bot()==Bridge.Phase.JOINED;}catch(Exception e){return false;}});irc.external("Alice","!list");await(()->irc.sent.stream().anyMatch(m->m.text().equals("在线人数：0/37")));
        List<UUID> ids=new ArrayList<>();for(int i=0;i<20;i++){UUID id=UUID.randomUUID();ids.add(id);bridge.join(id,"Player"+i).get();}
        await(()->irc.members()==21);irc.external("Alice","相同的消息😀");irc.external("Bob","相同的消息😀");
        await(()->ids.stream().allMatch(id->port.count(id,"相同的消息😀")==2));
        irc.external("MC_External","外部前缀用户");await(()->ids.stream().allMatch(id->port.count(id,"外部前缀用户")==1));
        UUID a=ids.getFirst();bridge.chat(a,"本地普通发言");bridge.chat(a,"本地普通发言");
        await(()->irc.sent.stream().filter(m->m.nick().equals("MC_Player0")&&m.text().equals("本地普通发言")).count()==2);
        Thread.sleep(150);assertTrue(port.deliveries.stream().noneMatch(d->d.text().contains("本地普通发言")));
        irc.ping();await(()->irc.pong.get()>=21);
    }
    @Test void managedPlayerQueriesWorkAndBotEchoDoesNotRepeatReplies() throws Exception {
        irc.echo=true;connect();UUID id=UUID.randomUUID();joined(id,"Steve");await(()->irc.members()==2);
        bridge.chat(id,"!list");await(()->port.count(id,"在线人数：0/37")==1);
        assertTrue(irc.sent.stream().anyMatch(m->m.nick().equals("MC_Steve")&&m.text().equals("!list")));
        Thread.sleep(150);bridge.chat(id,"!status");await(()->port.count(id,"TPS（1 分钟）：19.98")==1);
        Thread.sleep(150);bridge.chat(id,"!help");await(()->port.count(id,"!status：人数")==1);
        Thread.sleep(150);assertEquals(1,port.count(id,"在线人数：0/37"));
        assertTrue(port.deliveries.stream().noneMatch(d->d.text().matches(".*(内存|版本|UUID).*")));
    }
    @Test void offRemainsOffThroughReconnectReloadAndUuidRelogin() throws Exception {
        connect();UUID a=UUID.randomUUID(),b=UUID.randomUUID();joined(a,"Steve");joined(b,"Alex");await(()->irc.members()==3);
        bridge.enabled(a,false).get();await(()->irc.members()==2);assertFalse(saved.get(a).enabled());
        bridge.reconnect().get();await(()->irc.members()==2);
        bridge.reload(TestSettings.settings(irc.port())).get();bridge.quit(a).get();bridge.join(a,"NewName").get();
        Thread.sleep(300);assertEquals(2,irc.members());irc.external("Alice","off过滤");await(()->port.count(b,"off过滤")==1);assertEquals(0,port.count(a,"off过滤"));
        bridge.chat(a,"不能转发");Thread.sleep(150);assertFalse(irc.sent.stream().anyMatch(m->m.text().equals("不能转发")));
        bridge.nickname(a,"Stored").get();bridge.enabled(a,true).get();await(()->irc.memberNames().contains("MC_Stored"));
        bridge.quit(a).get();bridge.join(a,"ChangedName").get();await(()->irc.memberNames().contains("MC_Stored"));
        bridge.nickname(a,null).get();await(()->irc.memberNames().contains("MC_ChangedName"));
    }
    @Test void collisionsUseRfcCaseAndOnlineRenameWaitsForConfirmation() throws Exception {
        irc.occupy("mc_steve");irc.occupy("mcbot");connect();UUID id=UUID.randomUUID();joined(id,"Steve");
        await(()->irc.members()==2);String first=bridge.playerStatus(id).get().actualNick();
        assertTrue(first.matches("MC_Steve_[0-9a-f]{4}"));assertTrue(bridge.overallStatus().get().botNick().startsWith("MCBot_"));
        assertNull(saved.get(id));irc.occupy("mc_alex");bridge.nickname(id,"Alex").get();
        await(()->irc.memberNames().stream().anyMatch(n->n.startsWith("MC_Alex_")));
        String second=bridge.playerStatus(id).get().actualNick();assertEquals("Alex",saved.get(id).nicknameBase());
        irc.rejectRenames=true;bridge.nickname(id,"Rejected").get();await(()->port.feedback.stream().anyMatch(t->t.contains("改名失败")));
        assertEquals(second,bridge.playerStatus(id).get().actualNick());assertEquals(2,irc.members());
    }
    @Test void networkDisconnectDropsOldBuffersAndOffCancelsRetries() throws Exception {
        var y=TestSettings.yaml(irc.port());y.set("flood.lines-per-second",0.1);connect(Settings.from(y),Map.of(),tls.client.getSocketFactory());
        UUID id=UUID.randomUUID();joined(id,"Steve");bridge.chat(id,"首先");await(()->irc.sent.stream().anyMatch(m->m.text().equals("首先")));
        bridge.chat(id,"断线队列不补发");bridge.playerStatus(id).get();irc.disconnect("MC_Steve");
        await(()->port.feedback.stream().anyMatch(t->t.contains("连接失败")));await(()->irc.memberNames().contains("MC_Steve"));
        Thread.sleep(200);assertFalse(irc.sent.stream().anyMatch(m->m.text().equals("断线队列不补发")));
        bridge.enabled(id,false).get();await(()->irc.members()==1);int n=irc.accepted.get();Thread.sleep(800);assertEquals(n,irc.accepted.get());
    }
    @Test void quickTogglesWhileRegistrationPendingCannotCreateGhostConnections() throws Exception {
        irc.holdRegistration=true;connect();UUID id=UUID.randomUUID();bridge.join(id,"Steve").get();
        for(int i=0;i<10;i++){bridge.enabled(id,false).get();bridge.enabled(id,true).get();bridge.nickname(id,"Alex"+i).get();}
        bridge.enabled(id,false).get();irc.holdRegistration=false;
        bridge.reconnect().get();await(()->irc.members()==1);Thread.sleep(300);
        assertEquals(1,irc.members());assertEquals(Bridge.Phase.DISABLED,bridge.playerStatus(id).get().phase());
        bridge.enabled(id,true).get();await(()->irc.memberNames().contains("MC_Alex9"));assertEquals(2,irc.members());
    }
    @Test void joinDeniedAndActualNickLengthAreExplicitFailuresWithBackoff() throws Exception {
        irc.denyJoin=true;connect();await(()->port.logs.stream().anyMatch(t->t.contains("474")));
        assertTrue(irc.accepted.get()<10);assertEquals(0,irc.members());
        irc.denyJoin=false;irc.nickLength=9;UUID id=UUID.randomUUID();bridge.join(id,"LongPlayerName").get();
        await(()->port.feedback.stream().anyMatch(t->t.contains("昵称")&&t.contains("上限")));
        assertFalse(irc.memberNames().contains("MC_LongPlayerName"));
    }
    @Test void botOutageIsPartialAvailabilityAndRecoveryIsAnnouncedOnce() throws Exception {
        connect();UUID id=UUID.randomUUID();joined(id,"Steve");await(()->irc.members()==2);
        irc.disconnect("MCBot");await(()->port.count(id,"机器人暂不可用")>=1);
        bridge.chat(id,"机器人断线仍发言");await(()->irc.sent.stream().anyMatch(m->m.nick().equals("MC_Steve")&&m.text().equals("机器人断线仍发言")));
        await(()->port.count(id,"机器人已恢复")==1);assertEquals(1,port.count(id,"机器人暂不可用"));
    }
    @Test void unicodeLongTextAndCtcpPrivateMessagesUseSafePaths() throws Exception {
        connect();UUID id=UUID.randomUUID();joined(id,"Steve");await(()->irc.members()==2);
        String text="中文😀 <click:run_command:'/op Steve'> ".repeat(60);
        bridge.chat(id,text);await(()->irc.sent.stream().filter(m->m.nick().equals("MC_Steve")).map(FakeIrcServer.Sent::text).collect(java.util.stream.Collectors.joining()).length()>=text.length());
        assertEquals(text,irc.sent.stream().filter(m->m.nick().equals("MC_Steve")).map(FakeIrcServer.Sent::text).collect(java.util.stream.Collectors.joining()));
        irc.external("Alice","\u0001ACTION 不同步\u0001");Thread.sleep(150);assertEquals(0,port.count(id,"不同步"));
    }
    @Test void renameTimeoutReconcilesAndRetainsVerifiedOldIdentity() throws Exception {
        var y=TestSettings.yaml(irc.port());y.set("timeouts.nickname-seconds",1);
        connect(Settings.from(y),Map.of(),tls.client.getSocketFactory());UUID id=UUID.randomUUID();joined(id,"Steve");
        irc.ignoreRenames=true;bridge.nickname(id,"Ignored").get();
        await(()->port.feedback.stream().anyMatch(t->t.contains("原昵称仍有效")));
        assertEquals("MC_Steve",bridge.playerStatus(id).get().actualNick());assertEquals(Bridge.Phase.JOINED,bridge.playerStatus(id).get().phase());
        assertTrue(irc.commands.stream().anyMatch(c->c.equals("WHOIS MC_Steve")));
    }
    @Test void prefixReloadPreservesSavedBaseAndNeverSilentlyTruncates() throws Exception {
        var y=TestSettings.yaml(irc.port());y.set("irc.player-prefix","TAG_");
        connect(Settings.from(y),Map.of(),tls.client.getSocketFactory());UUID id=UUID.randomUUID();bridge.join(id,"Steve").get();
        await(()->irc.memberNames().contains("TAG_Steve"));bridge.nickname(id,"A".repeat(23)).get();await(()->irc.memberNames().contains("TAG_"+"A".repeat(23)));
        assertThrows(ExecutionException.class,()->bridge.nickname(id,"A".repeat(24)).get());
        y.set("irc.player-prefix","Z_");bridge.reload(Settings.from(y)).get();await(()->irc.memberNames().contains("Z_"+"A".repeat(23)));
        assertEquals("A".repeat(23),saved.get(id).nicknameBase());
    }
    @Test void ownerRenameAndOffBeforeBotDeliveryDoNotRebroadcastOldGameChat() throws Exception {
        connect();UUID a=UUID.randomUUID(),b=UUID.randomUUID();joined(a,"Steve");joined(b,"Alex");
        await(()->{try{return bridge.overallStatus().get().bot()==Bridge.Phase.JOINED;}catch(Exception e){return false;}});
        irc.holdBotMessages=true;bridge.chat(a,"改名前已发到IRC");await(()->irc.sent.stream().anyMatch(m->m.text().equals("改名前已发到IRC")));
        bridge.nickname(a,"Renamed").get();await(()->{try{return bridge.playerStatus(a).get().actualNick().equals("MC_Renamed");}catch(Exception e){return false;}});
        irc.releaseBotMessages();Thread.sleep(150);assertEquals(0,port.count(b,"改名前已发到IRC"));
        irc.holdBotMessages=true;bridge.chat(a,"关闭前已发到IRC");await(()->irc.sent.stream().anyMatch(m->m.text().equals("关闭前已发到IRC")));
        bridge.enabled(a,false).get();await(()->!irc.memberNames().contains("MC_Renamed"));irc.releaseBotMessages();
        Thread.sleep(150);assertEquals(0,port.count(b,"关闭前已发到IRC"));
        irc.external("MC_Renamed","外部复用旧昵称");await(()->port.count(b,"外部复用旧昵称")==1);
    }
}
