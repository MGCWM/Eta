package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpToolDefinition
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 传输层统一契约。
 *
 * HTTP 与 STDIO 共用同一套握手、发现、调用与探活入口，上层不再判断服务器是远程端点还是本地子进程。
 * 旧实现把这三件事写死在 McpHttpClient 里，导致本地 npx / uvx 型 MCP 服务器完全无法接入。
 */
internal interface McpTransport : AutoCloseable {
    /** 人类可读的传输描述，进入错误信息与诊断输出。 */
    val label: String

    /** 完成 initialize 握手。同一实例只握手一次，重复调用返回缓存结果。 */
    fun handshake(): McpHandshake

    /** 拉取一页工具。cursor 为 null 表示第一页。 */
    fun listTools(cursor: String? = null): McpToolPage

    /** 调用工具，返回未解包的 result 对象。 */
    fun callTool(name: String, arguments: JSONObject): JSONObject

    /** 往返探活，返回耗时毫秒。 */
    fun ping(): Long
}

internal data class McpHandshake(
    val protocolVersion: String,
    val serverName: String = "",
    val serverVersion: String = "",
    val instructions: String = "",
    val capabilities: JSONObject = JSONObject(),
    val roundTripMs: Long = 0L,
)

internal data class McpToolPage(
    val tools: List<McpToolDefinition>,
    val nextCursor: String? = null,
    val cacheTtlMs: Long? = null,
)

/** 传输阶段标签。诊断界面按阶段归类，模型据此判断该重试还是该改配置。 */
internal object McpStage {
    const val LAUNCH = "launch"
    const val HANDSHAKE = "initialize"
    const val LIST_TOOLS = "tools/list"
    const val CALL_TOOL = "tools/call"
    const val PING = "ping"
    const val CLOSE = "close"
}

/**
 * 传输层唯一异常类型。
 *
 * 与旧实现的差别：HTTP 状态码、JSON-RPC 错误码、上游原始 message、子进程 stderr 尾部全部保留，
 * 上层据此拼出可读诊断，不再统一折叠成一句「MCP 工具调用失败」。
 */
internal class McpTransportException(
    val stage: String,
    val code: String,
    override val message: String,
    val httpStatus: Int? = null,
    val jsonRpcCode: Int? = null,
    val upstreamMessage: String = "",
    val stderrTail: String = "",
    cause: Throwable? = null,
) : IOException(message, cause) {
    /** 供工具结果与诊断界面共用的结构化载荷。 */
    fun toPayload(serverName: String): JSONObject {
        val payload = JSONObject()
            .put("ok", false)
            .put("server", serverName)
            .put("stage", stage)
            .put("code", code)
            .put("message", message)
        if (httpStatus != null) payload.put("http_status", httpStatus)
        if (jsonRpcCode != null) payload.put("jsonrpc_code", jsonRpcCode)
        if (upstreamMessage.isNotBlank()) payload.put("upstream", upstreamMessage)
        if (stderrTail.isNotBlank()) payload.put("stderr", stderrTail)
        return payload
    }
}

/** 工具定义解析。HTTP 与 STDIO 返回的字段形状一致，抽出来共用。 */
internal object McpToolParser {
    private const val MAX_TITLE_CHARS = 160
    private const val MAX_DESCRIPTION_CHARS = 2_000

    fun parse(source: JSONObject): McpToolDefinition {
        val annotations = source.optJSONObject("annotations")
        return McpToolDefinition(
            name = source.optString("name").trim(),
            title = source.optString("title").ifBlank {
                annotations?.optString("title").orEmpty()
            }.take(MAX_TITLE_CHARS),
            description = source.optString("description").take(MAX_DESCRIPTION_CHARS),
            inputSchemaJson = (source.optJSONObject("inputSchema") ?: JSONObject()).toString(),
            readOnlyHint = annotations.nullableBoolean("readOnlyHint"),
            destructiveHint = annotations.nullableBoolean("destructiveHint"),
            idempotentHint = annotations.nullableBoolean("idempotentHint"),
            openWorldHint = annotations.nullableBoolean("openWorldHint"),
        )
    }

    fun parsePage(source: JSONObject, limit: Int): McpToolPage {
        val page = source.optJSONArray("tools") ?: JSONArray()
        val tools = mutableListOf<McpToolDefinition>()
        for (index in 0 until page.length()) {
            if (tools.size >= limit) break
            val item = page.optJSONObject(index) ?: continue
            val parsed = parse(item)
            if (parsed.name.isNotBlank()) tools += parsed
        }
        return McpToolPage(
            tools = tools,
            nextCursor = source.optString("nextCursor").takeIf { it.isNotBlank() },
            cacheTtlMs = source.nullableLong("ttlMs")?.coerceAtLeast(0L),
        )
    }

    private fun JSONObject?.nullableBoolean(key: String): Boolean? =
        if (this != null && has(key) && !isNull(key)) optBoolean(key) else null

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}

/** 单引号包壳，用于把 argv 拼成 sh -c 命令行。 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * 端点不认现代协议时抛出的信号。
 *
 * 与 [McpTransportException] 分开，是为了让 AUTO 模式的降级判断只认「协议形状不对」，
 * 不把鉴权失败、网络错误与 5xx 一起降级。
 */
internal class McpProtocolCompatibilityException(
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
