package com.musec.browser

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.musec.browser.data.EncryptedVault
import com.musec.browser.data.MuSecDatabase
import com.musec.browser.engine.GeckoBrowserManager
import com.musec.browser.engine.MuSecToolkit
import com.musec.browser.ui.ActiveToolSheet
import com.musec.browser.ui.BurnerMailSheet
import com.musec.browser.ui.HistoryAndBookmarksSheet
import com.musec.browser.ui.NewTabDashboard
import com.musec.browser.ui.PriceAndReviewSheet
import com.musec.browser.ui.ReaderModeSheet
import com.musec.browser.ui.ShieldPanelContent
import com.musec.browser.ui.TabsSheet
import com.musec.browser.ui.ToolsHubSheet
import com.musec.browser.ui.TranslatorSheet
import com.musec.browser.ui.VaultSheet
import com.musec.browser.ui.theme.MuSecColors
import com.musec.browser.ui.theme.MuSecTheme
import com.musec.browser.ui.theme.tvFocusBorder
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView

class MainActivity : FragmentActivity() {

    private lateinit var db: MuSecDatabase
    private lateinit var vault: EncryptedVault
    private lateinit var browserManager: GeckoBrowserManager
    private var isAndroidTv: Boolean = false
    private val vaultUnlockedState = mutableStateOf(false)
    private val activeSheetState = mutableStateOf(ActiveToolSheet.NONE)

    // Auto-wipe all InPrivate tabs immediately when phone or Android TV screen turns off / locks
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                browserManager.wipeAllPrivateTabs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw cleanly with explicit Compose safeDrawing / cutout / statusBar / navBar insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false

        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        isAndroidTv = (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        db = MuSecDatabase(this)
        vault = EncryptedVault(db)
        browserManager = GeckoBrowserManager(this, db, isAndroidTv)

        val bio = BiometricManager.from(this)
        if (bio.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            vaultUnlockedState.value = true
        }

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handleIncomingIntent(intent)

        setContent {
            MuSecTheme {
                MuSecBrowserRoot(
                    manager = browserManager,
                    db = db,
                    vault = vault,
                    isAndroidTv = isAndroidTv,
                    vaultUnlocked = vaultUnlockedState.value,
                    activeSheet = activeSheetState.value,
                    onSetSheet = { activeSheetState.value = it },
                    onRequestBiometricUnlock = { promptBiometricUnlock() },
                    onEnterPip = { enterBrowserPip() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataUrl = intent.dataString
            if (!dataUrl.isNullOrBlank() && (dataUrl.startsWith("http://") || dataUrl.startsWith("https://"))) {
                browserManager.navigateActiveTab(dataUrl)
            }
        }
    }

    private fun promptBiometricUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vaultUnlockedState.value = true
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock MuSec Encrypted Vault")
            .setSubtitle("Authenticate to access saved passwords")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    private fun enterBrowserPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            activeSheetState.value = if (activeSheetState.value == ActiveToolSheet.SHIELD) {
                ActiveToolSheet.NONE
            } else {
                ActiveToolSheet.SHIELD
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (activeSheetState.value != ActiveToolSheet.NONE) {
                activeSheetState.value = ActiveToolSheet.NONE
                return true
            }
            val active = browserManager.getActiveTab()
            if (active != null && active.url != GeckoBrowserManager.NEWTAB_URL) {
                if (active.canGoBack) {
                    active.session.goBack()
                } else {
                    browserManager.navigateActiveTab(GeckoBrowserManager.NEWTAB_URL)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        browserManager.shutdown()
        db.close()
        super.onDestroy()
    }
}

@Composable
private fun MuSecBrowserRoot(
    manager: GeckoBrowserManager,
    db: MuSecDatabase,
    vault: EncryptedVault,
    isAndroidTv: Boolean,
    vaultUnlocked: Boolean,
    activeSheet: ActiveToolSheet,
    onSetSheet: (ActiveToolSheet) -> Unit,
    onRequestBiometricUnlock: () -> Unit,
    onEnterPip: () -> Unit
) {
    val tabs by manager.tabs.collectAsState()
    val activeTabId by manager.activeTabId.collectAsState()
    val shieldConfig by manager.shieldConfig.collectAsState()
    val shieldStats by manager.shieldStats.collectAsState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    val currentUrl = activeTab?.url ?: GeckoBrowserManager.NEWTAB_URL
    val isNewTab = currentUrl == GeckoBrowserManager.NEWTAB_URL
    val legitimacy = remember(currentUrl) { MuSecToolkit.evaluateLegitimacy(currentUrl) }

    var omniboxText by remember(activeTabId, currentUrl) {
        mutableStateOf(if (isNewTab) "" else currentUrl)
    }
    var isBookmarked by remember(currentUrl) {
        mutableStateOf(!isNewTab && db.isBookmarked(currentUrl))
    }

    fun submitOmnibox() {
        val query = omniboxText.trim()
        if (query.isNotEmpty()) {
            focusManager.clearFocus()
            keyboardController?.hide()
            manager.navigateActiveTab(query)
        }
    }

    // Root container respects status bar, camera hole/display cutout, gesture bar, and keyboard IME!
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MuSecColors.BgDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.displayCutout))
    ) {
        // 1. TOP SAFE ADDRESS BAR (Full-width, never behind camera hole, 50dp tall touch targets!)
        val barBg = if (activeTab?.isPrivate == true) Color(0xFF23183A) else MuSecColors.SurfaceGlass
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg)
                .padding(horizontal = if (isAndroidTv) 24.dp else 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isAndroidTv) {
                ActionButtonPill("◀", enabled = activeTab?.canGoBack == true) { activeTab?.session?.goBack() }
                ActionButtonPill("▶", enabled = activeTab?.canGoForward == true) { activeTab?.session?.goForward() }
                ActionButtonPill("⌂") { manager.navigateActiveTab(GeckoBrowserManager.NEWTAB_URL) }
            }

            // Spacious Glass Pill Omnibox
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0xFF131622))
                    .tvFocusBorder(RoundedCornerShape(99.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (activeTab?.isPrivate == true) "🕵️" else legitimacy.grade,
                    color = if (activeTab?.isPrivate == true) MuSecColors.NeonViolet else MuSecColors.KidsEmerald,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onSetSheet(ActiveToolSheet.SHIELD) }
                )

                BasicTextField(
                    value = omniboxText,
                    onValueChange = { omniboxText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MuSecColors.TextPrimary,
                        fontSize = if (isAndroidTv) 17.sp else 16.sp
                    ),
                    cursorBrush = SolidColor(MuSecColors.NeonCyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submitOmnibox() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxWidth()) {
                            if (omniboxText.isEmpty()) {
                                Text(
                                    text = "Search or enter website...",
                                    color = MuSecColors.TextSecondary,
                                    fontSize = if (isAndroidTv) 17.sp else 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                if (omniboxText.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = MuSecColors.TextSecondary,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable { omniboxText = "" }
                            .padding(horizontal = 4.dp)
                    )
                }

                if (!isNewTab) {
                    Text(
                        text = if (isBookmarked) "★" else "☆",
                        color = if (isBookmarked) MuSecColors.WarningAmber else MuSecColors.TextSecondary,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clickable {
                                isBookmarked = db.toggleBookmark(currentUrl, activeTab?.title ?: currentUrl)
                            }
                            .padding(horizontal = 4.dp)
                    )
                }
            }

            // Explicit Go / Reload Button (Always visible & tappable!)
            Surface(
                color = MuSecColors.NeonCyan,
                shape = RoundedCornerShape(99.dp),
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .tvFocusBorder(RoundedCornerShape(99.dp))
                    .clickable {
                        if (omniboxText.isNotBlank() && omniboxText.trim() != currentUrl) {
                            submitOmnibox()
                        } else if (!isNewTab) {
                            manager.reloadActiveTab()
                        }
                    }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = if (omniboxText.isNotBlank() && omniboxText.trim() != currentUrl) "Go" else "↻",
                        color = Color(0xFF0A111D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            if (isAndroidTv) {
                ShieldToolbarPill(
                    ytEnabled = shieldConfig.ytEnabled,
                    kidsPreset = shieldConfig.isKidsPresetActive,
                    totalBlocked = shieldStats.totalBlocked,
                    activeFilters = shieldConfig.activeFilterCount,
                    onClick = {
                        onSetSheet(if (activeSheet == ActiveToolSheet.SHIELD) ActiveToolSheet.NONE else ActiveToolSheet.SHIELD)
                    }
                )
                ActionButtonPill("Tabs (${tabs.size})") {
                    onSetSheet(if (activeSheet == ActiveToolSheet.TABS) ActiveToolSheet.NONE else ActiveToolSheet.TABS)
                }
                ActionButtonPill("☰ Tools") {
                    onSetSheet(if (activeSheet == ActiveToolSheet.TOOLS_HUB) ActiveToolSheet.NONE else ActiveToolSheet.TOOLS_HUB)
                }
            }
        }

        // Loading Progress Indicator
        if (!isNewTab && (activeTab?.progress ?: 100) < 100) {
            LinearProgressIndicator(
                progress = { (activeTab?.progress ?: 0) / 100f },
                color = MuSecColors.NeonCyan,
                trackColor = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }

        // 2. MAIN BROWSER STAGE (Persistent GeckoView + Landing Page Overlay + On-Demand Floating Pill)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Keep GeckoView stably mounted so switching tabs/landing page never detaches brokenly
            if (activeTab != null) {
                AndroidView(
                    factory = { ctx -> GeckoView(ctx) },
                    update = { gv ->
                        if (gv.session !== activeTab.session) {
                            runCatching { gv.releaseSession() }
                            gv.setSession(activeTab.session)
                        }
                    },
                    onRelease = { gv ->
                        runCatching { gv.releaseSession() }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Aesthetic Glass New Tab Landing Page overlaid cleanly whenever tab is on musec://newtab
            if (isNewTab) {
                NewTabDashboard(
                    db = db,
                    isPrivate = activeTab?.isPrivate == true,
                    isAndroidTv = isAndroidTv,
                    kidsShieldActive = shieldConfig.isKidsPresetActive,
                    onNavigate = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        manager.navigateActiveTab(it)
                    },
                    onOpenShield = { onSetSheet(ActiveToolSheet.SHIELD) },
                    onOpenBurner = { onSetSheet(ActiveToolSheet.BURNER) }
                )
            } else if (activeSheet == ActiveToolSheet.NONE) {
                // Compact On-Demand Floating Button (Only scrapes prices/reviews when user taps it!)
                Surface(
                    color = Color(0xEB181B2A),
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .tvFocusBorder(RoundedCornerShape(99.dp))
                        .clickable { onSetSheet(ActiveToolSheet.PRICE_AND_REVIEWS) }
                ) {
                    Text(
                        text = "🏷️ Price & Reviews",
                        color = MuSecColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            // Glass Modal Sheet Overlay for Active Tool / YouTube Kids Shield
            if (activeSheet != ActiveToolSheet.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xA6000000))
                        .clickable { onSetSheet(ActiveToolSheet.NONE) }
                )
                Surface(
                    color = MuSecColors.SurfaceGlass,
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.88f)
                        .imePadding()
                ) {
                    when (activeSheet) {
                        ActiveToolSheet.SHIELD -> ShieldPanelContent(
                            manager = manager,
                            config = shieldConfig,
                            stats = shieldStats,
                            legitimacy = legitimacy,
                            onRunDarkPattern = { scope.launch { manager.runPageCommand("run_dark_pattern") } },
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.TABS -> TabsSheet(
                            tabs = tabs,
                            activeTabId = activeTabId,
                            onSelect = { manager.selectTab(it) },
                            onCloseTab = { manager.closeTab(it) },
                            onNewTab = { priv ->
                                manager.createTab(GeckoBrowserManager.NEWTAB_URL, priv)
                                onSetSheet(ActiveToolSheet.NONE)
                            },
                            onWipePrivate = { manager.wipeAllPrivateTabs() },
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.TOOLS_HUB -> ToolsHubSheet(
                            onOpenSheet = { onSetSheet(it) },
                            onEnterPip = { onSetSheet(ActiveToolSheet.NONE); onEnterPip() },
                            onNewPrivateTab = {
                                manager.createTab(GeckoBrowserManager.NEWTAB_URL, true)
                                onSetSheet(ActiveToolSheet.NONE)
                            },
                            onWipePrivate = {
                                manager.wipeAllPrivateTabs()
                                onSetSheet(ActiveToolSheet.NONE)
                            },
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.BURNER -> BurnerMailSheet(onClose = { onSetSheet(ActiveToolSheet.NONE) })
                        ActiveToolSheet.PRICE_AND_REVIEWS -> PriceAndReviewSheet(
                            manager = manager,
                            initialQuery = activeTab?.title.orEmpty(),
                            onOpenUrl = { manager.navigateActiveTab(it) },
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.READER -> ReaderModeSheet(
                            manager = manager,
                            currentUrl = currentUrl,
                            onOpenArchive = { manager.navigateActiveTab(it) },
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.TRANSLATE -> TranslatorSheet(
                            manager = manager,
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.VAULT -> VaultSheet(
                            vault = vault,
                            unlocked = vaultUnlocked,
                            onRequestBiometricUnlock = onRequestBiometricUnlock,
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.HISTORY -> HistoryAndBookmarksSheet(
                            history = db.getHistory(),
                            bookmarks = db.getBookmarks(),
                            onNavigate = { manager.navigateActiveTab(it) },
                            onClearHistory = { db.clearHistory(); onSetSheet(ActiveToolSheet.NONE) },
                            onClose = { onSetSheet(ActiveToolSheet.NONE) }
                        )
                        ActiveToolSheet.NONE -> Unit
                    }
                }
            }
        }

        // 3. PHONE BOTTOM NAVIGATION & SHIELD BAR (Large thumb-friendly 56dp controls!)
        if (!isAndroidTv) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(barBg)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButtonPill("◀", enabled = activeTab?.canGoBack == true) { activeTab?.session?.goBack() }
                ActionButtonPill("▶", enabled = activeTab?.canGoForward == true) { activeTab?.session?.goForward() }
                ActionButtonPill("⌂") { manager.navigateActiveTab(GeckoBrowserManager.NEWTAB_URL) }
                ShieldToolbarPill(
                    ytEnabled = shieldConfig.ytEnabled,
                    kidsPreset = shieldConfig.isKidsPresetActive,
                    totalBlocked = shieldStats.totalBlocked,
                    activeFilters = shieldConfig.activeFilterCount,
                    onClick = {
                        onSetSheet(if (activeSheet == ActiveToolSheet.SHIELD) ActiveToolSheet.NONE else ActiveToolSheet.SHIELD)
                    }
                )
                ActionButtonPill("🗂 ${tabs.size}") {
                    onSetSheet(if (activeSheet == ActiveToolSheet.TABS) ActiveToolSheet.NONE else ActiveToolSheet.TABS)
                }
                ActionButtonPill("☰") {
                    onSetSheet(if (activeSheet == ActiveToolSheet.TOOLS_HUB) ActiveToolSheet.NONE else ActiveToolSheet.TOOLS_HUB)
                }
            }
        }
    }
}

@Composable
private fun ShieldToolbarPill(
    ytEnabled: Boolean,
    kidsPreset: Boolean,
    totalBlocked: Int,
    activeFilters: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(
                when {
                    !ytEnabled -> Color(0x22FFFFFF)
                    kidsPreset -> Color(0x3834D399)
                    else -> Color(0x2E38BDF8)
                }
            )
            .tvFocusBorder(
                shape = RoundedCornerShape(99.dp),
                focusedColor = if (kidsPreset) MuSecColors.KidsEmerald else MuSecColors.NeonCyan
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("🛡️", fontSize = 15.sp)
        Text(
            text = when {
                !ytEnabled -> "OFF"
                kidsPreset -> "Kids"
                totalBlocked > 0 -> "$totalBlocked"
                else -> "${activeFilters}F"
            },
            color = if (kidsPreset) MuSecColors.KidsEmerald else MuSecColors.NeonCyan,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ActionButtonPill(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0x1EFFFFFF),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .tvFocusBorder(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 13.dp)
        ) {
            Text(
                text = label,
                color = if (enabled) MuSecColors.TextPrimary else Color(0x55A9B2C8),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
        }
    }
}
