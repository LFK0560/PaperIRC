# 参与贡献

欢迎通过 GitHub Issue 报告问题，或通过 Pull Request 提交改进。

开发需要 JDK 25；使用项目中的 Maven Wrapper 构建和验证：

```sh
export JAVA_HOME=/path/to/jdk-25
./mvnw -B clean verify
```

默认验证包含隔离 TLS 与桥接测试。真实 ngIRCd 集成、Paper 加载和游戏客户端验收步骤见 [verification/验收说明.md](verification/验收说明.md)。

报告问题时请提供 Paper/Java/ngIRCd 版本、复现步骤与相关报错。涉及聊天、昵称、连接或线程行为的修改，请补充能复现原问题的测试。游戏 API 应在 Paper 主线程调用，网络和文件操作应保留异步处理、有界队列与连接生命周期检查。

源码和文档贡献采用本项目的 [MIT License](LICENSE)。第三方文件保留其原有许可和声明。
