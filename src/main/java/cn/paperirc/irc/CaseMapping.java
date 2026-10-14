package cn.paperirc.irc;

import java.util.Locale;

public enum CaseMapping {
    ASCII, STRICT_RFC1459, RFC1459;
    public String fold(String value) {
        char[] chars=value.toCharArray();
        for(int i=0;i<chars.length;i++)if(chars[i]>='A'&&chars[i]<='Z')chars[i]=(char)(chars[i]+32);
        String lower=new String(chars);
        if (this==ASCII) return lower;
        lower=lower.replace('[','{').replace(']','}').replace('\\','|');
        return this==RFC1459 ? lower.replace('^','~') : lower;
    }
    public boolean same(String a,String b) { return fold(a).equals(fold(b)); }
    public static CaseMapping parse(String s) {
        return switch(s.toLowerCase(Locale.ROOT)) {
            case "ascii" -> ASCII;
            case "strict-rfc1459" -> STRICT_RFC1459;
            default -> RFC1459;
        };
    }
}
