package com.musec.browser.engine

import com.musec.browser.data.BurnerIdentity
import com.musec.browser.data.BurnerMessage
import com.musec.browser.data.LegitimacyReport
import com.musec.browser.data.NewsBriefingItem
import com.musec.browser.data.PriceOffer
import com.musec.browser.data.ReviewAuditResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

object MuSecToolkit {

    private fun httpRequest(
        urlStr: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 7000
    ): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile) Gecko/159.0 Firefox/159.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    // -------------------------------------------------------------------------
    // 1. Clean HTML/XML Entity Decoder (Fixes <p></p> tags in RSS News feeds)
    // -------------------------------------------------------------------------
    fun cleanNewsText(raw: String): String {
        var text = raw.replace("<![CDATA[", "").replace("]]>", "")
        repeat(2) {
            text = text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
        }
        return text
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // -------------------------------------------------------------------------
    // 2. World News & Sweden/EU/Global Briefing Engine
    // -------------------------------------------------------------------------
    suspend fun fetchWorldBriefing(): List<NewsBriefingItem> = withContext(Dispatchers.IO) {
        val feeds = listOf(
            Triple("Sweden", "SVT Nyheter", "https://www.svt.se/nyheter/rss.xml"),
            Triple("Europe", "BBC Europe", "https://feeds.bbci.co.uk/news/world/europe/rss.xml"),
            Triple("World", "BBC World", "https://feeds.bbci.co.uk/news/world/rss.xml"),
            Triple("Tech", "Hacker News", "https://hnrss.org/frontpage")
        )
        val itemRe = Regex("<item[\\s\\S]*?</item>", RegexOption.IGNORE_CASE)
        val titleRe = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        val descRe = Regex("<description[^>]*>([\\s\\S]*?)</description>", RegexOption.IGNORE_CASE)
        val linkRe = Regex("<link[^>]*>([\\s\\S]*?)</link>", RegexOption.IGNORE_CASE)

        val results = mutableListOf<NewsBriefingItem>()
        for ((region, source, feedUrl) in feeds) {
            runCatching {
                val (code, xml) = httpRequest(feedUrl, timeoutMs = 6000)
                if (code !in 200..299) return@runCatching
                itemRe.findAll(xml).take(4).forEach { m ->
                    val chunk = m.value
                    val title = cleanNewsText(titleRe.find(chunk)?.groupValues?.get(1) ?: "")
                    val desc = cleanNewsText(descRe.find(chunk)?.groupValues?.get(1) ?: "").take(190)
                    val link = cleanNewsText(linkRe.find(chunk)?.groupValues?.get(1) ?: "")
                    if (title.isNotBlank() && link.startsWith("http")) {
                        results.add(NewsBriefingItem(region, source, title, desc, link))
                    }
                }
            }
        }
        results
    }

    // -------------------------------------------------------------------------
    // 3. 1-Click Burner Email & Temp Inbox (Handles both JSON Array & Hydra)
    // -------------------------------------------------------------------------
    suspend fun createBurnerIdentity(): BurnerIdentity = withContext(Dispatchers.IO) {
        val firstNames = listOf("Astrid", "Lukas", "Freja", "Noah", "Maja", "Elias", "Saga", "Liam")
        val lastNames = listOf("Holm", "Lind", "Berg", "Nyman", "Ek", "Varg", "Dahl", "Sjö")
        val first = firstNames.random()
        val last = lastNames.random()
        val suffix = (1000..9999).random()
        val username = "${first.lowercase()}_${last.lowercase()}$suffix"
        val password = "MuSec!${Random.nextInt(100000, 999999)}aA"

        runCatching {
            val (domCode, domBody) = httpRequest(
                "https://api.mail.tm/domains?page=1",
                headers = mapOf("Accept" to "application/json")
            )
            if (domCode in 200..299 && domBody.isNotBlank()) {
                val domainsArr = if (domBody.trimStart().startsWith("[")) {
                    JSONArray(domBody)
                } else {
                    JSONObject(domBody).optJSONArray("hydra:member") ?: JSONArray()
                }
                if (domainsArr.length() > 0) {
                    val domain = domainsArr.getJSONObject(0).optString("domain")
                    if (domain.isNotBlank()) {
                        val address = "$username@$domain"
                        val bodyJson = JSONObject().apply {
                            put("address", address)
                            put("password", password)
                        }.toString()
                        val (accCode, _) = httpRequest(
                            "https://api.mail.tm/accounts",
                            method = "POST",
                            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                            body = bodyJson
                        )
                        if (accCode in 200..299) {
                            val (tokCode, tokBody) = httpRequest(
                                "https://api.mail.tm/token",
                                method = "POST",
                                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                                body = bodyJson
                            )
                            if (tokCode in 200..299) {
                                val token = JSONObject(tokBody).optString("token")
                                if (token.isNotBlank()) {
                                    return@withContext BurnerIdentity(address, token, "mail.tm", "$first $last")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Instant offline/rate-limit fallback identity
        BurnerIdentity("$username@musec-temp.local", "", "offline-alias", "$first $last")
    }

    suspend fun fetchBurnerMessages(identity: BurnerIdentity): List<BurnerMessage> = withContext(Dispatchers.IO) {
        if (identity.token.isBlank() || identity.provider != "mail.tm") return@withContext emptyList()
        runCatching {
            val (code, body) = httpRequest(
                "https://api.mail.tm/messages?page=1",
                headers = mapOf(
                    "Authorization" to "Bearer ${identity.token}",
                    "Accept" to "application/json"
                )
            )
            if (code !in 200..299 || body.isBlank()) return@withContext emptyList()
            val arr = if (body.trimStart().startsWith("[")) {
                JSONArray(body)
            } else {
                JSONObject(body).optJSONArray("hydra:member") ?: JSONArray()
            }
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val fromObj = obj.optJSONObject("from")
                BurnerMessage(
                    id = obj.optString("id"),
                    from = fromObj?.optString("address") ?: "Unknown sender",
                    subject = obj.optString("subject", "(No subject)"),
                    intro = obj.optString("intro", ""),
                    createdAt = obj.optString("createdAt", "")
                )
            }
        }.getOrElse { emptyList() }
    }

    // -------------------------------------------------------------------------
    // 4. Web-Wide Lowest Price Scraper (On-Demand Only)
    // -------------------------------------------------------------------------
    suspend fun huntLowestPrices(productTitle: String): List<PriceOffer> = withContext(Dispatchers.IO) {
        val query = productTitle.trim().take(90)
        if (query.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")

        val offers = mutableListOf<PriceOffer>()
        runCatching {
            val (code, html) = httpRequest(
                "https://html.duckduckgo.com/html/?q=${URLEncoder.encode("$query price buy store", "UTF-8")}",
                timeoutMs = 7000
            )
            if (code in 200..299) {
                val blockRe = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>[\s\S]{0,600}?class="result__snippet"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                val priceRe = Regex("""(?:([$€£])\s?(\d[\d,.]{1,9})|(\d[\d\s,.]{1,9})\s?(kr|SEK|USD|EUR))""")
                for (m in blockRe.findAll(html).take(12)) {
                    val url = m.groupValues[1]
                    val snippet = cleanNewsText(m.groupValues[2] + " " + m.groupValues[3])
                    val pm = priceRe.find(snippet) ?: continue
                    val formatted = pm.value.trim()
                    val numStr = (pm.groupValues[2].ifBlank { pm.groupValues[3] })
                        .replace(" ", "")
                        .replace(",", ".")
                    val numeric = numStr.toDoubleOrNull() ?: continue
                    val host = runCatching { URI(url).host?.removePrefix("www.") ?: "Web Store" }.getOrDefault("Web Store")
                    offers.add(PriceOffer(host, formatted, numeric, url))
                }
            }
        }

        val sorted = offers.sortedBy { it.numericPrice }.take(6).toMutableList()
        // Always include direct comparison hubs
        sorted.add(PriceOffer("Prisjakt Search", "Compare ↗", Double.MAX_VALUE - 3, "https://www.prisjakt.nu/search?search=$encoded"))
        sorted.add(PriceOffer("PriceRunner Search", "Compare ↗", Double.MAX_VALUE - 2, "https://www.pricerunner.se/results?q=$encoded"))
        sorted.add(PriceOffer("Amazon Search", "Compare ↗", Double.MAX_VALUE - 1, "https://www.amazon.se/s?k=$encoded"))
        sorted
    }

    // -------------------------------------------------------------------------
    // 5. Fake Review & AI-Spam Forensic Detector
    // -------------------------------------------------------------------------
    fun auditProductReviews(reviews: List<String>, bodySample: String): ReviewAuditResult {
        val signals = mutableListOf<String>()
        var penalty = 0
        val lowerBody = bodySample.lowercase()

        if (reviews.isEmpty()) {
            signals.add("No visible customer reviews found on page")
            penalty += 10
        } else {
            val aiPhrases = listOf("game changer", "exceeded my expectations", "highly recommend to everyone", "delve into", "seamless experience", "in conclusion", "must-have product")
            val aiHits = reviews.count { r -> aiPhrases.any { p -> r.lowercase().contains(p) } }
            if (aiHits >= 2) {
                penalty += 28
                signals.add("AI-generated review phrasing detected in $aiHits review(s)")
            }

            val incentivized = reviews.count { r ->
                val l = r.lowercase()
                l.contains("free product") || l.contains("in exchange for") || l.contains("vine voice") || l.contains("received this for free")
            }
            if (incentivized > 0) {
                penalty += 20
                signals.add("$incentivized incentivized / free-sample review(s) found")
            }

            val shortGeneric = reviews.count { it.length < 45 }
            if (reviews.size >= 4 && shortGeneric > reviews.size / 2) {
                penalty += 18
                signals.add("High ratio of ultra-short generic reviews ($shortGeneric/${reviews.size})")
            }
        }

        if (lowerBody.contains("only 1 left") || lowerBody.contains("hurry! sale ends") || lowerBody.contains("limited time flash")) {
            penalty += 12
            signals.add("Urgency / scarcity dark-pattern pressure language detected")
        }

        val score = (100 - penalty).coerceIn(10, 100)
        val grade = when {
            score >= 88 -> "A"
            score >= 74 -> "B"
            score >= 58 -> "C"
            score >= 42 -> "D"
            else -> "F"
        }
        val verdict = when (grade) {
            "A", "B" -> "Authentic reviews likely ($score/100)"
            "C" -> "Mixed authenticity — verify carefully ($score/100)"
            else -> "High fake/AI-spam risk ($score/100)"
        }
        if (signals.isEmpty()) signals.add("No AI-spam templates or incentivized bursts detected")
        return ReviewAuditResult(grade, score, verdict, signals)
    }

    // -------------------------------------------------------------------------
    // 6. Universal Any-Language -> English Translator (POST + Chunked Batching)
    // -------------------------------------------------------------------------
    private fun translateChunkPost(chunk: String): String {
        val clean = chunk.trim()
        if (clean.isBlank()) return ""
        val encodedBody = "q=" + URLEncoder.encode(clean, "UTF-8")
        val (code, body) = httpRequest(
            urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t",
            method = "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
            body = encodedBody,
            timeoutMs = 8000
        )
        if (code in 200..299 && body.startsWith("[")) {
            val root = JSONArray(body)
            val sentences = root.getJSONArray(0)
            return buildString {
                for (i in 0 until sentences.length()) {
                    append(sentences.getJSONArray(i).optString(0))
                }
            }.trim()
        }
        return clean
    }

    suspend fun translateToEnglish(text: String): String = withContext(Dispatchers.IO) {
        val clean = text.trim().take(12000)
        if (clean.isBlank()) return@withContext ""
        val paragraphs = clean.split("\n\n").filter { it.isNotBlank() }
        if (paragraphs.size <= 1 && clean.length <= 1800) {
            return@withContext runCatching { translateChunkPost(clean) }
                .getOrElse { "Translation failed: ${it.message}" }
        }
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (p in paragraphs) {
            if (buf.length + p.length > 1600 && buf.isNotEmpty()) {
                out.add(runCatching { translateChunkPost(buf.toString()) }.getOrDefault(buf.toString()))
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(p)
        }
        if (buf.isNotEmpty()) {
            out.add(runCatching { translateChunkPost(buf.toString()) }.getOrDefault(buf.toString()))
        }
        out.joinToString("\n\n")
    }

    suspend fun translateBatchToEnglish(
        items: List<Pair<Int, String>>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val marker = "\n[[[M_SEP]]]\n"
        val batches = mutableListOf<List<Pair<Int, String>>>()
        var current = mutableListOf<Pair<Int, String>>()
        var charLen = 0

        for (item in items) {
            val len = item.second.length + marker.length
            if (current.isNotEmpty() && (charLen + len > 1100 || current.size >= 16)) {
                batches.add(current)
                current = mutableListOf()
                charLen = 0
            }
            current.add(item)
            charLen += len
        }
        if (current.isNotEmpty()) batches.add(current)

        val results = mutableListOf<Pair<Int, String>>()
        var doneCount = 0
        val sepSplitRe = Regex("""\s*\[\[\[\s*M_SEP\s*\]\]\]\s*""", RegexOption.IGNORE_CASE)

        for (batch in batches) {
            val joined = batch.joinToString(marker) { it.second }
            val translatedJoined = runCatching { translateChunkPost(joined) }.getOrDefault("")
            val parts = if (translatedJoined.isNotBlank()) sepSplitRe.split(translatedJoined) else emptyList()

            if (parts.size == batch.size) {
                for (k in batch.indices) {
                    results.add(batch[k].first to parts[k].trim())
                }
            } else {
                // Safe per-item fallback if a batch delimiter shifted
                for (item in batch) {
                    val single = runCatching { translateChunkPost(item.second) }.getOrDefault(item.second)
                    results.add(item.first to single)
                }
            }
            doneCount += batch.size
            withContext(Dispatchers.Main) { onProgress(doneCount, items.size) }
        }
        results
    }

    // -------------------------------------------------------------------------
    // 7. Website Legitimacy & Trust Radar
    // -------------------------------------------------------------------------
    fun evaluateLegitimacy(rawUrl: String): LegitimacyReport {
        if (rawUrl.isBlank() || rawUrl.startsWith("musec://")) {
            return LegitimacyReport("A+", 100, "MuSec Local", "Internal Trusted Dashboard", listOf("Local browser page"))
        }
        val uri = runCatching { URI(rawUrl) }.getOrNull()
        val host = uri?.host?.lowercase() ?: rawUrl
        val signals = mutableListOf<String>()
        var score = 100

        if (uri?.scheme?.equals("https", ignoreCase = true) == true) {
            signals.add("Encrypted HTTPS transport")
        } else {
            score -= 35
            signals.add("Unencrypted HTTP connection")
        }

        if (host.contains("xn--")) {
            score -= 25
            signals.add("Punycode internationalized domain (verify spelling)")
        }
        if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").matches(host)) {
            score -= 30
            signals.add("Direct IP address navigation")
        }
        if (host.count { it == '-' } >= 3 || host.length > 42) {
            score -= 20
            signals.add("Suspicious long or multi-hyphen domain")
        }

        val finalScore = score.coerceIn(10, 100)
        val grade = when {
            finalScore >= 92 -> "A+"
            finalScore >= 80 -> "A"
            finalScore >= 65 -> "B"
            finalScore >= 45 -> "C"
            else -> "D"
        }
        val verdict = if (finalScore >= 80) "Trusted ($finalScore/100)" else "Caution ($finalScore/100)"
        return LegitimacyReport(grade, finalScore, host, verdict, signals)
    }
}
