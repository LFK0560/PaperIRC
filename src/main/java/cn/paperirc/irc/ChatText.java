package cn.paperirc.irc;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ChatText {
    private ChatText() {}
    public static String clean(String text) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<text.length();) {
            int cp=text.codePointAt(i);i+=Character.charCount(cp);
            if(cp==3) { // mIRC colour plus optional foreground/background digits
                int n=0;while(i<text.length()&&n<2&&isDigit(text.charAt(i))) {i++;n++;}
                if(i+1<text.length()&&text.charAt(i)==','&&isDigit(text.charAt(i+1))) {
                    i++;n=0;while(i<text.length()&&n<2&&isDigit(text.charAt(i))) {i++;n++;}
                }
            } else if(cp==4) { // IRC hex colour
                int end=i,n=0;while(end<text.length()&&n<6&&isHex(text.charAt(end))) {end++;n++;}
                if(n==6) {
                    i=end;
                    if(i<text.length()&&text.charAt(i)==',') {
                        end=i+1;n=0;while(end<text.length()&&n<6&&isHex(text.charAt(end))) {end++;n++;}
                        if(n==6)i=end;
                    }
                }
            } else if(cp=='\r'||cp=='\n'||cp=='\t') out.append(' ');
            else if(!Character.isISOControl(cp) && !(cp>=0xd800&&cp<=0xdfff)) out.appendCodePoint(cp);
        }
        return out.toString();
    }
    private static boolean isHex(char c) { return c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F'; }
    private static boolean isDigit(char c) {return c>='0'&&c<='9';}
    public static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    public static List<String> split(String text,String channel,int relayPrefixReserve,int maxCodepoints,int maxParts) {
        String clean=clean(text);
        if(clean.codePointCount(0,clean.length())>maxCodepoints)
            throw new IllegalArgumentException("消息超过 "+maxCodepoints+" 字符，未发送到 IRC");
        int budget=512-2-bytes("PRIVMSG "+channel+" :")-relayPrefixReserve;
        if(budget<4)throw new IllegalArgumentException("频道与转发前缀过长，没有消息空间");
        List<String> parts=new ArrayList<>();
        int start=0;
        while(start<clean.length()) {
            int end=start,used=0,lastSpace=-1;
            while(end<clean.length()) {
                int cp=clean.codePointAt(end);
                int cost=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
                if(used+cost>budget)break;
                used+=cost;end+=Character.charCount(cp);
                if(Character.isWhitespace(cp))lastSpace=end;
            }
            if(end<clean.length()&&lastSpace>start+(end-start)/2)end=lastSpace;
            parts.add(clean.substring(start,end));start=end;
            if(parts.size()>maxParts)throw new IllegalArgumentException("消息需超过 "+maxParts+" 段，未发送到 IRC");
        }
        return List.copyOf(parts);
    }
    public static String render(String format,String nick,String message) {
        // Single pass: inserted text is never reprocessed as another placeholder.
        StringBuilder out=new StringBuilder();
        for(int i=0;i<format.length();) {
            if(format.startsWith("{nick}",i)) {out.append(clean(nick));i+=6;}
            else if(format.startsWith("{message}",i)) {out.append(clean(message));i+=9;}
            else out.append(format.charAt(i++));
        }
        return out.toString();
    }
}
