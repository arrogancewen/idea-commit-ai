# AI Commit Message

**用你自己的模型，为 IntelliJ IDEA 的 Commit 窗口生成 commit message。**

在 Amend 旁边加一个按钮，把选中的改动变成一条 commit message —— 但走的是**你自己配置的模型端点**
（DeepSeek / OpenAI / Anthropic / Ollama / OpenRouter / 任何 OpenAI 兼容网关），而不是某个订阅服务。

> [English](README.md) | 简体中文

---

## 目录

- [特性](#特性)
- [安装](#安装)
- [配置](#配置)
- [输出示例](#输出示例)
- [输出长度如何决定](#输出长度如何决定)
- [它是怎么实现的](#它是怎么实现的)
- [从源码构建](#从源码构建)
- [项目结构](#项目结构)
- [安全](#安全)
- [兼容性](#兼容性)
- [已知限制](#已知限制)
- [许可证](#许可证)

---

## 特性

| | |
|---|---|
| **任意模型端点** | OpenAI 兼容（`/chat/completions`）与 Anthropic 兼容（`/v1/messages`）两种请求形状；模型名是自由文本 |
| **长度自适应** | 1–2 个文件 → 一行；55 个文件 → 摘要 + 按模块分组的要点。不是固定档位 |
| **业务语义** | 提示词强制描述**业务/行为变化**，禁止罗列类名、方法名、表名、存储过程、SQL |
| **代码兜底** | 模型不听话也会被裁剪：去掉 ```` ``` ```` 围栏、前言、引号、结尾句号，并强制限长 |
| **大提交不丢区域** | 每个文件分到均等字符预算并各自截断；**完整文件清单**始终发给模型 |
| **安全存 key** | API key 进 IDEA 凭据库（Windows DPAPI），不落明文文件 |
| **可取消** | 生成中按钮变成停止图标，再点一次即取消 |
| **零第三方依赖** | 只用 JDK 自带的 `java.net.http` 和手写 JSON，没有 Gson/Jackson |

## 安装

插件以 jar 形式分发（`Install Plugin from Disk`）。

1. 从 [Releases](../../releases) 下载最新的 `idea-commit-ai-*.jar`
   （还没有 Release 的话见下方 [从源码构建](#从源码构建)，一条命令即可产出）
2. IDEA → `Settings` → `Plugins` → 齿轮图标 → **Install Plugin from Disk...**
3. 选择 jar → 重启 IDE

## 配置

`Settings` → `Tools` → **AI Commit Message**

| 字段 | 说明 |
|---|---|
| **API protocol** | `OpenAI-compatible` 或 `Anthropic-compatible` |
| **Base URL** | 端点根地址，见下表 |
| **Model** | 模型名，自由文本 |
| **API key** | 存进凭据库，不写入配置文件 |
| **Detail level** | 默认 `auto`，见[下一节](#输出长度如何决定) |
| **Max diff characters** | 发给模型的 diff 预算，默认 60000 |
| **Request timeout** | 默认 90 秒 |

点 **Test connection** 会真的发一次最小请求并回显模型答案，用来确认 key、URL、模型名都对。

### 常见端点

| 服务 | API protocol | Base URL | Model 示例 |
|---|---|---|---|
| DeepSeek | OpenAI-compatible | `https://api.deepseek.com/v1` | `deepseek-chat` |
| DeepSeek | Anthropic-compatible | `https://api.deepseek.com/anthropic` | `deepseek-chat` |
| OpenAI | OpenAI-compatible | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | Anthropic-compatible | `https://api.anthropic.com` | `claude-sonnet-4-5` |
| Ollama（本地） | OpenAI-compatible | `http://localhost:11434/v1` | `qwen2.5-coder` |
| OpenRouter | OpenAI-compatible | `https://openrouter.ai/api/v1` | 任意模型 id |

## 输出示例

选中 2 个文件时，只出一行：

```
feat(erp): 聚水潭库存回调改为幂等分账入账并同步缓存
```

选中一个跨多个模块的大提交时，摘要 + 按关注点分组：

```
fix(refund): 确保库存回滚完成后再落终态

- 退款成功时先恢复库存，再推进订单状态，避免状态与库存不一致
- 库存副作用统一在所有变更提交后执行
- 退款回滚仅对实物退款生效，虚拟预约与已完成订单不再误触发
- 库存变更改为原子执行，消除并发下的超卖
```

注意要点里**没有**出现类名、方法名、表名或存储过程名 —— 这是刻意设计的。

## 输出长度如何决定

固定档位没法同时满足两种场景：勾 1 个文件时一行就准，勾 50 个文件时一行就太少。
所以默认是 `auto`，按选中的文件数决定要点数上限：

| 选中文件数 | 产出 |
|---|---|
| 1–2 | 只一行摘要（≤72 字符） |
| 3–8 | 一行 + 最多 2 条要点 |
| 9–25 | 一行 + 最多 4 条要点 |
| 26–60 | 一行 + 最多 6 条要点 |
| >60 | 一行 + 最多 8 条要点 |

要点要求**按关注点/模块分组**，提示词里明确写了 `NEVER write one bullet per file`。

也可以手动锁定：`subject only` / `subject + brief body`（最多 3 条）/ `detailed`。

**为什么要在代码里裁剪而不只靠提示词**：模型不保证听话。`CommitMessageFormatter` 负责
去掉代码围栏、前言（如 `Here is a commit message:`）、首尾引号、结尾句号，并按预算截断 ——
这一段有回归测试覆盖。

## 它是怎么实现的

### 按钮位置

不靠猜测，`Vcs.MessageActionGroup` 就是 Amend 右边那一排。一个普通 `AnAction` 注册进去即可，
不需要任何专用扩展点：

```xml
<actions>
  <action id="CommitAi.GenerateCommitMessage"
          class="com.commitai.idea.GenerateCommitMessageAction">
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="last"/>
  </action>
</actions>
```

### 关键 API

| 环节 | API |
|---|---|
| 读写消息框 | `VcsDataKeys.COMMIT_MESSAGE_CONTROL` → `CommitMessageI.setCommitMessage(String)` |
| 取选中的改动 | `VcsDataKeys.COMMIT_WORKFLOW_UI` → `getIncludedChanges()` / `getIncludedUnversionedFiles()` |
| 兜底 | `VcsDataKeys.COMMIT_WORKFLOW_HANDLER` 转 `AbstractCommitWorkflowHandler` → `getUi()` |
| 生成 diff | `IdeaTextPatchBuilder.buildPatch(...)` + `UnifiedDiffWriter.write(...)` |
| 未跟踪文件 | `new Change(null, new CurrentContentRevision(filePath))` |

### 线程模型

`CommitWorkflowUi` 是 UI 模型，**只能在 EDT 上读**。所以流程是：

1. `actionPerformed`（EDT）→ `DiffCollector.capture(ui)` 抓一份选中改动的快照
2. `Task.Backgroundable`（后台）→ 算 diff、发 HTTP
3. `onSuccess`（回到 EDT）→ `setCommitMessage(...)`

### 字符预算

大提交不能把整个 diff 塞进去。分配策略是**按文件均分**而不是"丢掉最大的"：

- 每个文件拿到 `clamp(maxDiffChars / 文件数, 800, 20000)` 字符
- 超出就各自截断，并标注 `... (diff truncated)`
- **完整文件清单**始终进提示词 —— 这样即使某个文件的 diff 被截断，模型仍知道该区域存在

## 从源码构建

### 方式一：离线构建（推荐，无需网络）

```bash
pwsh -File build.ps1
```

自动探测本机 IDEA 与 JDK；探测不到就显式指定：

```bash
pwsh -File build.ps1 -IdeaHome '/Applications/IntelliJ IDEA.app/Contents' -JdkHome "$JAVA_HOME"
```

产物：`build/dist/idea-commit-ai-<version>.jar`

原理是直接对已安装 IDE 的 `lib/*.jar` 和 `lib/modules/*.jar` 编译，因此不需要下载 ~1 GB 的
Platform SDK，也不需要 Gradle。

### 方式二：Gradle（需要网络）

```bash
gradle buildPlugin
```

使用 IntelliJ Platform Gradle Plugin 2.19.0。`build.gradle.kts` 默认通过 `local("<IDEA 路径>")`
复用本机 IDE；改成 `create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.3")` 可在任意机器构建。
需要 `runIde` 沙箱调试时走这条路。

### 运行测试

```bash
pwsh -File test.ps1
```

40 项离线断言，覆盖两个最容易出错的部分：

- `Json` 解析器与两种响应形状（含转义、`\u` 解码、嵌套取值、402/404 错误文案）
- `CommitMessageFormatter` 的输出形状与自适应预算（含真实场景：2 个文件 → 单行；55 个文件 → 摘要 + 要点）

## 项目结构

```
idea-commit-ai/
├── build.ps1                     # 离线构建（自动探测 IDEA / JDK）
├── test.ps1                      # 运行回归测试
├── build.gradle.kts              # IntelliJ Platform Gradle 构建
├── LICENSE
├── src/main/
│   ├── java/com/commitai/idea/
│   │   ├── GenerateCommitMessageAction.java    # 工具栏按钮（update / actionPerformed）
│   │   ├── CommitMessageGenerator.java         # 项目级服务：后台任务、取消、通知
│   │   ├── DiffCollector.java                  # 改动 → 统一 diff → 字符预算
│   │   ├── PromptTemplates.java                # 提示词（业务语义 / 分组 / 中英文）
│   │   ├── CommitMessageFormatter.java         # 输出形状与自适应预算（代码兜底）
│   │   ├── LlmClient.java                      # OpenAI / Anthropic 双协议
│   │   ├── Json.java                           # 零依赖 JSON 读写
│   │   ├── CommitAiSettings.java               # 持久化设置 + 凭据库
│   │   └── CommitAiSettingsConfigurable.java   # 设置页 UI
│   └── resources/META-INF/plugin.xml
└── test/java/                    # 离线回归测试
    ├── FormatterSmokeTest.java
    └── JsonSmokeTest.java
```

## 安全

- **API key 不落明文**：通过 `PasswordSafe` 存进 IDE 凭据库（Windows 上是 DPAPI）。
  `commit-ai.xml` 里只有 URL、模型名这类非敏感配置。
- **不进模型的内容**：只有你选中提交的那些 diff。没有遥测，没有中间服务器。
- **`Test connection`** 会发送一个极小的真实请求来做连通性验证。

## 兼容性

- 目标平台：IntelliJ IDEA 2024.1+（`since-build="241"`），在 **IDEA 2025.3（build 253）** 上验证
- 只依赖 `com.intellij.modules.platform` 与 `com.intellij.modules.lang`，因此**Git / SVN / Mercurial
  仓库都能用**
- 平台 API 签名逐个用 `javap` 对照实际 IDE 安装核对过，不是凭记忆写的

## 已知限制

- 只适配新的 **Commit 工具窗口**；旧的「Commit Changes」模态对话框未测试
- 多仓库（multi-root）提交时按当前仓库的选中改动生成，不会跨仓库合并成一条描述
- 二进制文件、以及删除后不可读的文件会被静默跳过
- 提示词本身是英文的，输出语言由设置里的 `auto / zh / en` 控制
- 不含流式输出：一次性返回，默认超时 90 秒

## 许可证

[MIT](LICENSE)
