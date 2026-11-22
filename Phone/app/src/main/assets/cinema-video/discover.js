(() => {
    let previous = "";
    let suppressUntil = 0;
    let port;
    const frameToken = Math.random().toString(36).slice(2) + Date.now().toString(36);
    let discoveryRevision = 1;
    let hlsAbort = new AbortController();
    let currentZoomPercent = 100;
    const ZOOM_STYLE_ID = "__lc_page_zoom_style__";

    function syncPageZoomNow() {
        if (window !== window.top) return;
        const root = document.documentElement;
        if (!root) return;
        const p = currentZoomPercent;
        const existing = document.getElementById(ZOOM_STYLE_ID);
        if (p === 100) {
            if (existing) existing.remove();
            if (root.style) root.style.removeProperty("zoom");
            return;
        }
        const factor = (p / 100).toFixed(2);
        const css = `:root { zoom: ${factor} !important; -webkit-text-size-adjust: 100% !important; text-size-adjust: 100% !important; }`;
        if (existing) {
            if (existing.textContent !== css) existing.textContent = css;
        } else {
            const style = document.createElement("style");
            style.id = ZOOM_STYLE_ID;
            style.textContent = css;
            (document.head || root).appendChild(style);
        }
        if (root.style) root.style.removeProperty("zoom");
    }

    function applyPageZoom(percent) {
        if (window !== window.top) return;
        currentZoomPercent = Math.max(50, Math.min(150, Math.round(Number(percent) || 100)));
        syncPageZoomNow();
        if (document.readyState === "loading") {
            window.addEventListener("DOMContentLoaded", syncPageZoomNow, { once: true });
        }
    }

    const YT_VIDEO_ITAG_HEIGHT = {
        313: 2160, 401: 2160, 315: 2160, 266: 2160, 138: 2160, 571: 2160, 701: 2160, 702: 2160,
        271: 1440, 400: 1440, 308: 1440, 264: 1440, 700: 1440,
        137: 1080, 248: 1080, 399: 1080, 299: 1080, 303: 1080, 699: 1080,
        136: 720, 247: 720, 398: 720, 298: 720, 302: 720, 22: 720, 698: 720,
        135: 480, 244: 480, 397: 480, 697: 480,
        134: 360, 243: 360, 396: 360, 18: 360, 696: 360,
        133: 240, 242: 240, 395: 240
    };
    const YT_AUDIO_ITAGS = new Set(["140", "141", "251", "250", "249", "139", "256", "258", "327", "328"]);

    function normalizeResolutionHeight(width, height) {
        const w = Number(width) || 0;
        const h = Number(height) || 0;
        if (w <= 0 && h <= 0) return 0;
        if (w > 0 && h > 0) {
            const maxDim = Math.max(w, h);
            const minDim = Math.min(w, h);
            // Portrait video (e.g. Shorts 1080x1920) keeps short-edge height
            if (h > w) {
                if (minDim >= 2000) return 2160;
                if (minDim >= 1400) return 1440;
                if (minDim >= 1000) return 1080;
                if (minDim >= 680) return 720;
                if (minDim >= 440) return 480;
                if (minDim >= 320) return 360;
                if (minDim >= 200) return 240;
                return minDim;
            }
            // Landscape / CinemaScope (e.g. 1920x800 -> 1080p, 3840x1600 -> 2160p)
            if (maxDim >= 3700 || minDim >= 2000) return 2160;
            if (maxDim >= 2450 || minDim >= 1380) return 1440;
            if (maxDim >= 1840 || minDim >= 1000) return 1080;
            if (maxDim >= 1220 || minDim >= 680) return 720;
            if (maxDim >= 800 || minDim >= 440) return 480;
            if (maxDim >= 580 || minDim >= 320) return 360;
            if (maxDim >= 380 || minDim >= 210) return 240;
            return minDim;
        }
        return h;
    }

    function inferHeightFromUrl(rawUrl) {
        if (!rawUrl || typeof rawUrl !== "string") return 0;
        try {
            const u = new URL(rawUrl, location.href);
            const itag = u.searchParams.get("itag");
            if (itag && YT_VIDEO_ITAG_HEIGHT[itag]) return YT_VIDEO_ITAG_HEIGHT[itag];
        } catch (_) {}
        const dim = /(?:^|[^0-9])(\d{3,4})x(\d{3,4})(?![0-9])/i.exec(rawUrl);
        if (dim) return normalizeResolutionHeight(Number(dim[1]), Number(dim[2]));
        if (/(?:^|[^a-z0-9])(2160p|4k|uhd)(?![a-z0-9])/i.test(rawUrl)) return 2160;
        if (/(?:^|[^a-z0-9])(1440p|2k|qhd)(?![a-z0-9])/i.test(rawUrl)) return 1440;
        if (/(?:^|[^a-z0-9])(1080p|fhd)(?![a-z0-9])/i.test(rawUrl)) return 1080;
        if (/(?:^|[^a-z0-9])(720p|hd)(?![a-z0-9])/i.test(rawUrl)) return 720;
        if (/(?:^|[^a-z0-9])(480p|sd)(?![a-z0-9])/i.test(rawUrl)) return 480;
        if (/(?:^|[^a-z0-9])360p(?![a-z0-9])/i.test(rawUrl)) return 360;
        if (/(?:^|[^a-z0-9])240p(?![a-z0-9])/i.test(rawUrl)) return 240;
        return 0;
    }

    function pauseMedia() {
        for (const element of document.querySelectorAll("video,audio")) if (!element.paused) element.pause();
    }

    function muteAllMedia(mute) {
        for (const element of document.querySelectorAll("video,audio")) {
            try {
                element.muted = Boolean(mute);
                if (mute && !element.paused) element.pause();
            } catch (_) {}
        }
    }

    function isAudioPlaying() {
        for (const el of document.querySelectorAll("video,audio")) {
            try {
                if (!el.paused && !el.ended && !el.muted && el.volume > 0 && el.readyState >= 2) {
                    return true;
                }
            } catch (_) {}
        }
        return false;
    }

    const GENERIC_SITE_TITLES = new Set([
        "cinemana", "shabakaty", "shabakaty cinemana", "youtube", "home", "video", "video player",
        "egybest", "faselhd", "mycima", "wecima", "cimalight", "arabseed", "akoam", "watch", "online",
        "سينمانا", "شبكتي", "شبكتي سينمانا", "سيما لايت", "مشاهدة", "الرئيسية"
    ]);

    function cleanMediaTitle(raw) {
        if (!raw || typeof raw !== "string") return "";
        let t = raw.replace(/\s+/g, " ").trim();
        t = t.replace(/^(?:watch(?:\s+and\s+download)?|download(?:\s+and\s+watch)?|مشاهدة(?:\s+و\s*تحميل)?|تحميل(?:\s+و\s*مشاهدة)?|فيلم|مسلسل|انمي|أنمي)\s+/i, "");
        t = t.replace(/\s*(?:[|–—•·:\-]\s*)+(?:shabakaty\s*)?(?:cinemana|shabakaty|youtube|yts(?:\.[a-z]+)?|yify|egybest|faselhd|mycima|wecima|e?\.?cimalight(?:\.co)?|arabseed|akoam|شبكتي|سينمانا|سيما\s*لايت|ايجي\s*بست|فاصل\s*إعلاني|ماي\s*سيما|اون\s*لاين|أون\s*لاين).*$/i, "");
        t = t.replace(/^(?:shabakaty\s*)?(?:cinemana|shabakaty|youtube|yts(?:\.[a-z]+)?|yify|egybest|faselhd|mycima|wecima|شبكتي|سينمانا)\s*(?:[|–—•·:\-]\s*)+/i, "");
        t = t.replace(/\s*\b(?:torrent(?:\s*-\s*yts(?:\.[a-z]+)?)?|watch\s+online|full\s+movie|official\s+trailer|مترجم(?:\s+أون\s*لاين|\s+اون\s*لاين|\s+كامل)?|مدبلج|أون\s*لاين|اون\s*لاين|اونلاين|HD|FHD|UHD|4K)\b\s*$/gi, "");
        t = t.replace(/^[\s\-–—|:•·]+|[\s\-–—|:•·]+$/g, "").trim();
        if (!t || GENERIC_SITE_TITLES.has(t.toLowerCase())) return "";
        return t.slice(0, 160);
    }

    function detectPageReleaseYear(baseTitle) {
        try {
            const yearSelectors = [
                "#movie-info h2", ".movie-info h2", ".year", ".release-year", ".movie-year",
                "[class*='release-year']", "[class*='movie-year']", "[itemprop='datePublished']",
                "[itemprop='copyrightYear']", "span.year", "a[href*='/year/']"
            ];
            for (const sel of yearSelectors) {
                const el = document.querySelector(sel);
                const m = (el?.textContent || el?.getAttribute("content") || "").match(/\b(19\d{2}|20[0-3]\d)\b/);
                if (m) return m[1];
            }
            const sources = [
                document.querySelector('meta[property="og:title"]')?.getAttribute("content") || "",
                document.querySelector('meta[name="twitter:title"]')?.getAttribute("content") || "",
                document.title || ""
            ];
            const lowBase = (baseTitle || "").toLowerCase();
            for (const src of sources) {
                if (!src) continue;
                if (!lowBase || src.toLowerCase().includes(lowBase)) {
                    const m = src.match(/\(\s*(19\d{2}|20[0-3]\d)\s*\)|\b(19\d{2}|20[0-3]\d)\b/);
                    if (m) return m[1] || m[2];
                }
            }
        } catch (_) {}
        return "";
    }

    function withDetectedYear(title) {
        if (!title) return "";
        if (/\b(19\d{2}|20[0-3]\d)\b/.test(title)) return title;
        const yr = detectPageReleaseYear(title);
        return yr ? (title + " (" + yr + ")").slice(0, 160) : title;
    }

    function extractCleanMediaTitle() {
        // 1. Structured JSON-LD (Movie / TVSeries / TVEpisode / VideoObject)
        try {
            const scripts = document.querySelectorAll('script[type="application/ld+json"]');
            for (const s of scripts) {
                if (!s.textContent) continue;
                const parsed = JSON.parse(s.textContent);
                const queue = Array.isArray(parsed) ? parsed : [parsed];
                for (const obj of queue) {
                    if (!obj || typeof obj !== "object") continue;
                    const types = [].concat(obj["@type"] || []).join(" ");
                    if (/Movie|TVSeries|TVEpisode|VideoObject/i.test(types) && typeof obj.name === "string") {
                        const seriesName = obj.partOfSeries?.name ? cleanMediaTitle(String(obj.partOfSeries.name)) : "";
                        const itemName = cleanMediaTitle(obj.name);
                        const pubYear = String(obj.datePublished || obj.dateCreated || "").match(/\b(19\d{2}|20[0-3]\d)\b/)?.[1] || "";
                        if (seriesName && itemName && !itemName.toLowerCase().includes(seriesName.toLowerCase())) {
                            return (seriesName + " · " + itemName).slice(0, 160);
                        }
                        if (itemName) {
                            if (pubYear && !itemName.includes(pubYear)) return (itemName + " (" + pubYear + ")").slice(0, 160);
                            return withDetectedYear(itemName);
                        }
                    }
                }
            }
        } catch (_) {}

        // 2. Site-specific movie header + year (e.g. #movie-info h1 + sibling h2 year on YTS)
        try {
            const mTitle = cleanMediaTitle(document.querySelector("#movie-info h1, .movie-info h1")?.textContent || "");
            if (mTitle && mTitle.length >= 2) {
                return withDetectedYear(mTitle);
            }
        } catch (_) {}

        // 3. Work title headings / OpenGraph
        const selectors = [
            "ytd-watch-metadata h1 yt-formatted-string",
            "ytd-watch-metadata h1",
            "#movie-title", ".movie-title", ".series-title", ".video-title",
            "[class*='movie-title']", "[class*='series-title']", "[class*='film-title']",
            "[class*='title-name']", "h1[class*='title']", "main h1", "article h1", "h1", "h2.title"
        ];
        for (const sel of selectors) {
            try {
                for (const el of document.querySelectorAll(sel)) {
                    const txt = cleanMediaTitle(el.textContent || "");
                    if (txt && txt.length >= 2) return thisIsYouTube ? txt : withDetectedYear(txt);
                }
            } catch (_) {}
        }
        for (const metaSel of ['meta[property="og:title"]', 'meta[name="twitter:title"]', 'meta[name="title"]']) {
            try {
                const m = document.querySelector(metaSel);
                const txt = cleanMediaTitle(m?.getAttribute("content") || "");
                if (txt) return thisIsYouTube ? txt : withDetectedYear(txt);
            } catch (_) {}
        }

        // 4. Cleaned document.title
        const docCleaned = cleanMediaTitle(document.title || "");
        if (docCleaned) return thisIsYouTube ? docCleaned : withDetectedYear(docCleaned);

        // 4. Fallback from URL or subtitle filename
        for (const subInfo of subtitleNetwork.values()) {
            try {
                const leaf = new URL(subInfo.url).pathname.split("/").pop() || "";
                const base = cleanMediaTitle(decodeURIComponent(leaf).replace(/\.(vtt|srt|ass|ssa)$/i, "").replace(/[._]+/g, " "));
                if (base && base.length >= 3) return base;
            } catch (_) {}
        }
        return "";
    }

    function sendHello() {
        try {
            browser.runtime.sendMessage({ hello: true }).catch(() => {});
        } catch (_) {}
    }

    function sendClearTab() {
        try {
            browser.runtime.sendMessage({ action: "clearTab" }).catch(() => {});
        } catch (_) {}
    }

    function clearDiscoveredState(includePlayerResponse = true) {
        previous = "";
        discoveryRevision++;
        try { hlsAbort.abort(); } catch (_) {}
        hlsAbort = new AbortController();
        protectedVideo = false;
        pillDismissed = false;
        pillOffsetX = 0;
        pillOffsetY = 0;
        dismissedUrls.clear();
        network.clear();
        subtitleNetwork.clear();
        inspected.clear();
        youtubeTracks.clear();
        blobSubtitleCache.clear();
        blobSubtitleLoading.clear();
        if (includePlayerResponse) playerResponse = null;
        hideFloatingButton();
    }

    function rescanPage() {
        clearDiscoveredState(false);
        sendClearTab();
        scanDomMedia();
        scanPerformanceResources();
        scanDomSubtitles();
        for (const [u, item] of network) {
            if (item && item.format === "hls" && !item.masterQuality) {
                readHls(u, Boolean(item.live), item);
            }
        }
        publish(true);
        updateFloatingCastButton();
    }

    function connectNative() {
        try {
            port = browser.runtime.connectNative("cinemaVideo");
            port.onMessage.addListener(message => {
                if (!message) return;
                if (message.action === "pause") {
                    suppressUntil = Date.now() + 900;
                    pauseMedia();
                    publish(true);
                } else if (message.action === "mute" || message.action === "toggleMute") {
                    suppressUntil = Date.now() + 900;
                    muteAllMedia(true);
                    publish(true);
                } else if (message.action === "unmute") {
                    muteAllMedia(false);
                    publish(true);
                } else if (message.action === "rescan") {
                    rescanPage();
                } else if (message.action === "setZoom") {
                    applyPageZoom(message.percent);
                }
            });
            port.onDisconnect.addListener(() => { port = null; });
        } catch (_) {
            port = null;
        }
    }

    let page = location.href;
    let protectedVideo = false;
    const network = new Map();
    const subtitleNetwork = new Map();
    const inspected = new Set();
    const youtubeTracks = new Map(); // itag -> { url, size, referer, origin, userAgent, cookie, authorization }
    const blobSubtitleCache = new Map(); // blob/data url -> { content, size, language }
    const blobSubtitleLoading = new Set();
    const thisIsYouTube = /(^|\.)(youtube\.com|youtu\.be)$/i.test(location.hostname);
    let playerResponse = null;
    const directMediaPattern = /https?:\/\/[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd|mkv|webm|mov)(?:[?#][^\s"'<>\\]*)?/gi;

    function collectUrlsFromText(text) {
        if (typeof text !== "string" || text.length < 10 || text.length > 524288) return;
        const normalized = text.replace(/\\\//g, "/");
        let match;
        directMediaPattern.lastIndex = 0;
        while ((match = directMediaPattern.exec(normalized)) !== null && network.size < 35) {
            try {
                const candidate = new URL(match[0], location.href);
                if (candidate.username || candidate.password) continue;
                if (/(^|\.)googlevideo\.com$/i.test(candidate.hostname)) continue;
                if (/\.(png|jpe?g|gif|webp|css|js|svg)(\?|$)/i.test(candidate.pathname)) continue;
                const format = /\.m3u8(\?|$)/i.test(candidate.href) ? "hls"
                    : /\.mpd(\?|$)/i.test(candidate.href) ? "dash" : "progressive";
                if (!network.has(candidate.href)) {
                    const item = entry(candidate.href, { format, height: inferHeightFromUrl(candidate.href) });
                    network.set(candidate.href, item);
                    if (format === "hls") readHls(candidate.href, false, item);
                }
            } catch (_) {}
        }
    }

    if (typeof exportFunction === "function" && window.wrappedJSObject) {
        const target = window.wrappedJSObject;
        const isPlayer = value => {
            try {
                const url = new URL(String(value?.url || value), location.href);
                return url.origin === location.origin && url.pathname === "/youtubei/v1/player";
            } catch (_) { return false; }
        };
        const remember = response => {
            if (response?.videoDetails?.videoId && response?.streamingData) {
                playerResponse = response;
                publish();
            }
        };
        try {
            const fetchFromPage = target.fetch;
            if (typeof fetchFromPage === "function") {
                exportFunction(function (...args) {
                    const reqUrl = String(args[0]?.url || args[0] || "");
                    const promise = Reflect.apply(fetchFromPage, this, args);
                    if (thisIsYouTube && isPlayer(args[0])) {
                        promise.then(exportFunction(response => {
                            try {
                                response.clone().json().then(exportFunction(remember, target),
                                    exportFunction(() => {}, target));
                            } catch (_) {}
                        }, target), exportFunction(() => {}, target));
                    } else if (/m3u8|mpd|mp4|mkv|webm|video|stream|file|episode|movie|watch|api|player/i.test(reqUrl)) {
                        promise.then(exportFunction(response => {
                            try {
                                const ct = (response.headers?.get?.("content-type") || "").toLowerCase();
                                if (ct.includes("json") || ct.includes("mpegurl") || ct.includes("javascript") || ct.includes("text")) {
                                    response.clone().text().then(exportFunction(body => {
                                        const before = network.size;
                                        collectUrlsFromText(body);
                                        if (network.size !== before) publish();
                                    }, target), exportFunction(() => {}, target));
                                }
                            } catch (_) {}
                        }, target), exportFunction(() => {}, target));
                    }
                    return promise;
                }, target, { defineAs: "fetch" });
            }
            const urls = new WeakMap();
            const xhrUrls = new WeakMap();
            const xhr = target.XMLHttpRequest.prototype;
            const open = xhr.open;
            const send = xhr.send;
            exportFunction(function (...args) {
                const reqUrl = String(args[1] || "");
                urls.set(this, thisIsYouTube && isPlayer(reqUrl));
                xhrUrls.set(this, reqUrl);
                return Reflect.apply(open, this, args);
            }, xhr, { defineAs: "open" });
            exportFunction(function (...args) {
                const reqUrl = xhrUrls.get(this) || "";
                if (urls.get(this)) {
                    this.addEventListener("load", exportFunction(() => {
                        try {
                            remember(this.responseType === "json" ? this.response : JSON.parse(this.responseText));
                        } catch (_) {}
                    }, target), { once: true });
                } else if (/m3u8|mpd|mp4|mkv|webm|video|stream|file|episode|movie|watch|api|player/i.test(reqUrl)) {
                    this.addEventListener("load", exportFunction(() => {
                        try {
                            const txt = typeof this.responseText === "string" ? this.responseText
                                : (this.responseType === "json" && this.response ? JSON.stringify(this.response) : "");
                            if (txt) {
                                const before = network.size;
                                collectUrlsFromText(txt);
                                if (network.size !== before) publish();
                            }
                        } catch (_) {}
                    }, target), { once: true });
                }
                return Reflect.apply(send, this, args);
            }, xhr, { defineAs: "send" });
        } catch (_) {}
    }

    function inferSubMime(url, rawMime = "") {
        if (rawMime) return rawMime;
        if (/\.vtt(\?|$)/i.test(url) || /fmt=vtt/i.test(url)) return "text/vtt";
        if (/\.srt(\?|$)/i.test(url)) return "application/x-subrip";
        if (/\.ass(\?|$)|\.ssa(\?|$)/i.test(url)) return "text/x-ssa";
        if (/\.m3u8(\?|$)/i.test(url)) return "application/vnd.apple.mpegurl";
        return "";
    }

    function detectLanguageFromText(sample) {
        if (!sample || typeof sample !== "string") return "";
        const lines = sample.replace(/^\uFEFF/, "").split(/\r?\n/)
            .map(l => l.trim())
            .filter(l => l && !/^WEBVTT/i.test(l) && !/^\d+$/.test(l)
                && !/-->/.test(l) && !/^(NOTE|STYLE|REGION|\[|Script Info|Format:|Dialogue:)/i.test(l));
        const snippet = lines.slice(0, 6).join(" ").replace(/<[^>]+>/g, " ").replace(/\{[^}]*\}/g, " ");
        const hasAr = /[\u0600-\u06FF]/.test(snippet);
        const hasLatin = /[A-Za-z]{2,}/.test(snippet);
        if (hasAr && hasLatin) return "En & Ar";
        if (hasAr) return "ar";
        if (hasLatin) return "en";
        return "";
    }

    function rememberSubtitle(subUrl, meta = {}) {
        try {
            const sub = new URL(subUrl, location.href);
            const isBlobOrData = sub.protocol === "blob:" || sub.protocol === "data:";
            if (sub.username || sub.password || (!isBlobOrData && !/^https?:$/i.test(sub.protocol))) return;
            if (subtitleNetwork.size >= 30 && !subtitleNetwork.has(sub.href)) {
                const oldest = subtitleNetwork.keys().next().value;
                if (oldest) subtitleNetwork.delete(oldest);
            }
            const prev = subtitleNetwork.get(sub.href) || {};
            subtitleNetwork.set(sub.href, {
                url: sub.href,
                mime: meta.mime || prev.mime || inferSubMime(sub.href),
                language: meta.language || prev.language || "",
                label: meta.label || prev.label || "",
                size: Number(meta.size) || prev.size || 0,
                content: meta.content || prev.content || "",
                isDefault: Boolean(meta.isDefault || prev.isDefault),
                referer: meta.referer || prev.referer || location.href,
                origin: meta.origin || prev.origin || location.origin,
                userAgent: meta.userAgent || prev.userAgent || navigator.userAgent,
                cookie: meta.cookie || prev.cookie || document.cookie || "",
                authorization: meta.authorization || prev.authorization || ""
            });
        } catch (_) {}
    }

    function serializeCuesToVtt(track) {
        try {
            const cues = track?.cues;
            if (!cues || !cues.length) return "";
            const fmt = sec => {
                const ms = Math.max(0, Math.round(sec * 1000));
                const h = String(Math.floor(ms / 3600000)).padStart(2, "0");
                const m = String(Math.floor((ms % 3600000) / 60000)).padStart(2, "0");
                const s = String(Math.floor((ms % 60000) / 1000)).padStart(2, "0");
                const f = String(ms % 1000).padStart(3, "0");
                return `${h}:${m}:${s}.${f}`;
            };
            const out = ["WEBVTT\n"];
            for (let i = 0; i < cues.length && i < 4000; i++) {
                const c = cues[i];
                if (!c || !c.text) continue;
                out.push(`${fmt(c.startTime)} --> ${fmt(c.endTime)}\n${c.text}\n`);
            }
            return out.join("\n");
        } catch (_) {
            return "";
        }
    }

    async function loadBlobOrDataTrack(rawUrl, trackEl) {
        if (!rawUrl || blobSubtitleCache.has(rawUrl) || blobSubtitleLoading.has(rawUrl)) return;
        blobSubtitleLoading.add(rawUrl);
        const startRev = discoveryRevision;
        try {
            let text = "";
            try {
                const resp = await fetch(rawUrl);
                if (resp.ok) text = await resp.text();
            } catch (_) {}
            if (!text && trackEl?.track) {
                text = serializeCuesToVtt(trackEl.track);
            }
            if (startRev !== discoveryRevision || !text) return;
            const trimmed = text.slice(0, 1400000);
            const size = new Blob([trimmed]).size;
            const lang = detectLanguageFromText(trimmed);
            blobSubtitleCache.set(rawUrl, { content: trimmed, size, language: lang });
            publish();
        } catch (_) {
        } finally {
            blobSubtitleLoading.delete(rawUrl);
        }
    }

    function scanDomSubtitles() {
        for (const video of document.querySelectorAll("video")) {
            for (const trackEl of video.querySelectorAll("track")) {
                const kind = (trackEl.kind || "").toLowerCase();
                if (kind && kind !== "subtitles" && kind !== "captions") continue;
                const rawSrc = trackEl.src || trackEl.getAttribute("src") || "";
                if (!rawSrc) continue;
                const isBlob = rawSrc.startsWith("blob:") || rawSrc.startsWith("data:");
                if (!isBlob && !/^https?:\/\//i.test(rawSrc)) continue;
                const lang = (trackEl.srclang || "").trim();
                const label = (trackEl.label || "").trim();
                const cached = isBlob ? blobSubtitleCache.get(rawSrc) : null;
                if (isBlob && !cached) loadBlobOrDataTrack(rawSrc, trackEl);
                rememberSubtitle(rawSrc, {
                    mime: inferSubMime(rawSrc, isBlob ? "text/vtt" : ""),
                    language: lang || cached?.language || "",
                    label,
                    size: cached?.size || 0,
                    content: cached?.content || "",
                    isDefault: Boolean(trackEl.default || trackEl.track?.mode === "showing")
                });
            }
        }
    }

    function scanPerformanceResources() {
        try {
            if (!performance || typeof performance.getEntriesByType !== "function") return;
            const entries = performance.getEntriesByType("resource") || [];
            for (let i = Math.max(0, entries.length - 250); i < entries.length; i++) {
                const res = entries[i];
                const name = res?.name;
                if (!name || typeof name !== "string") continue;
                if (!/^https?:\/\//i.test(name)) continue;
                let parsed;
                try { parsed = new URL(name); } catch (_) { continue; }
                const size = Number(res.decodedBodySize) || Number(res.encodedBodySize) || Number(res.transferSize) || 0;
                if (/\.(vtt|srt|ass|ssa)(\?|$)/i.test(parsed.pathname)) {
                    rememberSubtitle(parsed.href, {
                        mime: inferSubMime(parsed.href),
                        size
                    });
                } else if (/\.(m3u8|mp4|mpd|mkv|webm|mov)(\?|$)/i.test(parsed.pathname)
                        && !/(^|\.)googlevideo\.com$/i.test(parsed.hostname)) {
                    const format = /\.m3u8(\?|$)/i.test(parsed.pathname) ? "hls"
                        : /\.mpd(\?|$)/i.test(parsed.pathname) ? "dash" : "progressive";
                    if (!network.has(parsed.href)) {
                        const item = entry(parsed.href, {
                            format,
                            height: inferHeightFromUrl(parsed.href),
                            size
                        });
                        network.set(parsed.href, item);
                        if (format === "hls") readHls(parsed.href, false, item);
                    } else if (size > 0) {
                        const cur = network.get(parsed.href);
                        if (cur && !cur.size) cur.size = size;
                    }
                }
            }
        } catch (_) {}
    }

    function collectedSubtitles() {
        scanDomSubtitles();
        scanPerformanceResources();
        const list = [];
        for (const subInfo of subtitleNetwork.values()) {
            if (subInfo.url.startsWith("blob:") && !subInfo.content) continue;
            list.push({
                url: subInfo.url,
                mime: subInfo.mime || inferSubMime(subInfo.url),
                language: subInfo.language || "",
                label: subInfo.label || "",
                size: Number(subInfo.size) || 0,
                content: subInfo.content || "",
                isDefault: Boolean(subInfo.isDefault),
                referer: subInfo.referer || location.href,
                origin: subInfo.origin || location.origin,
                userAgent: subInfo.userAgent || navigator.userAgent,
                cookie: subInfo.cookie || document.cookie || "",
                authorization: subInfo.authorization || ""
            });
        }
        return list.slice(0, 30);
    }

    function bestSubtitle(allSubs) {
        const list = allSubs || collectedSubtitles();
        let best = null;
        let bestScore = -1;
        let seq = 0;
        for (const subInfo of list) {
            seq++;
            let score = 30 + Math.min(seq, 20);
            if (subInfo.isDefault) score += 25;
            const hint = ((subInfo.language || "") + " " + (subInfo.label || "") + " " + subInfo.url).toLowerCase();
            if (/(^|\b|_|-)(ar|ara|arabic)(\b|_|-)|عرب/i.test(hint)) score += 35;
            if (score >= bestScore) {
                bestScore = score;
                best = subInfo;
            }
        }
        return best;
    }

    function entry(url, extra = {}) {
        const cleanTitle = extractCleanMediaTitle();
        return {
            url,
            title: cleanTitle || String(document.title || "").slice(0, 160),
            referer: extra.referer || location.href,
            origin: extra.origin || location.origin,
            userAgent: extra.userAgent || navigator.userAgent,
            cookie: extra.cookie || document.cookie || "",
            authorization: extra.authorization || "",
            ...extra
        };
    }

    function getActiveVideoInfo() {
        let bestVideo = null;
        let bestArea = 0;
        for (const el of document.querySelectorAll("video")) {
            if (el.mediaKeys) continue;
            const rect = el.getBoundingClientRect();
            const area = Math.max(0, rect.width) * Math.max(0, rect.height);
            const eligible = (el.duration >= 5 || el.duration === Infinity || el.readyState >= 2)
                && rect.width >= 120 && rect.height >= 80;
            if (!eligible) continue;
            const playing = !el.paused && !el.ended && el.readyState >= 2;
            if (playing && area >= bestArea) {
                bestArea = area;
                bestVideo = el;
            }
        }
        if (!bestVideo) return { element: null, url: "", height: 0, playing: false };
        let activeH = 0;
        if (bestVideo.videoWidth > 0 && bestVideo.videoHeight > 0) {
            activeH = normalizeResolutionHeight(bestVideo.videoWidth, bestVideo.videoHeight);
        }
        if (activeH <= 0 && thisIsYouTube) {
            try {
                const q = document.getElementById("movie_player")?.wrappedJSObject?.getPlaybackQuality?.();
                const qMap = { highres: 2160, hd2160: 2160, hd1440: 1440, hd1080: 1080, hd720: 720, large: 480, medium: 360, small: 240 };
                if (q && qMap[q]) activeH = qMap[q];
            } catch (_) {}
        }
        const src = bestVideo.currentSrc || bestVideo.src || "";
        if (activeH <= 0 && src) activeH = inferHeightFromUrl(src);
        return { element: bestVideo, url: src, height: activeH, playing: true };
    }

    function scanDomMedia() {
        for (const el of document.querySelectorAll("video, source, a[href], [data-src]")) {
            const raw = el.currentSrc || el.src || el.getAttribute("src") || el.getAttribute("data-src")
                || (el.tagName === "A" ? el.getAttribute("href") : "");
            if (!raw || raw.startsWith("blob:") || raw.startsWith("data:")) continue;
            try {
                const u = new URL(raw, location.href);
                if (!/^https?:$/i.test(u.protocol) || u.username || u.password) continue;
                if (/(^|\.)googlevideo\.com$/i.test(u.hostname)) continue;
                const isVideoTag = el.tagName === "VIDEO" || el.tagName === "SOURCE";
                const isMediaExt = /\.(m3u8|mp4|mpd|mkv|webm|mov)(\?|$)/i.test(u.pathname);
                if (!isVideoTag && !isMediaExt) continue;
                const format = /\.m3u8(\?|$)/i.test(u.pathname) ? "hls"
                    : /\.mpd(\?|$)/i.test(u.pathname) ? "dash" : "progressive";
                let h = inferHeightFromUrl(u.href);
                if (h <= 0 && el.tagName === "VIDEO" && el.videoWidth > 0 && el.videoHeight > 0) {
                    h = normalizeResolutionHeight(el.videoWidth, el.videoHeight);
                }
                if (!network.has(u.href)) {
                    const item = entry(u.href, { format, height: h });
                    network.set(u.href, item);
                    if (format === "hls") readHls(u.href, false, item);
                } else if (h > 0) {
                    const existing = network.get(u.href);
                    if (existing && !existing.height) existing.height = h;
                }
            } catch (_) {}
        }
    }

    function reset() {
        if (page === location.href) return;
        page = location.href;
        clearDiscoveredState(true);
        sendClearTab();
        sendHello();
    }

    function youtubeOptions(items) {
        const original = window.wrappedJSObject;
        const videoId = new URL(location.href).searchParams.get("v")
            || location.pathname.match(/^\/(?:shorts\/|embed\/|live\/)?([\w-]{11})$/)?.[1];
        if (!videoId) return;

        let response = null;
        if (original) {
            try {
                const current = document.getElementById("movie_player")?.wrappedJSObject?.getPlayerResponse?.();
                response = [playerResponse, current, original.ytInitialPlayerResponse].find(
                    candidate => candidate?.videoDetails?.videoId === videoId && candidate?.streamingData);
            } catch (_) {}
        }

        try {
            if (response?.videoDetails?.videoId === videoId) {
                const capTracks = response?.captions?.playerCaptionsTracklistRenderer?.captionTracks;
                if (Array.isArray(capTracks)) {
                    for (const ct of capTracks.slice(0, 12)) {
                        if (!ct?.baseUrl) continue;
                        let vttUrl = String(ct.baseUrl);
                        if (!/[?&]fmt=/i.test(vttUrl)) vttUrl += (vttUrl.includes("?") ? "&" : "?") + "fmt=vtt";
                        rememberSubtitle(vttUrl, {
                            mime: "text/vtt",
                            language: ct.languageCode || "",
                            label: ct.name?.simpleText || ct.name?.runs?.[0]?.text || ct.languageCode || ""
                        });
                    }
                }
                const data = response.streamingData;
                if (data?.licenseInfos?.length || data?.drmFamilies?.length) return;
                for (const [format, candidate] of [["hls", data?.hlsManifestUrl], ["dash", data?.dashManifestUrl]]) {
                    if (!candidate || !/^https:\/\/[^/]+\.googlevideo\.com\//i.test(candidate)) continue;
                    const url = String(candidate);
                    const live = response.videoDetails.isLive === true;
                    const item = entry(url, { format, manifest: true, live });
                    if (!items.has(url)) items.set(url, item);
                    if (format === "hls") readHls(url, live, item);
                }
                if (response.videoDetails.isLive === true) return;
                const formats = Array.from(data?.formats || []);
                const adaptive = Array.from(data?.adaptiveFormats || []);
                const usable = item => item?.url && /^https:\/\/[\w.-]+\.googlevideo\.com\//i.test(item.url)
                    && !item.drmFamilies?.length;
                const resolveTrackUrl = item => {
                    const originalUrl = new URL(item.url);
                    const observed = youtubeTracks.get(String(item.itag));
                    if (observed?.url) {
                        try {
                            const obsUrl = new URL(observed.url);
                            if (obsUrl.searchParams.get("id") === originalUrl.searchParams.get("id")) {
                                const signed = (obsUrl.searchParams.get("sparams") || "").split(",");
                                if (!signed.includes("range")) {
                                    obsUrl.searchParams.delete("range");
                                    return obsUrl.href;
                                }
                            }
                        } catch (_) {}
                    }
                    return item.url;
                };
                const trackSize = (item, resolvedUrl) => {
                    const cLen = Number(item?.contentLength) || 0;
                    if (cLen > 0) return cLen;
                    try {
                        const clenParam = Number(new URL(resolvedUrl).searchParams.get("clen")) || 0;
                        if (clenParam > 0) return clenParam;
                    } catch (_) {}
                    return 0;
                };
                const audio = adaptive.filter(item => usable(item) && /^audio\//.test(item.mimeType));
                audio.sort((a, b) => (b.bitrate || 0) - (a.bitrate || 0));
                for (const item of formats.concat(adaptive).slice(0, 100)) {
                    const isVideo = /^video\//.test(item?.mimeType || "");
                    if (!usable(item) || !isVideo) continue;
                    const h = normalizeResolutionHeight(item.width || 0, item.height || 0)
                        || YT_VIDEO_ITAG_HEIGHT[String(item.itag)] || 0;
                    if (!h) continue;
                    const split = !formats.includes(item);
                    const track = split ? (audio.find(candidate =>
                        candidate.mimeType.split(";")[0].slice(6) === item.mimeType.split(";")[0].slice(6)) || audio[0]) : null;
                    if (split && !track) continue;
                    const url = resolveTrackUrl(item);
                    const aUrl = track ? resolveTrackUrl(track) : undefined;
                    const vSize = trackSize(item, url);
                    const aSize = track ? trackSize(track, aUrl) : 0;
                    items.set(url, entry(url, {
                        id: "yt:" + item.itag,
                        title: String(response.videoDetails.title || document.title).slice(0, 160),
                        height: h,
                        fps: item.fps || 0,
                        bitrate: item.bitrate || 0,
                        size: vSize > 0 ? vSize + aSize : 0,
                        format: split ? "split" : "progressive",
                        audioUrl: aUrl
                    }));
                }
                const bestAudio = audio[0];
                if (bestAudio) {
                    const url = resolveTrackUrl(bestAudio);
                    items.set(url, entry(url, {
                        id: "yt:audio",
                        format: "audio",
                        bitrate: bestAudio.bitrate || 0,
                        size: trackSize(bestAudio, url)
                    }));
                }
            }

            // Fallback / supplement from intercepted googlevideo.com/videoplayback requests
            if (youtubeTracks.size > 0) {
                let bestObsAudio = null;
                for (const [itag, meta] of youtubeTracks.entries()) {
                    if (!YT_AUDIO_ITAGS.has(String(itag))) continue;
                    try {
                        const au = new URL(meta.url);
                        const signed = (au.searchParams.get("sparams") || "").split(",");
                        if (signed.includes("range")) continue;
                        au.searchParams.delete("range");
                        const clen = Number(au.searchParams.get("clen")) || Number(meta.size) || 0;
                        if (!bestObsAudio || clen > bestObsAudio.size) {
                            bestObsAudio = { url: au.href, size: clen, meta };
                        }
                    } catch (_) {}
                }
                for (const [itag, meta] of youtubeTracks.entries()) {
                    const h = YT_VIDEO_ITAG_HEIGHT[String(itag)];
                    if (!h) continue;
                    const existingSameItag = [...items.values()].some(it => it.id === "yt:" + itag);
                    if (existingSameItag) continue;
                    try {
                        const vu = new URL(meta.url);
                        const signed = (vu.searchParams.get("sparams") || "").split(",");
                        if (signed.includes("range")) continue;
                        vu.searchParams.delete("range");
                        const vSize = Number(vu.searchParams.get("clen")) || Number(meta.size) || 0;
                        const isProg = itag === "18" || itag === "22";
                        if (!isProg && !bestObsAudio) continue;
                        items.set(vu.href, entry(vu.href, {
                            id: "yt:" + itag,
                            title: document.title.slice(0, 160),
                            height: h,
                            size: vSize > 0 ? vSize + (isProg ? 0 : (bestObsAudio?.size || 0)) : 0,
                            format: isProg ? "progressive" : "split",
                            audioUrl: isProg ? undefined : bestObsAudio.url,
                            referer: meta.referer || location.href,
                            origin: meta.origin || location.origin,
                            userAgent: meta.userAgent || navigator.userAgent,
                            cookie: meta.cookie || document.cookie || "",
                            authorization: meta.authorization || ""
                        }));
                    } catch (_) {}
                }
            }
        } catch (_) {}
    }

    function publish(force = false) {
        reset();
        if (!port) connectNative();
        scanDomMedia();
        scanPerformanceResources();
        const activeInfo = getActiveVideoInfo();
        const items = protectedVideo ? new Map() : new Map(network);
        for (const video of document.querySelectorAll("video")) {
            if (protectedVideo || video.mediaKeys || (!(video.duration >= 5) && video.duration !== Infinity)) continue;
            const source = video.currentSrc || video.src;
            if (source && /^https?:\/\//.test(source) && !/(^|\.)googlevideo\.com$/i.test(new URL(source).hostname)) {
                const existing = items.get(source);
                const intrinsicH = (video.videoWidth > 0 && video.videoHeight > 0)
                    ? normalizeResolutionHeight(video.videoWidth, video.videoHeight)
                    : 0;
                const h = existing?.height || intrinsicH || inferHeightFromUrl(source);
                items.set(source, entry(source, { ...existing, height: h }));
            }
        }
        if (thisIsYouTube && !protectedVideo) youtubeOptions(items);

        // Mark currently playing stream(s) with active = true
        if (activeInfo.playing && !protectedVideo) {
            let matchedAny = false;
            for (const item of items.values()) {
                if (activeInfo.url && item.url === activeInfo.url && !item.manifest) {
                    item.active = true;
                    matchedAny = true;
                } else if (activeInfo.height > 0 && item.height === activeInfo.height && item.format !== "audio") {
                    item.active = true;
                    matchedAny = true;
                }
            }
            if (!matchedAny) {
                for (const item of items.values()) {
                    if (activeInfo.url && item.url === activeInfo.url) {
                        item.active = true;
                        matchedAny = true;
                        break;
                    }
                }
            }
        }

        const allSubs = protectedVideo ? [] : collectedSubtitles();
        const sub = bestSubtitle(allSubs);
        const out = [...items.values()].slice(0, 30).map(item => {
            if (!sub || item.subtitleUrl) return item;
            return {
                ...item,
                subtitleUrl: sub.url,
                subtitleMime: sub.mime || "",
                subtitleLanguage: sub.language || "",
                subtitleContent: sub.content || "",
                subtitleReferer: sub.referer || item.referer || "",
                subtitleOrigin: sub.origin || item.origin || "",
                subtitleUserAgent: sub.userAgent || item.userAgent || "",
                subtitleCookie: sub.cookie || item.cookie || "",
                subtitleAuthorization: sub.authorization || item.authorization || ""
            };
        });
        const cleanTitle = extractCleanMediaTitle();
        updateFloatingCastButton(activeInfo, out.length > 0);
        const payload = JSON.stringify({
            frameToken,
            isTopFrame: window === window.top,
            revision: discoveryRevision,
            metaTitle: cleanTitle,
            audioPlaying: isAudioPlaying(),
            items: out,
            subtitles: allSubs
        });
        if (!force && payload === previous) return;
        previous = payload;
        browser.runtime.sendNativeMessage("cinemaVideo", payload).catch(() => { previous = ""; });
    }

    // ---- Floating Cast Pill on Active Video (Top-Right, Dismissible & Draggable) ----
    let floatHost = null;
    let floatBtn = null;
    let floatCloseBtn = null;
    let floatBodyBtn = null;
    let floatLabel = null;
    let observedVideo = null;
    let videoResizeObs = null;
    let pillDismissed = false;
    const dismissedVideos = new WeakSet();
    const dismissedUrls = new Set();
    let pillOffsetX = 0;
    let pillOffsetY = 0;
    let lastAnchorLeft = 0;
    let lastAnchorTop = 0;
    let isDraggingPill = false;
    let dragStartX = 0;
    let dragStartY = 0;
    let dragOriginOffX = 0;
    let dragOriginOffY = 0;
    let dragMoved = false;
    let lastSheetOpenAt = 0;

    function applyFloatingPosition(rect) {
        if (!floatHost || !floatBtn || !rect) return;
        const vw = window.innerWidth || document.documentElement.clientWidth || 0;
        const vh = window.innerHeight || document.documentElement.clientHeight || 0;
        const btnW = floatBtn.offsetWidth || 126;
        const btnH = floatBtn.offsetHeight || 30;
        const baseTop = Math.max(6, Math.min(vh - btnH - 6, rect.top + 10));
        const baseLeft = Math.max(6, Math.min(vw - btnW - 6, rect.right - btnW - 10));
        lastAnchorLeft = baseLeft;
        lastAnchorTop = baseTop;
        const left = Math.max(4, Math.min(vw - btnW - 4, baseLeft + pillOffsetX));
        const top = Math.max(4, Math.min(vh - btnH - 4, baseTop + pillOffsetY));
        floatHost.style.transform = `translate3d(${Math.round(left)}px, ${Math.round(top)}px, 0)`;
    }

    function openVideoSheetFromPill() {
        const now = Date.now();
        if (now - lastSheetOpenAt < 320) return;
        lastSheetOpenAt = now;
        browser.runtime.sendNativeMessage("cinemaVideo", JSON.stringify({
            action: "openVideoSheet",
            frameToken
        })).catch(() => {});
    }

    function dismissFloatingPill() {
        pillDismissed = true;
        if (observedVideo) {
            try { dismissedVideos.add(observedVideo); } catch (_) {}
            const src = observedVideo.currentSrc || observedVideo.src;
            if (src) dismissedUrls.add(src);
        }
        hideFloatingButton();
    }

    function ensureFloatingButton() {
        if (floatHost && document.documentElement?.contains(floatHost)) return;
        const rootParent = document.documentElement || document.body;
        if (!rootParent) return;
        floatHost = document.createElement("div");
        floatHost.setAttribute("data-localcinema-cast", "1");
        floatHost.style.cssText = "position:fixed;top:0;left:0;z-index:2147483647;pointer-events:none;display:none;";
        const shadow = floatHost.attachShadow({ mode: "closed" });
        const style = document.createElement("style");
        style.textContent = `
            .lc-cast-pill {
                pointer-events: auto;
                display: inline-flex;
                align-items: center;
                border-radius: 18px;
                border: 1.5px solid #e5b84b;
                background: rgba(18, 19, 23, 0.90);
                color: #fafafa;
                font: 700 12px/1 system-ui, -apple-system, sans-serif;
                letter-spacing: 0.2px;
                box-shadow: 0 4px 14px rgba(0, 0, 0, 0.55);
                backdrop-filter: blur(6px);
                user-select: none;
                touch-action: none;
                -webkit-tap-highlight-color: transparent;
                direction: ltr;
                overflow: hidden;
            }
            .lc-cast-close {
                all: unset;
                box-sizing: border-box;
                display: inline-flex;
                align-items: center;
                justify-content: center;
                padding: 6px 8px 6px 10px;
                color: #d4d4d8;
                font: 700 12px/1 system-ui, sans-serif;
                border-right: 1px solid rgba(255, 255, 255, 0.18);
                cursor: pointer;
                touch-action: manipulation;
            }
            .lc-cast-close:active { background: rgba(255, 255, 255, 0.14); color: #ffffff; }
            .lc-cast-body {
                all: unset;
                box-sizing: border-box;
                display: inline-flex;
                align-items: center;
                gap: 6px;
                padding: 6px 11px 6px 8px;
                color: #fafafa;
                font: 700 12px/1 system-ui, -apple-system, sans-serif;
                cursor: grab;
                touch-action: none;
            }
            .lc-cast-body:active { opacity: 0.86; }
            .lc-cast-icon { width: 14px; height: 14px; fill: #e5b84b; flex-shrink: 0; }
        `;
        shadow.appendChild(style);
        floatBtn = document.createElement("div");
        floatBtn.className = "lc-cast-pill";
        floatBtn.setAttribute("role", "group");

        floatCloseBtn = document.createElement("button");
        floatCloseBtn.type = "button";
        floatCloseBtn.className = "lc-cast-close";
        floatCloseBtn.setAttribute("aria-label", "Hide cast button");
        floatCloseBtn.textContent = "✕";

        floatBodyBtn = document.createElement("button");
        floatBodyBtn.type = "button";
        floatBodyBtn.className = "lc-cast-body";
        floatBodyBtn.innerHTML = `<svg class="lc-cast-icon" viewBox="0 0 24 24"><path d="M1 18v3h3c0-1.66-1.34-3-3-3zm0-4v2c2.76 0 5 2.24 5 5h2c0-3.87-3.13-7-7-7zm0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.93-11-11-11zm20-7H3c-1.1 0-2 .9-2 2v3h2V5h18v14h-7v2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z"/></svg>`;
        floatLabel = document.createElement("span");
        floatLabel.textContent = "Cast";
        floatBodyBtn.appendChild(floatLabel);

        floatBtn.appendChild(floatCloseBtn);
        floatBtn.appendChild(floatBodyBtn);

        const stopCloseEv = ev => {
            ev.stopPropagation();
            if (typeof ev.stopImmediatePropagation === "function") ev.stopImmediatePropagation();
        };
        floatCloseBtn.addEventListener("touchstart", stopCloseEv, { passive: true, capture: true });
        floatCloseBtn.addEventListener("pointerdown", stopCloseEv, true);
        floatCloseBtn.addEventListener("mousedown", stopCloseEv, true);
        floatCloseBtn.addEventListener("click", ev => {
            ev.preventDefault();
            stopCloseEv(ev);
            dismissFloatingPill();
        }, true);

        const beginDrag = (clientX, clientY) => {
            isDraggingPill = true;
            dragMoved = false;
            dragStartX = clientX;
            dragStartY = clientY;
            dragOriginOffX = pillOffsetX;
            dragOriginOffY = pillOffsetY;
        };
        const moveDrag = (clientX, clientY, ev) => {
            if (!isDraggingPill) return;
            const dx = clientX - dragStartX;
            const dy = clientY - dragStartY;
            if (!dragMoved && Math.hypot(dx, dy) > 6) {
                dragMoved = true;
            }
            if (dragMoved) {
                if (ev && ev.cancelable) ev.preventDefault();
                if (ev) ev.stopPropagation();
                pillOffsetX = dragOriginOffX + dx;
                pillOffsetY = dragOriginOffY + dy;
                if (observedVideo) applyFloatingPosition(observedVideo.getBoundingClientRect());
            }
        };
        const endDrag = ev => {
            if (!isDraggingPill) return;
            const wasDragged = dragMoved;
            isDraggingPill = false;
            dragMoved = false;
            if (wasDragged) {
                if (observedVideo && floatBtn) {
                    const rect = observedVideo.getBoundingClientRect();
                    const vw = window.innerWidth || document.documentElement.clientWidth || 0;
                    const vh = window.innerHeight || document.documentElement.clientHeight || 0;
                    const btnW = floatBtn.offsetWidth || 126;
                    const btnH = floatBtn.offsetHeight || 30;
                    const clampedLeft = Math.max(4, Math.min(vw - btnW - 4, lastAnchorLeft + pillOffsetX));
                    const clampedTop = Math.max(4, Math.min(vh - btnH - 4, lastAnchorTop + pillOffsetY));
                    pillOffsetX = clampedLeft - lastAnchorLeft;
                    pillOffsetY = clampedTop - lastAnchorTop;
                    applyFloatingPosition(rect);
                }
                if (ev && ev.cancelable) ev.preventDefault();
                if (ev) ev.stopPropagation();
            } else {
                if (ev) ev.stopPropagation();
                openVideoSheetFromPill();
            }
        };

        floatBodyBtn.addEventListener("touchstart", ev => {
            ev.stopPropagation();
            if (ev.touches && ev.touches.length === 1) {
                beginDrag(ev.touches[0].clientX, ev.touches[0].clientY);
            }
        }, { passive: true, capture: true });
        floatBodyBtn.addEventListener("touchmove", ev => {
            if (ev.touches && ev.touches.length === 1) {
                moveDrag(ev.touches[0].clientX, ev.touches[0].clientY, ev);
            }
        }, { passive: false, capture: true });
        floatBodyBtn.addEventListener("touchend", ev => endDrag(ev), true);
        floatBodyBtn.addEventListener("touchcancel", () => { isDraggingPill = false; dragMoved = false; }, true);

        floatBodyBtn.addEventListener("mousedown", ev => {
            if (ev.button !== 0) return;
            ev.stopPropagation();
            beginDrag(ev.clientX, ev.clientY);
            const onMove = me => moveDrag(me.clientX, me.clientY, me);
            const onUp = ue => {
                window.removeEventListener("mousemove", onMove, true);
                window.removeEventListener("mouseup", onUp, true);
                endDrag(ue);
            };
            window.addEventListener("mousemove", onMove, true);
            window.addEventListener("mouseup", onUp, true);
        }, true);
        floatBodyBtn.addEventListener("click", ev => {
            ev.preventDefault();
            ev.stopPropagation();
            if (!dragMoved) openVideoSheetFromPill();
        }, true);

        shadow.appendChild(floatBtn);
        rootParent.appendChild(floatHost);
    }

    function hideFloatingButton() {
        if (floatHost) floatHost.style.display = "none";
        if (videoResizeObs) {
            try { videoResizeObs.disconnect(); } catch (_) {}
        }
        observedVideo = null;
    }

    function updateFloatingCastButton(activeInfo = null, hasStreams = null) {
        if (protectedVideo || pillDismissed) {
            hideFloatingButton();
            return;
        }
        const info = activeInfo || getActiveVideoInfo();
        const el = info.element;
        const vidSrc = el ? (el.currentSrc || el.src || "") : "";
        if (!el || !info.playing || dismissedVideos.has(el) || (vidSrc && dismissedUrls.has(vidSrc))) {
            hideFloatingButton();
            return;
        }
        if (hasStreams === false && network.size === 0 && youtubeTracks.size === 0) {
            if (!info.url || info.url.startsWith("blob:")) {
                if (network.size === 0 && !thisIsYouTube) {
                    hideFloatingButton();
                    return;
                }
            }
        }
        const rect = el.getBoundingClientRect();
        const vw = window.innerWidth || document.documentElement.clientWidth || 0;
        const vh = window.innerHeight || document.documentElement.clientHeight || 0;
        if (rect.width < 120 || rect.height < 80
                || rect.bottom <= 8 || rect.top >= vh - 8 || rect.right <= 8 || rect.left >= vw - 8) {
            hideFloatingButton();
            return;
        }
        ensureFloatingButton();
        if (!floatHost || !floatLabel) return;
        floatLabel.textContent = info.height > 0 ? `Cast · ${info.height}p` : "Cast";
        floatHost.style.display = "block";
        observedVideo = el;
        applyFloatingPosition(rect);

        if (typeof ResizeObserver === "function") {
            if (!videoResizeObs) {
                videoResizeObs = new ResizeObserver(() => updateFloatingCastButton());
            } else {
                videoResizeObs.disconnect();
            }
            try { videoResizeObs.observe(el); } catch (_) {}
        }
    }

    async function readHls(url, knownLive = false, baseExtra = {}) {
        if (inspected.size >= 12 || inspected.has(url)) return;
        inspected.add(url);
        const startPage = page;
        const startRev = discoveryRevision;
        const signal = AbortSignal.any
            ? AbortSignal.any([hlsAbort.signal, AbortSignal.timeout(4000)])
            : hlsAbort.signal;
        try {
            const response = await fetch(url, { credentials: "same-origin", signal });
            if (!response.ok || !response.body) return;
            const reader = response.body.getReader();
            let source = "";
            const decoder = new TextDecoder();
            try {
                while (source.length < 262144) {
                    const { value, done } = await reader.read();
                    if (done) break;
                    source += decoder.decode(value, { stream: true });
                }
            } finally { await reader.cancel(); }
            source = source.replace(/^\uFEFF/, "");
            if (location.href !== startPage || startRev !== discoveryRevision || !source.trimStart().startsWith("#EXTM3U")) return;
            const parent = network.get(url) || baseExtra || {};
            const lines = source.split(/\r?\n/);
            const master = lines.some(line => line.startsWith("#EXT-X-STREAM-INF:"));
            const isVod = source.includes("#EXT-X-ENDLIST") || /#EXT-X-PLAYLIST-TYPE\s*:\s*VOD/i.test(source);
            let live = knownLive || (!master && (source.includes("#EXTINF:") || source.includes("#EXT-X-PART:")) && !isVod);

            if (master) {
                for (const line of lines) {
                    if (!/^#EXT-X-MEDIA:/i.test(line) || !/TYPE\s*=\s*SUBTITLES/i.test(line)) continue;
                    const uriMatch = /URI="([^"]+)"/i.exec(line);
                    if (!uriMatch || !uriMatch[1]) continue;
                    try {
                        const subAbs = new URL(uriMatch[1], response.url).href;
                        const lang = /LANGUAGE="([^"]+)"/i.exec(line)?.[1] || /NAME="([^"]+)"/i.exec(line)?.[1] || "";
                        const isDef = /DEFAULT\s*=\s*YES/i.test(line);
                        rememberSubtitle(subAbs, {
                            language: lang,
                            isDefault: isDef,
                            referer: parent.referer,
                            origin: parent.origin,
                            userAgent: parent.userAgent,
                            cookie: parent.cookie,
                            authorization: parent.authorization
                        });
                    } catch (_) {}
                }
            }

            let firstVariantHref = null;
            if (master && !knownLive) {
                for (let i = 0; i < lines.length - 1; i++) {
                    if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
                    const v = lines[i + 1].trim();
                    if (!v || v.startsWith("#")) continue;
                    try {
                        const child = new URL(v, response.url);
                        if (/^https?:$/i.test(child.protocol)) {
                            firstVariantHref = child.href;
                            break;
                        }
                    } catch (_) {}
                }
            }

            if (firstVariantHref) {
                try {
                    const vSignal = AbortSignal.any
                        ? AbortSignal.any([hlsAbort.signal, AbortSignal.timeout(3000)])
                        : hlsAbort.signal;
                    const variant = await fetch(firstVariantHref, { credentials: "same-origin", signal: vSignal });
                    const length = Number(variant.headers.get("Content-Length"));
                    if (length > 262144) { await variant.body?.cancel(); throw new Error("Playlist exceeds discovery limit"); }
                    const playlist = (await variant.text()).replace(/^\uFEFF/, "").slice(0, 262144);
                    if (variant.ok && playlist.trimStart().startsWith("#EXTM3U")) {
                        const childVod = playlist.includes("#EXT-X-ENDLIST") || /#EXT-X-PLAYLIST-TYPE\s*:\s*VOD/i.test(playlist);
                        const childHasSeg = playlist.includes("#EXTINF:") || playlist.includes("#EXT-X-PART:");
                        live = childHasSeg && !childVod;
                    }
                } catch (_) {}
            }

            if (location.href !== startPage || startRev !== discoveryRevision) return;
            network.set(url, entry(url, { ...parent, id: url, format: "hls", manifest: master, masterQuality: false, height: 0, live }));

            if (master) {
                for (let i = 0; i < lines.length - 1 && network.size < 25; i++) {
                    if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
                    const resMatch = /RESOLUTION=(\d+)x(\d+)/.exec(lines[i]);
                    const height = resMatch ? normalizeResolutionHeight(Number(resMatch[1]), Number(resMatch[2])) : 0;
                    const variant = lines[i + 1].trim();
                    if (!height || !variant || variant.startsWith("#")) continue;
                    const key = url + "#height=" + height;
                    // Always emit Master M3U8 URL so EXT-X-MEDIA audio & embedded subtitles are preserved
                    network.set(key, entry(url, {
                        ...parent,
                        id: key,
                        height,
                        format: "hls",
                        manifest: true,
                        masterQuality: true,
                        live,
                        bitrate: Number(/(?:[:,])BANDWIDTH=(\d+)/.exec(lines[i])?.[1]) || 0
                    }));
                }
            }
            publish();
        } catch (_) {}
    }

    browser.runtime.onMessage.addListener(message => {
        reset();
        if (!message) return;
        if (thisIsYouTube && !protectedVideo && typeof message.youtubeTrack === "string") {
            try {
                const observed = new URL(message.youtubeTrack);
                const itag = observed.searchParams.get("itag");
                if (itag) {
                    youtubeTracks.set(itag, {
                        url: observed.href,
                        size: Number(message.size) || 0,
                        referer: message.referer || "",
                        origin: message.origin || "",
                        userAgent: message.userAgent || "",
                        cookie: message.cookie || "",
                        authorization: message.authorization || ""
                    });
                    publish();
                }
            } catch (_) {}
            return;
        }
        if (typeof message.subtitleUrl === "string") {
            rememberSubtitle(message.subtitleUrl, {
                mime: message.mime || "",
                size: Number(message.size) || 0,
                referer: message.referer || "",
                origin: message.origin || "",
                userAgent: message.userAgent || "",
                cookie: message.cookie || "",
                authorization: message.authorization || ""
            });
            publish();
            return;
        }
        if (typeof message.mediaUrl !== "string" || network.size >= 25) return;
        let url;
        try {
            url = new URL(message.mediaUrl);
        } catch (_) {
            return;
        }
        if (url.username || url.password) return;
        const mime = message.mime || "";
        const format = /mpegurl/i.test(mime) || /\.m3u8$/i.test(url.pathname) ? "hls"
            : /dash\+xml/i.test(mime) || /\.mpd$/i.test(url.pathname) ? "dash" : "progressive";
        const existing = network.get(url.href);
        const h = existing?.height || inferHeightFromUrl(url.href);
        const item = entry(url.href, {
            ...existing,
            format,
            mime,
            height: h,
            size: Number(message.size) || existing?.size || 0,
            referer: message.referer || existing?.referer || "",
            origin: message.origin || existing?.origin || "",
            userAgent: message.userAgent || existing?.userAgent || "",
            cookie: message.cookie || existing?.cookie || "",
            authorization: message.authorization || existing?.authorization || ""
        });
        network.set(url.href, item);
        if (format === "hls") readHls(url.href, false, item);
        publish();
    });

    let clickScanCooldown = 0;
    const triggerInteractionScan = () => {
        const now = Date.now();
        if (now - clickScanCooldown < 120) return;
        clickScanCooldown = now;
        publish();
        updateFloatingCastButton();
        setTimeout(() => { scanDomMedia(); scanPerformanceResources(); publish(); updateFloatingCastButton(); }, 250);
        setTimeout(() => { scanDomMedia(); scanPerformanceResources(); publish(); updateFloatingCastButton(); }, 850);
    };
    document.addEventListener("click", triggerInteractionScan, true);
    document.addEventListener("pointerup", triggerInteractionScan, true);
    for (const event of ["play", "playing", "loadedmetadata", "pause", "ended", "emptied", "volumechange"]) {
        document.addEventListener(event, () => {
            if ((event === "play" || event === "playing") && Date.now() < suppressUntil) pauseMedia();
            updateFloatingCastButton();
            publish();
        }, true);
    }
    try {
        let titleSyncTimer = null;
        const domTitleObserver = new MutationObserver(() => {
            if (titleSyncTimer) return;
            titleSyncTimer = setTimeout(() => {
                titleSyncTimer = null;
                publish();
            }, 350);
        });
        if (document.documentElement) {
            domTitleObserver.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
        }
    } catch (_) {}
    window.addEventListener("scroll", () => updateFloatingCastButton(), { passive: true, capture: true });
    window.addEventListener("resize", () => updateFloatingCastButton(), { passive: true });
    document.addEventListener("fullscreenchange", () => updateFloatingCastButton(), true);
    for (const navEvent of ["yt-navigate-finish", "popstate", "hashchange"]) {
        window.addEventListener(navEvent, () => {
            syncPageZoomNow();
            reset();
            publish(true);
        }, true);
    }
    window.addEventListener("pageshow", () => {
        if (!port && window === window.top) connectNative();
        syncPageZoomNow();
    });
    document.addEventListener("encrypted", event => {
        if (event.target instanceof HTMLVideoElement) {
            protectedVideo = true;
            network.clear();
            hideFloatingButton();
            publish();
        }
    }, true);

    const timer = setInterval(() => {
        publish();
        updateFloatingCastButton();
    }, 1500);
    window.addEventListener("pagehide", event => {
        if (!event.persisted) {
            clearInterval(timer);
            hideFloatingButton();
            try { hlsAbort.abort(); } catch (_) {}
            try {
                const emptyMsg = JSON.stringify({ frameToken, revision: ++discoveryRevision, items: [], subtitles: [] });
                browser.runtime.sendNativeMessage("cinemaVideo", emptyMsg).catch(() => {});
            } catch (_) {}
        }
    });
    sendHello();
    publish();
})();

// Suppress only explicit anti-adblock interstitials on the YTS site family.
(() => {
    if (window !== window.top) return;
    const yts = /(^|\.)yts\.[a-z]{2,5}$/i.test(location.hostname);
    const ytsMessage = /from the one person running yts/i;
    const overlayWords = /(?:ad[\s-]?block(?:er)?|ad[\s-]?blocking)/i;
    const demandWords = /(?:disable|turn off|whitelist|allowlist|detected)/i;
    let scheduled = false;
    const review = () => {
        scheduled = false;
        for (const node of document.querySelectorAll(
            '[id*="adblock" i], [class*="adblock" i], [role="dialog"], dialog, .modal, .sweet-alert, .swal2-container, .mfp-wrap')) {
            const message = (node.textContent || '').trim();
            if (message.length > 3000 || !(yts && ytsMessage.test(message))
                    && !(overlayWords.test(message) && demandWords.test(message))) continue;
            const overlay = node.closest('.swal2-container,.modal,[role="dialog"],dialog,.sweet-alert,.mfp-wrap') || node;
            if (overlay.hidden) continue;
            overlay.hidden = true;
            overlay.style.setProperty('display', 'none', 'important');
            if (!document.querySelector('.modal.show:not([hidden]), .swal2-container:not([hidden])')) {
                for (const backdrop of document.querySelectorAll('.modal-backdrop')) backdrop.remove();
                document.body?.classList.remove('modal-open', 'swal2-shown');
            }
        }
    };
    new MutationObserver(() => {
        if (!scheduled) {
            scheduled = true;
            requestAnimationFrame(review);
        }
    }).observe(document, {childList: true, characterData: true, subtree: true});
    document.addEventListener('DOMContentLoaded', review, {once: true});
    review();
})();
