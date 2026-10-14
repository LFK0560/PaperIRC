package cn.paperirc.irc;

import java.util.*;

/** RFC-style parser; tags are accepted and ignored, never interpreted as commands. */
public record IrcMessage(String prefix,String command,List<String> params) {
    public static IrcMessage parse(String line) {
        if (line==null || line.isEmpty())throw new IllegalArgumentException("IRC 缺少命令");
        if (line.indexOf('\r')>=0 || line.indexOf('\n')>=0 || line.indexOf('\0')>=0)
            throw new IllegalArgumentException("IRC 行内包含 CR、LF 或 NUL 控制字符");
        int i=0;
        if (line.charAt(0)=='@') {
            i=line.indexOf(' '); if (i<0) throw new IllegalArgumentException("IRC 标签缺少命令");
            while (i<line.length()&&line.charAt(i)==' ') i++;
        }
        String prefix="";
        if (i<line.length()&&line.charAt(i)==':') {
            int end=line.indexOf(' ',i); if(end<0) throw new IllegalArgumentException("IRC 来源缺少命令");
            prefix=line.substring(i+1,end); i=end+1;
        }
        while(i<line.length()&&line.charAt(i)==' ')i++;
        int start=i; while(i<line.length()&&line.charAt(i)!=' ')i++;
        if(start==i)throw new IllegalArgumentException("IRC 缺少命令");
        String command=line.substring(start,i).toUpperCase(Locale.ROOT);
        List<String> params=new ArrayList<>();
        while(i<line.length()) {
            while(i<line.length()&&line.charAt(i)==' ')i++;
            if(i==line.length())break;
            if(line.charAt(i)==':') { params.add(line.substring(i+1));break; }
            start=i;while(i<line.length()&&line.charAt(i)!=' ')i++;
            params.add(line.substring(start,i));
            if(params.size()>15)throw new IllegalArgumentException("IRC 参数过多");
        }
        return new IrcMessage(prefix,command,List.copyOf(params));
    }
    public String nick() { int at=prefix.indexOf('!');return at<0?prefix:prefix.substring(0,at); }
    public String trailing() { return params.isEmpty()?"":params.getLast(); }
}
