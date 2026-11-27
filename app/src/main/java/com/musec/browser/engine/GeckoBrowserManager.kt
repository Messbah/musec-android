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
import org.mozilla.geckoview.AllowOrDeny
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
    val url: String = GeckoBrowserManager.NEWTAB_URL,
    val title: String = "New Tab",
    val progress: Int = 100,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
)

class GeckoBrowserManager(
    context: Context,
    val db: MuSecDatabase,
    val isAndroidTv: Boolean
) {
    companion object {
        private const val TAG = "MuSecGecko"
        private const val EXTENSION_URI = "resource://android/assets/musec_extension/"
        private const val EXTENSION_ID = "musec-shield@musec.browser"
        private const val NATIVE_APP_NAME = "musec_bridge"
        const val NEWTAB_URL = "musec://newtab"

        @Volatile
        private var sharedRuntime: GeckoRuntime? = null

        private fun getOrCreateRuntime(appContext: Context): GeckoRuntime {
            return sharedRuntime ?: synchronized(this) {
                sharedRuntime ?: GeckoRuntime.create(
                    appContext,
                    GeckoRuntimeSettings.Builder()
                        .javaScriptEnabled(true)
                        .consoleOutput(false)
                        .build()
                ).also { sharedRuntime = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime: GeckoRuntime = getOrCreateRuntime(context.applicationContext)
    private val popupBlockedDomains: Set<String> = loadBravePopupDomains(context.applicationContext)

    private var extensionPort: WebExtension.Port? = null
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private fun loadBravePopupDomains(appContext: Context): Set<String> {
        val out = HashSet<String>(3000)
        out.addAll(
            listOf(
                "popads.net", "popcash.net", "propellerads.com", "clickadu.com", "hilltopads.net",
                "adsterra.com", "monetag.com", "exoclick.com", "exosrv.com", "realsrv.com",
                "magsrv.com", "juicyads.com", "trafficjunky.net", "tsyndicate.com", "onclkds.com",
                "acscdn.com", "profitablegate.com", "effectivegatecpm.com", "highperformanceformat.com",
                "topcreativeformat.com", "doubleclick.net", "googlesyndication.com", "googleadservices.com"
            )
        )
        runCatching {
            appContext.assets.open("musec_extension/brave-shields-compiled.json").bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val popArr = json.optJSONArray("popupBlockedDomains")
                if (popArr != null) {
                    for (i in 0 until popArr.length()) {
                        val d = popArr.optString(i).lowercase().trim()
                        if (d.isNotEmpty()) out.add(d)
                    }
                }
            }
        }
        return out
    }

    private fun getRootDomain(urlStr: String?): String {
        if (urlStr.isNullOrBlank()) return ""
        val host = runCatching { java.net.URI(urlStr).host?.lowercase()?.removeSuffix(".") }.getOrNull() ?: return ""
        val parts = host.split(".")
        if (parts.size <= 2) return host
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (Regex("^(co|com|org|net|gov|edu|ac)\\.[a-z]{2}$").matches(lastTwo) && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    private fun isAuthOrBankUrl(urlStr: String?): Boolean {
        if (urlStr.isNullOrBlank()) return false
        return Regex(
            "(?:accounts\\.google\\.com|login\\.microsoftonline\\.com|appleid\\.apple\\.com|bankid|frejaeid|swedbank|seb\\.se|nordea|handelsbanken|klarna|paypal|stripe|/oauth2?/|/authorize\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(urlStr)
    }

    private fun isMaliciousPopupUrl(urlStr: String?): Boolean {
        if (urlStr.isNullOrBlank() || urlStr == "about:blank") return true
        val uri = runCatching { java.net.URI(urlStr) }.getOrNull() ?: return true
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return true
        var cursor = host
        while (cursor.isNotEmpty()) {
            if (popupBlockedDomains.contains(cursor)) return true
            val dot = cursor.indexOf('.')
            if (dot == -1) break
            cursor = cursor.substring(dot + 1)
        }
        val path = uri.rawPath?.lowercase().orEmpty()
        val query = uri.rawQuery?.lowercase().orEmpty()
        if (Regex("(?:popunder|pop_under|/adclick|/adserver|/pagead/|clicktrack|malvert)", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
            return true
        }
        if (Regex("(?:popunder|clickid=|aff_id=|offer_id=|zoneid=|adid=|bannerid=)", RegexOption.IGNORE_CASE).containsMatchIn(query)) {
            return true
        }
        return false
    }

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

    suspend fun runPageCommand(command: String, args: JSONObject? = null): JSONObject? {
        val port = extensionPort ?: return null
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pendingCommands[reqId] = deferred
        val msg = JSONObject().apply {
            put("type", "page_command")
            put("command", command)
            put("requestId", reqId)
            if (args != null) put("args", args)
        }
        mainHandler.post {
            try {
                port.postMessage(msg)
            } catch (_: Exception) {
                pendingCommands.remove(reqId)?.complete(JSONObject())
            }
        }
        return withTimeoutOrNull(8000L) { deferred.await() }
    }

    suspend fun translateWholeActivePage(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val extracted = runPageCommand("get_page_text_nodes") ?: return 0
        val arr = extracted.optJSONArray("items") ?: return 0
        val pageTitle = extracted.optString("title", "")
        val items = mutableListOf<Pair<Int, String>>()
        for (k in 0 until arr.length()) {
            val obj = arr.optJSONObject(k) ?: continue
            val idx = obj.optInt("i", -1)
            val txt = obj.optString("t", "")
            if (idx >= 0 && txt.isNotBlank()) items.add(idx to txt)
        }
        if (items.isEmpty()) return 0

        val translatedTitle = if (pageTitle.isNotBlank()) MuSecToolkit.translateToEnglish(pageTitle) else ""
        val translatedPairs = MuSecToolkit.translateBatchToEnglish(items, onProgress)
        val payloadArr = org.json.JSONArray()
        for ((idx, translatedText) in translatedPairs) {
            payloadArr.put(JSONObject().apply {
                put("i", idx)
                put("t", translatedText)
            })
        }
        val res = runPageCommand(
            "apply_page_translations",
            JSONObject().apply {
                put("translations", payloadArr)
                put("translatedTitle", translatedTitle)
            }
        )
        return res?.optInt("applied", translatedPairs.size) ?: translatedPairs.size
    }

    suspend fun restoreActivePageTranslation(): Int {
        val res = runPageCommand("restore_page_translation")
        return res?.optInt("restored", 0) ?: 0
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

    fun createTab(
        url: String = NEWTAB_URL,
        isPrivate: Boolean = false,
        openImmediately: Boolean = true
    ): BrowserTab {
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
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny> {
                val adShieldOn = _shieldConfig.value.adShieldEnabled
                val targetUri = request.uri
                val isAuth = isAuthOrBankUrl(targetUri)

                // 1. Block known Brave Shields popup/popunder/ad-redirect URLs immediately
                if (adShieldOn && !isAuth && isMaliciousPopupUrl(targetUri)) {
                    _shieldStats.update { it.copy(adsBlocked = it.adsBlocked + 1) }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                // 2. Guard new-window / target="_blank" requests against video-player popups
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    if (adShieldOn && !isAuth) {
                        val currentTabUrl = _tabs.value.find { it.id == tabId }?.url
                        val tabRoot = getRootDomain(currentTabUrl)
                        val triggerRoot = getRootDomain(request.triggerUri)
                        val isEmbeddedIframePopup = triggerRoot.isNotEmpty() && tabRoot.isNotEmpty() && triggerRoot != tabRoot

                        // Block popups spawned without user gesture, redirect traps, or embedded video-player iframes
                        if (!request.hasUserGesture || request.isRedirect || isEmbeddedIframePopup || isMaliciousPopupUrl(targetUri)) {
                            _shieldStats.update { it.copy(adsBlocked = it.adsBlocked + 1) }
                            return GeckoResult.fromValue(AllowOrDeny.DENY)
                        }
                    }
                    if (targetUri.startsWith("http", ignoreCase = true)) {
                        mainHandler.post {
                            createTab(url = targetUri, isPrivate = isPrivate, openImmediately = true)
                        }
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(
                session: GeckoSession,
                newUrl: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val safeUrl = newUrl?.trim() ?: return
                if (safeUrl == "about:blank" || safeUrl.isEmpty()) return

                updateTab(tabId) { it.copy(url = safeUrl) }
                if (!isPrivate && safeUrl.startsWith("http")) {
                    val currentTitle = _tabs.value.find { it.id == tabId }?.title ?: safeUrl
                    runCatching { db.recordVisit(safeUrl, currentTitle) }
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                updateTab(tabId) { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                updateTab(tabId) { it.copy(canGoForward = canGoForward) }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val adShieldOn = _shieldConfig.value.adShieldEnabled
                // Block scripted popup windows / about:blank popunders / ad domains when playing videos
                if (adShieldOn && !isAuthOrBankUrl(uri)) {
                    _shieldStats.update { it.copy(adsBlocked = it.adsBlocked + 1) }
                    return null
                }
                val newTab = createTab(url = uri.ifBlank { NEWTAB_URL }, isPrivate = isPrivate, openImmediately = false)
                return GeckoResult.fromValue(newTab.session)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val safeTitle = title?.takeIf { it.isNotBlank() } ?: return
                updateTab(tabId) { current ->
                    if (current.url == NEWTAB_URL) current else current.copy(title = safeTitle)
                }
            }

            override fun onCrash(session: GeckoSession) {
                // Recover gracefully if a content process crashes instead of closing the browser
                mainHandler.post {
                    runCatching {
                        if (!session.isOpen) session.open(runtime)
                        val currentUrl = _tabs.value.find { it.id == tabId }?.url
                        if (!currentUrl.isNullOrBlank() && currentUrl != NEWTAB_URL) {
                            session.loadUri(currentUrl)
                        }
                    }
                }
            }

            override fun onKill(session: GeckoSession) {
                // Recover gracefully if Android LMK kills the Gecko content process
                mainHandler.post {
                    runCatching {
                        if (!session.isOpen) session.open(runtime)
                        val currentUrl = _tabs.value.find { it.id == tabId }?.url
                        if (!currentUrl.isNullOrBlank() && currentUrl != NEWTAB_URL) {
                            session.loadUri(currentUrl)
                        }
                    }
                }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                updateTab(tabId) { current ->
                    if (current.url == NEWTAB_URL) current.copy(progress = 100)
                    else current.copy(progress = progress)
                }
            }
        }

        if (openImmediately && !session.isOpen) {
            runCatching { session.open(runtime) }
        }
        val tab = BrowserTab(
            id = tabId,
            session = session,
            isPrivate = isPrivate,
            url = url,
            title = if (url == NEWTAB_URL) (if (isPrivate) "Private Tab" else "New Tab") else url
        )

        _tabs.update { it + tab }
        _activeTabId.value = tabId

        if (openImmediately && url != NEWTAB_URL && url.isNotBlank()) {
            runCatching { session.loadUri(resolveInputToUrl(url)) }
        }
        return tab
    }

    fun ensureSessionOpen(session: GeckoSession) {
        if (!session.isOpen) {
            runCatching { session.open(runtime) }
        }
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
        if (trimmed.isEmpty()) return
        if (trimmed.equals(NEWTAB_URL, ignoreCase = true)) {
            runCatching { active.session.stop() }
            updateTab(active.id) {
                it.copy(
                    url = NEWTAB_URL,
                    title = if (it.isPrivate) "Private Tab" else "New Tab",
                    progress = 100
                )
            }
            return
        }
        val resolved = resolveInputToUrl(trimmed)
        updateTab(active.id) { it.copy(url = resolved, progress = 15) }
        active.session.loadUri(resolved)
    }

    fun reloadActiveTab() {
        val active = getActiveTab() ?: return
        if (active.url != NEWTAB_URL) {
            active.session.reload()
        }
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
