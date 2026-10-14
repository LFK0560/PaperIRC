package cn.paperirc.core;

import cn.paperirc.config.Settings;
import cn.paperirc.irc.*;
import cn.paperirc.persistence.*;
import javax.net.ssl.SSLSocketFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** All mutable IRC and identity state belongs to one actor. No Paper API is called here. */
public final class Bridge implements AutoCloseable {
    public interface GamePort {
        void tell(UUID player,String text);
        void deliver(Set<UUID> recipients,String text);
        void log(boolean error,String text);
    }
    public enum Phase {
        DISABLED("关闭"), CONNECTING("连接中"), REGISTERING("注册中"), JOINING("加入频道中"),
        JOINED("已加入频道"), RECONNECTING("重连中"), FAILED("失败");
        private final String label;Phase(String label){this.label=label;}public String label(){return label;}
    }
    public record PlayerStatus(boolean enabled,Phase phase,String savedBase,String actualNick,String error,int nicknameLimit,boolean inboundAvailable) {}
    public record OverallStatus(Phase bot,String botNick,int playerConnections,int joinedPlayers,int reconnecting,String error,boolean active) {}
    private static final UUID BOT_ID=new UUID(0,0);
    private static final class Slot {
        final UUID id; final boolean bot;
        String gameName,actual="",desired="",candidate="",error="",notifiedError="",deferredNick="";
        Phase phase=Phase.DISABLED;
        IrcSession session;
        CaseMapping mapping=CaseMapping.RFC1459;
        int nickLength,collision,retries;
        boolean welcomed,joinSent,renaming,reconciling;
        long retryAt,deadline,joinedAt;
        Slot(UUID id,String name,boolean bot){this.id=id;this.gameName=name;this.bot=bot;}
    }
    private record Origin(UUID id,IrcSession session) {}
    private record Member(String prefix,Origin origin) {}
    private final GamePort game;
    private final Function<UUID,Function<PlayerPreference,CompletableFuture<Void>>> persist;
    private final Supplier<ServerSnapshot> snapshot;
    private final SSLSocketFactory tls;
    private final ScheduledThreadPoolExecutor actor=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("PaperIRC-state").daemon(true).factory());
    private final ScheduledThreadPoolExecutor timers=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().name("PaperIRC-timers").daemon(true).factory());
    private final ExecutorService io=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("PaperIRC-io-",0).factory());
    private final Map<UUID,PlayerPreference> preferences;
    private final Map<UUID,Slot> players=new HashMap<>();
    private final Map<UUID,CompletableFuture<Void>> retiring=new HashMap<>();
    // Ingress ownership follows the bot's ordered JOIN/NICK/QUIT stream, independently of owner callbacks.
    private final Map<String,Member> members=new HashMap<>();
    private final Map<String,ArrayDeque<Origin>> joinProofs=new HashMap<>();
    private final Map<String,Origin> whoOwners=new HashMap<>();
    private boolean botInChannel,whoSent;
    private final Slot bot=new Slot(BOT_ID,"MCBot",true);
    private final QueryHandler queries=new QueryHandler();
    private final LinkedHashMap<String,Long> logs=new LinkedHashMap<>();
    private final Map<UUID,Long> chatFeedback=new HashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicInteger queued=new AtomicInteger();
    private volatile Settings settings;
    private final ScheduledFuture<?> tick;
    private long nextConnect;
    private int advertisedNickLength;
    private String lastError="";
    private boolean botOutage;

    public Bridge(Settings settings,Map<UUID,PlayerPreference> preferences,GamePort game,
                  BiFunction<UUID,PlayerPreference,CompletableFuture<Void>> persist,Supplier<ServerSnapshot> snapshot) {
        this(settings,preferences,game,persist,snapshot,(SSLSocketFactory)SSLSocketFactory.getDefault());
    }
    /** The alternate factory exists for isolated test trust stores, not for plugin configuration. */
    public Bridge(Settings settings,Map<UUID,PlayerPreference> preferences,GamePort game,
                  BiFunction<UUID,PlayerPreference,CompletableFuture<Void>> persist,Supplier<ServerSnapshot> snapshot,SSLSocketFactory tls) {
        this.settings=settings;this.preferences=new HashMap<>(preferences);this.game=game;
        this.persist=id->pref->persist.apply(id,pref);this.snapshot=snapshot;this.tls=tls;
        actor.setRemoveOnCancelPolicy(true);timers.setRemoveOnCancelPolicy(true);
        actor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        if(settings.active())activate(bot);else game.log(false,settings.inactiveReason());
        tick=actor.scheduleAtFixedRate(()->{
            if(!closed.get())try{pulse();}catch(Exception e){game.log(true,"IRC 状态处理失败："+IrcSession.describe(e));}
        },0,100,TimeUnit.MILLISECONDS);
    }
    public Settings settings(){return settings;}
    private boolean dispatch(Runnable task) {
        if(closed.get())return false;
        if(queued.incrementAndGet()>settings.flood().eventBuffer()){queued.decrementAndGet();return false;}
        try {actor.execute(()->{try{if(!closed.get())task.run();}finally{queued.decrementAndGet();}});return true;}
        catch(RejectedExecutionException e){queued.decrementAndGet();return false;}
    }
    private <T> CompletableFuture<T> call(Supplier<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();
        if(!dispatch(()->{try{result.complete(action.get());}catch(Exception e){result.completeExceptionally(e);}}))
            result.completeExceptionally(new IllegalStateException("IRC 正在关闭或处理队列已满，请稍后重试"));
        return result;
    }
    public CompletableFuture<Void> join(UUID id,String name) {return call(()->{
        Slot old=players.remove(id);if(old!=null)stop(old,"重新登录");
        Slot slot=new Slot(id,name,false);players.put(id,slot);
        if(preference(id).enabled()&&settings.active())activate(slot);
        return null;
    });}
    public CompletableFuture<Void> quit(UUID id) {return call(()->{
        Slot slot=players.remove(id);if(slot!=null)stop(slot,"玩家下线");chatFeedback.remove(id);return null;
    });}
    private PlayerPreference preference(UUID id){return preferences.getOrDefault(id,PlayerPreference.DEFAULT);}
    public CompletableFuture<PlayerStatus> playerStatus(UUID id){return call(()->{
        Slot s=players.get(id);PlayerPreference p=preference(id);
        return new PlayerStatus(p.enabled(),s==null?Phase.DISABLED:s.phase,p.nicknameBase(),s==null?"":s.actual,
                s==null?"":s.error,nickLimit(s),bot.phase==Phase.JOINED);
    });}
    public CompletableFuture<OverallStatus> overallStatus(){return call(()->new OverallStatus(bot.phase,bot.actual,
            (int)players.values().stream().filter(s->s.session!=null).count(),
            (int)players.values().stream().filter(s->s.phase==Phase.JOINED).count(),
            (int)allSlots().stream().filter(s->s.phase==Phase.RECONNECTING||s.phase==Phase.FAILED).count(),lastError,settings.active()));}
    public CompletableFuture<String> enabled(UUID id,boolean enable) {
        return call(()->{
            PlayerPreference old=preference(id);PlayerPreference next=new PlayerPreference(enable,old.nicknameBase());
            preferences.put(id,next);Slot slot=players.get(id);
            if(slot!=null) {
                if(!enable)stop(slot,"玩家关闭 IRC");
                else if(settings.active()&&slot.phase==Phase.DISABLED)activate(slot);
            }
            String suffix=enable?(settings.active()?"；正在异步连接，完成频道加入后可同步聊天":"；"+settings.inactiveReason()):"；个人连接已关闭，本地聊天正常";
            return save(id,next).thenApply(v->"IRC "+(enable?"开启":"关闭")+"设置已保存"+suffix);
        }).thenCompose(Function.identity());
    }
    public CompletableFuture<String> nickname(UUID id,String base) {
        return call(()->{
            Slot s=players.get(id);if(s==null)throw new IllegalArgumentException("仅在线玩家可修改个人昵称");
            String effective=base==null?s.gameName:base;
            Nicknames.validateBase(effective,settings.connection().prefix(),nickLimit(s));
            if(s.renaming)throw new IllegalArgumentException("上一次改名正在等待 IRC 服务器确认，请稍后再试");
            PlayerPreference next=new PlayerPreference(preference(id).enabled(),base);preferences.put(id,next);
            boolean live=s.phase==Phase.JOINED;
            if(live&&botInChannel&&bot.phase==Phase.JOINING)s.deferredNick=settings.connection().prefix()+effective;
            else if(live)requestNick(s,settings.connection().prefix()+effective,true);
            else if(next.enabled()&&settings.active()) {stop(s,"更新待连接昵称");activate(s);}
            return save(id,next).thenApply(v->(base==null?"已保存默认昵称规则":"已保存昵称主体："+base)
                    + (live?"；通过现有连接申请，机器人核对身份期间会短暂等待；以服务器确认结果为准":"；下次连接时应用，前缀为 "+settings.connection().prefix()));
        }).thenCompose(Function.identity());
    }
    private CompletableFuture<Void> save(UUID id,PlayerPreference next) {
        return persist.apply(id).apply(next).exceptionallyCompose(error->{
            String reason="玩家设置保存失败："+rootMessage(error)+"；本次会话已应用，但重启后可能恢复旧设置";
            game.log(true,reason);return CompletableFuture.failedFuture(new IllegalStateException(reason,error));
        });
    }
    public CompletableFuture<String> reload(Settings next) {return call(()->{
        boolean rebuild=settings.active()!=next.active()||!settings.connection().equals(next.connection());
        boolean queryChanged=!settings.queries().equals(next.queries());
        settings=next;if(queryChanged)queries.clear();
        if(rebuild) {
            advertisedNickLength=0;nextConnect=0;
            for(Slot s:allSlots())stop(s,"配置重载");
            botOutage=false;
            if(next.active()) {
                activate(bot);for(Slot s:players.values())if(preference(s.id).enabled())activate(s);
            } else game.log(false,next.inactiveReason());
        } else for(Slot s:allSlots())if(s.session!=null)s.session.updateFlood(next.flood());
        return "配置校验通过，已重载"+(rebuild?"并按建连限速更新连接":"；格式及限流参数已生效")+(next.active()?"":"；"+next.inactiveReason());
    });}
    public CompletableFuture<String> reconnect(){return call(()->{
        if(!settings.active())return settings.inactiveReason();
        for(Slot s:allSlots()) {stop(s,"管理员手动重连");if(s.bot||preference(s.id).enabled())activate(s);}
        nextConnect=0;return "已安排机器人和在线且开启 IRC 的玩家重连；个人设置保持不变";
    });}
    public boolean chat(UUID id,String text) {
        return dispatch(()->{
            Slot s=players.get(id);if(s==null)return;
            boolean query=QueryHandler.isQuery(text);
            if(!preference(id).enabled()||s.phase!=Phase.JOINED||s.session==null) {
                if(query)feedback(id,"当前个人 IRC 不可用，查询未发送；使用 /irc status 查看状态");return;
            }
            try {
                List<String> parts=parts(text,s);
                if(!s.session.sendChat(settings.connection().channel(),parts))
                    feedback(id,"IRC 发送缓冲已满或连接已中断，本条消息未发送；本地聊天正常");
                else if(query&&bot.phase!=Phase.JOINED)feedback(id,"请求已用个人身份发送，但机器人尚未就绪，查询暂不可用");
            } catch(IllegalArgumentException e){feedback(id,e.getMessage());}
        });
    }
    private List<String> parts(String text,Slot slot) {
        Settings.Flood f=settings.flood();
        // RFC 2812 host <=63 bytes, username <=10. Larger server-specific prefixes can be configured.
        int reserve=Math.max(f.prefixReserveBytes(),ChatText.bytes(slot.actual)+80);
        return ChatText.split(text,settings.connection().channel(),reserve,f.maxCodepoints(),f.maxParts());
    }
    private void feedback(UUID id,String text) {
        long now=System.nanoTime();
        if(now-chatFeedback.getOrDefault(id,Long.MIN_VALUE/2)>=(long)(settings.queries().feedbackSeconds()*1e9)) {
            chatFeedback.put(id,now);game.tell(id,text);
        }
    }
    private Set<UUID> recipients() {
        Set<UUID> ids=new HashSet<>();for(UUID id:players.keySet())if(preference(id).enabled())ids.add(id);return Set.copyOf(ids);
    }
    private List<Slot> allSlots(){List<Slot> result=new ArrayList<>();result.add(bot);result.addAll(players.values());return result;}
    private int nickLimit(Slot s) {
        return s!=null&&s.nickLength>0?s.nickLength:advertisedNickLength>0?advertisedNickLength:settings.connection().fallbackNickLength();
    }
    private void activate(Slot s) {
        s.phase=Phase.CONNECTING;s.retryAt=System.nanoTime();s.retries=0;s.error="";s.notifiedError="";
        if(!s.bot)game.tell(s.id,"IRC 正在异步连接；完成注册和频道加入后可同步聊天");
    }
    private void stop(Slot s,String reason) {
        if(s.bot)clearIngress();
        IrcSession old=s.session;s.session=null;
        if(old!=null){retiring.put(s.id,old.termination());old.close(reason);}
        s.actual="";s.phase=Phase.DISABLED;s.deadline=0;s.renaming=false;s.reconciling=false;s.welcomed=false;s.joinSent=false;s.error="";s.deferredNick="";
    }
    private void pulse() {
        long now=System.nanoTime();
        retiring.entrySet().removeIf(e->e.getValue().isDone());
        for(Slot s:allSlots()) {
            if(s.session!=null&&s.deadline>0&&now>=s.deadline) {
                if(s.renaming) {
                    if(!s.reconciling) {
                        // Numeric replies include the requesting connection's current nick. Probe only this identity.
                        s.reconciling=true;s.deadline=now+TimeUnit.SECONDS.toNanos(settings.connection().nickSeconds());
                        s.session.control("WHOIS "+s.actual);
                    } else failure(s,s.session,"改名与身份核对均超时，不能确认实际昵称；将重新注册身份");
                } else failure(s,s.session,"IRC "+s.phase.label()+"超时");
            }
        }
        if(botInChannel&&bot.phase==Phase.JOINING&&!whoSent&&players.values().stream().noneMatch(s->s.renaming))beginWho();
        if(!settings.active()||now<nextConnect)return;
        Slot next=allSlots().stream().filter(s->s.session==null&&!retiring.containsKey(s.id)&&s.phase!=Phase.DISABLED&&s.retryAt<=now)
                .min(Comparator.comparingLong(s->s.retryAt)).orElse(null);
        if(next!=null) {nextConnect=now+(long)(1e9/settings.connection().connectionsPerSecond());connect(next);}
    }
    private void connect(Slot s) {
        Settings.Connection c=settings.connection();
        debug((s.bot?"机器人":"玩家 "+s.gameName)+"开始建连，重试计数="+s.retries);
        s.nickLength=advertisedNickLength>0?advertisedNickLength:c.fallbackNickLength();s.mapping=CaseMapping.RFC1459;
        s.welcomed=false;s.joinSent=false;s.renaming=false;s.reconciling=false;s.actual="";s.deferredNick="";
        try {
            String desired=s.bot?c.botNick():c.prefix()+(preference(s.id).nicknameBase()==null?s.gameName:preference(s.id).nicknameBase());
            if(s.bot) {Nicknames.validateFull(desired,s.nickLength);if(desired.length()+5>s.nickLength)throw new IllegalArgumentException("机器人昵称需预留 5 字符冲突后缀，当前 NICKLEN="+s.nickLength);}
            else Nicknames.validateBase(desired.substring(c.prefix().length()),c.prefix(),s.nickLength);
            s.desired=desired;s.collision=0;s.candidate=desired;
            IrcSession session=new IrcSession(c,settings.flood(),tls,io,timers,new IrcSession.Listener(){
                @Override public boolean connected(IrcSession session){return dispatch(()->connectedSlot(s,session));}
                @Override public boolean message(IrcSession session,IrcMessage m){
                    if(!s.bot&&m.command().equals("PRIVMSG"))return true;
                    return dispatch(()->incoming(s,session,m));
                }
                @Override public void disconnected(IrcSession session,String reason){
                    if(!dispatch(()->failure(s,session,reason))&&!closed.get()) {
                        // One bounded rescue task per failed connection; prevents a full mailbox leaving a dead slot.
                        try{actor.execute(()->{if(!closed.get())failure(s,session,reason);});}catch(RejectedExecutionException ignored){}
                    }
                }
            });
            s.session=session;s.phase=s.retries==0?Phase.CONNECTING:Phase.RECONNECTING;
            s.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(c.connectSeconds()+c.handshakeSeconds()+1L);
            session.start();
        } catch(Exception e){failure(s,s.session,e.getMessage());}
    }
    private boolean current(Slot s,IrcSession session){return s.session==session&&session!=null&&(s.bot||players.get(s.id)==s)&&!closed.get();}
    private void connectedSlot(Slot s,IrcSession session) {
        if(!current(s,session)){session.close("会话已失效");return;}
        s.phase=Phase.REGISTERING;s.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.connection().registrationSeconds());
        String ident=s.bot?"mcbridge":"mc"+s.id.toString().replace("-","").substring(0,8);
        session.control("NICK "+s.candidate);
        session.control("USER "+ident+" 0 * :"+(s.bot?"Minecraft IRC query bot":"Minecraft player "+s.id.toString().substring(0,8)));
    }
    private void incoming(Slot s,IrcSession session,IrcMessage m) {
        if(!current(s,session)||!session.isOpen())return;
        if(s.renaming&&s.reconciling&&Set.of("311","318","401").contains(m.command())&&!m.params().isEmpty()) {
            String confirmed=m.params().getFirst();
            if(s.mapping.same(confirmed,s.actual))nickFailed(s,session,"改名确认超时；服务器核对原昵称仍有效");
            else if(s.mapping.same(confirmed,s.candidate)) {
                s.actual=confirmed;s.renaming=false;s.reconciling=false;s.deadline=0;s.error="";
                game.tell(s.id,"IRC 身份核对已确认改名，实际昵称："+confirmed);
            } else failure(s,session,"服务器核对返回未知昵称："+confirmed);
            return;
        }
        switch(m.command()) {
            case "001" -> {
                if(m.params().isEmpty())return;
                String nick=m.params().getFirst();
                if(!s.mapping.same(nick,s.candidate)) {failure(s,session,"服务器改变或截断了申请昵称："+nick);return;}
                s.actual=nick;s.welcomed=true;
            }
            case "005" -> support(s,session,m);
            case "376","422" -> {
                if(s.welcomed&&!s.joinSent) {
                    s.joinSent=true;s.phase=Phase.JOINING;
                    s.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.connection().joinSeconds());
                    session.control("JOIN "+settings.connection().channel());
                }
            }
            case "JOIN" -> {
                if(s.mapping.same(m.trailing(),settings.connection().channel())) {
                    if(s.bot&&!s.mapping.same(m.nick(),s.actual))observeJoin(m);
                    if(s.joinSent&&s.mapping.same(m.nick(),s.actual)) {
                        if(!s.bot)proveJoin(s,m.prefix());
                        joined(s);
                    }
                }
            }
            case "366" -> { // End-of-NAMES is also an authoritative channel-join confirmation.
                if(s.joinSent&&m.params().size()>1&&s.mapping.same(m.params().get(1),settings.connection().channel()))joined(s);
            }
            case "NICK" -> nickConfirmed(s,m);
            case "352" -> {
                if(s.bot&&whoSent&&m.params().size()>=7&&s.mapping.same(m.params().get(1),settings.connection().channel())) {
                    String nick=m.params().get(5);Origin origin=whoOwners.get(s.mapping.fold(nick));
                    if(origin!=null)members.put(s.mapping.fold(nick),new Member(nick+"!"+m.params().get(2)+"@"+m.params().get(3),origin));
                }
            }
            case "315" -> {
                if(s.bot&&whoSent&&m.params().size()>1&&s.mapping.same(m.params().get(1),settings.connection().channel())) {
                    whoSent=false;finishJoined(s);whoOwners.clear();
                    Set<Origin> bound=new HashSet<>();for(Member member:members.values())if(member.origin()!=null)bound.add(member.origin());
                    joinProofs.values().forEach(q->q.removeIf(bound::contains));joinProofs.entrySet().removeIf(e->e.getValue().isEmpty());
                    applyDeferredNames();
                }
            }
            case "QUIT" -> {if(s.bot)removeMember(m.nick());}
            case "433","436","437" -> collision(s,session,m);
            case "432" -> nickFailed(s,session,"服务器拒绝非法/超长昵称（432）："+m.trailing());
            case "431" -> nickFailed(s,session,"服务器拒绝空昵称（431）");
            case "401","421","461","462","464","465","466" -> failure(s,session,"IRC 注册/协议错误 "+m.command()+"："+m.trailing());
            case "403","405","471","473","474","475","476","477","479","489" -> failure(s,session,"无法加入频道 "+m.command()+"："+m.trailing());
            case "404" -> failure(s,session,"无法向频道发言（404）："+m.trailing());
            case "KICK" -> {
                if(s.bot&&m.params().size()>1&&s.mapping.same(m.params().get(0),settings.connection().channel()))removeMember(m.params().get(1));
                if(m.params().size()>1&&s.mapping.same(m.params().get(0),settings.connection().channel())&&s.mapping.same(m.params().get(1),s.actual))
                    failure(s,session,"被移出频道："+m.trailing());
            }
            case "PART" -> {
                if(s.bot&&!m.params().isEmpty()&&s.mapping.same(m.params().getFirst(),settings.connection().channel()))removeMember(m.nick());
                if(!m.params().isEmpty()&&s.mapping.same(m.nick(),s.actual)&&s.mapping.same(m.params().getFirst(),settings.connection().channel()))failure(s,session,"已离开目标频道");
            }
            case "ERROR" -> failure(s,session,"IRC ERROR："+m.trailing());
            case "PRIVMSG" -> {if(s.bot&&s.phase==Phase.JOINED)channelMessage(m);}
            default -> { /* No notices, CTCP, private messages or management events are relayed. */ }
        }
    }
    private void support(Slot s,IrcSession session,IrcMessage m) {
        for(String token:m.params()) {
            if(token.startsWith("CASEMAPPING="))s.mapping=CaseMapping.parse(token.substring(12));
            if(token.startsWith("NICKLEN="))try {
                int n=Integer.parseInt(token.substring(8));if(n<1)continue;s.nickLength=Math.min(n,64);
                if(s.bot)advertisedNickLength=s.nickLength;
            }catch(NumberFormatException ignored){}
        }
        debug((s.bot?"机器人":s.gameName)+" ISUPPORT：NICKLEN="+s.nickLength+"，CASEMAPPING="+s.mapping);
        try {
            if(s.bot) {if(s.desired.length()+5>s.nickLength)throw new IllegalArgumentException("机器人昵称及冲突后缀超过服务器 NICKLEN="+s.nickLength);}
            else Nicknames.validateBase(s.desired.substring(settings.connection().prefix().length()),settings.connection().prefix(),s.nickLength);
        }catch(IllegalArgumentException e){failure(s,session,e.getMessage());}
    }
    private void joined(Slot s) {
        if(s.phase==Phase.JOINED)return;
        if(s.bot) {
            if(!botInChannel){botInChannel=true;s.phase=Phase.JOINING;s.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.connection().joinSeconds());}
            return;
        }
        finishJoined(s);
    }
    private void finishJoined(Slot s) {
        s.phase=Phase.JOINED;s.deadline=0;s.joinedAt=System.nanoTime();s.error="";s.notifiedError="";
        game.log(false,(s.bot?"机器人":"玩家 "+s.gameName)+"已加入 "+settings.connection().channel()+"，实际昵称 "+s.actual);
        if(s.bot) {
            if(botOutage)game.deliver(recipients(),"[IRC] 机器人已恢复；IRC 入站和查询恢复，不回放断线期间消息");
            botOutage=false;
        } else game.tell(s.id,"IRC 已加入频道，实际昵称："+s.actual+(s.collision>0?"（申请名被占用，已自动加后缀）":"")
                +(bot.phase==Phase.JOINED?"":"；机器人暂不可用，个人发送可用，入站与查询待恢复"));
    }
    private void requestNick(Slot s,String desired,boolean rename) {
        s.desired=desired;s.collision=0;s.candidate=desired;s.renaming=rename;s.reconciling=false;
        if(desired.equals(s.actual)){s.renaming=false;s.deadline=0;s.error="";game.tell(s.id,"IRC 实际昵称已为："+s.actual);return;}
        s.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.connection().nickSeconds());
        s.session.control("NICK "+s.candidate);
    }
    private void collision(Slot s,IrcSession session,IrcMessage m) {
        if(m.params().size()<2||!s.mapping.same(m.params().get(1),s.candidate))return;
        if(s.phase==Phase.JOINED&&!s.renaming)return;
        if(++s.collision>settings.connection().nickRetries()) {nickFailed(s,session,"昵称冲突达到重试上限");return;}
        try {s.candidate=Nicknames.candidate(s.desired,s.id,s.collision,s.nickLength);session.control("NICK "+s.candidate);}
        catch(IllegalArgumentException e){nickFailed(s,session,e.getMessage());}
    }
    private void nickFailed(Slot s,IrcSession session,String reason) {
        if(s.renaming) {
            s.renaming=false;s.reconciling=false;s.deadline=0;s.error=reason;
            game.tell(s.id,"IRC 改名失败："+reason+"；保留实际昵称 "+s.actual+"，保存的主体将在下次连接时再申请");
        } else failure(s,session,reason);
    }
    private void nickConfirmed(Slot observer,IrcMessage m) {
        if(m.params().isEmpty())return;
        Origin observed=null;
        if(observer.bot&&m.prefix().indexOf('!')>=0) {
            String oldKey="irc:"+observer.mapping.fold(m.prefix());
            String nextKey="irc:"+observer.mapping.fold(m.trailing()+m.prefix().substring(m.prefix().indexOf('!')));
            queries.rename(oldKey,nextKey);
            String oldNick=observer.mapping.fold(m.nick()),newNick=observer.mapping.fold(m.trailing());
            Member old=members.remove(oldNick);
            Origin scoped=whoOwners.remove(oldNick);
            if(scoped!=null)whoOwners.put(newNick,scoped);
            if(old!=null){observed=old.origin();members.put(newNick,new Member(m.trailing()+m.prefix().substring(m.prefix().indexOf('!')),observed));}
        }
        Slot target=observer;
        if(observer.bot&&!observer.mapping.same(m.nick(),observer.actual)) {
            if(observed!=null){target=players.get(observed.id());if(target!=null&&target.session!=observed.session())target=null;}
            else target=players.values().stream().filter(s->s.session!=null&&!s.actual.isEmpty()&&observer.mapping.same(m.nick(),s.actual)).findFirst().orElse(null);
            if(target==null)return;
            // The owner socket may already have confirmed a later NICK. Never roll it back.
            if(!observer.mapping.same(target.actual,m.nick())&&!observer.mapping.same(target.actual,m.trailing()))return;
        } else if(!observer.mapping.same(m.nick(),observer.actual)&&!observer.mapping.same(m.trailing(),observer.actual))return;
        String next=m.trailing();
        if(!target.bot&&!next.startsWith(settings.connection().prefix())) {
            failure(target,target.session,"服务器确认昵称缺少配置前缀："+next);return;
        }
        target.actual=next;
        if(target.renaming&&target.mapping.same(next,target.candidate)) {
            target.renaming=false;target.reconciling=false;target.deadline=0;target.error="";
            game.tell(target.id,"IRC 改名已确认，实际昵称："+next+(target.collision>0?"（申请名被占用，已自动加后缀）":""));
        }
    }
    private void channelMessage(IrcMessage m) {
        if(m.params().size()!=2||!bot.mapping.same(m.params().getFirst(),settings.connection().channel())
                ||m.prefix().indexOf('!')<0||m.trailing().indexOf('\u0001')>=0)return;
        String nick=m.nick();
        if(bot.mapping.same(nick,bot.actual))return; // Ignore bot echo; replies already use the unified local path.
        Member member=members.get(bot.mapping.fold(nick));Origin origin=member==null?null:member.origin();
        boolean managed=origin!=null;
        String text=ChatText.clean(m.trailing());
        // Queries from managed players still reach the bot, even though their ordinary chat is already local.
        String speaker=managed?"mc:"+origin.id():"irc:"+bot.mapping.fold(m.prefix());
        String answer=queries.reply(speaker,text,snapshot.get(),settings.queries());
        if(!managed)game.deliver(recipients(),ChatText.render(settings.format(),nick,text));
        if(answer!=null)botReply(answer);
    }
    private void botReply(String text) {
        if(bot.session==null||bot.phase!=Phase.JOINED)return;
        try {
            List<String> parts=parts(text,bot);
            if(bot.session.sendChat(settings.connection().channel(),parts))
                for(String part:parts)game.deliver(recipients(),ChatText.render(settings.format(),bot.actual,part));
            else rateLog("bot-flood","机器人发送缓冲已满，查询回复暂停");
        }catch(IllegalArgumentException e){rateLog("bot-format",e.getMessage());}
    }
    private void failure(Slot s,IrcSession session,String reason) {
        if(session!=null&&!current(s,session))return;
        if(session==null&&s.session!=null)return;
        if(s.phase==Phase.DISABLED)return;
        if(s.bot)clearIngress();
        if(s.session!=null){retiring.put(s.id,s.session.termination());s.session.close("连接失败");}s.session=null;
        boolean wasJoined=s.phase==Phase.JOINED;
        if(wasJoined&&System.nanoTime()-s.joinedAt>=TimeUnit.SECONDS.toNanos(settings.connection().stableSeconds()))s.retries=0;
        s.actual="";s.deadline=0;s.renaming=false;s.deferredNick="";s.phase=Phase.FAILED;
        s.error=ChatText.clean(Objects.requireNonNullElse(reason,"连接失败"));lastError=s.error;
        double base=Math.min(settings.connection().retryMax(),settings.connection().retryInitial()*Math.pow(2,Math.min(s.retries++,20)));
        double jitter=1+ThreadLocalRandom.current().nextDouble(-settings.connection().jitter(),settings.connection().jitter()+1e-12);
        s.retryAt=System.nanoTime()+(long)(Math.max(0.1,Math.min(settings.connection().retryMax(),base*jitter))*1e9);
        rateLog(s.id+"|"+s.error,(s.bot?"机器人":"玩家 "+s.gameName)+"连接失败："+s.error+"；将退避重连");
        if(s.bot) {
            if(!botOutage)game.deliver(recipients(),"[IRC] 机器人暂不可用，入站消息与查询暂停；已加入频道的个人连接仍可发送");
            botOutage=true;
            applyDeferredNames();
        } else if(wasJoined||!s.error.equals(s.notifiedError)) {
            game.tell(s.id,"IRC 连接失败："+s.error+"；已安排退避重连，本地聊天正常");s.notifiedError=s.error;
        }
    }
    private void clearIngress(){members.clear();joinProofs.clear();whoOwners.clear();botInChannel=false;whoSent=false;}
    private void beginWho() {
        whoOwners.clear();
        for(Slot s:players.values())if(s.phase==Phase.JOINED&&s.session!=null&&!s.actual.isEmpty())
            whoOwners.put(bot.mapping.fold(s.actual),new Origin(s.id,s.session));
        whoSent=true;bot.session.control("WHO "+settings.connection().channel());
    }
    private void applyDeferredNames() {
        for(Slot s:players.values())if(!s.deferredNick.isEmpty()&&s.phase==Phase.JOINED&&s.session!=null&&!s.renaming) {
            String desired=s.deferredNick;s.deferredNick="";requestNick(s,desired,true);
        }
    }
    private void proveJoin(Slot s,String prefix) {
        Origin origin=new Origin(s.id,s.session);String key=bot.mapping.fold(s.actual);
        Member seen=members.get(key);
        if(seen!=null&&bot.mapping.same(seen.prefix(),prefix))members.put(key,new Member(prefix,origin));
        else {
            ArrayDeque<Origin> proofs=joinProofs.computeIfAbsent(bot.mapping.fold(prefix),p->new ArrayDeque<>());
            if(!origin.equals(proofs.peekLast()))proofs.addLast(origin);
        }
        boundIngress();
    }
    private void observeJoin(IrcMessage m) {
        ArrayDeque<Origin> proofs=joinProofs.get(bot.mapping.fold(m.prefix()));Origin origin=proofs==null?null:proofs.pollFirst();
        if(proofs!=null&&proofs.isEmpty())joinProofs.remove(bot.mapping.fold(m.prefix()));
        members.put(bot.mapping.fold(m.nick()),new Member(m.prefix(),origin));boundIngress();
    }
    private void removeMember(String nick) {
        String key=bot.mapping.fold(nick);Member old=members.remove(key);Origin scoped=whoOwners.remove(key);
        Origin origin=old!=null&&old.origin()!=null?old.origin():scoped;
        if(origin!=null){joinProofs.values().forEach(q->q.removeIf(o->o.session()==origin.session()));joinProofs.entrySet().removeIf(e->e.getValue().isEmpty());}
    }
    private void boundIngress() {
        if(members.size()+joinProofs.values().stream().mapToInt(ArrayDeque::size).sum()>1024&&bot.session!=null)
            failure(bot,bot.session,"频道身份事件积压超过 1024 项安全上限，暂停入站后重连");
    }
    private void rateLog(String key,String text) {
        long now=System.nanoTime();
        if(now-logs.getOrDefault(key,Long.MIN_VALUE/2)>=TimeUnit.SECONDS.toNanos(settings.logCooldownSeconds())) {
            logs.put(key,now);game.log(true,text);if(logs.size()>256)logs.remove(logs.keySet().iterator().next());
        }
    }
    private void debug(String text){if(settings.logLevel().equals("DEBUG"))game.log(false,"[DEBUG] "+text);}
    public CompletableFuture<Void> shutdown() {
        if(!closed.compareAndSet(false,true))return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> result=new CompletableFuture<>();
        actor.execute(()->{
            tick.cancel(false);List<CompletableFuture<Void>> endings=new ArrayList<>();
            for(Slot s:allSlots()) {if(s.session!=null)endings.add(s.session.termination());stop(s,"插件停用");}
            endings.addAll(retiring.values());
            actor.shutdown();
            Thread.ofVirtual().name("PaperIRC-shutdown").start(()->{
                try{CompletableFuture.allOf(endings.toArray(CompletableFuture[]::new)).get(settings.shutdownSeconds(),TimeUnit.SECONDS);}
                catch(Exception e){game.log(true,"IRC 有界关闭达到超时，强制清理网络任务");}
                finally{io.shutdownNow();timers.shutdownNow();actor.shutdownNow();result.complete(null);}
            });
        });
        return result;
    }
    @Override public void close(){shutdown();}
    public static String rootMessage(Throwable t) {
        while((t instanceof CompletionException||t instanceof ExecutionException)&&t.getCause()!=null)t=t.getCause();
        return Objects.requireNonNullElse(t.getMessage(),t.getClass().getSimpleName());
    }
}
