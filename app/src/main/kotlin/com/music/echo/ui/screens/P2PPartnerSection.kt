package echo.music.iad1tya.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.echo.p2p.DiscoveredPeer
import com.music.echo.p2p.P2PConnectionStatus
import echo.music.iad1tya.LocalListenTogetherManager
import echo.music.iad1tya.R
import echo.music.iad1tya.listentogether.ConnectionState
import echo.music.iad1tya.utils.rememberPreference

@Composable
fun P2PPartnerSection(
    username: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = LocalListenTogetherManager.current ?: return
    val p2pManager = manager.p2pPartnerManager

    val p2pStatus by p2pManager.status.collectAsState()
    val savedPartnerIp by p2pManager.savedPartnerAddress.collectAsState()
    val deviceName by p2pManager.deviceName.collectAsState()
    val isServerRunning by p2pManager.isServerRunning.collectAsState()
    val localPort by p2pManager.localPort.collectAsState()
    val discoveredPeers by p2pManager.discovery.discoveredPeers.collectAsState()
    val isScanning by p2pManager.discovery.isScanning.collectAsState()
    val connectionState by manager.connectionState.collectAsState()

    var partnerIpInput by rememberSaveable(savedPartnerIp) { mutableStateOf(savedPartnerIp) }
    var deviceNameInput by rememberSaveable(deviceName) { mutableStateOf(deviceName) }
    var isEditingDeviceName by rememberSaveable { mutableStateOf(false) }

    val (autoDiscoverable, onAutoDiscoverableChange) = rememberPreference(
        key = echo.music.iad1tya.constants.ListenTogetherAutoDiscoverableKey,
        defaultValue = false
    )

    val ipAddresses = remember { p2pManager.discovery.getDeviceIpAddresses() }
    val myIpsSet = remember(ipAddresses) { ipAddresses.map { it.first }.toSet() }

    LaunchedEffect(autoDiscoverable) {
        if (autoDiscoverable) {
            p2pManager.scanForPartners()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.wifi_proxy),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.p2p_partner_mesh),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.p2p_partner_mesh_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Auto-Discoverable Standby Toggle
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-discoverable in Background",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (autoDiscoverable) "Device is listening for partners" else "Tap 'Host P2P Session' to make discoverable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoDiscoverable,
                        onCheckedChange = { onAutoDiscoverableChange(it) }
                    )
                }
            }

            // Device Name Customization Field
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.p2p_device_name),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!isEditingDeviceName) {
                            TextButton(
                                onClick = { isEditingDeviceName = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(stringResource(R.string.edit), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (isEditingDeviceName) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = deviceNameInput,
                                onValueChange = { deviceNameInput = it },
                                placeholder = { Text(stringResource(R.string.p2p_device_name_hint)) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    p2pManager.saveDeviceName(deviceNameInput)
                                    isEditingDeviceName = false
                                    Toast.makeText(context, "Device name saved", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(stringResource(R.string.done))
                            }
                        }
                    } else {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Own Device IPs Helper Card
            if (ipAddresses.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.p2p_your_ips),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        ipAddresses.take(3).forEach { (ip, isTailscale) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", ip))
                                        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(vertical = 4.dp, horizontal = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isTailscale) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                                    )
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (isTailscale) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = "Tailscale",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Icon(
                                    painter = painterResource(R.drawable.content_copy),
                                    contentDescription = stringResource(R.string.copy_code),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Partner IP Input
            OutlinedTextField(
                value = partnerIpInput,
                onValueChange = { partnerIpInput = it },
                label = { Text(stringResource(R.string.p2p_partner_ip_hint)) },
                placeholder = { Text("100.x.y.z or partner-device") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.network_node),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )

            // Scan / Refresh Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.p2p_discovered_peers),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                TextButton(
                    onClick = { p2pManager.scanForPartners() },
                    enabled = !isScanning,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.p2p_scanning), style = MaterialTheme.typography.labelSmall)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.refresh),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.p2p_scan_peers), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Discovered Peers List (excluding self)
            val validDiscoveredPeers = remember(discoveredPeers, myIpsSet, deviceName) {
                discoveredPeers.filter { peer ->
                    peer.hostAddress !in myIpsSet &&
                    peer.hostAddress != "127.0.0.1" &&
                    !peer.hostAddress.startsWith("127.") &&
                    !peer.name.equals(deviceName, ignoreCase = true)
                }
            }

            if (validDiscoveredPeers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    validDiscoveredPeers.forEach { peer ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    partnerIpInput = peer.hostAddress
                                    manager.connectToPartner(peer.hostAddress, deviceName)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Column {
                                        Text(
                                            text = peer.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${peer.hostAddress}:${peer.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                FilledTonalButton(
                                    onClick = {
                                        partnerIpInput = peer.hostAddress
                                        manager.connectToPartner(peer.hostAddress, deviceName)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(stringResource(R.string.connect))
                                }
                            }
                        }
                    }
                }
            } else if (isScanning) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.p2p_scanning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Connection Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isConnected = connectionState == ConnectionState.CONNECTED
                val isConnecting = p2pStatus == P2PConnectionStatus.CONNECTING_TO_PEER ||
                        p2pStatus == P2PConnectionStatus.RECONNECTING ||
                        connectionState == ConnectionState.CONNECTING

                if (isConnected) {
                    Button(
                        onClick = { manager.disconnectP2P() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.logout),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.p2p_disconnect), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = {
                            val target = partnerIpInput.trim()
                            val cleanHost = target.removePrefix("ws://").removePrefix("wss://")
                                .removePrefix("http://").removePrefix("https://").substringBefore(":")
                            if (target.isBlank()) {
                                Toast.makeText(context, "Please enter partner Tailscale IP or Hostname", Toast.LENGTH_SHORT).show()
                            } else if (cleanHost in myIpsSet || cleanHost.startsWith("127.") || cleanHost.equals("localhost", ignoreCase = true)) {
                                Toast.makeText(context, "Cannot connect to self. Tap 'Host P2P Session' instead.", Toast.LENGTH_LONG).show()
                            } else {
                                manager.connectToPartner(target, deviceName)
                            }
                        },
                        enabled = !isConnecting,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.link),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.p2p_connect_partner), fontWeight = FontWeight.SemiBold)
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            manager.hostP2PSession(deviceName)
                        },
                        enabled = !isConnecting,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.radio),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.p2p_host_session), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
