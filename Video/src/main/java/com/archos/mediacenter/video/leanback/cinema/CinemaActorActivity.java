package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Episode;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.loader.VideoLoader;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediacenter.video.leanback.tvshow.TvshowFragment;
import com.archos.mediaprovider.video.VideoStore;
import com.meedx.providers.OpenMetadata;
import com.meedx.providers.ProviderKeys;
import com.meedx.providers.StremioAddons;
import com.squareup.picasso.Picasso;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Lightweight Actor profile & filmography screen matching MEEDX dark+gold design. */
public class CinemaActorActivity extends LeanbackActivity {
    public static final String EXTRA_NAME = "actor_name";
    public static final String EXTRA_ROLE = "actor_role";
    public static final String EXTRA_IMAGE = "actor_image";
    public static final String EXTRA_PERSON_ID = "actor_person_id";
    private static final long PROFILE_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private View settingsNav;
    private ImageView portraitView;
    private FrameLayout portraitFrame;
    private TextView birthView, bioView, creditsTitle;
    private LinearLayout creditsRow;
    private String actorName, actorRole, actorPhotoUrl, fullPhotoUrl, fullBio = "";

    private record Credit(String title, String year, String character, String posterUrl,
                          boolean isShow, String imdbId, Video localVideo, Tvshow localShow) {}

    public static void clearCache(Context context) {
        if (context == null) return;
        File dir = new File(context.getCacheDir(), "actor_profiles_v1");
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    public static void open(Context context, String name, String role, String imageUrl, String personId) {
        if (context == null || name == null || name.isBlank()) return;
        String clean = OpenMetadata.cleanPersonName(name);
        if (clean.isEmpty()) clean = name.trim();
        Intent intent = new Intent(context, CinemaActorActivity.class)
                .putExtra(EXTRA_NAME, clean);
        if (role != null && !role.isBlank()) intent.putExtra(EXTRA_ROLE, role.trim());
        if (imageUrl != null && !imageUrl.isBlank()) intent.putExtra(EXTRA_IMAGE, imageUrl.trim());
        if (personId != null && !personId.isBlank()) intent.putExtra(EXTRA_PERSON_ID, personId.trim());
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        String rawName = getIntent().getStringExtra(EXTRA_NAME);
        actorName = OpenMetadata.cleanPersonName(rawName);
        if (actorName.isEmpty() && rawName != null) actorName = rawName.trim();
        actorRole = getIntent().getStringExtra(EXTRA_ROLE);
        actorPhotoUrl = getIntent().getStringExtra(EXTRA_IMAGE);
        fullPhotoUrl = actorPhotoUrl;
        if (actorName == null || actorName.isBlank()) { finish(); return; }

        root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CinemaTokens.CANVAS);
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        // Leave clear horizontal margin from the left CinemaNavigation rail so portraits/cards never overlap it
        content.setPadding(dp(this, 96), dp(this, 36), dp(this, CinemaTokens.UTILITY_GUTTER), dp(this, 36));
        scroll.addView(content);

        // Header: 3:4 Portrait Card + Name / Birth / Bio
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.TOP);
        header.setClipChildren(false);
        content.addView(header);

        int pw = dp(this, 155);
        int ph = dp(this, 205);
        int pr = dp(this, 16);

        portraitFrame = new FrameLayout(this);
        portraitFrame.setFocusable(true);
        portraitFrame.setClickable(true);
        portraitFrame.setClipToOutline(true);
        GradientDrawable baseBg = rounded(0xff222222, pr, 0);
        baseBg.setStroke(dp(this, 1), 0x4DF5C518);
        portraitFrame.setBackground(baseBg);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(pw, ph);
        pLp.rightMargin = dp(this, 24);
        header.addView(portraitFrame, pLp);

        TextView fallbackInitial = text(this, 44, true);
        fallbackInitial.setGravity(Gravity.CENTER);
        fallbackInitial.setTextColor(CinemaTokens.GOLD);
        fallbackInitial.setText(actorName.substring(0, 1).toUpperCase(Locale.ROOT));
        portraitFrame.addView(fallbackInitial, new FrameLayout.LayoutParams(-1, -1));

        portraitView = new ImageView(this);
        portraitView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portraitFrame.addView(portraitView, new FrameLayout.LayoutParams(-1, -1));
        if (actorPhotoUrl != null && !actorPhotoUrl.isBlank()) {
            Picasso.get().load(actorPhotoUrl).resize(pw, ph).centerCrop(Gravity.TOP).into(portraitView);
        }
        portraitFrame.setContentDescription(actorName);
        portraitFrame.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable ring = rounded(Color.TRANSPARENT, pr, 0);
            if (focused) ring.setStroke(dp(this, 3), CinemaTokens.FOCUS);
            portraitFrame.setForeground(focused ? ring : null);
            animatePosterFocus(portraitFrame, focused);
        });
        portraitFrame.setOnClickListener(v -> openFullScreenPhoto());
        portraitFrame.setOnKeyListener((v, key, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) return settingsNav != null && settingsNav.requestFocus();
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && creditsRow != null && creditsRow.getChildCount() > 0) {
                return creditsRow.getChildAt(0).requestFocus();
            }
            return false;
        });

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        header.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nameTv = text(this, CinemaTokens.FILM_TITLE, true);
        nameTv.setText(actorName);
        nameTv.setTextColor(CinemaTokens.TEXT);
        nameTv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        nameTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        infoCol.addView(nameTv);

        if (actorRole != null && !actorRole.isBlank()) {
            TextView roleTv = text(this, CinemaTokens.LABEL, false);
            roleTv.setText(label(this, "الدور: ", "Role: ") + actorRole);
            roleTv.setTextColor(CinemaTokens.GOLD);
            roleTv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            roleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            roleTv.setPadding(0, dp(this, 4), 0, 0);
            infoCol.addView(roleTv);
        }

        birthView = text(this, CinemaTokens.CAPTION, false);
        birthView.setTextColor(CinemaTokens.SECONDARY);
        birthView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        birthView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        birthView.setPadding(0, dp(this, 6), 0, 0);
        birthView.setVisibility(View.GONE);
        infoCol.addView(birthView);

        bioView = text(this, CinemaTokens.LABEL, false);
        bioView.setTextColor(CinemaTokens.TEXT);
        bioView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        bioView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        bioView.setMaxLines(5);
        bioView.setEllipsize(TextUtils.TruncateAt.END);
        bioView.setLineSpacing(dp(this, 2), 1f);
        bioView.setPadding(0, dp(this, 10), 0, 0);
        bioView.setText(label(this, "جارٍ تحميل السيرة والأعمال...", "Loading biography & credits..."));
        bioView.setFocusable(true);
        bioView.setClickable(true);
        bioView.setOnFocusChangeListener((v, focused) ->
                bioView.setTextColor(focused ? CinemaTokens.FOCUS : CinemaTokens.TEXT));
        bioView.setOnClickListener(v -> {
            if (!fullBio.isBlank()) {
                CinemaUi.dialog(this).setTitle(actorName).setMessage(fullBio)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
        infoCol.addView(bioView);

        // Credits section
        creditsTitle = text(this, CinemaTokens.ROW_TITLE, true);
        creditsTitle.setText(label(this, "الأفلام والمسلسلات", "Movies & Series"));
        creditsTitle.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        creditsTitle.setPadding(dp(this, 4), 0, 0, 0);
        LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(-1, -2);
        ctLp.topMargin = dp(this, 24);
        ctLp.bottomMargin = dp(this, 10);
        content.addView(creditsTitle, ctLp);

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setClipChildren(false);
        hScroll.setClipToPadding(false);
        hScroll.setPadding(dp(this, 4), 0, dp(this, 24), 0);
        content.addView(hScroll);

        creditsRow = new LinearLayout(this);
        creditsRow.setOrientation(LinearLayout.HORIZONTAL);
        creditsRow.setClipChildren(false);
        creditsRow.setClipToPadding(false);
        hScroll.addView(creditsRow);

        settingsNav = CinemaNavigation.attach(this, root);
        portraitFrame.requestFocus();
        loadActorData();
    }

    private void openFullScreenPhoto() {
        String url = (fullPhotoUrl != null && !fullPhotoUrl.isBlank()) ? fullPhotoUrl : actorPhotoUrl;
        if (url == null || url.isBlank()) {
            Toast.makeText(this, label(this, "لا توجد صورة بدقة كاملة لهذا الممثل",
                    "No full-resolution photo available"), Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout viewer = new FrameLayout(this);
        viewer.setBackgroundColor(0xF0000000);
        ImageView big = new ImageView(this);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(big, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        Picasso.get().load(url).into(big);
        TextView hint = text(this, CinemaTokens.CAPTION, false);
        hint.setText(actorName + "  ·  " + label(this, "اضغط للرجوع", "Press Back to close"));
        hint.setTextColor(CinemaTokens.SECONDARY);
        hint.setPadding(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
        hint.setBackground(rounded(0x99171717, dp(this, 12), 0));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(this, 24);
        viewer.addView(hint, hp);
        viewer.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(viewer);
        dialog.show();
    }

    private void loadActorData() {
        Context app = getApplicationContext();
        String langCode = OpenMetadata.resolveLang(app);
        boolean wantArabic = "ar".equals(langCode);
        io.execute(() -> {
            List<Credit> localCredits = queryLocalLibraryCredits(app, actorName);
            Map<String, Credit> merged = new LinkedHashMap<>();
            for (Credit c : localCredits) {
                merged.putIfAbsent(norm(c.title()), c);
            }

            CachedProfile cached = readProfileCache(app, actorName, langCode);
            if (cached != null) {
                for (Credit rc : cached.credits) {
                    merged.putIfAbsent(norm(rc.title()), rc);
                }
                String photo = (cached.photo != null && !cached.photo.isBlank()) ? cached.photo : actorPhotoUrl;
                String displayBio = cached.bio.isBlank()
                        ? label(app, "لا تتوفر سيرة ذاتية إضافية حالياً.", "No additional biography available.")
                        : cached.bio;
                applyProfileToUi(cached.birth, cached.bio, displayBio, photo, new ArrayList<>(merged.values()));
                return;
            }

            String birthText = "";
            String bio = "";
            String bestPhoto = actorPhotoUrl;
            List<Credit> remoteCredits = new ArrayList<>();

            // 1. Optional TMDb person lookup if user saved TMDb key
            String tmdbKey = ProviderKeys.get(app, ProviderKeys.TMDB);
            if (!tmdbKey.isBlank()) {
                try {
                    String tmdbLang = wantArabic ? "ar-SA" : "en-US";
                    JSONObject s = jsonGet("https://api.themoviedb.org/3/search/person?api_key="
                            + Uri.encode(tmdbKey) + "&query=" + Uri.encode(actorName));
                    JSONArray res = s.optJSONArray("results");
                    if (res != null && res.length() > 0) {
                        long pid = res.getJSONObject(0).getLong("id");
                        JSONObject p = jsonGet("https://api.themoviedb.org/3/person/" + pid
                                + "?api_key=" + Uri.encode(tmdbKey) + "&language=" + Uri.encode(tmdbLang)
                                + "&append_to_response=combined_credits");
                        String prof = p.optString("profile_path", "");
                        if (!prof.isEmpty() && !"null".equals(prof)) {
                            bestPhoto = "https://image.tmdb.org/t/p/original" + prof;
                        }
                        birthText = formatBirth(p.optString("birthday", ""), p.optString("place_of_birth", ""));
                        bio = p.optString("biography", "").strip();
                        if (bio.isEmpty() && wantArabic) {
                            JSONObject pEn = jsonGet("https://api.themoviedb.org/3/person/" + pid
                                    + "?api_key=" + Uri.encode(tmdbKey) + "&language=en-US");
                            bio = pEn.optString("biography", "").strip();
                        }
                        JSONObject cc = p.optJSONObject("combined_credits");
                        JSONArray castArr = cc != null ? cc.optJSONArray("cast") : null;
                        if (castArr != null) {
                            for (int i = 0; i < Math.min(24, castArr.length()); i++) {
                                JSONObject c = castArr.optJSONObject(i);
                                if (c == null) continue;
                                String mt = c.optString("media_type", "movie");
                                boolean show = "tv".equals(mt);
                                String t = c.optString(show ? "name" : "title", "").strip();
                                if (t.isEmpty()) continue;
                                String date = c.optString(show ? "first_air_date" : "release_date", "");
                                String yr = date.length() >= 4 ? date.substring(0, 4) : "";
                                String post = c.optString("poster_path", "");
                                String pUrl = !post.isEmpty() && !"null".equals(post)
                                        ? "https://image.tmdb.org/t/p/w342" + post : null;
                                remoteCredits.add(new Credit(t, yr, c.optString("character", ""), pUrl, show, "", null, null));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 2. Open TVmaze People API (always keyless!)
            try {
                String pidExtra = getIntent().getStringExtra(EXTRA_PERSON_ID);
                long personId = 0L;
                if (pidExtra != null && pidExtra.matches("[0-9]+")) personId = Long.parseLong(pidExtra);
                if (personId <= 0) {
                    JSONArray arr = new JSONArray(httpGet("https://api.tvmaze.com/search/people?q=" + Uri.encode(actorName)));
                    for (int i = 0; i < Math.min(5, arr.length()); i++) {
                        JSONObject row = arr.optJSONObject(i);
                        JSONObject p = row != null ? row.optJSONObject("person") : null;
                        if (p == null) continue;
                        if (norm(actorName).equals(norm(p.optString("name", ""))) || i == 0) {
                            personId = p.optLong("id", 0L);
                            if (norm(actorName).equals(norm(p.optString("name", "")))) break;
                        }
                    }
                }
                if (personId > 0) {
                    JSONObject p = jsonGet("https://api.tvmaze.com/people/" + personId + "?embed=castcredits");
                    if (birthText.isBlank()) {
                        String country = p.optJSONObject("country") != null ? p.getJSONObject("country").optString("name", "") : "";
                        birthText = formatBirth(p.optString("birthday", ""), country);
                    }
                    if (bestPhoto == null || bestPhoto.isBlank()) {
                        JSONObject img = p.optJSONObject("image");
                        if (img != null) bestPhoto = img.optString("original", img.optString("medium", ""));
                    }
                    JSONArray credits = new JSONArray(httpGet("https://api.tvmaze.com/people/" + personId + "/castcredits?embed=show"));
                    for (int i = 0; i < Math.min(20, credits.length()); i++) {
                        JSONObject row = credits.optJSONObject(i);
                        JSONObject emb = row != null ? row.optJSONObject("_embedded") : null;
                        JSONObject show = emb != null ? emb.optJSONObject("show") : null;
                        if (show == null) continue;
                        String t = show.optString("name", "").strip();
                        if (t.isEmpty()) continue;
                        String prem = show.optString("premiered", "");
                        String yr = prem.length() >= 4 ? prem.substring(0, 4) : "";
                        JSONObject img = show.optJSONObject("image");
                        String pUrl = img != null ? img.optString("medium", img.optString("original", null)) : null;
                        JSONObject ext = show.optJSONObject("externals");
                        String showImdb = ext != null ? ext.optString("imdb", "") : "";
                        remoteCredits.add(new Credit(t, yr, "", pUrl, true, showImdb, null, null));
                    }
                }
            } catch (Exception ignored) {}

            // 3. Wikipedia fallback: strict English when lang=en, Arabic-first then English fallback when lang=ar
            if (bio.isBlank() || bestPhoto == null || bestPhoto.isBlank()) {
                try {
                    String slug = Uri.encode(actorName.replace(' ', '_'));
                    String host = wantArabic ? "ar.wikipedia.org" : "en.wikipedia.org";
                    JSONObject wiki = jsonGet("https://" + host + "/api/rest_v1/page/summary/" + slug);
                    if (!"disambiguation".equalsIgnoreCase(wiki.optString("type", ""))) {
                        if (bio.isBlank()) bio = wiki.optString("extract", "").strip();
                        if ((bestPhoto == null || bestPhoto.isBlank()) && wiki.optJSONObject("originalimage") != null) {
                            bestPhoto = wiki.getJSONObject("originalimage").optString("source", "");
                        }
                        if ((bestPhoto == null || bestPhoto.isBlank()) && wiki.optJSONObject("thumbnail") != null) {
                            bestPhoto = wiki.getJSONObject("thumbnail").optString("source", "");
                        }
                    }
                    if (wantArabic && (bio.isBlank() || bestPhoto == null || bestPhoto.isBlank())) {
                        JSONObject wikiEn = jsonGet("https://en.wikipedia.org/api/rest_v1/page/summary/" + slug);
                        if (!"disambiguation".equalsIgnoreCase(wikiEn.optString("type", ""))) {
                            if (bio.isBlank()) bio = wikiEn.optString("extract", "").strip();
                            if ((bestPhoto == null || bestPhoto.isBlank()) && wikiEn.optJSONObject("originalimage") != null) {
                                bestPhoto = wikiEn.getJSONObject("originalimage").optString("source", "");
                            }
                            if ((bestPhoto == null || bestPhoto.isBlank()) && wikiEn.optJSONObject("thumbnail") != null) {
                                bestPhoto = wikiEn.getJSONObject("thumbnail").optString("source", "");
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            writeProfileCache(app, actorName, langCode, new CachedProfile(
                    birthText, bio, bestPhoto == null ? "" : bestPhoto, remoteCredits));

            for (Credit rc : remoteCredits) {
                merged.putIfAbsent(norm(rc.title()), rc);
            }

            String finalBirth = birthText;
            String rawBio = bio;
            String finalBio = rawBio.isBlank() ? label(app, "لا تتوفر سيرة ذاتية إضافية حالياً.", "No additional biography available.") : rawBio;
            String finalPhoto = bestPhoto;
            List<Credit> allCredits = new ArrayList<>(merged.values());
            applyProfileToUi(finalBirth, rawBio, finalBio, finalPhoto, allCredits);
        });
    }

    private void applyProfileToUi(String finalBirth, String rawBio, String finalBio, String finalPhoto, List<Credit> allCredits) {
        handler.post(() -> {
            if (isDestroyed() || isFinishing()) return;
            fullBio = rawBio;
            if (finalPhoto != null && !finalPhoto.isBlank()) {
                fullPhotoUrl = finalPhoto;
                int pw = dp(this, 155);
                int ph = dp(this, 205);
                Picasso.get().load(finalPhoto).resize(pw, ph).centerCrop(Gravity.TOP).into(portraitView);
            }
            if (!finalBirth.isBlank()) {
                birthView.setText(finalBirth);
                birthView.setVisibility(View.VISIBLE);
            }
            bioView.setText(finalBio);
            renderCredits(allCredits);
        });
    }

    private record CachedProfile(String birth, String bio, String photo, List<Credit> credits) {}

    private static File profileCacheFile(Context context, String name, String lang) {
        String safe = (norm(name) + "_" + lang).replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 110) safe = Integer.toHexString(safe.hashCode()) + "_" + safe.substring(0, 60);
        return new File(new File(context.getCacheDir(), "actor_profiles_v1"), safe + ".json");
    }

    private static CachedProfile readProfileCache(Context context, String name, String lang) {
        if (context == null || name == null || name.isBlank()) return null;
        try {
            File f = profileCacheFile(context, name, lang);
            if (!f.isFile() || f.lastModified() < System.currentTimeMillis() - PROFILE_TTL_MS) return null;
            JSONObject obj = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            String birth = obj.optString("birth", "");
            String bio = obj.optString("bio", "");
            String photo = obj.optString("photo", "");
            List<Credit> list = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("credits");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject co = arr.optJSONObject(i);
                    if (co == null) continue;
                    String title = co.optString("title", "");
                    if (title.isEmpty()) continue;
                    String poster = co.optString("poster", "");
                    list.add(new Credit(
                            title,
                            co.optString("year", ""),
                            co.optString("character", ""),
                            poster.isEmpty() ? null : poster,
                            co.optBoolean("show", false),
                            co.optString("imdb", ""),
                            null, null));
                }
            }
            return new CachedProfile(birth, bio, photo, list);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeProfileCache(Context context, String name, String lang, CachedProfile profile) {
        if (context == null || name == null || name.isBlank() || profile == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("birth", profile.birth == null ? "" : profile.birth);
            obj.put("bio", profile.bio == null ? "" : profile.bio);
            obj.put("photo", profile.photo == null ? "" : profile.photo);
            JSONArray arr = new JSONArray();
            if (profile.credits != null) {
                for (Credit c : profile.credits) {
                    JSONObject co = new JSONObject();
                    co.put("title", c.title == null ? "" : c.title);
                    co.put("year", c.year == null ? "" : c.year);
                    co.put("character", c.character == null ? "" : c.character);
                    co.put("poster", c.posterUrl == null ? "" : c.posterUrl);
                    co.put("show", c.isShow);
                    co.put("imdb", c.imdbId == null ? "" : c.imdbId);
                    arr.put(co);
                }
            }
            obj.put("credits", arr);
            obj.put("ts", System.currentTimeMillis());
            File f = profileCacheFile(context, name, lang);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void renderCredits(List<Credit> list) {
        creditsRow.removeAllViews();
        if (list.isEmpty()) {
            TextView empty = text(this, CinemaTokens.LABEL, false);
            empty.setTextColor(CinemaTokens.SECONDARY);
            empty.setText(label(this, "لم يتم العثور على أعمال مسجلة.", "No known credits found."));
            creditsRow.addView(empty);
            return;
        }
        boolean rtlUi = arabic(this);
        int w = dp(this, 130);
        int h = dp(this, 195);
        for (int i = 0; i < list.size(); i++) {
            Credit c = list.get(i);
            final int idx = i;
            LinearLayout cardWrap = new LinearLayout(this);
            cardWrap.setOrientation(LinearLayout.VERTICAL);
            cardWrap.setLayoutDirection(rtlUi ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            cardWrap.setFocusable(true);
            cardWrap.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            lp.rightMargin = dp(this, 14);
            creditsRow.addView(cardWrap, lp);

            FrameLayout posterBox = new FrameLayout(this);
            posterBox.setClipToOutline(true);
            posterBox.setBackground(rounded(CinemaTokens.SURFACE, dp(this, CinemaTokens.POSTER_RADIUS), 0));
            cardWrap.addView(posterBox, new LinearLayout.LayoutParams(w, h));

            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            posterBox.addView(img, new FrameLayout.LayoutParams(-1, -1));
            if (c.posterUrl() != null && !c.posterUrl().isBlank()) {
                Picasso.get().load(c.posterUrl()).resize(w, h).centerCrop().into(img);
            } else if (c.localVideo() != null && c.localVideo().getPosterUri() != null) {
                Picasso.get().load(c.localVideo().getPosterUri()).resize(w, h).centerCrop().into(img);
            }

            if (c.localVideo() != null || c.localShow() != null) {
                TextView badge = text(this, 10, true);
                badge.setText(label(this, "في مكتبتك", "In Library"));
                badge.setTextColor(CinemaTokens.ON_FOCUS);
                badge.setPadding(dp(this, 6), dp(this, 2), dp(this, 6), dp(this, 2));
                badge.setBackground(rounded(CinemaTokens.FOCUS, dp(this, 4), 0));
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                bp.setMargins(dp(this, 6), dp(this, 6), dp(this, 6), 0);
                posterBox.addView(badge, bp);
            }

            TextView titleTv = text(this, CinemaTokens.CAPTION, true);
            String caption = c.title() + (c.year() != null && !c.year().isEmpty() ? " (" + c.year() + ")" : "");
            titleTv.setText(caption);
            titleTv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            titleTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            titleTv.setMaxLines(2);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            titleTv.setPadding(0, dp(this, 6), 0, 0);
            cardWrap.addView(titleTv);

            cardWrap.setContentDescription(caption);
            cardWrap.setOnFocusChangeListener((v, focused) -> {
                posterBox.setForeground(focused ? posterFocusRing(this, 2.5f) : null);
                animatePosterFocus(posterBox, focused);
                titleTv.setTextColor(focused ? CinemaTokens.FOCUS : CinemaTokens.TEXT);
            });
            cardWrap.setOnClickListener(v -> onCreditClick(c));
            cardWrap.setOnKeyListener((v, key, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (idx > 0) return creditsRow.getChildAt(idx - 1).requestFocus();
                    return settingsNav != null && settingsNav.requestFocus();
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && idx + 1 < creditsRow.getChildCount()) {
                    return creditsRow.getChildAt(idx + 1).requestFocus();
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) return portraitFrame.requestFocus();
                return false;
            });
        }
    }

    private void onCreditClick(Credit c) {
        if (c.localShow() != null) {
            startActivity(new Intent(this, CinemaSeriesActivity.class)
                    .putExtra(TvshowFragment.EXTRA_TVSHOW, c.localShow()));
            return;
        }
        if (c.localVideo() != null) {
            startActivity(new Intent(this, CinemaDetailsActivity.class)
                    .putExtra(VideoDetailsFragment.EXTRA_VIDEO, c.localVideo()));
            return;
        }
        if (StremioAddons.hasEnabled(this)) {
            Toast.makeText(this, label(this, "جارٍ البحث في الإضافات الخارجية...",
                    "Checking enabled addons..."), Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                String imdb = c.imdbId();
                if (imdb == null || !imdb.matches("tt[0-9]+")) {
                    int y = 0;
                    try { if (c.year() != null && !c.year().isEmpty()) y = Integer.parseInt(c.year()); } catch (Exception ignored) {}
                    OpenMetadata.Item found = OpenMetadata.lookup(getApplicationContext(),
                            new OpenMetadata.Query(c.title(), y, c.isShow()), null, null, 0, 0);
                    if (found != null) imdb = found.imdb();
                }
                if (imdb == null || !imdb.matches("tt[0-9]+")) {
                    handler.post(() -> {
                        if (!isDestroyed()) Toast.makeText(this, c.title() + " · "
                                + label(this, "غير متوفر في مكتبتك المحلية", "Not in your local library"), Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                String type = c.isShow() ? "series" : "movie";
                int s = c.isShow() ? 1 : 0;
                int ep = c.isShow() ? 1 : 0;
                List<StremioAddons.Stream> streams = StremioAddons.resolveStreams(this, type, imdb, s, ep);
                String finalImdb = imdb;
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (streams.isEmpty()) {
                        Toast.makeText(this, c.title() + " · "
                                + label(this, "لا توجد مصادر بث حالياً", "No addon streams available"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] labels = new String[streams.size()];
                    for (int i = 0; i < streams.size(); i++) labels[i] = streams.get(i).displayLabel();
                    CinemaUi.dialog(this).setTitle(c.title())
                            .setItems(labels, (d, which) -> {
                                StremioAddons.Stream chosen = streams.get(which);
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(chosen.url), this, CinemaHlsActivity.class)
                                        .putExtra("format", "addon")
                                        .putExtra("title", c.title())
                                        .putExtra("stream_key", "addon:" + finalImdb + (c.isShow() ? ":s1e1" : ""))
                                        .putExtra("media_type", c.isShow() ? "episode" : "movie")
                                        .putExtra("imdb_id", finalImdb)
                                        .putExtra("season", s)
                                        .putExtra("episode", ep);
                                startActivity(intent);
                            }).setNegativeButton(android.R.string.cancel, null).show();
                });
            });
        } else {
            Toast.makeText(this, c.title() + " · "
                    + label(this, "غير متوفر في مكتبتك المحلية", "Not in your local library"), Toast.LENGTH_SHORT).show();
        }
    }

    private List<Credit> queryLocalLibraryCredits(Context context, String name) {
        List<Credit> out = new ArrayList<>();
        if (name == null || name.isBlank()) return out;
        String like = "%" + name.trim() + "%";
        String sel = "(" + VideoStore.Video.VideoColumns.SCRAPER_M_ACTORS + " LIKE ? OR "
                + VideoStore.Video.VideoColumns.SCRAPER_S_ACTORS + " LIKE ? OR "
                + VideoStore.Video.VideoColumns.SCRAPER_M_DIRECTORS + " LIKE ?) AND "
                + VideoStore.Video.VideoColumns.ARCHOS_HIDDEN_BY_USER + "=0";
        String[] args = {like, like, like};
        try (Cursor cursor = context.getContentResolver().query(
                VideoStore.Video.Media.EXTERNAL_CONTENT_URI, VideoLoader.mProjection, sel, args,
                VideoStore.Video.VideoColumns._ID + " DESC")) {
            if (cursor == null) return out;
            VideoCursorMapper mapper = new VideoCursorMapper();
            mapper.bindColumns(cursor);
            int showCol = cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID);
            java.util.Set<Long> seenShows = new java.util.HashSet<>();
            while (cursor.moveToNext() && out.size() < 20) {
                Video v = (Video) mapper.bind(cursor);
                if (v instanceof Episode ep) {
                    long showId = showCol >= 0 ? cursor.getLong(showCol) : 0L;
                    if (showId > 0 && seenShows.add(showId)) {
                        Tvshow show = new Tvshow(showId, ep.getShowName(), ep.getPosterUri(), 0, 0, 0, ep.getFilePath());
                        Uri cached = CinemaArtwork.getCachedPoster(context, "s:" + showId, ep.getPosterUri());
                        out.add(new Credit(ep.getShowName(), "", "", cached != null ? cached.toString() : null, true, "", null, show));
                    }
                } else {
                    int yr = v instanceof Movie m ? m.getYear() : 0;
                    Uri cached = CinemaArtwork.getCachedPoster(context, "v:" + v.getId(), v.getPosterUri());
                    out.add(new Credit(v.getName(), yr > 0 ? Integer.toString(yr) : "", "",
                            cached != null ? cached.toString() : null, false, "", v, null));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String formatBirth(String birthDate, String place) {
        if (birthDate == null || birthDate.isBlank()) return place == null ? "" : place;
        StringBuilder sb = new StringBuilder();
        sb.append(label(this, "تاريخ الميلاد: ", "Born: ")).append(birthDate);
        try {
            if (birthDate.length() >= 4) {
                int y = Integer.parseInt(birthDate.substring(0, 4));
                int nowY = Calendar.getInstance().get(Calendar.YEAR);
                if (y > 1900 && y <= nowY) {
                    int age = nowY - y;
                    sb.append(label(this, " (" + age + " سنة)", " (age " + age + ")"));
                }
            }
        } catch (Exception ignored) {}
        if (place != null && !place.isBlank()) sb.append(" · ").append(place);
        return sb.toString();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static JSONObject jsonGet(String url) throws Exception {
        return new JSONObject(httpGet(url));
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "LocalCinema/1.0 (https://github.com/alnouri96/meedx)");
        try {
            if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 2 * 1024 * 1024) throw new java.io.IOException("Response too large");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
