"use strict";

// Native messaging bridge to Kotlin GeckoBrowserManager
const port = browser.runtime.connectNative("musec_bridge");

const DEFAULT_AD_DOMAINS = new Set([
  "doubleclick.net",
  "googlesyndication.com",
  "googleadservices.com",
  "adservice.google.com",
  "pagead2.googlesyndication.com",
  "google-analytics.com",
  "googletagmanager.com",
  "taboola.com",
  "outbrain.com",
  "criteo.com",
  "criteo.net",
  "adnxs.com",
  "popads.net",
  "popcash.net",
  "propellerads.com",
  "exoclick.com",
  "exosrv.com",
  "realsrv.com",
  "adsterra.com",
  "mgid.com",
  "monetag.com",
  "amazon-adsystem.com",
  "rubiconproject.com",
  "pubmatic.com",
  "openx.net",
  "casalemedia.com",
  "advertising.com",
  "scorecardresearch.com",
  "quantserve.com",
  "hotjar.com",
  "mixpanel.com",
  "segment.io",
  "amplitude.com",
  "branch.io",
  "appsflyer.com",
  "adjust.com",
  "adcolony.com",
  "unityads.unity3d.com",
  "applovin.com",
  "vungle.com",
  "moatads.com",
  "serving-sys.com",
  "zedo.com",
  "clickadu.com",
  "hilltopads.net",
  "trafficjunky.net",
  "juicyads.com",
  "coinhive.com",
  "cryptoloot.pro"
]);

const YOUTUBE_AD_PATH_RE =
  /(?:\/api\/stats\/ads|\/pagead\/|\/get_midroll_info|\/ptracking|\/pcs\/activeview|\/ad_status|\/youtubei\/v1\/log_event)/i;

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

function matchesDomainSet(hostname, extraList) {
  let cursor = String(hostname || "").toLowerCase().replace(/\.$/, "");
  const customSet = new Set((extraList || []).map((d) => String(d || "").toLowerCase()));
  while (cursor) {
    if (DEFAULT_AD_DOMAINS.has(cursor) || customSet.has(cursor)) return cursor;
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
      browser.tabs.sendMessage(tab.id, { type: "config_update", config: shieldConfig }).catch(() => {});
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
      if (!active) return;
      browser.tabs
        .sendMessage(active.id, {
          type: "page_command",
          command: message.command,
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

// Content scripts request current config or forward telemetry events
browser.runtime.onMessage.addListener((msg) => {
  if (!msg || typeof msg !== "object") return undefined;
  if (msg.type === "get_config") {
    return Promise.resolve({ config: shieldConfig });
  }
  if (msg.type === "telemetry") {
    sendTelemetry(msg.kind || "video", msg.detail || "");
    return Promise.resolve({ ok: true });
  }
  return undefined;
});

// 1. Network-level AdShield + YouTube Shorts/Ads/Reels Blocker
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

    // AdShield + custom blocked domains
    if (shieldConfig.adShieldEnabled) {
      const matched = matchesDomainSet(host, shieldConfig.customBlockedDomains);
      if (matched) {
        if (details.type === "main_frame") {
          const isCustom = (shieldConfig.customBlockedDomains || []).some((d) => String(d).toLowerCase() === matched);
          if (!isCustom) return {};
        }
        sendTelemetry("ad", `Blocked ${matched}`);
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

// Request initial persisted config from Kotlin on startup
port.postMessage({ type: "extension_ready" });
