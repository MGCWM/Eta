package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpTransportType
import java.io.File

/**
 * `K=V` / `K: V` 配置行解析与 `${VAR}` 展开。
 *
 * 与 Minis 的 `$$VAR` 占位符同一思路：配置里写变量名，真实值在运行时从进程环境取，
 * 这样 MCP 配置本身可以明文保存、随手分享，密钥留在环境变量里。
 */
internal object McpEnv {

    private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")
    private val HEADER_NAME = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")

    /** 解析 `K=V` 行；未定义的变量保留原文，诊断时能一眼看出漏配。 */
    fun parseAssignments(lines: List<String>): Map<String, String> = buildMap {
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val index = trimmed.indexOf('=')
            if (index <= 0) return@forEach
            val key = trimmed.substring(0, index).trim()
            if (key.isEmpty()) return@forEach
            put(key, expand(trimmed.substring(index + 1).trim()))
        }
    }

    /** 解析 `K: V` 行。头名不合法直接丢弃，避免换行或空格注入。 */
    fun parseHeaders(lines: List<String>): Map<String, String> = buildMap {
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val index = trimmed.indexOf(':')
            if (index <= 0) return@forEach
            val name = trimmed.substring(0, index).trim()
            if (!HEADER_NAME.matches(name)) return@forEach
            put(name, expand(trimmed.substring(index + 1).trim()))
        }
    }

    fun expand(raw: String, lookup: (String) -> String? = System::getenv): String =
        VARIABLE.replace(raw) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            lookup(name) ?: match.value
        }

    /** 配置里出现的变量名，供诊断提示「这些变量没取到值」。 */
    fun referencedVariables(raw: String): List<String> =
        VARIABLE.findAll(raw).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    fun missingVariables(raw: String, lookup: (String) -> String? = System::getenv): List<String> =
        referencedVariables(raw).filter { lookup(it) == null }.distinct()
}

/**
 * 子进程运行环境。
 *
 * STDIO 型 MCP 服务器本质是命令行程序，需要把 Eta 已装的 Node / Python 目录拼进 PATH，
 * 否则用户明明装过 npx，MCP 进程却报 command not found。
 */
internal class McpProcessContext(
    val workspace: File?,
    val pidDirectory: File?,
    private val baseEnvironment: Map<String, String> = emptyMap(),
) {
    fun environment(server: McpServerSetting): Map<String, String> {
        val declared = McpEnv.parseAssignments(server.environment)
        val path = buildList {
            declared["PATH"]?.takeIf { it.isNotBlank() }?.let { add(it) }
            baseEnvironment["PATH"]?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(defaultPath())
        }.joinToString(":")
        return baseEnvironment + declared + mapOf("PATH" to path)
    }

    private fun defaultPath(): String = buildList {
        workspace?.let {
            add("${it.absolutePath}/bin")
            add("${it.absolutePath}/.local/bin")
            add("${it.absolutePath}/usr/bin")
        }
        add("/data/local/bin")
        add("/system/bin")
        add("/system/xbin")
        add("/vendor/bin")
        add("/apex/com.android.runtime/bin")
        add("/sbin")
    }.joinToString(":")

    companion object {
        val EMPTY = McpProcessContext(workspace = null, pidDirectory = null)
    }
}

/** 按服务器配置挑传输实现。上层只认 [McpTransport]。 */
internal object McpTransportFactory {
    fun create(
        server: McpServerSetting,
        bearerToken: String?,
        processContext: McpProcessContext,
    ): McpTransport = when (server.transport) {
        McpTransportType.STDIO -> McpStdioTransport(
            server = server,
            extraEnvironment = processContext.environment(server),
            workingDirectory = processContext.workspace?.takeIf { it.isDirectory },
            pidDirectory = processContext.pidDirectory,
        )
        else -> McpHttpTransport(
            server = server,
            bearerToken = bearerToken,
            extraHeaders = McpEnv.parseHeaders(server.headers),
        )
    }
}
