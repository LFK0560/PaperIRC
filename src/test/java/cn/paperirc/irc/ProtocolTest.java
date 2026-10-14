package cn.paperirc.irc;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void parsesTagsPrefixAndTrailingText() {
        IrcMessage m=IrcMessage.parse("@msgid=12 :Alice!user@host PRIVMSG #minecraft :大家好 😀 : text");
        assertEquals("Alice",m.nick());assertEquals("PRIVMSG",m.command());
        assertEquals(List.of("#minecraft","大家好 😀 : text"),m.params());
        assertEquals("abc def",IrcMessage.parse("PING :abc def").trailing());
        assertThrows(IllegalArgumentException.class,()->IrcMessage.parse("NICK foo\r\nOPER bar"));
    }
    @Test void framingRetainsPartialUtf8AcrossTimeout() throws Exception {
        byte[] source="PRIVMSG #mc :中😀\r\nPING :x\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream in=new InputStream(){int i;boolean timed;
            @Override public int read() throws IOException {if(i==16&&!timed){timed=true;throw new SocketTimeoutException();}return i==source.length?-1:source[i++]&255;}
        };
        LineReader r=new LineReader(in);
        assertThrows(SocketTimeoutException.class,r::read);
        assertEquals("PRIVMSG #mc :中😀",r.read());assertEquals("PING :x",r.read());assertNull(r.read());
    }
    @Test void badFramesAreBoundedAndRejected() {
        assertThrows(IOException.class,()->new LineReader(new ByteArrayInputStream("bad\n".getBytes())).read());
        assertThrows(IOException.class,()->new LineReader(new ByteArrayInputStream(new byte[9000])).read());
        assertThrows(IOException.class,()->new LineReader(new ByteArrayInputStream(new byte[]{(byte)0xff,13,10})).read());
    }
    @Test void windowsMotdLineEndingsKeepUnicodeAndFollowingFrames() throws Exception {
        byte[] source=("\r\n:srv 372 MCBot :- 欢迎 😀  \r\r\n\r\r\n:srv 376 MCBot :End MOTD\r\nPING :token\r\n").getBytes(StandardCharsets.UTF_8);
        LineReader r=new LineReader(new ByteArrayInputStream(source));
        assertEquals("",r.read());
        assertEquals("- 欢迎 😀  ",IrcMessage.parse(r.read()).trailing());
        assertEquals("",r.read());
        assertEquals("376",IrcMessage.parse(r.read()).command());
        assertEquals("token",IrcMessage.parse(r.read()).trailing());
        assertNull(r.read());
    }
    @Test void boundaryToleranceStillRejectsEmbeddedProtocolControls() throws Exception {
        for(String line:List.of("PRIVMSG #mc :text\rOPER root","PRIVMSG #mc :text\0OPER root")) {
            String frame=new LineReader(new ByteArrayInputStream((line+"\r\r\n").getBytes(StandardCharsets.UTF_8))).read();
            assertEquals(line,frame);
            assertThrows(IllegalArgumentException.class,()->IrcMessage.parse(frame));
        }
        assertThrows(IOException.class,()->new LineReader(new ByteArrayInputStream("PING :bare-lf\n".getBytes(StandardCharsets.UTF_8))).read());
    }
    @Test void longUnicodeMessagesPreserveTextAndFullFrameBudget() {
        String s="大家好 😀 é {message} <click:run_command:'/op Steve'>  ".repeat(200);
        List<String> parts=ChatText.split(s,"#minecraft",200,32768,256);
        assertTrue(parts.size()>1);assertEquals(s,String.join("",parts));
        for(String part:parts){assertTrue(ChatText.bytes("PRIVMSG #minecraft :"+part)+200+2<=512);assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));}
        assertThrows(IllegalArgumentException.class,()->ChatText.split(s,"#mc",200,32,256));
        assertThrows(IllegalArgumentException.class,()->ChatText.split(s,"#mc",200,32768,1));
    }
    @Test void injectionAndFormattingCannotAddProtocolFrames() {
        assertEquals("hi  OPER rootbad",ChatText.clean("hi\r\nOPER root\u0001bad"));
        assertEquals("中文blue😀",ChatText.clean("\u000304,02中文\u0002blue\u000f😀"));
        String user="{nick}<click:run_command:'/op Steve'>§a😀";
        assertEquals("[IRC] <Alice> "+user,ChatText.render("[IRC] <{nick}> {message}","Alice",user));
        assertEquals("[IRC] <{message}> test",ChatText.render("[IRC] <{nick}> {message}","{message}","test"));
    }
    @Test void nicknameLimitsPrefixesAndRfcCaseMapping() {
        assertEquals(24,Nicknames.baseLimit("MC_",32));
        Nicknames.validateBase("123_Alex-","MC_",32);
        assertThrows(IllegalArgumentException.class,()->Nicknames.validateBase("a".repeat(25),"MC_",32));
        for(String invalid:List.of(""," a","x\ny","汉字","x:y"))assertThrows(IllegalArgumentException.class,()->Nicknames.validateBase(invalid,"MC_",32));
        String nick=Nicknames.candidate("MC_Alex",UUID.fromString("12345678-1234-1234-1234-123456789012"),1,32);
        assertTrue(nick.matches("MC_Alex_[0-9a-f]{4}"));
        assertTrue(CaseMapping.RFC1459.same("a[\\^]","A{|~}"));
        assertFalse(CaseMapping.STRICT_RFC1459.same("^","~"));assertFalse(CaseMapping.ASCII.same("[","{"));
    }
    @Test void malformedColourSequencesDoNotEraseOrdinaryUnicodeText() {
        assertEquals("١٢abc中文",ChatText.clean("\u0003١٢\u0004abc中文"));
        assertEquals("中文",ChatText.clean("\u0004abcdef,123456中文"));
    }
}
