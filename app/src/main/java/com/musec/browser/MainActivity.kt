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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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

        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        isAndroidTv = (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        db = MuSecDatabase(this)
        vault = EncryptedVault(db)
        browserManager = GeckoBrowserManager(this, db, isAndroidTv)

        // If device has no biometric hardware (e.g., Android TV), allow Vault access directly
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
        // Android TV Remote Menu button toggles the YouTube Kids Shield / Power Tools
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
            if (active != null && active.canGoBack) {
                active.session.goBack()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MuSecColors.BgDeep)
    ) {
        // Sleek Aesthetic Glass Top Toolbar (Touch + TV D-Pad Focusable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (activeTab?.isPrivate == true) Color(0xFF23183A) else MuSecColors.SurfaceGlass)
                .padding(horizontal = if (isAndroidTv) 20.dp else 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ToolbarIconPill("◀", enabled = activeTab?.canGoBack == true) { activeTab?.session?.goBack() }
            if (isAndroidTv) {
                ToolbarIconPill("▶", enabled = activeTab?.canGoForward == true) { activeTab?.session?.goForward() }
            }
            ToolbarIconPill("⌂") { manager.navigateActiveTab(GeckoBrowserManager.NEWTAB_URL) }

            // Glass Omnibox
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0xFF151722))
                    .tvFocusBorder(RoundedCornerShape(99.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (activeTab?.isPrivate == true) "🕵️" else legitimacy.grade,
                    color = if (activeTab?.isPrivate == true) MuSecColors.NeonViolet else MuSecColors.KidsEmerald,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onSetSheet(ActiveToolSheet.SHIELD) }
                )
                BasicTextField(
                    value = omniboxText,
                    onValueChange = { omniboxText = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MuSecColors.TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(MuSecColors.NeonCyan),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (omniboxText.isNotBlank()) manager.navigateActiveTab(omniboxText)
                    }),
                    decorationBox = { inner ->
                        if (omniboxText.isEmpty()) {
                            Text("Search or enter URL...", color = MuSecColors.TextSecondary, fontSize = 13.sp)
                        }
                        inner()
                    },
                    modifier = Modifier.weight(1f)
                )
                if (!isNewTab) {
                    Text(
                        text = if (isBookmarked) "★" else "☆",
                        color = if (isBookmarked) MuSecColors.WarningAmber else MuSecColors.TextSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable {
                            isBookmarked = db.toggleBookmark(currentUrl, activeTab?.title ?: currentUrl)
                        }
                    )
                }
            }

            // Prominent Dedicated YouTube Kids & Content Shield Button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        when {
                            !shieldConfig.ytEnabled -> Color(0x22FFFFFF)
                            shieldConfig.isKidsPresetActive -> Color(0x3334D399)
                            else -> Color(0x2838BDF8)
                        }
                    )
                    .tvFocusBorder(
                        shape = RoundedCornerShape(99.dp),
                        focusedColor = if (shieldConfig.isKidsPresetActive) MuSecColors.KidsEmerald else MuSecColors.NeonCyan
                    )
                    .clickable {
                        onSetSheet(if (activeSheet == ActiveToolSheet.SHIELD) ActiveToolSheet.NONE else ActiveToolSheet.SHIELD)
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("🛡️", fontSize = 12.sp)
                Text(
                    text = when {
                        !shieldConfig.ytEnabled -> "OFF"
                        shieldConfig.isKidsPresetActive -> "Kids"
                        shieldStats.totalBlocked > 0 -> "${shieldStats.totalBlocked}"
                        else -> "${shieldConfig.activeFilterCount}F"
                    },
                    color = if (shieldConfig.isKidsPresetActive) MuSecColors.KidsEmerald else MuSecColors.NeonCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            ToolbarIconPill("${tabs.size}") {
                onSetSheet(if (activeSheet == ActiveToolSheet.TABS) ActiveToolSheet.NONE else ActiveToolSheet.TABS)
            }
            ToolbarIconPill("☰") {
                onSetSheet(if (activeSheet == ActiveToolSheet.TOOLS_HUB) ActiveToolSheet.NONE else ActiveToolSheet.TOOLS_HUB)
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
                    .height(2.5.dp)
            )
        }

        // Main Browser Stage + Optional Compact On-Demand Floating Button + Active Tool Sheet
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isNewTab) {
                NewTabDashboard(
                    db = db,
                    isPrivate = activeTab?.isPrivate == true,
                    isAndroidTv = isAndroidTv,
                    kidsShieldActive = shieldConfig.isKidsPresetActive,
                    onNavigate = { manager.navigateActiveTab(it) },
                    onOpenShield = { onSetSheet(ActiveToolSheet.SHIELD) },
                    onOpenBurner = { onSetSheet(ActiveToolSheet.BURNER) }
                )
            } else if (activeTab != null) {
                AndroidView(
                    factory = { ctx -> GeckoView(ctx) },
                    update = { gv ->
                        if (gv.session !== activeTab.session) {
                            gv.setSession(activeTab.session)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Compact On-Demand Floating Button (Only scrapes prices/reviews when user clicks!)
                if (activeSheet == ActiveToolSheet.NONE) {
                    Surface(
                        color = Color(0xE6181A28),
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
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Glass Drawer Overlay for Active Tool / Shield Sheet
            if (activeSheet != ActiveToolSheet.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000))
                        .clickable { onSetSheet(ActiveToolSheet.NONE) }
                )
                Surface(
                    color = MuSecColors.SurfaceGlass,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(top = if (isAndroidTv) 48.dp else 64.dp)
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
    }
}

@Composable
private fun ToolbarIconPill(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0x18FFFFFF),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .tvFocusBorder(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
    ) {
        Text(
            text = label,
            color = if (enabled) MuSecColors.TextPrimary else Color(0x55A9B2C8),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}
