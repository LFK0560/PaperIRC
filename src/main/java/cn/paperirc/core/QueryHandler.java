package cn.paperirc.core;

import cn.paperirc.config.Settings;
import java.util.*;
import java.util.function.LongSupplier;

/** Actor-thread owned, bounded cooldown maps. No Minecraft commands are dispatched. */
public final class QueryHandler {
    private final LongSupplier clock;
    private final LinkedHashMap<String,Long> speakers=new LinkedHashMap<>(),feedback=new LinkedHashMap<>();
    private long global=Long.MIN_VALUE/2,globalFeedback=Long.MIN_VALUE/2;
    public QueryHandler() { this(System::nanoTime); }
    public QueryHandler(LongSupplier clock) { this.clock=clock; }
    public static boolean isQuery(String text) { return command(text)!=null; }
    private static String command(String text) {
        String stripped=text.strip();int space=0;
        while(space<stripped.length()&&!Character.isWhitespace(stripped.charAt(space)))space++;
        String cmd=stripped.substring(0,space).toLowerCase(Locale.ROOT);
        return switch(cmd) { case "!list","!status","!help" -> cmd;default -> null; };
    }
    public String reply(String speaker,String text,ServerSnapshot snapshot,Settings.Queries q) {
        String cmd=command(text);if(cmd==null)return null;
        long now=clock.getAsLong(),last=speakers.getOrDefault(speaker,Long.MIN_VALUE/2);
        if(now-last<(long)(q.speakerSeconds()*1e9)||now-global<(long)(q.globalSeconds()*1e9)) {
            if(now-feedback.getOrDefault(speaker,Long.MIN_VALUE/2)>=(long)(q.feedbackSeconds()*1e9)
                    && now-globalFeedback>=(long)(q.feedbackSeconds()*1e9)) {
                put(feedback,speaker,now);globalFeedback=now;
                return "查询过于频繁，请稍后再试。";
            }
            return null;
        }
        put(speakers,speaker,now);global=now;
        if(!text.strip().equalsIgnoreCase(cmd))return "查询不接受参数。"+help();
        return switch(cmd) { case "!list"->snapshot.list();case "!status"->snapshot.status();default->help(); };
    }
    public void clear() {speakers.clear();feedback.clear();global=globalFeedback=Long.MIN_VALUE/2;}
    public void rename(String oldKey,String nextKey) {
        Long at=speakers.remove(oldKey);if(at!=null)put(speakers,nextKey,Math.max(at,speakers.getOrDefault(nextKey,at)));
        at=feedback.remove(oldKey);if(at!=null)put(feedback,nextKey,Math.max(at,feedback.getOrDefault(nextKey,at)));
    }
    private static void put(LinkedHashMap<String,Long> map,String key,long value) {
        map.put(key,value);if(map.size()>4096)map.remove(map.keySet().iterator().next());
    }
    public static String help() {return "!list：在线人数/上限；!status：人数、TPS、MSPT、运行时间；!help：查询帮助。";}
}
