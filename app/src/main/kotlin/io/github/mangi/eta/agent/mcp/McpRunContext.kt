package io.github.mangi.eta.agent.mcp

import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import org.json.JSONArray
import org.json.JSONObject

/** 一次 run 内冻结的 MCP 工具条目：模型侧名字 ↔ 服务器原始工具。 */
internal class McpRunTool(
    val modelName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
)

/** 一次 run 使用冻结的 MCP 工具目录，设置变更从下一次 run 生效。 */
internal class McpRunSnapshot(
    val tools: List<McpRunTool>,
    /** 被上限截断的服务器，用于在工具描述与诊断里明确告知，而不是静默丢弃。 */
    val skippedServers: List<String> = emptyList(),
) {
    private val byModelName = tools.associateBy(McpRunTool::modelName)

    fun resolve(modelName: String): McpRunTool? = byModelName[modelName]

    fun appendModelTools(destination: JSONArray) {
        tools.forEach { tool ->
            val description = buildString {
                append("MCP 服务器「").append(tool.server.name).append("」提供的工具")
                tool.definition.description.trim().takeIf { it.isNotBlank() }?.let {
                    append("。 ").append(it)
                }
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", JSONObject(tool.definition.inputSchemaJson)),
                    ),
            )
        }
    }

    companion object {
        val EMPTY = McpRunSnapshot(emptyList())

        suspend fun load(): McpRunSnapshot {
            val projected = mutableListOf<McpRunTool>()
            val usedNames = mutableSetOf<String>()
            val skipped = mutableListOf<String>()
            val now = System.currentTimeMillis()
            McpServerRepository.enabledServers().forEach { configured ->
                val bearerToken = McpServerRepository.bearerToken(configured.id)
                // STDIO 每次 run 都重新握手代价过高；只有 HTTP 的现代协议带 TTL，才按过期时间刷新。
                // tools 为空说明上一次发现没成（端点当时不可达、协议版本对不上、
                // 或者数据库里留着别人的版本号），这种记录留着也调不动，必须重试一次。
                val needsRefresh = configured.lastProtocolVersion == null ||
                    configured.tools.isEmpty() ||
                    (!configured.isStdio &&
                        configured.lastProtocolVersion == io.github.mangi.eta.data.model.McpProtocolMode.LATEST &&
                        (configured.toolsExpireAt == null || configured.toolsExpireAt <= now))
                val server = if (needsRefresh) {
                    runCatching {
                        McpServerManager.discover(configured, bearerToken).also {
                            McpServerRepository.update(it)
                        }
                    }.getOrNull() ?: run {
                        skipped += configured.name
                        return@forEach
                    }
                } else {
                    configured
                }
                var added = 0
                for (tool in server.activeTools) {
                    if (projected.size >= MAX_RUN_TOOLS || added >= MAX_TOOLS_PER_SERVER) break
                    projected += McpRunTool(
                        modelName = McpToolNaming.modelName(server.name, tool.name, usedNames),
                        server = server,
                        definition = tool,
                        bearerToken = bearerToken,
                    )
                    added += 1
                }
                if (added < server.activeTools.size) skipped += server.name
            }
            return McpRunSnapshot(projected, skipped)
        }

        /**
         * 全局工具上限。旧实现是 64，并且到顶后对后续服务器静默 `return@forEach`，
         * 用户只会看到「某个服务器突然没工具」。这里放宽到 256（多数模型的 tool 上限），
         * 单服务器 128，超出部分记录在 [skippedServers] 里由诊断入口展示。
         */
        private const val MAX_RUN_TOOLS = 256
        private const val MAX_TOOLS_PER_SERVER = 128
    }
}

internal class McpToolExecutor(
    private val snapshot: McpRunSnapshot,
    private val processContext: McpProcessContext = McpRuntime.processContext(),
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val lifecycleLock = Any()
    private val clients = mutableMapOf<String, McpTransport>()
    private var closed = false

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name) ?: return failure(
            code = "UNKNOWN_MCP_TOOL",
            message = "MCP 工具未在本次运行中启用",
        )
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "MCP 工具参数不是 JSON object") }
        return try {
            val client = synchronized(lifecycleLock) {
                if (closed) return failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭")
                clients[tool.server.id] ?: McpTransportFactory.create(
                    server = tool.server,
                    bearerToken = tool.bearerToken,
                    processContext = processContext,
                ).also { clients[tool.server.id] = it }
            }
            adaptResult(tool, client.callTool(tool.definition.name, arguments))
        } catch (failure: McpTransportException) {
            // 完整保留阶段、HTTP 状态、JSON-RPC 码、上游原文与 stderr，模型据此能自己改参数或让用户改配置。
            AgentModelClient.ToolResult(
                content = failure.toDiagnostic(tool.server).toString(),
                sensitive = true,
            )
        } catch (failure: Throwable) {
            failure(
                code = "MCP_CALL_FAILED",
                message = "MCP 工具调用失败：${failure.message.orEmpty().take(MAX_ERROR_CHARS)}",
            )
        }
    }

    fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    private fun adaptResult(tool: McpRunTool, result: JSONObject): AgentModelClient.ToolResult {
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return failure(
                code = "MCP_INPUT_REQUIRED_UNSUPPORTED",
                message = "该 MCP 工具在执行中请求补充输入，本轮无法继续",
            )
        }
        if (resultType.isNotBlank() && resultType != "complete") {
            return failure(
                code = "MCP_RESULT_TYPE_UNSUPPORTED",
                message = "MCP 工具返回了不支持的结果类型：$resultType",
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = JSONArray()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        var textBytes = 0
        var imageBytes = 0
        var truncated = false
        var omittedItems = 0
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    val text = item.optString("text")
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (remaining > 0) {
                        val bounded = text.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < text.length) truncated = true
                    } else if (text.isNotEmpty()) {
                        truncated = true
                    }
                }
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) {
                        omittedItems += 1
                        continue
                    }
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT).size }.getOrNull()
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        omittedItems += 1
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val resourceText = resource?.optString("text").orEmpty()
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (resourceText.isNotBlank() && remaining > 0) {
                        val bounded = resourceText.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < resourceText.length) truncated = true
                    } else if (resource?.has("blob") == true || resourceText.isNotBlank()) {
                        omittedItems += 1
                    }
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        val rendered = if (label.isBlank()) uri else "$label: $uri"
                        val remaining = MAX_TEXT_BYTES - textBytes
                        if (remaining > 0) {
                            val bounded = rendered.takeUtf8Bytes(remaining)
                            textItems.put(bounded)
                            textBytes += bounded.toByteArray().size
                            if (bounded.length < rendered.length) truncated = true
                        } else {
                            truncated = true
                        }
                    } else {
                        omittedItems += 1
                    }
                }
                "audio" -> omittedItems += 1
                else -> omittedItems += 1
            }
        }
        val payload = JSONObject()
            .put("ok", !result.optBoolean("isError", false))
            .put("server", tool.server.name)
            .put("tool", tool.definition.name)
            .put("model_name", tool.modelName)
            .put("content", textItems)
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let { structured ->
            val encoded = structured.toString()
            if (encoded.toByteArray().size <= MAX_STRUCTURED_BYTES) {
                payload.put("structured_content", structured)
            } else {
                truncated = true
            }
        }
        if (truncated) payload.put("truncated", true)
        if (omittedItems > 0) payload.put("omitted_items", omittedItems)
        if (result.optBoolean("isError", false)) payload.put("code", "MCP_TOOL_ERROR")
        return AgentModelClient.ToolResult(
            content = payload.toString(),
            images = images,
            sensitive = true,
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_STRUCTURED_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_ERROR_CHARS = 300
    }
}

internal class RoutingToolExecutor(
    private val local: AgentModelClient.ToolExecutor,
    private val mcp: McpToolExecutor,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        if (mcp.contains(toolCall.name)) mcp.execute(toolCall) else local.execute(toolCall)

    override fun close() {
        runCatching { mcp.close() }
        (local as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
