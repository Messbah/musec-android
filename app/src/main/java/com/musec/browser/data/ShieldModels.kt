package com.musec.browser.data

import org.json.JSONArray
import org.json.JSONObject

val BUILTIN_KIDS_UNSAFE_KEYWORDS = listOf(
    "skibidi", "elsagate", "huggy wuggy", "siren head", "poppy playtime scary",
    "jumpscare", "creepypasta", "gore", "murder", "gambling", "casino", "betting",
    "slot machine", "18+", "nsfw", "onlyfans", "explicit", "prank gone wrong",
    "brainrot", "italian brainrot", "tung tung", "sprunki", "slenderman",
    "horror movie", "try not to get scared", "vaping", "smoking weed", "drunk prank"
)

data class YouTubeShieldConfig(
    val adShieldEnabled: Boolean = true,
    val darkPatternAuto: Boolean = true,
    val ytEnabled: Boolean = true,
    val blockShorts: Boolean = true,
    val blockAds: Boolean = true,
    val blockKidsUnsafeKeywords: Boolean = true,
    val blockComments: Boolean = false,
    val blockRecommendations: Boolean = false,
    val blockHomeFeed: Boolean = false,
    val blockAutoplay: Boolean = true,
    val enforceRestrictedMode: Boolean = false,
    val blockSocialReels: Boolean = false,
    val whitelistOnlyMode: Boolean = false,
    val customBlockedKeywords: List<String> = emptyList(),
    val customBlockedChannels: List<String> = emptyList(),
    val allowedChannels: List<String> = emptyList(),
    val customBlockedDomains: List<String> = emptyList(),
    val hasPin: Boolean = false,
    val locked: Boolean = false
) {
    val isKidsPresetActive: Boolean
        get() = ytEnabled && blockShorts && blockAds && blockKidsUnsafeKeywords &&
            blockComments && blockRecommendations && enforceRestrictedMode

    val activeFilterCount: Int
        get() {
            if (!ytEnabled) return 0
            var c = 0
            if (blockShorts) c++
            if (blockAds) c++
            if (blockKidsUnsafeKeywords) c++
            if (blockComments) c++
            if (blockRecommendations) c++
            if (blockHomeFeed) c++
            if (blockAutoplay) c++
            if (enforceRestrictedMode) c++
            if (blockSocialReels) c++
            if (whitelistOnlyMode) c++
            c += customBlockedKeywords.size + customBlockedChannels.size
            return c
        }

    fun toExtensionConfigJson(): JSONObject {
        val effectiveKeywords = buildList {
            if (blockKidsUnsafeKeywords) addAll(BUILTIN_KIDS_UNSAFE_KEYWORDS)
            addAll(customBlockedKeywords)
        }.map { it.lowercase() }.distinct()

        return JSONObject().apply {
            put("adShieldEnabled", adShieldEnabled)
            put("darkPatternAuto", darkPatternAuto)
            put("customBlockedDomains", JSONArray(customBlockedDomains))
            put("youtubeShield", JSONObject().apply {
                put("enabled", ytEnabled)
                put("blockShorts", blockShorts)
                put("blockAds", blockAds)
                put("blockKidsUnsafeKeywords", blockKidsUnsafeKeywords)
                put("blockComments", blockComments)
                put("blockRecommendations", blockRecommendations)
                put("blockHomeFeed", blockHomeFeed)
                put("blockAutoplay", blockAutoplay)
                put("enforceRestrictedMode", enforceRestrictedMode)
                put("blockSocialReels", blockSocialReels)
                put("whitelistOnlyMode", whitelistOnlyMode)
                put("blockedKeywords", JSONArray(effectiveKeywords))
                put("blockedChannels", JSONArray(customBlockedChannels.map { it.lowercase().removePrefix("@") }))
                put("allowedChannels", JSONArray(allowedChannels.map { it.lowercase().removePrefix("@") }))
            })
        }
    }
}

data class ShieldStats(
    val adsBlocked: Int = 0,
    val shortsBlocked: Int = 0,
    val ytAdsSkipped: Int = 0,
    val videosFiltered: Int = 0,
    val reelsBlocked: Int = 0
) {
    val totalBlocked: Int
        get() = adsBlocked + shortsBlocked + ytAdsSkipped + videosFiltered + reelsBlocked
}

data class HistoryItem(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long
)

data class BookmarkItem(
    val id: Long,
    val url: String,
    val title: String
)

data class FrequentSite(
    val title: String,
    val url: String,
    val badgeColorHex: Long
)

data class NewsBriefingItem(
    val region: String,
    val source: String,
    val headline: String,
    val summary: String,
    val url: String
)

data class BurnerIdentity(
    val address: String,
    val token: String,
    val provider: String,
    val personaName: String
)

data class BurnerMessage(
    val id: String,
    val from: String,
    val subject: String,
    val intro: String,
    val createdAt: String
)

data class PriceOffer(
    val store: String,
    val formattedPrice: String,
    val numericPrice: Double,
    val url: String
)

data class ReviewAuditResult(
    val grade: String,
    val trustScore: Int,
    val verdict: String,
    val signals: List<String>
)

data class LegitimacyReport(
    val grade: String,
    val score: Int,
    val host: String,
    val verdict: String,
    val signals: List<String>
)

data class VaultEntry(
    val id: Long,
    val site: String,
    val username: String,
    val updatedAt: Long
)

data class ReaderArticle(
    val title: String,
    val url: String,
    val method: String,
    val paragraphs: List<String>
)
