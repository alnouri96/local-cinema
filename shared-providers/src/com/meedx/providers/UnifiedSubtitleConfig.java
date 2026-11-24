package com.meedx.providers;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for Unified Subtitle Settings shared between TV (Video) and Phone (Phone),
 * supporting full bidirectional TV <-> Phone JSON synchronization.
 */
public final class UnifiedSubtitleConfig {
    private static final String PREFS = "meedx_unified_subtitles";

    public static final String SRC_OPENSUBTITLES_V3 = "opensubtitles_v3";
    public static final String SRC_OPENSUBTITLES_FREE = "opensubtitles_free";
    public static final String SRC_YIFY = "yify";
    public static final String SRC_GESTDOWN = "gestdown";
    public static final String SRC_STREMIO_ADDONS = "stremio_addons";
    public static final String SRC_SUBSOURCE = "subsource";
    public static final String SRC_SUBDL = "subdl";
    public static final String SRC_OPENSUBTITLES_API = "opensubtitles_api";

    public static final String MODE_ALL = "all";
    public static final String MODE_CUSTOM = "custom";

    public static final class ProviderInfo {
        public final String id;
        public final String name;
        public final String arType;
        public final String enType;
        public final boolean keyless;
        public final String apiKeyName; // null when keyless
        public final boolean supportsMovies;
        public final boolean supportsSeries;
        public final String keyUrl;

        public ProviderInfo(String id, String name, String arType, String enType,
                            boolean keyless, String apiKeyName,
                            boolean supportsMovies, boolean supportsSeries, String keyUrl) {
            this.id = id;
            this.name = name;
            this.arType = arType;
            this.enType = enType;
            this.keyless = keyless;
            this.apiKeyName = apiKeyName;
            this.supportsMovies = supportsMovies;
            this.supportsSeries = supportsSeries;
            this.keyUrl = keyUrl;
        }
    }

    private static final List<ProviderInfo> PROVIDERS = List.of(
            new ProviderInfo(SRC_OPENSUBTITLES_V3, "OpenSubtitles v3",
                    "مدمج · مفتوح المصدر (Stremio) · بدون مفتاح",
                    "Built-in · Open Source (Stremio) · No API Key",
                    true, null, true, true, ""),
            new ProviderInfo(SRC_OPENSUBTITLES_FREE, "OpenSubtitles Free",
                    "مدمج · مفتوح المصدر (REST) · أفلام ومسلسلات",
                    "Built-in · Open Catalog (REST) · Movies & Series",
                    true, null, true, true, ""),
            new ProviderInfo(SRC_YIFY, "YIFY Subtitles",
                    "مدمج · مفتوح المصدر · للأفلام (عربي + عالمي)",
                    "Built-in · Open Source · Movies (Arabic + Global)",
                    true, null, true, false, ""),
            new ProviderInfo(SRC_GESTDOWN, "Gestdown (Addic7ed)",
                    "مدمج · API مفتوح المصدر · للمسلسلات والحلقات",
                    "Built-in · Open API · TV Series & Episodes",
                    true, null, false, true, ""),
            new ProviderInfo(SRC_STREMIO_ADDONS, "Stremio Subtitle Add-ons",
                    "إضافات Stremio المثبّتة التي تدعم الترجمة",
                    "Installed Stremio Add-ons with subtitle support",
                    true, null, true, true, ""),
            new ProviderInfo(SRC_SUBSOURCE, "Subsource API",
                    "مصدر احترافي · يتطلب مفتاح API اختياري",
                    "Pro source · Optional API key",
                    false, ProviderKeys.SUBSOURCE, true, true, "https://subsource.net/"),
            new ProviderInfo(SRC_SUBDL, "SubDL API",
                    "مصدر احترافي · يتطلب مفتاح API اختياري",
                    "Pro source · Optional API key",
                    false, ProviderKeys.SUBDL, true, true, "https://subdl.com/panel/api"),
            new ProviderInfo(SRC_OPENSUBTITLES_API, "OpenSubtitles.com API",
                    "مصدر رسمي · يتطلب مفتاح API اختياري",
                    "Official REST API · Optional API key",
                    false, ProviderKeys.OPEN_SUBTITLES, true, true, "https://www.opensubtitles.com/en/consumers")
    );

    public static final List<String> SUPPORTED_LANGUAGES = List.of(
            "arabic", "english", "french", "spanish", "german",
            "turkish", "persian", "kurdish", "chinese", "japanese", "korean", "italian", "portuguese", "russian"
    );

    private UnifiedSubtitleConfig() {}

    public static List<ProviderInfo> allProviders() {
        return PROVIDERS;
    }

    public static ProviderInfo providerById(String id) {
        if (id == null) return null;
        for (ProviderInfo p : PROVIDERS) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean hasKey(Context context, ProviderInfo info) {
        if (info == null) return false;
        if (info.keyless || info.apiKeyName == null) return true;
        String k = ProviderKeys.get(context, info.apiKeyName);
        if (!k.isBlank()) return true;
        if (SRC_SUBSOURCE.equals(info.id)) {
            try {
                return !new ApiKeyStore(context, "subsource").read().isEmpty();
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static String resolveSubsourceKey(Context context) {
        String key = ProviderKeys.get(context, ProviderKeys.SUBSOURCE).trim();
        if (!key.isEmpty()) return key;
        try {
            List<String> legacy = new ApiKeyStore(context, "subsource").read();
            if (!legacy.isEmpty()) return legacy.get(0).trim();
        } catch (Exception ignored) {}
        return "";
    }

    public static Set<String> enabledSources(Context context) {
        SharedPreferences prefs = sp(context);
        String raw = prefs.getString("enabled_sources", null);
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            for (ProviderInfo p : PROVIDERS) {
                if (p.keyless || hasKey(context, p)) out.add(p.id);
            }
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "").trim();
                if (providerById(id) != null) out.add(id);
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            out.add(SRC_OPENSUBTITLES_V3);
            out.add(SRC_OPENSUBTITLES_FREE);
        }
        return out;
    }

    public static boolean isSourceEnabled(Context context, String sourceId) {
        return enabledSources(context).contains(sourceId);
    }

    public static void setSourceEnabled(Context context, String sourceId, boolean enabled) {
        if (providerById(sourceId) == null) return;
        Set<String> current = new LinkedHashSet<>(enabledSources(context));
        if (enabled) current.add(sourceId);
        else current.remove(sourceId);
        if (current.isEmpty()) current.add(SRC_OPENSUBTITLES_V3);
        saveEnabledSources(context, current);
    }

    public static void saveEnabledSources(Context context, Set<String> sources) {
        JSONArray arr = new JSONArray();
        if (sources != null) {
            for (String s : sources) {
                if (providerById(s) != null) arr.put(s);
            }
        }
        if (arr.length() == 0) arr.put(SRC_OPENSUBTITLES_V3);
        sp(context).edit()
                .putString("enabled_sources", arr.toString())
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static String searchSelectionMode(Context context) {
        String mode = sp(context).getString("search_mode", MODE_ALL);
        return MODE_CUSTOM.equals(mode) ? MODE_CUSTOM : MODE_ALL;
    }

    public static Set<String> selectedSearchSources(Context context) {
        Set<String> enabled = enabledSources(context);
        if (!MODE_CUSTOM.equals(searchSelectionMode(context))) {
            return enabled;
        }
        String raw = sp(context).getString("selected_search_sources", null);
        Set<String> selected = new LinkedHashSet<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i, "").trim();
                    if (providerById(id) != null) selected.add(id);
                }
            } catch (Exception ignored) {}
        }
        return selected.isEmpty() ? enabled : selected;
    }

    public static void setSearchSelectionAll(Context context) {
        sp(context).edit()
                .putString("search_mode", MODE_ALL)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static void setSearchSelectionCustom(Context context, Set<String> sources) {
        if (sources == null || sources.isEmpty()) {
            setSearchSelectionAll(context);
            return;
        }
        JSONArray arr = new JSONArray();
        for (String s : sources) {
            if (providerById(s) != null) arr.put(s);
        }
        if (arr.length() == 0) {
            setSearchSelectionAll(context);
            return;
        }
        sp(context).edit()
                .putString("search_mode", MODE_CUSTOM)
                .putString("selected_search_sources", arr.toString())
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static String searchSelectionSummary(Context context, boolean arabic) {
        if (!MODE_CUSTOM.equals(searchSelectionMode(context))) {
            int count = runnableSources(context, enabledSources(context)).size();
            return arabic
                    ? ("الكل (" + count + " مصادر مفعّلة)")
                    : ("All Enabled (" + count + " sources)");
        }
        Set<String> sel = selectedSearchSources(context);
        if (sel.size() == 1) {
            ProviderInfo p = providerById(sel.iterator().next());
            return p != null ? p.name : sel.iterator().next();
        }
        List<String> names = new ArrayList<>();
        for (String id : sel) {
            ProviderInfo p = providerById(id);
            if (p != null) names.add(p.name);
        }
        if (names.size() <= 2) return String.join(" + ", names);
        return arabic
                ? (names.size() + " مصادر محددة")
                : (names.size() + " selected sources");
    }

    public static Set<String> runnableSources(Context context, Set<String> requested) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : requested) {
            ProviderInfo p = providerById(id);
            if (p != null && (p.keyless || hasKey(context, p))) {
                out.add(id);
            }
        }
        return out;
    }

    public static List<String> languagePriorities(Context context) {
        String raw = sp(context).getString("language_priorities", null);
        List<String> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String canon = canonicalLangName(arr.optString(i, ""));
                    if (!canon.isEmpty() && !out.contains(canon)) out.add(canon);
                }
            } catch (Exception ignored) {}
        }
        if (out.isEmpty()) {
            out.add("arabic");
            out.add("english");
        }
        return out;
    }

    public static void setLanguagePriorities(Context context, List<String> langs) {
        JSONArray arr = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        if (langs != null) {
            for (String l : langs) {
                String c = canonicalLangName(l);
                if (!c.isEmpty() && seen.add(c)) arr.put(c);
            }
        }
        if (arr.length() == 0) {
            arr.put("arabic");
            arr.put("english");
        }
        sp(context).edit()
                .putString("language_priorities", arr.toString())
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static String primaryLanguage(Context context) {
        List<String> list = languagePriorities(context);
        return list.isEmpty() ? "arabic" : list.get(0);
    }

    public static String secondaryLanguage(Context context) {
        SharedPreferences prefs = sp(context);
        if (prefs.contains("secondary_subtitle_language")) {
            return canonicalLangName(prefs.getString("secondary_subtitle_language", ""));
        }
        List<String> list = languagePriorities(context);
        return list.size() >= 2 ? list.get(1) : "";
    }

    public static void setPrimaryLanguage(Context context, String lang) {
        String canon = canonicalLangName(lang);
        if (canon.isEmpty()) canon = "arabic";
        List<String> list = new ArrayList<>(languagePriorities(context));
        list.remove(canon);
        list.add(0, canon);
        setLanguagePriorities(context, list);
    }

    public static void setSecondaryLanguage(Context context, String lang) {
        String canon = canonicalLangName(lang);
        sp(context).edit()
                .putString("secondary_subtitle_language", canon)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static final int SUB_MODE_AUTO = 0;
    public static final int SUB_MODE_MOVIE_SOURCE = 1;
    public static final int SUB_MODE_OFF = 2;

    public static final int AUTO_PRIORITY_SOURCE_FIRST = 0;
    public static final int AUTO_PRIORITY_EMBEDDED_FIRST = 1;
    public static final int AUTO_PRIORITY_ONLINE_DIRECT = 2;

    public static int preferredSubtitleMode(Context context) {
        return Math.max(0, Math.min(2, sp(context).getInt("preferred_subtitle_mode", SUB_MODE_AUTO)));
    }

    public static void setPreferredSubtitleMode(Context context, int mode) {
        sp(context).edit()
                .putInt("preferred_subtitle_mode", Math.max(0, Math.min(2, mode)))
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static String preferredSubtitleModeLabel(int mode, boolean arabic) {
        return switch (mode) {
            case SUB_MODE_MOVIE_SOURCE -> arabic ? "ملفات مصدر الفيلم" : "Movie Source Files";
            case SUB_MODE_OFF -> arabic ? "إيقاف الترجمة" : "Turn Off Subtitles";
            default -> arabic ? "اختيار تلقائي" : "Auto-Select";
        };
    }

    public static int autoSelectPriority(Context context) {
        return Math.max(0, Math.min(2, sp(context).getInt("auto_select_priority", AUTO_PRIORITY_SOURCE_FIRST)));
    }

    public static void setAutoSelectPriority(Context context, int priority) {
        sp(context).edit()
                .putInt("auto_select_priority", Math.max(0, Math.min(2, priority)))
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static String autoSelectPriorityLabel(int priority, boolean arabic) {
        return switch (priority) {
            case AUTO_PRIORITY_EMBEDDED_FIRST -> arabic
                    ? "ترجمة الفيديو المدمجة ← ملفات مصدر الفيلم ← الإنترنت"
                    : "Embedded Video Subtitles → Movie Source Files → Online";
            case AUTO_PRIORITY_ONLINE_DIRECT -> arabic
                    ? "البحث والتحميل من الإنترنت مباشرةً"
                    : "Search & Download Online Directly";
            default -> arabic
                    ? "ملفات مصدر الفيلم ← ترجمة الفيديو المدمجة ← الإنترنت"
                    : "Movie Source Files → Embedded Video Subtitles → Online";
        };
    }

    public static String autoSelectPriorityShortLabel(Context context, boolean arabic) {
        int prio = autoSelectPriority(context);
        boolean onlineOn = autoSubtitlesEnabled(context);
        return switch (prio) {
            case AUTO_PRIORITY_EMBEDDED_FIRST -> arabic
                    ? (onlineOn ? "ترجمة الفيديو المدمجة ثم الإنترنت" : "ترجمة الفيديو المدمجة ثم مصدر الفيلم")
                    : (onlineOn ? "Embedded Video Subtitles then Online" : "Embedded Video Subtitles then Movie Source");
            case AUTO_PRIORITY_ONLINE_DIRECT -> arabic
                    ? "البحث من الإنترنت مباشرةً"
                    : "Online Search Directly";
            default -> arabic
                    ? (onlineOn ? "ملفات مصدر الفيلم ثم الإنترنت" : "ملفات مصدر الفيلم ثم المدمجة")
                    : (onlineOn ? "Movie Source Files then Online" : "Movie Source Files then Embedded");
        };
    }

    public static boolean autoSubtitlesEnabled(Context context) {
        return sp(context).getBoolean("auto_subtitles_enabled", true);
    }

    public static void setAutoSubtitlesEnabled(Context context, boolean enabled) {
        sp(context).edit()
                .putBoolean("auto_subtitles_enabled", enabled)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static boolean dualSubtitlesEnabled(Context context) {
        return sp(context).getBoolean("dual_subtitles_enabled", false);
    }

    public static void setDualSubtitlesEnabled(Context context, boolean enabled) {
        sp(context).edit()
                .putBoolean("dual_subtitles_enabled", enabled)
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static int dualSubPosition(Context context) {
        return Math.max(0, Math.min(2, sp(context).getInt("dual_sub_position", 1)));
    }

    public static void setDualSubPosition(Context context, int pos) {
        sp(context).edit()
                .putInt("dual_sub_position", Math.max(0, Math.min(2, pos)))
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static int textSize(Context context) {
        return Math.max(18, Math.min(80, sp(context).getInt("text_size", 36)));
    }

    public static int vpos(Context context) {
        return Math.max(16, Math.min(200, sp(context).getInt("vpos", 64)));
    }

    public static int textColor(Context context) {
        return sp(context).getInt("text_color", Color.WHITE);
    }

    public static boolean opaqueBg(Context context) {
        return sp(context).getBoolean("opaque_bg", false);
    }

    public static long defaultDelayMs(Context context) {
        return sp(context).getLong("default_delay_ms", 0L);
    }

    public static void saveAppearance(Context context, int size, int vpos, int color, boolean opaqueBg, long delayMs) {
        sp(context).edit()
                .putInt("text_size", Math.max(18, Math.min(80, size)))
                .putInt("vpos", Math.max(16, Math.min(200, vpos)))
                .putInt("text_color", color)
                .putBoolean("opaque_bg", opaqueBg)
                .putLong("default_delay_ms", Math.max(-30000L, Math.min(30000L, delayMs)))
                .putLong("updated_at", System.currentTimeMillis())
                .apply();
    }

    public static long updatedAt(Context context) {
        return sp(context).getLong("updated_at", 0L);
    }

    /** Exports full unified subtitle preferences as JSON for TV <-> Phone sync. */
    public static JSONObject exportSyncJson(Context context) {
        JSONObject obj = new JSONObject();
        try {
            JSONArray enArr = new JSONArray();
            for (String s : enabledSources(context)) enArr.put(s);
            obj.put("enabled_sources", enArr);
            obj.put("search_mode", searchSelectionMode(context));
            JSONArray selArr = new JSONArray();
            for (String s : selectedSearchSources(context)) selArr.put(s);
            obj.put("selected_search_sources", selArr);
            JSONArray langArr = new JSONArray();
            for (String l : languagePriorities(context)) langArr.put(l);
            obj.put("language_priorities", langArr);
            obj.put("secondary_subtitle_language", secondaryLanguage(context));
            obj.put("preferred_subtitle_mode", preferredSubtitleMode(context));
            obj.put("auto_subtitles_enabled", autoSubtitlesEnabled(context));
            obj.put("auto_select_priority", autoSelectPriority(context));
            obj.put("dual_subtitles_enabled", dualSubtitlesEnabled(context));
            obj.put("dual_sub_position", dualSubPosition(context));
            obj.put("text_size", textSize(context));
            obj.put("vpos", vpos(context));
            obj.put("text_color", textColor(context));
            obj.put("opaque_bg", opaqueBg(context));
            obj.put("default_delay_ms", defaultDelayMs(context));
            obj.put("updated_at", updatedAt(context));
        } catch (Exception ignored) {}
        return obj;
    }

    /**
     * Imports a remote TV <-> Phone subtitle config snapshot.
     * When {@code force} is false, applies only if remote {@code updated_at} is newer or local has never been modified.
     */
    public static boolean importSyncJson(Context context, JSONObject obj, boolean force) {
        if (context == null || obj == null) return false;
        long remoteTs = obj.optLong("updated_at", 0L);
        long localTs = updatedAt(context);
        if (!force && localTs > 0L && remoteTs > 0L && remoteTs < localTs) {
            return false;
        }
        SharedPreferences.Editor ed = sp(context).edit();
        JSONArray enArr = obj.optJSONArray("enabled_sources");
        if (enArr != null && enArr.length() > 0) {
            JSONArray clean = new JSONArray();
            for (int i = 0; i < enArr.length(); i++) {
                String id = enArr.optString(i, "").trim();
                if (providerById(id) != null) clean.put(id);
            }
            if (clean.length() > 0) ed.putString("enabled_sources", clean.toString());
        }
        if (obj.has("search_mode")) {
            String m = obj.optString("search_mode", MODE_ALL);
            ed.putString("search_mode", MODE_CUSTOM.equals(m) ? MODE_CUSTOM : MODE_ALL);
        }
        JSONArray selArr = obj.optJSONArray("selected_search_sources");
        if (selArr != null) {
            JSONArray clean = new JSONArray();
            for (int i = 0; i < selArr.length(); i++) {
                String id = selArr.optString(i, "").trim();
                if (providerById(id) != null) clean.put(id);
            }
            ed.putString("selected_search_sources", clean.toString());
        }
        JSONArray langArr = obj.optJSONArray("language_priorities");
        if (langArr != null && langArr.length() > 0) {
            JSONArray clean = new JSONArray();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < langArr.length(); i++) {
                String c = canonicalLangName(langArr.optString(i, ""));
                if (!c.isEmpty() && seen.add(c)) clean.put(c);
            }
            if (clean.length() > 0) ed.putString("language_priorities", clean.toString());
        }
        if (obj.has("secondary_subtitle_language")) {
            ed.putString("secondary_subtitle_language", canonicalLangName(obj.optString("secondary_subtitle_language", "")));
        }
        if (obj.has("preferred_subtitle_mode")) {
            ed.putInt("preferred_subtitle_mode", Math.max(0, Math.min(2, obj.optInt("preferred_subtitle_mode", SUB_MODE_AUTO))));
        }
        if (obj.has("auto_subtitles_enabled")) ed.putBoolean("auto_subtitles_enabled", obj.optBoolean("auto_subtitles_enabled", true));
        if (obj.has("auto_select_priority")) ed.putInt("auto_select_priority", Math.max(0, Math.min(2, obj.optInt("auto_select_priority", AUTO_PRIORITY_SOURCE_FIRST))));
        if (obj.has("dual_subtitles_enabled")) ed.putBoolean("dual_subtitles_enabled", obj.optBoolean("dual_subtitles_enabled", false));
        if (obj.has("dual_sub_position")) ed.putInt("dual_sub_position", Math.max(0, Math.min(2, obj.optInt("dual_sub_position", 1))));
        if (obj.has("text_size")) ed.putInt("text_size", Math.max(18, Math.min(80, obj.optInt("text_size", 36))));
        if (obj.has("vpos")) ed.putInt("vpos", Math.max(16, Math.min(200, obj.optInt("vpos", 64))));
        if (obj.has("text_color")) ed.putInt("text_color", obj.optInt("text_color", Color.WHITE));
        if (obj.has("opaque_bg")) ed.putBoolean("opaque_bg", obj.optBoolean("opaque_bg", false));
        if (obj.has("default_delay_ms")) ed.putLong("default_delay_ms", Math.max(-30000L, Math.min(30000L, obj.optLong("default_delay_ms", 0L))));
        ed.putLong("updated_at", remoteTs > 0L ? remoteTs : System.currentTimeMillis());
        ed.commit();
        return true;
    }

    private static final Map<String, String> ISO639_TO_BCP47 = new HashMap<>();
    private static final Map<String, String[]> LANG_DISPLAY_NAMES = new HashMap<>();

    static {
        // Complete ISO-639-2 (B & T) and common ISO-639-3 aliases -> BCP-47 / ISO-639-1
        String[][] isoMappings = {
                {"aar", "aa"}, {"abk", "ab"}, {"afr", "af"}, {"aka", "ak"},
                {"alb", "sq"}, {"sqi", "sq"}, {"amh", "am"},
                {"ara", "ar"}, {"arb", "ar"}, {"arz", "ar"}, {"apc", "ar"}, {"ary", "ar"},
                {"arg", "an"}, {"arm", "hy"}, {"hye", "hy"}, {"asm", "as"}, {"ast", "ast"},
                {"aze", "az"}, {"azb", "az"}, {"azj", "az"}, {"bak", "ba"},
                {"baq", "eu"}, {"eus", "eu"}, {"bel", "be"}, {"ben", "bn"},
                {"bos", "bs"}, {"bre", "br"}, {"bul", "bg"}, {"bur", "my"}, {"mya", "my"},
                {"cat", "ca"}, {"ces", "cs"}, {"cze", "cs"},
                {"chi", "zh"}, {"zho", "zh"}, {"cmn", "zh"}, {"zhs", "zh"}, {"zht", "zh"}, {"yue", "yue"},
                {"cos", "co"}, {"cym", "cy"}, {"wel", "cy"}, {"dan", "da"},
                {"deu", "de"}, {"ger", "de"}, {"div", "dv"}, {"dut", "nl"}, {"nld", "nl"},
                {"dzo", "dz"}, {"ell", "el"}, {"gre", "el"}, {"eng", "en"},
                {"epo", "eo"}, {"est", "et"}, {"fao", "fo"},
                {"fas", "fa"}, {"per", "fa"}, {"pes", "fa"}, {"prs", "fa"},
                {"fij", "fj"}, {"fil", "fil"}, {"fin", "fi"},
                {"fra", "fr"}, {"fre", "fr"}, {"fry", "fy"},
                {"geo", "ka"}, {"kat", "ka"}, {"gla", "gd"}, {"gle", "ga"}, {"glg", "gl"},
                {"grn", "gn"}, {"guj", "gu"}, {"hat", "ht"}, {"hau", "ha"},
                {"heb", "he"}, {"iw", "he"}, {"hin", "hi"},
                {"hrv", "hr"}, {"scr", "hr"}, {"hun", "hu"}, {"ibo", "ig"},
                {"ice", "is"}, {"isl", "is"}, {"ind", "id"}, {"in", "id"},
                {"ita", "it"}, {"jav", "jv"}, {"jpn", "ja"}, {"kan", "kn"},
                {"kas", "ks"}, {"kaz", "kk"}, {"khm", "km"}, {"kin", "rw"}, {"kir", "ky"},
                {"kor", "ko"}, {"kur", "ku"}, {"ckb", "ku"}, {"kmr", "ku"},
                {"lao", "lo"}, {"lat", "la"}, {"lav", "lv"}, {"lin", "ln"}, {"lit", "lt"},
                {"ltz", "lb"}, {"mac", "mk"}, {"mkd", "mk"}, {"mal", "ml"},
                {"mao", "mi"}, {"mri", "mi"}, {"mar", "mr"},
                {"may", "ms"}, {"msa", "ms"}, {"zsm", "ms"},
                {"mlg", "mg"}, {"mlt", "mt"}, {"mon", "mn"}, {"nep", "ne"},
                {"nno", "nn"}, {"nob", "nb"}, {"bok", "nb"}, {"nor", "no"},
                {"nya", "ny"}, {"oci", "oc"}, {"ori", "or"}, {"orm", "om"},
                {"pan", "pa"}, {"pol", "pl"},
                {"por", "pt"}, {"pob", "pt"}, {"pb", "pt"},
                {"pus", "ps"}, {"que", "qu"}, {"roh", "rm"},
                {"ron", "ro"}, {"rum", "ro"}, {"mol", "ro"}, {"mo", "ro"},
                {"run", "rn"}, {"rus", "ru"}, {"san", "sa"}, {"sin", "si"},
                {"slk", "sk"}, {"slo", "sk"}, {"slv", "sl"}, {"sme", "se"},
                {"smo", "sm"}, {"sna", "sn"}, {"snd", "sd"}, {"som", "so"}, {"sot", "st"},
                {"spa", "es"}, {"srd", "sc"}, {"srp", "sr"}, {"scc", "sr"},
                {"sun", "su"}, {"swa", "sw"}, {"swe", "sv"},
                {"tam", "ta"}, {"tat", "tt"}, {"tel", "te"}, {"tgk", "tg"},
                {"tgl", "tl"}, {"tha", "th"}, {"tib", "bo"}, {"bod", "bo"},
                {"tir", "ti"}, {"ton", "to"}, {"tur", "tr"}, {"tuk", "tk"},
                {"uig", "ug"}, {"ukr", "uk"}, {"urd", "ur"}, {"uzb", "uz"},
                {"vie", "vi"}, {"wol", "wo"}, {"xho", "xh"},
                {"yid", "yi"}, {"ji", "yi"}, {"yor", "yo"}, {"zul", "zu"}
        };
        for (String[] pair : isoMappings) {
            ISO639_TO_BCP47.put(pair[0], pair[1]);
        }

        // Native language Endonyms + Arabic/English aliases for parsing & recognition.
        // Format: {bcp47Tag, endonym, englishName, arabicName}
        String[][] names = {
                {"ar", "العربية", "Arabic", "العربية"},
                {"en", "English", "English", "الإنجليزية"},
                {"fr", "Français", "French", "الفرنسية"},
                {"es", "Español", "Spanish", "الإسبانية"},
                {"de", "Deutsch", "German", "الألمانية"},
                {"tr", "Türkçe", "Turkish", "التركية"},
                {"fa", "فارسی", "Persian", "الفارسية"},
                {"ku", "کوردی", "Kurdish", "الكردية"},
                {"zh", "中文", "Chinese", "الصينية"},
                {"yue", "粵語", "Cantonese", "الكانتونية"},
                {"ja", "日本語", "Japanese", "اليابانية"},
                {"ko", "한국어", "Korean", "الكورية"},
                {"it", "Italiano", "Italian", "الإيطالية"},
                {"pt", "Português", "Portuguese", "البرتغالية"},
                {"ru", "Русский", "Russian", "الروسية"},
                {"ca", "Català", "Catalan", "الكتالانية"},
                {"cs", "Čeština", "Czech", "التشيكية"},
                {"da", "Dansk", "Danish", "الدنماركية"},
                {"el", "Ελληνικά", "Greek", "اليونانية"},
                {"eu", "Euskara", "Basque", "الباسكية"},
                {"fi", "Suomi", "Finnish", "الفنلندية"},
                {"fil", "Filipino", "Filipino", "الفلبينية"},
                {"tl", "Tagalog", "Tagalog", "التاغالوغية"},
                {"gl", "Galego", "Galician", "الجاليكية"},
                {"he", "עברית", "Hebrew", "العبرية"},
                {"hr", "Hrvatski", "Croatian", "الكرواتية"},
                {"hu", "Magyar", "Hungarian", "المجرية"},
                {"id", "Bahasa Indonesia", "Indonesian", "الإندونيسية"},
                {"ms", "Bahasa Melayu", "Malay", "الملايوية"},
                {"nb", "Norsk", "Norwegian", "النرويجية"},
                {"no", "Norsk", "Norwegian", "النرويجية"},
                {"nn", "Norsk nynorsk", "Norwegian Nynorsk", "النرويجية النينورسك"},
                {"nl", "Nederlands", "Dutch", "الهولندية"},
                {"pl", "Polski", "Polish", "البولندية"},
                {"ro", "Română", "Romanian", "الرومانية"},
                {"sv", "Svenska", "Swedish", "السويدية"},
                {"th", "ไทย", "Thai", "التايلاندية"},
                {"uk", "Українська", "Ukrainian", "الأوكرانية"},
                {"vi", "Tiếng Việt", "Vietnamese", "الفيتنامية"},
                {"hi", "हिन्दी", "Hindi", "الهندية"},
                {"bn", "বাংলা", "Bengali", "البنغالية"},
                {"ur", "اردو", "Urdu", "الأردية"},
                {"ta", "தமிழ்", "Tamil", "التاميلية"},
                {"te", "తెలుగు", "Telugu", "التيلوغوية"},
                {"ml", "മലയാളം", "Malayalam", "المالايالامية"},
                {"mr", "मराठी", "Marathi", "الماراثية"},
                {"pa", "ਪੰਜਾਬੀ", "Punjabi", "البنجابية"},
                {"gu", "ગુજરાતી", "Gujarati", "الغوجاراتية"},
                {"kn", "ಕನ್ನಡ", "Kannada", "الكانادية"},
                {"bg", "Български", "Bulgarian", "البلغارية"},
                {"sr", "Српски", "Serbian", "الصربية"},
                {"sk", "Slovenčina", "Slovak", "السلوفاكية"},
                {"sl", "Slovenščina", "Slovenian", "السلوفينية"},
                {"bs", "Bosanski", "Bosnian", "البوسنية"},
                {"mk", "Македонски", "Macedonian", "المقدونية"},
                {"sq", "Shqip", "Albanian", "الألبانية"},
                {"lt", "Lietuvių", "Lithuanian", "الليتوانية"},
                {"lv", "Latviešu", "Latvian", "اللاتفية"},
                {"et", "Eesti", "Estonian", "الإستونية"},
                {"is", "Íslenska", "Icelandic", "الأيسلندية"},
                {"hy", "Հայերեն", "Armenian", "الأرمنية"},
                {"ka", "ქართული", "Georgian", "الجورجية"},
                {"be", "Беларуская", "Belarusian", "البيلاروسية"},
                {"az", "Azərbaycanca", "Azerbaijani", "الأذربيجانية"},
                {"kk", "Қазақша", "Kazakh", "الكازاخية"},
                {"uz", "Oʻzbekcha", "Uzbek", "الأوزبكية"},
                {"mn", "Монгол", "Mongolian", "المنغولية"},
                {"km", "ខ្មែរ", "Khmer", "الخميرية"},
                {"lo", "ລາວ", "Lao", "اللاوية"},
                {"my", "မြန်မာ", "Burmese", "البورمية"},
                {"ne", "नेपाली", "Nepali", "النيبالية"},
                {"si", "සිංහල", "Sinhala", "السنهالية"},
                {"sw", "Kiswahili", "Swahili", "السواحلية"},
                {"af", "Afrikaans", "Afrikaans", "الأفريقانية"},
                {"am", "አማርኛ", "Amharic", "الأمهرية"},
                {"ga", "Gaeilge", "Irish", "الأيرلندية"},
                {"cy", "Cymraeg", "Welsh", "الويلزية"},
                {"mt", "Malti", "Maltese", "المالطية"},
                {"ast", "Asturianu", "Asturian", "الأستورية"},
                {"so", "Soomaali", "Somali", "الصومالية"},
                {"ps", "پښتو", "Pashto", "البشتو"}
        };
        for (String[] row : names) {
            LANG_DISPLAY_NAMES.put(row[0], new String[]{row[1], row[2], row[3]});
        }
    }

    public static String canonicalLangName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String t = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (t.equals("und") || t.equals("unk") || t.equals("unknown") || t.equals("zxx")
                || t.equals("mis") || t.equals("mul") || t.equals("none") || t.equals("off")) {
            return "";
        }
        String base = t.contains("-") ? t.substring(0, t.indexOf('-')) : t;
        if (t.contains("عرب") || base.equals("ar") || base.equals("ara") || base.equals("arb") || base.equals("arabic")) return "arabic";
        if (base.equals("en") || base.equals("eng") || base.equals("english")) return "english";
        if (base.equals("fr") || base.equals("fre") || base.equals("fra") || base.equals("french") || base.equals("français")) return "french";
        if (base.equals("es") || base.equals("spa") || base.equals("spanish") || base.equals("español")) return "spanish";
        if (base.equals("de") || base.equals("ger") || base.equals("deu") || base.equals("german") || base.equals("deutsch")) return "german";
        if (base.equals("tr") || base.equals("tur") || base.equals("turkish") || base.equals("türkçe")) return "turkish";
        if (base.equals("fa") || base.equals("per") || base.equals("fas") || base.equals("persian") || base.equals("farsi") || base.equals("فارسی")) return "persian";
        if (base.equals("ku") || base.equals("kur") || base.equals("ckb") || base.equals("kmr") || base.equals("kurdish") || base.equals("کوردی")) return "kurdish";
        if (base.equals("zh") || base.equals("chi") || base.equals("zho") || base.equals("cmn") || base.equals("chinese") || base.equals("中文")) return "chinese";
        if (base.equals("ja") || base.equals("jpn") || base.equals("japanese") || base.equals("日本語")) return "japanese";
        if (base.equals("ko") || base.equals("kor") || base.equals("korean") || base.equals("한국어")) return "korean";
        if (base.equals("it") || base.equals("ita") || base.equals("italian") || base.equals("italiano")) return "italian";
        if (base.equals("pt") || base.equals("por") || base.equals("pob") || base.equals("pb") || base.equals("portuguese") || base.equals("português")) return "portuguese";
        if (base.equals("ru") || base.equals("rus") || base.equals("russian") || base.equals("русский")) return "russian";

        String mapped = ISO639_TO_BCP47.get(t);
        if (mapped == null) mapped = ISO639_TO_BCP47.get(base);
        if (mapped != null) return mapped;
        if (LANG_DISPLAY_NAMES.containsKey(base)) return base;
        for (Map.Entry<String, String[]> entry : LANG_DISPLAY_NAMES.entrySet()) {
            String[] info = entry.getValue();
            if (t.equalsIgnoreCase(info[0]) || t.equalsIgnoreCase(info[1]) || t.equals(info[2])) {
                return entry.getKey();
            }
        }
        return base;
    }

    /** Resolves any canonical name or ISO-639-1/2(B/T) code to a normalized BCP-47 language tag. */
    public static String toBcp47Tag(String lang) {
        String c = canonicalLangName(lang);
        if (c.isEmpty()) return "";
        return switch (c) {
            case "arabic" -> "ar";
            case "english" -> "en";
            case "french" -> "fr";
            case "spanish" -> "es";
            case "german" -> "de";
            case "turkish" -> "tr";
            case "persian" -> "fa";
            case "kurdish" -> "ku";
            case "chinese" -> "zh";
            case "japanese" -> "ja";
            case "korean" -> "ko";
            case "italian" -> "it";
            case "portuguese" -> "pt";
            case "russian" -> "ru";
            default -> {
                String mapped = ISO639_TO_BCP47.get(c);
                yield mapped != null ? mapped : c;
            }
        };
    }

    /** Returns the language's own native name (Endonym) for display & deduplicating track titles. */
    public static String nativeLanguageName(String lang) {
        String tag = toBcp47Tag(lang);
        if (tag.isEmpty()) return "";
        String[] known = LANG_DISPLAY_NAMES.get(tag);
        if (known != null) return known[0];
        try {
            Locale loc = Locale.forLanguageTag(tag);
            String nativeName = loc.getDisplayLanguage(loc);
            if (nativeName != null && !nativeName.isBlank() && !nativeName.equalsIgnoreCase(tag)) {
                return Character.toUpperCase(nativeName.charAt(0)) + nativeName.substring(1);
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Returns the English and Arabic exonyms for stripping redundant words in track titles. */
    public static String[] languageAliases(String lang) {
        String tag = toBcp47Tag(lang);
        if (tag.isEmpty()) return new String[0];
        String[] known = LANG_DISPLAY_NAMES.get(tag);
        if (known != null) return known;
        List<String> out = new ArrayList<>();
        try {
            Locale target = Locale.forLanguageTag(tag);
            String nat = target.getDisplayLanguage(target);
            String en = target.getDisplayLanguage(Locale.ENGLISH);
            String ar = target.getDisplayLanguage(Locale.forLanguageTag("ar"));
            if (nat != null && !nat.isBlank()) out.add(nat);
            if (en != null && !en.isBlank()) out.add(en);
            if (ar != null && !ar.isBlank()) out.add(ar);
        } catch (Exception ignored) {}
        return out.toArray(new String[0]);
    }

    public static String toIso2(String lang) {
        String tag = toBcp47Tag(lang);
        if (tag.isEmpty()) return "";
        return tag.length() >= 2 ? tag.substring(0, 2) : tag;
    }

    public static String toIso3(String lang) {
        return switch (canonicalLangName(lang)) {
            case "arabic" -> "ara";
            case "english" -> "eng";
            case "french" -> "fre";
            case "spanish" -> "spa";
            case "german" -> "ger";
            case "turkish" -> "tur";
            case "persian" -> "per";
            case "kurdish" -> "kur";
            case "chinese" -> "chi";
            case "japanese" -> "jpn";
            case "korean" -> "kor";
            case "italian" -> "ita";
            case "portuguese" -> "por";
            case "russian" -> "rus";
            default -> {
                String c = canonicalLangName(lang);
                yield c.length() >= 3 ? c.substring(0, 3) : c;
            }
        };
    }

    /**
     * Always returns the language in its own native Endonym (e.g. "العربية", "English", "Español",
     * "Deutsch", "Français", "فارسی", "Türkçe", "Русский", "Українська", "日本語") so speakers
     * of any language immediately recognize their own language regardless of UI locale.
     */
    public static String languageDisplay(String lang, boolean arabicUi) {
        if (lang == null || lang.isBlank()) {
            return arabicUi ? "الكل" : "All Languages";
        }
        String canon = canonicalLangName(lang);
        if (canon.isEmpty()) {
            return arabicUi ? "لغة غير محددة" : "Unknown Language";
        }
        String endonym = nativeLanguageName(canon);
        if (!endonym.isEmpty()) {
            return endonym;
        }
        String tag = toBcp47Tag(canon);
        try {
            Locale target = Locale.forLanguageTag(tag);
            String resolved = target.getDisplayLanguage(target);
            if (resolved == null || resolved.isBlank() || resolved.equalsIgnoreCase(tag)) {
                resolved = target.getDisplayLanguage(arabicUi ? Locale.forLanguageTag("ar") : Locale.ENGLISH);
            }
            if (resolved != null && !resolved.isBlank()
                    && !resolved.equalsIgnoreCase(tag)
                    && !resolved.equalsIgnoreCase(lang.trim())) {
                return Character.toUpperCase(resolved.charAt(0)) + resolved.substring(1);
            }
        } catch (Exception ignored) {}
        return lang.trim();
    }
}
