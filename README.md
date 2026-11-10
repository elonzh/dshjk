# dshjk

DeepSeek Harness 的独立 JVM SDK。核心制品 `com.elonzh:dshjk` 同时支持 Java 17+ 与 Kotlin 2.2.0+，包名前缀 `com.elonzh.dshjk`。当前版本 `0.1.0-SNAPSHOT`，尚未远程发布。

SDK 直接启动原生 `dsh --profile sdk`，通过双向 JSONL JSON-RPC 2.0 通信。支持多 Session、文本/图片、原生命令、持久化恢复、工作目录、事件订阅、审批和用户问题；内置事件载荷、状态与结束原因均提供类型化定义。Kotlin 使用 suspend/Flow；Java 在同一类型上使用阻塞方法和 JDK `Flow.Publisher`。

## Kotlin

```kotlin
val harness = DeepSeekHarness()
try {
    val session = harness.session()
    session.run("帮我分析这个项目", NotificationObserver { notification ->
        if (notification is HarnessNotification.Event && notification.sessionId == session.id) {
            when (val data = notification.event.data) {
                is SessionEventData.AssistantMessage -> {
                    data.message.content.filterIsInstance<ContentBlock.Text>().forEach { print(it.text) }
                    System.out.flush()
                }
                is SessionEventData.ToolCalled -> System.err.println("工具：${data.name}")
                is SessionEventData.TurnEnded -> System.err.println("结束：${data.reason.kind.wireName}")
                else -> Unit
            }
        }
    })
} finally {
    harness.closeAsync()
}
```

完整 [Kotlin 示例](examples/kotlin/src/main/kotlin/example/KotlinExample.kt) 使用独立协程收集 `Flow`，处理文本、工具、命令、状态与子 Agent 事件。默认使用 PATH 上的 `dsh`、官方 `sdk` profile 和当前工作目录。

## Java

```java
try (var harness = new DeepSeekHarness()) {
    var session = harness.session();
    session.run("帮我分析这个项目", notification -> {
        if (notification instanceof HarnessNotification.Event event && event.getSessionId().equals(session.getId())) {
            var data = event.getEvent().getData();
            if (data instanceof SessionEventData.AssistantMessage message) {
                for (var block : message.getMessage().getContent()) {
                    if (block instanceof ContentBlock.Text text) System.out.print(text.getText());
                }
                System.out.flush();
            } else if (data instanceof SessionEventData.ToolCalled call) {
                System.err.println("工具：" + call.getName());
            } else if (data instanceof SessionEventData.TurnEnded turn) {
                System.err.println("结束：" + turn.getReason().getKind().getWireName());
            }
        }
    });
}
```

完整 [Java 示例](examples/java/src/main/java/example/JavaExample.java) 使用 JDK `Flow.Subscriber`，展示 demand、错误传播、取消和队列排空。Java 无需 Continuation、runBlocking 或额外语言适配包。

## 会话与可选配置

`harness.run(input)` 自动创建独立会话，ID 遵循官方 SDK 的 `session-<32 位十六进制 UUID>` 格式。要连续对话，保留 `harness.session()` 返回的句柄并多次调用 `session.run(input)`；结果中的 `sessionId` 可用于后续恢复。也可通过 `session(id)` 使用显式 ID。

按需覆盖工作目录、home 或运行时路径：

```kotlin
val harness = DeepSeekHarness(HarnessConfig(
    cwd = Path.of("/path/to/workspace"),
    dshHome = Path.of("/path/to/isolated-home"),
    runtime = RuntimeCommand.node(Path.of("/path/to/@deepseek-ai/dsh/lib/bin.js")),
))
```

Java 使用 `HarnessConfig.builder().cwd(...).dshHome(...).runtime(...).build()`，各项均可省略。

## Spring Boot 4 与 Spring AI

添加可选 starter 后，可直接注入由 Spring 管理的 `DeepSeekHarness`：

```kotlin
implementation("com.elonzh:dshjk-spring-boot-starter:0.1.0-SNAPSHOT")
```

starter 保留原生默认行为，支持数据类 patch、类型化供应商配置、Bean 覆盖、回调注入和容器关闭时的进程回收。未显式配置模型时，可从现有 Spring AI OpenAI / Anthropic ChatModel 提取连接配置。Spring AI 的 `@Tool` / `ToolCallbackProvider` 经官方 MCP Server 接入 Harness；连接配置由 starter 转为原生启动 patch。

[Spring 集成文档](docs/spring.md)包含完整配置与用法；[Spring Boot 示例](examples/spring-boot/src/main/kotlin/example/spring/HarnessApplication.kt)提供工具注册、普通 HTTP 请求和 SSE 事件输出。

## 构建与使用

构建需要 JDK 17（测试可切换 JDK 21），运行 npm CLI 需要 Node.js 24.9+。Gradle Wrapper、Kotlin 和依赖版本均已固定。`dsh` 由宿主安装。省略 home 时，原生运行时使用 `DSH_HOME`，未设置或为空白时使用 `~/.dsh`；显式 `dshHome` 配置可覆盖它。模型凭据通过原生 profile 或 `HarnessConfig.environment` 提供。

```sh
./gradlew build
# 项目内安装未经修改的固定版 CLI，仅供开发验证
npm ci --prefix infra/dsh-runtime --no-audit --no-fund
DSHJK_TEST_CLI="$PWD/infra/dsh-runtime/node_modules/@deepseek-ai/dsh/lib/bin.js" ./gradlew build
# 使用已安装的 JDK 21 跑同一套测试
DSHJK_TEST_CLI="$PWD/infra/dsh-runtime/node_modules/@deepseek-ai/dsh/lib/bin.js" ./gradlew build -PtestJavaVersion=21
# 仅写入本仓库 build/repository
./gradlew publishAllPublicationsToLocalDevelopmentRepository
```

未设置 `DSHJK_TEST_CLI` 时，原生集成测试明确跳过；协议/进程测试仍需要 Node。两种语言的示例始终参与编译。所有模型测试使用本地 HTTP 夹具和临时 home，无需外部 API 密钥或 Testcontainers。

开发期消费本地 Maven 制品：

```kotlin
repositories {
    maven { url = uri("/absolute/path/to/dshjk/build/repository") }
    mavenCentral()
}
dependencies { implementation("com.elonzh:dshjk:0.1.0-SNAPSHOT") }
```

不传参数时只检查运行时和 Session 目录；传入提示词后持续输出文本和事件：

```sh
./gradlew :examples:java:run
./gradlew :examples:kotlin:run
# 运行模型并观察输出（需要已配置模型凭据）
./gradlew -q :examples:java:run --args='"Explain this project."'
./gradlew -q :examples:kotlin:run --args='"Explain this project."'
```

完整示例将 assistant/命令文本写入 stdout，事件与状态写入 stderr；结束前排空已收到的事件，避免遗漏最后一段输出。原生 SDK 的文本按模型步骤到达，具体语义见 [事件订阅与输出粒度](docs/sdk.md#事件订阅)。

架构、完整操作对照、状态归属、配置、事件和扩展方式集中维护在 [SDK 使用与架构](docs/sdk.md)。兼容基线为 DeepSeek Harness `0.2.2-rc.1`；该上游处于 developer preview，升级时应重新运行原生测试。

项目使用 [Apache License 2.0](LICENSE)。第三方声明见 [NOTICE](NOTICE)。
