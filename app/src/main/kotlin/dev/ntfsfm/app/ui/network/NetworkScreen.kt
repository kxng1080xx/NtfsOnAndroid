package dev.ntfsfm.app.ui.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ntfsfm.app.data.NetworkRepository
import dev.ntfsfm.app.ui.design.EmptyState
import dev.ntfsfm.app.ui.design.RoundIconButton
import dev.ntfsfm.app.ui.design.PageTitle
import dev.ntfsfm.app.ui.design.bottomClearance
import dev.ntfsfm.app.ui.design.card
import dev.ntfsfm.core.network.NetworkProtocol
import dev.ntfsfm.core.network.NetworkServer
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class NetworkViewModel @Inject constructor(private val repository: NetworkRepository) : ViewModel() {
    val servers = repository.servers
    val connected = repository.connected
    val connecting = MutableStateFlow<Set<String>>(emptySet())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())

    fun save(server: NetworkServer, thenConnect: Boolean, onConnected: (volumeId: String) -> Unit) {
        val saved = repository.save(server)
        if (thenConnect) connect(saved, onConnected)
    }

    fun delete(id: String) = repository.delete(id)

    fun disconnect(id: String) = repository.disconnect(id)

    fun connect(server: NetworkServer, onConnected: (volumeId: String) -> Unit) {
        connecting.update { it + server.id }
        errors.update { it - server.id }
        viewModelScope.launch {
            runCatching { repository.connect(server) }
                .onSuccess { onConnected(server.volumeId) }
                .onFailure { e -> errors.update { it + (server.id to (e.cause?.message ?: e.message ?: "Couldn't connect")) } }
            connecting.update { it - server.id }
        }
    }
}

@Composable
fun NetworkScreen(onOpenVolume: (volumeId: String) -> Unit, viewModel: NetworkViewModel = hiltViewModel()) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<NetworkServer?>(null) }
    val newServer = { editing = NetworkServer(id = "", protocol = NetworkProtocol.SMB, name = "", host = "") }

    Column(Modifier.fillMaxSize()) {
        PageTitle("Network", "Shared folders and FTP servers") {
            RoundIconButton(Icons.Rounded.Add, "Add server", newServer)
        }
        if (servers.isEmpty()) {
            EmptyState(
                Icons.Rounded.Lan,
                "No servers yet",
                "Add a Windows or NAS shared folder (SMB), or an FTP server, to copy files over Wi-Fi.",
                Modifier.padding(bottom = bottomClearance()),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomClearance()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        isConnected = server.id in connected,
                        isConnecting = server.id in connecting,
                        error = errors[server.id],
                        onOpen = {
                            if (server.id in connected) onOpenVolume(server.volumeId) else viewModel.connect(server, onOpenVolume)
                        },
                        onEdit = { editing = server },
                        onDisconnect = { viewModel.disconnect(server.id) },
                        onDelete = { viewModel.delete(server.id) },
                    )
                }
            }
        }
    }

    editing?.let { server ->
        ServerEditor(
            initial = server,
            onDismiss = { editing = null },
            onSave = { edited, connect ->
                editing = null
                viewModel.save(edited, connect, onOpenVolume)
            },
        )
    }
}

private fun NetworkProtocol.accent() = when (this) {
    NetworkProtocol.SMB -> Color(0xFF3B5BFD)
    NetworkProtocol.FTP -> Color(0xFF0FA3A3)
    NetworkProtocol.FTPS -> Color(0xFF7B61FF)
}

private fun NetworkProtocol.icon() = when (this) {
    NetworkProtocol.SMB -> Icons.Rounded.Computer
    NetworkProtocol.FTP -> Icons.Rounded.Public
    NetworkProtocol.FTPS -> Icons.Rounded.Lock
}

@Composable
private fun ServerCard(
    server: NetworkServer,
    isConnected: Boolean,
    isConnecting: Boolean,
    error: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val accent = server.protocol.accent()
    Column(
        Modifier
            .fillMaxWidth()
            .card(MaterialTheme.shapes.extraLarge, tint = accent.copy(alpha = 0.06f))
            .clickable(enabled = !isConnecting, onClick = onOpen)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(server.protocol.icon(), null, tint = accent) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name.ifBlank { server.host }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Server options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                    if (isConnected) {
                        DropdownMenuItem(text = { Text("Disconnect") }, leadingIcon = { Icon(Icons.Rounded.LinkOff, null) }, onClick = { menu = false; onDisconnect() })
                    }
                    DropdownMenuItem(
                        text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(isConnected)
            Spacer(Modifier.weight(1f))
            when {
                isConnecting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                isConnected -> FilledTonalButton(onClick = onOpen) { Text("Open") }
                else -> Button(onClick = onOpen) { Text("Connect") }
            }
        }
        AnimatedVisibility(error != null) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusPill(connected: Boolean) {
    val color = if (connected) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "Connected" else "Not connected", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditor(initial: NetworkServer, onDismiss: () -> Unit, onSave: (NetworkServer, connect: Boolean) -> Unit) {
    var protocol by rememberSaveable { mutableStateOf(initial.protocol) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var share by rememberSaveable { mutableStateOf(initial.share) }
    var rootPath by rememberSaveable { mutableStateOf(initial.rootPath) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var password by rememberSaveable { mutableStateOf(initial.password) }
    var domain by rememberSaveable { mutableStateOf(initial.domain) }
    var showPassword by remember { mutableStateOf(false) }
    val valid = host.isNotBlank() && (protocol != NetworkProtocol.SMB || share.isNotBlank())

    fun build() = initial.copy(
        protocol = protocol,
        name = name.trim().ifEmpty { host.trim() },
        host = host.trim(),
        port = port.toIntOrNull() ?: 0,
        share = share.trim().trim('/', '\\'),
        rootPath = rootPath.trim(),
        username = username.trim(),
        password = password,
        domain = domain.trim(),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (initial.id.isBlank()) "Add server" else "Edit server", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth().card(CircleShape, elevation = 1.2f).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NetworkProtocol.entries.forEach { p ->
                    val selected = p == protocol
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { protocol = p }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(p.label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Field("Host or IP address", host, { host = it }, placeholder = "192.168.1.20", keyboard = KeyboardType.Uri)
            if (protocol == NetworkProtocol.SMB) Field("Share", share, { share = it }, placeholder = "Public")
            Field("Folder (optional)", rootPath, { rootPath = it }, placeholder = if (protocol == NetworkProtocol.SMB) "" else "/pub")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Name (optional)", name, { name = it }, Modifier.weight(1.6f))
                Field("Port", port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f), placeholder = protocol.defaultPort.toString(), keyboard = KeyboardType.Number)
            }
            Field("Username", username, { username = it }, placeholder = if (protocol == NetworkProtocol.SMB) "Leave empty for guest" else "Leave empty for anonymous")
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (protocol == NetworkProtocol.SMB) Field("Domain (optional)", domain, { domain = it }, placeholder = "WORKGROUP")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onSave(build(), false) }, enabled = valid) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(build(), true) }, enabled = valid) { Text("Save & connect") }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}
