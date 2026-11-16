package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.UserProfile
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.theme.PrimaryGreen
import com.example.util.MediaUtils
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    userProfile: UserProfile?,
    onBack: () -> Unit,
    onUpdateProfile: (displayName: String, about: String, phone: String?, photoBase64: String?) -> Unit,
    onToggleOnline: (Boolean) -> Unit,
    onOpenPrivacySettings: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pulseColors = LocalPulseChatColors.current

    var showEditDialog by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val base64 = MediaUtils.uriToCompressedBase64(context, uri, maxDim = 420)
                if (base64 != null) {
                    onUpdateProfile(
                        userProfile?.displayName ?: "PulseChat User",
                        userProfile?.about ?: "Available on PulseChat",
                        null,
                        base64
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Surface(color = pulseColors.headerBackground, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("profile_back_button")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = pulseColors.headerContent
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Profile",
                    style = MaterialTheme.typography.titleLarge,
                    color = pulseColors.headerContent,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.testTag("edit_profile_button")
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit profile",
                        tint = pulseColors.headerContent
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.clickable {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    UserAvatar(
                        name = userProfile?.displayName ?: "PulseChat User",
                        colorHex = userProfile?.avatarColorHex ?: "#128C7E",
                        photoBase64 = userProfile?.photoBase64 ?: "",
                        size = 116.dp
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(PrimaryGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Change photo",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column {
                        ProfileDetailRow(
                            icon = Icons.Default.Person,
                            label = "Display Name",
                            value = userProfile?.displayName ?: "PulseChat User",
                            helper = "This name is visible to your PulseChat contacts.",
                            onClick = { showEditDialog = true }
                        )
                        HorizontalDivider(color = pulseColors.divider)
                        ProfileDetailRow(
                            icon = Icons.Default.Info,
                            label = "About",
                            value = userProfile?.about ?: "Available on PulseChat",
                            onClick = { showEditDialog = true }
                        )
                        HorizontalDivider(color = pulseColors.divider)
                        ProfileDetailRow(
                            icon = Icons.Default.Phone,
                            label = "Phone Number",
                            value = userProfile?.phoneNumber?.ifBlank { "Not verified yet" } ?: "Not verified yet",
                            onClick = { showEditDialog = true }
                        )
                        HorizontalDivider(color = pulseColors.divider)
                        ProfileDetailRow(
                            icon = Icons.Default.Email,
                            label = "Google Account",
                            value = userProfile?.email?.ifBlank { "Signed in" } ?: "Signed in",
                            onClick = {}
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Show Online Status", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Let contacts see when you're active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pulseColors.bubbleSubtext
                                )
                            }
                            Switch(
                                checked = userProfile?.isOnline ?: true,
                                onCheckedChange = onToggleOnline
                            )
                        }
                        HorizontalDivider(color = pulseColors.divider)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPrivacySettings() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Privacy Settings", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Manage last seen, read receipts, and status audience",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pulseColors.bubbleSubtext
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        var nameInput by remember { mutableStateOf(userProfile?.displayName ?: "") }
        var aboutInput by remember { mutableStateOf(userProfile?.about ?: "") }
        var phoneInput by remember { mutableStateOf(userProfile?.phoneNumber ?: "") }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(80) },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_name_input")
                    )
                    OutlinedTextField(
                        value = aboutInput,
                        onValueChange = { aboutInput = it.take(200) },
                        label = { Text("About") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_about_input")
                    )
                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it.take(30) },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateProfile(nameInput, aboutInput, phoneInput, null)
                        showEditDialog = false
                    },
                    modifier = Modifier.testTag("save_profile_button")
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProfileDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    helper: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (helper != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
