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
        val fetch = if (version == McpProtocolMode.LATEST) {
            request(
                method = "tools/list",
                params = params,
                protocolVersion = version,
                sessionId = null,
                stage = McpStage.LIST_TOOLS,
            )
        } else {
            // 有状态模式：会话过期（404）时重新 initialize 并重试一次。
            withLegacySessionRetry { sessionId ->
                request(
                    method = "tools/list",
                    params = params,
                    protocolVersion = version,
                    sessionId = sessionId,
                    stage = McpStage.LIST_TOOLS,
                )
            }
        }
        val result = unwrapResult(fetch.json, McpStage.LIST_TOOLS)
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
        // 只有用户显式选了 LATEST 才允许跳过协商直接发。
        // AUTO 与 LEGACY 都必须先 initialize：McpRunContext 每个运行周期都会新建
        // transport，此时 negotiatedVersion 还是 null，若直接假设 LATEST 就会拿
        // 2026-07-28 去撞只认 2025-06-18 的服务器，每次调用稳定 400。
        val version = negotiatedVersion ?: when (server.protocolMode) {
            McpProtocolMode.LATEST -> McpProtocolMode.LATEST.also { negotiatedVersion = it }
            else -> initializeLegacy()
        }
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
        return try {
            if (version in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
                withLegacySessionRetry(invoke)
            } else {
                invoke(null)
            }
        } catch (failure: McpTransportException) {
            // 自愈：缓存里的版本已经不能用了（服务器升级、换了端点、DB 里留着别人的
            // 版本号），丢一次缓存重新协商，用新版本重发一次再决定成败。
            if (!isVersionRejection(failure)) throw failure
            synchronized(lifecycleLock) {
                negotiatedVersion = null
                legacySessionId = null
                handshakeCache = null
            }
            val retryVersion = initializeLegacy()
            if (retryVersion == version) throw failure
            val parameterHeaders = if (retryVersion == McpProtocolMode.LATEST) {
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
            unwrapResult(
                request(
                    method = "tools/call",
                    name = name,
                    params = JSONObject().put("name", name).put("arguments", arguments),
                    protocolVersion = retryVersion,
                    sessionId = legacySessionId,
                    stage = McpStage.CALL_TOOL,
                    additionalHeaders = parameterHeaders,
                ).json,
                McpStage.CALL_TOOL,
            )
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

    private fun initializeLegacy(): String = synchronized(lifecycleLock) {
        negotiatedVersion?.let { return it }
        var lastFailure: McpTransportException? = null
        // 第一轮不带版本头，让服务端按自己的默认版本应答；后面再逐个候选猜。
        for (candidate in PROTOCOL_CANDIDATES) {
            try {
                return initializeWith(candidate)
            } catch (failure: McpTransportException) {
                lastFailure = failure
                if (!isVersionRejection(failure)) throw failure
            }
        }
        throw lastFailure ?: McpTransportException(
            stage = McpStage.HANDSHAKE,
            code = "MCP_HANDSHAKE_FAILED",
            message = "MCP 握手失败：没有可用的协议版本",
        )
    }

    /**
     * 单次 initialize。
     *
     * [candidate] 为 null 表示不声明版本，让服务端挑。MCP 规范里 initialize 的
     * params 才携带版本协商，请求头只是给 Streamable HTTP 的路由提示，多数实现
     * 允许缺省；先空手问一次能救回那些只认自己版本号的服务器。
     */
    private fun initializeWith(candidate: String?): String {
        val requested = candidate ?: McpProtocolMode.LEGACY
        val initialized = request(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", requested)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                ),
            protocolVersion = requested,
            sessionId = null,
            stage = McpStage.HANDSHAKE,
            omitVersionHeader = candidate == null,
        )
        val result = unwrapResult(initialized.json, McpStage.HANDSHAKE)
        // 以服务端回告为准。规范明确允许服务端改选它支持的版本，
        // 客户端应当接受回告值而不是拿本地白名单去否决。
        val reported = result.optString("protocolVersion").takeIf { it.isNotBlank() }
        val protocolVersion = reported ?: requested
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

    /**
     * 服务端明确表示「不认这个协议版本」，换下一档重试。
     *
     * initialize 阶段收到的 400 基本只有两种成因：协议版本不被接受，或者参数不合。
     * 前者换版本能救回来，后者换版本也只是多一次请求，代价很低；而漏判会让一台
     * 明明可用的服务器彻底用不了，所以这里 400 一律换档，401/403 这类鉴权失败
     * 与网络错误照旧原样上抛。
     */
    private fun isVersionRejection(failure: McpTransportException): Boolean {
        if (failure.code == "MCP_PROTOCOL_VERSION_UNSUPPORTED") return true
        val status = failure.httpStatus ?: return false
        if (status == 400) return true
        if (status !in COMPATIBILITY_HTTP_STATUS) return false
        val text = buildString {
            append(failure.message)
            append(' ')
            append(failure.upstreamMessage)
        }.lowercase()
        return "protocol-version" in text ||
            "protocol version" in text ||
            "protocol_version" in text ||
            "unsupported protocol" in text
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
            initializeLegacy()
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
        omitVersionHeader: Boolean = false,
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
            .apply {
                // 旧服务器（MT 管理器这类）看到不认识的 Mcp-Method 会直接 400，
                // 只有现代协议才需要它做路由。首次探活也允许完全省略版本头。
                // 空串头必判 400（MT 管理器实测：缺头放行、空值拒绝），所以只发非空值。
                if (!omitVersionHeader) {
                    protocolVersion.trim().takeIf { it.isNotEmpty() }
                        ?.let { header(HEADER_PROTOCOL_VERSION, it) }
                }
                if (protocolVersion == McpProtocolMode.LATEST) header(HEADER_METHOD, method)
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
                    // 非 JSON 的错误正文（MT 这类直接回 "Invalid MCP-Protocol-Version"）
                    // 也要留下来：既能让版本判定认出是协议问题，也方便诊断台看原话。
                    val rawBody = if (error == null) {
                        runCatching { response.peekBody(MAX_TEXT_CHARS.toLong()).string() }
                            .getOrNull()
                            .orEmpty()
                            .trim()
                    } else {
                        ""
                    }
                    val upstream = error?.optString("message").orEmpty().ifBlank { rawBody }
                    throw McpTransportException(
                        stage = stage,
                        code = "MCP_HTTP_${response.code}",
                        message = "MCP 请求失败：HTTP ${response.code} ${response.message.take(MAX_TEXT_CHARS)}"
                            .let { if (upstream.isBlank()) it else "$it — $upstream" },
                        httpStatus = response.code,
                        jsonRpcCode = error?.optInt("code"),
                        upstreamMessage = upstream.take(MAX_TEXT_CHARS),
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
        /**
         * initialize 的尝试顺序。null 表示不带版本头先问一次。
         * 覆盖 2024-11 至今全部正式修订，各家服务器认哪个就用哪个。
         */
        val PROTOCOL_CANDIDATES: List<String?> = listOf(
            null,
            McpProtocolMode.LATEST,
            McpProtocolMode.LEGACY,
            "2025-06-18",
            "2025-03-26",
            "2024-11-05",
        )

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
