"use strict";

(() => {
  const HOST = String(location.hostname || "").toLowerCase();
  const IS_YT = HOST === "youtube.com" || HOST.endsWith(".youtube.com") || HOST === "youtu.be";
  const IS_IG = HOST === "instagram.com" || HOST.endsWith(".instagram.com");
  const IS_FB = HOST === "facebook.com" || HOST.endsWith(".facebook.com");
  const IS_TOP_FRAME = window === window.top;

  let config = {
    adShieldEnabled: true,
    darkPatternAuto: true,
    youtubeShield: {
      enabled: true,
      blockShorts: true,
      blockAds: true,
      blockKidsUnsafeKeywords: true,
      blockComments: false,
      blockRecommendations: false,
      blockHomeFeed: false,
      blockAutoplay: true,
      blockSocialReels: false,
      whitelistOnlyMode: false,
      blockedKeywords: [],
      blockedChannels: [],
      allowedChannels: []
    }
  };
  let cosmeticSelectors = [];

  // =========================================================================
  // 1. MAIN-WORLD VIDEO CLICK-SHIELD & POPUP BLOCKER (runs at document_start
  //    in ALL frames, including embedded video player iframes!)
  // =========================================================================
  function injectMainWorldPopupShield() {
    try {
      const script = document.createElement("script");
      script.textContent = `
        (() => {
          if (window.__musecMainWorldShield) return;
          window.__musecMainWorldShield = true;

          const AUTH_RE = /(?:accounts\\.google\\.com|login\\.microsoftonline\\.com|appleid\\.apple\\.com|bankid|frejaeid|swedbank|seb\\.se|nordea|handelsbanken|klarna|paypal|stripe|oauth2?|authorize)/i;
          const isAuth = (str) => AUTH_RE.test(String(str || ""));

          const makeDecoyWindow = () => {
            const noop = () => {};
            const loc = { href: "about:blank", assign: noop, replace: noop, reload: noop };
            const doc = { open: noop, write: noop, writeln: noop, close: noop, body: {}, documentElement: {} };
            const obj = { closed: false, focus: noop, blur: noop, close: noop, postMessage: noop, opener: null, location: loc, document: doc };
            return new Proxy(obj, {
              get(t, p) { return p === "closed" ? false : (p in t ? t[p] : noop); },
              set(t, p, v) { if (p === "location" || p === "href") loc.href = String(v || ""); else t[p] = v; return true; }
            });
          };

          // 1. Intercept window.open() popups on all non-auth sites (stops 90%+ of video-click popups!)
          const origOpen = window.open;
          if (typeof origOpen === "function") {
            const guardedOpen = function open(url) {
              if (isAuth(location.hostname) || isAuth(url)) {
                return origOpen.apply(this, arguments);
              }
              return makeDecoyWindow();
            };
            try { Object.defineProperty(guardedOpen, "toString", { value: () => "function open() { [native code] }" }); } catch {}
            window.open = guardedOpen;
          }

          let lastVideoTapAt = 0;

          const isPointInsideVideo = (x, y) => {
            if (!x && !y) return false;
            try {
              const els = document.querySelectorAll("video, .jwplayer, .video-js, .plyr, [class*='video-player' i]");
              for (let i = 0; i < els.length; i++) {
                const r = els[i].getBoundingClientRect();
                if (r.width > 100 && r.height > 70 && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                  return true;
                }
              }
            } catch {}
            return false;
          };

          // 2. Defuse synthetic script-triggered <a target="_blank">.click() popups
          const origAClick = HTMLAnchorElement.prototype.click;
          if (typeof origAClick === "function") {
            HTMLAnchorElement.prototype.click = function click() {
              try {
                const href = String(this.href || "");
                if (!isAuth(location.hostname) && !isAuth(href)) {
                  const recentVideo = Date.now() - lastVideoTapAt < 1800;
                  const rect = this.getBoundingClientRect ? this.getBoundingClientRect() : { width: 0, height: 0 };
                  const invisible = rect.width === 0 || rect.height === 0;
                  if (!this.isConnected || invisible || recentVideo) {
                    return undefined;
                  }
                }
              } catch {}
              return origAClick.apply(this, arguments);
            };
          }

          // 3. Defuse hidden <form target="_blank">.submit() popup tricks
          const origFormSubmit = HTMLFormElement.prototype.submit;
          if (typeof origFormSubmit === "function") {
            HTMLFormElement.prototype.submit = function submit() {
              try {
                const tgt = String(this.target || "").toLowerCase();
                if (!isAuth(location.hostname) && !isAuth(this.action) && (tgt === "_blank" || tgt === "_new")) {
                  return undefined;
                }
              } catch {}
              return origFormSubmit.apply(this, arguments);
            };
          }

          // 4. Strip transparent click-jacking overlays over <video> players (while preserving normal visible links!)
          const shieldTap = (e) => {
            try {
              if (isAuth(location.hostname)) return;
              const pt = (e.touches && e.touches[0]) ? e.touches[0] : e;
              const cx = pt.clientX || 0;
              const cy = pt.clientY || 0;
              const overVideo = isPointInsideVideo(cx, cy);
              const targetEl = e.target && e.target.closest ? e.target : null;
              if (!targetEl) return;

              const anchor = targetEl.closest("a");
              if (overVideo && !anchor) {
                lastVideoTapAt = Date.now();
              }

              // Kill invisible click-trap divs/ins over videos
              if (overVideo && !anchor && (targetEl.tagName === "DIV" || targetEl.tagName === "SPAN" || targetEl.tagName === "INS")) {
                const st = window.getComputedStyle(targetEl);
                const r = targetEl.getBoundingClientRect();
                const z = parseInt(st.zIndex || "0", 10);
                if (
                  (st.position === "fixed" || st.position === "absolute") &&
                  z >= 100 &&
                  (parseFloat(st.opacity || "1") < 0.2 || st.backgroundColor === "rgba(0, 0, 0, 0)") &&
                  r.width > 120 && r.height > 80 &&
                  !targetEl.querySelector("video, button, input, [role='slider']")
                ) {
                  targetEl.style.setProperty("pointer-events", "none", "important");
                  targetEl.style.setProperty("display", "none", "important");
                }
              }

              if (!anchor) return;
              const href = String(anchor.getAttribute("href") || anchor.href || "");
              if (isAuth(href)) return;

              const st = window.getComputedStyle(anchor);
              const op = parseFloat(st.opacity || "1");
              const noVisibleContent = !(anchor.textContent || "").trim() && !anchor.querySelector("img, svg, canvas, video, button, picture");
              const rect = anchor.getBoundingClientRect();
              const hugeOverlay = (st.position === "fixed" || st.position === "absolute") &&
                rect.width > window.innerWidth * 0.4 && rect.height > window.innerHeight * 0.4;

              // Only block invisible/empty overlay anchors over videos or full-screen traps
              if ((noVisibleContent || op < 0.15 || st.visibility === "hidden") && (overVideo || hugeOverlay)) {
                e.preventDefault();
                e.stopPropagation();
                e.stopImmediatePropagation();
                anchor.removeAttribute("target");
                anchor.removeAttribute("href");
                anchor.style.setProperty("pointer-events", "none", "important");
              }
            } catch {}
          };

          window.addEventListener("touchstart", shieldTap, true);
          window.addEventListener("pointerdown", shieldTap, true);
          window.addEventListener("mousedown", shieldTap, true);
          window.addEventListener("click", shieldTap, true);
        })();
      `;
      (document.documentElement || document.head || document).appendChild(script);
      script.remove();
    } catch {}
  }

  injectMainWorldPopupShield();

  // =========================================================================
  // 2. TELEMETRY & TOAST HELPERS
  // =========================================================================
  const reportedMap = new Map();
  function sendTelemetry(kind, detail) {
    const key = `${kind}::${detail}`;
    const last = reportedMap.get(key) || 0;
    if (Date.now() - last < 4500) return;
    reportedMap.set(key, Date.now());
    browser.runtime.sendMessage({ type: "telemetry", kind, detail }).catch(() => {});
  }

  function showToast(msg) {
    if (!IS_TOP_FRAME) return;
    try {
      let toast = document.getElementById("musec-shield-toast");
      if (!toast) {
        toast = document.createElement("div");
        toast.id = "musec-shield-toast";
        toast.style.cssText =
          "position:fixed;bottom:22px;left:50%;transform:translateX(-50%);z-index:2147483647;" +
          "background:rgba(20,22,34,0.95);color:#e2f8ff;border:1px solid rgba(56,189,248,0.65);" +
          "border-radius:999px;padding:10px 18px;font:600 14px/1.3 system-ui,sans-serif;" +
          "box-shadow:0 8px 28px rgba(0,0,0,0.45);pointer-events:none;transition:opacity .25s ease;";
        (document.body || document.documentElement).appendChild(toast);
      }
      toast.textContent = msg;
      toast.style.opacity = "1";
      clearTimeout(window.__musecToastTimer);
      window.__musecToastTimer = setTimeout(() => {
        if (toast) toast.style.opacity = "0";
      }, 3000);
    } catch {}
  }

  // =========================================================================
  // 3. COSMETIC AD-BLOCKING (650 Brave Shields Selectors) & YOUTUBE SHIELD CSS
  // =========================================================================
  const BASE_AD_SELECTORS = [
    "ins.adsbygoogle", ".adsbygoogle", ".ad-unit", ".ad-placement", ".ad-leaderboard",
    ".ad-Banner", ".ad_unit", ".ad_wrapper", ".taboola-widget", ".outbrain-widget",
    '[id^="google_ads_"]', '[id^="div-gpt-ad"]', '[id^="ad_position_"]',
    '[id^="taboola-"]', '[id^="outbrain_"]', "[data-ad-client]", "[data-ad-slot]",
    "[data-google-query-id]", 'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication.com"]',
    'iframe[src*="adnxs.com"]', 'iframe[src*="popads"]', 'iframe[src*="exoclick"]',
    'iframe[src*="adsterra"]', 'iframe[src*="propellerads"]', 'iframe[src*="magsrv"]',
    'iframe[src*="realsrv"]', 'iframe[id^="google_ads"]',
    ".mgbox", "[id^='MarketGid']", "[class*='ad-container' i]", "[class*='ad-banner' i]",
    "[id*='popup-ad' i]", "[class*='popup-ad' i]", "[class*='sticky-ad' i]", "[class*='floating-ad' i]"
  ];

  function buildDynamicCss() {
    const yt = config.youtubeShield || {};
    const rules = [];

    if (config.adShieldEnabled) {
      const allSel = BASE_AD_SELECTORS.concat(cosmeticSelectors || []);
      // Chunk into 80 selectors per rule so CSS parser stays blazing fast
      for (let i = 0; i < allSel.length; i += 80) {
        const slice = allSel.slice(i, i + 80);
        if (slice.length > 0) {
          rules.push(`${slice.join(", ")} { display: none !important; visibility: hidden !important; pointer-events: none !important; }`);
        }
      }
    }

    if (yt.enabled && IS_YT && yt.blockShorts) {
      rules.push(
        'ytd-reel-shelf-renderer, ytm-reel-shelf-renderer, ytd-rich-shelf-renderer[is-shorts], ' +
        'ytd-shorts, ytd-reel-video-renderer, a[title="Shorts"], ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ' +
        'ytd-guide-entry-renderer:has(a[title="Shorts"]), ytd-mini-guide-entry-renderer:has(a[title="Shorts"]), ' +
        'yt-tab-shape[tab-title="Shorts"], ytd-rich-item-renderer:has(a[href^="/shorts/"]), ' +
        'ytd-video-renderer:has(a[href^="/shorts/"]), ytd-compact-video-renderer:has(a[href^="/shorts/"]), ' +
        'grid-shelf-view-model:has(a[href^="/shorts/"]), ytm-pivot-bar-item-renderer:has(.pivot-shorts) { display: none !important; }'
      );
    }

    if (yt.enabled && IS_YT && yt.blockAds) {
      rules.push(
        'ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-promoted-sparkles-web-renderer, ' +
        'ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-companion-ad-renderer, ' +
        'ytd-display-ad-renderer, ytd-banner-promo-renderer, ytd-action-companion-ad-renderer, ' +
        'ytd-merch-shelf-renderer, #masthead-ad, #player-ads, .ytp-ad-overlay-container, ' +
        '.ytp-ad-message-container, ytd-enforcement-message-view-model { display: none !important; }'
      );
    }

    if (yt.enabled && IS_YT && yt.blockComments) {
      rules.push('#comments, ytd-comments, #chat, ytd-live-chat-frame, ytm-comment-section-header-renderer { display: none !important; }');
    }

    if (yt.enabled && IS_YT && yt.blockRecommendations) {
      rules.push('#related, ytd-watch-next-secondary-results-renderer, ytm-item-section-renderer[section-identifier="related-items"], .ytp-ce-element, .ytp-endscreen-content { display: none !important; }');
    }

    if (yt.enabled && IS_YT && yt.blockHomeFeed) {
      rules.push('ytd-browse[page-subtype="home"] ytd-rich-grid-renderer, ytm-browse ytm-rich-grid-renderer { display: none !important; }');
    }

    if (yt.enabled && (IS_IG || IS_FB) && yt.blockSocialReels) {
      rules.push('a[href*="/reels"], a[href*="/reel/"], [aria-label="Reels"] { display: none !important; }');
    }

    rules.push('[data-musec-yt-hidden="1"] { display: none !important; }');
    return rules.join("\n");
  }

  function applyStyle() {
    let styleEl = document.getElementById("musec-shield-style");
    if (!styleEl) {
      styleEl = document.createElement("style");
      styleEl.id = "musec-shield-style";
      (document.head || document.documentElement).appendChild(styleEl);
    }
    const nextCss = buildDynamicCss();
    if (styleEl.textContent !== nextCss) {
      styleEl.textContent = nextCss;
    }
  }

  function matchesKeyword(haystack, keywords) {
    if (!haystack || !keywords || keywords.length === 0) return null;
    for (let i = 0; i < keywords.length; i++) {
      const kw = keywords[i];
      if (kw && haystack.includes(kw)) return kw;
    }
    return null;
  }

  function matchesChannel(channelLower, list) {
    if (!channelLower || !list || list.length === 0) return null;
    const norm = channelLower.replace(/^@/, "").trim();
    for (let i = 0; i < list.length; i++) {
      const item = list[i];
      if (!item) continue;
      if (norm === item || norm.includes(item) || item.includes(norm)) return item;
    }
    return null;
  }

  function evaluateBlockReason(titleText, channelText, yt) {
    if (!yt.enabled) return null;
    const combined = `${titleText || ""} ${channelText || ""}`.toLowerCase();
    const chLower = String(channelText || "").toLowerCase().trim();

    const blockedCh = matchesChannel(chLower, yt.blockedChannels);
    if (blockedCh) return `Blocked channel: "${blockedCh}"`;

    const blockedKw = matchesKeyword(combined, yt.blockedKeywords);
    if (blockedKw) return `Blocked keyword: "${blockedKw}"`;

    if (yt.whitelistOnlyMode && yt.allowedChannels && yt.allowedChannels.length > 0) {
      if (chLower && !matchesChannel(chLower, yt.allowedChannels)) {
        return `Channel "${channelText.trim()}" is not in Allowed Channels`;
      }
    }
    return null;
  }

  function removeBlockOverlay() {
    const el = document.getElementById("musec-yt-block-screen");
    if (el) el.remove();
  }

  function showBlockOverlay(reasonText) {
    try {
      const video = document.querySelector("video");
      if (video) {
        video.pause();
        video.muted = true;
      }
      let overlay = document.getElementById("musec-yt-block-screen");
      if (!overlay) {
        overlay = document.createElement("div");
        overlay.id = "musec-yt-block-screen";
        overlay.style.cssText =
          "position:fixed;inset:0;z-index:2147483646;background:rgba(16,18,28,0.97);" +
          "display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;" +
          "padding:24px;text-align:center;color:#f4f4f6;font-family:system-ui,sans-serif;";
        const icon = document.createElement("div");
        icon.style.fontSize = "44px";
        icon.textContent = "🛡️";
        const heading = document.createElement("h2");
        heading.style.cssText = "margin:0;font-size:21px;font-weight:700;color:#38bdf8;";
        heading.textContent = "Blocked by MuSec YouTube Kids Shield";
        const reason = document.createElement("p");
        reason.id = "musec-yt-block-reason";
        reason.style.cssText = "margin:0;max-width:480px;font-size:15px;color:#cbd5e1;line-height:1.5;";
        const btn = document.createElement("button");
        btn.type = "button";
        btn.textContent = "← Return to Safe YouTube Home";
        btn.style.cssText =
          "margin-top:8px;padding:12px 22px;border-radius:999px;border:1px solid #38bdf8;" +
          "background:rgba(56,189,248,0.2);color:#f8fafc;font-size:15px;font-weight:600;cursor:pointer;";
        btn.addEventListener("click", () => {
          location.href = "https://www.youtube.com/";
        });
        overlay.appendChild(icon);
        overlay.appendChild(heading);
        overlay.appendChild(reason);
        overlay.appendChild(btn);
        (document.body || document.documentElement).appendChild(overlay);
      }
      const rEl = document.getElementById("musec-yt-block-reason");
      if (rEl) rEl.textContent = reasonText;
    } catch {}
  }

  let savedPreAdMute = null;

  function runYouTubeAndReelsShield() {
    applyStyle();
    const yt = config.youtubeShield || {};

    if (!yt.enabled) {
      document.querySelectorAll('[data-musec-yt-hidden="1"]').forEach((el) => el.removeAttribute("data-musec-yt-hidden"));
      removeBlockOverlay();
      return;
    }

    const pathname = String(location.pathname || "").toLowerCase();

    if ((IS_IG || IS_FB) && yt.blockSocialReels) {
      if (pathname === "/reels" || pathname.startsWith("/reels/") || pathname.startsWith("/reel/")) {
        sendTelemetry("reel", `Redirected ${HOST}${pathname}`);
        location.replace(`${location.origin}/`);
        return;
      }
    }

    if (!IS_YT) return;

    if (yt.blockShorts && (pathname === "/shorts" || pathname.startsWith("/shorts/"))) {
      sendTelemetry("short", `Blocked Short ${pathname.slice(0, 32)}`);
      showToast("🛡️ YouTube Short blocked by MuSec Shield");
      location.replace("https://www.youtube.com/");
      return;
    }

    if (location.search.includes("mmu_shield=short_blocked")) {
      showToast("🛡️ YouTube Short blocked by MuSec Shield");
      try { history.replaceState(null, "", "https://www.youtube.com/"); } catch {}
    }

    if (yt.blockAds) {
      const player = document.getElementById("movie_player") || document.querySelector(".html5-video-player");
      const isAdShowing = Boolean(
        player && (player.classList.contains("ad-showing") || player.classList.contains("ad-interrupting"))
      );
      const video = document.querySelector("#movie_player video, video.html5-main-video, video");
      if (isAdShowing && video) {
        if (!savedPreAdMute) savedPreAdMute = { wasMuted: video.muted };
        video.muted = true;
        if (Number.isFinite(video.duration) && video.duration > 0) {
          try { video.currentTime = Math.max(video.duration - 0.1, video.duration); } catch {}
        }
        try { video.playbackRate = 16; } catch {}
        const skipBtn = document.querySelector(
          ".ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot button"
        );
        if (skipBtn && typeof skipBtn.click === "function") skipBtn.click();
        sendTelemetry("yt_ad", "Skipped YouTube video ad");
      } else if (!isAdShowing && video && savedPreAdMute) {
        try {
          video.muted = Boolean(savedPreAdMute.wasMuted);
          if (video.playbackRate > 4) video.playbackRate = 1;
        } catch {}
        savedPreAdMute = null;
      }

      document.querySelectorAll("tp-yt-paper-dialog").forEach((dlg) => {
        const txt = (dlg.textContent || "").toLowerCase();
        if (txt.includes("ad blocker") || txt.includes("allow youtube ads")) {
          const dismiss = dlg.querySelector("#dismiss-button button, button[aria-label='Close']");
          if (dismiss) dismiss.click();
          dlg.style.setProperty("display", "none", "important");
          if (video && video.paused) video.play().catch(() => {});
        }
      });
    }

    if (yt.blockAutoplay) {
      const autoBtn = document.querySelector('.ytp-autonav-toggle-button[aria-checked="true"], button[aria-label*="Autoplay is on" i]');
      if (autoBtn && typeof autoBtn.click === "function") autoBtn.click();
    }

    const hasTextFilters =
      (yt.blockedKeywords && yt.blockedKeywords.length > 0) ||
      (yt.blockedChannels && yt.blockedChannels.length > 0) ||
      (yt.whitelistOnlyMode && yt.allowedChannels && yt.allowedChannels.length > 0);

    const cardSelector =
      "ytd-rich-item-renderer, ytd-video-renderer, ytd-compact-video-renderer, " +
      "ytd-grid-video-renderer, yt-lockup-view-model, ytm-video-with-context-renderer, " +
      "ytm-rich-item-renderer, ytm-compact-video-renderer";

    const cards = document.querySelectorAll(cardSelector);
    for (let i = 0; i < cards.length; i++) {
      const card = cards[i];
      let reason = null;
      if (yt.blockShorts && card.querySelector('a[href^="/shorts/"], a[href*="/shorts/"]')) {
        reason = "YouTube Short card";
      } else if (hasTextFilters) {
        const titleEl = card.querySelector("#video-title, #video-title-link, h3, h4, .media-item-headline");
        const chanEl = card.querySelector("ytd-channel-name, #channel-name, .ytm-badge-and-byline-item-byline, a[href^='/@'], a[href^='/channel/']");
        reason = evaluateBlockReason(titleEl ? titleEl.textContent : "", chanEl ? chanEl.textContent : "", yt);
      }

      if (reason) {
        if (card.getAttribute("data-musec-yt-hidden") !== "1") {
          card.setAttribute("data-musec-yt-hidden", "1");
          sendTelemetry(reason === "YouTube Short card" ? "short" : "video", reason);
        }
      } else if (card.getAttribute("data-musec-yt-hidden") === "1") {
        card.removeAttribute("data-musec-yt-hidden");
      }
    }

    if (pathname === "/watch" && hasTextFilters) {
      const titleEl = document.querySelector("h1.ytd-watch-metadata, .slim-video-information-title, h1");
      const chanEl = document.querySelector("ytd-watch-metadata ytd-channel-name a, .slim-owner-channel-name, #owner #channel-name a");
      const reason = evaluateBlockReason(
        titleEl ? titleEl.textContent : document.title,
        chanEl ? chanEl.textContent : "",
        yt
      );
      if (reason) {
        sendTelemetry("video", reason);
        showBlockOverlay(reason);
      } else {
        removeBlockOverlay();
      }
    } else {
      removeBlockOverlay();
    }
  }

  // Dark Pattern Automator (Auto-decline non-essential cookies)
  function runDarkPatternAutomator() {
    if (!config.darkPatternAuto || !IS_TOP_FRAME) return { dismissed: 0 };
    let dismissed = 0;
    const rejectRe = /^(reject all|decline all|decline|reject non-essential|only necessary|necessary only|avvisa alla|endast nödvändiga)$/i;
    const buttons = document.querySelectorAll("button, [role='button'], a");
    for (let i = 0; i < buttons.length; i++) {
      const b = buttons[i];
      const txt = (b.innerText || b.textContent || "").replace(/\s+/g, " ").trim();
      if (txt.length > 0 && txt.length < 36 && rejectRe.test(txt)) {
        const rect = b.getBoundingClientRect();
        if (rect.width > 20 && rect.height > 14) {
          try {
            b.click();
            dismissed += 1;
            break;
          } catch {}
        }
      }
    }
    return { dismissed };
  }

  // =========================================================================
  // 4. IN-PLACE WHOLE-PAGE TRANSLATION ENGINE (TreeWalker across entire DOM)
  // =========================================================================
  let storedTransNodes = [];
  let storedOriginalTitle = null;

  function collectPageTextNodes() {
    if (!IS_TOP_FRAME || !document.body) return { items: [], title: "" };
    if (storedTransNodes.length > 0) {
      // Restore originals before re-collecting so we never double-translate
      restorePageTranslation();
    }
    const skipTags = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "CODE", "PRE", "SVG", "TEXTAREA", "INPUT", "SELECT", "OPTION", "MATH"]);
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const parent = node.parentElement;
        if (!parent) return NodeFilter.FILTER_REJECT;
        if (skipTags.has(parent.tagName) || parent.closest("script, style, noscript, code, pre, svg, textarea, [contenteditable='true']")) {
          return NodeFilter.FILTER_REJECT;
        }
        const raw = (node.nodeValue || "").replace(/\s+/g, " ").trim();
        if (raw.length < 2 || /^[\d\s.,:%$€£+\-–—/|•·()[\]{}!?@#&*_=]+$/.test(raw)) {
          return NodeFilter.FILTER_REJECT;
        }
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    storedTransNodes = [];
    storedOriginalTitle = document.title || "";
    const items = [];
    let current;
    while ((current = walker.nextNode()) && items.length < 420) {
      const text = (current.nodeValue || "").replace(/\s+/g, " ").trim();
      const idx = storedTransNodes.length;
      storedTransNodes.push({ node: current, original: current.nodeValue });
      items.push({ i: idx, t: text.slice(0, 600) });
    }
    return { title: storedOriginalTitle, items };
  }

  function applyPageTranslations(args) {
    if (!IS_TOP_FRAME) return { applied: 0 };
    const list = (args && Array.isArray(args.translations)) ? args.translations : [];
    let applied = 0;
    for (let k = 0; k < list.length; k++) {
      const entry = list[k];
      if (!entry || typeof entry.i !== "number" || typeof entry.t !== "string") continue;
      const target = storedTransNodes[entry.i];
      if (target && target.node && target.node.isConnected) {
        const orig = target.original || "";
        const leadSpace = orig.startsWith(" ") ? " " : "";
        const trailSpace = orig.endsWith(" ") ? " " : "";
        target.node.nodeValue = `${leadSpace}${entry.t}${trailSpace}`;
        applied += 1;
      }
    }
    if (args && typeof args.translatedTitle === "string" && args.translatedTitle.trim()) {
      document.title = args.translatedTitle.trim();
    }
    if (applied > 0) {
      showToast(`🌐 Translated ${applied} page blocks to English`);
    }
    return { applied };
  }

  function restorePageTranslation() {
    if (!IS_TOP_FRAME) return { restored: 0 };
    let restored = 0;
    for (let i = 0; i < storedTransNodes.length; i++) {
      const entry = storedTransNodes[i];
      if (entry && entry.node && entry.node.isConnected) {
        entry.node.nodeValue = entry.original;
        restored += 1;
      }
    }
    if (storedOriginalTitle !== null) {
      document.title = storedOriginalTitle;
    }
    storedTransNodes = [];
    if (restored > 0) {
      showToast("↩ Restored original page language");
    }
    return { restored };
  }

  // On-demand Page Intelligence Extractor (Reader/Paywall, Price/Reviews, Whole-Page Translation)
  function extractPageIntelligence(command, args) {
    if (!IS_TOP_FRAME) return null;

    if (command === "get_page_text_nodes") {
      return collectPageTextNodes();
    }
    if (command === "apply_page_translations") {
      return applyPageTranslations(args);
    }
    if (command === "restore_page_translation") {
      return restorePageTranslation();
    }

    if (command === "extract_reader") {
      for (const root of [document.documentElement, document.body]) {
        if (!root) continue;
        root.style.setProperty("overflow", "auto", "important");
        root.style.setProperty("position", "static", "important");
      }
      document.querySelectorAll('[id*="paywall" i], [class*="paywall" i], .tp-modal, .tp-backdrop').forEach((el) => {
        if (el.tagName !== "ARTICLE" && el.tagName !== "MAIN" && !el.querySelector("article, main, h1")) {
          el.style.setProperty("display", "none", "important");
        }
      });
      let ldParagraphs = [];
      try {
        for (const s of document.querySelectorAll('script[type="application/ld+json"]')) {
          const parsed = JSON.parse(s.textContent || "{}");
          const items = Array.isArray(parsed) ? parsed : (parsed["@graph"] || [parsed]);
          for (const item of items) {
            if (item && typeof item.articleBody === "string" && item.articleBody.length > 160) {
              ldParagraphs = item.articleBody.split(/\n{2,}|(?<=[.!?])\s+(?=[A-ZÅÄÖ])/).map((t) => t.trim()).filter((t) => t.length > 35);
              break;
            }
          }
          if (ldParagraphs.length >= 3) break;
        }
      } catch {}
      const domParagraphs = Array.from(new Set(
        Array.from(document.querySelectorAll("article p, main p, p"))
          .map((p) => (p.innerText || p.textContent || "").replace(/\s+/g, " ").trim())
          .filter((t) => t.length > 45)
      ));
      return {
        title: document.title || location.href,
        url: location.href,
        method: ldParagraphs.length > domParagraphs.length ? "JSON-LD Paywall Recovery" : "DOM Paywall & Reader Unlock",
        paragraphs: (ldParagraphs.length > domParagraphs.length ? ldParagraphs : domParagraphs).slice(0, 60)
      };
    }

    if (command === "extract_shopping") {
      const h1 = document.querySelector("h1");
      const title = (h1 ? h1.innerText : document.title || "").replace(/\s+/g, " ").trim();
      const bodyText = (document.body ? document.body.innerText : "").slice(0, 25000);
      const priceMatches = bodyText.match(/(?:[$€£]\s?\d[\d.,]*|\d[\d\s.,]*\s?(?:kr|SEK|EUR|USD))/g) || [];
      const reviewNodes = Array.from(document.querySelectorAll('[class*="review" i], [data-hook="review-body"], [itemprop="reviewBody"]'))
        .map((n) => (n.innerText || "").replace(/\s+/g, " ").trim())
        .filter((t) => t.length > 25 && t.length < 900)
        .slice(0, 25);
      return {
        productName: title.slice(0, 120),
        primaryPrice: priceMatches[0] || "",
        url: location.href,
        reviews: reviewNodes,
        bodySample: bodyText.slice(0, 6000)
      };
    }

    if (command === "extract_translate") {
      const text = Array.from(document.querySelectorAll("h1, h2, h3, p"))
        .map((n) => (n.innerText || "").replace(/\s+/g, " ").trim())
        .filter((t) => t.length > 20)
        .slice(0, 25)
        .join("\n\n");
      return { title: document.title || "", url: location.href, text: text.slice(0, 4500) };
    }

    if (command === "run_dark_pattern") {
      return runDarkPatternAutomator();
    }

    return { ok: true };
  }

  browser.runtime.onMessage.addListener((msg) => {
    if (!msg || typeof msg !== "object") return undefined;
    if (msg.type === "config_update" && msg.config) {
      config = msg.config;
      if (Array.isArray(msg.cosmeticSelectors)) cosmeticSelectors = msg.cosmeticSelectors;
      runYouTubeAndReelsShield();
      return Promise.resolve({ ok: true });
    }
    if (msg.type === "page_command") {
      if (!IS_TOP_FRAME) return undefined;
      return Promise.resolve(extractPageIntelligence(msg.command, msg.args || {}));
    }
    return undefined;
  });

  // Fetch initial config + 650 Brave cosmetic selectors immediately
  browser.runtime.sendMessage({ type: "get_config" }).then((res) => {
    if (res && res.config) config = res.config;
    if (res && Array.isArray(res.cosmeticSelectors)) cosmeticSelectors = res.cosmeticSelectors;
    runYouTubeAndReelsShield();
  }).catch(() => {
    runYouTubeAndReelsShield();
  });

  let scanScheduled = false;
  const scheduleScan = () => {
    if (scanScheduled) return;
    scanScheduled = true;
    setTimeout(() => {
      scanScheduled = false;
      runYouTubeAndReelsShield();
    }, 180);
  };

  if (document.documentElement) {
    new MutationObserver(scheduleScan).observe(document.documentElement, { childList: true, subtree: true });
  }
  window.addEventListener("yt-navigate-start", scheduleScan, true);
  window.addEventListener("yt-navigate-finish", scheduleScan, true);
  window.addEventListener("popstate", scheduleScan, true);
  window.addEventListener("DOMContentLoaded", () => {
    runYouTubeAndReelsShield();
    setTimeout(runDarkPatternAutomator, 1400);
  });
  setInterval(runYouTubeAndReelsShield, 600);
})();
