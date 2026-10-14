package cn.paperirc.testing;

import cn.paperirc.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class TestSettings {
    public static YamlConfiguration yaml(int port) throws Exception {
        YamlConfiguration y=new YamlConfiguration();
        try(InputStream in=TestSettings.class.getResourceAsStream("/config.yml")){y.loadFromString(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        y.set("enabled",true);y.set("irc.host","127.0.0.1");y.set("irc.port",port);
        y.set("reconnect.connections-per-second",20);y.set("reconnect.initial-seconds",0.1);
        y.set("reconnect.max-seconds",0.4);y.set("reconnect.jitter",0);y.set("flood.lines-per-second",20);
        y.set("queries.per-speaker-cooldown-seconds",0.1);y.set("queries.global-cooldown-seconds",0.1);
        return y;
    }
    public static Settings settings(int port) throws Exception{return Settings.from(yaml(port));}
}
