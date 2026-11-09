package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.input.CouiHapticFeedback
import com.itos.coui.input.performCouiHapticFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpConfigImport
import io.github.mangi.eta.agent.mcp.McpEnv
import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.screens.tools.GroupBatchTogglePill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }
    var transportType by remember { mutableStateOf(McpTransportType.HTTP) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val totalTools = servers.sumOf { it.tools.size }
    val totalEnabledTools = servers.sumOf { server ->
        server.tools.count { it.name in server.enabledToolNames }
    }
    val allServersToolsEnabled = totalTools > 0 && totalEnabledTools == totalTools
    val onToggleAllServersTools: () -> Unit = {
        val target = !allServersToolsEnabled
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    McpServerRepository.setAllServersToolsEnabled(target)
                }
            }.onFailure { failure ->
                Toast.makeText(
                    context,
                    failure.message ?: resources.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val onConfirmAdd: () -> Unit = {
        val normalizedName = name.trim()
        val normalizedUrl = url.trim()
        val stdio = transportType == McpTransportType.STDIO
        error = when {
            normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
            stdio && command.isBlank() -> resources.getString(R.string.mcp_stdio_command_required)
            !stdio -> runCatching { validateMcpEndpoint(normalizedUrl) }
                .exceptionOrNull()?.message
            else -> null
        }
        if (error == null) {
            scope.launch {
                working = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val draft = McpServerSetting(
                            id = "",
                            name = normalizedName,
                            url = if (stdio) "" else normalizedUrl,
                            transport = transportType,
                            command = if (stdio) command.trim() else "",
                            args = if (stdio) splitArguments(argsText) else emptyList(),
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
                            protocolMode = McpProtocolMode.AUTO,
                            authorizationType = if (token.isBlank()) {
                                McpAuthorizationType.NONE
                            } else {
                                McpAuthorizationType.BEARER
                            },
                        )
                        // 只做本地写入：网络探测放到 UI 释放之后，避免按钮一直转圈。
                        McpServerRepository.add(draft, token)
                    }
                }
                working = false
                result.onSuccess { stored ->
                    val pendingToken = token
                    showAdd = false
                    name = ""
                    url = ""
                    command = ""
                    argsText = ""
                    envText = ""
                    headersText = ""
                    error = null
                    Toast.makeText(
                        context,
                        resources.getString(R.string.mcp_server_added, 0),
                        Toast.LENGTH_SHORT,
                    ).show()
                    // 已落库，工具列表在后台补；Room 的 Flow 会在写入后自动刷新界面。
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            McpServerRepository.update(McpServerManager.discover(stored, pendingToken))
                        }
                    }
                    token = ""
                }.onFailure {
                    error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                }
            }
        }
    }

    val onImport: () -> Unit = {
        scope.launch {
            try {
            working = true
            val result = withContext(Dispatchers.IO) {
                try {
                    val parsed = McpConfigImport.parse(importText)
                    val warnings = parsed.warnings.toMutableList()
                    val imports = mutableListOf<Pair<io.github.mangi.eta.data.model.McpServerSetting, String>>()
                    var added = 0
                    parsed.servers.forEach { candidate ->
                        val token = McpEnv.parseHeaders(candidate.headers)
                            .entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                            ?.value
                            ?.substringAfter("Bearer ", "")
                            ?.takeIf { it.isNotBlank() }
                            .orEmpty()
                        try {
                            // 只做本地写入，网络探测统一放到导入结束之后。
                            imports += McpServerRepository.add(candidate, token) to token
                            added += 1
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            warnings += "「${candidate.name}」${failure.message.orEmpty()}"
                        }
                    }
                    Result.success(Triple(added, warnings, imports))
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Result.failure<Triple<Int, List<String>, List<Pair<io.github.mangi.eta.data.model.McpServerSetting, String>>>>(failure)
                }
            }
            working = false
            result.onSuccess { (added, warnings, pending) ->
                importResult = buildString {
                    append(resources.getString(R.string.mcp_import_done, added))
                    warnings.forEach { append('\n').append(it) }
                }
                if (added > 0) {
                    showImport = false
                    importText = ""
                    // 配置已全部落库，工具列表在后台补；Room 的 Flow 会自动刷新列表。
                    scope.launch(Dispatchers.IO) {
                        pending.forEach { (stored, bearer) ->
                            runCatching {
                                McpServerRepository.update(McpServerManager.discover(stored, bearer))
                            }
                        }
                    }
                }
            }.onFailure {
                importResult = it.message ?: resources.getString(R.string.mcp_connection_failed)
            }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                working = false
                importResult = buildString {
                    append(failure.javaClass.simpleName).append(": ").append(failure.message.orEmpty())
                    append('\n')
                    append(failure.stackTraceToString().take(1200))
                }
            }
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.route_mcp_servers),
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = CouiIcons.Add,
                        contentDescription = stringResource(R.string.mcp_add_server),
                        onClick = { showAdd = true },
                    )
                },
            ) {
                Group(
                    title = stringResource(R.string.mcp_configured_servers, servers.size),
                    headerActions = {
                        if (totalTools > 0) {
                            GroupBatchTogglePill(
                                allEnabled = allServersToolsEnabled,
                                onToggleAll = onToggleAllServersTools,
                            )
                        }
                    },
                ) {
                    if (servers.isEmpty()) {
                        ListEmptyState(
                            title = stringResource(R.string.mcp_empty_title),
                            summary = stringResource(R.string.mcp_empty_summary),
                            icon = Icons.Rounded.Hub,
                            action = {
                                CouiButton(
                                    text = stringResource(R.string.mcp_add_server),
                                    role = CouiButtonRole.Primary,
                                    onClick = { showAdd = true },
                                )
                            },
                        )
                    } else {
                        servers.forEachIndexed { index, server ->
                            CouiPreference(
                                title = server.name,
                                summary = stringResource(
                                    R.string.mcp_server_row_summary,
                                    server.activeTools.size,
                                    server.tools.size,
                                ),
                                onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                            )
                            if (index < servers.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }
                Group {
                    CouiPreference(
                        title = stringResource(R.string.mcp_import_json),
                        summary = stringResource(R.string.mcp_import_json_summary),
                        onClick = {
                            importResult = null
                            showImport = true
                        },
                    )
                }
                CouiPageBottomSpacer()
            }

            CouiConfirmDialog(
                visible = showAdd,
                title = stringResource(R.string.mcp_add_server),
                message = null,
                confirmText = if (working) stringResource(R.string.mcp_connecting) else stringResource(R.string.mcp_add_server),
                confirmEnabled = !working,
                cancelEnabled = !working,
                onConfirm = onConfirmAdd,
                onDismissRequest = { if (!working) showAdd = false },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    CouiTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.mcp_server_name),
                        singleLine = true,
                        enabled = !working,
                    )
                    CouiSwitchPreference(
                        title = stringResource(R.string.mcp_transport_stdio),
                        summary = stringResource(R.string.mcp_transport_stdio_summary),
                        checked = transportType == McpTransportType.STDIO,
                        onCheckedChange = { stdio ->
                            transportType = if (stdio) McpTransportType.STDIO else McpTransportType.HTTP
                        },
                    )
                    if (transportType == McpTransportType.STDIO) {
                        CouiTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = stringResource(R.string.mcp_stdio_command),
                            singleLine = true,
                            enabled = !working,
                        )
                        CouiTextField(
                            value = argsText,
                            onValueChange = { argsText = it },
                            label = stringResource(R.string.mcp_stdio_args),
                            enabled = !working,
                        )
                        CouiTextField(
                            value = envText,
                            onValueChange = { envText = it },
                            label = stringResource(R.string.mcp_stdio_env),
                            enabled = !working,
                        )
                    } else {
                        CouiTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = stringResource(R.string.mcp_server_url),
                            singleLine = true,
                            enabled = !working,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        CouiTextField(
                            value = headersText,
                            onValueChange = { headersText = it },
                            label = stringResource(R.string.mcp_http_headers),
                            enabled = !working,
                        )
                    }
                    CouiTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = stringResource(R.string.mcp_bearer_optional),
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    error?.let {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.route_mcp_servers),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.mcp_add_server),
                        )
                    }
                },
            ) {
                item(key = "servers") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SmallTitle(stringResource(R.string.mcp_configured_servers, servers.size))
                        }
                        if (totalTools > 0) {
                            GroupBatchTogglePill(
                                allEnabled = allServersToolsEnabled,
                                onToggleAll = onToggleAllServersTools,
                            )
                        }
                    }
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        if (servers.isEmpty()) {
                            ListEmptyState(
                                title = stringResource(R.string.mcp_empty_title),
                                summary = stringResource(R.string.mcp_empty_summary),
                                icon = Icons.Rounded.Hub,
                                action = {
                                    TextButton(
                                        text = stringResource(R.string.mcp_add_server),
                                        onClick = { showAdd = true },
                                    )
                                },
                            )
                        } else {
                            servers.forEach { server ->
                                ArrowPreference(
                                    title = server.name,
                                    summary = stringResource(
                                        R.string.mcp_server_row_summary,
                                        server.activeTools.size,
                                        server.tools.size,
                                    ),
                                    onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                                )
                            }
                        }
                    }
                }
                item(key = "import") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.mcp_import_json),
                            summary = stringResource(R.string.mcp_import_json_summary),
                            onClick = {
                                importResult = null
                                showImport = true
                            },
                        )
                    }
                }
            }

            WindowDialog(
                show = showAdd,
                title = stringResource(R.string.mcp_add_server),
                onDismissRequest = { if (!working) showAdd = false },
            ) {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.mcp_server_name),
                            singleLine = true,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SwitchPreference(
                            title = stringResource(R.string.mcp_transport_stdio),
                            summary = stringResource(R.string.mcp_transport_stdio_summary),
                            checked = transportType == McpTransportType.STDIO,
                            onCheckedChange = { stdio ->
                                transportType = if (stdio) McpTransportType.STDIO else McpTransportType.HTTP
                            },
                        )
                        if (transportType == McpTransportType.STDIO) {
                            TextField(
                                value = command,
                                onValueChange = { command = it },
                                label = stringResource(R.string.mcp_stdio_command),
                                singleLine = true,
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = argsText,
                                onValueChange = { argsText = it },
                                label = stringResource(R.string.mcp_stdio_args),
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = envText,
                                onValueChange = { envText = it },
                                label = stringResource(R.string.mcp_stdio_env),
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            TextField(
                                value = url,
                                onValueChange = { url = it },
                                label = stringResource(R.string.mcp_server_url),
                                singleLine = true,
                                enabled = !working,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = headersText,
                                onValueChange = { headersText = it },
                                label = stringResource(R.string.mcp_http_headers),
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextField(
                            value = token,
                            onValueChange = { token = it },
                            label = stringResource(R.string.mcp_bearer_optional),
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        error?.let {
                            Text(
                                text = it,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                    MiuixDialogActions(
                        modifier = Modifier.padding(top = 16.dp),
                        confirmText = if (working) {
                            stringResource(R.string.mcp_connecting)
                        } else {
                            stringResource(R.string.mcp_add_server)
                        },
                        confirmEnabled = !working,
                        onCancel = { showAdd = false },
                        onConfirm = onConfirmAdd,
                    )
                }
            }
        }
    }

    if (showImport) {
        McpImportDialog(
            value = importText,
            onValueChange = { importText = it },
            result = importResult,
            busy = working,
            onConfirm = onImport,
            onDismiss = {
                if (!working) {
                    showImport = false
                    importResult = null
                }
            },
        )
    }
}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }

    var optimisticServerEnabled by remember(server?.id, server?.enabled) {
        mutableStateOf(server?.enabled ?: true)
    }
    var optimisticEnabledTools by remember(server?.id, server?.enabledToolNames) {
        mutableStateOf(server?.enabledToolNames.orEmpty())
    }

    val onToggleServerEnabled: (Boolean) -> Unit = { enabled ->
        val current = server
        if (current != null) {
            val previous = optimisticServerEnabled
            optimisticServerEnabled = enabled
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        McpServerRepository.update(current.copy(enabled = enabled))
                    }
                }.onFailure { failure ->
                    optimisticServerEnabled = previous
                    Toast.makeText(
                        context,
                        failure.message ?: resources.getString(R.string.settings_write_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val onToggleTool: (McpToolDefinition, Boolean) -> Unit = { tool, enabled ->
        val previous = optimisticEnabledTools
        optimisticEnabledTools = previous.toMutableSet().apply {
            if (enabled) add(tool.name) else remove(tool.name)
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                }
            }.onFailure { failure ->
                optimisticEnabledTools = previous
                Toast.makeText(
                    context,
                    failure.message ?: resources.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val onToggleAllTools: (Boolean) -> Unit = { enabled ->
        val current = server
        if (current != null) {
            val previous = optimisticEnabledTools
            optimisticEnabledTools = if (enabled) {
                current.tools.mapTo(linkedSetOf()) { it.name }
            } else {
                emptySet()
            }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        McpServerRepository.setAllToolsEnabled(serverId, enabled)
                    }
                }.onFailure { failure ->
                    optimisticEnabledTools = previous
                    Toast.makeText(
                        context,
                        failure.message ?: resources.getString(R.string.settings_write_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    val onRefresh: () -> Unit = {
        scope.launch {
            working = true
            val result = withContext(Dispatchers.IO) {
                runCatching { McpServerManager.refresh(serverId) }
            }
            working = false
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { resources.getString(R.string.mcp_refreshed, it.tools.size) },
                    onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dialogColors = CouiDialogDefaults.themeColors()
            CouiLazyScaffoldPage(
                title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.mcp_refresh_tools),
                        enabled = server != null && !working,
                        onClick = onRefresh,
                    )
                },
            ) {
                if (server == null) {
                    item(key = "missing") {
                        ListEmptyState(title = stringResource(R.string.mcp_server_missing))
                    }
                    return@CouiLazyScaffoldPage
                }
                item(key = "server-settings") {
                    Group(title = stringResource(R.string.mcp_server_settings)) {
                        CouiSwitchPreference(
                            title = stringResource(R.string.mcp_enable_server),
                            summary = endpointSummary(server),
                            checked = optimisticServerEnabled,
                            onCheckedChange = onToggleServerEnabled,
                        )
                        Divider()
                        CouiPreference(
                            title = stringResource(R.string.mcp_edit_server),
                            summary = stringResource(R.string.mcp_edit_server_summary),
                            onClick = { showEdit = true },
                        )
                        Divider()
                        CouiPreference(
                            title = stringResource(R.string.mcp_update_token),
                            summary = stringResource(
                                if (server.authorizationType == McpAuthorizationType.BEARER) {
                                    R.string.mcp_token_configured
                                } else {
                                    R.string.mcp_no_authentication
                                },
                            ),
                            onClick = { showToken = true },
                        )
                    }
                }


                val enabledCount = server.tools.count { it.name in optimisticEnabledTools }
                val allEnabled = server.tools.isNotEmpty() && enabledCount == server.tools.size
                item(key = "tools-header") {
                    CouiSectionTitle(
                        text = "${stringResource(R.string.mcp_tools_count, server.tools.size)} · 已启用 $enabledCount",
                        trailing = {
                            if (server.tools.isNotEmpty()) {
                                GroupBatchTogglePill(
                                    allEnabled = allEnabled,
                                    onToggleAll = { onToggleAllTools(!allEnabled) },
                                )
                            }
                        },
                    )
                }

                if (server.tools.isEmpty()) {
                    item(key = "tools-empty") {
                        Group {
                            ListEmptyState(
                                title = stringResource(R.string.mcp_no_tools),
                                summary = stringResource(R.string.mcp_refresh_tools_hint),
                                icon = Icons.Rounded.Refresh,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = server.tools,
                        key = { _, tool -> "mcp-tool-${tool.name}" },
                    ) { index, tool ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == server.tools.lastIndex,
                        ) {
                            McpToolSwitchRow(
                                tool = tool,
                                checked = tool.name in optimisticEnabledTools,
                                onCheckedChange = { enabled -> onToggleTool(tool, enabled) },
                            )
                        }
                    }
                }

                item(key = "delete-server") {
                    Group {
                        CouiPreference(
                            title = stringResource(R.string.mcp_delete_server),
                            titleColor = dialogColors.destructive,
                            summary = stringResource(R.string.mcp_delete_server_summary),
                            showChevron = false,
                            onClick = { showDelete = true },
                        )
                    }
                }
            }

            CouiInputDialog(
                visible = showToken,
                title = stringResource(R.string.mcp_update_token),
                message = stringResource(R.string.mcp_update_token_summary),
                value = token,
                onValueChange = { token = it },
                placeholder = stringResource(R.string.mcp_bearer_token),
                confirmText = stringResource(R.string.action_save),
                onConfirm = {
                    server?.let { current ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(
                                current.copy(
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                ),
                                bearerToken = token,
                            )
                        }
                        token = ""
                        showToken = false
                    }
                },
                onDismissRequest = { showToken = false },
            )

            CouiConfirmDialog(
                visible = showDelete,
                title = stringResource(R.string.mcp_delete_server),
                message = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                onConfirm = {
                    scope.launch {
                        withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                        onBack()
                    }
                },
                onDismissRequest = { showDelete = false },
            )
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
                onBack = onBack,
                actions = {
                    IconButton(
                        enabled = server != null && !working,
                        onClick = onRefresh,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.mcp_refresh_tools),
                        )
                    }
                },
            ) {
                if (server == null) {
                    item(key = "missing") {
                        ListEmptyState(title = stringResource(R.string.mcp_server_missing))
                    }
                    return@MiuixScaffoldPage
                }
                item(key = "server") {
                    SmallTitle(stringResource(R.string.mcp_server_settings))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.mcp_enable_server),
                            summary = endpointSummary(server),
                            checked = optimisticServerEnabled,
                            onCheckedChange = onToggleServerEnabled,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.mcp_edit_server),
                            summary = stringResource(R.string.mcp_edit_server_summary),
                            onClick = { showEdit = true },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.mcp_update_token),
                            summary = stringResource(
                                if (server.authorizationType == McpAuthorizationType.BEARER) {
                                    R.string.mcp_token_configured
                                } else {
                                    R.string.mcp_no_authentication
                                },
                            ),
                            onClick = { showToken = true },
                        )
                    }
                }

                val enabledCount = server.tools.count { it.name in optimisticEnabledTools }
                val allEnabled = server.tools.isNotEmpty() && enabledCount == server.tools.size
                item(key = "tools-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SmallTitle("${stringResource(R.string.mcp_tools_count, server.tools.size)} · 已启用 $enabledCount")
                        }
                        if (server.tools.isNotEmpty()) {
                            GroupBatchTogglePill(
                                allEnabled = allEnabled,
                                onToggleAll = { onToggleAllTools(!allEnabled) },
                            )
                        }
                    }
                }

                if (server.tools.isEmpty()) {
                    item(key = "tools-empty") {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            ListEmptyState(
                                title = stringResource(R.string.mcp_no_tools),
                                summary = stringResource(R.string.mcp_refresh_tools_hint),
                                icon = Icons.Rounded.Refresh,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = server.tools,
                        key = { _, tool -> "mcp-tool-${tool.name}" },
                    ) { index, tool ->
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == server.tools.lastIndex,
                        ) {
                            McpToolSwitchRow(
                                tool = tool,
                                checked = tool.name in optimisticEnabledTools,
                                onCheckedChange = { enabled -> onToggleTool(tool, enabled) },
                            )
                        }
                    }
                }

                item(key = "delete") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 4.dp, bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.mcp_delete_server),
                            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                            summary = stringResource(R.string.mcp_delete_server_summary),
                            onClick = { showDelete = true },
                        )
                    }
                }
            }

            WindowDialog(
                show = showToken,
                title = stringResource(R.string.mcp_update_token),
                summary = stringResource(R.string.mcp_update_token_summary),
                onDismissRequest = { showToken = false },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextField(
                        value = token,
                        onValueChange = { token = it },
                        label = stringResource(R.string.mcp_bearer_token),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.action_save),
                        onCancel = { showToken = false },
                        onConfirm = {
                            server?.let { current ->
                                scope.launch(Dispatchers.IO) {
                                    McpServerRepository.update(
                                        current.copy(
                                            authorizationType = if (token.isBlank()) {
                                                McpAuthorizationType.NONE
                                            } else {
                                                McpAuthorizationType.BEARER
                                            },
                                        ),
                                        bearerToken = token,
                                    )
                                }
                                token = ""
                                showToken = false
                            }
                        },
                    )
                }
            }

            WindowDialog(
                show = showDelete,
                title = stringResource(R.string.mcp_delete_server),
                summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
                onDismissRequest = { showDelete = false },
            ) {
                MiuixDialogActions(
                    confirmText = stringResource(R.string.ui_delete_3755f5),
                    destructive = true,
                    onCancel = { showDelete = false },
                    onConfirm = {
                        scope.launch {
                            withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                            onBack()
                        }
                    },
                )
            }
        }
    }

    if (showDiagnostics) {
        server?.let { current ->
            McpDiagnosticsDialog(
                server = current,
                onDismiss = { showDiagnostics = false },
            )
        }
    }

    if (showEdit) {
        server?.let { current ->
            McpServerEditDialog(
                server = current,
                onDismiss = { showEdit = false },
                onSaved = {
                    showEdit = false
                    Toast.makeText(
                        context,
                        resources.getString(R.string.mcp_server_updated),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
    }
}

private fun endpointSummary(server: McpServerSetting): String =
    if (server.isStdio) {
        (listOf(server.command) + server.args).joinToString(" ")
    } else {
        server.url
    }

/** 按 shell 习惯切分参数：引号内保留空格，引号本身不进入 argv。 */
private fun splitArguments(raw: String): List<String> {
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

/**
 * MCP 单个工具行：
 * - 顶行左侧展示工具名称，右侧固定放置开关（展开长说明时开关仍保持在顶行右侧，绝不被长文挤到底部）；
 * - 说明默认折叠为 1～2 行紧凑摘要，长说明提供「展开 / 收起」并按 Markdown 排版展示全文。
 */
@Composable
private fun McpToolSwitchRow(
    tool: McpToolDefinition,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayTitle = tool.title.ifBlank { tool.name }
    val showRawName = tool.title.isNotBlank() && tool.title != tool.name
    val fallbackSummary = if (tool.readOnlyHint == true) {
        stringResource(R.string.mcp_read_only_tool)
    } else {
        stringResource(R.string.mcp_may_modify_data)
    }
    val rawDescription = tool.description.trim()
    val collapsedPreview = remember(rawDescription, fallbackSummary) {
        if (rawDescription.isBlank()) fallbackSummary else collapseDescriptionPreview(rawDescription)
    }
    val isinherentlyMultiLine = remember(rawDescription) {
        rawDescription.count { it == '\n' } >= 2 || rawDescription.length > 90
    }
    var hasVisualOverflow by remember(collapsedPreview) { mutableStateOf(false) }
    var expanded by rememberSaveable(tool.name) { mutableStateOf(false) }
    val canExpand = rawDescription.isNotBlank() && (isinherentlyMultiLine || hasVisualOverflow || expanded)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            val view = LocalView.current
            val headerInteraction = remember { MutableInteractionSource() }
            var optimisticChecked by remember(checked) { mutableStateOf(checked) }
            val commitToggle: (Boolean) -> Unit = { target ->
                optimisticChecked = target
                onCheckedChange(target)
            }

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = optimisticChecked,
                            interactionSource = headerInteraction,
                            indication = null,
                            enabled = true,
                            role = Role.Switch,
                            onValueChange = { target ->
                                view.performCouiHapticFeedback(CouiHapticFeedback.Switch, true)
                                commitToggle(target)
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        CouiText(
                            text = displayTitle,
                            kind = CouiTextKind.PreferenceTitle,
                            color = colors.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showRawName) {
                            CouiText(
                                text = tool.name,
                                kind = CouiTextKind.Description,
                                color = colors.summary.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    CouiSwitch(
                        checked = optimisticChecked,
                        onCheckedChange = null,
                        dark = dark,
                        colors = CouiSwitchDefaults.colors(colors.accent, dark),
                        enabled = true,
                        interactionSource = headerInteraction,
                        onDragCheckedChange = commitToggle,
                    )
                }

                Spacer(Modifier.height(4.dp))

                if (!expanded) {
                    Text(
                        text = collapsedPreview,
                        style = MiuixTheme.textStyles.body2,
                        color = colors.summary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (result.hasVisualOverflow != hasVisualOverflow) {
                                hasVisualOverflow = result.hasVisualOverflow
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (canExpand) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { expanded = true }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.title.copy(alpha = 0.04f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        StaticMarkdown(
                            content = rawDescription,
                            tone = MarkdownTone.Thinking,
                        )
                    }
                }

                if (canExpand) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { expanded = !expanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        CouiText(
                            text = if (expanded) "收起说明" else "展开说明",
                            kind = CouiTextKind.Description,
                            color = colors.accent,
                        )
                        CouiIcon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = colors.accent,
                        )
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            val headerInteraction = remember { MutableInteractionSource() }
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            interactionSource = headerInteraction,
                            indication = null,
                            enabled = true,
                            role = Role.Switch,
                            onValueChange = onCheckedChange,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayTitle,
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showRawName) {
                            Text(
                                text = tool.name,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                    )
                }

                Spacer(Modifier.height(4.dp))

                if (!expanded) {
                    Text(
                        text = collapsedPreview,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (result.hasVisualOverflow != hasVisualOverflow) {
                                hasVisualOverflow = result.hasVisualOverflow
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (canExpand) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { expanded = true }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        StaticMarkdown(
                            content = rawDescription,
                            tone = MarkdownTone.Thinking,
                        )
                    }
                }

                if (canExpand) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { expanded = !expanded }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (expanded) "收起说明" else "展开说明",
                            style = MiuixTheme.textStyles.footnote1.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 将可能带大量换行、标题井号与列表前缀的 MCP 工具原始说明收敛为紧凑的折叠态预览文本。
 */
internal fun collapseDescriptionPreview(raw: String): String =
    raw.lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("### ")
                .removePrefix("## ")
                .removePrefix("# ")
                .removePrefix("- ")
                .removePrefix("* ")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
