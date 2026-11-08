package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
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
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiInputDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
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
                        // 先落库再发现：服务器暂时离线、内网地址当前不可达，都不该拦住保存。
                        val stored = McpServerRepository.add(draft, token)
                        val warning = runCatching {
                            McpServerRepository.update(McpServerManager.discover(stored, token))
                        }.exceptionOrNull()?.let { "已保存，但暂时没连上：${it.message.orEmpty()}" }
                        stored to warning
                    }
                }
                working = false
                result.onSuccess { (stored, warning) ->
                    showAdd = false
                    name = ""
                    url = ""
                    command = ""
                    argsText = ""
                    envText = ""
                    headersText = ""
                    token = ""
                    error = null
                    val message = warning
                        ?: resources.getString(R.string.mcp_server_added, stored.tools.size)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                    var added = 0
                    parsed.servers.forEach { candidate ->
                        val token = McpEnv.parseHeaders(candidate.headers)
                            .entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                            ?.value
                            ?.substringAfter("Bearer ", "")
                            ?.takeIf { it.isNotBlank() }
                            .orEmpty()
                        try {
                            // 先落库，再尽力拉一次工具；连不上只记警告，不影响导入结果。
                            val stored = McpServerRepository.add(candidate, token)
                            added += 1
                            try {
                                McpServerRepository.update(McpServerManager.discover(stored, token))
                            } catch (failure: kotlinx.coroutines.CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                warnings += "「${candidate.name}」已保存，暂时没连上：${failure.message.orEmpty()}"
                            }
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            warnings += "「${candidate.name}」${failure.message.orEmpty()}"
                        }
                    }
                    Result.success(added to warnings)
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Result.failure<Pair<Int, List<String>>>(failure)
                }
            }
            working = false
            result.onSuccess { (added, warnings) ->
                importResult = buildString {
                    append(resources.getString(R.string.mcp_import_done, added))
                    warnings.forEach { append('\n').append(it) }
                }
                if (added > 0) {
                    showImport = false
                    importText = ""
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
                Group(title = stringResource(R.string.mcp_configured_servers, servers.size)) {
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
                    SmallTitle(stringResource(R.string.mcp_configured_servers, servers.size))
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
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }

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
            CouiScaffoldPage(
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
                    ListEmptyState(title = stringResource(R.string.mcp_server_missing))
                    return@CouiScaffoldPage
                }
                Group(title = stringResource(R.string.mcp_server_settings)) {
                    CouiSwitchPreference(
                        title = stringResource(R.string.mcp_enable_server),
                        summary = server.url,
                        checked = server.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch(Dispatchers.IO) {
                                McpServerRepository.update(server.copy(enabled = enabled))
                            }
                        },
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

                Group(title = stringResource(R.string.mcp_tools_count, server.tools.size)) {
                    if (server.tools.isEmpty()) {
                        ListEmptyState(
                            title = stringResource(R.string.mcp_no_tools),
                            summary = stringResource(R.string.mcp_refresh_tools_hint),
                            icon = Icons.Rounded.Refresh,
                        )
                    } else {
                        server.tools.forEachIndexed { index, tool ->
                            val checked = tool.name in server.enabledToolNames
                            CouiSwitchPreference(
                                title = tool.title.ifBlank { tool.name },
                                summary = toolSummary(tool),
                                checked = checked,
                                onCheckedChange = { enabled ->
                                    if (enabled && tool.readOnlyHint != true) {
                                        pendingRiskyTool = tool
                                    } else {
                                        scope.launch(Dispatchers.IO) {
                                            McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                        }
                                    }
                                },
                            )
                            if (index < server.tools.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                Group {
                    CouiPreference(
                        title = stringResource(R.string.mcp_delete_server),
                        titleColor = dialogColors.destructive,
                        summary = stringResource(R.string.mcp_delete_server_summary),
                        showChevron = false,
                        onClick = { showDelete = true },
                    )
                }
                CouiPageBottomSpacer()
            }

            pendingRiskyTool?.let { tool ->
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.mcp_enable_risky_tool),
                    message = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
                    confirmText = stringResource(R.string.mcp_enable_tool),
                    destructive = true,
                    onConfirm = {
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.setToolEnabled(serverId, tool.name, true)
                        }
                        pendingRiskyTool = null
                    },
                    onDismissRequest = { pendingRiskyTool = null },
                )
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
                            summary = server.url,
                            checked = server.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch(Dispatchers.IO) {
                                    McpServerRepository.update(server.copy(enabled = enabled))
                                }
                            },
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
                item(key = "tools") {
                    SmallTitle(stringResource(R.string.mcp_tools_count, server.tools.size))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        if (server.tools.isEmpty()) {
                            ListEmptyState(
                                title = stringResource(R.string.mcp_no_tools),
                                summary = stringResource(R.string.mcp_refresh_tools_hint),
                                icon = Icons.Rounded.Refresh,
                            )
                        } else {
                            server.tools.forEach { tool ->
                                val checked = tool.name in server.enabledToolNames
                                SwitchPreference(
                                    title = tool.title.ifBlank { tool.name },
                                    summary = toolSummary(tool),
                                    checked = checked,
                                    onCheckedChange = { enabled ->
                                        if (enabled && tool.readOnlyHint != true) {
                                            pendingRiskyTool = tool
                                        } else {
                                            scope.launch(Dispatchers.IO) {
                                                McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                            }
                                        }
                                    },
                                )
                            }
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

            pendingRiskyTool?.let { tool ->
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.mcp_enable_risky_tool),
                    summary = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
                    onDismissRequest = { pendingRiskyTool = null },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.mcp_enable_tool),
                        destructive = true,
                        onCancel = { pendingRiskyTool = null },
                        onConfirm = {
                            scope.launch(Dispatchers.IO) {
                                McpServerRepository.setToolEnabled(serverId, tool.name, true)
                            }
                            pendingRiskyTool = null
                        },
                    )
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

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}
