package cn.paperirc.core;

import cn.paperirc.config.Settings;
import cn.paperirc.persistence.*;
import cn.paperirc.testing.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    private static final Bridge.GamePort NOOP=new Bridge.GamePort(){public void tell(UUID id,String text){}public void deliver(Set<UUID> ids,String text){}public void log(boolean error,String text){}};
    @Test void invalidSettingsNeverBecomeActiveAndPlaceholderDoesNotConnect() throws Exception {
        var y=TestSettings.yaml(6697);y.set("irc.host","irc.example.invalid");Settings good=Settings.from(y);assertFalse(good.active());
        y.set("irc.channel","#mc\r\nOPER root");var badChannel=y;assertThrows(IllegalArgumentException.class,()->Settings.from(badChannel));
        y=TestSettings.yaml(6697);y.set("chat.format","<{nick}> {message}");var missingOrigin=y;assertThrows(IllegalArgumentException.class,()->Settings.from(missingOrigin));
        Bridge bridge=new Bridge(good,Map.of(),NOOP,(id,p)->CompletableFuture.completedFuture(null),()->new ServerSnapshot(0,20,20,0,0));
        try {UUID id=UUID.randomUUID();bridge.join(id,"Steve").get();Thread.sleep(150);assertEquals(0,bridge.overallStatus().get().playerConnections());assertEquals(Bridge.Phase.DISABLED,bridge.overallStatus().get().bot());}
        finally{bridge.shutdown().get(2,TimeUnit.SECONDS);}
    }
    @Test void persistenceFailureCannotClaimSavedPreferences() throws Exception {
        var y=TestSettings.yaml(6697);y.set("enabled",false);
        Bridge bridge=new Bridge(Settings.from(y),Map.of(),NOOP,(id,p)->CompletableFuture.failedFuture(new java.io.IOException("disk-full")),()->new ServerSnapshot(0,20,20,0,0));
        try{UUID id=UUID.randomUUID();bridge.join(id,"Steve").get();Exception e=assertThrows(ExecutionException.class,()->bridge.enabled(id,false).get());assertTrue(e.getMessage().contains("重启后可能恢复旧设置"));assertFalse(bridge.playerStatus(id).get().enabled());}
        finally{bridge.shutdown().get(2,TimeUnit.SECONDS);}
    }
}
