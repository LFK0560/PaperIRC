package cn.paperirc.persistence;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import cn.paperirc.config.YamlFiles;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

/** All calls are made on the plugin's single file-I/O executor. */
public final class PlayerStore {
    private final Path file;
    private Map<UUID,PlayerPreference> committed=Map.of();
    private boolean loaded;
    private byte[] fingerprint;
    public PlayerStore(Path file) {this.file=file;}
    public Map<UUID,PlayerPreference> load() throws Exception {
        if(loaded)throw new IllegalStateException("玩家数据已加载");
        Map<UUID,PlayerPreference> result=new HashMap<>();
        if(Files.exists(file)) {
            if(Files.size(file)>8*1024*1024)throw new IOException("players.yml 超出 8 MiB 安全上限；保留原文件");
            String source=Files.readString(file,StandardCharsets.UTF_8);
            fingerprint=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            YamlConfiguration y=YamlFiles.parse(source);
            for(String key:y.getKeys(false)) {
                UUID uuid;try{uuid=UUID.fromString(key);}catch(IllegalArgumentException e){throw new IOException("players.yml 非法 UUID："+key,e);}
                ConfigurationSection section=y.getConfigurationSection(key);
                if(section==null || !(section.get("enabled") instanceof Boolean enabled))
                    throw new IOException("players.yml "+key+" 缺少布尔 enabled");
                Object raw=section.get("nicknameBase");
                String base=null;
                if(raw!=null) {
                    if(!(raw instanceof String s)||!s.matches("[A-Za-z0-9_-]{1,64}"))
                        throw new IOException("players.yml "+key+" 的 nicknameBase 非法");
                    base=s;
                }
                if(!section.getKeys(false).stream().allMatch(k->k.equals("enabled")||k.equals("nicknameBase")))
                    throw new IOException("players.yml "+key+" 存在未知字段，请核对文件格式");
                result.put(uuid,new PlayerPreference(enabled,base));
            }
        }
        if(!Arrays.equals(fingerprint,digest()))throw new IOException("players.yml 在读取期间被外部修改，禁止覆盖；请核对文件");
        committed=Map.copyOf(result);loaded=true;return committed;
    }
    public void save(UUID uuid,PlayerPreference preference) throws Exception {
        if(!loaded)throw new IOException("玩家设置尚未成功读取，禁止覆盖原文件");
        if(!Arrays.equals(fingerprint,digest()))throw new IOException("players.yml 在加载后被外部修改或损坏，禁止覆盖；请停服核对原文件");
        Map<UUID,PlayerPreference> next=new HashMap<>(committed);next.put(uuid,preference);
        YamlConfiguration y=new YamlConfiguration();
        next.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
            String path=entry.getKey().toString();
            y.set(path+".enabled",entry.getValue().enabled());
            y.set(path+".nicknameBase",entry.getValue().nicknameBase());
        });
        Files.createDirectories(file.getParent());
        Path temp=Files.createTempFile(file.getParent(),"players-",".tmp");
        boolean moved=false;
        try {
            byte[] content=y.saveToString().getBytes(StandardCharsets.UTF_8);
            try(FileChannel c=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer data=ByteBuffer.wrap(content);while(data.hasRemaining())c.write(data);c.force(true);
            }
            // Do not fall back to a non-atomic destructive replacement.
            if(!Arrays.equals(fingerprint,digest()))throw new IOException("players.yml 保存期间被外部修改，禁止覆盖");
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            moved=true;fingerprint=MessageDigest.getInstance("SHA-256").digest(content);committed=Map.copyOf(next);
        } finally {if(!moved)Files.deleteIfExists(temp);}
    }
    private byte[] digest() throws Exception {
        if(!Files.exists(file))return null;
        if(Files.size(file)>8*1024*1024)throw new IOException("players.yml 超出 8 MiB 安全上限，禁止覆盖");
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
