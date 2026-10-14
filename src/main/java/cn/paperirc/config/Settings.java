package cn.paperirc.config;

import cn.paperirc.irc.Nicknames;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.Locale;

/** Immutable, fully validated settings. A failed reload never replaces this object. */
public record Settings(boolean enabled, Connection connection, String format, Queries queries,
                       Flood flood, String logLevel, int logCooldownSeconds, int shutdownSeconds) {
    public record Connection(String host, int port, String channel, String botNick, String prefix,
                             int fallbackNickLength, int nickRetries, int connectSeconds,
                             int handshakeSeconds, int registrationSeconds, int joinSeconds,
                             int nickSeconds, int idleSeconds, int pongSeconds, double retryInitial,
                             double retryMax, double jitter, int stableSeconds, double connectionsPerSecond) {}
    public record Queries(double speakerSeconds, double globalSeconds, double feedbackSeconds) {}
    public record Flood(double linesPerSecond, int bufferLines, int maxCodepoints, int maxParts,
                        int prefixReserveBytes, int eventBuffer) {}

    public boolean active() {
        return enabled && !connection.host().toLowerCase(Locale.ROOT).endsWith(".invalid");
    }
    public String inactiveReason() {
        return !enabled ? "桥接已在 config.yml 中停用" : "请填写真实 IRC 主机；不会连接 .invalid 占位地址";
    }
    public static Settings from(YamlConfiguration y) {
        String host = string(y, "irc.host");
        if (host.isBlank() || host.length() > 253 || !host.matches("[A-Za-z0-9._:-]+"))
            throw bad("irc.host", "必须是证书对应的主机名（不带协议、路径或空格）");
        String channel = string(y, "irc.channel");
        if (!channel.matches("#[^ ,:\\x00-\\x20\\x7f]{1,79}"))
            throw bad("irc.channel", "仅支持一个 #频道，禁止空格、逗号及控制字符");
        String prefix = string(y, "irc.player-prefix");
        if (!prefix.matches("[A-Za-z_][A-Za-z0-9_-]*"))
            throw bad("irc.player-prefix", "必须非空，以字母或下划线开头，只能使用 ASCII 字母、数字、下划线、连字符");
        int length = integer(y, "irc.nickname-length-fallback", 7, 64);
        if (prefix.length() + Nicknames.SUFFIX_LENGTH + 1 > length)
            throw bad("irc.player-prefix", "长度不足：需给昵称主体至少 1 字符及冲突后缀 5 字符留空间");
        String bot = string(y, "irc.bot-nickname");
        Nicknames.validateFull(bot, length);
        if (bot.length() + Nicknames.SUFFIX_LENGTH > length)
            throw bad("irc.bot-nickname", "需预留 5 字符冲突后缀");
        String format = string(y, "chat.format");
        if (format.length() > 512 || !format.contains("[IRC]") || occurrences(format, "{nick}") != 1
                || occurrences(format, "{message}") != 1 || format.codePoints().anyMatch(c -> Character.isISOControl(c)))
            throw bad("chat.format", "必须保留 [IRC]，且 {nick}、{message} 各出现一次；模板是纯文本");
        double initial = number(y, "reconnect.initial-seconds", 0.1, 300);
        double max = number(y, "reconnect.max-seconds", initial, 3600);
        Connection c = new Connection(host, integer(y,"irc.port",1,65535), channel, bot, prefix, length,
                integer(y,"irc.nickname-retries",1,16), integer(y,"timeouts.connect-seconds",1,120),
                integer(y,"timeouts.handshake-seconds",1,120), integer(y,"timeouts.registration-seconds",1,180),
                integer(y,"timeouts.join-seconds",1,120), integer(y,"timeouts.nickname-seconds",1,60),
                integer(y,"timeouts.idle-seconds",5,600), integer(y,"timeouts.pong-seconds",1,120),
                initial, max, number(y,"reconnect.jitter",0,0.5), integer(y,"reconnect.stable-seconds",1,600),
                number(y,"reconnect.connections-per-second",0.1,20));
        Queries q = new Queries(number(y,"queries.per-speaker-cooldown-seconds",0.1,3600),
                number(y,"queries.global-cooldown-seconds",0.1,60), number(y,"queries.feedback-cooldown-seconds",1,3600));
        Flood f = new Flood(number(y,"flood.lines-per-second",0.1,20), integer(y,"flood.send-buffer-lines",4,512),
                integer(y,"flood.max-message-codepoints",32,32768), integer(y,"flood.max-message-parts",1,256),
                integer(y,"flood.relay-prefix-reserve-bytes",160,300), integer(y,"flood.event-buffer",128,16384));
        String level = string(y,"logging.level").toUpperCase(Locale.ROOT);
        if (!level.equals("INFO") && !level.equals("DEBUG")) throw bad("logging.level", "仅允许 INFO 或 DEBUG");
        return new Settings(bool(y,"enabled"),c,format,q,f,level,integer(y,"logging.repeated-error-cooldown-seconds",1,3600),
                integer(y,"timeouts.shutdown-seconds",1,15));
    }
    private static int occurrences(String s,String target) { return (s.length()-s.replace(target,"").length())/target.length(); }
    private static String string(YamlConfiguration y,String key) {
        Object value=y.get(key); if (!(value instanceof String s)) throw bad(key,"必须是字符串"); return s;
    }
    private static boolean bool(YamlConfiguration y,String key) {
        Object value=y.get(key); if (!(value instanceof Boolean b)) throw bad(key,"必须是 true 或 false"); return b;
    }
    private static double number(YamlConfiguration y,String key,double min,double max) {
        Object value=y.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<min || n.doubleValue()>max)
            throw bad(key,"必须介于 "+min+" 与 "+max+" 之间");
        return n.doubleValue();
    }
    private static int integer(YamlConfiguration y,String key,int min,int max) {
        double n=number(y,key,min,max); if (n!=Math.rint(n)) throw bad(key,"必须是整数"); return (int)n;
    }
    private static IllegalArgumentException bad(String key,String reason) { return new IllegalArgumentException(key+"："+reason); }
}
