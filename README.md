# PaperIRC 1.0.1

[![Java CI](https://github.com/LFK0560/PaperIRC/actions/workflows/ci.yml/badge.svg)](https://github.com/LFK0560/PaperIRC/actions/workflows/ci.yml)

面向 **Paper 26.3、Java 25** 的 Minecraft ↔ IRC 公共聊天插件。每个在线且开启 IRC 的玩家使用一条独立 TLS 连接；另有一条常驻机器人连接。20 名玩家全部开启时，频道中有 21 个真实客户端。

采用 Java 25、Maven 3.9.13 Wrapper 和 JDK TLS/虚拟线程。IRC 实现包含注册、ISUPPORT、频道加入、改名确认、数值错误、PING/PONG 和有界发送队列。运行时使用 Paper 提供的 Adventure、YAML API 及其 SnakeYAML，没有附加运行时库、NMS、反射或原生库。Paper API 依赖为 `provided`，不会放入插件 JAR。

## 1.0.1 连接修复

修复 ngIRCd 欢迎信息（MOTD）以 `CR CR LF` 结尾时，插件报“IllegalArgumentException：非法 IRC 行”并重连的问题。只兼容帧边界多余的 CR，保留行内 CR/LF/NUL 的拒绝检查；按 [RFC 2812 §2.3.1](https://www.rfc-editor.org/rfc/rfc2812#section-2.3.1) 忽略空消息。欢迎信息中的中文、emoji 和普通空格保持原样。

已在真实 ngIRCd 27 公共服务上使用 Java 25 默认 CA 信任库和主机名校验实测：旧 1.0.0 JAR 复现原报错，新 1.0.1 JAR 注册成功并读完 24 行 MOTD。连接时请使用证书 SAN 覆盖的域名；证书未包含 IP 地址时，直接填写 IP 会产生证书主机名不匹配。公共服务器测试仅进行 TLS 和注册，未加入频道或发送聊天。

升级时正常停服，用 `PaperIRC-1.0.1.jar` 替换旧插件 JAR，确保 `plugins/` 中只保留一份 PaperIRC，再启动服务器。现有 `config.yml` 和 UUID 玩家设置可继续使用；`/irc reload` 只重载配置，不能替换已加载的插件代码。为了支持最长游戏名与冲突后缀，建议管理员按下一节将 `MaxNickLength` 调整为 32。

## 安装

1. 使用 Java 25 启动 Paper 26.3。已核实并固定 API 为 `io.papermc.paper:paper-api:26.3.build.178-stable`，描述文件使用 `api-version: '26.3'`。
2. 从 [GitHub Releases](https://github.com/LFK0560/PaperIRC/releases/latest) 下载 `PaperIRC-1.0.1.jar`，放进服务端 `plugins/`，正常启动服务器。Release 同时提供完整源码/成品 ZIP 和 SHA-256 校验值。
3. 首次生成的 `plugins/PaperIRC/config.yml` 默认 `enabled: false`。填写真实主机、直接 TLS 端口和单个频道后改为 `true`。
4. 在控制台执行 `irc reload`，再执行 `irc adminstatus` 检查机器人状态。游戏中使用 `/irc status` 查看个人状态。

最少需要修改：

```yaml
enabled: true
irc:
  host: irc.example.invalid # 占位；必须改成真实证书域名
  port: 6697
  channel: '#minecraft'
```

以上只是需要修改的字段，**不要用这段覆盖完整配置**。JAR 内及 `src/main/resources/config.yml` 提供全部选项与中文注释。`irc.example.invalid`、6697、`#minecraft` 是占位示例，必须按实际部署填写/核实；`.invalid` 地址不会触发连接重试。

TLS 强制开启。JDK 默认信任库验证公共 CA 证书链和连接主机名；插件没有忽略证书、STARTTLS/明文回退或生产自定义信任库选项。不要向插件填写服务器密码、SASL、NickServ 密码或 IRC Operator 信息。

## ngIRCd 的必要调整

独立玩家身份会让多个 IRC 客户端从 Minecraft 服务器的同一出口 IP 接入。ngIRCd 官方示例中 `MaxConnectionsIP` 默认 5、`MaxNickLength` 默认 9，无法满足本项目的连接数量及昵称规则。`MC_` 加最长 16 字符游戏名需要 19 字符；冲突时还需 5 字符后缀。

在**现有配置**的 `[Limits]` 中修改相应项即可，例如：

```ini
[Limits]
MaxConnectionsIP = 32
MaxNickLength = 32
```

同时核对 `MaxConnections` 总额度、频道人数限制，以及其他共享出口 IP 的客户端。20 名开启的玩家加机器人至少需要 21 个名额，32 是建议余量；不要仅修改插件配置来掩盖服务端限制。IRC 网络内各服务器的昵称长度规则也应一致。

在已有 TLS 配置中核对类似这些字段；保留其余配置：

```ini
[SSL]
Ports = 6697
CertFile = /etc/letsencrypt/live/irc.your-domain.example/fullchain.pem
KeyFile = /etc/letsencrypt/live/irc.your-domain.example/privkey.pem
```

ngIRCd 必须编译了 TLS 支持，运行账户应能读取证书链和私钥，防火墙应允许相应端口。连接域名必须与证书 SAN 匹配，证书需在有效期内。目标频道必须允许这些普通客户端加入和发言：检查邀请、密码、封禁、人数上限及发言模式。插件不会申请 Operator 权限或修改频道权限。

先用部署机器的实际 `ngircd --version` 和 `ngircd --configtest --config /实际路径/ngircd.conf` 核对语法。修改生效方式应按该版本的手册和服务管理方式执行；通常可用服务配置重载/SIGHUP，涉及监听端口等启动参数时可能需要受控重启。查看重载结果和 ngIRCd 日志，确认配置真的生效。插件的 `nickname-length-fallback` 应与 `MaxNickLength` 一致；收到有效 `005 NICKLEN` 后优先采用服务端公布值。

官方资料：[ngIRCd 文档](https://ngircd.barton.de/documentation.php.en)、[官方示例配置](https://github.com/ngircd/ngircd/blob/master/doc/sample-ngircd.conf.tmpl)。

## 游戏指令和权限

| 指令 | 功能 | 权限（普通玩家默认允许） |
| --- | --- | --- |
| `/irc on` | 开启并异步连接 | `paperirc.on` |
| `/irc off` | 关闭个人连接及双向同步 | `paperirc.off` |
| `/irc nick <主体>` | 保存主体并申请带前缀的昵称 | `paperirc.nick` |
| `/irc resetnick` | 恢复当前游戏名的默认规则 | `paperirc.resetnick` |
| `/irc status` | 开关、阶段、保存主体、实际昵称、机器人可用性 | `paperirc.status` |
| `/irc help` | 游戏端指令帮助 | `paperirc.help` |

玩家只能修改自己的 UUID 设置。主体只允许 ASCII 字母、数字、下划线和连字符，不允许空格、控制字符、空值或超长。**无需输入前缀**：`/irc nick Alex` 默认申请 `MC_Alex`。默认前缀 `MC_` 可配置，必须非空且合法。

总昵称上限取服务端 `NICKLEN`（插件支持的安全上限 64）或配置回退值。主体上限为“总上限 − 前缀长度 − 5”，默认 32、前缀 `MC_` 时最多 24 字符。默认游戏名也使用同样校验，服务器上限太小时明确报错，不截断。机器人也需预留 5 字符。

被占用时按服务器 CASEMAPPING 规则有限重试，追加 `_a1b2` 形式的临时后缀；状态和反馈显示最终昵称。临时后缀不会覆盖保存的主体。在线改名使用现有连接，收到服务器确认后更新身份；明确拒绝时保留原昵称。确认超时会通过内部只读 WHOIS 数值回复核对自己的实际身份，保留仍有效的原昵称；连核对也超时才结束这个无法确认的会话并退避重连。

关闭时仍可设置昵称，连接完成前聊天只留在游戏中。`/irc off` 在下一次登录、重启、配置重载、手动重连和自动重连后持续生效。重新开启不会补发过去消息。默认昵称随游戏账号名更新，自定义主体保持不变。默认新玩家开启个人 IRC，但管理员尚未启用全局桥接时不会建连。

| 管理指令 | 权限（默认 OP） |
| --- | --- |
| `/irc reload` | `paperirc.reload` |
| `/irc adminstatus` | `paperirc.adminstatus` |
| `/irc reconnect` | `paperirc.reconnect` |

控制台可执行管理指令。重载先异步读取并整体校验；失败保留当前配置。格式、查询与发送限流参数可直接生效；主机、端口、频道、前缀、超时等连接配置改变时有序重建连接。手动重连只包含机器人和在线且个人开启的玩家，不修改偏好。

插件仅注册 `/irc`，没有全局 `/nick`、`/list` 或原始协议入口。

## 公共聊天与查询

游戏普通聊天使用发言者自己的 IRC 连接发送，IRC 显示 `<MC_Steve> 正文`。游戏本地聊天保持原样，尊重被取消的 Paper `AsyncChatEvent`。IRC 入站由机器人统一处理，游戏默认显示 `[IRC] <Alice> 正文`。

接收对象为在线且个人开关开启的玩家，即使该玩家个人连接暂时断开，只要机器人可用仍可收到入站消息。关闭的玩家不收消息。身份过滤使用当前受管理连接及机器人按 JOIN/NICK/QUIT 顺序观察到的身份；外部 `MC_` 用户仍能正常发言。两次相同正文会作为两次消息处理。机器人回复在统一出站路径同步到游戏，不依赖服务端自回显；存在自回显也不会重复。

`chat.format` 是纯文本模板，必须保留 `[IRC]`、`{nick}`、`{message}` 各一次。昵称和正文始终通过 `Component.text` 发送；MiniMessage、点击标签及 `§` 文字不会生成可执行组件。保留普通 Unicode、中文和 emoji，可移除 IRC 颜色与控制格式。游戏命令、私信、CTCP `/me`、JOIN/PART 管理事件和系统通知不桥接。

游戏和 IRC 客户端都可以在频道普通聊天中输入：

| 文本 | 返回 |
| --- | --- |
| `!list` | 真实在线人数/服务器上限，无名单 |
| `!status` | 人数、1 分钟 TPS、平均 MSPT、进程运行时间 |
| `!help` | 仅说明三个查询文本 |

忽略大小写及两端空白，不匹配句子中间的指令；已知命令带参数给出帮助，其他文本是普通聊天。游戏查询也通过发言玩家的个人连接发送，机器人处理后回复到频道和游戏。关闭或未加入频道的玩家不会由机器人代发请求。

默认每个发言者 5 秒、全局 1 秒冷却，反馈另有限流。游戏身份按 UUID 计冷却，外部 IRC 按实际来源身份计冷却，并在 NICK 事件中迁移冷却状态。机器人不执行游戏命令、控制台操作或个人设置修改，不展示内存、版本、名单、UUID 或身份对应表。

统计快照在游戏主线程每秒更新，玩家进出时也更新人数。人数来自 `Server.getOnlinePlayers()`，上限来自 `getMaxPlayers()`，TPS 取 `getTPS()[0]`。MSPT 使用 `getAverageTickTime()`；固定目标构建的公开实现使用最近 **5 秒** MSPT 报告。机器人首次加入或恢复时先用内部只读 WHO 核对频道身份；核对期间入站/查询显示为加入中，已有个人连接继续发送。此短暂阶段的新改名请求会保存，随后通过原连接申请，避免两个 Socket 的事件先后差异造成回流。WHO 数据只供身份过滤，不作为查询回复、日志或保存数据。

运行时间来自 `RuntimeMXBean.getUptime()`，代表当前 JVM 进程运行时间，插件重载不会重置它。

## 限流、重连和关闭

每个连接默认每秒发送 2 段，缓冲 64 段。所有消息遵守完整 512 字节 IRC 帧预算，默认另预留 200 字节转发来源前缀；UTF-8 分段保持顺序，不切开代理对，尽量在空白边界分段。超出 8192 字符/64 段或缓冲容量时拒绝整条并提示，不静默截断。特殊服务器若使用更长 cloaked host，可增大前缀预留值。

异常断线后从 1 秒指数退避到最多 60 秒，加少量抖动，稳定 30 秒后重置。所有连接共享默认每秒 2 条建连速率。旧会话先关闭再允许同 UUID 新会话建立，代次检查丢弃迟到回调。每个会话的发送队列独立，断开会清空；不存储、回放或补发断线期间聊天。

机器人断线时，已加入频道的个人连接可继续向 IRC 发送，入站及查询暂停；玩家收到一次状态变化提示，恢复后恢复入站。普通连接失败重复反馈与日志有冷却。`INFO` 记录状态和错误概要，不记录聊天正文；`DEBUG` 额外记录建连次数、NICKLEN 和 CASEMAPPING，也不记录正文。

网络读写、DNS/TLS、注册、重连及文件 I/O 都在工作线程。IRC 状态由一个独立串行线程管理，网络使用虚拟线程；游戏 API 和消息投递只在主线程。跨线程队列和发送队列均有上限。DNS 使用 JVM/操作系统解析器，其取消受系统解析实现影响；同 UUID 的旧任务未结束前不再启动新连接，避免堆积。正常停用发送尽力 QUIT，并在默认 5 秒总时限内关闭网络、刷新排队的设置保存及取消任务。

## 玩家数据

`plugins/PaperIRC/players.yml` 以 UUID 为键，只保存开关及可空自定义主体：

```yaml
12345678-1234-1234-1234-123456789012:
  enabled: false
  nicknameBase: Alex
```

`nicknameBase` 缺省/空值代表默认游戏名规则。文件异步串行保存，通过同目录临时文件、磁盘刷新和原子替换写入。成功写入后才反馈“设置已保存”。保存失败时当前会话偏好继续生效，但明确提示重启可能恢复旧设置。

数据文件损坏时启动失败并保留原文件，不会自动清空覆盖。加载后发现外部修改也会阻止覆盖。请停服后编辑/恢复备份，再启动；`/irc reload` 只重载 `config.yml`，不会重新读玩家数据。关闭超时或磁盘故障的未完成保存会在日志明确说明。

## 构建与验证

```sh
git clone https://github.com/LFK0560/PaperIRC.git
cd PaperIRC
export JAVA_HOME=/path/to/jdk-25
./mvnw -B clean verify
```

Windows 使用 `mvnw.cmd -B clean verify`。Wrapper 固定 Maven 3.9.13，首次联网下载，构建无需系统 Maven。`./build.sh` 是便捷入口，使用 `JAVA_HOME` 或 `PATH` 中的 JDK；构建需要 Java 25。GitHub Actions 在推送和 Pull Request 时执行同样的构建及核心测试。

产物：`target/PaperIRC-1.0.1.jar`。关键测试使用隔离的测试 CA、TLS Socket 服务器和真实多连接。测试证书不写入系统/JVM 默认信任库，不影响生产配置。自动化测试与真实 ngIRCd 的验收步骤、已执行结果见 [verification/验收说明.md](verification/验收说明.md)。

本地 Paper 加载及无人在线查询验证脚本是 `verification/run_paper.py`，只创建独立目录，不使用真实服务器的数据目录。需 Python 3、OpenSSL、JDK 25、官方 Paper build 178 JAR和已接受的测试服 EULA 文件。运行：

```sh
python3 verification/run_paper.py \
  --java-home /path/to/jdk-25 \
  --paper-jar /path/to/paper-26.3-178.jar \
  --accepted-eula-file /path/to/test-server/eula.txt
```

可选 `--cache-from /path/to/another-test-server` 仅复制库、缓存和版本文件以节省下载。测试服只绑定 `127.0.0.1`，使用 offline mode、单独测试信任库；这些参数不应用于正式部署。测试结果和日志在 `verification/`，运行数据/测试私钥位于忽略的 `verification/runtime/`。

## 故障排查

先查看 `/irc status` 和 `/irc adminstatus` 的阶段/最近错误，再结合 Paper 和 ngIRCd 日志：

- TLS 错误：核对域名 SAN、有效期、系统时钟、完整证书链、JVM 默认 CA 信任库及 TLS 监听端口。
- 接入数达到限制：检查同 IP 和总连接额度，释放离线客户端，按部署容量调整 ngIRCd。
- 432/昵称长度：核对 `NICKLEN`、`MaxNickLength`、前缀及 5 字符后缀预留；不要通过截断或去掉前缀修复。
- 433/冲突上限：换主体或让管理员检查占用；机器人真实昵称也会显示自动后缀。
- 471/473/474/475/404/被踢：检查频道人数、邀请、封禁、密码和发言权限；插件不会改变这些设置。
- 机器人离线、玩家已加入：属于部分可用状态，个人发送继续，入站/查询等机器人重连。
- 数据读取/保存失败：修复磁盘、目录权限或 YAML 后重启，原文件保留；及时备份。

Paper 官方依据：[版本列表](https://fill.papermc.io/v3/projects/paper)、[Java 要求](https://docs.papermc.io/paper/getting-started/)、[API 项目设置](https://docs.papermc.io/paper/dev/project-setup/)、[plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/)、[固定构建的 MSPT 实现](https://github.com/PaperMC/Paper/blob/03226a58389b1327f749567b809ef13f31adac09/paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java)。

## 许可证与贡献

PaperIRC 原创源码及文档采用 [MIT License](LICENSE)，版权署名为 `Copyright (c) 2026 LFK0560`。构建出的插件 JAR 在 `META-INF/LICENSE` 中包含同一许可证。

Maven Wrapper 保留上游 Apache License 2.0，详见 [第三方许可说明](THIRD_PARTY_NOTICES.md)。欢迎在 [GitHub Issues](https://github.com/LFK0560/PaperIRC/issues) 报告问题或提交 Pull Request；开发与验证要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。
