import cn.paperirc.config.Settings;
import cn.paperirc.irc.IrcMessage;
import cn.paperirc.irc.IrcSession;
import cn.paperirc.irc.Nicknames;
import javax.net.ssl.SSLSocketFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Run with Java 25 and the plugin JAR on the classpath. No JOIN or PRIVMSG is sent. */
class IrcRegistrationProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: IrcRegistrationProbe.java <TLS hostname> <port> <temporary nickname>");
        String host=args[0],nick=args[2];int port=Integer.parseInt(args[1]);
        Nicknames.validateFull(nick,32);
        var connection=new Settings.Connection(host,port,"#unused",nick,"MC_",32,6,10,10,30,15,10,90,20,1,60,0.2,30,2);
        var flood=new Settings.Flood(2,64,8192,64,200,2048);
        ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<String> result=new CompletableFuture<>();
        AtomicBoolean registered=new AtomicBoolean();AtomicInteger motd=new AtomicInteger();
        IrcSession session=new IrcSession(connection,flood,(SSLSocketFactory)SSLSocketFactory.getDefault(),io,timer,new IrcSession.Listener(){
            public boolean connected(IrcSession s) {
                System.out.println("TLS_VERIFIED: default JVM trust store, hostname verification enabled");
                return s.control("NICK "+nick)&&s.control("USER pidiag 0 * :PaperIRC connection diagnostic");
            }
            public boolean message(IrcSession s,IrcMessage m) {
                switch(m.command()) {
                    case "001" -> {registered.set(true);System.out.println("REGISTERED: 001");}
                    case "005" -> m.params().stream().filter(p->p.startsWith("NICKLEN=")||p.startsWith("CASEMAPPING=")).forEach(System.out::println);
                    case "372" -> motd.incrementAndGet();
                    case "376","422" -> result.complete(registered.get()?"PASS: TLS and registration completed; MOTD lines="+motd.get():"FAIL: MOTD ended without 001");
                    case "ERROR","432","433","436","437","464","465" -> result.complete("FAIL: IRC "+m.command());
                    default -> {}
                }
                return true;
            }
            public void disconnected(IrcSession s,String reason) {result.complete("FAIL: "+reason);}
        });
        String outcome;
        try {
            session.start();outcome=result.get(25,TimeUnit.SECONDS);System.out.println(outcome);
        } finally {
            session.close("PaperIRC diagnostic complete");
            try {session.termination().get(3,TimeUnit.SECONDS);}
            finally {io.shutdownNow();timer.shutdownNow();}
        }
        if(!outcome.startsWith("PASS:"))throw new IllegalStateException("Registration probe failed");
    }
}
