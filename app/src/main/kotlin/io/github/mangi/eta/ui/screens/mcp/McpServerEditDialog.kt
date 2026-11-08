package io.github.mangi.eta.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 服务器配置编辑对话框。
 *
 * 旧实现建完之后只能删了重建，改一个 IP 或端口要重填整份配置。
 * 这里复用新建表单的字段集合，只针对已有条目做原地修改：
 * 地址、命令、参数、环境变量、请求头、Token 都能改，工具开关与启用状态不动。
 */
@Composable
internal fun McpServerEditDialog(
    server: McpServerSetting,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stdio = server.isStdio

    var name by remember(server.id) { mutableStateOf(server.name) }
    var url by remember(server.id) { mutableStateOf(server.url) }
    var command by remember(server.id) { mutableStateOf(server.command) }
    var argsText by remember(server.id) { mutableStateOf(server.args.joinToString(" ")) }
    var envText by remember(server.id) {
        mutableStateOf(server.environment.joinToString("\n"))
    }
    var headersText by remember(server.id) {
        mutableStateOf(server.headers.joinToString("\n"))
    }
    var token by remember(server.id) { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        val draftError = when {
            trimmedName.isBlank() -> "名称不能为空"
            stdio && command.isBlank() -> "启动命令不能为空"
            !stdio -> runCatching { validateMcpEndpoint(trimmedUrl) }.exceptionOrNull()?.message
            else -> null
        }
        if (draftError != null) {
            error = draftError
            return
        }
        val pendingToken = token
        val edited = server.copy(
            name = trimmedName,
            url = if (stdio) "" else trimmedUrl,
            command = if (stdio) command.trim() else "",
            args = if (stdio) splitMcpArguments(argsText) else emptyList(),
            environment = if (stdio) {
                envText.lines().map(String::trim).filter(String::isNotBlank)
            } else {
                emptyList()
            },
            headers = if (stdio) {
                emptyList()
            } else {
                headersText.lines().map(String::trim).filter(String::isNotBlank)
            },
            authorizationType = if (pendingToken.isBlank()) {
                if (server.authorizationType == McpAuthorizationType.BEARER) {
                    McpAuthorizationType.BEARER
                } else {
                    McpAuthorizationType.NONE
                }
            } else {
                McpAuthorizationType.BEARER
            },
            // 地址变了原来的工具列表就失效了，清空等着重新发现。
            tools = if (editedEndpointChanged(server, trimmedUrl, command)) emptyList() else server.tools,
        )
        scope.launch {
            busy = true
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    McpServerRepository.update(edited, pendingToken.ifBlank { null })
                }.exceptionOrNull()
            }
            busy = false
            if (failure != null) {
                error = failure.message ?: failure.javaClass.simpleName
                return@launch
            }
            onSaved()
            // 保存成功后再补工具列表，风格与新建流程一致：界面先返回，探测在后台。
            scope.launch(Dispatchers.IO) {
                runCatching {
                    McpServerRepository.update(
                        McpServerManager.discover(edited, pendingToken.ifBlank { null }),
                    )
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "编辑服务器", style = MiuixTheme.textStyles.title3)

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (stdio) {
                    TextField(
                        value = command,
                        onValueChange = { command = it },
                        label = "启动命令",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = "参数（空格分隔，可加引号）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = "环境变量（KEY=VALUE，每行一条）",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                    )
                } else {
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        label = "服务器地址",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = "请求头（Name: Value，每行一条）",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                    )
                    TextField(
                        value = token,
                        onValueChange = { token = it },
                        label = if (server.authorizationType == McpAuthorizationType.BEARER) {
                            "Bearer Token（留空保持不变）"
                        } else {
                            "Bearer Token（可选）"
                        },
                        singleLine = true,
                        visualTransformation = if (showToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        text = if (showToken) "隐藏 Token" else "显示 Token",
                        onClick = { showToken = !showToken },
                    )
                }

                if (error != null) {
                    Text(
                        text = error.orEmpty(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.danger,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(text = "取消", enabled = !busy, onClick = onDismiss)
                    TextButton(
                        text = if (busy) "保存中…" else "保存",
                        enabled = !busy,
                        onClick = { save() },
                    )
                }
            }
        }
    }
}

private fun editedEndpointChanged(
    server: McpServerSetting,
    newUrl: String,
    newCommand: String,
): Boolean = if (server.isStdio) {
    newCommand.trim() != server.command
} else {
    newUrl != server.url
}

/** 与新建表单同一套切分规则：引号内保留空格，引号本身不进入 argv。 */
internal fun splitMcpArguments(raw: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    raw.forEach { character ->
        when {
            quote != null && character == quote -> quote = null
            quote == null && (character == '"' || character == '\'') -> quote = character
            quote == null && character.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }
            else -> current.append(character)
        }
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}
