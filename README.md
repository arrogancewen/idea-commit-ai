# AI Commit Message

**Generate commit messages in IntelliJ IDEA with your own model.**

Adds a button next to *Amend* in the Commit tool window and turns the selected changes into a
commit message — but through **your own model endpoint** (DeepSeek / OpenAI / Anthropic / Ollama /
OpenRouter / any OpenAI-compatible gateway) instead of a subscription service.

> English | [简体中文](README.zh-CN.md)

---

## Contents

- [Features](#features)
- [Install](#install)
- [Configure](#configure)
- [Output examples](#output-examples)
- [How the length is decided](#how-the-length-is-decided)
- [How it works](#how-it-works)
- [Build from source](#build-from-source)
- [Project layout](#project-layout)
- [Security](#security)
- [Compatibility](#compatibility)
- [Known limitations](#known-limitations)
- [License](#license)

---

## Features

| | |
|---|---|
| **Any model endpoint** | OpenAI-compatible (`/chat/completions`) and Anthropic-compatible (`/v1/messages`) request shapes; the model name is free text |
| **Length adapts to the change** | 1–2 files → a single line; 55 files → a summary plus bullets grouped by concern. Not a fixed level |
| **Business semantics** | The prompt demands the *behavioural* change and forbids listing class names, method names, tables, stored procedures or SQL |
| **Enforced in code** | The model cannot override the shape: fences, preambles, quotes and trailing periods are stripped, length is capped |
| **Large commits keep every area** | Each file gets an equal share of the character budget and is truncated individually; the **complete file list** is always sent |
| **API key stored safely** | Goes to the IDE credential store (Windows DPAPI), never a plain-text file |
| **Cancellable** | The button becomes a stop icon while generating; click again to cancel |
| **No third-party dependencies** | Only `java.net.http` from the JDK and a hand-written JSON reader |

## Install

The plugin ships as a jar (`Install Plugin from Disk`).

1. Download the latest `idea-commit-ai-*.jar` from [Releases](../../releases)
   (if there is no release yet, see [Build from source](#build-from-source) — one command produces it)
2. IntelliJ IDEA → `Settings` → `Plugins` → gear icon → **Install Plugin from Disk...**
3. Pick the jar → restart the IDE

## Configure

`Settings` → `Tools` → **AI Commit Message**

| Field | Meaning |
|---|---|
| **API protocol** | `OpenAI-compatible` or `Anthropic-compatible` |
| **Base URL** | Endpoint root, see the table below |
| **Model** | Model name, free text |
| **API key** | Stored in the credential store, not in the settings file |
| **Detail level** | Defaults to `auto`, see [the next section](#how-the-length-is-decided) |
| **Max diff characters** | Budget for the diff sent to the model, default 60000 |
| **Request timeout** | Default 90 seconds |

**Test connection** sends a real minimal request and echoes the model's answer, so you can confirm
the key, URL and model name in one click.

### Common endpoints

| Service | API protocol | Base URL | Example model |
|---|---|---|---|
| DeepSeek | OpenAI-compatible | `https://api.deepseek.com/v1` | `deepseek-chat` |
| DeepSeek | Anthropic-compatible | `https://api.deepseek.com/anthropic` | `deepseek-chat` |
| OpenAI | OpenAI-compatible | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | Anthropic-compatible | `https://api.anthropic.com` | `claude-sonnet-4-5` |
| Ollama (local) | OpenAI-compatible | `http://localhost:11434/v1` | `qwen2.5-coder` |
| OpenRouter | OpenAI-compatible | `https://openrouter.ai/api/v1` | any model id |

## Output examples

Two files selected — a single line:

```
feat(erp): 聚水潭库存回调改为幂等分账入账并同步缓存
```

A large commit spanning several modules — a summary plus bullets grouped by concern:

```
fix(refund): 确保库存回滚完成后再落终态

- 退款成功时先恢复库存，再推进订单状态，避免状态与库存不一致
- 库存副作用统一在所有变更提交后执行
- 退款回滚仅对实物退款生效，虚拟预约与已完成订单不再误触发
- 库存变更改为原子执行，消除并发下的超卖
```

Note that the bullets contain **no** class names, method names, table names or stored-procedure
names — that is deliberate.

## How the length is decided

A fixed level cannot serve both cases: with one selected file a single line is exactly right, with
fifty files it says far too little. So the default is `auto`, deriving the bullet cap from the
number of selected files:

| Selected files | Output |
|---|---|
| 1–2 | a single summary line (≤72 chars) |
| 3–8 | one line + up to 2 bullets |
| 9–25 | one line + up to 4 bullets |
| 26–60 | one line + up to 6 bullets |
| >60 | one line + up to 8 bullets |

Bullets are required to be **grouped by concern or module**; the prompt states
`NEVER write one bullet per file`.

You can also pin it manually: `subject only` / `subject + brief body` (max 3 bullets) / `detailed`.

**Why the shape is enforced in code and not only in the prompt**: models do not reliably obey.
`CommitMessageFormatter` strips code fences, leading chatter (such as `Here is a commit message:`),
surrounding quotes and trailing periods, then applies the budget — and this is covered by tests.

## How it works

### Button placement

No guessing involved: `Vcs.MessageActionGroup` is the toolbar row to the right of *Amend*. A plain
`AnAction` registered there is all it takes — no dedicated extension point:

```xml
<actions>
  <action id="CommitAi.GenerateCommitMessage"
          class="com.commitai.idea.GenerateCommitMessageAction">
    <add-to-group group-id="Vcs.MessageActionGroup" anchor="last"/>
  </action>
</actions>
```

### Key APIs

| Step | API |
|---|---|
| Read/write the message | `VcsDataKeys.COMMIT_MESSAGE_CONTROL` → `CommitMessageI.setCommitMessage(String)` |
| Read the selected changes | `VcsDataKeys.COMMIT_WORKFLOW_UI` → `getIncludedChanges()` / `getIncludedUnversionedFiles()` |
| Fallback | `VcsDataKeys.COMMIT_WORKFLOW_HANDLER` cast to `AbstractCommitWorkflowHandler` → `getUi()` |
| Build the diff | `IdeaTextPatchBuilder.buildPatch(...)` + `UnifiedDiffWriter.write(...)` |
| Untracked files | `new Change(null, new CurrentContentRevision(filePath))` |

### Threading

`CommitWorkflowUi` is a UI model and may only be read on the EDT. The flow is:

1. `actionPerformed` (EDT) → `DiffCollector.capture(ui)` snapshots the selection
2. `Task.Backgroundable` (background) → build the diff, call the HTTP endpoint
3. `onSuccess` (back on the EDT) → `setCommitMessage(...)`

### Character budget

A large commit will not fit. The budget is shared **per file** rather than "drop the biggest":

- each file receives `clamp(maxDiffChars / fileCount, 800, 20000)` characters
- anything longer is truncated and marked `... (diff truncated)`
- the **complete file list** always goes into the prompt, so even a truncated file's area is known

## Build from source

### Option 1 — offline build (recommended, no network)

```bash
pwsh -File build.ps1
```

IntelliJ IDEA and the JDK are auto-detected; if detection fails, pass them explicitly:

```bash
pwsh -File build.ps1 -IdeaHome '/Applications/IntelliJ IDEA.app/Contents' -JdkHome "$JAVA_HOME"
```

Output: `build/dist/idea-commit-ai-<version>.jar`

It compiles directly against the installed IDE's `lib/*.jar` and `lib/modules/*.jar`, so there is no
~1 GB Platform SDK download and no Gradle involved.

### Option 2 — Gradle (needs network)

```bash
gradle buildPlugin
```

Uses IntelliJ Platform Gradle Plugin 2.19.0. `build.gradle.kts` defaults to `local("<IDEA path>")` to
reuse the local IDE; switch it to `create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.3")` to
build anywhere. Take this route when you need `runIde` for sandbox debugging.

### Run the tests

```bash
pwsh -File test.ps1
```

40 offline assertions covering the two parts most likely to break:

- the `Json` reader and both response shapes (escaping, `\u` decoding, nested lookups, 402/404 messages)
- `CommitMessageFormatter` output shape and the adaptive budget (including real scenarios: 2 files → one line, 55 files → summary + bullets)

## Project layout

```
idea-commit-ai/
├── build.ps1                     # offline build (auto-detects IDEA / JDK)
├── test.ps1                      # run the regression tests
├── build.gradle.kts              # IntelliJ Platform Gradle build
├── LICENSE
├── src/main/
│   ├── java/com/commitai/idea/
│   │   ├── GenerateCommitMessageAction.java    # toolbar button (update / actionPerformed)
│   │   ├── CommitMessageGenerator.java         # project service: task, cancel, notifications
│   │   ├── DiffCollector.java                  # changes → unified diff → character budget
│   │   ├── PromptTemplates.java                # prompt (business semantics / grouping / language)
│   │   ├── CommitMessageFormatter.java         # output shape and adaptive budget (enforced)
│   │   ├── LlmClient.java                      # OpenAI / Anthropic protocols
│   │   ├── Json.java                           # dependency-free JSON reader/writer
│   │   ├── CommitAiSettings.java               # persisted settings + credential store
│   │   └── CommitAiSettingsConfigurable.java   # settings UI
│   └── resources/META-INF/plugin.xml
└── test/java/                    # offline regression tests
    ├── FormatterSmokeTest.java
    └── JsonSmokeTest.java
```

## Security

- **The API key is never written in plain text**: it goes through `PasswordSafe` into the IDE
  credential store (DPAPI on Windows). `commit-ai.xml` only holds non-sensitive settings such as the
  URL and model name.
- **Nothing else leaves your machine**: only the diffs of the changes you selected. No telemetry, no
  intermediary server.
- **Test connection** sends one very small real request purely as a connectivity check.

## Compatibility

- Target platform: IntelliJ IDEA 2024.1+ (`since-build="241"`), verified on **IDEA 2025.3 (build 253)**
- Depends only on `com.intellij.modules.platform` and `com.intellij.modules.lang`, so **Git, SVN and
  Mercurial** repositories all work
- Every platform API signature was checked with `javap` against the actual IDE installation rather
  than written from memory

## Known limitations

- Only the new **Commit tool window** is wired up; the legacy "Commit Changes" modal dialog is untested
- Multi-root commits describe the currently selected repository's changes; they are not merged into
  one message across repositories
- Binary files, and files that were deleted but are unreadable, are skipped silently
- The prompt itself is English; the output language follows the `auto` / `zh` / `en` setting
- No streaming: the response arrives in one piece, default timeout 90 seconds

## License

[MIT](LICENSE)
