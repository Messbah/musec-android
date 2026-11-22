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
                .padding(horizontal = if (isAndroidTv) 48.dp else 18.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Greeting Hero
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isPrivate) "🕵️ InPrivate Session" else greeting,
                    color = MuSecColors.TextPrimary,
                    fontSize = if (isAndroidTv) 32.sp else 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isPrivate) {
                        "Ephemeral · Zero History · Auto-wipes when screen locks"
                    } else {
                        "Private · Fast · YouTube Kids Shield Ready"
                    },
                    color = MuSecColors.TextSecondary,
                    fontSize = 13.sp
                )
            }

            // Pill Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    shape = RoundedCornerShape(99.dp),
                    placeholder = { Text("Search Google or enter website URL...") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (searchQuery.isNotBlank()) onNavigate(searchQuery)
                    }),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (searchQuery.isNotBlank()) onNavigate(searchQuery) },
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text("Go", fontWeight = FontWeight.Bold)
                }
            }

            // Quick Action Pills (YouTube Kids Shield, Burner Mail)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenShield,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (kidsShieldActive) MuSecColors.KidsEmerald else MuSecColors.SurfaceCard
                    ),
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text(
                        text = if (kidsShieldActive) "🛡️ YouTube Kids Shield: ON" else "🛡️ Configure YouTube Kids Shield",
                        color = if (kidsShieldActive) Color(0xFF062018) else MuSecColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                OutlinedButton(
                    onClick = onOpenBurner,
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text("🎭 1-Click Burner Email", color = MuSecColors.TextPrimary, fontSize = 13.sp)
                }
            }

            // Frequent Websites Grid (Removable tiles, D-pad focusable for TV)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 880.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Frequent websites", color = MuSecColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
                        .widthIn(max = 880.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quick Notes", color = MuSecColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        OutlinedTextField(
                            value = quickNotes,
                            onValueChange = {
                                quickNotes = it
                                db.saveQuickNotes(it)
                            },
                            maxLines = 4,
                            placeholder = { Text("Write a quick note...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // World News Briefing (Readable font size, clean stripped text — no HTML tags)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 880.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Top Stories & Global Briefing", color = MuSecColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (loadingNews) {
                    Text("Loading clean live news briefing...", color = MuSecColors.TextSecondary, fontSize = 14.sp)
                } else if (newsItems.isEmpty()) {
                    Text("Connect to the internet to load live Sweden, Europe & World news.", color = MuSecColors.TextSecondary, fontSize = 14.sp)
                } else {
                    newsItems.forEach { item ->
                        Surface(
                            color = MuSecColors.SurfaceCard,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusBorder(RoundedCornerShape(14.dp))
                                .clickable { onNavigate(item.url) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = "${item.region.uppercase()} · ${item.source}",
                                    color = MuSecColors.NeonCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = item.headline,
                                    color = MuSecColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (isAndroidTv) 17.sp else 15.sp,
                                    lineHeight = 22.sp
                                )
                                if (item.summary.isNotBlank()) {
                                    Text(
                                        text = item.summary,
                                        color = MuSecColors.TextSecondary,
                                        fontSize = if (isAndroidTv) 15.sp else 14.sp,
                                        lineHeight = 21.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
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
                horizontal = if (isAndroidTv) 18.dp else 14.dp,
                vertical = if (isAndroidTv) 14.dp else 11.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(site.badgeColorHex).copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = site.title.take(1).uppercase(),
                    color = Color(site.badgeColorHex),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Text(
                text = site.title,
                color = MuSecColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isAndroidTv) 15.sp else 13.sp
            )
            Text(
                text = "✕",
                color = MuSecColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onRemove() }
                    .padding(start = 4.dp)
            )
        }
    }
}
