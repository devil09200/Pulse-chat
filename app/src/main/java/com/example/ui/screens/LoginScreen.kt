package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import coil.compose.AsyncImage
import com.example.R
import com.example.auth.onGoogleSignInClicked
import com.example.ui.theme.LocalPulseChatColors
import com.example.ui.theme.PrimaryGreen

data class CountryOption(
    val name: String,
    val code: String,
    val flag: String
)

private val COUNTRIES = listOf(
    CountryOption("United States", "+1", "US"),
    CountryOption("India", "+91", "IN"),
    CountryOption("United Kingdom", "+44", "UK"),
    CountryOption("Canada", "+1", "CA"),
    CountryOption("Germany", "+49", "DE"),
    CountryOption("Australia", "+61", "AU"),
    CountryOption("Japan", "+81", "JP")
)

@Composable
fun LoginScreen(
    isSignedInNeedingPhone: Boolean = false,
    onGoogleAuthSuccess: (phoneNumber: String, countryCode: String) -> Unit,
    onPhoneOtpVerified: (phoneNumber: String, countryCode: String) -> Unit,
    onSkipPhone: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var selectedCountry by remember { mutableStateOf(COUNTRIES.first()) }
    var countryExpanded by remember { mutableStateOf(false) }
    var phoneNumber by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showTermsDialog by remember { mutableStateOf(false) }

    val pulseColors = LocalPulseChatColors.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(pulseColors.headerBackground)
                    .windowInsetsTopHeight(WindowInsets.statusBars)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_pulsechat_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                AsyncImage(
                    model = R.drawable.img_welcome_hero,
                    contentDescription = "PulseChat messaging illustration",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(164.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = if (isSignedInNeedingPhone) "Link your phone number" else stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isSignedInNeedingPhone) {
                        "Save your mobile number so your PulseChat contacts can find and message you."
                    } else {
                        stringResource(R.string.welcome_subtitle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Country Selector
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = "${selectedCountry.flag}  ${selectedCountry.name} (${selectedCountry.code})",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Country") },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Select country"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("country_selector")
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { countryExpanded = true }
                            )
                            DropdownMenu(
                                expanded = countryExpanded,
                                onDismissRequest = { countryExpanded = false }
                            ) {
                                COUNTRIES.forEach { country ->
                                    DropdownMenuItem(
                                        text = { Text("${country.flag}  ${country.name} (${country.code})") },
                                        onClick = {
                                            selectedCountry = country
                                            countryExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Phone Input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = selectedCountry.code,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Code") },
                                modifier = Modifier.width(88.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = { input ->
                                    phoneNumber = input.filter { it.isDigit() || it == ' ' || it == '-' }.take(16)
                                    errorMessage = null
                                },
                                label = { Text("Phone number") },
                                placeholder = { Text("555 019 2834") },
                                leadingIcon = {
                                    Icon(Icons.Default.Phone, contentDescription = null)
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("phone_number_input")
                            )
                        }

                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isSignedInNeedingPhone) {
                    Button(
                        onClick = {
                            val digits = phoneNumber.filter { it.isDigit() }
                            if (digits.length < 6) {
                                errorMessage = "Enter a valid phone number (at least 6 digits)."
                                return@Button
                            }
                            onPhoneOtpVerified("${selectedCountry.code} ${phoneNumber.trim()}", selectedCountry.code)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("continue_button"),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryGreen)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save & Continue",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onSkipPhone,
                        modifier = Modifier.testTag("skip_phone_button")
                    ) {
                        Text("Skip for now")
                    }
                } else {
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            onGoogleSignInClicked(
                                context = context,
                                credentialManager = credentialManager,
                                onAuthSuccess = {
                                    isLoading = false
                                    onGoogleAuthSuccess(phoneNumber.trim(), selectedCountry.code)
                                },
                                onAuthError = { msg ->
                                    isLoading = false
                                    errorMessage = msg
                                },
                                scope = scope,
                                onAuthCancelled = {
                                    isLoading = false
                                }
                            )
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("google_sign_in_button"),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryGreen,
                            contentColor = Color.White
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.4.dp
                            )
                        } else {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.sign_in_google),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextButton(
                    onClick = { showTermsDialog = true },
                    modifier = Modifier.testTag("terms_privacy_button")
                ) {
                    Text(
                        text = stringResource(R.string.terms_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        }
    }

    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            title = { Text("Terms & Privacy Policy") },
            text = {
                Text(
                    "PulseChat stores your messages, status updates, communities, and call records securely in Cloud Firestore scoped to your authenticated Google account. Your phone number and email are isolated to your private user document and never exposed in public directory cards."
                )
            },
            confirmButton = {
                TextButton(onClick = { showTermsDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }
}
