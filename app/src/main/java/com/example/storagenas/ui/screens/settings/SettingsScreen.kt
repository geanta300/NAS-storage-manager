package com.example.storagenas.ui.screens.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storagenas.domain.model.ZeroTierConnectionMode
import com.example.storagenas.ui.components.PremiumCard
import com.example.storagenas.ui.components.SectionHeader

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToNasBrowser: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val requestNotificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.onKeepZeroTierAliveInBackgroundChanged(true)
            } else {
                viewModel.onKeepAliveNotificationPermissionDenied()
            }
        }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let {
            snackbarHostState.showSnackbar(message = it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.saveConfig() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                if (uiState.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Save, contentDescription = "Save Settings")
                }
            }
        }
    ) { padding ->
        SettingsContent(
            uiState = uiState,
            viewModel = viewModel,
            onPickDefaultFolder = onNavigateToNasBrowser,
            onOpenLogs = onNavigateToLogs,
            onKeepAliveToggle = { enabled ->
                if (enabled &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.onKeepZeroTierAliveInBackgroundChanged(enabled)
                }
            },
            modifier = Modifier.padding(padding)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onPickDefaultFolder: () -> Unit,
    onOpenLogs: () -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var connectionModeExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 80.dp) // Space for FAB
    ) {
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            SectionHeader(title = "Network (ZeroTier)")
            PremiumCard {
                OutlinedTextField(
                    value = uiState.zeroTierNetworkId,
                    onValueChange = viewModel::onZeroTierNetworkIdChanged,
                    label = { Text("Network ID (16 hex chars)") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.WifiTethering, contentDescription = null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.zeroTierApiToken,
                    onValueChange = viewModel::onZeroTierApiTokenChanged,
                    label = { Text("API Token (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = connectionModeExpanded,
                    onExpandedChange = { connectionModeExpanded = !connectionModeExpanded },
                ) {
                    OutlinedTextField(
                        value = uiState.zeroTierConnectionMode.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Connection Mode") },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = connectionModeExpanded) },
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = connectionModeExpanded,
                        onDismissRequest = { connectionModeExpanded = false },
                    ) {
                        ZeroTierConnectionMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(mode.name)
                                        Text(
                                            text = mode.description(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.onZeroTierConnectionModeChanged(mode)
                                    connectionModeExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = uiState.zeroTierConnectionMode.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep ZeroTier Alive In Background",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Uses an optional foreground service so reconnect is faster when reopening the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = uiState.keepZeroTierAliveInBackground,
                        onCheckedChange = onKeepAliveToggle,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow TCP Fallback on LAN",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "If ZeroTier routing fails but the NAS is reachable on the local network (Wi-Fi), fall back to a direct TCP connection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = uiState.useTcpFallbackOnLan,
                        onCheckedChange = { viewModel.onUseTcpFallbackOnLanChanged(it) },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ZeroTier provides private overlay-path encryption. Direct TCP fallback on LAN does not rely on ZeroTier but guarantees connectivity if the SDK stalls. Default is off for strict security.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Background keep-alive shows a persistent notification and may increase battery usage slightly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionHeader(title = "NAS Configuration (SFTP)")
            PremiumCard {
                OutlinedTextField(
                    value = uiState.host,
                    onValueChange = viewModel::onHostChanged,
                    label = { Text("Host / IP Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.port,
                        onValueChange = viewModel::onPortChanged,
                        label = { Text("Port") },
                        modifier = Modifier.weight(0.3f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = uiState.username,
                        onValueChange = viewModel::onUsernameChanged,
                        label = { Text("Username") },
                        modifier = Modifier.weight(0.7f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = viewModel::onPasswordChanged,
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Default Remote Folder",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = uiState.defaultRemotePath,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onPickDefaultFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick Folder")
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.connectToZeroTier() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isBusy
                ) {
                    Text("Connect to ZeroTier")
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { viewModel.connectToNas() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isBusy
                ) {
                    Text("Connect to NAS")
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Logs")
                }
            }
        }
    }
}

private fun ZeroTierConnectionMode.description(): String =
    when (this) {
        ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST ->
            "Try system sockets first, then fallback to embedded ZeroTier if needed."

        ZeroTierConnectionMode.AUTO_FALLBACK ->
            "Try embedded ZeroTier first, then fallback to system sockets."

        ZeroTierConnectionMode.EMBEDDED_ONLY ->
            "Use only embedded ZeroTier sockets (no system-route fallback)."
    }
