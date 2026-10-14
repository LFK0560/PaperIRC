package cn.paperirc;

import cn.paperirc.config.Settings;
import cn.paperirc.config.YamlFiles;
import cn.paperirc.core.*;
import cn.paperirc.irc.*;
import cn.paperirc.persistence.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Level;

public final class PaperIrcPlugin extends JavaPlugin implements Listener {
    private final ThreadPoolExecutor files=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().name("PaperIRC-files").daemon(true).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final BlockingQueue<Runnable> mainQueue=new ArrayBlockingQueue<>(4096);
    private final AtomicBoolean overflow=new AtomicBoolean();
    private final Set<UUID> locallyOff=new HashSet<>(); // Paper thread only
    private final ConcurrentMap<UUID,Player> activePlayers=new ConcurrentHashMap<>();
    private final Map<UUID,Long> toggleRevision=new HashMap<>();
    private final AtomicReference<ServerSnapshot> snapshot=new AtomicReference<>(new ServerSnapshot(0,20,20,0,0));
    private volatile Bridge bridge;
    private PlayerStore store;
    private Path configFile;
    private BukkitTask pump;
    private volatile boolean closing,ready;
    private int ticks;
    private long lastOverflowNotice=Long.MIN_VALUE/2;
    private record Loaded(Settings settings,Map<UUID,PlayerPreference> players) {}

    @Override public void onEnable() {
        closing=false;configFile=getDataFolder().toPath().resolve("config.yml");
        store=new PlayerStore(getDataFolder().toPath().resolve("players.yml"));
        getServer().getPluginManager().registerEvents(this,this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event->
                event.registrar().register("irc","IRC 个人设置及管理",List.of(),new IrcCommands(this)));
        pump=getServer().getScheduler().runTaskTimer(this,()->{
            for(int i=0;i<256;i++){Runnable r=mainQueue.poll();if(r==null)break;r.run();}
            if(overflow.getAndSet(false)) {
                long now=System.nanoTime();int cooldown=bridge==null?30:bridge.settings().logCooldownSeconds();
                if(now-lastOverflowNotice>=TimeUnit.SECONDS.toNanos(cooldown)) {
                    lastOverflowNotice=now;
                    getLogger().warning("游戏投递队列已满，部分 IRC 消息未同步；请调整限流并检查服务器负载");
                    for(Player player:getServer().getOnlinePlayers())if(!locallyOff.contains(player.getUniqueId()))
                        player.sendMessage(Component.text("[IRC] 入站消息过于频繁，部分消息未同步，请稍后再试"));
                }
            }
            if(++ticks%20==0)refreshSnapshot(null);
        },1,1);
        refreshSnapshot(null);
        fileTask(()->{
            Files.createDirectories(configFile.getParent());
            if(!Files.exists(configFile))try(InputStream defaults=getResource("config.yml")) {
                if(defaults==null)throw new IOException("插件缺少默认 config.yml");
                Files.copy(defaults,configFile);
            }
            return new Loaded(readConfig(),store.load());
        }).whenComplete((loaded,error)->main(()->{
            if(error!=null) {
                getLogger().log(Level.SEVERE,"配置/玩家数据读取失败，原文件保持不变："+Bridge.rootMessage(error));
                getServer().getPluginManager().disablePlugin(this);return;
            }
            loaded.players().forEach((id,p)->{if(!p.enabled())locallyOff.add(id);});
            bridge=new Bridge(loaded.settings(),loaded.players(),new Bridge.GamePort(){
                @Override public void tell(UUID id,String text){
                    Player expected=activePlayers.get(id);
                    if(expected!=null)main(()->{if(activePlayers.get(id)==expected)expected.sendMessage(Component.text("[PaperIRC] "+text));});
                }
                @Override public void deliver(Set<UUID> ids,String text){
                    Map<UUID,Player> targets=new HashMap<>();for(UUID id:ids){Player p=activePlayers.get(id);if(p!=null)targets.put(id,p);}
                    main(()->{
                    for(UUID id:ids) {
                        Player p=targets.get(id);
                        if(p!=null&&activePlayers.get(id)==p&&!locallyOff.contains(id))p.sendMessage(Component.text(text));
                    }
                });}
                @Override public void log(boolean error,String text){if(error)getLogger().warning(text);else getLogger().info(text);}
            },(id,p)->fileTask(()->{store.save(id,p);return null;}),snapshot::get);
            ready=true;
            for(Player p:getServer().getOnlinePlayers()){activePlayers.put(p.getUniqueId(),p);observe(bridge.join(p.getUniqueId(),p.getName()));}
            getLogger().info("PaperIRC 已启用，使用 Paper API 26.3.build.178-stable / Java 25；"+
                    (loaded.settings().active()?"TLS 桥接正在异步连接":loaded.settings().inactiveReason()));
        }));
    }
    private Settings readConfig() throws Exception {
        if(Files.size(configFile)>1024*1024)throw new IOException("config.yml 超出 1 MiB 安全上限");
        YamlConfiguration y=YamlFiles.parse(Files.readString(configFile,java.nio.charset.StandardCharsets.UTF_8));return Settings.from(y);
    }
    private <T> CompletableFuture<T> fileTask(Callable<T> action) {
        CompletableFuture<T> f=new CompletableFuture<>();
        try {files.execute(()->{try{f.complete(action.call());}catch(Exception e){f.completeExceptionally(e);}});}
        catch(RejectedExecutionException e){f.completeExceptionally(new IllegalStateException("设置文件队列已满或插件正在关闭",e));}
        return f;
    }
    public void main(Runnable action) {
        if(closing)return;
        if(!mainQueue.offer(()->{if(!closing)action.run();}))overflow.set(true);
    }
    public boolean ready(){return ready&&!closing;}
    public Bridge bridge(){return bridge;}
    public void respond(CommandSender sender,CompletableFuture<String> response) {
        response.whenComplete((text,error)->main(()->sender.sendMessage(Component.text("[PaperIRC] "+(error==null?text:Bridge.rootMessage(error))))));
    }
    public void toggle(Player player,boolean enabled) {
        UUID id=player.getUniqueId();boolean wasOff=locallyOff.contains(id);
        long revision=toggleRevision.merge(id,1L,Long::sum);
        if(enabled)locallyOff.remove(id);else locallyOff.add(id);
        CompletableFuture<String> f=bridge.enabled(id,enabled);
        f.whenComplete((text,error)->main(()->{
            if(toggleRevision.getOrDefault(id,0L)!=revision)return;
            if(error!=null&&Bridge.rootMessage(error).contains("处理队列已满")&&toggleRevision.getOrDefault(id,0L)==revision) {
                if(wasOff)locallyOff.add(id);else locallyOff.remove(id);
            }
            if(player.isOnline())player.sendMessage(Component.text("[PaperIRC] "+(error==null?text:Bridge.rootMessage(error))));
        }));
    }
    public CompletableFuture<String> reloadFiles() {
        return fileTask(this::readConfig).thenCompose(next->bridge.reload(next))
                .exceptionallyCompose(e->CompletableFuture.failedFuture(new IllegalArgumentException("重载失败，保留原有效配置："+Bridge.rootMessage(e),e)));
    }
    private void observe(CompletableFuture<?> f){f.exceptionally(e->{getLogger().warning(Bridge.rootMessage(e));return null;});}
    @EventHandler public void joined(PlayerJoinEvent event) {
        activePlayers.put(event.getPlayer().getUniqueId(),event.getPlayer());
        if(ready())observe(bridge.join(event.getPlayer().getUniqueId(),event.getPlayer().getName()));
        refreshSnapshot(null);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        activePlayers.remove(event.getPlayer().getUniqueId(),event.getPlayer());
        if(ready())observe(bridge.quit(event.getPlayer().getUniqueId()));
        toggleRevision.remove(event.getPlayer().getUniqueId());refreshSnapshot(event.getPlayer().getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void chat(AsyncChatEvent event) {
        if(!ready())return;
        if(activePlayers.get(event.getPlayer().getUniqueId())!=event.getPlayer())return;
        String text=PlainTextComponentSerializer.plainText().serialize(event.message());
        if(!bridge.chat(event.getPlayer().getUniqueId(),text)) {
            overflow.set(true); // The main-thread pump reports bounded overload, without touching the event.
        }
    }
    private void refreshSnapshot(UUID leaving) {
        int online=(int)getServer().getOnlinePlayers().stream().filter(p->!p.getUniqueId().equals(leaving)).count();
        double[] tps=getServer().getTPS();
        double average=getServer().getAverageTickTime();
        snapshot.set(new ServerSnapshot(online,getServer().getMaxPlayers(),tps.length==0?0:tps[0],average,
                ManagementFactory.getRuntimeMXBean().getUptime()));
    }
    @Override public void onDisable() {
        closing=true;ready=false;HandlerList.unregisterAll((org.bukkit.plugin.Plugin)this);
        if(pump!=null)pump.cancel();getServer().getScheduler().cancelTasks(this);mainQueue.clear();activePlayers.clear();
        int seconds=bridge==null?5:bridge.settings().shutdownSeconds();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        CompletableFuture<Void> network=bridge==null?CompletableFuture.completedFuture(null):bridge.shutdown();
        files.shutdown();
        try {
            network.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(!files.awaitTermination(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))
                getLogger().severe("玩家设置最终写入超过关闭时限，未完成的设置可能未持久化");
        } catch(Exception e){getLogger().warning("插件有界关闭："+Bridge.rootMessage(e));}
        finally {files.shutdownNow();}
        getLogger().info("PaperIRC 已停用；连接、监听器、调度任务及文件线程已清理");
    }
}
