# AI Commit Message (Custom Model) — IntelliJ IDEA 插件

参考 GitHub Copilot 那个 commit 按钮，自己实现一个**接自定义模型**的版本。

Copilot 的按钮在 AI Credits 用满后会返回 `HTTP 402`（见文末背景）。这个插件不依赖 Copilot 额度：
它把 diff 发到**你自己配置的模型端点**（DeepSeek / OpenAI / Anthropic / Ollama / OpenRouter / 任何
OpenAI 兼容网关），把生成结果写回 Commit 消息框。

---

## 快速开始（本机已验证路径，不需要网络）

```powershell
pwsh -File D:\myconfig\idea-commit-ai\build.ps1
```

产物：`build\dist\idea-commit-ai-1.2.0.jar`

安装：

1. IDEA → `Settings` → `Plugins` → 齿轮图标 → **Install Plugin from Disk...**
2. 选择上面的 jar
3. 重启 IDEA

配置：

1. `Settings` → `Tools` → **AI Commit Message**
2. 填 Base URL / Model / API key（见下表），点 **Test connection** 确认连通
3. 打开 Commit 工具窗口（`Ctrl+K`），工具栏会出现一个**灯泡图标**按钮，位置就在 Amend 右边那一排
   —— 和 Copilot 按钮同一处

### 常见端点配置

| 服务 | API protocol | Base URL | Model 示例 |
|---|---|---|---|
| DeepSeek（OpenAI 兼容，推荐） | OpenAI-compatible | `https://api.deepseek.com/v1` | `deepseek-chat` |
| DeepSeek（Anthropic 兼容） | Anthropic-compatible | `https://api.deepseek.com/anthropic` | `deepseek-chat` |
| OpenAI | OpenAI-compatible | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | Anthropic-compatible | `https://api.anthropic.com` | `claude-sonnet-4-5` |
| Ollama（本地） | OpenAI-compatible | `http://localhost:11434/v1` | `qwen2.5-coder` |
| OpenRouter | OpenAI-compatible | `https://openrouter.ai/api/v1` | 任意模型 id |

> Model 是**自由文本**，端点接受什么就填什么。

### 输出长度：随改动规模自适应（v1.2.0 起）

`Detail level` **默认 `auto`** —— 长度跟着改动规模走。固定档位不可能同时满足两种场景：
勾 1 个文件时一行就准，勾 55 个文件时一行就太少。

| 选中文件数 | 自动产出 |
|---|---|
| 1–2 | 只一行摘要（≤72 字符） |
| 3–8 | 一行 + 最多 2 条要点 |
| 9–25 | 一行 + 最多 4 条要点 |
| 26–60 | 一行 + 最多 6 条要点 |
| >60 | 一行 + 最多 8 条要点 |

要点按**关注点/模块分组**，提示词明确禁止"一个文件一条"。也可在设置里手动锁定
`subject only` / `subject + brief body` / `detailed`。

另外，大提交不再"整文件丢弃"：每个文件分到均等的字符预算并各自截断，同时**始终把完整
文件清单**发给模型 —— 否则 55 个文件里被丢掉的那些区域模型根本不知道存在，只能写得很薄。

提示词另外强制了两件事（对应"要业务相关，不要写代码改了什么字符"）：

- **只描述业务/行为变化**（产品上现在有什么不同），面向产品负责人或评审者
- **禁止出现**文件名、类名、方法名、字段名、表名、存储过程名、SQL、id、行号
- 提示词里带正反例：

  ```
  BAD : stateMutationService: replace direct table update with stored procedure us_apply_...
  GOOD: 库存回滚改为原子执行，避免并发下库存与订单状态不一致
  ```

> 为什么要在代码里裁剪、而不只靠提示词：模型不保证听话。`CommitMessageFormatter`
> 负责去掉 ``` 围栏、前言（如 "Here is a commit message:"）、首尾引号、结尾句号，
> 并在 `subject only` 模式下只保留第一行 —— 这一条有回归测试覆盖。

---

## 它怎么工作

按钮的位置不是靠猜的 —— 是从本机已安装的 Copilot 插件里反查出来的。Copilot 的
`META-INF/copilot-vcs-plugin.xml` 里写着：

```xml
<action id="copilot.git.commit.button"
        class="com.github.copilot.git.GitCommitGenerateAction">
  <add-to-group group-id="Vcs.MessageActionGroup" anchor="last"/>
</action>
```

所以**不需要任何专用扩展点**，一个普通 `AnAction` 挂到 `Vcs.MessageActionGroup` 就是那个位置。
读写消息框同样来自它的实现：

| 环节 | API |
|---|---|
| 拿到消息框 | `VcsDataKeys.COMMIT_MESSAGE_CONTROL` → `CommitMessageI.setCommitMessage(String)` |
| 拿到选中的改动 | `VcsDataKeys.COMMIT_WORKFLOW_UI` → `getIncludedChanges()` / `getIncludedUnversionedFiles()` |
| 兜底 | `VcsDataKeys.COMMIT_WORKFLOW_HANDLER` 转 `AbstractCommitWorkflowHandler` → `getUi()` |
| 生成 diff | `IdeaTextPatchBuilder.buildPatch(...)` + `UnifiedDiffWriter.write(...)` |
| 线程模型 | UI 模型只在 EDT 上读（`DiffCollector.capture`），diff 计算放后台（`Task.Backgroundable`） |
| 超限处理 | 按 diff 长度排序，优先丢弃最大的文件，保留尽可能多的小文件 |

按钮在生成中会变成**停止图标**，再点一次即取消 —— 和 Copilot 的行为一致。

---

## 项目结构

```
idea-commit-ai/
├── build.ps1                     # 离线构建（对着本机 IDEA 编译，已验证）
├── build.gradle.kts              # 标准 Gradle 构建（需联网，见下）
├── settings.gradle.kts
├── gradle.properties
└── src/main/
    ├── java/com/commitai/idea/
    │   ├── GenerateCommitMessageAction.java    # 工具栏按钮
    │   ├── CommitMessageGenerator.java         # 项目级服务，管后台任务与取消
    │   ├── DiffCollector.java                  # 取选中改动 → 统一 diff → 截断
    │   ├── LlmClient.java                      # OpenAI / Anthropic 双协议调用
    │   ├── Json.java                           # 零依赖 JSON 读写
    │   ├── PromptTemplates.java                # 提示词（业务语义、Conventional Commits、中英文）
    │   ├── CommitMessageFormatter.java         # 强制输出形状（裁剪/去围栏/限长）
    │   ├── CommitAiSettings.java               # 持久化设置 + 凭据库读取
    │   └── CommitAiSettingsConfigurable.java   # 设置页 UI
    └── resources/META-INF/plugin.xml
```

## 安全说明

**API key 存进 IDEA 凭据库**（`PasswordSafe` / Windows DPAPI），不进 `commit-ai.xml`。

对比一下：Copilot 自己的 BYOK 把 key **明文**写在
`%LOCALAPPDATA%\github-copilot\byok.json` 里，任何本地进程都能读。这个插件没沿用那种做法。

> ⚠️ 之前排查 Copilot 402 问题时读取过那个文件，里面的 DeepSeek key 已经出现在会话记录里，
> 建议去 platform.deepseek.com 轮换一次。

## 已验证 / 未验证

**已验证**
- 对着本机 IDEA 2025.3（build 253）用 `javac --release 21` 编译通过，产出可安装 jar（34 KB）
- **按钮已在真实 IDE 里跑通**：截图确认灯泡按钮出现在 Amend 右侧那一排
- 所有平台 API 签名都用 `javap` 对着 IDEA 安装目录逐个核对过（不是凭记忆写的）
- `Json` 解析器 + 两种响应提取 + 错误文案：7 项离线断言全 PASS（含转义、`\u` 解码、嵌套导航、402/404 文案）
- `CommitMessageFormatter` 输出形状与自适应预算：**33 项回归断言全 PASS**，其中包含用 IDE 里
  实测到的真实输出作为输入的两组场景（2 个文件 → 单行；55 个文件 → 摘要 + 要点）
- jar 结构正确：`META-INF/plugin.xml` + 类文件，可直接 Install from Disk
- `plugin.xml` XML 合法；其中引用的 4 个类都在 jar 里；通知组 id 两边一致
- 依赖集合按「同样能用的 Copilot 插件」对齐：只用 `com.intellij.modules.platform` +
  `com.intellij.modules.lang`。Copilot 用同一批 VCS/commit API 时也只声明这两个，
  所以**不**声明 `com.intellij.modules.vcs`（整个 IDEA 安装里找不到该 id 的声明，
  声明它反而有「未知依赖」加载失败的风险）。副作用：SVN/Hg 仓库也能用。

**未验证**
- 没有在真实 IDEA 里启动并点过按钮（需要重启 IDE，我无法在这个会话里做）
- Gradle 路径未跑通：本机没有 Gradle，也没有 wrapper 缓存，跑它要联网下载 Gradle + 插件依赖。
  `build.gradle.kts` 用的是 IntelliJ Platform Gradle Plugin **2.19.0**（已核对为当前最新），
  并配置成 `local("D:/app/idea/IntelliJ IDEA")` 复用本机 IDE 而不是下载 1GB SDK。
  需要 `runIde` 沙箱调试或发布插件时再走这条路。

## 已知限制

- 只为 **Commit 工具窗口**设计；老的「Commit Changes」对话框未测试
- 多仓库（multi-root）提交：按当前仓库的选中改动生成，不会跨仓库合并描述
- 二进制文件、删除且不可读的文件会被静默跳过
- 提示词是英文的，输出语言由设置里的 `auto / zh / en` 控制
- 不含流式输出（一次性返回，超时默认 90 秒）

---

## 背景：为什么会有这个插件

Copilot 的 commit 按钮失败不是网络问题，是额度用满：

```
[fetchChat] ... at <https://api.individual.githubcopilot.com/chat/completions>
            finished with 402 status
git/commitGenerate error -32603  →  Failed to generate commit message
```

`402 Payment Required`，且它**无视你在模型选择器里选什么** —— 即使选了 BYOK 的
`deepseek-flash`，请求依然发往 Copilot 后端。这个插件把那条链路整个换成你自己的端点。

参考实现（反编译产物）放在 `D:\myconfig\_recon\`，仅作对照，可随时删除。
