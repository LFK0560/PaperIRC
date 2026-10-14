package cn.paperirc.core;

import cn.paperirc.config.Settings;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class QueryTest {
    @Test void exactQueriesAndPrivacy() {
        AtomicLong clock=new AtomicLong();QueryHandler q=new QueryHandler(clock::get);
        Settings.Queries limits=new Settings.Queries(5,1,10);
        ServerSnapshot s=new ServerSnapshot(3,20,19.98,12.34,((24+2)*60L+3)*60000);
        assertEquals("在线人数：3/20",q.reply("A","  !LIST  ",s,limits));
        clock.addAndGet(6_000_000_000L);
        String status=q.reply("A","!status",s,limits);
        assertTrue(status.contains("19.98"));assertTrue(status.contains("12.34"));assertTrue(status.contains("1天2小时3分钟"));
        assertFalse(status.matches(".*(内存|Paper|UUID|版本|Steve).*"));
        assertNull(q.reply("B","我说 !list",s,limits));assertNull(q.reply("B","!op Steve",s,limits));
        clock.addAndGet(6_000_000_000L);
        assertTrue(q.reply("A","!list Steve",s,limits).startsWith("查询不接受参数"));
    }
    @Test void speakerAndGlobalCooldownFeedbackIsAlsoLimited() {
        AtomicLong clock=new AtomicLong();QueryHandler q=new QueryHandler(clock::get);
        Settings.Queries limits=new Settings.Queries(5,1,10);
        ServerSnapshot s=new ServerSnapshot(0,37,20,0,10000);
        assertEquals("在线人数：0/37",q.reply("A","!list",s,limits));
        assertTrue(q.reply("B","!list",s,limits).contains("频繁"));
        assertNull(q.reply("B","!list",s,limits));assertNull(q.reply("C","!list",s,limits));
        clock.addAndGet(2_000_000_000L);
        assertNull(q.reply("A","!status",s,limits));assertEquals("在线人数：0/37",q.reply("B","!list",s,limits));
    }
    @Test void differentSpeakersRemainIndependentAndRenameRetainsCooldown() {
        AtomicLong clock=new AtomicLong();QueryHandler q=new QueryHandler(clock::get);
        Settings.Queries limits=new Settings.Queries(5,1,10);ServerSnapshot s=new ServerSnapshot(0,20,20,0,0);
        assertEquals("在线人数：0/20",q.reply("Alice!same@host","!list",s,limits));
        clock.addAndGet(2_000_000_000L);q.rename("Alice!same@host","Carol!same@host");
        assertTrue(q.reply("Carol!same@host","!list",s,limits).contains("频繁"));
        assertEquals("在线人数：0/20",q.reply("Bob!same@host","!list",s,limits));
    }
}
