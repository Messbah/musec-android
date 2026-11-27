"use strict";

// Native messaging bridge to Kotlin GeckoBrowserManager
const port = browser.runtime.connectNative("musec_bridge");

// Immediate core fallback blocklist (active at t=0ms before 97k Brave Shields JSON loads)
const UNCONDITIONAL_DOMAINS = new Set([
  "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
  "pagead2.googlesyndication.com", "google-analytics.com", "googletagmanager.com",
  "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "adnxs.com", "adsrvr.org",
  "adform.net", "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com",
  "realsrv.com", "magsrv.com", "adsterra.com", "mgid.com", "monetag.com", "amazon-adsystem.com",
  "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "advertising.com",
  "scorecardresearch.com", "quantserve.com", "hotjar.com", "mixpanel.com", "segment.io",
  "amplitude.com", "branch.io", "appsflyer.com", "adjust.com", "adcolony.com", "applovin.com",
  "vungle.com", "moatads.com", "serving-sys.com", "zedo.com", "clickadu.com", "hilltopads.net",
  "trafficjunky.net", "juicyads.com", "coinhive.com", "cryptoloot.pro", "acscdn.com",
  "onclkds.com", "tsyndicate.com", "highperformanceformat.com", "topcreativeformat.com",
  "profitablegate.com", "effectivegatecpm.com", "a-ads.com", "revcontent.com", "adskeeper.com"
]);
const THIRD_PARTY_DOMAINS = new Set();
const POPUP_DOMAINS = new Set([
  "popads.net", "popcash.net", "propellerads.com", "clickadu.com", "hilltopads.net",
  "adsterra.com", "monetag.com", "exoclick.com", "exosrv.com", "realsrv.com", "magsrv.com",
  "juicyads.com", "trafficjunky.net", "tsyndicate.com", "onclkds.com", "acscdn.com",
  "profitablegate.com", "effectivegatecpm.com", "highperformanceformat.com", "topcreativeformat.com"
]);
const EXCEPTION_DOMAINS = new Set();
let cosmeticSelectors = [];

// Load full Brave Shields 97,665-domain + 2,667-popup + 650-cosmetic dataset bundled in assets
fetch(browser.runtime.getURL("brave-shields-compiled.json"))
  .then((r) => r.json())
  .then((data) => {
    if (!data || typeof data !== "object") return;
    if (Array.isArray(data.unconditionalDomains)) {
      for (let i = 0; i < data.unconditionalDomains.length; i++) {
        UNCONDITIONAL_DOMAINS.add(data.unconditionalDomains[i]);
      }
    }
    if (Array.isArray(data.thirdPartyDomains)) {
      for (let i = 0; i < data.thirdPartyDomains.length; i++) {
        THIRD_PARTY_DOMAINS.add(data.thirdPartyDomains[i]);
      }
    }
    if (Array.isArray(data.popupBlockedDomains)) {
      for (let i = 0; i < data.popupBlockedDomains.length; i++) {
        POPUP_DOMAINS.add(data.popupBlockedDomains[i]);
      }
    }
    if (Array.isArray(data.exceptionDomains)) {
      for (let i = 0; i < data.exceptionDomains.length; i++) {
        EXCEPTION_DOMAINS.add(data.exceptionDomains[i]);
      }
    }
    if (Array.isArray(data.genericCosmeticSelectors)) {
      cosmeticSelectors = data.genericCosmeticSelectors.slice(0, 650);
      broadcastConfigToAllTabs();
    }
  })
  .catch(() => {});

const YOUTUBE_AD_PATH_RE =
  /(?:\/api\/stats\/ads|\/pagead\/|\/get_midroll_info|\/ptracking|\/pcs\/activeview|\/ad_status|\/youtubei\/v1\/log_event)/i;
const MALICIOUS_AD_PATH_RE =
  /(?:\/pagead\/|\/adframe|\/adserver|\/popunder|\/pop_under|\/clicktrack|\/malvert|\/banner_ad|[_.-]ads\.js|\/ads\/|\/adview|\/adclick)/i;
const POPUP_QUERY_RE = /(?:popunder|clickid=|aff_id=|offer_id=|zoneid=|adid=|bannerid=)/i;
const TRUSTED_AUTH_OR_BANK_RE =
  /(?:accounts\.google\.com|login\.microsoftonline\.com|appleid\.apple\.com|bankid\.com|frejaeid\.com|swedbank\.se|seb\.se|nordea\.se|handelsbanken\.se|klarna\.com|paypal\.com|stripe\.com)/i;

let shieldConfig = {
  adShieldEnabled: true,
  darkPatternAuto: true,
  customBlockedDomains: [],
  youtubeShield: {
    enabled: true,
    blockShorts: true,
    blockAds: true,
    blockKidsUnsafeKeywords: true,
    blockComments: false,
    blockRecommendations: false,
    blockHomeFeed: false,
    blockAutoplay: true,
    enforceRestrictedMode: false,
    blockSocialReels: false,
    whitelistOnlyMode: false,
    blockedKeywords: [],
    blockedChannels: [],
    allowedChannels: []
  }
};

function isYouTubeHost(host) {
  return (
    host === "youtube.com" ||
    host.endsWith(".youtube.com") ||
    host === "youtu.be" ||
    host === "youtube-nocookie.com" ||
    host.endsWith(".youtube-nocookie.com")
  );
}

function getRootDomain(hostname) {
  const parts = String(hostname || "").toLowerCase().replace(/\.$/, "").split(".");
  if (parts.length <= 2) return parts.join(".");
  const lastTwo = parts.slice(-2).join(".");
  if (/^(co|com|org|net|gov|edu|ac)\.[a-z]{2}$/.test(lastTwo) && parts.length >= 3) {
    return parts.slice(-3).join(".");
  }
  return lastTwo;
}

function matchInSet(hostname, domainSet) {
  let cursor = String(hostname || "").toLowerCase().replace(/\.$/, "");
  while (cursor) {
    if (domainSet.has(cursor)) return cursor;
    const dot = cursor.indexOf(".");
    if (dot === -1) break;
    cursor = cursor.slice(dot + 1);
  }
  return null;
}

function sendTelemetry(kind, detail) {
  try {
    port.postMessage({ type: "telemetry", kind, detail: String(detail || "").slice(0, 100) });
  } catch {}
}

function broadcastConfigToAllTabs() {
  browser.tabs.query({}).then((tabs) => {
    for (const tab of tabs) {
      browser.tabs
        .sendMessage(tab.id, {
          type: "config_update",
          config: shieldConfig,
          cosmeticSelectors
        })
        .catch(() => {});
    }
  }).catch(() => {});
}

// Receive live commands and config updates from Kotlin UI
port.onMessage.addListener((message) => {
  if (!message || typeof message !== "object") return;

  if (message.type === "set_config" && message.config) {
    shieldConfig = message.config;
    broadcastConfigToAllTabs();
    return;
  }

  if (message.type === "page_command") {
    browser.tabs.query({ active: true }).then((tabs) => {
      const active = tabs[0];
      if (!active) {
        port.postMessage({
          type: "page_command_result",
          command: message.command,
          requestId: message.requestId || "",
          payload: {}
        });
        return;
      }
      browser.tabs
        .sendMessage(active.id, {
          type: "page_command",
          command: message.command,
          args: message.args || {},
          requestId: message.requestId || ""
        })
        .then((response) => {
          port.postMessage({
            type: "page_command_result",
            command: message.command,
            requestId: message.requestId || "",
            payload: response || {}
          });
        })
        .catch((err) => {
          port.postMessage({
            type: "page_command_result",
            command: message.command,
            requestId: message.requestId || "",
            error: String(err && err.message ? err.message : err)
          });
        });
    }).catch(() => {});
  }
});

// Content scripts request current config + cosmetic selectors or forward telemetry
browser.runtime.onMessage.addListener((msg) => {
  if (!msg || typeof msg !== "object") return undefined;
  if (msg.type === "get_config") {
    return Promise.resolve({ config: shieldConfig, cosmeticSelectors });
  }
  if (msg.type === "telemetry") {
    sendTelemetry(msg.kind || "video", msg.detail || "");
    return Promise.resolve({ ok: true });
  }
  return undefined;
});

// 1. Full Brave Shields (97k domains) + YouTube Shorts/Ads/Reels Network Blocker
browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    let parsed;
    try {
      parsed = new URL(details.url);
    } catch {
      return {};
    }
    const host = parsed.hostname.toLowerCase();
    const pathname = parsed.pathname.toLowerCase();
    const yt = shieldConfig.youtubeShield || {};

    if (TRUSTED_AUTH_OR_BANK_RE.test(host)) return {};

    // YouTube Shorts main_frame redirect
    if (yt.enabled && yt.blockShorts && details.type === "main_frame" && isYouTubeHost(host)) {
      if (pathname === "/shorts" || pathname.startsWith("/shorts/")) {
        sendTelemetry("short", "Redirected YouTube Short");
        return { redirectUrl: "https://www.youtube.com/?mmu_shield=short_blocked" };
      }
    }

    // YouTube ad network endpoints
    if (yt.enabled && yt.blockAds && isYouTubeHost(host) && details.type !== "main_frame") {
      if (YOUTUBE_AD_PATH_RE.test(pathname)) {
        sendTelemetry("yt_ad", "Blocked YouTube ad endpoint");
        return { cancel: true };
      }
      if (pathname.includes("/videoplayback") && (parsed.searchParams.has("oad") || parsed.searchParams.get("ctier") === "L")) {
        sendTelemetry("yt_ad", "Blocked YouTube video ad stream");
        return { cancel: true };
      }
    }

    // Social Reels blocking (Instagram / Facebook / TikTok)
    if (yt.enabled && yt.blockSocialReels && details.type === "main_frame") {
      if (host === "tiktok.com" || host.endsWith(".tiktok.com")) {
        sendTelemetry("reel", "Blocked TikTok");
        return { cancel: true };
      }
      if ((host === "instagram.com" || host.endsWith(".instagram.com")) && (pathname === "/reels" || pathname.startsWith("/reels/") || pathname.startsWith("/reel/"))) {
        sendTelemetry("reel", "Blocked Instagram Reels");
        return { redirectUrl: "https://www.instagram.com/" };
      }
      if ((host === "facebook.com" || host.endsWith(".facebook.com")) && (pathname === "/reel" || pathname.startsWith("/reel/") || pathname.startsWith("/reels"))) {
        sendTelemetry("reel", "Blocked Facebook Reels");
        return { redirectUrl: "https://www.facebook.com/" };
      }
    }

    // Full Brave Shields Ad & Popup Blocker
    if (shieldConfig.adShieldEnabled) {
      if (matchInSet(host, EXCEPTION_DOMAINS)) return {};

      const customSet = new Set((shieldConfig.customBlockedDomains || []).map((d) => String(d || "").toLowerCase()));
      const customMatch = matchInSet(host, customSet);
      if (customMatch) {
        sendTelemetry("ad", `Blocked custom ${customMatch}`);
        return { cancel: true };
      }

      // Main-frame popup / pop-under redirect blocking (2,667 Brave popup domains + ad redirect params)
      if (details.type === "main_frame") {
        const popupMatch = matchInSet(host, POPUP_DOMAINS);
        if (popupMatch || POPUP_QUERY_RE.test(parsed.search)) {
          sendTelemetry("ad", `Blocked popup ${popupMatch || host}`);
          return { cancel: true };
        }
        return {};
      }

      // Subresource unconditional domain match (92,112 Brave EasyList + uBO domains)
      const uncondMatch = matchInSet(host, UNCONDITIONAL_DOMAINS) || matchInSet(host, POPUP_DOMAINS);
      if (uncondMatch) {
        sendTelemetry("ad", `Blocked ${uncondMatch}`);
        return { cancel: true };
      }

      // Third-party tracker domain match (5,553 domains)
      const originUrl = details.originUrl || details.documentUrl || "";
      if (originUrl) {
        try {
          const originHost = new URL(originUrl).hostname.toLowerCase();
          if (originHost && getRootDomain(originHost) !== getRootDomain(host)) {
            const tpMatch = matchInSet(host, THIRD_PARTY_DOMAINS);
            if (tpMatch) {
              sendTelemetry("ad", `Blocked 3P tracker ${tpMatch}`);
              return { cancel: true };
            }
          }
        } catch {}
      }

      // Aggressive ad script / iframe / banner path patterns
      if (
        (details.type === "script" || details.type === "sub_frame" || details.type === "image" || details.type === "xmlhttprequest") &&
        (MALICIOUS_AD_PATH_RE.test(pathname) || POPUP_QUERY_RE.test(parsed.search))
      ) {
        sendTelemetry("ad", `Blocked ad path ${host}`);
        return { cancel: true };
      }
    }

    return {};
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);

// 2. Official YouTube Restricted Mode Header (`YouTube-Restrict: Strict`) + DNT/GPC
browser.webRequest.onBeforeSendHeaders.addListener(
  (details) => {
    let host = "";
    try {
      host = new URL(details.url).hostname.toLowerCase();
    } catch {
      return {};
    }
    const headers = details.requestHeaders || [];
    const yt = shieldConfig.youtubeShield || {};
    let modified = false;

    if (yt.enabled && yt.enforceRestrictedMode && isYouTubeHost(host)) {
      const existing = headers.find((h) => h.name.toLowerCase() === "youtube-restrict");
      if (existing) existing.value = "Strict";
      else headers.push({ name: "YouTube-Restrict", value: "Strict" });
      modified = true;
    }

    if (shieldConfig.adShieldEnabled) {
      if (!headers.some((h) => h.name.toLowerCase() === "sec-gpc")) {
        headers.push({ name: "Sec-GPC", value: "1" });
        modified = true;
      }
    }

    return modified ? { requestHeaders: headers } : {};
  },
  { urls: ["<all_urls>"] },
  ["blocking", "requestHeaders"]
);

port.postMessage({ type: "extension_ready" });
