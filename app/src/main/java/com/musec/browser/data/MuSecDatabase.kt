package com.musec.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class MuSecDatabase(context: Context) : SQLiteOpenHelper(context, "musec_browser.db", null, 1) {

    companion object {
        private const val META_SHIELD_CONFIG = "youtube_shield_config_v1"
        private const val META_PIN_HASH = "youtube_shield_pin_hash"
        private const val META_PIN_SALT = "youtube_shield_pin_salt"
        private const val META_QUICK_NOTES = "ntp_quick_notes"
        private const val META_FREQUENT_SITES = "ntp_frequent_sites_v1"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                visited_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT UNIQUE NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS vault (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site TEXT NOT NULL,
                username TEXT NOT NULL,
                ciphertext_b64 TEXT NOT NULL,
                iv_b64 TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Initial v1 schema
    }

    fun getMeta(key: String): String? {
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun setMeta(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteMeta(key: String) {
        writableDatabase.delete("meta", "key = ?", arrayOf(key))
    }

    // --- History ---
    fun recordVisit(url: String, title: String) {
        if (url.isBlank() || url.startsWith("musec://") || url.startsWith("about:")) return
        val cv = ContentValues().apply {
            put("url", url)
            put("title", title.ifBlank { url })
            put("visited_at", System.currentTimeMillis())
        }
        writableDatabase.insert("history", null, cv)
    }

    fun getHistory(limit: Int = 50): List<HistoryItem> {
        val out = mutableListOf<HistoryItem>()
        readableDatabase.rawQuery(
            "SELECT id, url, title, visited_at FROM history ORDER BY visited_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(HistoryItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        return out
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    // --- Bookmarks ---
    fun getBookmarks(): List<BookmarkItem> {
        val out = mutableListOf<BookmarkItem>()
        readableDatabase.rawQuery("SELECT id, url, title FROM bookmarks ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(BookmarkItem(c.getLong(0), c.getString(1), c.getString(2)))
            }
        }
        return out
    }

    fun isBookmarked(url: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)).use { c ->
            return c.moveToFirst()
        }
    }

    fun toggleBookmark(url: String, title: String): Boolean {
        if (isBookmarked(url)) {
            writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
            return false
        }
        val cv = ContentValues().apply {
            put("url", url)
            put("title", title.ifBlank { url })
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    // --- Quick Notes & Frequent Sites ---
    fun getQuickNotes(): String = getMeta(META_QUICK_NOTES) ?: "Good morning, Creative."
    fun saveQuickNotes(text: String) = setMeta(META_QUICK_NOTES, text)

    fun getFrequentSites(): List<FrequentSite> {
        val raw = getMeta(META_FREQUENT_SITES)
        if (raw.isNullOrBlank()) {
            return listOf(
                FrequentSite("YouTube", "https://www.youtube.com", 0xFFEF4444),
                FrequentSite("Google", "https://www.google.com", 0xFF38BDF8),
                FrequentSite("GitHub", "https://github.com", 0xFF6366F1),
                FrequentSite("Wikipedia", "https://en.wikipedia.org", 0xFF94A3B8),
                FrequentSite("SVT Nyheter", "https://www.svt.se/nyheter", 0xFF10B981),
                FrequentSite("Reddit", "https://www.reddit.com", 0xFFF97316)
            )
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                FrequentSite(
                    obj.optString("title", "Site"),
                    obj.optString("url", "https://example.com"),
                    obj.optLong("color", 0xFF38BDF8)
                )
            }
        }.getOrElse { emptyList() }
    }

    fun saveFrequentSites(sites: List<FrequentSite>) {
        val arr = JSONArray()
        sites.forEach { s ->
            arr.put(JSONObject().apply {
                put("title", s.title)
                put("url", s.url)
                put("color", s.badgeColorHex)
            })
        }
        setMeta(META_FREQUENT_SITES, arr.toString())
    }

    // --- YouTube Kids & Content Shield Config + Parental PIN ---
    fun loadShieldConfig(): YouTubeShieldConfig {
        val hasPin = !getMeta(META_PIN_HASH).isNullOrBlank() && !getMeta(META_PIN_SALT).isNullOrBlank()
        val raw = getMeta(META_SHIELD_CONFIG) ?: return YouTubeShieldConfig(hasPin = hasPin, locked = hasPin)
        return runCatching {
            val j = JSONObject(raw)
            fun jsonList(key: String): List<String> {
                val arr = j.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            YouTubeShieldConfig(
                adShieldEnabled = j.optBoolean("adShieldEnabled", true),
                darkPatternAuto = j.optBoolean("darkPatternAuto", true),
                ytEnabled = j.optBoolean("ytEnabled", true),
                blockShorts = j.optBoolean("blockShorts", true),
                blockAds = j.optBoolean("blockAds", true),
                blockKidsUnsafeKeywords = j.optBoolean("blockKidsUnsafeKeywords", true),
                blockComments = j.optBoolean("blockComments", false),
                blockRecommendations = j.optBoolean("blockRecommendations", false),
                blockHomeFeed = j.optBoolean("blockHomeFeed", false),
                blockAutoplay = j.optBoolean("blockAutoplay", true),
                enforceRestrictedMode = j.optBoolean("enforceRestrictedMode", false),
                blockSocialReels = j.optBoolean("blockSocialReels", false),
                whitelistOnlyMode = j.optBoolean("whitelistOnlyMode", false),
                customBlockedKeywords = jsonList("customBlockedKeywords"),
                customBlockedChannels = jsonList("customBlockedChannels"),
                allowedChannels = jsonList("allowedChannels"),
                customBlockedDomains = jsonList("customBlockedDomains"),
                hasPin = hasPin,
                locked = hasPin
            )
        }.getOrElse { YouTubeShieldConfig(hasPin = hasPin, locked = hasPin) }
    }

    fun saveShieldConfig(cfg: YouTubeShieldConfig) {
        val j = JSONObject().apply {
            put("adShieldEnabled", cfg.adShieldEnabled)
            put("darkPatternAuto", cfg.darkPatternAuto)
            put("ytEnabled", cfg.ytEnabled)
            put("blockShorts", cfg.blockShorts)
            put("blockAds", cfg.blockAds)
            put("blockKidsUnsafeKeywords", cfg.blockKidsUnsafeKeywords)
            put("blockComments", cfg.blockComments)
            put("blockRecommendations", cfg.blockRecommendations)
            put("blockHomeFeed", cfg.blockHomeFeed)
            put("blockAutoplay", cfg.blockAutoplay)
            put("enforceRestrictedMode", cfg.enforceRestrictedMode)
            put("blockSocialReels", cfg.blockSocialReels)
            put("whitelistOnlyMode", cfg.whitelistOnlyMode)
            put("customBlockedKeywords", JSONArray(cfg.customBlockedKeywords))
            put("customBlockedChannels", JSONArray(cfg.customBlockedChannels))
            put("allowedChannels", JSONArray(cfg.allowedChannels))
            put("customBlockedDomains", JSONArray(cfg.customBlockedDomains))
        }
        setMeta(META_SHIELD_CONFIG, j.toString())
    }

    private fun pbkdf2Hex(pin: String, saltBytes: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), saltBytes, 65536, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun setParentalPin(pin: String) {
        val clean = pin.trim()
        if (clean.isEmpty()) {
            deleteMeta(META_PIN_HASH)
            deleteMeta(META_PIN_SALT)
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        setMeta(META_PIN_SALT, saltHex)
        setMeta(META_PIN_HASH, pbkdf2Hex(clean, salt))
    }

    fun verifyParentalPin(pin: String): Boolean {
        val storedHash = getMeta(META_PIN_HASH) ?: return true
        val saltHex = getMeta(META_PIN_SALT) ?: return true
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val candidate = pbkdf2Hex(pin.trim(), salt)
        return candidate.equals(storedHash, ignoreCase = true)
    }
}
