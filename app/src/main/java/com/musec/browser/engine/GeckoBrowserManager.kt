package com.musec.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.musec.browser.data.MuSecDatabase
import com.musec.browser.data.ShieldStats
import com.musec.browser.data.YouTubeShieldConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val session: GeckoSession,
    val isPrivate: Boolean = false,
    val url: String = "musec://newtab",
    val title: String = "New Tab",
    val progress: Int = 100,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

class GeckoBrowserManager(
    private val context: Context,
    val db: MuSecDatabase,
    val isAndroidTv: Boolean
) {
    companion object {
        private const val TAG = "MuSecGecko"
        private const val EXTENSION_URI = "resource://android/assets/musec_extension/"
        private const val EXTENSION_ID = "musec-shield@musec.browser"
        private const val NATIVE_APP_NAME = "musec_bridge"
        const val NEWTAB_URL = "musec://newtab"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime: GeckoRuntime = GeckoRuntime.create(
        context.applicationContext,
        GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .consoleOutput(false)
            .build()
    )

    private var extensionPort: WebExtension.Port? = null
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _shieldConfig = MutableStateFlow(db.loadShieldConfig())
    val shieldConfig: StateFlow<YouTubeShieldConfig> = _shieldConfig.asStateFlow()

    private val _shieldStats = MutableStateFlow(ShieldStats())
    val shieldStats: StateFlow<ShieldStats> = _shieldStats.asStateFlow()

    private val _searchEngine = MutableStateFlow("google")
    val searchEngine: StateFlow<String> = _searchEngine.asStateFlow()

    init {
        installBuiltInShieldExtension()
        createTab(url = NEWTAB_URL, isPrivate = false)
    }

    private fun installBuiltInShieldExtension() {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension != null) {
                        mainHandler.post {
                            extension.setMessageDelegate(messageDelegate, NATIVE_APP_NAME)
                        }
                    }
                },
                { err ->
                    Log.e(TAG, "Failed to install MuSec built-in WebExtension", err)
                }
            )
    }

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            extensionPort = port
            port.setDelegate(portDelegate)
            pushConfigToExtension(_shieldConfig.value)
        }
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = message as? JSONObject ?: return
            when (json.optString("type")) {
                "extension_ready" -> pushConfigToExtension(_shieldConfig.value)
                "telemetry" -> {
                    val kind = json.optString("kind")
                    _shieldStats.update { s ->
                        when (kind) {
                            "short" -> s.copy(shortsBlocked = s.shortsBlocked + 1)
                            "yt_ad" -> s.copy(ytAdsSkipped = s.ytAdsSkipped + 1)
                            "reel" -> s.copy(reelsBlocked = s.reelsBlocked + 1)
                            "video" -> s.copy(videosFiltered = s.videosFiltered + 1)
                            else -> s.copy(adsBlocked = s.adsBlocked + 1)
                        }
                    }
                }
                "page_command_result" -> {
                    val reqId = json.optString("requestId")
                    val deferred = pendingCommands.remove(reqId)
                    deferred?.complete(json.optJSONObject("payload") ?: JSONObject())
                }
            }
        }
    }

    private fun pushConfigToExtension(config: YouTubeShieldConfig) {
        val msg = JSONObject().apply {
            put("type", "set_config")
            put("config", config.toExtensionConfigJson())
        }
        try {
            extensionPort?.postMessage(msg)
        } catch (e: Exception) {
            Log.w(TAG, "Could not push shield config to extension yet", e)
        }
    }

    suspend fun runPageCommand(command: String): JSONObject? {
        val port = extensionPort ?: return null
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pendingCommands[reqId] = deferred
        val msg = JSONObject().apply {
            put("type", "page_command")
            put("command", command)
            put("requestId", reqId)
        }
        mainHandler.post {
            try {
                port.postMessage(msg)
            } catch (_: Exception) {
                pendingCommands.remove(reqId)?.complete(JSONObject())
            }
        }
        return withTimeoutOrNull(4500L) { deferred.await() }
    }

    // --- Shield Config Mutations ---
    fun updateShieldConfig(transform: (YouTubeShieldConfig) -> YouTubeShieldConfig): Boolean {
        val current = _shieldConfig.value
        if (current.locked) return false
        val updated = transform(current)
        _shieldConfig.value = updated
        db.saveShieldConfig(updated)
        pushConfigToExtension(updated)
        return true
    }

    fun applyKidsSafePreset(): Boolean = updateShieldConfig { cfg ->
        cfg.copy(
            adShieldEnabled = true,
            ytEnabled = true,
            blockShorts = true,
            blockAds = true,
            blockKidsUnsafeKeywords = true,
            blockComments = true,
            blockRecommendations = true,
            blockAutoplay = true,
            enforceRestrictedMode = true,
            blockSocialReels = true
        )
    }

    fun setParentalPin(pin: String): Boolean {
        val current = _shieldConfig.value
        if (current.locked) return false
        val clean = pin.trim()
        if (clean.isNotEmpty() && clean.length < 4) return false
        db.setParentalPin(clean)
        val hasPin = clean.isNotEmpty()
        _shieldConfig.value = current.copy(hasPin = hasPin, locked = hasPin)
        return true
    }

    fun unlockWithPin(pin: String): Boolean {
        if (!db.verifyParentalPin(pin)) return false
        _shieldConfig.value = _shieldConfig.value.copy(locked = false)
        return true
    }

    fun lockWithExistingPin() {
        if (_shieldConfig.value.hasPin) {
            _shieldConfig.value = _shieldConfig.value.copy(locked = true)
        }
    }

    fun setSearchEngine(engine: String) {
        _searchEngine.value = engine
    }

    // --- Tab Management ---
    fun getActiveTab(): BrowserTab? {
        val id = _activeTabId.value ?: return _tabs.value.firstOrNull()
        return _tabs.value.find { it.id == id } ?: _tabs.value.firstOrNull()
    }

    fun createTab(url: String = NEWTAB_URL, isPrivate: Boolean = false): BrowserTab {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .userAgentMode(
                if (isAndroidTv) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            )
            .build()
        val session = GeckoSession(settings)
        val tabId = UUID.randomUUID().toString()

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                newUrl: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val safeUrl = newUrl ?: return
                updateTab(tabId) { it.copy(url = safeUrl) }
                if (!isPrivate && safeUrl.startsWith("http")) {
                    val currentTitle = _tabs.value.find { it.id == tabId }?.title ?: safeUrl
                    db.recordVisit(safeUrl, currentTitle)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                updateTab(tabId) { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                updateTab(tabId) { it.copy(canGoForward = canGoForward) }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Open target="_blank" links cleanly inside MuSec tab strip instead of popups
                val newTab = createTab(url = uri, isPrivate = isPrivate)
                return GeckoResult.fromValue(newTab.session)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val safeTitle = title?.takeIf { it.isNotBlank() } ?: return
                updateTab(tabId) { it.copy(title = safeTitle) }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                updateTab(tabId) { it.copy(progress = progress) }
            }
        }

        session.open(runtime)
        val tab = BrowserTab(
            id = tabId,
            session = session,
            isPrivate = isPrivate,
            url = url,
            title = if (url == NEWTAB_URL) (if (isPrivate) "Private Tab" else "New Tab") else url
        )

        _tabs.update { it + tab }
        _activeTabId.value = tabId

        if (url != NEWTAB_URL && url.isNotBlank()) {
            session.loadUri(resolveInputToUrl(url))
        }
        return tab
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    fun closeTab(tabId: String) {
        val list = _tabs.value
        val target = list.find { it.id == tabId } ?: return
        runCatching { target.session.close() }
        val remaining = list.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            _tabs.value = emptyList()
            createTab(NEWTAB_URL, false)
        } else {
            _tabs.value = remaining
            if (_activeTabId.value == tabId) {
                _activeTabId.value = remaining.last().id
            }
        }
    }

    /**
     * Mobile equivalent of MuSec's Laptop Lid-Shut Guard:
     * Auto-closes all InPrivate tabs and wipes private browsing data when the phone/TV screen locks.
     */
    fun wipeAllPrivateTabs() {
        val current = _tabs.value
        val privateTabs = current.filter { it.isPrivate }
        if (privateTabs.isEmpty()) return

        privateTabs.forEach { runCatching { it.session.close() } }
        val normalTabs = current.filterNot { it.isPrivate }
        if (normalTabs.isEmpty()) {
            _tabs.value = emptyList()
            createTab(NEWTAB_URL, false)
        } else {
            _tabs.value = normalTabs
            if (normalTabs.none { it.id == _activeTabId.value }) {
                _activeTabId.value = normalTabs.last().id
            }
        }
    }

    fun navigateActiveTab(rawInput: String) {
        val active = getActiveTab() ?: createTab()
        val trimmed = rawInput.trim()
        if (trimmed.equals(NEWTAB_URL, ignoreCase = true)) {
            updateTab(active.id) { it.copy(url = NEWTAB_URL, title = if (it.isPrivate) "Private Tab" else "New Tab", progress = 100) }
            return
        }
        val resolved = resolveInputToUrl(trimmed)
        updateTab(active.id) { it.copy(url = resolved) }
        active.session.loadUri(resolved)
    }

    fun resolveInputToUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (!trimmed.contains(" ") && trimmed.contains(".") && !trimmed.startsWith(".")) {
            return "https://$trimmed"
        }
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return when (_searchEngine.value) {
            "bing" -> "https://www.bing.com/search?q=$encoded"
            "duckduckgo" -> "https://duckduckgo.com/?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
    }

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        _tabs.update { list ->
            list.map { if (it.id == tabId) transform(it) else it }
        }
    }

    fun shutdown() {
        extensionPort?.disconnect()
        _tabs.value.forEach { runCatching { it.session.close() } }
    }
}
