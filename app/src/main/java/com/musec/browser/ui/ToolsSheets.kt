package com.musec.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musec.browser.data.BookmarkItem
import com.musec.browser.data.BurnerIdentity
import com.musec.browser.data.BurnerMessage
import com.musec.browser.data.EncryptedVault
import com.musec.browser.data.HistoryItem
import com.musec.browser.data.PriceOffer
import com.musec.browser.data.ReaderArticle
import com.musec.browser.data.ReviewAuditResult
import com.musec.browser.data.VaultEntry
import com.musec.browser.engine.BrowserTab
import com.musec.browser.engine.GeckoBrowserManager
import com.musec.browser.engine.MuSecToolkit
import com.musec.browser.ui.theme.MuSecColors
import com.musec.browser.ui.theme.tvFocusBorder
import kotlinx.coroutines.launch

enum class ActiveToolSheet {
    NONE, SHIELD, TABS, TOOLS_HUB, BURNER, PRICE_AND_REVIEWS, READER, TRANSLATE, VAULT, HISTORY
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsHubSheet(
    onOpenSheet: (ActiveToolSheet) -> Unit,
    onEnterPip: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onWipePrivate: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("✨ MuSec Power Tools", color = MuSecColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedButton(onClick = onClose, modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))) { Text("Close") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "🛡️ YouTube Kids Shield" to { onOpenSheet(ActiveToolSheet.SHIELD) },
                "🎭 1-Click Burner Email" to { onOpenSheet(ActiveToolSheet.BURNER) },
                "🏷️ Lowest Price & Fake Reviews" to { onOpenSheet(ActiveToolSheet.PRICE_AND_REVIEWS) },
                "📖 Paywall Bypass Reader" to { onOpenSheet(ActiveToolSheet.READER) },
                "🌐 Translate Page (Any→EN)" to { onOpenSheet(ActiveToolSheet.TRANSLATE) },
                "🔑 Encrypted Password Vault" to { onOpenSheet(ActiveToolSheet.VAULT) },
                "🕒 History & Bookmarks" to { onOpenSheet(ActiveToolSheet.HISTORY) },
                "⧉ Picture-in-Picture" to onEnterPip,
                "🕵️ New Private Tab" to onNewPrivateTab,
                "🧹 Wipe Private Tabs" to onWipePrivate
            ).forEach { (label, action) ->
                Surface(
                    color = MuSecColors.SurfaceCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .tvFocusBorder(RoundedCornerShape(14.dp))
                        .clickable { action() }
                ) {
                    Text(label, color = MuSecColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp))
                }
            }
        }
    }
}

@Composable
fun TabsSheet(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onWipePrivate: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Tabs (${tabs.size})", color = MuSecColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onNewTab(false) }, modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))) { Text("+ Tab") }
                OutlinedButton(onClick = { onNewTab(true) }, modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))) { Text("+ Private") }
                OutlinedButton(onClick = onClose, modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))) { Text("✕") }
            }
        }
        if (tabs.any { it.isPrivate }) {
            OutlinedButton(onClick = onWipePrivate, modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))) {
                Text("🧹 Wipe All Private Tabs Now (Also auto-wipes on screen lock)", color = MuSecColors.NeonViolet, fontSize = 12.sp)
            }
        }
        tabs.forEach { tab ->
            val selected = tab.id == activeTabId
            Surface(
                color = if (selected) Color(0x3338BDF8) else MuSecColors.SurfaceCard,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusBorder(RoundedCornerShape(12.dp))
                    .clickable {
                        onSelect(tab.id)
                        onClose()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (if (tab.isPrivate) "🕵️ " else "") + tab.title.take(46),
                            color = MuSecColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(tab.url.take(60), color = MuSecColors.TextSecondary, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onCloseTab(tab.id) }) { Text("✕") }
                }
            }
        }
    }
}

@Composable
fun BurnerMailSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var identity by remember { mutableStateOf<BurnerIdentity?>(null) }
    var messages by remember { mutableStateOf<List<BurnerMessage>>(emptyList()) }
    var status by remember { mutableStateOf("Generating disposable mailbox...") }

    LaunchedEffect(Unit) {
        val id = MuSecToolkit.createBurnerIdentity()
        identity = id
        status = "Live via ${id.provider} · Persona: ${id.personaName}"
        messages = MuSecToolkit.fetchBurnerMessages(id)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🎭 1-Click Burner Email", color = MuSecColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedButton(onClick = onClose, modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))) { Text("Close") }
        }
        Text(status, color = MuSecColors.TextSecondary, fontSize = 12.sp)
        identity?.let { id ->
            Surface(color = MuSecColors.SurfaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(id.address, color = MuSecColors.NeonCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            copyToClipboard(context, "Burner Email", id.address)
                            status = "Copied ${id.address}!"
                        }) { Text("Copy") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                status = "Refreshing inbox..."
                                messages = MuSecToolkit.fetchBurnerMessages(id)
                                status = "Inbox synced (${messages.size} message(s))"
                            }
                        }) { Text("Refresh Inbox") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                status = "Creating fresh address..."
                                val fresh = MuSecToolkit.createBurnerIdentity()
                                identity = fresh
                                messages = emptyList()
                                status = "New mailbox via ${fresh.provider}"
                            }
                        }) { Text("New") }
                    }
                }
            }
        }
        if (messages.isEmpty()) {
            Text("No emails yet. Use the address above to sign up and tap Refresh Inbox.", color = MuSecColors.TextSecondary, fontSize = 12.sp)
        } else {
            messages.forEach { msg ->
                Surface(color = MuSecColors.SurfaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(msg.subject, color = MuSecColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("From: ${msg.from}", color = MuSecColors.NeonCyan, fontSize = 11.sp)
                        if (msg.intro.isNotBlank()) Text(msg.intro, color = MuSecColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PriceAndReviewSheet(
    manager: GeckoBrowserManager,
    initialQuery: String,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var offers by remember { mutableStateOf<List<PriceOffer>>(emptyList()) }
    var audit by remember { mutableStateOf<ReviewAuditResult?>(null) }
    var status by remember { mutableStateOf("Gathering lowest prices & auditing page reviews...") }

    LaunchedEffect(Unit) {
        val pageData = manager.runPageCommand("extract_shopping")
        val detectedProduct = pageData?.optString("productName").orEmpty().ifBlank { initialQuery }
        if (detectedProduct.isNotBlank()) query = detectedProduct
        val reviewsArr = pageData?.optJSONArray("reviews")
        val reviews = if (reviewsArr != null) (0 until reviewsArr.length()).map { reviewsArr.optString(it) } else emptyList()
        audit = MuSecToolkit.auditProductReviews(reviews, pageData?.optString("bodySample").orEmpty())
        if (query.isNotBlank()) {
            offers = MuSecToolkit.huntLowestPrices(query)
            status = "Found ${offers.size} store offer(s) & comparison hubs."
        } else {
            status = "Enter a product name to scrape the lowest web price."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🏷️ Lowest Price & Review Audit", color = MuSecColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Item name...") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    status = "Scraping web stores..."
                    offers = MuSecToolkit.huntLowestPrices(query)
                    status = "Found ${offers.size} store offer(s)."
                }
            }) { Text("Scrape") }
        }
        Text(status, color = MuSecColors.TextSecondary, fontSize = 12.sp)

        offers.forEach { offer ->
            Surface(
                color = MuSecColors.SurfaceCard,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusBorder(RoundedCornerShape(12.dp))
                    .clickable {
                        onOpenUrl(offer.url)
                        onClose()
                    }
            ) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(offer.store, color = MuSecColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(offer.formattedPrice, color = MuSecColors.KidsEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        audit?.let { res ->
            Surface(color = MuSecColors.SurfaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("🕵️ Fake Review & AI-Spam Grade: ${res.grade}", color = MuSecColors.NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(res.verdict, color = MuSecColors.TextPrimary, fontSize = 12.sp)
                    res.signals.forEach { s -> Text("• $s", color = MuSecColors.TextSecondary, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
fun ReaderModeSheet(manager: GeckoBrowserManager, currentUrl: String, onOpenArchive: (String) -> Unit, onClose: () -> Unit) {
    var article by remember { mutableStateOf<ReaderArticle?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(currentUrl) {
        val data = manager.runPageCommand("extract_reader")
        val parasArr = data?.optJSONArray("paragraphs")
        val paras = if (parasArr != null) (0 until parasArr.length()).map { parasArr.optString(it) } else emptyList()
        article = ReaderArticle(
            title = data?.optString("title").orEmpty().ifBlank { "Immersive Reader" },
            url = data?.optString("url").orEmpty().ifBlank { currentUrl },
            method = data?.optString("method").orEmpty().ifBlank { "DOM Paywall Unlock" },
            paragraphs = paras
        )
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF6F1E7))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("📖 Paywall Bypass Reader", color = Color(0xFF1F2937), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onOpenArchive("https://archive.is/newest/$currentUrl"); onClose() }) {
                    Text("Archive Snapshot", color = Color(0xFF1F2937), fontSize = 11.sp)
                }
                OutlinedButton(onClick = onClose) { Text("Close", color = Color(0xFF1F2937)) }
            }
        }
        if (loading) {
            Text("Unlocking soft overlays & extracting clean article...", color = Color(0xFF4B5563))
        } else {
            article?.let { art ->
                Text(art.title, color = Color(0xFF111827), fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 28.sp)
                Text("🔓 ${art.method} · ${art.paragraphs.size} paragraph(s)", color = Color(0xFF6B7280), fontSize = 12.sp)
                if (art.paragraphs.isEmpty()) {
                    Text("No article paragraphs found on this page. Tap Archive Snapshot above for hard paywalls.", color = Color(0xFF374151), fontSize = 15.sp)
                } else {
                    art.paragraphs.forEach { p ->
                        Text(p, color = Color(0xFF1F2937), fontSize = 16.sp, lineHeight = 25.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TranslatorSheet(manager: GeckoBrowserManager, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val data = manager.runPageCommand("extract_translate")
        val extracted = data?.optString("text").orEmpty()
        if (extracted.isNotBlank()) {
            inputText = extracted
            loading = true
            translated = MuSecToolkit.translateToEnglish(extracted)
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🌐 Universal Translator (Any → EN)", color = MuSecColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        OutlinedTextField(value = inputText, onValueChange = { inputText = it }, placeholder = { Text("Paste any language text...") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                loading = true
                translated = MuSecToolkit.translateToEnglish(inputText)
                loading = false
            }
        }) { Text(if (loading) "Translating..." else "Translate to English") }
        if (translated.isNotBlank()) {
            Surface(color = MuSecColors.SurfaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(translated, color = MuSecColors.TextPrimary, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
fun VaultSheet(
    vault: EncryptedVault,
    unlocked: Boolean,
    onRequestBiometricUnlock: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(vault.listEntries()) }
    var site by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🔑 Encrypted Password Vault", color = MuSecColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Text("Hardware-backed Android Keystore AES-256-GCM", color = MuSecColors.TextSecondary, fontSize = 12.sp)

        if (!unlocked) {
            Button(onClick = onRequestBiometricUnlock, modifier = Modifier.tvFocusBorder(RoundedCornerShape(99.dp))) {
                Text("Unlock Vault (Biometric / Device Credential)")
            }
            return@Column
        }

        OutlinedTextField(value = site, onValueChange = { site = it }, singleLine = true, placeholder = { Text("Site (e.g. github.com)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = username, onValueChange = { username = it }, singleLine = true, placeholder = { Text("Username / Email") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                placeholder = { Text("Password") },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { password = vault.generateStrongPassword(24); feedback = "Generated 24-char password" }) { Text("🎲 Gen") }
            Button(onClick = {
                if (site.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                    vault.saveCredential(site, username, password)
                    entries = vault.listEntries()
                    site = ""; username = ""; password = ""
                    feedback = "Saved encrypted credential!"
                }
            }) { Text("Save") }
        }
        if (feedback.isNotBlank()) Text(feedback, color = MuSecColors.KidsEmerald, fontSize = 12.sp)

        entries.forEach { entry ->
            Surface(color = MuSecColors.SurfaceCard, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.site, color = MuSecColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(entry.username, color = MuSecColors.TextSecondary, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            val pw = vault.decryptPassword(entry.id)
                            if (pw != null) {
                                copyToClipboard(context, "Password", pw)
                                feedback = "Copied password for ${entry.site}"
                            }
                        }) { Text("Copy PW", fontSize = 11.sp) }
                        OutlinedButton(onClick = {
                            vault.deleteEntry(entry.id)
                            entries = vault.listEntries()
                        }) { Text("✕") }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryAndBookmarksSheet(
    history: List<HistoryItem>,
    bookmarks: List<BookmarkItem>,
    onNavigate: (String) -> Unit,
    onClearHistory: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🕒 Bookmarks & History", color = MuSecColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onClearHistory) { Text("Clear History", fontSize = 11.sp) }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
        Text("Bookmarks (${bookmarks.size})", color = MuSecColors.NeonCyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (bookmarks.isEmpty()) Text("No bookmarks saved yet.", color = MuSecColors.TextSecondary, fontSize = 12.sp)
        bookmarks.forEach { b ->
            Surface(
                color = MuSecColors.SurfaceCard,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().tvFocusBorder(RoundedCornerShape(10.dp)).clickable { onNavigate(b.url); onClose() }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("★ ${b.title}", color = MuSecColors.TextPrimary, fontSize = 13.sp)
                    Text(b.url.take(65), color = MuSecColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
        Text("Recent History (${history.size})", color = MuSecColors.NeonCyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        history.take(25).forEach { h ->
            Surface(
                color = MuSecColors.SurfaceCard,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().tvFocusBorder(RoundedCornerShape(10.dp)).clickable { onNavigate(h.url); onClose() }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(h.title.take(55), color = MuSecColors.TextPrimary, fontSize = 13.sp)
                    Text(h.url.take(65), color = MuSecColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}
