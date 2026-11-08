package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * 本地子进程传输：按 MCP STDIO 规范用换行分隔的 JSON-RPC 走 stdin / stdout。
 *
 * 进程用 `sh -c 'echo $$ > pidfile; exec <argv>'` 启动：
 * - `exec` 让 sh 被目标进程替换，pidfile 里记的就是真正的服务器 pid；
 * - 不加 setsid，服务器 fork 出来的孙进程与原进程同属一个进程组，回收时用负 pid 一次带走；
 * - stderr 单独线程收进环形缓冲，进程异常退出或超时时随错误一起抛出，不再只剩一句「调用失败」。
 */
internal class McpStdioTransport(
    private val server: McpServerSetting,
    private val extraEnvironment: Map<String, String>,
    private val workingDirectory: File?,
    private val pidDirectory: File?,
) : McpTransport {

    override val label: String get() = "stdio ${server.command} ${server.args.joinToString(" ")}".trim()

    private val requestIds = AtomicLong(1)
    private val inbound = ArrayBlockingQueue<JSONObject>(QUEUE_CAPACITY)
    private val stderrRing = StringBuilder()
    private val sendLock = Any()
    private val lifecycleLock = Any()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var pidFile: File? = null
    @Volatile
    private var closed = false
    @Volatile
    private var capturedHandshake: McpHandshake? = null

    private val timeoutMs: Long get() = server.requestTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    private val startupTimeoutMs: Long
        get() = server.startupTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_STARTUP_TIMEOUT_MS)

    override fun handshake(): McpHandshake {
        capturedHandshake?.let { return it }
        synchronized(lifecycleLock) {
            capturedHandshake?.let { return it }
            ensureStarted()
            val startedAt = System.currentTimeMillis()
            val result = rpc(
                stage = McpStage.HANDSHAKE,
                method = "initialize",
                params = JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put(
                        "clientInfo",
                        JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION),
                    ),
                timeoutMs = startupTimeoutMs,
            )
            val serverInfo = result.optJSONObject("serverInfo") ?: JSONObject()
            val negotiated = McpHandshake(
                protocolVersion = result.optString("protocolVersion").ifBlank { PROTOCOL_VERSION },
                serverName = serverInfo.optString("name").take(MAX_TEXT_CHARS),
                serverVersion = serverInfo.optString("version").take(MAX_TEXT_CHARS),
                instructions = result.optString("instructions").take(MAX_INSTRUCTIONS_CHARS),
                capabilities = result.optJSONObject("capabilities") ?: JSONObject(),
                roundTripMs = System.currentTimeMillis() - startedAt,
            )
            // 握手成功后必须补发 initialized 通知，否则多数服务器会拒绝后续请求。
            notify(method = "notifications/initialized")
            capturedHandshake = negotiated
            return negotiated
        }
    }

    override fun listTools(cursor: String?): McpToolPage {
        handshake()
        val params = JSONObject().apply { cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) } }
        val result = rpc(McpStage.LIST_TOOLS, "tools/list", params, timeoutMs)
        return McpToolParser.parsePage(result, MAX_TOOLS_PER_PAGE)
    }

    override fun callTool(name: String, arguments: JSONObject): JSONObject {
        handshake()
        return rpc(
            stage = McpStage.CALL_TOOL,
            method = "tools/call",
            params = JSONObject().put("name", name).put("arguments", arguments),
            timeoutMs = timeoutMs,
        )
    }

    override fun ping(): Long {
        handshake()
        val startedAt = System.currentTimeMillis()
        rpc(McpStage.PING, "ping", JSONObject(), timeoutMs)
        return System.currentTimeMillis() - startedAt
    }

    override fun close() {
        val target: Process?
        val pid: Long?
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            target = process
            pid = readPid()
            process = null
        }
        runCatching { writer?.close() }
        writer = null
        if (target != null) {
            // 先按进程组发送 TERM，让服务器有机会写完日志；超时再强杀。
            pid?.let { killGroup(it, signal = "TERM") }
            runCatching { target.destroy() }
            if (!target.waitFor(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                pid?.let { killGroup(it, signal = "KILL") }
                runCatching { target.destroyForcibly() }
            }
        }
        pidFile?.let { runCatching { it.delete() } }
        inbound.clear()
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        launch()
    }

    private fun launch() {
        val command = server.command.trim()
        require(command.isNotBlank()) { "MCP STDIO 服务器未填写启动命令" }
        val argv = buildList {
            add(command)
            addAll(server.args.filter { it.isNotBlank() })
        }
        val pid = File(pidDirectory ?: File(System.getProperty("java.io.tmpdir")), "eta-mcp-${server.id}.pid")
        val pidPath = shellQuote(pid.absolutePath)
        // $$ 在 exec 之前展开，写入的是 sh 自己的 pid，exec 不改变 pid。
        val launcher = "printf '%s' \$\$ > $pidPath; exec ${argv.joinToString(" ") { shellQuote(it) }}"
        val builder = ProcessBuilder("sh", "-c", launcher)
        workingDirectory?.takeIf { it.isDirectory }?.let(builder::directory)
        builder.redirectErrorStream(false)
        val environment = builder.environment()
        environment.putAll(extraEnvironment)
        environment.putIfAbsent("ETA_MCP_SERVER", server.name)
        val started = try {
            builder.start()
        } catch (failure: IOException) {
            throw McpTransportException(
                stage = McpStage.LAUNCH,
                code = "MCP_STDIO_LAUNCH_FAILED",
                message = "MCP 进程启动失败：${failure.message ?: command}",
                cause = failure,
            )
        }
        process = started
        writer = started.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        pidFile = pid
        Thread({ drainStdout(started) }, "eta-mcp-out-${server.id}").apply { isDaemon = true }.start()
        Thread({ drainStderr(started) }, "eta-mcp-err-${server.id}").apply { isDaemon = true }.start()
    }

    private fun drainStdout(target: Process) {
        try {
            target.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parsed = runCatching { JSONObject(line) }.getOrNull()
                if (parsed == null) {
                    // 不少服务器会往 stdout 打启动横幅，留着当诊断线索。
                    appendStderr("非 JSON 输出：${line.take(MAX_LINE_CHARS)}")
                } else if (!inbound.offer(parsed)) {
                    appendStderr("入站队列已满，丢弃 id=${parsed.opt("id")}")
                }
            }
        } catch (failure: IOException) {
            appendStderr("stdout 结束：${failure.message.orEmpty()}")
        }
    }

    private fun drainStderr(target: Process) {
        try {
            target.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                if (line.isNotBlank()) appendStderr(line.take(MAX_LINE_CHARS))
            }
        } catch (failure: IOException) {
            appendStderr("stderr 结束：${failure.message.orEmpty()}")
        }
    }

    private fun rpc(
        stage: String,
        method: String,
        params: JSONObject,
        timeoutMs: Long,
    ): JSONObject {
        val id = requestIds.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        writeLine(body, stage)
        val response = awaitResponse(id, method, stage, timeoutMs)
        return response.optJSONObject("result") ?: JSONObject()
    }

    private fun notify(method: String) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", JSONObject())
        writeLine(body, McpStage.HANDSHAKE)
    }

    private fun writeLine(body: JSONObject, stage: String) {
        val target = writer ?: throw McpTransportException(
            stage = stage,
            code = "MCP_STDIO_NOT_RUNNING",
            message = "MCP 进程未运行",
        )
        synchronized(sendLock) {
            try {
                target.write(body.toString())
                target.write("\n")
                target.flush()
            } catch (failure: IOException) {
                throw McpTransportException(
                    stage = stage,
                    code = "MCP_STDIO_WRITE_FAILED",
                    message = "写入 MCP 进程失败：${failure.message.orEmpty()}",
                    stderrTail = stderrSnapshot(),
                    cause = failure,
                )
            }
        }
    }

    private fun awaitResponse(
        expectedId: Long,
        method: String,
        stage: String,
        timeoutMs: Long,
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                throw McpTransportException(
                    stage = stage,
                    code = "MCP_STDIO_TIMEOUT",
                    message = "MCP 进程 ${timeoutMs}ms 内未响应 $method",
                    stderrTail = stderrSnapshot(),
                )
            }
            val message = inbound.poll(remaining, TimeUnit.MILLISECONDS)
            if (message == null) {
                val alive = process?.isAlive == true
                if (!alive) {
                    throw McpTransportException(
                        stage = stage,
                        code = "MCP_STDIO_PROCESS_EXITED",
                        message = "MCP 进程已退出，未响应 $method",
                        stderrTail = stderrSnapshot(),
                    )
                }
                continue
            }
            if (!message.has("id") || message.isNull("id")) continue
            if (message.opt("id")?.toString() != expectedId.toString()) continue
            message.optJSONObject("error")?.let { error ->
                throw McpTransportException(
                    stage = stage,
                    code = "MCP_JSONRPC_ERROR",
                    message = "MCP 协议错误 code=${error.optInt("code")}",
                    jsonRpcCode = error.optInt("code"),
                    upstreamMessage = error.optString("message").take(MAX_TEXT_CHARS),
                    stderrTail = stderrSnapshot(),
                )
            }
            return message
        }
    }

    private fun appendStderr(line: String) {
        synchronized(stderrRing) {
            if (stderrRing.isNotEmpty()) stderrRing.append('\n')
            stderrRing.append(line)
            if (stderrRing.length > MAX_STDERR_CHARS) {
                stderrRing.delete(0, stderrRing.length - MAX_STDERR_CHARS)
            }
        }
    }

    private fun stderrSnapshot(): String = synchronized(stderrRing) {
        stderrRing.toString().trim().take(MAX_STDERR_CHARS)
    }

    private fun readPid(): Long? =
        pidFile?.let { file -> runCatching { file.readText().trim().toLong() }.getOrNull() }

    private fun killGroup(pid: Long, signal: String) {
        if (pid <= 0L) return
        runCatching {
            ProcessBuilder("kill", "-$signal", "-$pid")
                .redirectErrorStream(true)
                .start()
                .waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = McpProtocolMode.LEGACY
        const val CLIENT_NAME = "Eta"
        const val CLIENT_VERSION = "1"
        const val QUEUE_CAPACITY = 256
        const val MAX_TOOLS_PER_PAGE = 128
        const val MAX_TEXT_CHARS = 300
        const val MAX_LINE_CHARS = 500
        const val MAX_INSTRUCTIONS_CHARS = 4_000
        const val MAX_STDERR_CHARS = 8_192
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val MAX_STARTUP_TIMEOUT_MS = 900_000L
        const val CLOSE_GRACE_MS = 800L
        const val KILL_TIMEOUT_MS = 1_000L
    }
}
