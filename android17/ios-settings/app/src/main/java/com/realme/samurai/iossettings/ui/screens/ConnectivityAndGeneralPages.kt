package com.realme.samurai.iossettings.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.realme.samurai.iossettings.data.SystemSettingsController
import com.realme.samurai.iossettings.data.WifiNetworkItem
import com.realme.samurai.iossettings.ui.components.IosGroupedSection
import com.realme.samurai.iossettings.ui.components.IosHeroPlacardCard
import com.realme.samurai.iossettings.ui.components.IosSettingsRow
import com.realme.samurai.iossettings.ui.components.IosSubPageScaffold
import com.realme.samurai.iossettings.ui.icons.AccountDottedHaloAvatar
import com.realme.samurai.iossettings.ui.icons.InfoCircleIcon
import com.realme.samurai.iossettings.ui.icons.LockFillIcon
import com.realme.samurai.iossettings.ui.icons.SettingsIcon
import com.realme.samurai.iossettings.ui.icons.WifiSignalIcon
import com.realme.samurai.iossettings.ui.theme.ContinuousSquircleShape
import com.realme.samurai.iossettings.ui.theme.IosBlueLight
import com.realme.samurai.iossettings.ui.theme.IosGreenLight
import com.realme.samurai.iossettings.ui.theme.IosOrange
import com.realme.samurai.iossettings.ui.theme.IosPurple
import com.realme.samurai.iossettings.ui.theme.IosTheme
import com.realme.samurai.iossettings.ui.theme.IosType
import java.util.Locale

@Composable
fun AccountSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors

    IosSubPageScaffold(title = "Google Account", alwaysShowTitle = true, onBack = onBack) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AccountDottedHaloAvatar(size = 84.dp, signedInInitial = state.accountName?.take(2))
                Spacer(Modifier.height(14.dp))
                Text(
                    text = state.accountName ?: "Google Account",
                    style = IosType.title2,
                    color = colors.primaryText,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.accountEmail ?: "Sign in to sync Contacts, Backup, Play Store, and Find My Device.",
                    style = IosType.subheadline,
                    color = colors.secondaryText,
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = if (state.accountEmail == null) "Sign In with Google" else "Add Another Account",
                    titleColor = colors.accent,
                    showDivider = true,
                    onClick = controller::launchAddGoogleAccount,
                )
                IosSettingsRow(
                    title = "Play Store",
                    showChevron = true,
                    showDivider = true,
                    onClick = { controller.launchApp("com.android.vending") },
                )
                IosSettingsRow(
                    title = "Google Backup & Cloud Sync",
                    value = if (state.accountEmail != null) "On" else "Not Signed In",
                    showDivider = false,
                )
            }
        }
    }
}

/**
 * Exact 1:1 reproduction of `wave5_03e_wifi.png` + interactive iOS 26 WPA2/WPA3 Password Join Sheet!
 */
@Composable
fun WifiSettingsScreen(
    controller: SystemSettingsController,
    onNavigate: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
    initialPromptNetwork: WifiNetworkItem? = null,
) {
    val state = controller.state
    val colors = IosTheme.colors
    var promptNetwork by remember { mutableStateOf(initialPromptNetwork) }
    var promptOtherNetwork by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller.triggerWifiScan()
    }

    val connectedNet = state.wifiNetworks.firstOrNull { it.connected || it.ssid == state.connectedWifiSsid }
    val otherNets = state.wifiNetworks.filter { it.ssid != connectedNet?.ssid }

    IosSubPageScaffold(title = "Wi-Fi", alwaysShowTitle = false, onBack = onBack) {
        item(key = "wifi_placard") {
            IosHeroPlacardCard(
                icon = SettingsIcon.WIFI,
                title = "Wi-Fi",
                description = "Connect to Wi-Fi, view available networks, and manage settings for joining networks and nearby hotspots.",
                learnMoreText = "Learn more...",
                onLearnMoreClick = { controller.showBanner("Wi-Fi uses WPA2/WPA3 encryption & randomized MAC") },
                switchLabel = "Wi-Fi",
                switchChecked = state.wifiEnabled,
                onSwitchChange = controller::setWifiEnabled,
            )
            Spacer(Modifier.height(26.dp))
        }

        if (state.wifiEnabled) {
            if (connectedNet != null) {
                item(key = "wifi_current") {
                    IosGroupedSection {
                        WifiNetworkRow(
                            net = connectedNet.copy(connected = true),
                            showDivider = false,
                            onRowClick = { onNavigate(SettingsRoute.WifiNetworkDetail) },
                            onInfoClick = { onNavigate(SettingsRoute.WifiNetworkDetail) },
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }

            item(key = "wifi_networks") {
                IosGroupedSection(header = "Networks") {
                    otherNets.forEach { net ->
                        WifiNetworkRow(
                            net = net,
                            showDivider = true,
                            onRowClick = {
                                if (net.secured) {
                                    promptNetwork = net
                                } else {
                                    controller.connectToWifi(net.ssid, null, secured = false)
                                }
                            },
                            onInfoClick = { onNavigate(SettingsRoute.WifiNetworkDetail) },
                        )
                    }
                    IosSettingsRow(
                        title = "Other...",
                        leadingCheckmarkGutter = true,
                        leadingChecked = false,
                        showDivider = false,
                        onClick = { promptOtherNetwork = true },
                    )
                }
                Spacer(Modifier.height(26.dp))
            }

            item(key = "wifi_ask_join") {
                IosGroupedSection(
                    footer = "Known networks will be joined automatically. If no known networks are available, you will be notified of available networks.",
                ) {
                    IosSettingsRow(
                        title = "Ask to Join Networks",
                        value = state.askToJoinNetworks,
                        showChevron = true,
                        showDivider = false,
                        onClick = controller::cycleAskToJoinNetworks,
                    )
                }
            }
        } else {
            item(key = "wifi_off_footer") {
                Text(
                    text = "AirDrop, Personal Hotspot, and high-accuracy location require Wi-Fi.",
                    style = IosType.footnote,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }

    val activePrompt = promptNetwork
    if (activePrompt != null) {
        WifiPasswordJoinDialog(
            ssid = activePrompt.ssid,
            onDismiss = { promptNetwork = null },
            onJoin = { pwd ->
                controller.connectToWifi(activePrompt.ssid, pwd, secured = true)
                promptNetwork = null
            },
        )
    }

    if (promptOtherNetwork) {
        WifiOtherNetworkDialog(
            onDismiss = { promptOtherNetwork = false },
            onJoin = { ssid, pwd ->
                controller.connectToWifi(ssid, pwd, secured = pwd.isNotEmpty())
                promptOtherNetwork = false
            },
        )
    }
}

@Composable
private fun WifiNetworkRow(
    net: WifiNetworkItem,
    showDivider: Boolean,
    onRowClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val colors = IosTheme.colors
    IosSettingsRow(
        title = net.ssid,
        leadingCheckmarkGutter = true,
        leadingChecked = net.connected,
        showDivider = showDivider,
        onClick = onRowClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (net.secured) {
                    LockFillIcon(color = colors.primaryText, size = 13.dp)
                }
                WifiSignalIcon(bars = net.bars, color = colors.primaryText, size = 16.dp)
                InfoCircleIcon(
                    color = colors.accent,
                    size = 22.dp,
                    modifier = Modifier.clickable(onClick = onInfoClick),
                )
            }
        },
    )
}

@Composable
private fun WifiPasswordJoinDialog(
    ssid: String,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    val colors = IosTheme.colors
    var password by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousSquircleShape(24.dp))
                .background(colors.groupedCell)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cancel",
                    style = IosType.body,
                    color = colors.accent,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
                Text(
                    text = "Enter Password",
                    style = IosType.headline,
                    color = colors.primaryText,
                )
                Text(
                    text = "Join",
                    style = IosType.headline,
                    color = if (password.length >= 8) colors.accent else colors.tertiaryText,
                    modifier = Modifier.clickable(enabled = password.length >= 8) { onJoin(password) },
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Enter the password for \"$ssid\"",
                style = IosType.footnote,
                color = colors.secondaryText,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousSquircleShape(14.dp))
                    .background(colors.fieldBackground)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Password", style = IosType.body, color = colors.primaryText)
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = IosType.body.copy(color = colors.primaryText),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WifiOtherNetworkDialog(
    onDismiss: () -> Unit,
    onJoin: (String, String) -> Unit,
) {
    val colors = IosTheme.colors
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousSquircleShape(24.dp))
                .background(colors.groupedCell)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cancel", style = IosType.body, color = colors.accent, modifier = Modifier.clickable(onClick = onDismiss))
                Text("Other Network", style = IosType.headline, color = colors.primaryText)
                Text(
                    "Join",
                    style = IosType.headline,
                    color = if (ssid.isNotBlank()) colors.accent else colors.tertiaryText,
                    modifier = Modifier.clickable(enabled = ssid.isNotBlank()) { onJoin(ssid.trim(), password) },
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousSquircleShape(14.dp))
                    .background(colors.fieldBackground)
                    .padding(horizontal = 14.dp),
            ) {
                Row(modifier = Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Name", style = IosType.body, color = colors.primaryText, modifier = Modifier.width(84.dp))
                    BasicTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        singleLine = true,
                        textStyle = IosType.body.copy(color = colors.primaryText),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
                Row(modifier = Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Password", style = IosType.body, color = colors.primaryText, modifier = Modifier.width(84.dp))
                    BasicTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = IosType.body.copy(color = colors.primaryText),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun WifiNetworkDetailScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors
    var autoJoin by remember { mutableStateOf(true) }
    var lowDataMode by remember { mutableStateOf(false) }

    IosSubPageScaffold(
        title = state.connectedWifiSsid ?: "Network Details",
        alwaysShowTitle = true,
        onBack = onBack,
    ) {
        if (state.connectedWifiSsid != null) {
            item {
                IosGroupedSection {
                    IosSettingsRow(
                        title = "Forget This Network",
                        titleColor = colors.destructive,
                        showDivider = false,
                        onClick = {
                            controller.forgetCurrentWifi()
                            onBack()
                        },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Auto-Join",
                    checked = autoJoin,
                    onCheckedChange = { autoJoin = it },
                )
                IosSettingsRow(
                    title = "Low Data Mode",
                    checked = lowDataMode,
                    onCheckedChange = { lowDataMode = it },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "IPv4 Address") {
                IosSettingsRow(title = "Configure IP", value = "Automatic")
                IosSettingsRow(title = "IP Address", value = state.wifiIpAddress)
                IosSettingsRow(title = "Subnet Mask", value = "255.255.255.0")
                IosSettingsRow(title = "Router", value = state.wifiRouter)
                IosSettingsRow(title = "Link Speed", value = "${state.wifiLinkSpeedMbps} Mbps", showDivider = false)
            }
        }
    }
}

@Composable
fun BluetoothSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors

    LaunchedEffect(Unit) {
        controller.refreshBluetooth()
        if (state.bluetoothEnabled) controller.startBluetoothDiscovery()
    }

    val bonded = state.bluetoothDevices.filter { it.bonded }
    val discovered = state.bluetoothDevices.filter { !it.bonded }

    IosSubPageScaffold(title = "Bluetooth", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.BLUETOOTH,
                title = "Bluetooth",
                description = "Connect to accessories you can use for activities such as streaming music, making phone calls, and gaming.",
                learnMoreText = "Learn more...",
                switchLabel = "Bluetooth",
                switchChecked = state.bluetoothEnabled,
                onSwitchChange = controller::setBluetoothEnabled,
            )
            if (state.bluetoothEnabled) {
                Text(
                    text = "This phone is discoverable as \"${state.bluetoothDeviceName}\" while Bluetooth Settings is open.",
                    style = IosType.footnote,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (state.bluetoothEnabled) {
            item {
                IosGroupedSection(header = "My Devices") {
                    if (bonded.isEmpty()) {
                        IosSettingsRow(
                            title = "No Paired Accessories",
                            titleColor = colors.secondaryText,
                            showDivider = false,
                        )
                    } else {
                        bonded.forEachIndexed { idx, dev ->
                            IosSettingsRow(
                                title = dev.name,
                                value = if (dev.connected) "Connected" else "Not Connected",
                                showDivider = idx < bonded.lastIndex,
                                onClick = { controller.pairOrToggleBluetoothDevice(dev) },
                                trailing = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = if (dev.connected) "Connected" else "Not Connected",
                                            style = IosType.body,
                                            color = colors.secondaryText,
                                        )
                                        InfoCircleIcon(color = colors.accent, size = 22.dp)
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            item {
                IosGroupedSection(
                    header = if (state.bluetoothScanning) "Other Devices • Scanning..." else "Other Devices",
                ) {
                    if (discovered.isEmpty()) {
                        IosSettingsRow(
                            title = "Scan for Nearby Devices",
                            titleColor = colors.accent,
                            showDivider = false,
                            onClick = controller::startBluetoothDiscovery,
                        )
                    } else {
                        discovered.forEachIndexed { idx, dev ->
                            IosSettingsRow(
                                title = dev.name,
                                value = "Pair",
                                showDivider = idx < discovered.lastIndex,
                                onClick = { controller.pairOrToggleBluetoothDevice(dev) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CellularSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state

    IosSubPageScaffold(title = "Cellular", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.CELLULAR,
                title = "Cellular",
                description = "Find out how much data you're using, set data restrictions, and manage carrier settings such as Dual SIM and VoLTE.",
                learnMoreText = "Learn more...",
                switchLabel = "Cellular Data",
                switchChecked = state.cellularDataEnabled,
                onSwitchChange = controller::setCellularDataEnabled,
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(header = "Cellular Data Options") {
                IosSettingsRow(
                    title = "Voice & Data",
                    value = state.preferredNetworkLabel,
                    checked = state.volteEnabled,
                    onCheckedChange = controller::setVolteEnabled,
                )
                IosSettingsRow(
                    title = "Data Roaming",
                    checked = state.dataRoamingEnabled,
                    onCheckedChange = controller::setDataRoamingEnabled,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "SIMs") {
                if (state.simCards.isEmpty()) {
                    IosSettingsRow(
                        title = "No SIM Inserted",
                        value = "Dual Nano-SIM",
                        showDivider = false,
                    )
                } else {
                    state.simCards.forEachIndexed { idx, sim ->
                        IosSettingsRow(
                            title = sim.carrierName,
                            subtitle = "SIM ${sim.slotIndex + 1} • ${sim.number}",
                            value = if (sim.active) "On" else "Off",
                            showChevron = true,
                            showDivider = idx < state.simCards.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HotspotSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors
    var editingPassword by remember { mutableStateOf(false) }
    var tempPass by remember(state.hotspotPassword) { mutableStateOf(state.hotspotPassword) }
    var maxCompat by remember { mutableStateOf(false) }

    IosSubPageScaffold(title = "Personal Hotspot", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.HOTSPOT,
                title = "Personal Hotspot",
                description = "Personal Hotspot on your phone can provide internet access to other devices signed in to your account or using your Wi-Fi password.",
                learnMoreText = "Learn more...",
                switchLabel = "Allow Others to Join",
                switchChecked = state.hotspotEnabled,
                onSwitchChange = controller::setHotspotEnabled,
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection(
                footer = "Other users can look for your shared network \"${state.hotspotSsid}\" using Wi-Fi.",
            ) {
                IosSettingsRow(
                    title = "Wi-Fi Password",
                    value = state.hotspotPassword,
                    showChevron = true,
                    showDivider = false,
                    onClick = { editingPassword = true },
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(
                footer = "Internet performance may be reduced for devices connected to your hotspot when turned on.",
            ) {
                IosSettingsRow(
                    title = "Maximize Compatibility",
                    checked = maxCompat,
                    onCheckedChange = { maxCompat = it },
                    showDivider = false,
                )
            }
        }
    }

    if (editingPassword) {
        Dialog(onDismissRequest = { editingPassword = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousSquircleShape(24.dp))
                    .background(colors.groupedCell)
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Cancel", style = IosType.body, color = colors.accent, modifier = Modifier.clickable { editingPassword = false })
                    Text("Wi-Fi Password", style = IosType.headline, color = colors.primaryText)
                    Text(
                        "Done",
                        style = IosType.headline,
                        color = if (tempPass.length >= 8) colors.accent else colors.tertiaryText,
                        modifier = Modifier.clickable(enabled = tempPass.length >= 8) {
                            controller.updateHotspotPassword(tempPass)
                            editingPassword = false
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousSquircleShape(14.dp))
                        .background(colors.fieldBackground)
                        .padding(14.dp),
                ) {
                    BasicTextField(
                        value = tempPass,
                        onValueChange = { tempPass = it },
                        singleLine = true,
                        textStyle = IosType.body.copy(color = colors.primaryText),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun BatterySettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors

    IosSubPageScaffold(title = "Battery", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(header = "Current Charge") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "${state.batteryLevel}%",
                            style = IosType.largeTitle,
                            color = colors.primaryText,
                        )
                        Text(
                            text = if (state.batteryCharging) "SuperVOOC 50W Charging" else "4000 mAh Dual-Cell",
                            style = IosType.subheadline,
                            color = if (state.batteryCharging) IosGreenLight else colors.secondaryText,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(CircleShape)
                            .background(colors.fieldBackground),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((state.batteryLevel / 100f).coerceIn(0.04f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(if (state.batteryLowPowerMode) IosOrange else IosGreenLight),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = String.format(Locale.US, "Battery Temperature: %.1f°C", state.batteryTempCelsius),
                        style = IosType.footnote,
                        color = colors.secondaryText,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(
                footer = "Low Power Mode temporarily reduces background activity like downloads and mail fetch until you can fully charge your phone.",
            ) {
                IosSettingsRow(
                    title = "Battery Percentage",
                    checked = state.showBatteryPercentage,
                    onCheckedChange = controller::setShowBatteryPercentage,
                )
                IosSettingsRow(
                    title = "Low Power Mode",
                    checked = state.batteryLowPowerMode,
                    onCheckedChange = controller::setLowPowerMode,
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(
                header = "Battery Health & Charging",
                footer = "80% Charge Limit reduces chemical aging of the dual-cell SuperVOOC battery.",
            ) {
                IosSettingsRow(
                    title = "Battery Health",
                    value = state.batteryHealthLabel,
                )
                IosSettingsRow(
                    title = "80% Charge Limit",
                    checked = state.chargeLimit80,
                    onCheckedChange = controller::setChargeLimit80,
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun GeneralSettingsScreen(
    controller: SystemSettingsController,
    onNavigate: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
) {
    IosSubPageScaffold(title = "General", alwaysShowTitle = false, onBack = onBack) {
        item {
            IosHeroPlacardCard(
                icon = SettingsIcon.GENERAL,
                title = "General",
                description = "Manage your overall setup and preferences for software updates, device storage, language, and system configuration.",
                learnMoreText = "",
            )
            Spacer(Modifier.height(26.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "About",
                    icon = SettingsIcon.GENERAL,
                    showChevron = true,
                    onClick = { onNavigate(SettingsRoute.About) },
                )
                IosSettingsRow(
                    title = "Software Update",
                    icon = SettingsIcon.GENERAL,
                    showChevron = true,
                    onClick = { onNavigate(SettingsRoute.SoftwareUpdate) },
                )
                IosSettingsRow(
                    title = "Phone Storage",
                    icon = SettingsIcon.GENERAL,
                    showChevron = true,
                    showDivider = false,
                    onClick = { onNavigate(SettingsRoute.Storage) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Date & Time",
                    icon = SettingsIcon.GENERAL,
                    showChevron = true,
                    onClick = { onNavigate(SettingsRoute.DateTime) },
                )
                IosSettingsRow(
                    title = "Language & Region",
                    icon = SettingsIcon.GENERAL,
                    showChevron = true,
                    showDivider = false,
                    onClick = { onNavigate(SettingsRoute.LanguageRegion) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Transfer or Reset Phone",
                    showChevron = true,
                    showDivider = false,
                    onClick = { onNavigate(SettingsRoute.Reset) },
                )
            }
        }
    }
}

@Composable
fun AboutSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors
    var editingName by remember { mutableStateOf(false) }
    var tempName by remember(state.deviceName) { mutableStateOf(state.deviceName) }

    IosSubPageScaffold(title = "About", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Name",
                    value = state.deviceName,
                    showChevron = true,
                    onClick = { editingName = true },
                )
                IosSettingsRow(
                    title = "Samurai iOS Version",
                    value = state.osVersionLabel,
                )
                IosSettingsRow(
                    title = "Android Base",
                    value = state.androidVersionLabel,
                )
                IosSettingsRow(
                    title = "Model Name",
                    value = state.modelName,
                )
                IosSettingsRow(
                    title = "Model Number",
                    value = state.modelNumber,
                )
                IosSettingsRow(
                    title = "Build Number",
                    value = state.buildNumber,
                    showDivider = false,
                    onClick = controller::onTapBuildNumber,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Hardware") {
                IosSettingsRow(title = "Chipset", value = "Snapdragon 855+ (SM8150)")
                IosSettingsRow(title = "Memory", value = state.totalRamGb)
                IosSettingsRow(title = "Display", value = "6.5\" Super AMOLED 90Hz")
                IosSettingsRow(title = "Capacity", value = "${state.totalStorageGb.toInt()} GB UFS 3.0")
                IosSettingsRow(title = "Kernel", value = state.kernelVersion, showDivider = false)
            }
        }
    }

    if (editingName) {
        Dialog(onDismissRequest = { editingName = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousSquircleShape(24.dp))
                    .background(colors.groupedCell)
                    .padding(20.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cancel", style = IosType.body, color = colors.accent, modifier = Modifier.clickable { editingName = false })
                    Text("Name", style = IosType.headline, color = colors.primaryText)
                    Text(
                        "Done",
                        style = IosType.headline,
                        color = colors.accent,
                        modifier = Modifier.clickable {
                            controller.updateDeviceName(tempName)
                            editingName = false
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousSquircleShape(14.dp))
                        .background(colors.fieldBackground)
                        .padding(14.dp),
                ) {
                    BasicTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        singleLine = true,
                        textStyle = IosType.body.copy(color = colors.primaryText),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun SoftwareUpdateScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors
    var autoInstall by remember { mutableStateOf(true) }

    IosSubPageScaffold(title = "Software Update", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "Automatic Updates",
                    value = if (autoInstall) "On" else "Off",
                    checked = autoInstall,
                    onCheckedChange = { autoInstall = it },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(36.dp))
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Samurai OS ${state.osVersionLabel}",
                    style = IosType.title3,
                    color = colors.primaryText,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Samurai OS is up to date",
                    style = IosType.subheadline,
                    color = colors.secondaryText,
                )
                Spacer(Modifier.height(24.dp))
            }
            IosGroupedSection {
                IosSettingsRow(
                    title = "Check for OTA Updates",
                    titleColor = colors.accent,
                    showChevron = true,
                    showDivider = false,
                    onClick = controller::openOtaUpdater,
                )
            }
        }
    }
}

@Composable
fun StorageSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    val colors = IosTheme.colors
    val total = state.totalStorageGb.coerceAtLeast(1f)
    val used = state.usedStorageGb.coerceIn(1f, total)
    val appsFrac = (used * 0.46f / total).coerceIn(0.04f, 0.55f)
    val mediaFrac = (used * 0.24f / total).coerceIn(0.03f, 0.30f)
    val osFrac = (used * 0.30f / total).coerceIn(0.04f, 0.35f)

    IosSubPageScaffold(title = "Phone Storage", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Realme X2 Pro", style = IosType.headline, color = colors.primaryText)
                        Text(
                            String.format(Locale.US, "%.1f GB of %.0f GB Used", used, total),
                            style = IosType.subheadline,
                            color = colors.secondaryText,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .clip(ContinuousSquircleShape(5.dp))
                            .background(colors.fieldBackground),
                    ) {
                        Box(Modifier.fillMaxWidth(appsFrac).fillMaxHeight().background(Color(0xFFFF3B30)))
                        Spacer(Modifier.width(1.5.dp))
                        Box(Modifier.fillMaxWidth(mediaFrac / (1f - appsFrac).coerceAtLeast(0.1f)).fillMaxHeight().background(IosBlueLight))
                        Spacer(Modifier.width(1.5.dp))
                        Box(Modifier.fillMaxWidth(osFrac / (1f - appsFrac - mediaFrac).coerceAtLeast(0.1f)).fillMaxHeight().background(IosPurple))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StorageLegendDot("Apps", Color(0xFFFF3B30))
                        StorageLegendDot("Media", IosBlueLight)
                        StorageLegendDot("Samurai OS", IosPurple)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        item {
            IosGroupedSection(header = "Installed Applications") {
                state.installedApps.take(12).forEachIndexed { idx, app ->
                    IosSettingsRow(
                        title = app.label,
                        value = "v${app.versionName}",
                        showDivider = idx < state.installedApps.take(12).lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageLegendDot(label: String, color: Color) {
    val colors = IosTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = IosType.caption1, color = colors.secondaryText)
    }
}

@Composable
fun DateTimeSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    IosSubPageScaffold(title = "Date & Time", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection {
                IosSettingsRow(
                    title = "24-Hour Time",
                    checked = state.use24HourTime,
                    onCheckedChange = controller::setUse24HourTime,
                )
                IosSettingsRow(
                    title = "Set Automatically",
                    checked = state.autoDateTime,
                    onCheckedChange = controller::setAutoDateTime,
                )
                IosSettingsRow(
                    title = "Time Zone",
                    value = state.timeZoneLabel,
                    showDivider = false,
                )
            }
        }
    }
}

@Composable
fun LanguageRegionScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val state = controller.state
    IosSubPageScaffold(title = "Language & Region", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(header = "Preferred Languages") {
                IosSettingsRow(title = state.languageLabel, value = "Primary", showDivider = false)
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            IosGroupedSection {
                IosSettingsRow(title = "Calendar", value = "Gregorian")
                IosSettingsRow(title = "Temperature", value = "°C", showDivider = false)
            }
        }
    }
}

@Composable
fun ResetSettingsScreen(
    controller: SystemSettingsController,
    onBack: () -> Unit,
) {
    val colors = IosTheme.colors
    IosSubPageScaffold(title = "Transfer or Reset Phone", alwaysShowTitle = true, onBack = onBack) {
        item {
            IosGroupedSection(
                footer = "Reset Liquid Glass optical settings and display defaults without erasing user data.",
            ) {
                IosSettingsRow(
                    title = "Reset Liquid Glass & Display Defaults",
                    titleColor = colors.accent,
                    onClick = {
                        controller.setLiquidGlassMode(0)
                        controller.setRefractionPct(20)
                        controller.setFrostPct(20)
                        controller.showBanner("Liquid Glass reset to calibrated defaults")
                    },
                )
                IosSettingsRow(
                    title = "Reset Wi-Fi & Network Suggestions",
                    titleColor = colors.destructive,
                    showDivider = false,
                    onClick = { controller.showBanner("Network suggestions cleared") },
                )
            }
        }
    }
}
