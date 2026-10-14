package cn.paperirc.irc;

import cn.paperirc.testing.*;
import org.junit.jupiter.api.*;
import javax.net.ssl.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TlsSessionTest {
    private static TlsFixture fixture;
    @BeforeAll static void certificates() throws Exception {fixture=TlsFixture.get();}
    private String connect(SSLContext serverContext,SSLSocketFactory client,boolean success) throws Exception {
        try(FakeIrcServer server=new FakeIrcServer(serverContext)) {
            ExecutorService io=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("session-test-",0).factory());
            ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
            CompletableFuture<String> event=new CompletableFuture<>();
            var settings=TestSettings.settings(server.port());
            IrcSession session=new IrcSession(settings.connection(),settings.flood(),client,io,timer,new IrcSession.Listener(){
                @Override public boolean connected(IrcSession s){event.complete("CONNECTED");return true;}
                @Override public boolean message(IrcSession s,IrcMessage m){return true;}
                @Override public void disconnected(IrcSession s,String reason){event.complete(reason);}
            });
            try {
                session.start();String result=event.get(5,TimeUnit.SECONDS);
                if(success)assertEquals("CONNECTED",result);else {assertTrue(result.contains("TLS"),result);assertTrue(server.commands.isEmpty());}
                return result;
            }finally {session.close("test complete");session.termination().get(2,TimeUnit.SECONDS);io.shutdownNow();timer.shutdownNow();}
        }
    }
    @Test void isolatedTrustedCaConnectsWithHostnameVerification() throws Exception {
        connect(fixture.valid,fixture.client.getSocketFactory(),true);
    }
    @Test void defaultJvmTrustRejectsTestCaAndNeverSendsPlaintext() throws Exception {
        connect(fixture.valid,(SSLSocketFactory)SSLSocketFactory.getDefault(),false);
    }
    @Test void mismatchedHostnameFailsEvenWithTrustedCa() throws Exception {
        assertTrue(connect(fixture.wrongHost,fixture.client.getSocketFactory(),false).contains("127.0.0.1"));
    }
    @Test void expiredLeafFailsEvenWithTrustedCa() throws Exception {
        String reason=connect(fixture.expired,fixture.client.getSocketFactory(),false);assertTrue(reason.contains("TLS"));
    }
    @Test void applicationQueueRejectsWholeMessageInsteadOfTruncating() throws Exception {
        try(FakeIrcServer server=new FakeIrcServer(fixture.valid)) {
            ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
            CompletableFuture<Void> connected=new CompletableFuture<>();var y=TestSettings.yaml(server.port());y.set("flood.send-buffer-lines",4);var s=cn.paperirc.config.Settings.from(y);
            IrcSession session=new IrcSession(s.connection(),s.flood(),fixture.client.getSocketFactory(),io,timer,new IrcSession.Listener(){
                public boolean connected(IrcSession s){connected.complete(null);return true;}
                public boolean message(IrcSession s,IrcMessage m){return true;}
                public void disconnected(IrcSession s,String e){}
            });
            try {session.start();connected.get(5,TimeUnit.SECONDS);assertFalse(session.sendChat("#minecraft",java.util.Collections.nCopies(5,"abc")));assertTrue(server.sent.isEmpty());}
            finally{session.close("done");session.termination().get(2,TimeUnit.SECONDS);io.shutdownNow();timer.shutdownNow();}
        }
    }
}
