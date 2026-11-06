# dshjk

DeepSeek Harness 的独立 JVM SDK。一个 `com.elonzh:dshjk` 制品，同时支持 Java 17+ 与 Kotlin 2.2.0+，包名前缀 `com.elonzh.dshjk`。当前版本 `0.1.0-SNAPSHOT`，尚未远程发布。

SDK 直接启动原生 `dsh --profile sdk`，通过双向 JSONL JSON-RPC 2.0 通信。支持多 Session、文本/图片、原生命令、持久化恢复、工作目录、事件订阅、审批和用户问题。Kotlin 使用 suspend/Flow；Java 在同一类型上使用阻塞方法和 JDK `Flow.Publisher`。

## Kotlin

```kotlin
val config = HarnessConfig(
    dshHome = Path.of("/absolute/path/to/isolated-home"),
    cwd = Path.of("/absolute/path/to/workspace"),
    runtime = RuntimeCommand.node(Path.of("/path/to/@deepseek-ai/dsh/lib/bin.js")),
)
val harness = DeepSeekHarness(config)
try {
    val session = harness.session("my-session")
    val result = session.run("帮我分析这个项目")
    println(result.finalResponse)
} finally {
    harness.closeAsync()
}
```

完整导入和启动方式见 [Kotlin 示例](examples/kotlin/src/main/kotlin/example/KotlinExample.kt)。也可使用 `runtime = RuntimeCommand.executable("dsh")` 运行已安装的 CLI。

## Java

```java
var config = HarnessConfig.builder(Path.of("/absolute/path/to/isolated-home"))
    .cwd(Path.of("/absolute/path/to/workspace"))
    .runtime(RuntimeCommand.node(Path.of("/path/to/@deepseek-ai/dsh/lib/bin.js")))
    .build();
try (var harness = new DeepSeekHarness(config)) {
    var result = harness.session("my-session").run("帮我分析这个项目");
    System.out.println(result.getFinalResponse());
}
```

完整导入和启动方式见 [Java 示例](examples/java/src/main/java/example/JavaExample.java)。Java 无需 Continuation、runBlocking 或额外语言适配包。

## 构建与使用

构建需要 JDK 17（测试可切换 JDK 21），运行 npm CLI 需要 Node.js 24.9+。Gradle Wrapper、Kotlin 和依赖版本均已固定。`dsh` 由宿主安装；库不会下载运行时或读取默认的 `~/.dsh`，每次使用必须明确指定 Harness home。模型凭据通过原生 profile 或 `HarnessConfig.environment` 提供。

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

只检查启动与 Session 目录，无模型请求的示例：

```sh
./gradlew :examples:java:run --args='/path/to/dsh/lib/bin.js /path/to/isolated-home'
./gradlew :examples:kotlin:run --args='/path/to/dsh/lib/bin.js /path/to/isolated-home'
```

架构、完整操作对照、状态归属、配置、事件和扩展方式集中维护在 [SDK 使用与架构](docs/sdk.md)。兼容基线为 DeepSeek Harness `0.2.2-rc.1`；该上游处于 developer preview，升级时应重新运行原生测试。

项目使用 [Apache License 2.0](LICENSE)。第三方声明见 [NOTICE](NOTICE)。
