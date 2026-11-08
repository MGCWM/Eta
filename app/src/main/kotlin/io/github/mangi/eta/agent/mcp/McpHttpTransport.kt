package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject

/**
 * Streamable HTTP 传输，实现 [McpTransport]。
 *
 * 相对旧 McpHttpClient 的改动：
 * 1. 同时支持现代协议（`_meta` 携带协议版本与能力）与旧版 initialize 协商；
 * 2. 自定义请求头来自服务器配置，配合 `$$ENV` 展开可直接读环境变量；
 * 3. 失败时保留 HTTP 状态码、JSON-RPC 错误码与上游原文，不再折叠成一句话；
 * 4. 去掉「同一客户端同时只能有一个请求」的全局锁，改成在途请求集合，close 时统一取消。
 */
internal class McpHttpTransport(
    private val server: McpServerSetting,
    private val bearerToken: String?,
    private val extraHeaders: Map<String, String>,
) : McpTransport {

    override val label: String get() = "http ${server.url}"

    private val endpoint = validateMcpEndpoint(server.url)
    private val requestIds = AtomicLong(1)
    private val inFlight: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())
    private val toolHeadersByName = mutableMapOf<String, McpToolHeaders>()
    private val lifecycleLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var negotiatedVersion: String? = null

    @Volatile
    private var legacySessionId: String? = null

    @Volatile
    private var capturedHandshake: McpHandshake? = null

    override fun handshake(): McpHandshake {
        capturedHandshake?.let { return it }
        val startedAt = System.currentTimeMillis()
        val version = negotiatedVersion ?: when (server.protocolMode) {
            McpProtocolMode.LEGACY -> initializeLegacy()
            McpProtocolMode.LATEST -> McpProtocolMode.LATEST.also { negotiatedVersion = it }
            else -> probeLatestVersion()
        }
        val result = (handshakeCache ?: McpHandshake(version)).copy(
            protocolVersion = version,
            roundTripMs = System.currentTimeMillis() - startedAt,
        )
        capturedHandshake = result
        return result
    }

    /** 当前协商到的协议版本，未协商时为 null。诊断与兼容外壳用它回报版本。 */
    fun negotiatedProtocol(): String? = negotiatedVersion

    /** AUTO 模式探活：拿第一页工具当探针，形状不对就降到 initialize 协商。 */
    private fun probeLatestVersion(): String = try {
        listToolsOnce(McpProtocolMode.LATEST, null)
        McpProtocolMode.LATEST.also { negotiatedVersion = it }
    } catch (failure: McpProtocolCompatibilityException) {
        initializeLegacy()
    } catch (failure: McpTransportException) {
        if (!isCompatibilityFailure(failure)) throw failure
        initializeLegacy()
    }

    @Volatile
    private var handshakeCache: McpHandshake? = null

    /** 工具名 → inputSchema 原文，用于把 x-mcp-header 参数映射成请求头。 */
    private val schemaByName = mutableMapOf<String, String>()

    override fun listTools(cursor: String?): McpToolPage {
        negotiatedVersion?.let { return listToolsOnce(it, cursor) }
        return when (server.protocolMode) {
            McpProtocolMode.LEGACY -> listToolsOnce(initializeLegacy(), cursor)
            McpProtocolMode.LATEST -> listToolsOnce(
                McpProtocolMode.LATEST.also { negotiatedVersion = it },
                cursor,
            )
            else -> try {
                listToolsOnce(McpProtocolMode.LATEST.also { negotiatedVersion = it }, cursor)
            } catch (failure: McpProtocolCompatibilityException) {
                negotiatedVersion = null
                listToolsOnce(initializeLegacy(), cursor)
            } catch (failure: McpTransportException) {
                if (!isCompatibilityFailure(failure)) throw failure
                negotiatedVersion = null
                listToolsOnce(initializeLegacy(), cursor)
            }
        }
    }

    private fun listToolsOnce(version: String, cursor: String?): McpToolPage {
        val params = JSONObject().apply {
            cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) }
        }
        val result = unwrapResult(
            request(
                method = "tools/list",
                params = params,
                protocolVersion = version,
                sessionId = if (version == McpProtocolMode.LATEST) null else legacySessionId,
                stage = McpStage.LIST_TOOLS,
            ).json,
            McpStage.LIST_TOOLS,
        )
        if (version == McpProtocolMode.LATEST) {
            // 旧服务会忽略现代协议元数据、照样用 200 返回，形状不对就得改走 initialize 协商。
            if (!result.has("resultType") || result.isNull("resultType")) {
                throw McpProtocolCompatibilityException("MCP tools/list 返回了旧协议结果")
            }
            require(result.optString("resultType") == "complete") {
                "MCP tools/list 返回了不支持的结果类型"
            }
        }
        val page = McpToolParser.parsePage(result, MAX_TOOLS_PER_PAGE)
        page.tools.forEach { schemaByName[it.name] = it.inputSchemaJson }
        return page
    }

    override fun callTool(name: String, arguments: JSONObject): JSONObject =
        callTool(name, arguments, schemaByName[name])

    /**
     * 带 schema 的调用入口。
     *
     * 直接调用工具（没先 listTools）时 schemaByName 是空的，此时必须由调用方把定义里的
     * inputSchema 传进来，否则 x-mcp-header 参数不会映射成请求头。
     */
    fun callTool(name: String, arguments: JSONObject, inputSchemaJson: String?): JSONObject {
        val version = negotiatedVersion ?: negotiateVersion()
        val parameterHeaders = if (version == McpProtocolMode.LATEST) {
            val schema = inputSchemaJson ?: schemaByName[name]
            if (schema == null) {
                emptyMap()
            } else {
                toolHeadersByName.getOrPut(name) {
                    McpToolHeaders.fromSchema(JSONObject(schema))
                }.extract(arguments)
            }
        } else {
            emptyMap()
        }
        val invoke: (String?) -> JSONObject = { sessionId ->
            unwrapResult(
                request(
                    method = "tools/call",
                    name = name,
                    params = JSONObject().put("name", name).put("arguments", arguments),
                    protocolVersion = version,
                    sessionId = sessionId,
                    stage = McpStage.CALL_TOOL,
                    additionalHeaders = parameterHeaders,
                ).json,
                McpStage.CALL_TOOL,
            )
        }
        return if (version in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
            withLegacySessionRetry(invoke)
        } else {
            invoke(null)
        }
    }

    override fun ping(): Long {
        val startedAt = System.currentTimeMillis()
        handshake()
        return System.currentTimeMillis() - startedAt
    }

    override fun close() {
        val snapshot = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            inFlight.toList().also { inFlight.clear() }
        }
        snapshot.forEach { runCatching { it.cancel() } }
        val sessionId = legacySessionId
        val version = negotiatedVersion
        legacySessionId = null
        if (sessionId != null && version != null && version in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
            runCatching { releaseLegacySession(sessionId, version) }
        }
    }

    private fun negotiateVersion(): String = synchronized(lifecycleLock) {
        negotiatedVersion?.let { return it }
        check(!closed) { "MCP 客户端已关闭" }
        val mode = server.protocolMode
        val resolved = when (mode) {
            McpProtocolMode.LEGACY -> initializeLegacy()
            McpProtocolMode.LATEST -> McpProtocolMode.LATEST.also { negotiatedVersion = it }
            else -> probeLatestVersion()
        }
        resolved
    }

    /**
     * 只有「端点不认现代协议」这一类失败才降级到 initialize 协商。
     * 鉴权失败、网络错误与 5xx 必须原样上抛，否则 401 会被误判成协议不兼容而反复重试。
     */
    private fun isCompatibilityFailure(failure: McpTransportException): Boolean = when {
        failure.httpStatus in COMPATIBILITY_HTTP_STATUS ->
            failure.jsonRpcCode !in MODERN_JSON_RPC_ERRORS
        failure.jsonRpcCode == -32600 -> true
        else -> false
    }

    private fun initializeLegacy(): String {
        val initialized = request(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", McpProtocolMode.LEGACY)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                ),
            protocolVersion = McpProtocolMode.LEGACY,
            sessionId = null,
            stage = McpStage.HANDSHAKE,
        )
        val result = unwrapResult(initialized.json, McpStage.HANDSHAKE)
        val protocolVersion = result.optString("protocolVersion")
        if (protocolVersion !in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
            throw McpTransportException(
                stage = McpStage.HANDSHAKE,
                code = "MCP_PROTOCOL_VERSION_UNSUPPORTED",
                message = "MCP 服务器返回了不支持的协议版本：$protocolVersion",
            )
        }
        legacySessionId = initialized.sessionId
        notification(
            method = "notifications/initialized",
            protocolVersion = protocolVersion,
            sessionId = legacySessionId,
        )
        negotiatedVersion = protocolVersion
        val serverInfo = result.optJSONObject("serverInfo") ?: JSONObject()
        handshakeCache = McpHandshake(
            protocolVersion = protocolVersion,
            serverName = serverInfo.optString("name"),
            serverVersion = serverInfo.optString("version"),
            instructions = result.optString("instructions"),
            capabilities = result.optJSONObject("capabilities") ?: JSONObject(),
        )
        return protocolVersion
    }

    private fun <T> withLegacySessionRetry(block: (String?) -> T): T {
        val sessionId = legacySessionId
        return try {
            block(sessionId)
        } catch (failure: McpTransportException) {
            if (sessionId == null || failure.httpStatus != 404) throw failure
            legacySessionId = null
            negotiatedVersion = null
            handshakeCache = null
            negotiateVersion()
            block(legacySessionId)
        }
    }

    private fun request(
        method: String,
        params: JSONObject,
        protocolVersion: String,
        sessionId: String?,
        stage: String,
        name: String? = null,
        additionalHeaders: Map<String, String> = emptyMap(),
    ): WireResponse {
        val id = requestIds.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", decorateParams(params, protocolVersion))
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_METHOD, method)
            .apply {
                name?.let {
                    header(HEADER_NAME, if (protocolVersion == McpProtocolMode.LATEST) encodeMcpHeaderValue(it) else it)
                }
                sessionId?.let { header(HEADER_SESSION_ID, it) }
                extraHeaders.forEach { (headerName, headerValue) -> header(headerName, headerValue) }
                additionalHeaders.forEach { (headerName, headerValue) -> header(headerName, headerValue) }
            }
            .applyAuthorization()
            .build()
        return execute(httpRequest, expectsBody = true, expectedId = id, stage = stage)
    }

    private fun decorateParams(params: JSONObject, protocolVersion: String): JSONObject {
        if (protocolVersion != McpProtocolMode.LATEST) return params
        return JSONObject(params.toString()).put(
            "_meta",
            (params.optJSONObject("_meta") ?: JSONObject())
                .put("io.modelcontextprotocol/protocolVersion", protocolVersion)
                .put(
                    "io.modelcontextprotocol/clientInfo",
                    JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                )
                .put("io.modelcontextprotocol/clientCapabilities", JSONObject()),
        )
    }

    private fun notification(method: String, protocolVersion: String, sessionId: String?) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", JSONObject())
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_METHOD, method)
            .apply {
                sessionId?.let { header(HEADER_SESSION_ID, it) }
                extraHeaders.forEach { (headerName, headerValue) -> header(headerName, headerValue) }
            }
            .applyAuthorization()
            .build()
        execute(httpRequest, expectsBody = false, expectedId = null, stage = McpStage.HANDSHAKE)
    }

    private fun execute(
        request: Request,
        expectsBody: Boolean,
        expectedId: Long?,
        stage: String,
    ): WireResponse {
        synchronized(lifecycleLock) {
            if (closed) throw McpTransportException(stage, "MCP_CLIENT_CLOSED", "MCP 客户端已关闭")
        }
        val call = AgentHttpClient.client.newCall(request)
        inFlight.add(call)
        try {
            call.execute().use { response ->
                val sessionId = response.header(HEADER_SESSION_ID)
                if (response.isSuccessful && !expectsBody) return WireResponse(null, sessionId)
                val payload = if (response.isSuccessful) {
                    response.readWireJson(expectedId)
                } else {
                    runCatching { response.readWireJson(expectedId) }.getOrNull()
                }
                if (!response.isSuccessful) {
                    val error = payload?.optJSONObject("error")
                    throw McpTransportException(
                        stage = stage,
                        code = "MCP_HTTP_${response.code}",
                        message = "MCP 请求失败：HTTP ${response.code} ${response.message.take(MAX_TEXT_CHARS)}",
                        httpStatus = response.code,
                        jsonRpcCode = error?.optInt("code"),
                        upstreamMessage = error?.optString("message").orEmpty().take(MAX_TEXT_CHARS),
                    )
                }
                if (payload == null) {
                    throw McpTransportException(
                        stage = stage,
                        code = "MCP_EMPTY_RESPONSE",
                        message = "MCP 响应正文为空",
                        httpStatus = response.code,
                    )
                }
                if (expectedId != null && payload.opt("id")?.toString() != expectedId.toString()) {
                    throw McpTransportException(
                        stage = stage,
                        code = "MCP_ID_MISMATCH",
                        message = "MCP 响应 id 与请求不符",
                        httpStatus = response.code,
                    )
                }
                payload.optJSONObject("error")?.let { error ->
                    throw McpTransportException(
                        stage = stage,
                        code = "MCP_JSONRPC_ERROR",
                        message = "MCP 协议错误 code=${error.optInt("code")}",
                        httpStatus = response.code,
                        jsonRpcCode = error.optInt("code"),
                        upstreamMessage = error.optString("message").take(MAX_TEXT_CHARS),
                    )
                }
                return WireResponse(payload, sessionId)
            }
        } catch (failure: IOException) {
            if (failure is McpTransportException) throw failure
            throw McpTransportException(
                stage = stage,
                code = "MCP_NETWORK_ERROR",
                message = "MCP 网络请求失败：${failure.message.orEmpty()}",
                cause = failure,
            )
        } finally {
            inFlight.remove(call)
        }
    }

    private fun releaseLegacySession(sessionId: String, protocolVersion: String) {
        val request = Request.Builder()
            .url(endpoint)
            .delete()
            .header(HEADER_PROTOCOL_VERSION, protocolVersion)
            .header(HEADER_SESSION_ID, sessionId)
            .applyAuthorization()
            .build()
        runCatching {
            AgentHttpClient.client.newCall(request).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                    }
                }
            )
        }
    }

    private fun Request.Builder.applyAuthorization(): Request.Builder = apply {
        bearerToken?.trim()?.takeIf { it.isNotBlank() }?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    private fun unwrapResult(response: JSONObject?, stage: String): JSONObject =
        response?.optJSONObject("result") ?: throw McpTransportException(
            stage = stage,
            code = "MCP_MISSING_RESULT",
            message = "MCP 响应缺少 result",
        )

    private fun Response.readWireJson(expectedId: Long?): JSONObject? =
        if (header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
            readSseJson(expectedId)
        } else {
            readBoundedBody().takeIf(String::isNotBlank)?.let(::JSONObject)
        }

    private fun Response.readBoundedBody(): String {
        val bytes = body.source().use { source ->
            val buffer = Buffer()
            while (buffer.size <= MAX_RESPONSE_BYTES) {
                val read = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - buffer.size))
                if (read == -1L) break
            }
            buffer.readByteArray()
        }
        require(bytes.size <= MAX_RESPONSE_BYTES) { "MCP 响应超过大小限制" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun Response.readSseJson(expectedId: Long?): JSONObject? {
        val source = body.source()
        val dataLines = mutableListOf<String>()
        var totalBytes = 0L
        var fallback: JSONObject? = null

        fun consumeEvent(): JSONObject? {
            if (dataLines.isEmpty()) return null
            val json = runCatching { JSONObject(dataLines.joinToString("\n")) }.getOrNull()
            dataLines.clear()
            if (json == null || (!json.has("result") && !json.has("error"))) return null
            if (expectedId == null || json.opt("id")?.toString() == expectedId.toString()) return json
            // 部分实现固定回一个 id，不能据此把整条响应丢掉；先留作兜底。
            fallback = json
            return null
        }

        while (true) {
            val line = source.readBoundedSseLine(MAX_RESPONSE_BYTES - totalBytes) ?: break
            totalBytes += line.toByteArray().size + 1L
            require(totalBytes <= MAX_RESPONSE_BYTES) { "MCP 响应超过大小限制" }
            when {
                line.isEmpty() -> consumeEvent()?.let { return it }
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        return consumeEvent() ?: fallback
    }

    private fun okio.BufferedSource.readBoundedSseLine(remainingBytes: Long): String? {
        require(remainingBytes > 0L) { "MCP 响应超过大小限制" }
        val newline = indexOf('\n'.code.toByte(), 0L, remainingBytes + 1L)
        if (newline >= 0L) {
            val line = readUtf8(newline)
            skip(1L)
            return line.removeSuffix("\r")
        }
        if (request(remainingBytes + 1L)) throw IOException("MCP 响应超过大小限制")
        if (exhausted()) return null
        return readUtf8().removeSuffix("\r").ifEmpty { null }
    }

    private data class WireResponse(val json: JSONObject?, val sessionId: String?)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CLIENT_VERSION = "1"
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val HEADER_METHOD = "Mcp-Method"
        const val HEADER_NAME = "Mcp-Name"
        const val HEADER_SESSION_ID = "Mcp-Session-Id"
        const val MAX_RESPONSE_BYTES = 1_048_576L
        const val MAX_TEXT_CHARS = 300
        const val MAX_TOOLS_PER_PAGE = 128
        val SUPPORTED_LEGACY_PROTOCOL_VERSIONS = setOf(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18",
            McpProtocolMode.LEGACY,
        )
        val COMPATIBILITY_HTTP_STATUS = setOf(400, 404, 405)
        val MODERN_JSON_RPC_ERRORS = setOf(-32601, -32020, -32021, -32022)
    }
}

internal fun validateMcpEndpoint(raw: String): okhttp3.HttpUrl {
    return raw.trim().toHttpUrlOrNull() ?: error("MCP 地址无效")
}
