package cn.paperirc.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStoreTest {
    @TempDir Path dir;
    @Test void uuidPreferencesSurviveRestartAndReset() throws Exception {
        Path file=dir.resolve("players.yml");UUID id=UUID.randomUUID(),other=UUID.randomUUID();
        PlayerStore s=new PlayerStore(file);assertTrue(s.load().isEmpty());
        s.save(id,new PlayerPreference(false,"Alex"));s.save(other,PlayerPreference.DEFAULT);
        PlayerStore reload=new PlayerStore(file);assertEquals(new PlayerPreference(false,"Alex"),reload.load().get(id));
        reload.save(id,new PlayerPreference(false,null));assertEquals(new PlayerPreference(false,null),new PlayerStore(file).load().get(id));
        try(var listing=Files.list(dir)){assertEquals(1,listing.count());}
    }
    @Test void corruptFileIsNeverOverwritten() throws Exception {
        Path f=dir.resolve("players.yml");String corrupt="[ broken YAML :";Files.writeString(f,corrupt);
        PlayerStore s=new PlayerStore(f);assertThrows(Exception.class,s::load);
        assertThrows(Exception.class,()->s.save(UUID.randomUUID(),PlayerPreference.DEFAULT));assertEquals(corrupt,Files.readString(f));
    }
    @Test void externalDamageAndIoFailureAreReported() throws Exception {
        Path f=dir.resolve("players.yml");PlayerStore s=new PlayerStore(f);s.load();s.save(UUID.randomUUID(),PlayerPreference.DEFAULT);
        Files.writeString(f,"damaged");assertThrows(Exception.class,()->s.save(UUID.randomUUID(),PlayerPreference.DEFAULT));assertEquals("damaged",Files.readString(f));
        Path parent=dir.resolve("not-a-directory");PlayerStore fail=new PlayerStore(parent.resolve("players.yml"));fail.load();Files.writeString(parent,"file");
        assertThrows(Exception.class,()->fail.save(UUID.randomUUID(),PlayerPreference.DEFAULT));
    }
    @Test void duplicateUuidKeysAreRejectedWithoutDiscardingOriginalData() throws Exception {
        Path f=dir.resolve("players.yml");String id=UUID.randomUUID().toString();
        String data=id+":\n  enabled: false\n"+id+":\n  enabled: true\n";
        Files.writeString(f,data);PlayerStore s=new PlayerStore(f);assertThrows(Exception.class,s::load);
        assertThrows(Exception.class,()->s.save(UUID.fromString(id),PlayerPreference.DEFAULT));assertEquals(data,Files.readString(f));
    }
}
