package com.manacinema.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ShareCompat
import coil.compose.AsyncImage
import com.manacinema.app.localization.LocalAppStrings
import com.manacinema.app.models.CastMember
import com.manacinema.app.models.Plan

// ------------------ SHARE HELPER (P8 #24) ------------------

fun shareTitle(context: Context, title: String, poster: String) {
    val text = "Check out \"$title\" on Mana Cinema!" + if (poster.isNotEmpty()) "\n$poster" else ""
    val intent = ShareCompat.IntentBuilder(context)
        .setType("text/plain")
        .setChooserTitle("Share $title")
        .setText(text)
        .createChooserIntent()
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

// ------------------ CAST ROW (P3 #6) ------------------

@Composable
fun CastRow(cast: List<CastMember>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(cast, key = { it.name + it.photo }) { member ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp)
            ) {
                AsyncImage(
                    model = member.photo,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = member.name,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ------------------ RATING SECTION (P7 #20) ------------------

@Composable
fun RatingSection(viewModel: MovieViewModel, contentId: String) {
    var userScore by remember(contentId) { mutableStateOf(0) }

    LaunchedEffect(contentId) {
        viewModel.getUserRating(contentId) { userScore = it }
    }

    Column {
        Text("YOUR RATING", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row {
            (1..5).forEach { star ->
                Icon(
                    imageVector = if (star <= userScore) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Rate $star",
                    tint = if (star <= userScore) Color(0xFFFFC107) else Color.Gray,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            if (userScore != star) {
                                userScore = star
                                viewModel.setUserRating(contentId, star)
                            }
                        }
                )
            }
        }
    }
}

// ------------------ BANNED SCREEN (P3 #8) ------------------

@Composable
fun BannedScreen(onExit: () -> Unit) {
    val strings = LocalAppStrings.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Block, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(strings.accountSuspended, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                strings.suspendedMessage,
                color = Color.Gray,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onExit) { Text("Exit App") }
        }
    }
}

// ------------------ FORCE UPDATE DIALOG (P3 #13) ------------------

@Composable
fun ForceUpdateDialog(info: com.manacinema.app.models.AppVersionInfo) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { /* non-dismissible */ },
        title = { Text(strings.updateRequired) },
        text = {
            Column {
                Text("A new version (${info.versionName}) is required to continue.")
                if (info.changelog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(info.changelog, fontSize = 13.sp, color = Color.LightGray)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (info.apkUrl.isNotEmpty()) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.apkUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("ForceUpdate", "Cannot open apkUrl", e)
                    }
                }
            }) { Text(strings.updateNow) }
        }
    )
}

// ------------------ PLANS SCREEN (P3 #9) ------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(
    viewModel: MovieViewModel,
    onSelectPlan: (Plan) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val plansState by viewModel.plansUiState.collectAsState()
    val subscription by viewModel.subscription.collectAsState()
    val manaId by viewModel.manaId.collectAsState()
    val context = LocalContext.current
    var restoreId by remember { mutableStateOf("") }
    var restoreSubmitting by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        viewModel.startPlansLoading()
        onDispose { viewModel.stopPlansLoading() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(strings.premiumPlans) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = strings.back) }
            }
        )
        if (subscription.isActive) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Active: ${subscription.planName}", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Expires ${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(subscription.expiryDate))}",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }
        }
        Text(
            text = if (manaId.isBlank()) "Mana ID: loading..." else "Your Mana ID: $manaId",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = Color.LightGray,
            fontSize = 13.sp
        )
        OutlinedTextField(
            value = restoreId,
            onValueChange = { restoreId = it.uppercase().take(20) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Old Mana ID") },
            singleLine = true
        )
        Button(
            onClick = {
                restoreSubmitting = true
                viewModel.submitRestoreRequest(restoreId) { success ->
                    restoreSubmitting = false
                    android.widget.Toast.makeText(
                        context,
                        if (success) "Restore request submitted." else "Could not submit request. Check the Mana ID and connection.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            },
            enabled = !restoreSubmitting && restoreId.length in 4..20,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) { Text(if (restoreSubmitting) "Submitting..." else "Restore Premium") }
        when (val state = plansState) {
            MovieViewModel.PlansUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is MovieViewModel.PlansUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = viewModel::startPlansLoading) { Text("Retry") }
                }
            }
            is MovieViewModel.PlansUiState.Success -> {
                if (state.plans.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(strings.noPlansAvailable, color = Color.Gray)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.plans, key = { it.name }) { plan ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(plan.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("₹${plan.price}", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp, fontWeight = FontWeight.Black)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("${plan.days} days • ${plan.devices} device(s)", color = Color.LightGray, fontSize = 13.sp)
                                    if (plan.description.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(plan.description, color = Color.Gray, fontSize = 13.sp)
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { onSelectPlan(plan) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(strings.choosePlan) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------ PAYMENT SCREEN (P3 #9) ------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentScreen(
    viewModel: MovieViewModel,
    plan: Plan,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val payment by viewModel.paymentInfo.collectAsState()
    val context = LocalContext.current
    var utr by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(strings.completePayment) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = strings.back) }
            }
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("${plan.name} — ₹${plan.price}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${plan.days} days • ${plan.devices} device(s)", color = Color.Gray, fontSize = 13.sp)
            }
            item {
                if (payment.qrCodeUrl.isNotEmpty()) {
                    AsyncImage(
                        model = payment.qrCodeUrl,
                        contentDescription = "Payment QR",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }
            item {
                if (payment.upiId.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(strings.payToUpi, color = Color.Gray, fontSize = 12.sp)
                                Text(payment.upiId, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    strings.afterPayment,
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            }
            item {
                OutlinedTextField(
                    value = utr,
                    onValueChange = { utr = it },
                    label = { Text(strings.utrLabel) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = {
                        if (utr.isBlank()) {
                            android.widget.Toast.makeText(context, strings.enterUtr, android.widget.Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        submitting = true
                        viewModel.submitPremiumRequest(plan, utr.trim()) { success ->
                            submitting = false
                            android.widget.Toast.makeText(
                                context,
                                if (success) strings.requestSubmitted else strings.submissionFailed,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            if (success) onDone()
                        }
                    },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (submitting) strings.submitting else strings.submitPayment) }
            }
        }
    }
}
