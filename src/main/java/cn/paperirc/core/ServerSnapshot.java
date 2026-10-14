package cn.paperirc.core;

import java.util.Locale;

public record ServerSnapshot(int online,int maxPlayers,double tps,double mspt,long uptimeMillis) {
    public String list() { return "在线人数："+online+"/"+maxPlayers; }
    public String status() {
        return String.format(Locale.ROOT,"在线：%d/%d | TPS（1 分钟）：%.2f | 平均 MSPT：%.2f ms | 运行：%s",
                online,maxPlayers,tps,mspt,uptime(uptimeMillis));
    }
    static String uptime(long ms) {
        long minutes=Math.max(0,ms)/60000,days=minutes/1440,hours=minutes%1440/60;
        return (days>0?days+"天":"")+(hours>0?hours+"小时":"")+(minutes%60)+"分钟";
    }
}
