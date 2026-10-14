package cn.paperirc.irc;

import cn.paperirc.config.Settings;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One real TLS client. Network I/O is confined to virtual threads, never the Paper thread. */
public final class IrcSession {
    public interface Listener {
        boolean connected(IrcSession session);
        boolean message(IrcSession session,IrcMessage message);
        void disconnected(IrcSession session,String reason);
    }
    private record Frame(String line,boolean quit) {}
    private final Settings.Connection config;
    private volatile Settings.Flood flood;
    private final SSLSocketFactory tls;
    private final ExecutorService io;
    private final ScheduledExecutorService timers;
    private final Listener listener;
    private final AtomicBoolean closed=new AtomicBoolean(),closing=new AtomicBoolean(),reported=new AtomicBoolean();
    private final AtomicReference<Socket> socket=new AtomicReference<>();
    private final BlockingQueue<Frame> control=new ArrayBlockingQueue<>(32);
    private final ArrayDeque<Frame> chat=new ArrayDeque<>();
    private volatile long received=System.nanoTime(),pingSent;
    private volatile String pingToken;
    private volatile OutputStream output;
    private volatile boolean started;
    private final CompletableFuture<Void> termination=new CompletableFuture<>();
    private final AtomicInteger workers=new AtomicInteger();
    private volatile long writeStarted;
    private ScheduledFuture<?> watchdog;

    public IrcSession(Settings.Connection config,Settings.Flood flood,SSLSocketFactory tls,
                      ExecutorService io,ScheduledExecutorService timers,Listener listener) {
        this.config=config;this.flood=flood;this.tls=tls;this.io=io;this.timers=timers;this.listener=listener;
    }
    public void start() {
        workers.incrementAndGet();
        io.execute(()->{
            try {
                Socket raw=new Socket();
                socket.set(raw);
                if(closing.get()||closed.get()) {raw.close();return;}
                raw.connect(new InetSocketAddress(config.host(),config.port()),config.connectSeconds()*1000);
                if(closing.get()||closed.get())return;
                SSLSocket secure=(SSLSocket)tls.createSocket(raw,config.host(),config.port(),true);
                socket.set(secure);
                if(closing.get()||closed.get())return;
                SSLParameters parameters=secure.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);
                secure.setSoTimeout(config.handshakeSeconds()*1000);
                secure.startHandshake(); // default JVM trust store; no permissive TrustManager
                if(closing.get()||closed.get())return;
                secure.setSoTimeout(1000);secure.setTcpNoDelay(true);
                output=secure.getOutputStream();started=true;
                workers.incrementAndGet();io.execute(this::writer);
                watchdog=timers.scheduleAtFixedRate(()->{
                    long at=writeStarted;
                    if(at!=0&&System.nanoTime()-at>TimeUnit.SECONDS.toNanos(config.pongSeconds()))
                        fail("IRC 写入超时，发送缓冲已清空");
                },1,1,TimeUnit.SECONDS);
                if(!listener.connected(this))throw new IOException("内部处理队列已满，连接暂停");
                read(secure);
            } catch(Exception e) {
                if(!closing.get()&&!closed.get())fail(describe(e));
            } finally {
                if(!started||!closing.get())terminate();
                workerFinished();
            }
        });
    }
    private void read(SSLSocket secure) throws IOException {
        LineReader reader=new LineReader(new BufferedInputStream(secure.getInputStream()));
        while(!closed.get()&&!closing.get()) {
            try {
                String line=reader.read();
                if(line==null)throw new EOFException("IRC 服务器关闭连接");
                // RFC 2812 §2.3.1: empty messages are silently ignored. Still check
                // liveness below, so empty frames cannot mask a pending PONG timeout.
                if(!line.isEmpty()) {
                    received=System.nanoTime();
                    IrcMessage message=IrcMessage.parse(line);
                    if(message.command().equals("PING")) {
                        control("PONG :"+message.trailing());
                    } else if(message.command().equals("PONG")&&Objects.equals(message.trailing(),pingToken)) {
                        pingToken=null;pingSent=0;
                    } else if(!listener.message(this,message)) {
                        throw new IOException("内部入站队列已满，连接暂停");
                    }
                }
            } catch(SocketTimeoutException e) { /* Keep partial CRLF frame; check liveness below. */ }
            long now=System.nanoTime();
            if(pingToken!=null && now-pingSent>TimeUnit.SECONDS.toNanos(config.pongSeconds()))
                throw new SocketTimeoutException("IRC PONG 超时");
            if(pingToken==null && now-received>TimeUnit.SECONDS.toNanos(config.idleSeconds())) {
                pingToken="paperirc-"+Long.toUnsignedString(now,36);pingSent=now;control("PING :"+pingToken);
            }
        }
    }
    private void writer() {
        long nextChat=0;
        try {
            while(!closed.get()) {
                Frame frame=control.poll(25,TimeUnit.MILLISECONDS);
                if(frame==null&&!closing.get()&&System.nanoTime()>=nextChat) {
                    synchronized(chat) {frame=chat.pollFirst();}
                    if(frame!=null)nextChat=System.nanoTime()+(long)(1e9/flood.linesPerSecond());
                }
                if(frame==null)continue;
                writeStarted=System.nanoTime();
                output.write((frame.line()+"\r\n").getBytes(StandardCharsets.UTF_8));output.flush();
                writeStarted=0;
                if(frame.quit())break;
            }
        } catch(Exception e) { if(!closing.get()&&!closed.get())fail("IRC 发送失败："+describe(e)); }
        finally {terminate();workerFinished();}
    }
    public boolean control(String line) {
        validate(line);
        if(closed.get()||closing.get())return false;
        boolean ok=control.offer(new Frame(line,false));if(!ok)fail("IRC 协议队列已满");return ok;
    }
    public boolean sendChat(String channel,List<String> parts) {
        List<Frame> frames=new ArrayList<>();
        for(String part:parts) {String line="PRIVMSG "+channel+" :"+part;validate(line);frames.add(new Frame(line,false));}
        synchronized(chat) {
            if(!started||closed.get()||closing.get()||chat.size()+frames.size()>flood.bufferLines())return false;
            chat.addAll(frames);return true;
        }
    }
    public void updateFlood(Settings.Flood next) { flood=next; }
    public boolean isOpen() {return started&&!closing.get()&&!closed.get();}
    public void close(String reason) {
        if(closing.getAndSet(true))return;
        synchronized(chat) {chat.clear();}
        control.clear();
        if(started&&!closed.get()) {
            control.offer(new Frame("QUIT :"+ChatText.clean(reason),true));
            try {timers.schedule(this::terminate,200,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException e){terminate();}
        } else terminate();
    }
    public void fail(String reason) {
        terminate();
        if(!closing.get()&&reported.compareAndSet(false,true))listener.disconnected(this,reason);
    }
    private void terminate() {
        closed.set(true);Socket s=socket.getAndSet(null);
        if(s!=null)try{s.close();}catch(IOException ignored){}
        synchronized(chat){chat.clear();}control.clear();
        ScheduledFuture<?> task=watchdog;if(task!=null)task.cancel(false);
        if(workers.get()==0)termination.complete(null);
    }
    private void workerFinished() {
        if(workers.decrementAndGet()==0) {
            ScheduledFuture<?> task=watchdog;if(task!=null)task.cancel(false);
            termination.complete(null);
        }
    }
    public CompletableFuture<Void> termination() {return termination;}
    private static void validate(String line) {
        if(line.indexOf('\r')>=0||line.indexOf('\n')>=0||line.indexOf('\0')>=0||ChatText.bytes(line)+2>512)
            throw new IllegalArgumentException("IRC 出站帧非法或超过 512 字节");
    }
    public static String describe(Throwable e) {
        if(e instanceof SSLException)return "TLS 验证/握手失败（检查证书链、有效期和主机名）："+e.getMessage();
        if(e instanceof UnknownHostException)return "无法解析 IRC 主机："+e.getMessage();
        String message=e.getMessage();return e.getClass().getSimpleName()+(message==null?"":"："+ChatText.clean(message));
    }
}
