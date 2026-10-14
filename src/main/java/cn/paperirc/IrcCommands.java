package cn.paperirc;

import cn.paperirc.core.Bridge;
import io.papermc.paper.command.brigadier.*;
import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

/** Only this local command surface can mutate settings. IRC gets no command dispatcher. */
public final class IrcCommands implements BasicCommand {
    private static final List<String> SUBS=List.of("on","off","nick","resetnick","status","help","reload","adminstatus","reconnect");
    private final PaperIrcPlugin plugin;
    public IrcCommands(PaperIrcPlugin plugin){this.plugin=plugin;}
    @Override public void execute(CommandSourceStack source,String[] args) {
        CommandSender sender=source.getSender();String sub=args.length==0?"help":args[0].toLowerCase(Locale.ROOT);
        if(!SUBS.contains(sub)){say(sender,"未知指令，请使用 /irc help");return;}
        boolean console=sender instanceof ConsoleCommandSender||sender instanceof RemoteConsoleCommandSender;
        if(!console&&!sender.hasPermission("paperirc."+sub)){say(sender,"没有权限：paperirc."+sub);return;}
        int length=sub.equals("nick")?2:sub.equals("help")&&args.length==0?0:1;
        if(args.length!=length){say(sender,sub.equals("nick")?"用法：/irc nick <昵称主体>；无需输入前缀":"用法：/irc "+sub);return;}
        if(sub.equals("help")){help(sender);return;}
        if(!plugin.ready()){say(sender,"插件正在加载或停用，请稍后重试并检查控制台");return;}
        Bridge b=plugin.bridge();
        switch(sub) {
            case "reload" -> plugin.respond(sender,plugin.reloadFiles());
            case "reconnect" -> plugin.respond(sender,b.reconnect());
            case "adminstatus" -> plugin.respond(sender,b.overallStatus().thenApply(s->
                    "桥接："+(s.active()?"启用":"停用")+" | 机器人："+s.bot().label()+" / "+display(s.botNick())+
                    " | 个人连接："+s.playerConnections()+"（已加入 "+s.joinedPlayers()+"） | 待重连："+s.reconnecting()+
                    " | 最近错误："+display(s.error())));
            default -> {
                if(!(sender instanceof Player p)){say(sender,"个人设置只能由游戏玩家操作自己的 UUID");return;}
                switch(sub) {
                    case "on" -> plugin.toggle(p,true);
                    case "off" -> plugin.toggle(p,false);
                    case "nick" -> plugin.respond(sender,b.nickname(p.getUniqueId(),args[1]));
                    case "resetnick" -> plugin.respond(sender,b.nickname(p.getUniqueId(),null));
                    case "status" -> plugin.respond(sender,b.playerStatus(p.getUniqueId()).thenApply(s->
                            "个人开关："+(s.enabled()?"开":"关")+" | 状态："+s.phase().label()+
                            " | 保存主体："+(s.savedBase()==null?"默认（当前游戏名）":s.savedBase())+
                            " | 实际昵称："+display(s.actualNick())+" | 机器人入站/查询："+(s.inboundAvailable()?"可用":"暂不可用")+
                            " | 昵称总上限："+s.nicknameLimit()+" | 原因："+display(s.error())+
                            (b.settings().active()?"":" | "+b.settings().inactiveReason())));
                    default -> { }
                }
            }
        }
    }
    private static String display(String s){return s==null||s.isEmpty()?"无":s;}
    private static void say(CommandSender sender,String text){sender.sendMessage(Component.text("[PaperIRC] "+text));}
    private void help(CommandSender sender) {
        say(sender,"/irc on：开启；/irc off：关闭并断开；/irc status：查看个人状态");
        say(sender,"/irc nick <主体>：保存昵称，无需输入前缀；/irc resetnick：恢复当前游戏名规则");
        say(sender,"/irc help：插件帮助；普通聊天 !list、!status、!help：经个人 IRC 身份查询机器人");
        if(sender.hasPermission("paperirc.reload")||sender.hasPermission("paperirc.adminstatus")||sender.hasPermission("paperirc.reconnect"))
            say(sender,"管理：/irc reload（校验重载）、/irc adminstatus（整体状态）、/irc reconnect（重连开启的连接）");
    }
    @Override public Collection<String> suggest(CommandSourceStack source,String[] args) {
        CommandSender sender=source.getSender();
        if(args.length<=1) {
            String prefix=args.length==0?"":args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s->s.startsWith(prefix)&&sender.hasPermission("paperirc."+s)).toList();
        }
        if(args.length==2&&args[0].equalsIgnoreCase("nick")&&sender instanceof Player p&&sender.hasPermission("paperirc.nick"))
            return p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))?List.of(p.getName()):List.of();
        return List.of();
    }
}
