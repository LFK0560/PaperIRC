package cn.paperirc.irc;

import java.util.UUID;

public final class Nicknames {
    public static final int SUFFIX_LENGTH=5;
    private Nicknames() {}
    public static int baseLimit(String prefix,int nickLength) { return nickLength-prefix.length()-SUFFIX_LENGTH; }
    public static void validateBase(String base,String prefix,int nickLength) {
        int max=baseLimit(prefix,nickLength);
        if (base==null || !base.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("昵称主体只能使用 ASCII 字母、数字、下划线和连字符；不能留空或含空格、控制字符。无需输入前缀 "+prefix);
        if (base.length()>max || max<1)
            throw new IllegalArgumentException("昵称主体最多 "+Math.max(0,max)+" 字符（服务器上限 "+nickLength+"，前缀 "+prefix+"，另预留 5 字符冲突后缀）；不会截断昵称");
    }
    public static void validateFull(String name,int length) {
        if (name==null || !name.matches("[A-Za-z_][A-Za-z0-9_-]*") || name.length()>length)
            throw new IllegalArgumentException("IRC 昵称必须以 ASCII 字母或下划线开头，只允许字母、数字、_、-，总长度上限 "+length);
    }
    public static String candidate(String desired,UUID id,int attempt,int length) {
        String result=desired;
        if (attempt>0) {
            long hash=id.getMostSignificantBits() ^ id.getLeastSignificantBits();
            int code=(int)(hash + (attempt-1)*0x9e37L)&0xffff;
            result=desired+"_"+String.format(java.util.Locale.ROOT,"%04x",code);
        }
        validateFull(result,length);
        return result;
    }
}
