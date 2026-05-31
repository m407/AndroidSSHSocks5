package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.SshProfile
import com.example.service.ProxyStatus
import com.example.service.SshProxyService
import com.example.ui.SshProxyViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Notification permission is recommended to show proxy status in background.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ask for standard Android 13+ Notification Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF070B13) // Cyber deep dark background
                ) {
                    val viewModel: SshProxyViewModel = viewModel()
                    ProxyAppContent(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyAppContent(viewModel: SshProxyViewModel) {
    val profiles by viewModel.profiles.collectAsState()
    val proxyStatus by viewModel.proxyStatus.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    var showFormDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<SshProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00FF66))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SECURE SOCKS5",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editingProfile = null
                            showFormDialog = true
                        },
                        modifier = Modifier
                            .testTag("add_profile_button")
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E2D43))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Profile",
                            tint = Color(0xFF00E5FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D1525)
                )
            )
        },
        containerColor = Color(0xFF070B13)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
        ) {
            // Live Status Card
            item {
                StatusCard(
                    status = proxyStatus,
                    activeProfile = activeProfile,
                    lastError = lastError,
                    onStopClick = { SshProxyService.stopProxy(it) }
                )
            }

            // Connection Configuration Panel header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SSH CONFIGURATIONS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF5A6E85),
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(Color(0xFF152238))
                    )
                }
            }

            // Profile count or Empty state
            if (profiles.isEmpty()) {
                item {
                    EmptyStateCard()
                }
            }

            // Configuration list items
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    isActive = activeProfile?.id == profile.id,
                    proxyStatus = if (activeProfile?.id == profile.id) proxyStatus else ProxyStatus.DISCONNECTED,
                    onToggle = { viewModel.toggleProxy(profile) },
                    onEdit = {
                        editingProfile = profile
                        showFormDialog = true
                    },
                    onDelete = { viewModel.deleteProfile(profile) }
                )
            }
        }
    }

    if (showFormDialog) {
        ProfileFormDialog(
            profile = editingProfile,
            onDismiss = { showFormDialog = false },
            onSave = { savedProfile ->
                viewModel.saveProfile(savedProfile)
                showFormDialog = false
            }
        )
    }
}

@Composable
fun StatusCard(
    status: ProxyStatus,
    activeProfile: SshProfile?,
    lastError: String?,
    onStopClick: (android.content.Context) -> Unit
) {
    val context = LocalContext.current

    val stateColor = when (status) {
        ProxyStatus.CONNECTED -> Color(0xFF00FF66)
        ProxyStatus.CONNECTING -> Color(0xFF00E5FF)
        ProxyStatus.ERROR -> Color(0xFFFF5252)
        ProxyStatus.DISCONNECTED -> Color(0xFF5A6E85)
    }

    val stateLabel = when (status) {
        ProxyStatus.CONNECTED -> "SECURE TUNNEL ACTIVE"
        ProxyStatus.CONNECTING -> "AUTHENTICATING & FORWARDING..."
        ProxyStatus.ERROR -> "CONNECTION FAILED"
        ProxyStatus.DISCONNECTED -> "PROXY SERVER SUSPENDED"
    }

    // Glowing animation for active state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseSize by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseWidth"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF172A45), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F1B2F)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Pulsing / glowing node indicators
                    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        if (status == ProxyStatus.CONNECTED || status == ProxyStatus.CONNECTING) {
                            Box(
                                modifier = Modifier
                                    .size((14f * pulseSize).dp)
                                    .clip(CircleShape)
                                    .background(stateColor.copy(alpha = 0.3f))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(stateColor)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stateLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = stateColor,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }

                if (status == ProxyStatus.CONNECTED || status == ProxyStatus.CONNECTING) {
                    Button(
                        onClick = { onStopClick(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF5252).copy(alpha = 0.2f),
                            contentColor = Color(0xFFFF5252)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("stop_proxy_action"),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop Proxy",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Disconnect", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (status) {
                ProxyStatus.CONNECTED -> {
                    if (activeProfile != null) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InstructionRow("Proxy Address IP", "127.0.0.1")
                            InstructionRow("Configured Port", "${activeProfile.socksPort}")
                            InstructionRow("Tunnel Server", "${activeProfile.username}@${activeProfile.host}:${activeProfile.port}")
                            InstructionRow("Encryption Protocol", "SSH-2.0 Secure Channel")

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .background(Color(0xFF070B13), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "GUIDE: TO ROUTE SYSTEM TRAFFIC",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF00E5FF),
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Please set your browser or SOCKS5 network proxy client on your device to connect locally via Loopback IP address 127.0.0.1 on Port ${activeProfile.socksPort}.",
                                        fontSize = 11.sp,
                                        color = Color(0xFFCAD4E0),
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
                ProxyStatus.CONNECTING -> {
                    if (activeProfile != null) {
                        Text(
                            text = "Establishing secure connection to remote SSH host ${activeProfile.username}@${activeProfile.host}:${activeProfile.port}...",
                            fontSize = 13.sp,
                            color = Color(0xFFCAD4E0),
                            lineHeight = 18.sp
                        )
                    }
                }
                ProxyStatus.ERROR -> {
                    Column {
                        Text(
                            text = "Last connection log info:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5252).copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF200B0B), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF5A1E1E), RoundedCornerShape(6.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = lastError ?: "Operation timed out without handshake receipt.",
                                fontSize = 12.sp,
                                color = Color(0xFFFFD1D1),
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
                ProxyStatus.DISCONNECTED -> {
                    Text(
                        text = "SSH tunneling proxy is sleeping. Select a secure login configuration below and click 'Establish Connect' to start traffic dynamic forwarding.",
                        fontSize = 13.sp,
                        color = Color(0xFFCAD4E0),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun InstructionRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFF8B9FB4),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun EmptyStateCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF152238), RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1424))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                tint = Color(0xFF1E3557),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "No SSH profiles added yet. Tap the top-right '+' to configure your first proxy endpoint.",
                fontSize = 13.sp,
                color = Color(0xFF8B9FB4),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun ProfileCard(
    profile: SshProfile,
    isActive: Boolean,
    proxyStatus: ProxyStatus,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val connectionStateBg = when {
        isActive && proxyStatus == ProxyStatus.CONNECTED -> Color(0xFF00FF66).copy(alpha = 0.05f)
        isActive && proxyStatus == ProxyStatus.CONNECTING -> Color(0xFF00E5FF).copy(alpha = 0.05f)
        else -> Color.Transparent
    }

    val actionButtonTint = when {
        isActive && proxyStatus == ProxyStatus.CONNECTED -> Color(0xFF00FF66)
        isActive && proxyStatus == ProxyStatus.CONNECTING -> Color(0xFF00E5FF)
        else -> Color(0xFFCAD4E0)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isActive) Color(0xFF00E5FF).copy(alpha = 0.4f) else Color(0xFF152238),
                RoundedCornerShape(8.dp)
            )
            .background(connectionStateBg, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0A1322)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = profile.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF12243A), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "SOCK: ${profile.socksPort}",
                            fontSize = 10.sp,
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Text(
                    text = "${profile.username}@${profile.host}:${profile.port}",
                    fontSize = 12.sp,
                    color = Color(0xFF8B9FB4),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (profile.authType == "PASSWORD") Icons.Default.Lock else Icons.Default.Info,
                        contentDescription = "Auth type",
                        tint = Color(0xFF5A6E85),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (profile.authType == "PASSWORD") "Secure Credentials login" else "RSA Keypair login",
                        fontSize = 11.sp,
                        color = Color(0xFF5A6E85)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Edit Button
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF131F33))
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Profile",
                        tint = Color(0xFF8B9FB4),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Delete Button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1F121C))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Profile",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Connection Toggle Play/Stop Action Button
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .testTag("profile_connect_${profile.id}")
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionButtonTint.copy(alpha = 0.15f))
                        .border(1.dp, actionButtonTint.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = if (isActive && (proxyStatus == ProxyStatus.CONNECTED || proxyStatus == ProxyStatus.CONNECTING)) {
                            Icons.Default.Close
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = "Connect Toggle",
                        tint = actionButtonTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileFormDialog(
    profile: SshProfile?,
    onDismiss: () -> Unit,
    onSave: (SshProfile) -> Unit
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var host by remember { mutableStateOf(profile?.host ?: "") }
    var port by remember { mutableStateOf(profile?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(profile?.username ?: "") }
    var authType by remember { mutableStateOf(profile?.authType ?: "PASSWORD") }
    var password by remember { mutableStateOf(profile?.password ?: "") }
    var privateKey by remember { mutableStateOf(profile?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(profile?.passphrase ?: "") }
    var socksPort by remember { mutableStateOf(profile?.socksPort?.toString() ?: "1080") }

    var passwordVisible by remember { mutableStateOf(false) }
    var passphraseVisible by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .border(1.dp, Color(0xFF1E2D43), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0F1726)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (profile == null) "NEW CONNECTION PROFILE" else "EDIT CONNECTION PROFILE",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )

                Divider(color = Color(0xFF152238))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name (e.g., London VPS)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF1C2C42),
                        focusedLabelColor = Color(0xFF00E5FF),
                        unfocusedLabelColor = Color(0xFF8B9FB4)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("SSH Host Name/IP") },
                        modifier = Modifier.weight(2f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1C2C42),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedLabelColor = Color(0xFF8B9FB4)
                        )
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1C2C42),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedLabelColor = Color(0xFF8B9FB4)
                        )
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (login)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF1C2C42),
                        focusedLabelColor = Color(0xFF00E5FF),
                        unfocusedLabelColor = Color(0xFF8B9FB4)
                    )
                )

                // Authentication Strategy selector
                Column {
                    Text(
                        text = "SSH AUTHENTICATION TYPE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8B9FB4),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { authType = "PASSWORD" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (authType == "PASSWORD") Color(0xFF1E2D43) else Color(0xFF070B13),
                                contentColor = if (authType == "PASSWORD") Color(0xFF00E5FF) else Color(0xFF8B9FB4)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (authType == "PASSWORD") Color(0xFF00E5FF) else Color(0xFF152238)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Password Credentials", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { authType = "KEY" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (authType == "KEY") Color(0xFF1E2D43) else Color(0xFF070B13),
                                contentColor = if (authType == "KEY") Color(0xFF00E5FF) else Color(0xFF8B9FB4)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (authType == "KEY") Color(0xFF00E5FF) else Color(0xFF152238)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Private Key", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (authType == "PASSWORD") {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("SSH Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(
                                    text = if (passwordVisible) "HIDE" else "SHOW",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1C2C42),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedLabelColor = Color(0xFF8B9FB4)
                        )
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("Paste RSA/ED25519 Private Key in PEM Format") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1C2C42),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedLabelColor = Color(0xFF8B9FB4)
                        )
                    )

                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Key Passphrase (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                Text(
                                    text = if (passphraseVisible) "HIDE" else "SHOW",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1C2C42),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedLabelColor = Color(0xFF8B9FB4)
                        )
                    )
                }

                // SOCKS5 configuration
                OutlinedTextField(
                    value = socksPort,
                    onValueChange = { socksPort = it },
                    label = { Text("SOCKS5 Proxy Bind Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF1C2C42),
                        focusedLabelColor = Color(0xFF00E5FF),
                        unfocusedLabelColor = Color(0xFF8B9FB4)
                    )
                )

                validationError?.let { error ->
                    Text(
                        text = error,
                        color = Color(0xFFFF5252),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Actions row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF8B9FB4)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF1E2D43))
                    ) {
                        Text("Cancel", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val pInt = port.toIntOrNull()
                            val sInt = socksPort.toIntOrNull()
                            validationError = when {
                                name.isBlank() -> "Profile name is required."
                                host.isBlank() -> "SSH host name or IP address is required."
                                username.isBlank() -> "Username is required."
                                pInt == null || pInt !in 1..65535 -> "SSH port must be between 1 and 65535."
                                sInt == null || sInt !in 1024..65535 -> "SOCKS5 bind port must be between 1024 and 65535."
                                authType == "PASSWORD" && password.isEmpty() -> "SSH password is required for password authentication."
                                authType == "KEY" && privateKey.isBlank() -> "Private key is required for key authentication."
                                else -> null
                            }
                            if (validationError != null) {
                                return@Button
                            }
                            onSave(
                                SshProfile(
                                    id = profile?.id ?: 0,
                                    name = name.trim(),
                                    host = host.trim(),
                                    port = pInt!!,
                                    username = username.trim(),
                                    authType = authType,
                                    password = password,
                                    privateKey = privateKey.trim(),
                                    passphrase = passphrase,
                                    socksPort = sInt!!
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_profile_action"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color(0xFF070B13)
                        )
                    ) {
                        Text("Save Profile", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
