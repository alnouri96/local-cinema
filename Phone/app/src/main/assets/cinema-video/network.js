const types = /^(video\/(mp4|webm|x-matroska|mkv|mp2t|3gpp|quicktime|x-msvideo|avi|mpeg|x-flv|ogg)|audio\/(mpegurl|x-mpegurl)|application\/(vnd\.apple\.mpegurl|x-mpegurl|mpegurl|dash\+xml))$/i;
const subtitleTypes = /^(text\/(vtt|srt|x-ssa|x-ass)|application\/(x-subrip|ttml\+xml|srt))$/i;
const torrentTypes = /^(application\/x-bittorrent|application\/bittorrent)$/i;
const AD_HOST_RE = /(^|\.)(doubleclick\.net|googlesyndication\.com|googleadservices\.com|imasdk\.googleapis\.com|2mdn\.net|adnxs\.com|rubiconproject\.com|pubmatic\.com|criteo\.com|taboola\.com|outbrain\.com|exoclick\.com|exosrv\.com|magsrv\.com|tsyndicate\.com|propellerads\.com|popads\.net|popcash\.net|juicyads\.com|a-ads\.com|adsterra\.com|hilltopads\.net|clickadu\.com|galaksion\.com|bidgear\.com|springserve\.com|spotxchange\.com|innovid\.com|flashtalking\.com|teads\.tv|smartadserver\.com|adform\.net|vidoomy\.com|aniview\.com|connatix\.com)$/i;
const AD_PATH_RE = /(?:\/|^)(?:vast|preroll|midroll|postroll|adserver|adbreak|ad_break|advert|ads_video|ima3)(?:\/|\.|\?|$)/i;
const STORYBOARD_SUB_RE = /(?:#xywh=\d+|\.(?:jpe?g|png|webp|gif|bmp|avif|svg)(?:$|[?#])|(?:^|[/_.-])(?:storyboard|sprites?|thumbnails?|thumbs|trickplay|preview[_-]?vtt|bif)(?:$|[/_.-]))/i;
const requestHeadersById = new Map();
const requestHeadersByUrl = new Map();
const tabCache = new Map(); // `${tabId}:${frameId}` -> { media: Map, subs: Map, yt: Map, torrents: Map }

function isAdMediaUrl(urlObj) {
    if (!urlObj) return false;
    if (AD_HOST_RE.test(urlObj.hostname || "")) return true;
    if (AD_PATH_RE.test(urlObj.pathname || "")) return true;
    const q = urlObj.search || "";
    return /[?&](?:ad_tag|adtag|vast|preroll|ad_url|ad_type)=/i.test(q);
}

function isStoryboardSubUrl(rawUrl, dispName = "") {
    return STORYBOARD_SUB_RE.test(String(rawUrl || "")) || STORYBOARD_SUB_RE.test(String(dispName || ""));
}

function rememberBounded(key, store, value, maxSize) {
    if (!key) return;
    if (store.has(key)) store.delete(key);
    store.set(key, value);
    while (store.size > maxSize) {
        const oldest = store.keys().next().value;
        if (oldest !== undefined) store.delete(oldest);
        else break;
    }
}

function getTabFrameBucket(tabId, frameId) {
    const key = `${tabId}:${frameId || 0}`;
    let bucket = tabCache.get(key);
    if (!bucket) {
        if (tabCache.size >= 40) {
            const oldest = tabCache.keys().next().value;
            if (oldest !== undefined) tabCache.delete(oldest);
        }
        bucket = { media: new Map(), subs: new Map(), yt: new Map(), torrents: new Map() };
        tabCache.set(key, bucket);
    }
    return bucket;
}

function clearTabBuckets(tabId, frameId = null) {
    const prefix = `${tabId}:`;
    if (frameId === null) {
        for (const k of [...tabCache.keys()]) {
            if (k.startsWith(prefix)) tabCache.delete(k);
        }
        const urlPrefix = `${tabId}|`;
        for (const k of [...requestHeadersByUrl.keys()]) {
            if (k.startsWith(urlPrefix)) requestHeadersByUrl.delete(k);
        }
    } else {
        tabCache.delete(`${tabId}:${frameId}`);
    }
}

function extractHeaders(details) {
    const existing = requestHeadersById.get(details.requestId)
        || requestHeadersByUrl.get(`${details.tabId}|${details.url}`)
        || {};
    let referer = existing.referer || details.originUrl || details.documentUrl || "";
    let origin = existing.origin || "";
    let userAgent = existing.userAgent || "";
    let cookie = existing.cookie || "";
    let authorization = existing.authorization || "";
    for (const h of (details.requestHeaders || [])) {
        const n = (h.name || "").toLowerCase();
        if (n === "referer" && h.value) referer = h.value;
        else if (n === "origin" && h.value) origin = h.value;
        else if (n === "user-agent" && h.value) userAgent = h.value;
        else if (n === "cookie" && h.value) cookie = h.value;
        else if (n === "authorization" && h.value) authorization = h.value;
    }
    if (!origin && referer) {
        try {
            const refOrigin = new URL(referer).origin;
            if (refOrigin && refOrigin !== "null") origin = refOrigin;
        } catch (_) {}
    }
    if (referer || origin || userAgent || cookie || authorization) {
        const info = { referer, origin, userAgent, cookie, authorization };
        rememberBounded(details.requestId, requestHeadersById, info, 500);
        rememberBounded(`${details.tabId}|${details.url}`, requestHeadersByUrl, info, 500);
    }
}

function extractDispositionFilename(disposition) {
    if (!disposition) return "";
    const utf8 = /filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;]+)/i.exec(disposition);
    if (utf8 && utf8[1]) {
        try { return decodeURIComponent(utf8[1].trim().replace(/^["']|["']$/g, "")); } catch (_) {}
    }
    const plain = /filename\s*=\s*("([^"]+)"|'([^']+)'|([^;\s]+))/i.exec(disposition);
    return plain ? (plain[2] || plain[3] || plain[4] || "").trim() : "";
}

const headerFilter = { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "sub_frame", "main_frame"] };

browser.webRequest.onBeforeRequest.addListener(details => {
    if (details.tabId < 0) return;
    if (details.type === "main_frame") {
        clearTabBuckets(details.tabId, null);
    } else if (details.type === "sub_frame") {
        clearTabBuckets(details.tabId, details.frameId);
    }
}, { urls: ["<all_urls>"], types: ["main_frame", "sub_frame"] });

browser.webRequest.onBeforeSendHeaders.addListener(details => {
    if (details.tabId >= 0) extractHeaders(details);
}, headerFilter, ["requestHeaders"]);

if (browser.webRequest.onSendHeaders) {
    browser.webRequest.onSendHeaders.addListener(details => {
        if (details.tabId >= 0) extractHeaders(details);
    }, headerFilter, ["requestHeaders"]);
}

if (browser.webRequest.onBeforeRedirect) {
    browser.webRequest.onBeforeRedirect.addListener(details => {
        if (details.tabId < 0 || !details.redirectUrl) return;
        const prev = requestHeadersById.get(details.requestId)
            || requestHeadersByUrl.get(`${details.tabId}|${details.url}`);
        if (prev) {
            rememberBounded(details.requestId, requestHeadersById, prev, 500);
            rememberBounded(`${details.tabId}|${details.redirectUrl}`, requestHeadersByUrl, prev, 500);
        }
    }, headerFilter);
}

browser.webRequest.onHeadersReceived.addListener(details => {
    const reqHeaders = requestHeadersById.get(details.requestId)
        || requestHeadersByUrl.get(`${details.tabId}|${details.url}`)
        || {};
    if (details.statusCode >= 300 && details.statusCode < 400) {
        return;
    }
    requestHeadersById.delete(details.requestId);
    if (details.tabId < 0 || details.statusCode < 200 || details.statusCode >= 300) return;
    const header = name => (details.responseHeaders || []).find(item => item.name.toLowerCase() === name)?.value || "";
    const mime = header("content-type").split(";")[0].trim().toLowerCase();
    const dispName = extractDispositionFilename(header("content-disposition"));
    let url;
    try {
        url = new URL(details.url);
    } catch (_) {
        return;
    }
    const frameId = details.frameId || 0;
    const bucket = getTabFrameBucket(details.tabId, frameId);

    const range = /^bytes \d+-\d+\/(\d+)$/i.exec(header("content-range"));
    const contentLen = Number(header("content-length")) || 0;
    const knownSize = contentLen > 0 ? contentLen : (range ? Number(range[1]) : 0);

    if (torrentTypes.test(mime)
            || /\.torrent(\?|$)/i.test(url.pathname)
            || /\.torrent$/i.test(dispName)) {
        const torPayload = {
            torrentUrl: details.url,
            filename: dispName || "",
            size: knownSize,
            referer: reqHeaders.referer || "",
            origin: reqHeaders.origin || "",
            userAgent: reqHeaders.userAgent || "",
            cookie: reqHeaders.cookie || "",
            authorization: reqHeaders.authorization || ""
        };
        rememberBounded(details.url, bucket.torrents, torPayload, 35);
        browser.tabs.sendMessage(details.tabId, torPayload, { frameId }).catch(() => {});
        return;
    }

    if (subtitleTypes.test(mime)
            || /\.(vtt|srt|ass|ssa)(\?|$)/i.test(url.pathname)
            || /\.(vtt|srt|ass|ssa)$/i.test(dispName)) {
        if (isStoryboardSubUrl(details.url, dispName) || isAdMediaUrl(url)) return;
        const subPayload = {
            subtitleUrl: details.url,
            mime,
            size: knownSize,
            referer: reqHeaders.referer || "",
            origin: reqHeaders.origin || "",
            userAgent: reqHeaders.userAgent || "",
            cookie: reqHeaders.cookie || "",
            authorization: reqHeaders.authorization || ""
        };
        rememberBounded(details.url, bucket.subs, subPayload, 28);
        browser.tabs.sendMessage(details.tabId, subPayload, { frameId }).catch(() => {});
        return;
    }
    if (isAdMediaUrl(url)) return;
    if ([...url.searchParams.keys()].some(key => /^_HLS_(msn|part|skip)$/i.test(key))) return;
    const mediaExtRe = /\.(m3u8|mp4|mpd|mkv|webm|mov|3gp|avi|flv)(\?|$)/i;
    const file = mediaExtRe.test(url.pathname)
        || mediaExtRe.test(dispName)
        || [...url.searchParams.values()].some(val => mediaExtRe.test(val));
    if (mime === "video/mp2t" && details.type !== "media") return;
    const isMediaMime = types.test(mime);
    const isFileFallback = file && (/^(application\/octet-stream|text\/plain|application\/force-download|binary\/octet-stream|application\/x-download)$/.test(mime) || mime === "");
    const isLargeRange = (mime === "application/octet-stream" || mime === "binary/octet-stream")
        && (details.type === "media" || Boolean(dispName))
        && ((range && Number(range[1]) > 400000) || contentLen > 500000);
    if (!isMediaMime && !isFileFallback && !isLargeRange) return;

    if (/(^|\.)googlevideo\.com$/i.test(url.hostname)) {
        if (url.pathname === "/videoplayback" && url.searchParams.has("itag")) {
            const clen = Number(url.searchParams.get("clen")) || 0;
            const ytPayload = {
                youtubeTrack: url.href,
                size: clen > 0 ? clen : knownSize,
                referer: reqHeaders.referer || "",
                origin: reqHeaders.origin || "",
                userAgent: reqHeaders.userAgent || "",
                cookie: reqHeaders.cookie || "",
                authorization: reqHeaders.authorization || ""
            };
            rememberBounded(url.searchParams.get("itag") || url.href, bucket.yt, ytPayload, 30);
            browser.tabs.sendMessage(details.tabId, ytPayload, { frameId }).catch(() => {});
        }
        return;
    }

    const mediaPayload = {
        mediaUrl: details.url,
        mime,
        filename: dispName || "",
        size: knownSize,
        referer: reqHeaders.referer || "",
        origin: reqHeaders.origin || "",
        userAgent: reqHeaders.userAgent || "",
        cookie: reqHeaders.cookie || "",
        authorization: reqHeaders.authorization || ""
    };
    rememberBounded(details.url, bucket.media, mediaPayload, 45);
    browser.tabs.sendMessage(details.tabId, mediaPayload, { frameId }).catch(() => {});
}, { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "main_frame", "sub_frame"] }, ["responseHeaders"]);

browser.runtime.onMessage.addListener((message, sender) => {
    if (!message || !sender || !sender.tab || sender.tab.id < 0) return;
    const tabId = sender.tab.id;
    const frameId = sender.frameId || 0;
    if (message.action === "clearTab") {
        clearTabBuckets(tabId, message.allFrames ? null : frameId);
        return;
    }
    if (!message.hello) return;
    const bucket = tabCache.get(`${tabId}:${frameId}`);
    if (!bucket) return;
    for (const subPayload of bucket.subs.values()) {
        browser.tabs.sendMessage(tabId, subPayload, { frameId }).catch(() => {});
    }
    for (const ytPayload of bucket.yt.values()) {
        browser.tabs.sendMessage(tabId, ytPayload, { frameId }).catch(() => {});
    }
    for (const torPayload of bucket.torrents.values()) {
        browser.tabs.sendMessage(tabId, torPayload, { frameId }).catch(() => {});
    }
    for (const mediaPayload of bucket.media.values()) {
        browser.tabs.sendMessage(tabId, mediaPayload, { frameId }).catch(() => {});
    }
});

if (browser.tabs && browser.tabs.onUpdated) {
    browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
        if (changeInfo && changeInfo.url) {
            clearTabBuckets(tabId, null);
        }
    });
}

if (browser.tabs && browser.tabs.onRemoved) {
    browser.tabs.onRemoved.addListener(tabId => {
        clearTabBuckets(tabId, null);
    });
}
