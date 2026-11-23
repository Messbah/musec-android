package com.musec.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musec.browser.data.FrequentSite
import com.musec.browser.data.MuSecDatabase
import com.musec.browser.data.NewsBriefingItem
import com.musec.browser.engine.MuSecToolkit
import com.musec.browser.ui.theme.MuSecColors
import com.musec.browser.ui.theme.tvFocusBorder
import java.util.Calendar

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTabDashboard(
    db: MuSecDatabase,
    isPrivate: Boolean,
    isAndroidTv: Boolean,
    kidsShieldActive: Boolean,
    onNavigate: (String) -> Unit,
    onOpenShield: () -> Unit,
    onOpenBurner: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var quickNotes by remember { mutableStateOf(db.getQuickNotes()) }
    var frequentSites by remember { mutableStateOf(db.getFrequentSites()) }
    var newsItems by remember { mutableStateOf<List<NewsBriefingItem>>(emptyList()) }
    var loadingNews by remember { mutableStateOf(true) }

    val greeting = remember {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hr < 12 -> "Good morning, Creative"
            hr < 18 -> "Good afternoon, Creative"
            else -> "Good evening, Creative"
        }
    }

    LaunchedEffect(Unit) {
        newsItems = MuSecToolkit.fetchWorldBriefing()
        loadingNews = false
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MuSecColors.TextPrimary,
        unfocusedTextColor = MuSecColors.TextPrimary,
        focusedBorderColor = MuSecColors.NeonCyan,
        unfocusedBorderColor = Color(0x55FFFFFF),
        focusedContainerColor = Color(0xFF1A1E2F),
        unfocusedContainerColor = Color(0xFF1A1E2F),
        cursorColor = MuSecColors.NeonCyan
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF14182B), MuSecColors.BgDeep, Color(0xFF1B1930))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isAndroidTv) 48.dp else 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Greeting Hero
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isPrivate) "🕵️ InPrivate Session" else greeting,
                    color = MuSecColors.TextPrimary,
                    fontSize = if (isAndroidTv) 34.sp else 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (isPrivate) {
                        "Ephemeral · Zero History · Auto-wipes on lock"
                    } else {
                        "Private · Fast · YouTube Kids Shield Ready"
                    },
                    color = MuSecColors.TextSecondary,
                    fontSize = if (isAndroidTv) 17.sp else 15.sp
                )
            }

            // Spacious Pill Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    shape = RoundedCornerShape(99.dp),
                    colors = fieldColors,
                    textStyle = TextStyle(fontSize = if (isAndroidTv) 18.sp else 16.sp, color = MuSecColors.TextPrimary),
                    placeholder = {
                        Text(
                            "Search Google or enter URL...",
                            color = MuSecColors.TextSecondary,
                            fontSize = if (isAndroidTv) 18.sp else 16.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (searchQuery.isNotBlank()) onNavigate(searchQuery)
                    }),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (searchQuery.isNotBlank()) onNavigate(searchQuery) },
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier
                        .height(54.dp)
                        .tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text("Search", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            // Quick Action Pills (YouTube Kids Shield, Burner Mail)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenShield,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (kidsShieldActive) MuSecColors.KidsEmerald else MuSecColors.SurfaceCard
                    ),
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text(
                        text = if (kidsShieldActive) "🛡️ YouTube Kids Shield: ON" else "🛡️ YouTube Kids Shield",
                        color = if (kidsShieldActive) Color(0xFF062018) else MuSecColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                OutlinedButton(
                    onClick = onOpenBurner,
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text("🎭 Burner Email", color = MuSecColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Frequent Websites Grid (Large touch targets, removable tiles)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Frequent websites",
                    color = MuSecColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isAndroidTv) 20.sp else 18.sp
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    frequentSites.forEach { site ->
                        SiteTileCard(
                            site = site,
                            isAndroidTv = isAndroidTv,
                            onClick = { onNavigate(site.url) },
                            onRemove = {
                                val next = frequentSites.filterNot { it.url == site.url }
                                frequentSites = next
                                db.saveFrequentSites(next)
                            }
                        )
                    }
                }
            }

            // Quick Notes Card
            if (!isPrivate) {
                Surface(
                    color = MuSecColors.SurfaceCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 900.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Quick Notes",
                            color = MuSecColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isAndroidTv) 20.sp else 17.sp
                        )
                        OutlinedTextField(
                            value = quickNotes,
                            onValueChange = {
                                quickNotes = it
                                db.saveQuickNotes(it)
                            },
                            colors = fieldColors,
                            textStyle = TextStyle(fontSize = 16.sp, color = MuSecColors.TextPrimary),
                            maxLines = 4,
                            placeholder = { Text("Write a quick note...", color = MuSecColors.TextSecondary, fontSize = 16.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // World News Briefing (Large, easy-to-read text, zero HTML tags)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Top Stories & Global Briefing",
                    color = MuSecColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isAndroidTv) 20.sp else 18.sp
                )
                if (loadingNews) {
                    Text("Loading live Sweden, Europe & World briefing...", color = MuSecColors.TextSecondary, fontSize = 15.sp)
                } else if (newsItems.isEmpty()) {
                    Text("Tap any website above or search to start browsing.", color = MuSecColors.TextSecondary, fontSize = 15.sp)
                } else {
                    newsItems.forEach { item ->
                        Surface(
                            color = MuSecColors.SurfaceCard,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusBorder(RoundedCornerShape(16.dp))
                                .clickable { onNavigate(item.url) }
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${item.region.uppercase()} · ${item.source}",
                                    color = MuSecColors.NeonCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = item.headline,
                                    color = MuSecColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (isAndroidTv) 19.sp else 17.sp,
                                    lineHeight = 24.sp
                                )
                                if (item.summary.isNotBlank()) {
                                    Text(
                                        text = item.summary,
                                        color = MuSecColors.TextSecondary,
                                        fontSize = if (isAndroidTv) 16.sp else 15.sp,
                                        lineHeight = 22.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SiteTileCard(
    site: FrequentSite,
    isAndroidTv: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = MuSecColors.SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .tvFocusBorder(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (isAndroidTv) 20.dp else 16.dp,
                vertical = if (isAndroidTv) 16.dp else 13.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(site.badgeColorHex).copy(alpha = 0.25f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = site.title.take(1).uppercase(),
                    color = Color(site.badgeColorHex),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Text(
                text = site.title,
                color = MuSecColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isAndroidTv) 17.sp else 16.sp
            )
            Text(
                text = "✕",
                color = MuSecColors.TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable { onRemove() }
                    .padding(start = 6.dp)
            )
        }
    }
}
