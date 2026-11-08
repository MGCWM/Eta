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
            if (!isHeaderName(name)) return@forEach
            put(name, expand(trimmed.substring(index + 1).trim()))
        }
    }

    /**
     * 展开 `${VAR}` 与 `$VAR`。
     *
     * 手写扫描而不是正则：Android 走的是 ICU 正则，语法比 JVM 严格得多，
     * 一个未转义的 `}` 就能让类初始化直接抛 ExceptionInInitializerError。
     */
    fun expand(raw: String, lookup: (String) -> String? = System::getenv): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val current = raw[index]
            if (current != '$' || index == raw.length - 1) {
                out.append(current)
                index += 1
                continue
            }
            val next = raw[index + 1]
            if (next == '{') {
                val close = raw.indexOf('}', index + 2)
                if (close > index + 2) {
                    val name = raw.substring(index + 2, close)
                    if (isVariableName(name)) {
                        out.append(lookup(name) ?: raw.substring(index, close + 1))
                        index = close + 1
                        continue
                    }
                }
                out.append(current)
                index += 1
                continue
            }
            if (next.isLetter() || next == '_') {
                var end = index + 1
                while (end < raw.length && (raw[end].isLetterOrDigit() || raw[end] == '_')) end += 1
                val name = raw.substring(index + 1, end)
                out.append(lookup(name) ?: raw.substring(index, end))
                index = end
                continue
            }
            out.append(current)
            index += 1
        }
        return out.toString()
    }

    /** 配置里出现的变量名，供诊断提示「这些变量没取到值」。 */
    fun referencedVariables(raw: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '$' || index == raw.length - 1) {
                index += 1
                continue
            }
            val next = raw[index + 1]
            if (next == '{') {
                val close = raw.indexOf('}', index + 2)
                if (close > index + 2) {
                    val name = raw.substring(index + 2, close)
                    if (isVariableName(name)) {
                        found += name
                        index = close + 1
                        continue
                    }
                }
                index += 1
                continue
            }
            if (next.isLetter() || next == '_') {
                var end = index + 1
                while (end < raw.length && (raw[end].isLetterOrDigit() || raw[end] == '_')) end += 1
                found += raw.substring(index + 1, end)
                index = end
                continue
            }
            index += 1
        }
        return found
    }

    private fun isVariableName(value: String): Boolean =
        value.isNotEmpty() &&
            (value.first().isLetter() || value.first() == '_') &&
            value.all { it.isLetterOrDigit() || it == '_' }

    /** RFC 7230 的 token 规则，用来挡住换行与空格注入。 */
    private fun isHeaderName(value: String): Boolean {
        if (value.isEmpty()) return false
        val separators = "()<>@,;:\\\"/[]?={} \t"
        return value.all { character -> character.code in 0x21..0x7e && character !in separators }
    }

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
