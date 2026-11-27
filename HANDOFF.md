# Eta MCP 改造 —— 交接文档

> 交接时间：2026-11-21
> 当前版本：**v3.4.4-mcp**，commit `0aa7db5`，基于上游 `Mangi-11/Eta` v3.4.4

---

## 0. 一句话交接

把 Android 应用 **Eta**（Mangi-11/Eta，GPL-3.0）fork 出来，**重写了它的整个 MCP 客户端层**：加了 STDIO 传输、可读工具名、六档协议降级、错误透传、诊断台、JSON 批量导入、服务器原地编辑。代码已合入上游 3.4.4，出包链路走 GitHub Actions，产物发布在 `MGCWM/Eta-build` 的 Releases。

---

## 1. 仓库拓扑（三个远程，别搞混）

| 名字 | 地址 | 用途 | Actions |
|---|---|---|---|
| `upstream` | `Mangi-11/Eta` | 原作者，只读，拉更新用 | 正常 |
| `origin` | `MGCWM/Eta` | 我的 fork，**存档用** | **被 GitHub 禁用** |
| `build` | `MGCWM/Eta-build` | **实际出包仓**，非 fork | 正常 |

### ⚠️ 为什么有第三个仓库

fork 上的 Actions 被 GitHub 判定禁用：所有 dispatch 返回

```
HTTP 422: Actions has been disabled for this repository
```

但 `GET /actions/permissions` 返回 `enabled: true`，`PUT permissions` 和 `PUT /actions/workflows/{id}/enable` 都返回成功 —— **fork 层面的状态不一致，REST API 改不回来**。换非 fork 仓库立刻正常。

**所以：推代码必须双推 `origin` + `build`，只推 `origin` 不会触发构建。**

### 本地路径

```
/var/minis/mounts/minis-ubuntu/Eta         主工作区
/var/minis/mounts/minis-ubuntu/eta-sync.sh  上游同步脚本（已支持双推、冲突回滚点提示）
```

分支：
- `main` — 工作分支
- `backup-mcp-3.4.1` — 合并 3.4.4 之前的快照，**大版本合并出问题时 `git reset --hard backup-mcp-3.4.1` 是最快的回滚路**

---

## 2. 构建与发版

### 触发构建

```sh
cd /var/minis/mounts/minis-ubuntu/Eta
git push build main
gh workflow run "Eta Build" --repo MGCWM/Eta-build --ref main
```

构建约 **10.5 分钟**（Lint+单测 与 APK 并行）。

### 环境要求（CI 已配好，本地编需要）

```
JDK 25 toolchain    compileSdk 37   NDK 30   Kotlin 2.4.x
minSdk 34（3.4.4 起从 33 提到 34，Android 13 装不上）
```

### 取产物

```sh
# 列出
gh api repos/MGCWM/Eta-build/actions/runs/<RUN_ID>/artifacts \
   --jq '.artifacts[] | "\(.id) \(.name) \(.size_in_bytes)"'

# 注意：artifact zip 就是 APK 本身，下载后直接改名，不要 unzip
gh api repos/MGCWM/Eta-build/actions/artifacts/<ARTIFACT_ID>/zip > out.apk
```

### 发布

```sh
SHA=$(gh api repos/MGCWM/Eta-build/git/refs/heads/main --jq .object.sha)
gh release create "v3.4.4-mcp" --repo MGCWM/Eta-build \
   --title "..." --notes "..." --target "$SHA"
gh release upload "v3.4.4-mcp" out.apk --repo MGCWM/Eta-build --clobber
```

`--target` 必须传**完整 40 位 SHA**，短 SHA 会报 `tag_name is not a valid tag`。

### 签名

| 项 | 值 |
|---|---|
| keystore | `/var/minis/mounts/minis-ubuntu/eta-keystore/eta-release.jks` |
| base64 | 同目录 `keystore.b64` |
| 密码 | 同目录 `.storepass`（chmod 600） |
| alias | `eta` |
| 证书 | `CN=Eta Fork, O=MGCWM`，RSA 4096，30 年 |

CI 里 4 个 secret（名字别改，workflow 硬编码）：

```
ETA_RELEASE_KEYSTORE_BASE64
ETA_RELEASE_STORE_PASSWORD
ETA_RELEASE_KEY_ALIAS
ETA_RELEASE_KEY_PASSWORD
```

换仓库/重建仓时这 4 个要重新灌。

> 签名与作者官方包不同 → 覆盖安装会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必须先 `adb uninstall io.github.mangi.eta`。

---

## 3. 代码地图

改造集中在 `app/src/main/kotlin/io/github/mangi/eta/agent/mcp/`，共 12 文件 ~2440 行。

### 我新写的（8 个）

| 文件 | 行数 | 职责 |
|---|---|---|
| `McpTransport.kt` | 149 | 传输接口、`McpToolPage` / `McpHandshake` 数据类、`McpTransportException`、`McpStage` 枚举、`shellQuote` |
| `McpHttpTransport.kt` | 691 | HTTP 传输。**协议协商主逻辑在这里**，六档降级 + 会话头 + SSE 解析 + 自愈重试 |
| `McpStdioTransport.kt` | 335 | STDIO 传输。`ProcessBuilder` 起子进程，换行分隔 JSON-RPC，pid 文件 + `kill -TERM -<pgid>` 按进程组回收，stderr 环形缓冲 |
| `McpEnvironment.kt` | 196 | `${VAR}` 展开、headers/env 解析、`McpTransportFactory` |
| `McpServerManager.kt` | 175 | `discover` / `refresh` / `ping`，分页拉工具、截断原因上报、写回 DB |
| `McpRuntime.kt` | 33 | `McpProcessContext` 持有者（STDIO 用），`EtaApp.onCreate` 里 init |
| `McpToolNaming.kt` | 49 | `server__tool` 命名，重名追加 `_2`，64 字符上限 |
| `McpDiagnostics.kt` | 174 | 诊断三件套 ping / tools / call，返回结构化结果 |
| `McpConfigImport.kt` | 141 | 粘贴 `mcpServers` JSON 批量导入 |

### 我改的上游文件（4 个）

| 文件 | 改动 |
|---|---|
| `McpHttpClient.kt` | **被压成 91 行兼容壳**，只转发到 `McpTransportFactory`。原实现 565 行已移入 `McpHttpTransport` |
| `McpRunContext.kt` | 工具快照、去重、上限（4096 总计 / 2048 单服务器），工具名走 `McpToolNaming` |
| `EtaApp.kt` | +2 行，`McpRuntime.init(this)` |
| `data/db/McpEntities.kt` · `data/model/McpServer.kt` | 加 STDIO 字段 |

### UI 层（`ui/screens/mcp/`）

| 文件 | 说明 |
|---|---|
| `McpServerEditDialog.kt` | **新** 267 行，服务器原地编辑 |
| `McpImportDialog.kt` | **新** 142 行，JSON 粘贴导入 |
| `McpDiagnosticsDialog.kt` | **新** 166 行，诊断台 |
| `McpServersScreen.kt` | 改 115 行，把上面三个入口插进上游新结构的两套 UI 风格分支 |

---

## 4. 关键设计（为什么这么做）

### 4.1 工具命名

```
旧：mcp_3a1f2b8c_search_notes_a1b2c3d4     ← 模型看到十六进制
新：github__list_repositories              ← Claude / Minis 同款约定
```

重名追加 `_2`…`_99`，长度截到 64，字符集限 `[A-Za-z0-9_-]`（模型工具名硬限制）。

### 4.2 协议协商（**改动最多、最容易改坏的地方**）

`initialize` 尝试顺序：

```kotlin
listOf(
    null,               // ① 不带 MCP-Protocol-Version 头，让服务端按自己默认版本答
    "2026-07-28",       // ② 现代协议（LATEST）
    "2025-11-25",       // ③ LEGACY
    "2025-06-18",       // ④
    "2025-03-26",       // ⑤
    "2024-11-05",       // ⑥
)
```

**为什么第一档是 `null`**：规范允许服务端不带版本头启动；很多实现（MT 管理器就是一例）**缺头放行、空值 400**。先不带头问一次，最省事。

**协商结果以服务端回告为准**，客户端的白名单只用于记录，不用来否决服务端。

**`callTool` 必须先协商，不能硬套 LATEST** —— 这是踩得最惨的坑，见 §5.1。

### 4.3 版本来源优先级

```
negotiatedVersion（本次会话协商结果）
  ?: server.lastProtocolVersion（DB 缓存，discover 时写入）
  ?: 走完整协商链
```

DB 缓存值合法就直接用，`tools/call` 一次网络请求都不多发。

### 4.4 空值头防御

```kotlin
protocolVersion.trim().takeIf { it.isNotEmpty() }
    ?.let { header(HEADER_PROTOCOL_VERSION, it) }
```

**永远不发空值头**。原因见 §5.2。

### 4.5 上限

| 常量 | 值 | 位置 |
|---|---|---|
| `MAX_RUN_TOOLS` | 4096 | `McpRunContext` |
| `MAX_TOOLS_PER_SERVER` | 2048 | `McpRunContext` |
| `MAX_TOOLS_PER_PAGE` | **8192** | `McpHttpTransport` / `McpStdioTransport` |
| `MAX_DISCOVERED_TOOLS` | 2048 | `McpServerManager` / `McpHttpClient` |
| `MAX_LIST_PAGES` | 64 | 同上 |
| `MAX_CATALOG_CHARS` | 2,000,000 | 同上 |

⚠️ `MAX_TOOLS_PER_PAGE` 必须 **远大于** `MAX_DISCOVERED_TOOLS`。两者相等时，单页解析先把工具吃光，`TOOL_LIMIT` 截断信号永远观测不到，回归测试 `discoveryReportsExplicitTruncation...` 会挂。

### 4.6 游标语义

```kotlin
// 空串是合法游标，必须透传；只有「缺失」或「null」表示结束
nextCursor = if (src.has("nextCursor") && !src.isNull("nextCursor")) src.optString("nextCursor") else null
```

写成 `optString("nextCursor").takeIf { it.isNotBlank() }` 会让分页停在第 2 页。

### 4.7 STDIO 回收

```sh
sh -c 'echo $$ > /path/pid; exec <argv>'
```

启动后读 pid 文件，回收时 `kill -TERM -<pid>`，**按进程组一次带走**（`npx` 这类会 fork 出孙子进程，只杀父进程会留孤儿）。

stderr 单独线程读入 8 KB 环形缓冲，进程崩了直接甩进错误信息。

### 4.8 数据库迁移

当前 `version = 31`。我的 MCP 六列在 **30 → 31**，写法**幂等**：

```kotlin
addColumnIfMissing(db, "mcp_servers", "transport", "TEXT NOT NULL DEFAULT 'http'")
// 内部先 PRAGMA table_info 查列存在再 ALTER
```

**必须幂等**：用户从「我早期的 3.4.1-mcp」升级时，那六列已经建过（当时占用 24→25）。直接 ALTER 会 `duplicate column name` 崩库。

迁移历史：

```
24 → 25   我的 MCP STDIO 六列（3.4.1-mcp 时代）
25 → 26   上游会话性能统计列（3.4.4 合并时让号）
26 → 27..30  上游若干
30 → 31   我的 MCP 六列 最终落点（幂等）
```

⚠️ **`EtaDatabase` 上有 `fallbackToDestructiveMigration(dropAllTables = true)`** —— 迁移一旦抛异常，**整库被清空**。改迁移代码时必须跑 `EtaDatabaseMigrationTest`（它从 version 6 一路迁到最新并逐表校验 schema）。

---

## 5. 踩过的坑（都是真金白银）

### 5.1 `callTool` 在 AUTO 模式硬套 LATEST（最惨）

```kotlin
// 错误写法
val version = negotiatedVersion ?: when (server.protocolMode) {
    McpProtocolMode.LEGACY -> initializeLegacy()
    else -> McpProtocolMode.LATEST.also { negotiatedVersion = it }   // ← AUTO 落这里
}
```

`McpRunContext` **每个运行周期新建 transport**，`negotiatedVersion` 恒为 `null`，于是每次 `tools/call` 都直接带 `2026-07-28` 发出去，一次 `initialize` 都不发。

表现：`discover`（会协商）能拉到工具列表，但**一按工具就 `MCP_HTTP_400`**。诊断台 ping 正常，工具调用全废。这个症状极具迷惑性，会让人误以为是 DB 缓存脏。

### 5.2 空值头 vs 缺头

MT 管理器的实测矩阵（`POST /mcp` 的 `tools/call`）：

| `MCP-Protocol-Version` | 结果 |
|---|---|
| `2025-03-26` / `2025-06-18` / `2025-11-25` | 200 |
| `2025-06-29` / `2025-06-19` / `2024-11-05` / `2020-01-01` | 400 |
| **空值** | **400** |
| **完全不发** | **200** |

**缺头放行、空值拒绝**。所以 `?.let { header(...) }` 的空值短路不是风格问题，是生死问题。

### 5.3 Android 用 ICU 正则，不是 JVM 正则

```kotlin
Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")
//                                    ↑ 裸右花括号，桌面 JVM 宽容，Android 抛 PatternSyntaxException
```

`private val` 的正则在**类静态初始化**时编译 → `ExceptionInInitializerError` → 第一次访问该类就**整个进程崩**。表现是「点导入直接闪退」。

**教训：手机上不要写复杂正则，尤其带 `{}` 的。** 那处已改成手写字符扫描，`mcp` 包里现在零 `Regex`（`McpToolNaming.slug` 里还剩一个 `_+`，无花括号，安全）。

### 5.4 添加/导入不能等网络

原流程：`discover`（联网）→ 成功才 `add`（写库）。连不上就一个字都存不进去，而且 UI 卡 15 秒（OkHttp connect timeout）。

现在：**先写库 → UI 立刻返回 → 后台协程去 discover**，失败只往警告列表加一行。配置是否保存成功，与服务器是否在线彻底解耦。

### 5.5 自建服务器会往 stdout 打横幅

`ProcessBuilder` 起 Python MCP 服务器时，它可能先打一行 `listening on 8080`。STDIO 传输要**跳过所有非 JSON 行**，并把它们转进 stderr 缓冲，不能直接崩。

### 5.6 编译校验的两级策略

本地跑不动 Gradle（内存不够，见 §7），实际用的快速校验：

```sh
# ① 单文件语法（缺依赖没关系，看语法错）
/tmp/kotlinc/bin/kotlinc <file.kt> 2>&1 | rg 'error:'

# ② 核心层真编译（带 okhttp5 / org.json / coroutines 真依赖）
cd /var/minis/mounts/minis-ubuntu/Eta
E=app/src/main/kotlin/io/github/mangi/eta
CP=$(ls /tmp/mcpcheck/lib/*.jar | tr '\n' ':')
/tmp/kotlinc/bin/kotlinc -nowarn -cp "$CP" \
  $E/agent/mcp/*.kt $E/data/model/McpServer.kt $E/data/db/McpEntities.kt \
  /tmp/mcpcheck/stub/*.kt -d /tmp/mcpout
```

`/tmp/mcpcheck/stub/` 里是 android / base64 / model / repo 的手写桩。

**但这只能证明 MCP 层自洽，不代表整个 app 能编过。** 真实结论以 CI 为准。经验：每轮 CI 10 分钟，只修第一批报错，不要猜。

### 5.7 合并大版本时不要凭记忆重建文件

合 3.4.4 时 `McpServer.kt` 冲突，我「重建」了这个文件，结果漏了 `McpTimeouts`、`isStdio`、`startupTimeoutMs`、`validate()` 四个成员，编译直接过不了。

**正确姿势**：

```sh
git show upstream/main:<path> > /tmp/up.kt    # 取干净版
# 再用脚本往干净版里叠自己的字段
rg -n '用到的符号' app/src/main/              # 最后核对我原先依赖的符号是否都还在
```

### 5.8 上游会反复重写 UI

`McpServersScreen.kt` 从 723 行 → 1155 行 → 1255 行，3.4.1 和 3.4.4 各重写一次，并且引入 `when (LocalUiStyle.current)` 双风格分支（COUI / Miuix）。**任何 UI 改动都要在两套分支里各做一遍**，只改一处会编译过、运行偏。

---

## 6. 当前状态

### 已完成并验证

- [x] STDIO 传输（本地进程 MCP 服务器）
- [x] 六档协议降级 + 以服务端回告为准
- [x] 版本从 DB 播种、空值头不发、脏版本 400 自愈重试
- [x] 可读工具名 `server__tool`
- [x] 错误透传（HTTP 码 / JSON-RPC 码 / 上游原文 / stderr）
- [x] 诊断台（ping / 列工具 / 调工具）
- [x] JSON 批量导入
- [x] 服务器原地编辑（含 token 留空保持原值）
- [x] 数据库迁移幂等化
- [x] 合入上游 v3.4.4（87 提交），双推两仓
- [x] CI 全绿（Lint + 1920 单测）
- [x] 产物发布 `v3.4.4-mcp`

### 已知限制 / 待办

| 项 | 说明 | 难度 |
|---|---|---|
| **STDIO 够不到 proot 内的 node** | Eta 的 Node 装在 `/data/data/io.github.mangi.eta/files/proot/<发行版>/opt/eta/node/<版本>/bin/node`，`ProcessBuilder` 直起原生子进程 exec 不到，需要包一层 `proot -r <rootfs>` 桥接。目前 `npx` / `uvx` 型 MCP 服务器起不来，原生二进制和 shell 脚本可用 | 中 |
| **fork 的 Actions 永久禁用** | 已用 `Eta-build` 绕过，不用再修 | — |
| **签署证书与官方不同** | 覆盖官方包必须先卸载，`Eta-build` 的 secret 是我生成的。用户若要求与官方同签，需要作者签名（不可得） | — |
| **上游不接受外部 PR** | README 明写「已关闭外部 PR，只接受协作者提交」。想贡献只能 fork 自维护，或走 Issue 让作者内部实现 | — |
| 诊断台的 `call` 未做参数表单 | 目前手输 JSON，可加 schema 驱动的表单 | 低 |

---

## 7. 环境事实（换机器/换 AI 要知道）

| 项 | 值 |
|---|---|
| 本机内存 | 11 GB 总量，**可用仅 ~3 GB** → **跑不动 Gradle**，必须走 CI |
| WorkBuddy 云沙箱 | `32` vCPU / 8 GiB / 256 G，理论可编，需现装 JDK25 + `platforms;android-37.0`（canary）+ NDK 30，约 40 分钟起步。桥接未连接（`48777` 端口无响应） |
| kotlinc | `/tmp/kotlinc/bin/kotlinc`（2.1.0，已下） |
| 编译依赖 jar | `/tmp/mcpcheck/lib/`（okhttp 5.5.0 / okio / org.json / coroutines） |
| 编译桩 | `/tmp/mcpcheck/stub/`（android / base64 / model / repo） |
| MCP 测试服务器 | `/tmp/mcpcheck/test/`（stdio 版 `server.py`、HTTP 版 `httpserver.py`、MT 模拟 `mt_server.py`） |

> `/tmp` 会被清理，上面这些如果没了，重建很快（脚本都在历史记录里）。

---

## 8. 上手检查清单

```sh
# 1. 仓库状态
cd /var/minis/mounts/minis-ubuntu/Eta
git log --oneline -3
git status --short                 # 应为空
git log --oneline upstream/main..HEAD | wc -l   # 应为 26（我的提交数）
git log --oneline HEAD..upstream/main | wc -l   # 应为 0（不落后）

# 2. MCP 层完整性
ls app/src/main/kotlin/io/github/mangi/eta/agent/mcp/   # 应 12 个文件
rg -c 'Regex\(' app/src/main/kotlin/io/github/mangi/eta/agent/mcp/   # 应只剩 McpToolNaming 的 _+

# 3. 数据库
rg -n 'version = 3' app/src/main/kotlin/io/github/mangi/eta/data/db/EtaDatabase.kt

# 4. CI
gh run list --repo MGCWM/Eta-build --limit 3
```

---

## 9. 与用户沟通的既定事实

- 用户是 **MGCWM**（GitHub 同名），设备 Android，已装这个自签包。
- 主要场景：**用 MCP 工具做 APK 逆向 / 改包**，主力工具是 **MT 管理器**（`http://127.0.0.1:8787/mcp`）和 ProxyPin 上报服务（`192.168.1.191:9127`）。
- 用户偏好：**直接给下载链接，不要中间产物**；**要真跑过验证，不要纸上推演**。
- MT 管理器那台服务端只认 `{2025-03-26, 2025-06-18, 2025-11-25}`，空值头 400 —— 这是 §5.2 的来源。

---

## 10. 教训一句话总结

> **改协议层时，把「谁协商、什么时候协商、协商结果存哪」画清楚再动手。**
> 这个项目里 80% 的 bug 来自同一个错误：以为某个变量是「会话级」的，实际它是「每次调用重建」的。
