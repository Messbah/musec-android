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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musec.browser.data.BUILTIN_KIDS_UNSAFE_KEYWORDS
import com.musec.browser.data.LegitimacyReport
import com.musec.browser.data.ShieldStats
import com.musec.browser.data.YouTubeShieldConfig
import com.musec.browser.engine.GeckoBrowserManager
import com.musec.browser.ui.theme.MuSecColors
import com.musec.browser.ui.theme.tvFocusBorder

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShieldPanelContent(
    manager: GeckoBrowserManager,
    config: YouTubeShieldConfig,
    stats: ShieldStats,
    legitimacy: LegitimacyReport,
    onRunDarkPattern: () -> Unit,
    onClose: () -> Unit
) {
    var filterType by remember { mutableStateOf("customBlockedKeywords") }
    var filterInput by remember { mutableStateOf("") }
    var showPinBox by remember { mutableStateOf(config.locked) }
    var pinInput by remember { mutableStateOf("") }
    var pinFeedback by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🛡️ YouTube Kids & Content Shield",
                color = MuSecColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
            ) {
                Text("Close", color = MuSecColors.TextPrimary)
            }
        }

        // Master Controls + 1-Click Kids Safe Preset + Parental PIN
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { manager.updateShieldConfig { it.copy(ytEnabled = !it.ytEnabled) } },
                enabled = !config.locked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (config.ytEnabled) MuSecColors.NeonCyan else MuSecColors.SurfaceCard
                ),
                modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
            ) {
                Text(
                    text = if (config.ytEnabled) "Shield: ON" else "Shield: OFF",
                    color = if (config.ytEnabled) Color(0xFF0A111D) else MuSecColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = { manager.applyKidsSafePreset() },
                enabled = !config.locked,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (config.isKidsPresetActive) MuSecColors.KidsEmerald else MuSecColors.SurfaceCard
                ),
                modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
            ) {
                Text(
                    text = if (config.isKidsPresetActive) "👶 Kids Preset: ON" else "👶 Kids Safe Preset",
                    color = if (config.isKidsPresetActive) Color(0xFF062018) else MuSecColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    if (config.hasPin && !config.locked) {
                        manager.lockWithExistingPin()
                        showPinBox = true
                        pinFeedback = "Settings locked with Parental PIN."
                    } else {
                        showPinBox = !showPinBox
                    }
                },
                modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
            ) {
                Text(
                    text = when {
                        config.locked -> "🔓 Unlock PIN"
                        config.hasPin -> "🔒 Lock Now"
                        else -> "🔒 Parental PIN"
                    },
                    color = if (config.locked) MuSecColors.DangerRed else MuSecColors.TextPrimary
                )
            }
        }

        // Parental PIN Box
        if (showPinBox || config.locked) {
            Surface(
                color = MuSecColors.SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (config.locked) "Enter Parental PIN to unlock filters" else "Set a 4+ character Parental PIN to lock settings",
                        color = MuSecColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            placeholder = { Text("PIN (4+ chars)") },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (config.locked) {
                                    if (manager.unlockWithPin(pinInput)) {
                                        pinInput = ""
                                        pinFeedback = "Unlocked."
                                        showPinBox = false
                                    } else {
                                        pinFeedback = "Incorrect PIN."
                                    }
                                } else {
                                    if (manager.setParentalPin(pinInput)) {
                                        pinInput = ""
                                        pinFeedback = "Parental PIN saved & locked."
                                    } else {
                                        pinFeedback = "PIN must be at least 4 characters."
                                    }
                                }
                            },
                            modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
                        ) {
                            Text(if (config.locked) "Unlock" else "Save")
                        }
                        if (config.hasPin && !config.locked) {
                            OutlinedButton(onClick = {
                                manager.setParentalPin("")
                                pinFeedback = "Parental PIN removed."
                                showPinBox = false
                            }) {
                                Text("Clear")
                            }
                        }
                    }
                    if (pinFeedback.isNotBlank()) {
                        Text(pinFeedback, color = MuSecColors.NeonCyan, fontSize = 12.sp)
                    }
                }
            }
        }

        // Live Telemetry Counters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MuSecColors.SurfaceCard)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatBadge("Shorts", stats.shortsBlocked)
            StatBadge("YT Ads", stats.ytAdsSkipped)
            StatBadge("Filtered", stats.videosFiltered)
            StatBadge("Reels", stats.reelsBlocked)
            StatBadge("Web Ads", stats.adsBlocked)
        }

        // Selectable Filters Grid
        Text("Selectable Filters", color = MuSecColors.NeonCyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        val filterItems = listOf(
            Triple("Block Shorts / Reels", config.blockShorts) { c: YouTubeShieldConfig -> c.copy(blockShorts = !c.blockShorts, ytEnabled = true) },
            Triple("Block YouTube Ads", config.blockAds) { c: YouTubeShieldConfig -> c.copy(blockAds = !c.blockAds, ytEnabled = true) },
            Triple("Kids Unsafe Keywords", config.blockKidsUnsafeKeywords) { c: YouTubeShieldConfig -> c.copy(blockKidsUnsafeKeywords = !c.blockKidsUnsafeKeywords, ytEnabled = true) },
            Triple("Hide Comments & Chat", config.blockComments) { c: YouTubeShieldConfig -> c.copy(blockComments = !c.blockComments, ytEnabled = true) },
            Triple("Hide Recommendations", config.blockRecommendations) { c: YouTubeShieldConfig -> c.copy(blockRecommendations = !c.blockRecommendations, ytEnabled = true) },
            Triple("Hide Home Feed", config.blockHomeFeed) { c: YouTubeShieldConfig -> c.copy(blockHomeFeed = !c.blockHomeFeed, ytEnabled = true) },
            Triple("Disable Autoplay", config.blockAutoplay) { c: YouTubeShieldConfig -> c.copy(blockAutoplay = !c.blockAutoplay, ytEnabled = true) },
            Triple("YouTube Restricted Mode", config.enforceRestrictedMode) { c: YouTubeShieldConfig -> c.copy(enforceRestrictedMode = !c.enforceRestrictedMode, ytEnabled = true) },
            Triple("Block IG / FB / TikTok", config.blockSocialReels) { c: YouTubeShieldConfig -> c.copy(blockSocialReels = !c.blockSocialReels, ytEnabled = true) },
            Triple("Allowed Channels Only", config.whitelistOnlyMode) { c: YouTubeShieldConfig -> c.copy(whitelistOnlyMode = !c.whitelistOnlyMode, ytEnabled = true) },
            Triple("Web AdShield (Global)", config.adShieldEnabled) { c: YouTubeShieldConfig -> c.copy(adShieldEnabled = !c.adShieldEnabled) },
            Triple("Auto Cookie Decline", config.darkPatternAuto) { c: YouTubeShieldConfig -> c.copy(darkPatternAuto = !c.darkPatternAuto) }
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filterItems.forEach { (label, active, updater) ->
                val effectiveActive = active && (label.startsWith("Web") || label.startsWith("Auto") || config.ytEnabled)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (effectiveActive) Color(0x2A34D399) else MuSecColors.SurfaceCard)
                        .tvFocusBorder(
                            shape = RoundedCornerShape(12.dp),
                            focusedColor = if (effectiveActive) MuSecColors.KidsEmerald else MuSecColors.NeonCyan
                        )
                        .clickable(enabled = !config.locked) { manager.updateShieldConfig(updater) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (effectiveActive) MuSecColors.KidsEmerald else Color(0x6694A3B8))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(label, color = MuSecColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Add Custom Filters (Keywords / Channels / Whitelist / Domains)
        Text("Add Custom Filter", color = MuSecColors.NeonCyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "customBlockedKeywords" to "Block Keyword",
                "customBlockedChannels" to "Block Channel",
                "allowedChannels" to "Allow Channel",
                "customBlockedDomains" to "Block Domain"
            ).forEach { (key, title) ->
                val selected = filterType == key
                OutlinedButton(
                    onClick = { filterType = key },
                    enabled = !config.locked,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) Color(0x3338BDF8) else Color.Transparent
                    ),
                    modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text(title, fontSize = 11.sp, color = MuSecColors.TextPrimary)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filterInput,
                onValueChange = { filterInput = it },
                enabled = !config.locked,
                singleLine = true,
                placeholder = {
                    Text(
                        when (filterType) {
                            "customBlockedChannels" -> "e.g. @ChannelName"
                            "allowedChannels" -> "e.g. PBS Kids, Numberblocks"
                            "customBlockedDomains" -> "e.g. example-ads.com"
                            else -> "e.g. skibidi, prank, horror"
                        },
                        fontSize = 12.sp
                    )
                },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val clean = filterInput.trim()
                    if (clean.isNotEmpty()) {
                        manager.updateShieldConfig { c ->
                            when (filterType) {
                                "customBlockedChannels" -> c.copy(customBlockedChannels = (c.customBlockedChannels + clean).distinct())
                                "allowedChannels" -> c.copy(allowedChannels = (c.allowedChannels + clean).distinct())
                                "customBlockedDomains" -> c.copy(customBlockedDomains = (c.customBlockedDomains + clean.lowercase()).distinct())
                                else -> c.copy(customBlockedKeywords = (c.customBlockedKeywords + clean).distinct())
                            }
                        }
                        filterInput = ""
                    }
                },
                enabled = !config.locked,
                modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
            ) {
                Text("Add")
            }
        }

        // Quick Starter Chips
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "customBlockedKeywords" to "prank",
                "customBlockedKeywords" to "unboxing",
                "customBlockedKeywords" to "horror",
                "allowedChannels" to "PBS Kids",
                "allowedChannels" to "Numberblocks"
            ).forEach { (listName, sample) ->
                Surface(
                    color = MuSecColors.SurfaceCard,
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier
                        .tvFocusBorder(RoundedCornerShape(99.dp))
                        .clickable(enabled = !config.locked) {
                            manager.updateShieldConfig { c ->
                                if (listName == "allowedChannels") c.copy(allowedChannels = (c.allowedChannels + sample).distinct())
                                else c.copy(customBlockedKeywords = (c.customBlockedKeywords + sample).distinct())
                            }
                        }
                ) {
                    Text("+ $sample", color = MuSecColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }

        // Active Custom Filter Chips
        val chips = buildList {
            config.customBlockedKeywords.forEach { add(Triple("kw", "🚫 $it", it)) }
            config.customBlockedChannels.forEach { add(Triple("ch", "⛔ $it", it)) }
            config.allowedChannels.forEach { add(Triple("al", "✅ $it", it)) }
            config.customBlockedDomains.forEach { add(Triple("dm", "🌐 $it", it)) }
        }
        if (chips.isEmpty()) {
            Text(
                text = "Built-in Kids keyword pack active (${BUILTIN_KIDS_UNSAFE_KEYWORDS.size} terms). Add custom keywords or channels above.",
                color = MuSecColors.TextSecondary,
                fontSize = 12.sp
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (kind, label, raw) ->
                    val bg = when (kind) {
                        "al" -> Color(0x2A34D399)
                        "ch" -> Color(0x2AF97316)
                        else -> Color(0x2AF87171)
                    }
                    Surface(
                        color = bg,
                        shape = RoundedCornerShape(99.dp),
                        modifier = Modifier
                            .tvFocusBorder(RoundedCornerShape(99.dp))
                            .clickable(enabled = !config.locked) {
                                manager.updateShieldConfig { c ->
                                    when (kind) {
                                        "kw" -> c.copy(customBlockedKeywords = c.customBlockedKeywords.filterNot { it == raw })
                                        "ch" -> c.copy(customBlockedChannels = c.customBlockedChannels.filterNot { it == raw })
                                        "al" -> c.copy(allowedChannels = c.allowedChannels.filterNot { it == raw })
                                        else -> c.copy(customBlockedDomains = c.customBlockedDomains.filterNot { it == raw })
                                    }
                                }
                            }
                    ) {
                        Text(
                            text = if (config.locked) label else "$label ✕",
                            color = MuSecColors.TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // Website Legitimacy & Dark Pattern Card
        Surface(
            color = MuSecColors.SurfaceCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = Color(0x3334D399), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            legitimacy.grade,
                            color = MuSecColors.KidsEmerald,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Column {
                        Text(legitimacy.verdict, color = MuSecColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(legitimacy.host, color = MuSecColors.TextSecondary, fontSize = 11.sp)
                    }
                }
                legitimacy.signals.forEach { sig ->
                    Text("• $sig", color = MuSecColors.TextSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onRunDarkPattern,
                    modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))
                ) {
                    Text("🤖 Decline Cookies & Pop-ups Now", color = MuSecColors.NeonCyan, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = MuSecColors.NeonCyan, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(label, color = MuSecColors.TextSecondary, fontSize = 13.sp)
    }
}
