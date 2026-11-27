package com.archos.mediacenter.video.leanback.cinema;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.archos.mediacenter.utils.ShortcutDbAdapter;
import com.archos.mediacenter.video.browser.adapters.mappers.VideoCursorMapper;
import com.archos.mediacenter.video.browser.adapters.mappers.TvshowCursorMapper;
import com.archos.mediacenter.video.browser.adapters.object.Base;
import com.archos.mediacenter.video.browser.adapters.object.Video;
import com.archos.mediacenter.video.browser.adapters.object.Movie;
import com.archos.mediacenter.video.browser.adapters.object.Tvshow;
import com.archos.mediacenter.video.browser.loader.AllTvshowsLoader;
import com.archos.mediacenter.video.leanback.adapter.NetworkShortcutMapper;
import com.archos.mediacenter.video.leanback.adapter.object.NetworkShortcut;
import com.archos.mediacenter.video.leanback.details.VideoDetailsFragment;
import com.archos.mediacenter.video.leanback.search.VideoSearchActivity;
import com.archos.mediacenter.video.leanback.tvshow.TvshowFragment;
import com.archos.mediaprovider.NetworkScanner;
import com.archos.mediaprovider.video.VideoStore;
import com.squareup.picasso.Picasso;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Cinematic TV entry point backed by the original NOVA provider, scanner and player. */
public class CinemaHomeFragment extends Fragment implements LoaderManager.LoaderCallbacks<Cursor> {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final java.util.Set<String> pendingPosters = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static final String EXTRA_RETURN_HOME_TOP = "cinema_return_home_top";
    private static final String SORT_LATEST = "latest";
    private static final String SORT_OLDEST = "oldest";
    private static final String SORT_NAME = "name";
    private static final String SORT_NAME_DESC = "name_desc";
    private static final String SORT_YEAR = "year";
    private static final String SORT_YEAR_OLDEST = "year_oldest";
    private static final String SORT_RATING = "rating";
    private static final String SORT_WATCHED = "watched";
    private final java.util.Set<String> attemptedPosters = new java.util.HashSet<>();
    private final java.util.Set<String> failedPosterLoads = new java.util.HashSet<>();
    private final Map<String, Long> posterRetryAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService posterIo=Executors.newFixedThreadPool(4);
    private final ExecutorService rowDiffIo=Executors.newSingleThreadExecutor();
    private final ExecutorService watchActionIo=Executors.newCachedThreadPool();
    private final Map<String, Long> optimisticRemovedContinueAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Boolean> optimisticWatchedState = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, Integer> rowLoadTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final RecyclerView.RecycledViewPool sharedPosterPool = new RecyclerView.RecycledViewPool();
    private final android.util.LruCache<String, Integer> backdropTintCache = new android.util.LruCache<>(128);
    private volatile boolean videoStoreDirtyWhilePaused = false;
    private long lastObservedWatchEpoch = -1L;
    private long lastLoadedLibraryEpoch = -1L;
    private boolean warmStartupDone = false;
    private volatile boolean cachedAddonsEnabled = false;
    private static volatile String cachedSectionsSignature = "";
    private static volatile List<LibraryRow> cachedDynamicSections = List.of();
    private android.database.ContentObserver videoStoreObserver;
    private final Map<String,CinemaArtwork.Art> heroArt=new HashMap<>();
    private final Map<Integer,java.util.concurrent.Future<?>> posterTasks=new HashMap<>();
    private final Map<Integer, List<Item>> data = new HashMap<>();
    private final Map<Integer, RecyclerView> lists = new HashMap<>();
    private FrameLayout root;
    private LinearLayout rail, rows;
    private ScrollView contentScroll;
    private LinearLayout otherVideos;
    private View shade, lastContentFocus,otherFirst;
    private View tileNetworkLed, tileQrLed, tileRemoteLed;
    private View otherRemoteCard;
    private View tileAddonsLed;
    private TextView otherDescription;
    private final Runnable tileLedTick = new Runnable() {
        @Override public void run() {
            if (!isAdded() || root == null) return;
            refreshTileStatusDots();
            handler.postDelayed(this, 3000L);
        }
    };
    private ImageView backdrop;
    private ImageView logo;
    private View horizontalShade,bottomShade;
    private java.util.concurrent.Future<?> artworkTask;
    private TextView title, meta, plot, empty;
    private CinemaWeatherWidget weatherWidget;
    private SharedPreferences prefs;
    private boolean expanded;
    private int libraryCount;
    private static final java.util.regex.Pattern SERIES_PATH_OR_NAME_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:\\bS\\d{1,2}\\s*E\\d{1,3}\\b|\\bS\\d{1,2}\\b|\\b\\d{1,2}x\\d{1,3}\\b|\\bSeason\\s*\\d+|\\bEpisode\\s*\\d+|\\bEp\\.?\\s*\\d+|الموسم|الحلقة|مسلسل|خواتم السلطة|الظلام والنيران|بيتسبرغ|نيغلي|\\bThe\\s+Pitt\\b|\\bReacher\\b|\\bRings\\s+of\\s+Power\\b|\\bShadow\\s+and\\s+Bone\\b|[/\\\\](?:tv\\s*shows?|series|shows|المسلسلات|مسلسلات)[/\\\\])");
    private static final java.util.regex.Pattern CARTOON_PATH_PATTERN = java.util.regex.Pattern.compile(
            "(?i)[/\\\\](?:cartoons?|anime|animation|kids|الكارتون|كرتون|أنمي|انمي)[/\\\\]");

    static boolean isSeriesOrEpisodeFile(Base value, String filePath) {
        if (value instanceof com.archos.mediacenter.video.browser.adapters.object.Movie) return false;
        if (value instanceof Tvshow || value instanceof com.archos.mediacenter.video.browser.adapters.object.Episode) return true;
        if (filePath != null && (SERIES_PATH_OR_NAME_PATTERN.matcher(filePath).find()
                || SERIES_PATH_OR_NAME_PATTERN.matcher(Uri.decode(filePath)).find())) return true;
        if (value instanceof Video v && v.getFileUri() != null) {
            String u = v.getFileUri().toString();
            if (SERIES_PATH_OR_NAME_PATTERN.matcher(u).find() || SERIES_PATH_OR_NAME_PATTERN.matcher(Uri.decode(u)).find()) return true;
        }
        String name = value != null ? value.getName() : null;
        return name != null && SERIES_PATH_OR_NAME_PATTERN.matcher(name).find();
    }

    static boolean isCartoonFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) return false;
        return CARTOON_PATH_PATTERN.matcher(filePath).find()
                || CARTOON_PATH_PATTERN.matcher(Uri.decode(filePath)).find();
    }

    private record LibraryRow(String name, List<String> prefixes, int category, boolean mobile) {}
    private final Map<Integer,LibraryRow> libraryRows=new java.util.LinkedHashMap<>();
    private int nextLibraryRow=100;
    private boolean librariesLoaded;
    private String lastRailSignature = "";
    private boolean railBuilt;
    private boolean sidebarSubmenuOpen;
    private TextView searchTile;
    private TextView librariesTile;
    private record DiscoverLocation(int strip,String key) {}
    private DiscoverLocation selectedDiscover,returningDiscover;
    private final List<String> renderedSectionKeys = new ArrayList<>();
    private final Map<String, Integer> sectionKeyToRowId = new java.util.HashMap<>();
    private final Map<Integer, String> rowIdToSectionKey = new java.util.HashMap<>();
    private int pendingContinueFocusPos = -1;

    private List<Integer> rowOrder(){
        List<Integer> order = new ArrayList<>();
        order.add(1);
        order.addAll(libraryRows.keySet());
        return order;
    }

    private void restartActiveLoaders() {
        if (!isAdded() || root == null) return;
        LoaderManager lm = LoaderManager.getInstance(this);
        lm.restartLoader(1, null, this);
        for (int id : libraryRows.keySet()) {
            lm.restartLoader(id, null, this);
        }
    }

    private List<String> activeSectionOrder() {
        List<String> available = new ArrayList<>();
        sectionKeyToRowId.clear();
        rowIdToSectionKey.clear();
        for (int id : rowOrder()) {
            LibraryRow source = libraryRows.get(id);
            String key = source != null ? HomePreferences.libSectionKey(source.name()) : HomePreferences.coreSectionKey(id);
            if (!sectionKeyToRowId.containsKey(key)) {
                available.add(key);
                sectionKeyToRowId.put(key, id);
                rowIdToSectionKey.put(id, key);
            }
        }
        available.add(HomePreferences.KEY_TRAILER_TRENDING);
        available.add(HomePreferences.KEY_TRAILER_NEW);
        available.add(HomePreferences.KEY_TRAILER_COMING);
        return HomePreferences.sectionOrder(prefs, available);
    }

    public static String sectionTitle(Context c, String sectionKey) {
        if (sectionKey == null) return "";
        if (HomePreferences.KEY_TRAILER_TRENDING.equals(sectionKey)) {
            return label(c, "الإعلانات الرائجة", "Trending Trailers");
        }
        if (HomePreferences.KEY_TRAILER_NEW.equals(sectionKey)) {
            return c.getString(com.archos.mediacenter.video.R.string.cinema_new_trailers);
        }
        if (HomePreferences.KEY_TRAILER_COMING.equals(sectionKey)) {
            return c.getString(com.archos.mediacenter.video.R.string.cinema_coming_soon);
        }
        if (HomePreferences.isLibKey(sectionKey)) {
            return HomePreferences.libNameFromKey(sectionKey);
        }
        if (sectionKey.startsWith("row:")) {
            try {
                return rowTitle(c, Integer.parseInt(sectionKey.substring(4)));
            } catch (NumberFormatException ignored) {}
        }
        return sectionKey;
    }
    private boolean contentLoaded;
    private String selectedKey;
    private int selectedRow=1;
    private int selectedPosition=0;
    private int pendingRow=1;
    private androidx.appcompat.app.AlertDialog exitDialog;
    private final java.util.Set<Long> autoCompletedContinueIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private record PosterLocation(int row, String sectionKey, String key, int position, int scrollY, int rowScrollPos, int rowScrollOffset) {}
    private PosterLocation returningPoster;
    private PosterLocation persistedRestore;
    private DiscoverLocation persistedDiscoverRestore;
    private static final String PREF_HOME_SECTION = "cinema_home_last_section";
    private static final String PREF_HOME_ROW = "cinema_home_last_row";
    private static final String PREF_HOME_KEY = "cinema_home_last_key";
    private static final String PREF_HOME_POS = "cinema_home_last_pos";
    private static final String PREF_HOME_SCROLL_Y = "cinema_home_last_scroll_y";
    private static final String PREF_HOME_ROW_POS = "cinema_home_last_row_pos";
    private static final String PREF_HOME_ROW_OFF = "cinema_home_last_row_off";
    private static final String PREF_HOME_DISC_STRIP = "cinema_home_last_disc_strip";
    private static final String PREF_HOME_DISC_KEY = "cinema_home_last_disc_key";
    private int focusRequest;
    private int generation;
    private int viewGeneration;
    private String layoutSignature = "";
    private ValueAnimator railAnimation;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (weatherWidget == null) return;
            weatherWidget.updateNow();
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    static final class Item {
        final Base value; final Uri background; final CinemaWatchStore.StreamEntry stream; final long dateAdded; final CinemaWatchStore.TraktItem trakt;
        String cachedPosterKey;
        String cachedCardTitle;
        String cachedEpisodeTag;
        boolean cachedWatched;
        int cachedProgressWidth = -1;
        Item(Base value, Uri background) { this(value, background, null, 0L); }
        Item(Base value, Uri background, CinemaWatchStore.StreamEntry stream) { this(value, background, stream, 0L); }
        Item(Base value, Uri background, CinemaWatchStore.StreamEntry stream, long dateAdded) {
            this(value, background, stream, dateAdded, null);
        }
        Item(Base value, Uri background, CinemaWatchStore.StreamEntry stream, long dateAdded,
             CinemaWatchStore.TraktItem trakt) {
            this.trakt = trakt;
            this.value = value; this.background = background; this.stream = stream; this.dateAdded = dateAdded;
        }
    }
    public static String rowTitle(Context c, int id) {
        return switch (id) {
            case 1 -> c.getString(com.archos.mediacenter.video.R.string.cinema_continue_watching);
            case 6 -> c.getString(com.archos.mediacenter.video.R.string.cinema_watched);
            case 2 -> c.getString(com.archos.mediacenter.video.R.string.cinema_recently_added);
            case 3 -> c.getString(com.archos.mediacenter.video.R.string.cinema_recently_added_movies);
            default -> c.getString(com.archos.mediacenter.video.R.string.cinema_recently_added_tv_shows);
        };
    }
    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        viewGeneration++;pending=null;lists.clear();rowLoadTokens.clear();optimisticRemovedContinueAt.clear();optimisticWatchedState.clear();layoutSignature="";contentLoaded=false;librariesLoaded=false;lastLoadedLibraryEpoch=-1L;lastRailSignature="";railBuilt=false;sidebarSubmenuOpen=false;searchTile=null;librariesTile=null;expanded=false;lastContentFocus=null;
        sharedPosterPool.setMaxRecycledViews(0, 32);
        Context c = requireContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(c);
        libraryRows.clear();
        nextLibraryRow = 100;
        List<LibraryRow> cachedSections = cachedDynamicSections;
        if (cachedSections != null && !cachedSections.isEmpty()) {
            for (LibraryRow row : cachedSections) {
                libraryRows.put(nextLibraryRow++, row);
            }
            librariesLoaded = true;
        }
        videoStoreObserver = new android.database.ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                if (!isResumed()) videoStoreDirtyWhilePaused = true;
            }
        };
        try {
            c.getApplicationContext().getContentResolver().registerContentObserver(
                    VideoStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoStoreObserver);
        } catch (Exception ignored) {}
        loadPersistedFocusState();
        data.clear();
        boolean rtl = CinemaUi.arabic(c);
        root = new FrameLayout(c);
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CinemaTokens.CANVAS);root.setFocusableInTouchMode(true);root.requestFocus();
        backdrop = new ImageView(c); backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View horizontal = new View(c);
        horizontalShade=horizontal;
        horizontal.setBackground(new GradientDrawable(rtl
                ?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{CinemaTokens.CANVAS, 0xc4171717, 0x10171717}));
        root.addView(horizontal, new FrameLayout.LayoutParams(-1,-1));
        View bottom = new View(c);
        bottomShade=bottom;
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00171717,0x44171717,CinemaTokens.CANVAS}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1,-1));
        LinearLayout hero = new LinearLayout(c); hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(dp(c,540),dp(c,205),Gravity.TOP|(rtl ? Gravity.RIGHT : Gravity.LEFT));
        if (rtl) hp.rightMargin = dp(c, 100);
        else hp.leftMargin = dp(c, 100);
        hp.topMargin=dp(c,48); root.addView(hero,hp);
        FrameLayout identity=new FrameLayout(c);hero.addView(identity,new LinearLayout.LayoutParams(-1,dp(c,96)));
        title=text(c,CinemaTokens.FILM_TITLE,true);title.setMaxLines(2);title.setText("Local Cinema");
        title.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
        identity.addView(title,new FrameLayout.LayoutParams(-1,-1));
        logo=new ImageView(c);logo.setScaleType(rtl ? ImageView.ScaleType.FIT_END : ImageView.ScaleType.FIT_START);logo.setVisibility(View.GONE);
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(dp(c,320),-1, rtl ? Gravity.RIGHT : Gravity.LEFT);
        identity.addView(logo, logoLp);
        meta=text(c,CinemaTokens.LABEL,false); meta.setTextColor(CinemaTokens.SECONDARY);meta.setMaxLines(2);
        meta.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        meta.setPadding(0,dp(c,8),0,dp(c,8)); hero.addView(meta);
        plot=text(c,CinemaTokens.LABEL,false); plot.setMaxLines(3); plot.setLineSpacing(dp(c,2),1); plot.setTextColor(CinemaTokens.SECONDARY);
        plot.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        hero.addView(plot);

        weatherWidget = new CinemaWeatherWidget(c);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|(rtl ? Gravity.LEFT : Gravity.RIGHT));
        if (rtl) cp.leftMargin = dp(c, 32);
        else cp.rightMargin = dp(c, 32);
        cp.topMargin=dp(c,20); root.addView(weatherWidget,cp);

        ScrollView scroll = new ScrollView(c) {
            @Override public void computeScroll() {
                super.computeScroll();
                alignOtherVideos();
            }
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                alignOtherVideos();
            }
        };contentScroll=scroll; scroll.setClipChildren(false); scroll.setClipToPadding(false); scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false); scroll.setFocusable(false);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,-1);
        if (rtl) sp.rightMargin = dp(c, 60);
        else sp.leftMargin = dp(c, 60);
        sp.topMargin=dp(c,250);
        root.addView(scroll,sp);
        rows=new LinearLayout(c); rows.setOrientation(LinearLayout.VERTICAL); rows.setClipChildren(false); rows.setClipToPadding(false);
        scroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        empty=text(c,16,false); empty.setText(c.getString(com.archos.mediacenter.video.R.string.cinema_add_your_library_folders_in_network_files_to_get_started));
        empty.setPaddingRelative(dp(c,40),dp(c,18),dp(c,40),dp(c,18));
        shade=new View(c); shade.setBackgroundColor(CinemaTokens.RAIL_HOME_SCRIM); shade.setVisibility(View.GONE);
        root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        ScrollView railScroll=new ScrollView(c); railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setFocusable(false);
        railScroll.setClipToPadding(false);
        railScroll.setSmoothScrollingEnabled(false);
        railScroll.setBackground(rounded(CinemaTokens.RAIL_BACKGROUND,dp(c,CinemaTokens.CONTROL_RADIUS),0));
        FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(dp(c,CinemaTokens.RAIL_CLOSED),-2,(rtl ? Gravity.RIGHT : Gravity.LEFT)|Gravity.CENTER_VERTICAL);
        if (rtl) rp.rightMargin = dp(c, CinemaTokens.RAIL_LEFT);
        else rp.leftMargin = dp(c, CinemaTokens.RAIL_LEFT);
        rp.topMargin=dp(c,24);rp.bottomMargin=dp(c,24); root.addView(railScroll,rp);
        rail=new LinearLayout(c); rail.setId(View.generateViewId()); rail.setOrientation(LinearLayout.VERTICAL); rail.setPadding(dp(c,4),dp(c,8),dp(c,4),dp(c,8));
        railScroll.addView(rail);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus,newFocus)->{
            if(newFocus==null) return;
            boolean inSubmenu = CinemaActionMenu.isPanelDescendant(newFocus, root);
            boolean inRail = isInRail(newFocus) || sidebarSubmenuOpen || inSubmenu;
            if (isInRail(newFocus) && newFocus.getParent() == rail) {
                var bounds = new android.graphics.Rect();
                newFocus.getDrawingRect(bounds);
                newFocus.requestRectangleOnScreen(bounds, true);
            }
            if(!inRail&&newFocus!=root) lastContentFocus=newFocus;
            expand(inRail);
        });
        return root;
    }
    private boolean isInRail(View v) {
        if(v==rail||v==rail.getParent())return true;
        for(android.view.ViewParent p=v.getParent();p!=null;p=p.getParent()) if(p==rail) return true;
        return false;
    }
    public boolean returnToHomeTop() {
        if (root == null) return false;
        setPosterRowsShown(true);
        selectedDiscover = null;
        selectedRow = -1;
        selectedKey = null;
        selectedPosition = 0;
        returningPoster = null;
        persistedRestore = null;
        persistedDiscoverRestore = null;
        lastContentFocus = null;
        if (contentScroll != null) {
            contentScroll.smoothScrollTo(0, 0);
            contentScroll.post(() -> { if (contentScroll != null) contentScroll.scrollTo(0, 0); });
        }
        return focusFirstVisibleSection();
    }

    private static boolean isDescendant(View child, ViewGroup parent) {
        if (child == null || parent == null) return false;
        for (android.view.ViewParent p = child.getParent(); p != null; p = p.getParent()) {
            if (p == parent) return true;
        }
        return false;
    }

    public boolean handleBackPress() {
        if (!isAdded() || root == null) return false;
        if (CinemaActionMenu.isShowing(root)) {
            return CinemaActionMenu.dismiss(root, true);
        }
        if (exitDialog != null && exitDialog.isShowing()) {
            exitDialog.dismiss();
            return true;
        }
        View focused = root.findFocus();
        if (expanded || (focused != null && isInRail(focused))) {
            expand(false);
            focusContent();
            return true;
        }
        Context c = requireContext();
        String firstKey = firstVisibleSectionKey();
        boolean inFirstSection = false;
        if (firstKey != null) {
            if (HomePreferences.isTrailerKey(firstKey)) {
                int sIdx = HomePreferences.trailerIndex(firstKey);
                inFirstSection = selectedRow == 5 && selectedDiscover != null && selectedDiscover.strip() == sIdx;
            } else {
                Integer rId = sectionKeyToRowId.get(firstKey);
                inFirstSection = rId != null && selectedRow == rId;
            }
        }
        boolean scrolledDown = contentScroll != null && contentScroll.getScrollY() > dp(c, 48);
        boolean inSubRow = (firstKey != null && !inFirstSection)
                || (focused != null && otherVideos != null && isDescendant(focused, otherVideos));
        if (scrolledDown || inSubRow) {
            return returnToHomeTop();
        }
        showExitConfirmationDialog();
        return true;
    }

    private void showExitConfirmationDialog() {
        if (!isAdded()) return;
        if (exitDialog != null && exitDialog.isShowing()) return;
        Context c = requireContext();
        boolean ar = CinemaUi.arabic(c);
        exitDialog = CinemaUi.dialog(c)
                .setTitle(ar ? "هل تريد الخروج من التطبيق؟" : "Exit Local Cinema?")
                .setPositiveButton(ar ? "نعم، خروج" : "Exit", (d, w) -> {
                    if (isAdded() && getActivity() != null) getActivity().finish();
                })
                .setNegativeButton(ar ? "إلغاء" : "Cancel", (d, w) -> {
                    d.dismiss();
                    focusContent();
                })
                .setOnDismissListener(d -> exitDialog = null)
                .create();
        exitDialog.setOnShowListener(d -> {
            android.widget.Button pos = exitDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (pos != null) pos.requestFocus();
        });
        exitDialog.show();
        android.widget.Button pos = exitDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (pos != null) pos.requestFocus();
    }

    private boolean focusContent(){
        if(root==null)return false;
        if(selectedRow==5&&selectedDiscover!=null&&focusDiscover(selectedDiscover))return true;
        if(selectedRow==5&&lastContentFocus!=null&&lastContentFocus.isShown()&&lastContentFocus.isAttachedToWindow())return lastContentFocus.requestFocus();
        List<Integer> order=new ArrayList<>(rowOrder());
        if(order.remove((Integer)selectedRow))order.add(0,selectedRow);
        if(selectedKey!=null)for(int id:order){RecyclerView list=lists.get(id);if(list!=null&&list.getAdapter() instanceof Posters p){int position=p.find(selectedKey);if(position>=0)return focusPoster(list,position);}}
        return focusFirstVisibleSection();
    }

    private boolean isSectionPopulated(String key) {
        if (key == null) return false;
        if (HomePreferences.isTrailerKey(key)) {
            int s = HomePreferences.trailerIndex(key);
            return s >= 0 && s < discoverStrips.size() && s < discoverGroups.size()
                    && HomePreferences.isSectionVisible(prefs, key)
                    && discoverGroups.get(s).getVisibility() != View.GONE
                    && discoverStrips.get(s).getChildCount() > 0;
        }
        Integer rowId = sectionKeyToRowId.get(key);
        if (rowId == null) return false;
        RecyclerView list = lists.get(rowId);
        return list != null && list.getAdapter() != null && list.getAdapter().getItemCount() > 0
                && list.getParent() instanceof View pv && pv.getVisibility() != View.GONE;
    }

    private String firstVisibleSectionKey() {
        for (String key : renderedSectionKeys) {
            if (isSectionPopulated(key)) return key;
        }
        return null;
    }

    private boolean focusSection(String key, int preferredIndex) {
        if (!isSectionPopulated(key)) return false;
        setPosterRowsShown(true);
        if (HomePreferences.isTrailerKey(key)) {
            int s = HomePreferences.trailerIndex(key);
            LinearLayout strip = discoverStrips.get(s);
            int target = Math.min(Math.max(0, preferredIndex), strip.getChildCount() - 1);
            return ((ViewGroup) strip.getChildAt(target)).getChildAt(0).requestFocus();
        }
        Integer rowId = sectionKeyToRowId.get(key);
        if (rowId == null) return false;
        RecyclerView list = lists.get(rowId);
        if (list == null || list.getAdapter() == null || list.getAdapter().getItemCount() == 0) return false;
        return focusPoster(list, Math.min(Math.max(0, preferredIndex), list.getAdapter().getItemCount() - 1));
    }

    private boolean focusFirstVisibleSection() {
        for (String key : renderedSectionKeys) {
            if (focusSection(key, 0)) return true;
        }
        for (int id : rowOrder()) {
            RecyclerView list = lists.get(id);
            if (list != null && list.getAdapter() != null && list.getAdapter().getItemCount() > 0) {
                return focusPoster(list, 0);
            }
        }
        return focusStrip(0, 1, 0) || (otherFirst != null && otherFirst.requestFocus());
    }

    private boolean focusLastVisibleSection(int preferredIndex) {
        setPosterRowsShown(true);
        for (int i = renderedSectionKeys.size() - 1; i >= 0; i--) {
            if (focusSection(renderedSectionKeys.get(i), preferredIndex)) return true;
        }
        return focusLastPosterRow(preferredIndex);
    }

    private boolean focusAdjacentSection(String currentKey, int step, int preferredIndex) {
        int from = renderedSectionKeys.indexOf(currentKey);
        if (from >= 0) {
            for (int i = from + step; i >= 0 && i < renderedSectionKeys.size(); i += step) {
                if (focusSection(renderedSectionKeys.get(i), preferredIndex)) return true;
            }
        }
        if (step > 0 && otherFirst != null) return otherFirst.requestFocus();
        return true;
    }

    private void captureContinueFocusPosition(int rowId, Item item) {
        if (rowId != 1) return;
        RecyclerView list = lists.get(1);
        int pos = -1;
        if (list != null && list.getAdapter() instanceof Posters posters) {
            if (item != null) pos = posters.find(itemKey(item));
            if (pos < 0 && root != null) {
                View focused = root.findFocus();
                if (focused != null && focused.getParent() == list) {
                    pos = list.getChildAdapterPosition(focused);
                }
            }
        }
        pendingContinueFocusPos = Math.max(0, pos);
    }
    /** Moves focus inside one horizontal row without ever falling back to the default (downward) focus search. */
    private boolean focusNeighbour(RecyclerView list,int position){
        return requestPoster(list,position);
    }
    private boolean focusPoster(RecyclerView list,int position){
        setPosterRowsShown(true);
        return requestPoster(list,position);
    }
    private boolean requestPoster(RecyclerView list,int position){
        int request=++focusRequest;
        RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(position);
        if(holder!=null)return holder.itemView.requestFocus();
        View before=root.findFocus();
        list.scrollToPosition(position);
        androidx.core.view.OneShotPreDrawListener.add(list,()->{
            if(root==null||request!=focusRequest)return;
            View current=root.findFocus();
            if(current!=null&&current!=root&&current!=before&&isInRail(current))return;
            RecyclerView.ViewHolder target=list.findViewHolderForAdapterPosition(position);
            if(target!=null)target.itemView.requestFocus();
        });
        return true;
    }
    private PosterLocation capturePosterLocation(int rowId, String key, int position) {
        int scrollY = contentScroll != null ? contentScroll.getScrollY() : 0;
        int rowScrollPos = -1;
        int rowScrollOff = 0;
        RecyclerView list = lists.get(rowId);
        if (list != null && list.getLayoutManager() instanceof LinearLayoutManager lm) {
            int first = lm.findFirstVisibleItemPosition();
            if (first != RecyclerView.NO_POSITION) {
                rowScrollPos = first;
                View fv = lm.findViewByPosition(first);
                rowScrollOff = fv != null ? (fv.getLeft() - list.getPaddingLeft()) : 0;
            }
        }
        return new PosterLocation(rowId, rowIdToSectionKey.get(rowId), key, Math.max(0, position),
                Math.max(0, scrollY), rowScrollPos, rowScrollOff);
    }

    private void saveHomeFocusState() {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        if (selectedRow == 5 && selectedDiscover != null) {
            ed.putInt(PREF_HOME_ROW, 5)
                    .putInt(PREF_HOME_DISC_STRIP, selectedDiscover.strip())
                    .putString(PREF_HOME_DISC_KEY, selectedDiscover.key())
                    .putInt(PREF_HOME_SCROLL_Y, contentScroll != null ? Math.max(0, contentScroll.getScrollY()) : 0)
                    .remove(PREF_HOME_KEY);
        } else if (selectedKey != null && selectedRow > 0) {
            PosterLocation loc = capturePosterLocation(selectedRow, selectedKey, selectedPosition);
            ed.putInt(PREF_HOME_ROW, loc.row())
                    .putString(PREF_HOME_SECTION, loc.sectionKey() != null ? loc.sectionKey() : "")
                    .putString(PREF_HOME_KEY, loc.key())
                    .putInt(PREF_HOME_POS, loc.position())
                    .putInt(PREF_HOME_SCROLL_Y, loc.scrollY())
                    .putInt(PREF_HOME_ROW_POS, loc.rowScrollPos())
                    .putInt(PREF_HOME_ROW_OFF, loc.rowScrollOffset())
                    .remove(PREF_HOME_DISC_KEY);
        }
        ed.apply();
    }

    private void loadPersistedFocusState() {
        persistedRestore = null;
        persistedDiscoverRestore = null;
        if (prefs == null) return;
        Intent actIntent = getActivity() != null ? getActivity().getIntent() : null;
        if (actIntent != null && actIntent.getBooleanExtra(EXTRA_RETURN_HOME_TOP, false)) return;
        int savedRow = prefs.getInt(PREF_HOME_ROW, -1);
        String discKey = prefs.getString(PREF_HOME_DISC_KEY, null);
        if (savedRow == 5 && discKey != null && !discKey.isBlank()) {
            persistedDiscoverRestore = new DiscoverLocation(prefs.getInt(PREF_HOME_DISC_STRIP, 0), discKey);
            return;
        }
        String savedKey = prefs.getString(PREF_HOME_KEY, null);
        if (savedRow > 0 && savedKey != null && !savedKey.isBlank()) {
            String savedSection = prefs.getString(PREF_HOME_SECTION, "");
            int savedPos = prefs.getInt(PREF_HOME_POS, 0);
            int savedScrollY = prefs.getInt(PREF_HOME_SCROLL_Y, 0);
            int savedRowPos = prefs.getInt(PREF_HOME_ROW_POS, -1);
            int savedRowOff = prefs.getInt(PREF_HOME_ROW_OFF, 0);
            persistedRestore = new PosterLocation(savedRow, savedSection.isBlank() ? null : savedSection,
                    savedKey, savedPos, savedScrollY, savedRowPos, savedRowOff);
        }
    }

    private RecyclerView resolveRowForLocation(PosterLocation location) {
        if (location == null) return null;
        if (location.sectionKey() != null) {
            Integer mapped = sectionKeyToRowId.get(location.sectionKey());
            if (mapped != null) {
                RecyclerView bySec = lists.get(mapped);
                if (bySec != null && bySec.getAdapter() instanceof Posters p && p.getItemCount() > 0) return bySec;
            }
        }
        RecyclerView direct = lists.get(location.row());
        if (direct != null && direct.getAdapter() instanceof Posters p && p.getItemCount() > 0) return direct;
        return null;
    }

    private boolean restorePoster(PosterLocation location){
        RecyclerView list = resolveRowForLocation(location);
        if(list!=null&&list.getAdapter() instanceof Posters posters&&posters.getItemCount()>0){
            int position=posters.find(location.key());
            if(position<0)position=Math.min(Math.max(0,location.position()),posters.getItemCount()-1);
            setPosterRowsShown(true);
            if(location.rowScrollPos()>=0 && location.rowScrollPos()<posters.getItemCount()
                    && list.getLayoutManager() instanceof LinearLayoutManager lm){
                lm.scrollToPositionWithOffset(location.rowScrollPos(), location.rowScrollOffset());
            }
            if(contentScroll!=null){
                int targetY = location.scrollY() > 0 ? location.scrollY()
                        : (list.getParent() instanceof View pv ? pv.getTop() : 0);
                contentScroll.post(() -> { if(root!=null&&contentScroll!=null) contentScroll.scrollTo(0, targetY); });
            }
            focusPoster(list,position);
            return true;
        }
        focusContent();
        return false;
    }
    private int logicalKey(View view, int physical) {
        boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                || (root != null && root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        if (!rtl) return physical;
        if (physical == KeyEvent.KEYCODE_DPAD_LEFT) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if (physical == KeyEvent.KEYCODE_DPAD_RIGHT) return KeyEvent.KEYCODE_DPAD_LEFT;
        return physical;
    }

    private String posterKey(Base item) {
        return MetadataLanguage.resolve(requireContext()) + ":" + mediaKey(item);
    }

    private static String mediaKey(Base item) {
        if (item instanceof Video v) {
            if (v.getId() > 0) return "v:" + v.getId();
            String fp = v.getFilePath();
            if (fp == null || fp.isBlank()) fp = v.getFileUri() != null ? v.getFileUri().toString() : v.getName();
            return "vu:" + Integer.toHexString((fp == null ? "" : fp).toLowerCase(Locale.ROOT).hashCode());
        }
        Tvshow s = (Tvshow) item;
        if (s.getTvshowId() > 0) return "s:" + s.getTvshowId();
        String name = s.getName() == null ? "" : s.getName().toLowerCase(Locale.ROOT).trim();
        return "st:" + Integer.toHexString(name.hashCode());
    }
    private static String itemKey(Item item){return item.stream!=null?"stream:"+item.stream.streamKey():mediaKey(item.value);}

    private static boolean isValidContinuePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return false;
        String trimmed = rawPath.trim();
        if (trimmed.startsWith("/")) {
            return new File(trimmed).isFile();
        }
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            return new File(trimmed).isFile();
        }
        String low = scheme.toLowerCase(Locale.ROOT);
        if ("file".equals(low)) {
            String p = uri.getPath();
            return p != null && new File(p).isFile();
        }
        return "smb".equals(low) || "smbj".equals(low) || "webdav".equals(low) || "webdavs".equals(low)
                || "upnp".equals(low) || "ftp".equals(low) || "ftps".equals(low) || "sftp".equals(low)
                || "sshj".equals(low) || "http".equals(low) || "https".equals(low) || "magnet".equals(low)
                || "content".equals(low) || "trakt".equals(low) || "addon".equals(low);
    }

    private List<String> matchKeysForItem(Item item) {
        List<String> keys = new ArrayList<>(6);
        if (item == null) return keys;
        keys.add(itemKey(item));
        keys.add(mediaKey(item.value));
        if (item.stream != null && item.stream.streamKey() != null && !item.stream.streamKey().isBlank()) {
            keys.add("stream:" + item.stream.streamKey());
            if (item.stream.episode() > 0) {
                String ek = continueEpisodeKey(item.stream.title(), item.stream.season(), item.stream.episode());
                if (!ek.isEmpty()) keys.add(ek);
            } else {
                String n = CinemaWatchStore.normalizeTitle(item.stream.title());
                if (!n.isEmpty()) keys.add("norm:" + n);
            }
        }
        if (item.trakt != null && item.trakt.itemKey() != null && !item.trakt.itemKey().isBlank()) {
            keys.add("trakt:" + item.trakt.itemKey());
            if (item.trakt.episode() > 0) {
                String ek = continueEpisodeKey(item.trakt.title(), item.trakt.season(), item.trakt.episode());
                if (!ek.isEmpty()) keys.add(ek);
            } else {
                String n = CinemaWatchStore.normalizeTitle(item.trakt.title());
                if (!n.isEmpty()) keys.add("norm:" + n);
            }
        }
        if (item.value instanceof Video v) {
            if (v.getFilePath() != null && !v.getFilePath().isBlank()) keys.add("path:" + v.getFilePath());
            boolean epFile = v instanceof com.archos.mediacenter.video.browser.adapters.object.Episode
                    || isSeriesOrEpisodeFile(v, v.getFilePath());
            if (epFile) {
                int[] se = v instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep
                        ? new int[]{ep.getSeasonNumber(), ep.getEpisodeNumber()}
                        : CinemaPosters.extractSeasonEpisode(v.getFilePath(), v.getName());
                String qt = CinemaPosters.query(v, true).title();
                String ek = continueEpisodeKey(qt, se[0], se[1]);
                if (!ek.isEmpty()) keys.add(ek);
            } else {
                String n = CinemaWatchStore.normalizeTitle(v.getName());
                if (!n.isEmpty()) keys.add("norm:" + n);
            }
        }
        return keys;
    }

    private boolean isOptimisticallyRemovedFromContinue(Item item, long candidateUpdatedAtMs) {
        if (optimisticRemovedContinueAt.isEmpty()) return false;
        for (String k : matchKeysForItem(item)) {
            Long removedAt = optimisticRemovedContinueAt.get(k);
            if (removedAt != null && (candidateUpdatedAtMs <= 0L || candidateUpdatedAtMs <= removedAt)) {
                return true;
            }
        }
        return false;
    }

    private void clearOptimisticRemovedForPlay(Item item) {
        for (String k : matchKeysForItem(item)) {
            optimisticRemovedContinueAt.remove(k);
        }
    }

    private boolean itemsReferToSameMedia(Item a, Item b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (itemKey(a).equals(itemKey(b)) || mediaKey(a.value).equals(mediaKey(b.value))) return true;
        List<String> ka = matchKeysForItem(a);
        for (String kb : matchKeysForItem(b)) {
            if (ka.contains(kb)) return true;
        }
        return false;
    }

    private void applyOptimisticRemoveFromContinue(int sourceRowId, Item target) {
        if (target == null || !isAdded() || root == null) return;
        long now = System.currentTimeMillis();
        for (String k : matchKeysForItem(target)) {
            optimisticRemovedContinueAt.put(k, now);
        }
        boolean keepWatched = isItemWatched(target);
        if (target.value instanceof Video v) {
            CinemaMediaTime.clearResume(v.getId(), v.getFilePath(), v.getFileUri());
            if (keepWatched) {
                CinemaMediaTime.rememberState(v.getId(), v.getFileUri(), v.getDurationMs(), CinemaPlaybackRouter.BOOKMARK_COMPLETED);
            }
        }
        RecyclerView cwList = lists.get(1);
        List<Item> cwItems = data.get(1);
        if (cwItems != null && !cwItems.isEmpty()) {
            int focusIdx = -1;
            if (cwList != null && cwList.getAdapter() instanceof Posters cwPosters) {
                for (int i = 0; i < cwItems.size(); i++) {
                    if (itemsReferToSameMedia(cwItems.get(i), target)) {
                        focusIdx = i;
                        break;
                    }
                }
                if (focusIdx < 0) {
                    View focused = root.findFocus();
                    if (focused != null && focused.getParent() == cwList) {
                        focusIdx = cwList.getChildAdapterPosition(focused);
                    }
                }
            }
            List<Item> nextCw = new ArrayList<>(cwItems.size());
            for (Item it : cwItems) {
                if (!itemsReferToSameMedia(it, target)) nextCw.add(it);
            }
            if (nextCw.size() != cwItems.size()) {
                data.put(1, nextCw);
                if (cwList != null && cwList.getAdapter() instanceof Posters cwPosters) {
                    cwPosters.replace(nextCw);
                    updateVisibility();
                    if (sourceRowId == 1) {
                        final int targetPos = Math.max(0, focusIdx);
                        if (!nextCw.isEmpty()) {
                            focusPoster(cwList, Math.min(targetPos, nextCw.size() - 1));
                        } else if (!focusAdjacentSection(HomePreferences.KEY_CONTINUE, 1, 0)) {
                            focusFirstVisibleSection();
                        }
                    }
                }
            }
        }
        for (Map.Entry<Integer, RecyclerView> entry : lists.entrySet()) {
            if (entry.getKey() == 1) continue;
            RecyclerView rv = entry.getValue();
            if (rv != null && rv.getAdapter() instanceof Posters adapter) {
                List<Item> snap = adapter.snapshot();
                for (int i = 0; i < snap.size(); i++) {
                    Item rowItem = snap.get(i);
                    if (itemsReferToSameMedia(rowItem, target)) {
                        rowItem.cachedProgressWidth = 0;
                        rowItem.cachedWatched = keepWatched;
                        adapter.notifyItemChanged(i, Posters.PAYLOAD_STATE);
                    }
                }
            }
        }
    }

    private void applyOptimisticWatchedToggle(int sourceRowId, Item target, boolean newWatched) {
        if (target == null || !isAdded() || root == null) return;
        long now = System.currentTimeMillis();
        for (String k : matchKeysForItem(target)) {
            optimisticWatchedState.put(k, newWatched);
            optimisticRemovedContinueAt.put(k, now);
        }
        if (target.value instanceof Video v) {
            CinemaMediaTime.clearResume(v.getId(), v.getFilePath(), v.getFileUri());
            CinemaMediaTime.rememberState(v.getId(), v.getFileUri(), v.getDurationMs(),
                    newWatched ? CinemaPlaybackRouter.BOOKMARK_COMPLETED : 0L);
        }
        RecyclerView cwList = lists.get(1);
        List<Item> cwItems = data.get(1);
        if (cwItems != null && !cwItems.isEmpty()) {
            int focusIdx = -1;
            for (int i = 0; i < cwItems.size(); i++) {
                if (itemsReferToSameMedia(cwItems.get(i), target)) {
                    focusIdx = i;
                    break;
                }
            }
            List<Item> nextCw = new ArrayList<>(cwItems.size());
            for (Item it : cwItems) {
                if (!itemsReferToSameMedia(it, target)) nextCw.add(it);
            }
            if (nextCw.size() != cwItems.size()) {
                data.put(1, nextCw);
                if (cwList != null && cwList.getAdapter() instanceof Posters cwPosters) {
                    cwPosters.replace(nextCw);
                    updateVisibility();
                    if (sourceRowId == 1) {
                        final int targetPos = Math.max(0, focusIdx);
                        if (!nextCw.isEmpty()) {
                            focusPoster(cwList, Math.min(targetPos, nextCw.size() - 1));
                        } else if (!focusAdjacentSection(HomePreferences.KEY_CONTINUE, 1, 0)) {
                            focusFirstVisibleSection();
                        }
                    }
                }
            }
        }
        for (Map.Entry<Integer, RecyclerView> entry : lists.entrySet()) {
            if (entry.getKey() == 1) continue;
            RecyclerView rv = entry.getValue();
            if (rv != null && rv.getAdapter() instanceof Posters adapter) {
                List<Item> snap = adapter.snapshot();
                for (int i = 0; i < snap.size(); i++) {
                    Item rowItem = snap.get(i);
                    if (itemsReferToSameMedia(rowItem, target)) {
                        rowItem.cachedWatched = newWatched;
                        rowItem.cachedProgressWidth = 0;
                        adapter.notifyItemChanged(i, Posters.PAYLOAD_STATE);
                    }
                }
            }
        }
    }
    private void expand(boolean open) {
        if(expanded==open||rail==null) return;
        expanded=open; shade.setVisibility(View.VISIBLE);shade.animate().alpha(open?1:0).setDuration(motion(requireContext())?CinemaTokens.PANEL_MS:0).withEndAction(()->{if(!expanded)shade.setVisibility(View.GONE);}).start();
        View railScroll=(View)rail.getParent();
        railScroll.setBackground(rounded(open?CinemaTokens.DRAWER:CinemaTokens.RAIL_BACKGROUND,
                dp(requireContext(),CinemaTokens.CONTROL_RADIUS),0));
        if(railAnimation!=null) railAnimation.cancel();
        railAnimation=ValueAnimator.ofInt(railScroll.getWidth(),dp(requireContext(),open?CinemaTokens.RAIL_OPEN:CinemaTokens.RAIL_CLOSED));
        railAnimation.setDuration(motion(requireContext())?CinemaTokens.PANEL_MS:0);
        railAnimation.addUpdateListener(a->{ViewGroup.LayoutParams lp=railScroll.getLayoutParams();lp.width=(int)a.getAnimatedValue();railScroll.setLayoutParams(lp);});
        railAnimation.start();
        for(int i=0;i<rail.getChildCount();i++) {
            TextView t=(TextView)rail.getChildAt(i); String[] labels=(String[])t.getTag();
            navigationLabel(t,labels[1],open);
        }
    }
    private TextView nav(String icon,String name,Runnable action,Runnable longAction) {
        Context c=requireContext(); TextView t=text(c,15,false);
        t.setId(View.generateViewId());
        t.setTag(new String[]{icon,name}); t.setText(expanded?name:""); t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true); t.setPadding(dp(c,10),0,dp(c,8),0); iconOnly(t);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(c,4);rail.addView(t,p);
        t.setMinimumHeight(dp(c,CinemaTokens.RAIL_ROW_HEIGHT));
        int image=switch(icon){case "⚙"->com.archos.mediacenter.video.R.drawable.ic_cog_outline;case "⌂"->com.archos.mediacenter.video.R.drawable.cinema_home;case "⌕"->com.archos.mediacenter.video.R.drawable.cinema_search;case "♡"->com.archos.mediacenter.video.R.drawable.cinema_watchlist;case "✓"->com.archos.mediacenter.video.R.drawable.cinema_check;case "⟳"->com.archos.mediacenter.video.R.drawable.ic_menu_refresh;case "▯"->com.archos.mediacenter.video.R.drawable.ic_tablet_cellphone;case "▤"->com.archos.mediacenter.video.R.drawable.cinema_network;default->com.archos.mediacenter.video.R.drawable.cinema_folder;};
        CinemaUi.icon(t,image);navigationLabel(t,name,expanded);
        t.setContentDescription(name);t.setOnClickListener(v->action.run());
        if(longAction!=null){t.setLongClickable(true);t.setOnLongClickListener(v->{longAction.run();return true;});}
        t.setOnKeyListener((v,k,e)->{
            k=logicalKey(v,k);
            if(CinemaNavigation.moveVertically(rail,v,k,e))return true;
            if(e.getAction()==KeyEvent.ACTION_DOWN&&k==KeyEvent.KEYCODE_DPAD_RIGHT&&"▣".equals(icon))return t.performClick();
            if(k==KeyEvent.KEYCODE_DPAD_RIGHT){
                expand(false);
                return focusContent();
            }
            if(k==KeyEvent.KEYCODE_BACK){
                if(e.getAction()==KeyEvent.ACTION_UP){
                    expand(false);
                    focusContent();
                }
                return true;
            }
            if(k==KeyEvent.KEYCODE_MENU&&e.getAction()==KeyEvent.ACTION_DOWN&&longAction!=null){longAction.run();return true;}
            return false;
        });
        return t;
    }
    private void syncTraktFromSidebar() {
        Context c = getContext();
        if (c == null) return;
        Context app = c.getApplicationContext();
        if (!com.meedx.providers.TraktSyncClient.isConnected(app)) {
            Toast.makeText(c, label(c, "اربط حساب Trakt من الهاتف أو الإعدادات أولاً",
                    "Connect your Trakt account in Phone TV Hub or Settings first"), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(c, label(c, "جارٍ تحديث ومزامنة Trakt...", "Syncing Trakt database..."), Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String statusMsg;
            boolean ok = false;
            try {
                int count = CinemaWatchStore.syncWithTrakt(app);
                ok = true;
                statusMsg = label(app, "تمت مزامنة Trakt: ", "Trakt sync complete: ") + count;
            } catch (Exception e) {
                statusMsg = label(app, "فشلت مزامنة Trakt: ", "Trakt sync failed: ") + e.getMessage();
            }
            String finalMsg = statusMsg;
            boolean refreshUi = ok;
            handler.post(() -> {
                if (!isAdded() || root == null) return;
                Toast.makeText(requireContext(), finalMsg, Toast.LENGTH_SHORT).show();
                if (refreshUi) {
                    LoaderManager.getInstance(this).restartLoader(1, null, this);
                    LoaderManager.getInstance(this).restartLoader(2, null, this);
                    LoaderManager.getInstance(this).restartLoader(3, null, this);
                    LoaderManager.getInstance(this).restartLoader(4, null, this);
                    renderRows();
                }
            });
        });
    }

    public static void triggerDirectScan(Context context) {
        if(context==null)return;
        CinemaLibraryScan.start(context);
        Toast.makeText(context,context.getString(com.archos.mediacenter.video.R.string.cinema_movie_files_scan_started),Toast.LENGTH_SHORT).show();
    }
    private static final java.util.Set<String> KNOWN_SECTION_NAMES = java.util.Set.of(
            "movies", "films", "shows", "tv shows", "series", "cartoon", "cartoons",
            "anime", "animation", "kids", "others", "other", "documentaries", "documentary",
            "sports", "family", "arabic", "english", "أفلام", "افلام", "مسلسلات", "كرتون", "كارتون", "أنمي", "انمي", "أخرى"
    );

    private static String extractRelativeAfterPrefix(String dataPath, String prefix, String decodedPrefix) {
        if (dataPath == null) return null;
        if (prefix != null && dataPath.length() > prefix.length()
                && dataPath.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return dataPath.substring(prefix.length());
        }
        if (decodedPrefix != null && dataPath.length() > decodedPrefix.length()
                && dataPath.regionMatches(true, 0, decodedPrefix, 0, decodedPrefix.length())) {
            return dataPath.substring(decodedPrefix.length());
        }
        String decodedData = Uri.decode(dataPath);
        if (decodedData != null) {
            if (decodedPrefix != null && decodedData.length() > decodedPrefix.length()
                    && decodedData.regionMatches(true, 0, decodedPrefix, 0, decodedPrefix.length())) {
                return decodedData.substring(decodedPrefix.length());
            }
            if (prefix != null && decodedData.length() > prefix.length()
                    && decodedData.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return decodedData.substring(prefix.length());
            }
        }
        return null;
    }

    private static List<LibraryRow> resolveDynamicLibrarySections(Context c, List<NetworkShortcut> sources) {
        StringBuilder sig = new StringBuilder();
        for (NetworkShortcut s : sources) {
            sig.append(s.getId()).append('|').append(s.getName()).append('|').append(s.getUri()).append(';');
        }
        try (Cursor cntCursor = c.getContentResolver().query(
                VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{"COUNT(*)", "MAX(_id)", "MAX(" + VideoStore.MediaColumns.DATE_MODIFIED + ")"},
                VideoStore.Video.VideoColumns.ARCHOS_HIDE_FILE + "=0",
                null, null)) {
            if (cntCursor != null && cntCursor.moveToFirst()) {
                sig.append("#").append(cntCursor.getLong(0))
                        .append(":").append(cntCursor.getLong(1))
                        .append(":").append(cntCursor.getLong(2));
            }
        } catch (Exception ignored) {}
        String currentSig = sig.toString();
        if (!currentSig.isEmpty() && currentSig.equals(cachedSectionsSignature)) {
            return cachedDynamicSections;
        }
        List<LibraryRow> resolved = new ArrayList<>();
        Map<String, List<String>> groupedPrefixes = new java.util.LinkedHashMap<>();
        Map<String, Integer> groupedCategory = new java.util.LinkedHashMap<>();
        Map<String, String> groupedDisplayName = new java.util.LinkedHashMap<>();
        Map<String, Boolean> groupedMobile = new java.util.LinkedHashMap<>();

        for (NetworkShortcut source : sources) {
            String prefix = libraryPrefix(c, source.getUri());
            if (prefix == null) continue;
            if (isMobileLibraryShortcut(source)) {
                String raw = source.getName() == null ? "Folder" : source.getName().trim();
                String groupKey = "mobile:" + raw.toLowerCase(Locale.ROOT) + ":" + prefix;
                groupedPrefixes.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(prefix);
                groupedCategory.putIfAbsent(groupKey, CinemaHomeLoader.CAT_DEFAULT);
                groupedDisplayName.putIfAbsent(groupKey, raw);
                groupedMobile.putIfAbsent(groupKey, true);
                continue;
            }
            String decodedPrefix = Uri.decode(prefix);
            class SubFolderStats {
                final String displayName;
                final java.util.LinkedHashSet<String> prefixes = new java.util.LinkedHashSet<>();
                final java.util.Set<String> distinctTitles = new java.util.HashSet<>();
                SubFolderStats(String displayName) { this.displayName = displayName; }
            }
            Map<String, SubFolderStats> subfolders = new java.util.LinkedHashMap<>();
            boolean hasDirectRootFiles = false;
            String esc1 = prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            String esc2 = decodedPrefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            try (Cursor vc = c.getContentResolver().query(
                    VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{VideoStore.MediaColumns.DATA, VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID, VideoStore.MediaColumns.TITLE},
                    VideoStore.Video.VideoColumns.ARCHOS_HIDE_FILE + "=0 AND ("
                            + VideoStore.MediaColumns.DATA + " LIKE ? ESCAPE '!' OR "
                            + VideoStore.MediaColumns.DATA + " LIKE ? ESCAPE '!')",
                    new String[]{esc1, esc2},
                    VideoStore.MediaColumns.DATA + " ASC")) {
                if (vc != null) {
                    while (vc.moveToNext()) {
                        String dataPath = vc.getString(0);
                        long showId = vc.getLong(1);
                        String title = vc.getString(2);
                        String rel = extractRelativeAfterPrefix(dataPath, prefix, decodedPrefix);
                        if (rel == null || rel.isEmpty()) continue;
                        int slash = rel.indexOf('/');
                        if (slash <= 0) {
                            hasDirectRootFiles = true;
                            continue;
                        }
                        String rawSub = rel.substring(0, slash);
                        String decodedSub = Uri.decode(rawSub);
                        if (decodedSub == null || decodedSub.isBlank() || decodedSub.startsWith(".")) continue;
                        String subKey = decodedSub.toLowerCase(Locale.ROOT);
                        SubFolderStats stats = subfolders.computeIfAbsent(subKey, k -> new SubFolderStats(decodedSub));
                        int prefixEnd = dataPath.length() - rel.length() + slash + 1;
                        if (prefixEnd > 0 && prefixEnd <= dataPath.length()) {
                            stats.prefixes.add(dataPath.substring(0, prefixEnd));
                        }
                        stats.prefixes.add(prefix + rawSub + "/");
                        String titleKey;
                        if (showId > 0) {
                            titleKey = "s:" + showId;
                        } else if (isSeriesOrEpisodeFile(null, dataPath)) {
                            var stub = new com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo(
                                    Uri.parse(dataPath), Uri.parse(dataPath), title == null ? decodedSub : title, null);
                            String q = CinemaPosters.query(stub, true).title().toLowerCase(Locale.ROOT);
                            titleKey = "t:" + (q.isEmpty() ? decodedSub.toLowerCase(Locale.ROOT) : q);
                        } else {
                            String rem = rel.substring(slash + 1);
                            int nextSlash = rem.indexOf('/');
                            String unit = nextSlash > 0 ? rem.substring(0, nextSlash) : rem;
                            titleKey = "f:" + unit.toLowerCase(Locale.ROOT);
                        }
                        stats.distinctTitles.add(titleKey);
                    }
                }
            } catch (Exception ignored) {}

            boolean hasNamedSection = false;
            for (var entry : subfolders.entrySet()) {
                if (KNOWN_SECTION_NAMES.contains(entry.getKey())) hasNamedSection = true;
            }
            Uri srcUri = source.getUri();
            boolean isShareRoot = srcUri != null && srcUri.getPathSegments().size() <= 1;
            boolean expandSubfolders = !subfolders.isEmpty()
                    && (isShareRoot || (subfolders.size() >= 2 && hasNamedSection));

            if (expandSubfolders) {
                List<SubFolderStats> orderedSubs = new ArrayList<>(subfolders.values());
                orderedSubs.sort((a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
                for (SubFolderStats sub : orderedSubs) {
                    String folderName = sub.displayName == null ? "" : sub.displayName.trim();
                    if (folderName.isEmpty()) continue;
                    String firstSubPrefix = sub.prefixes.iterator().next();
                    int cat = classifyLibraryCategory(folderName, firstSubPrefix);
                    String groupKey = "sec:" + folderName.trim().toLowerCase(Locale.ROOT);
                    List<String> list = groupedPrefixes.computeIfAbsent(groupKey, k -> new ArrayList<>());
                    for (String pfx : sub.prefixes) {
                        if (!list.contains(pfx)) list.add(pfx);
                    }
                    groupedCategory.putIfAbsent(groupKey, cat);
                    groupedDisplayName.putIfAbsent(groupKey, folderName);
                    groupedMobile.putIfAbsent(groupKey, false);
                }
                if (hasDirectRootFiles) {
                    String rawName = source.getName() == null || source.getName().isBlank()
                            ? FileUtilsNameFallback(source.getUri()) : source.getName().trim();
                    String groupKey = "sec:" + rawName.trim().toLowerCase(Locale.ROOT);
                    List<String> list = groupedPrefixes.computeIfAbsent(groupKey, k -> new ArrayList<>());
                    if (!list.contains(prefix)) list.add(prefix);
                    groupedCategory.putIfAbsent(groupKey, classifyLibraryCategory(rawName, prefix));
                    groupedDisplayName.putIfAbsent(groupKey, rawName);
                    groupedMobile.putIfAbsent(groupKey, false);
                }
            } else {
                String rawName = source.getName() == null || source.getName().isBlank()
                        ? FileUtilsNameFallback(source.getUri()) : source.getName().trim();
                int cat = classifyLibraryCategory(rawName, prefix);
                String groupKey = "sec:" + rawName.trim().toLowerCase(Locale.ROOT);
                groupedPrefixes.computeIfAbsent(groupKey, k -> new ArrayList<>());
                if (!groupedPrefixes.get(groupKey).contains(prefix)) {
                    groupedPrefixes.get(groupKey).add(prefix);
                }
                groupedCategory.putIfAbsent(groupKey, cat);
                groupedDisplayName.putIfAbsent(groupKey, rawName);
                groupedMobile.putIfAbsent(groupKey, false);
            }
        }

        List<String> orderedKeys = new ArrayList<>(groupedPrefixes.keySet());
        orderedKeys.sort((a, b) -> {
            boolean ma = Boolean.TRUE.equals(groupedMobile.get(a));
            boolean mb = Boolean.TRUE.equals(groupedMobile.get(b));
            if (ma != mb) return ma ? 1 : -1;
            String na = groupedDisplayName.getOrDefault(a, "");
            String nb = groupedDisplayName.getOrDefault(b, "");
            return na.compareToIgnoreCase(nb);
        });
        for (String groupKey : orderedKeys) {
            resolved.add(new LibraryRow(
                    groupedDisplayName.getOrDefault(groupKey, ""),
                    List.copyOf(groupedPrefixes.get(groupKey)),
                    groupedCategory.getOrDefault(groupKey, CinemaHomeLoader.CAT_DEFAULT),
                    Boolean.TRUE.equals(groupedMobile.get(groupKey))
            ));
        }
        cachedDynamicSections = List.copyOf(resolved);
        cachedSectionsSignature = currentSig;
        return resolved;
    }

    private static String FileUtilsNameFallback(Uri uri) {
        if (uri == null) return "Library";
        String seg = uri.getLastPathSegment();
        return seg != null && !seg.isBlank() ? seg : "Library";
    }

    private void loadLibraries() {
        Context c=requireContext().getApplicationContext(); int token=++generation;
        io.execute(()->{
            ShortcutDbAdapter.VIDEO.cleanupDuplicateShortcuts(c);
            List<NetworkShortcut> sources=new ArrayList<>();
            try(Cursor cursor=ShortcutDbAdapter.VIDEO.queryAllShortcuts(c)) {
                if(cursor!=null){NetworkShortcutMapper mapper=new NetworkShortcutMapper();mapper.bindColumns(cursor);
                    while(cursor.moveToNext())sources.add((NetworkShortcut)mapper.bind(cursor));}
            }
            catch(Exception ignored){/* Keep core navigation usable if shortcut storage is temporarily busy. */}
            sources.removeIf(source -> !CinemaLibraryScan.isAvailable(source.getUri()));
            List<LibraryRow> resolvedSections = resolveDynamicLibrarySections(c, sources);
            StringBuilder sigBuilder = new StringBuilder(getResources().getConfiguration().locale.toString())
                    .append(":").append(MetadataLanguage.resolve(c));
            for (NetworkShortcut s : sources) {
                sigBuilder.append("|").append(s.getId()).append(":").append(s.getName()).append(":").append(s.getUri());
            }
            final String railSig = sigBuilder.toString();
            handler.post(()->{
                if(root==null||!isAdded()||token!=generation)return;
                closeLibraries();
                if(!railBuilt || !railSig.equals(lastRailSignature) || rail.getChildCount()==0){
                    View focused=requireActivity().getCurrentFocus();
                    String[] restoreNav=focused!=null&&isInRail(focused)&&focused.getTag() instanceof String[]?(String[])focused.getTag():null;
                    rail.removeAllViews();
                    nav("⚙",c.getString(com.archos.mediacenter.video.R.string.cinema_settings),()->startActivity(new Intent(requireContext(),CinemaSettingsActivity.class)),null);

                    nav("⌂",c.getString(com.archos.mediacenter.video.R.string.cinema_home),this::returnToHomeTop,null);
                    searchTile=nav("⌕",c.getString(com.archos.mediacenter.video.R.string.cinema_search),()->{},null);
                    searchTile.setOnClickListener(v->showSearchMenu(v));
                    nav("♡",c.getString(com.archos.mediacenter.video.R.string.cinema_watchlist),()->startActivity(new Intent(requireContext(),CinemaWatchlistActivity.class)),null);
                    nav("✓",c.getString(com.archos.mediacenter.video.R.string.cinema_watched),()->startActivity(new Intent(requireContext(),CinemaWatchlistActivity.class).putExtra(CinemaWatchlistActivity.EXTRA_WATCHED,true)),null);
                    nav("⟳",label(c,"مزامنة Trakt","Sync Trakt"),this::syncTraktFromSidebar,null);
                    com.meedx.providers.OpenTrailersProvider.prefetchDailyIfNeeded(c, MetadataLanguage.resolve(c).startsWith("ar"));

                    librariesTile=nav("▣",c.getString(com.archos.mediacenter.video.R.string.cinema_libraries),()->{},null);
                    librariesTile.setOnClickListener(v->showLibraries(v,sources));
                    nav("▯",c.getString(com.archos.mediacenter.video.R.string.cinema_link_phone),()->startActivity(new Intent(requireContext(),CinemaPhoneLinkActivity.class)),null);
                    lastRailSignature = railSig;
                    railBuilt = true;
                    if(restoreNav!=null){
                        for(int n=0;n<rail.getChildCount();n++){String[] tag=(String[])rail.getChildAt(n).getTag();if(tag[0].equals(restoreNav[0])){rail.getChildAt(n).requestFocus();break;}}
                    }
                } else if (librariesTile != null) {
                    librariesTile.setOnClickListener(v->showLibraries(v,sources));
                }
                librariesLoaded = true;
                lastLoadedLibraryEpoch = CinemaLibraryScan.stateEpoch();
                libraryCount=sources.size();updateLibraryRows(resolvedSections);updateVisibility();
            });
        });
    }
    private static int classifyLibraryCategory(String name, String prefix) {
        String combined = ((name == null ? "" : name) + " " + (prefix == null ? "" : Uri.decode(prefix))).toLowerCase(Locale.ROOT);
        String n = (name == null ? "" : name).trim().toLowerCase(Locale.ROOT);
        if (n.matches(".*(cartoon|anime|animation|kids|كارتون|كرتون|أنمي|انمي).*")
                || combined.matches(".*[/\\\\](cartoons?|anime|animation|kids|الكارتون|كرتون|أنمي|انمي)[/\\\\]?.*")) {
            return CinemaHomeLoader.CAT_CARTOON;
        }
        if (n.matches(".*(show|series|tv|مسلسل|حلقات).*")
                || combined.matches(".*[/\\\\](tv\\s*shows?|series|shows|المسلسلات|مسلسلات)[/\\\\]?.*")) {
            return CinemaHomeLoader.CAT_SHOWS;
        }
        if (n.matches(".*(movie|film|أفلام|افلام|سينما).*")
                || combined.matches(".*[/\\\\](movies?|films?|الأفلام|افلام)[/\\\\]?.*")) {
            return CinemaHomeLoader.CAT_MOVIES;
        }
        return CinemaHomeLoader.CAT_DEFAULT;
    }

    private String canonicalLibraryDisplayName(Context c, String rawName, int category) {
        boolean ar = MetadataLanguage.resolve(c).startsWith("ar");
        return switch (category) {
            case CinemaHomeLoader.CAT_MOVIES -> ar ? "الأفلام" : "Movies";
            case CinemaHomeLoader.CAT_SHOWS -> ar ? "المسلسلات" : "Shows";
            case CinemaHomeLoader.CAT_CARTOON -> ar ? "الكارتون" : "Cartoon";
            default -> rawName;
        };
    }

    private static boolean isMobileLibraryShortcut(NetworkShortcut source) {
        if (source == null) return false;
        String n = source.getName() == null ? "" : source.getName().trim();
        if (n.startsWith("Mobile (") || n.startsWith("Mobile USB (")) return true;
        Uri u = source.getUri();
        return u != null && "webdav".equalsIgnoreCase(u.getScheme())
                && u.getPath() != null && u.getPath().startsWith("/webdav/");
    }

    private void updateLibraryRows(List<LibraryRow> sections){
        Map<Integer,LibraryRow> updated=new java.util.LinkedHashMap<>();
        for (LibraryRow row : sections) {
            int id = 0;
            for (var previous : libraryRows.entrySet()) {
                if (previous.getValue().prefixes().equals(row.prefixes())
                        && previous.getValue().category() == row.category()
                        && previous.getValue().name().equals(row.name())) {
                    id = previous.getKey();
                    break;
                }
            }
            if (id == 0) id = nextLibraryRow++;
            updated.put(id, row);
        }
        if(updated.equals(libraryRows)){
            List<String> knownNames = new ArrayList<>();
            boolean hasNonMobile = false;
            for (LibraryRow r : libraryRows.values()) {
                knownNames.add(r.name());
                if (!r.mobile()) hasNonMobile = true;
            }
            HomePreferences.saveKnownLibrarySections(prefs, knownNames, hasNonMobile);
            return;
        }
        java.util.Set<Integer> removed=new java.util.HashSet<>(libraryRows.keySet());
        removed.removeAll(updated.keySet());
        java.util.Set<Integer> added=new java.util.HashSet<>(updated.keySet());
        added.removeAll(libraryRows.keySet());
        libraryRows.clear();
        libraryRows.putAll(updated);
        List<String> knownNames = new ArrayList<>();
        boolean hasNonMobile = false;
        for (LibraryRow r : libraryRows.values()) {
            knownNames.add(r.name());
            if (!r.mobile()) hasNonMobile = true;
        }
        HomePreferences.saveKnownLibrarySections(prefs, knownNames, hasNonMobile);
        for(int id:removed){
            LoaderManager.getInstance(this).destroyLoader(id);
            data.remove(id);
        }
        renderRows();
        for(int id:added)LoaderManager.getInstance(this).initLoader(id,null,this);
    }
    static String libraryPrefix(Context context,Uri source){
        if(source==null)return null;
        if("content".equals(source.getScheme())){
            try{
                Uri document=android.provider.DocumentsContract.isDocumentUri(context,source)
                        ?source
                        :com.archos.filecorelibrary.contentstorage.DocumentUriBuilder.buildDocumentUriUsingTree(source);
                String documentId=android.provider.DocumentsContract.getDocumentId(document);
                return document+(documentId.endsWith(":")?"":"%2F");
            }catch(IllegalArgumentException ignored){return null;}
        }
        String path=com.archos.mediacenter.video.utils.VideoUtils.getMediaLibCompatibleFilepathFromUri(source);
        if(path==null||path.isBlank())return null;
        return path.endsWith("/")?path:path+"/";
    }

    private void openSource(NetworkShortcut s) {
        if (s == null || s.getUri() == null) return;
        Intent i = new Intent(requireContext(), CinemaNetworkActivity.class)
                .putExtra(CinemaNetworkActivity.EXTRA_BROWSE_URI, s.getUri().toString())
                .putExtra(CinemaNetworkActivity.EXTRA_BROWSE_NAME, s.getName());
        startActivity(i);
    }
    private void closeLibraries(){
        if (root != null) CinemaActionMenu.dismiss(root, false);
    }

    private void showSearchMenu(View anchor) {
        if (!isAdded() || root == null || getActivity() == null) return;
        expand(true);
        View railContainer = rail != null && rail.getParent() instanceof View pv ? pv : rail;
        CinemaActionMenu.showSearchSidebarPanel(
                requireActivity(), root, anchor, railContainer,
                () -> { sidebarSubmenuOpen = true; expand(true); },
                () -> sidebarSubmenuOpen = false);
    }

    private void showLibraries(View anchor,List<NetworkShortcut> sources){
        if (!isAdded() || root == null) return;
        Context context = requireContext();
        expand(true);
        View railContainer = rail != null && rail.getParent() instanceof View pv ? pv : rail;
        List<CinemaActionMenu.Action> actions = new ArrayList<>();
        actions.add(new CinemaActionMenu.Action(
                com.archos.mediacenter.video.R.drawable.ic_menu_refresh,
                context.getString(com.archos.mediacenter.video.R.string.cinema_scan_all),
                label(context, "فحص وتحديث جميع مجلدات المكتبة الآن", "Scan and refresh all library folders now"),
                () -> triggerDirectScan(context)));
        for (NetworkShortcut source : sources) {
            boolean mobile = isMobileLibraryShortcut(source);
            int folderIcon = mobile
                    ? com.archos.mediacenter.video.R.drawable.ic_tablet_cellphone
                    : com.archos.mediacenter.video.R.drawable.cinema_folder;
            String hint = label(context,
                    "اضغط للفتح · ضغطة مطوّلة أو زر القائمة للفحص أو الإزالة",
                    "Press OK to open · Hold or Menu to scan or remove");
            actions.add(new CinemaActionMenu.Action(
                    folderIcon,
                    source.getName(),
                    hint,
                    () -> openSource(source),
                    () -> sourceMenu(source)));
        }
        actions.add(new CinemaActionMenu.Action(
                com.archos.mediacenter.video.R.drawable.cinema_network,
                context.getString(com.archos.mediacenter.video.R.string.cinema_manage_library_sources),
                label(context, "إضافة أو تعديل مجلدات SMB والتخزين والشبكة", "Add or manage SMB, network & storage folders"),
                () -> startActivity(new Intent(context, CinemaNetworkActivity.class))));
        actions.add(new CinemaActionMenu.Action(
                com.archos.mediacenter.video.R.drawable.cinema_folder,
                label(context, "تصفح ملفات التخزين وUSB", "Browse Local & USB Files"),
                label(context, "فتح متصفح الملفات الموحّد للفيديوهات والترجمات", "Open unified file browser for videos & subtitles"),
                () -> startActivity(new Intent(context, CinemaFileBrowserActivity.class))));
        CinemaActionMenu.showSidebarPanel(
                root, anchor, railContainer,
                com.archos.mediacenter.video.R.drawable.cinema_folder,
                label(context, "فحص المكتبات", "Libraries & Scan"),
                () -> { sidebarSubmenuOpen = true; expand(true); },
                () -> sidebarSubmenuOpen = false,
                actions);
    }

    private void sourceMenu(NetworkShortcut s) {
        Context c=requireContext();
        CinemaUi.dialog(c).setTitle(s.getName()).setItems(new String[]{c.getString(com.archos.mediacenter.video.R.string.cinema_open_library),c.getString(com.archos.mediacenter.video.R.string.cinema_scan),c.getString(com.archos.mediacenter.video.R.string.cinema_remove_library)},(d,which)->{
            if(which==0)openSource(s);
            if(which==1){CinemaLibraryScan.start(c,s.getUri());Toast.makeText(c,c.getString(com.archos.mediacenter.video.R.string.cinema_library_refresh_started),Toast.LENGTH_SHORT).show();}
            if(which==2)CinemaUi.dialog(c).setTitle(c.getString(com.archos.mediacenter.video.R.string.cinema_remove_library_source))
                    .setMessage(c.getString(com.archos.mediacenter.video.R.string.cinema_only_the_app_library_source_will_be_removed_video_files_and_the_network_folder_will_be_preserved))
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(dialog,w)->io.execute(()->{
                        if(ShortcutDbAdapter.VIDEO.deleteShortcut(c.getApplicationContext(),s.getId()))NetworkScanner.removeVideos(c.getApplicationContext(),s.getUri());
                        handler.post(()->{if(isAdded())loadLibraries();});
                    })).show();
        }).show().setOnDismissListener(dialog->{
            if(root!=null&&isResumed()){
                for(int n=0;n<rail.getChildCount();n++){
                    View item=rail.getChildAt(n);
                    if(item.getTag() instanceof String[] tag&&"▣".equals(tag[0])){
                        item.requestFocus();
                        break;
                    }
                }
            }
        });
    }
    private final android.content.BroadcastReceiver scanReceiver=new android.content.BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            CinemaLibraryScan.bumpStateEpoch();
            if(!isAdded()||root==null)return;
            restartActiveLoaders();
            loadLibraries();
        }
    };
    private final android.content.BroadcastReceiver mobileLibraries = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (root == null) return;
            if (CinemaLinkServer.ACTION_REMOTE_STATUS_UPDATED.equals(intent.getAction())
                    || CinemaLinkServer.ACTION_ADDONS_CHANGED.equals(intent.getAction())) {
                refreshTileStatusDots();
                return;
            }
            if (CinemaLinkServer.ACTION_WATCH_SYNC_UPDATED.equals(intent.getAction())) {
                restartActiveLoaders();
                return;
            }
            CinemaLibraryScan.bumpStateEpoch();
            loadLibraries();
        }
    };

    private long computeWatchEpoch() {
        return (CinemaMediaTime.stateEpoch() * 31L) ^ CinemaWatchStore.stateEpoch();
    }

    @Override public void onResume() {
        super.onResume();
        var libraryChanges = new android.content.IntentFilter(CinemaLinkServer.MOBILE_LIBRARY_CHANGED);
        libraryChanges.addAction(CinemaLibraryScan.ACTION_CHANGED);
        libraryChanges.addAction(CinemaLinkServer.ACTION_WATCH_SYNC_UPDATED);
        libraryChanges.addAction(CinemaLinkServer.ACTION_REMOTE_STATUS_UPDATED);
        libraryChanges.addAction(CinemaLinkServer.ACTION_ADDONS_CHANGED);
        androidx.core.content.ContextCompat.registerReceiver(requireContext(), mobileLibraries,
                libraryChanges,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        CinemaLibraryScan.startWatching(requireContext());
        try{
            android.content.IntentFilter filter=new android.content.IntentFilter(com.archos.mediaprovider.ArchosMediaIntent.ACTION_VIDEO_SCANNER_SCAN_FINISHED);
            filter.addDataScheme("smb");filter.addDataScheme("smbj");filter.addDataScheme("file");filter.addDataScheme("content");filter.addDataScheme("webdav");
            androidx.core.content.ContextCompat.registerReceiver(requireContext(),scanReceiver,filter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            android.content.IntentFilter noDataFilter=new android.content.IntentFilter(com.archos.mediaprovider.ArchosMediaIntent.ACTION_VIDEO_SCANNER_SCAN_FINISHED);
            androidx.core.content.ContextCompat.registerReceiver(requireContext(),scanReceiver,noDataFilter,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        }catch(Exception ignored){}
        handler.removeCallbacks(tick);tick.run();
        refreshTileStatusDots();
        handler.removeCallbacks(tileLedTick);
        handler.postDelayed(tileLedTick, 3000L);
        String signature=getResources().getConfiguration().locale.toString()+":"+MetadataLanguage.resolve(requireContext())+":"+HomePreferences.layoutSignature(prefs);
        if(!signature.equals(layoutSignature)){layoutSignature=signature;heroArt.clear();renderRows();}
        String refresh = prefs.getString(CinemaPosters.REFRESH, "");
        boolean force = "all".equals(refresh);
        if (!refresh.isEmpty()) {
            prefs.edit().remove(CinemaPosters.REFRESH).apply();
            android.util.Log.i(CinemaPosters.TAG, "Refresh requested: " + refresh);
            if (force) {
                Context app = requireContext().getApplicationContext();
                CinemaPosters.QUEUE.execute(() -> {
                    File[] files = new File(app.getFilesDir(), "posters").listFiles((dir, name) -> name.matches("[a-f0-9]{64}\\.jpg"));
                    if (files != null) for (File file : files) {
                        Picasso.get().invalidate(Uri.fromFile(file));
                        file.delete();
                    }
                });
            }
        }
        if(!contentLoaded || force){
            attemptedPosters.clear();
            failedPosterLoads.clear();
            for (int rowId : rowOrder()) {
                for (Item item : data.getOrDefault(rowId, List.of())) queuePoster(item, force);
            }
            for (List<Item> items : data.values()) for (Item item : items) queuePoster(item, force);
        }
        LoaderManager.getInstance(this).initLoader(1, null, this);
        long currentWatchEpoch = computeWatchEpoch();
        boolean watchChanged = !contentLoaded || videoStoreDirtyWhilePaused || currentWatchEpoch != lastObservedWatchEpoch;
        videoStoreDirtyWhilePaused = false;
        lastObservedWatchEpoch = currentWatchEpoch;
        if (watchChanged) {
            LoaderManager.getInstance(this).restartLoader(1, null, this);
        }
        for(int id:libraryRows.keySet())LoaderManager.getInstance(this).initLoader(id,null,this);
        if (!librariesLoaded || lastLoadedLibraryEpoch != CinemaLibraryScan.stateEpoch()) {
            loadLibraries();
        }
        loadDiscover();
        if (!warmStartupDone) {
            warmStartupDone = true;
            Context appCtx = requireContext().getApplicationContext();
            handler.postDelayed(() -> {
                CinemaMetadataPrefetcher.maybeRunOnStartup(appCtx);
                CinemaCacheMaintenance.maybeRunSmartMaintenanceAsync(appCtx);
            }, 2200L);
            handler.postDelayed(() -> {
                if (isAdded() && getActivity() != null && !getActivity().isFinishing()) {
                    TvPermissionsWizardDialog.maybeShowFirstLaunch(requireActivity());
                }
            }, 650);
        }
        if(returningDiscover!=null&&focusDiscover(returningDiscover))returningDiscover=null;
        if(returningPoster!=null&&contentLoaded){
            PosterLocation location=returningPoster;
            RecyclerView retList = resolveRowForLocation(location);
            if (retList != null && retList.getAdapter() instanceof Posters retAdapter) {
                int pos = retAdapter.find(location.key());
                if (pos >= 0) retAdapter.notifyItemChanged(pos, Posters.PAYLOAD_STATE);
            }
            if (restorePoster(location)) returningPoster = null;
        }
        Intent actIntent = getActivity() != null ? getActivity().getIntent() : null;
        if (actIntent != null && actIntent.getBooleanExtra(EXTRA_RETURN_HOME_TOP, false)) {
            actIntent.removeExtra(EXTRA_RETURN_HOME_TOP);
            rows.post(this::returnToHomeTop);
        }
    }
    @Override public void onPause(){saveHomeFocusState();lastObservedWatchEpoch=computeWatchEpoch();videoStoreDirtyWhilePaused=false;CinemaLibraryScan.stopWatching();requireContext().unregisterReceiver(mobileLibraries);closeLibraries();try{requireContext().unregisterReceiver(scanReceiver);}catch(Exception ignored){}handler.removeCallbacks(tick);handler.removeCallbacks(tileLedTick);handler.removeCallbacks(refreshDiscover);super.onPause();}
    @Override public void onDestroyView(){if(videoStoreObserver!=null){try{requireContext().getApplicationContext().getContentResolver().unregisterContentObserver(videoStoreObserver);}catch(Exception ignored){}videoStoreObserver=null;}closeLibraries();generation++;viewGeneration++;rowLoadTokens.clear();pending=null;handler.removeCallbacksAndMessages(null);if(artworkTask!=null)artworkTask.cancel(true);for(java.util.concurrent.Future<?> task:posterTasks.values())task.cancel(true);posterTasks.clear();if(railAnimation!=null)railAnimation.cancel();Picasso.get().cancelRequest(backdrop);Picasso.get().cancelRequest(logo);tileNetworkLed=null;tileQrLed=null;tileRemoteLed=null;tileAddonsLed=null;otherRemoteCard=null;otherDescription=null;root=null;weatherWidget=null;super.onDestroyView();}
    @Override public void onDestroy(){io.shutdownNow();posterIo.shutdownNow();rowDiffIo.shutdownNow();watchActionIo.shutdownNow();super.onDestroy();}
    private final Map<String, Item> relocatedShows = new java.util.LinkedHashMap<>();

    private int findShowsRowId() {
        for (var e : libraryRows.entrySet()) {
            if (e.getValue().category() == CinemaHomeLoader.CAT_SHOWS) return e.getKey();
        }
        return 4;
    }

    @NonNull @Override public Loader<Cursor> onCreateLoader(int id,Bundle args) {
        LibraryRow source=libraryRows.get(id);
        if(source!=null)return new CinemaHomeLoader(requireContext(),id,source.prefixes(),source.category());
        if(id==4)return new AllTvshowsLoader(requireContext(),"MAX("+VideoStore.MediaColumns.DATE_ADDED+") DESC LIMIT 50",true,true,1500);
        return new CinemaHomeLoader(requireContext(),id);
    }
    private Uri background(Cursor c) {
        int f=c.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_BACKDROP_LARGE_FILE);
        int u=c.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_BACKDROP_LARGE_URL);
        String file=f>=0?c.getString(f):null;
        if(file!=null&&!file.isEmpty())return Uri.fromFile(new File(file));
        String url=u>=0?c.getString(u):null;
        return url==null||url.isEmpty()?null:Uri.parse(url);
    }
    private static Tvshow convertSeriesToTvshow(Base value, long showId, String poster, String plot, int seasons, String filePath) {
        if (value instanceof Tvshow tv) return tv;
        String showName;
        Uri posterUri = null;
        int year = value instanceof Movie m ? m.getYear() : 0;
        if (value instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep) {
            showName = ep.getShowName();
            posterUri = ep.getPosterUri();
        } else {
            CinemaPosters.Query q = CinemaPosters.query(value, true);
            showName = (q.title() != null && !q.title().isBlank()) ? q.title()
                    : (value != null && value.getName() != null ? value.getName() : "");
            if (year <= 0) year = q.year();
            if (value != null) posterUri = value.getPosterUri();
        }
        if (poster != null && !poster.isEmpty()) posterUri = Uri.fromFile(new File(poster));
        long sid = showId > 0 ? showId : 0L;
        return new Tvshow(sid, showName, posterUri, Math.max(1, seasons), 0, 0,
                false, false, plot, null, null, year, -1, "", 0, filePath);
    }

    private boolean isSeriesRowItem(Base value, int rowId) {
        if (value instanceof Tvshow || value instanceof com.archos.mediacenter.video.browser.adapters.object.Episode) return true;
        LibraryRow libRow = libraryRows.get(rowId);
        int cat = libRow != null ? libRow.category() : (rowId == 3 ? CinemaHomeLoader.CAT_MOVIES : (rowId == 4 ? CinemaHomeLoader.CAT_SHOWS : CinemaHomeLoader.CAT_DEFAULT));
        if (cat == CinemaHomeLoader.CAT_SHOWS) return true;
        String fp = value instanceof Video v ? v.getFilePath() : null;
        return isSeriesOrEpisodeFile(value, fp);
    }

    private long lookupShowIdForVideo(Context context, long videoId) {
        if (videoId <= 0 || context == null) return 0L;
        try (Cursor c = context.getContentResolver().query(
                VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{ VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID },
                "_id=?", new String[]{ Long.toString(videoId) }, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {}
        return 0L;
    }

    private record RawCursorEntry(Base value, String bgFile, String bgUrl, long showId,
                                  String poster, String plot, int seasons, long addedTs) {}

    private static Uri resolveBackgroundUri(String file, String url) {
        if (file != null && !file.isEmpty()) return Uri.fromFile(new File(file));
        return (url == null || url.isEmpty()) ? null : Uri.parse(url);
    }

    @Override public void onLoadFinished(@NonNull Loader<Cursor> loader,Cursor cursor) {
        if(root==null || !isAdded())return;
        final int loaderId = loader.getId();
        final int viewToken = viewGeneration;
        final int loadToken = rowLoadTokens.merge(loaderId, 1, Integer::sum);
        final Context app = requireContext().getApplicationContext();
        final String lang = MetadataLanguage.resolve(app);
        final boolean arabicMode = lang.startsWith("ar");
        final boolean uiArabic = CinemaUi.arabic(app);
        final int cardWidth = posterWidth();
        final LibraryRow libRow = libraryRows.get(loaderId);
        final int rowCategory = libRow != null ? libRow.category()
                : (loaderId == 3 ? CinemaHomeLoader.CAT_MOVIES : (loaderId == 4 ? CinemaHomeLoader.CAT_SHOWS : CinemaHomeLoader.CAT_DEFAULT));
        final List<String> indexedPrefixes = new ArrayList<>();
        if (loaderId == 1) {
            for (var lr : libraryRows.values()) indexedPrefixes.addAll(lr.prefixes());
        }

        // Fast main-thread snapshot of Cursor rows (~1ms in RAM) before CursorLoader recycles/closes the cursor.
        final List<RawCursorEntry> rawEntries = new ArrayList<>();
        if (cursor != null && !cursor.isClosed()) {
            cursor.moveToPosition(-1);
            int fCol = cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_BACKDROP_LARGE_FILE);
            int uCol = cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_BACKDROP_LARGE_URL);
            if (loaderId == 4) {
                TvshowCursorMapper mapper = new TvshowCursorMapper();
                mapper.bindColumns(cursor);
                int addedCol = cursor.getColumnIndex(VideoStore.MediaColumns.DATE_ADDED);
                while (cursor.moveToNext()) {
                    Base value = (Base) mapper.bind(cursor);
                    long addedTs = addedCol >= 0 ? cursor.getLong(addedCol) : 0L;
                    String bgFile = fCol >= 0 ? cursor.getString(fCol) : null;
                    String bgUrl = uCol >= 0 ? cursor.getString(uCol) : null;
                    rawEntries.add(new RawCursorEntry(value, bgFile, bgUrl, 0L, null, null, 1, addedTs));
                }
            } else {
                VideoCursorMapper mapper = new VideoCursorMapper();
                mapper.bindColumns(cursor);
                int showCol = cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID);
                int coverCol = cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_S_COVER);
                int plotCol = cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_S_PLOT);
                int seasonsCol = cursor.getColumnIndex("cinema_seasons");
                int cinemaAddedCol = cursor.getColumnIndex("cinema_added");
                int addedCol = cursor.getColumnIndex(VideoStore.MediaColumns.DATE_ADDED);
                while (cursor.moveToNext()) {
                    Base value = (Base) mapper.bind(cursor);
                    long addedTs = cinemaAddedCol >= 0 ? cursor.getLong(cinemaAddedCol)
                            : (addedCol >= 0 ? cursor.getLong(addedCol) : 0L);
                    long showId = showCol >= 0 ? cursor.getLong(showCol) : 0L;
                    String poster = coverCol >= 0 ? cursor.getString(coverCol) : null;
                    String plot = plotCol >= 0 ? cursor.getString(plotCol) : null;
                    int seasons = seasonsCol >= 0 ? cursor.getInt(seasonsCol) : 1;
                    String bgFile = fCol >= 0 ? cursor.getString(fCol) : null;
                    String bgUrl = uCol >= 0 ? cursor.getString(uCol) : null;
                    rawEntries.add(new RawCursorEntry(value, bgFile, bgUrl, showId, poster, plot, seasons, addedTs));
                }
            }
        }

        final RecyclerView currentRv = lists.get(loaderId);
        final List<Item> adapterOldSnapshot = (currentRv != null && currentRv.getAdapter() instanceof Posters p)
                ? p.snapshot() : null;

        rowDiffIo.execute(() -> {
            if (viewGeneration != viewToken || !Integer.valueOf(loadToken).equals(rowLoadTokens.get(loaderId))) {
                return;
            }
            List<Item> items = new ArrayList<>(rawEntries.size());
            Map<String, Item> newlyRelocatedShows = new java.util.LinkedHashMap<>();
            java.util.Set<String> seenKeys = new java.util.HashSet<>();
            java.util.Map<String, Integer> seriesAt = new java.util.HashMap<>();

            if (loaderId == 4) {
                for (RawCursorEntry raw : rawEntries) {
                    Base value = raw.value();
                    String dedupe = CinemaPosters.query(value).title().toLowerCase(Locale.ROOT);
                    if (!dedupe.isEmpty() && seenKeys.add(dedupe)) {
                        items.add(new Item(value, resolveBackgroundUri(raw.bgFile(), raw.bgUrl()), null, raw.addedTs()));
                    }
                }
            } else {
                boolean library = libRow != null;
                for (RawCursorEntry raw : rawEntries) {
                    Base value = raw.value();
                    long addedTs = raw.addedTs();
                    String filePath = value instanceof Video v ? v.getFilePath()
                            : (value instanceof Tvshow s && s.getFileUri() != null ? s.getFileUri().toString() : null);
                    long showId = raw.showId();
                    String poster = raw.poster();
                    String plot = raw.plot();
                    int seasons = raw.seasons();
                    boolean isSeries = !(value instanceof com.archos.mediacenter.video.browser.adapters.object.Movie)
                            && (showId > 0 || isSeriesOrEpisodeFile(value, filePath));
                    boolean isCartoon = isCartoonFile(filePath);
                    if (!library && rowCategory == CinemaHomeLoader.CAT_MOVIES) {
                        if (isCartoon) continue;
                        if (isSeries) {
                            Tvshow showVal = convertSeriesToTvshow(value, showId, poster, plot, seasons, filePath);
                            String showDedupe = (showId > 0 ? "s:" + showId : "t:" + CinemaPosters.query(showVal, true).title().toLowerCase(Locale.ROOT));
                            newlyRelocatedShows.putIfAbsent(showDedupe,
                                    new Item(showVal, resolveBackgroundUri(raw.bgFile(), raw.bgUrl()), null, addedTs));
                            continue;
                        }
                    } else if (rowCategory == CinemaHomeLoader.CAT_SHOWS && isSeries) {
                        value = convertSeriesToTvshow(value, showId, poster, plot, seasons, filePath);
                    } else if (library && isSeries) {
                        value = convertSeriesToTvshow(value, showId, poster, plot, seasons, filePath);
                    }
                    if (loaderId == 1 && value instanceof Video v) {
                        if (!isValidContinuePath(v.getFilePath())
                                || CinemaWatchStore.isEphemeralLiveCast(v.getFilePath(), v.getName(), null, null, 0L)
                                || CinemaWatchStore.isNonLibraryVideoStoreItem(v.getFilePath(), v.getName(), indexedPrefixes)) {
                            io.execute(() -> {
                                try {
                                    com.archos.mediacenter.video.utils.DbUtils.clearResumeOnly(app, v);
                                } catch (Exception ignored) {}
                            });
                            continue;
                        }
                        long dur = CinemaMediaTime.known(v.getId(), v.getFilePath(), v.getDurationMs());
                        long res = CinemaMediaTime.knownResume(v.getId(), v.getFilePath(), v.getResumeMs());
                        if (dur > 0 && res > 0 && res * 100L >= dur * 85L) {
                            CinemaMediaTime.rememberState(v.getId(), v.getFileUri(), dur, CinemaPlaybackRouter.BOOKMARK_COMPLETED);
                            if (autoCompletedContinueIds.add(v.getId())) {
                                final long finishedId = v.getId();
                                io.execute(() -> {
                                    try {
                                        android.content.ContentValues cv = new android.content.ContentValues();
                                        cv.put(VideoStore.Video.VideoColumns.BOOKMARK, CinemaPlaybackRouter.BOOKMARK_COMPLETED);
                                        cv.put(VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN, com.archos.mediacenter.utils.trakt.Trakt.TRAKT_DB_MARKED);
                                        cv.put(VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED, System.currentTimeMillis() / 1000L);
                                        app.getContentResolver().update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, cv,
                                                VideoStore.Video.VideoColumns._ID + "=?", new String[]{Long.toString(finishedId)});
                                    } catch (Exception ignored) {}
                                    handler.post(() -> {
                                        if (isAdded() && root != null) {
                                            LoaderManager.getInstance(CinemaHomeFragment.this).restartLoader(1, null, CinemaHomeFragment.this);
                                        }
                                    });
                                });
                            }
                            continue;
                        }
                        if (res <= 1) continue;
                        long lastPlayedMs = Math.max(0L, v.getLastPlayed()) * 1000L;
                        int[] epNums = isSeries
                                ? (v instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep
                                        ? new int[]{ep.getSeasonNumber(), ep.getEpisodeNumber()}
                                        : CinemaPosters.extractSeasonEpisode(v.getFilePath(), v.getName()))
                                : new int[]{0, 0};
                        long onlineTmdb = v instanceof Movie m ? m.getOnlineId() : 0L;
                        if (CinemaWatchStore.isContinueTombstoned(app, "", "v:" + v.getId(), v.getFilePath(),
                                v.getName(), "", onlineTmdb, epNums[0], epNums[1], lastPlayedMs)) {
                            continue;
                        }
                        Item probeItem = new Item(v, null, null, addedTs);
                        if (isOptimisticallyRemovedFromContinue(probeItem, lastPlayedMs)) continue;
                        String queryTitle = CinemaPosters.query(v, isSeries).title();
                        if (isSeries) {
                            String episodeKey = continueEpisodeKey(queryTitle, epNums[0], epNums[1]);
                            if (!episodeKey.isEmpty()) seenKeys.add(episodeKey);
                        } else {
                            String norm = CinemaWatchStore.normalizeTitle(v.getName());
                            if (!norm.isEmpty()) seenKeys.add("norm:" + norm);
                            if (!queryTitle.isEmpty()) seenKeys.add("q:" + queryTitle.toLowerCase(Locale.ROOT));
                        }
                    }
                    String seriesTitleKey = null;
                    if (library || loaderId == 3) {
                        String dedupe;
                        if (value instanceof Tvshow) {
                            String tKey = CinemaPosters.query(value, true).title().toLowerCase(Locale.ROOT);
                            String looseKey = tKey.replaceAll("\\s*\\((?:19|20)\\d{2}\\)\\s*", " ").replaceAll("[^\\p{L}\\p{N}]+", "");
                            seriesTitleKey = looseKey.isEmpty() ? null : "series:" + looseKey;
                            Integer known = seriesTitleKey == null ? null : seriesAt.get(seriesTitleKey);
                            if (known != null) {
                                Item old = items.get(known);
                                boolean oldScraped = old.value instanceof Tvshow ot && ot.getTvshowId() > 0;
                                if (showId > 0 && !oldScraped) {
                                    items.set(known, new Item(value, resolveBackgroundUri(raw.bgFile(), raw.bgUrl()), null, Math.max(old.dateAdded, addedTs)));
                                }
                                continue;
                            }
                            dedupe = (showId > 0 ? "s:" + showId : "t:" + tKey);
                        } else {
                            dedupe = (showId > 0 ? "s:" + showId : CinemaPosters.query(value, isSeries).title().toLowerCase(Locale.ROOT) + ":" + (value instanceof Movie m ? m.getYear() : 0));
                        }
                        if (!dedupe.isBlank() && !seenKeys.add(dedupe)) continue;
                    }
                    items.add(new Item(value, resolveBackgroundUri(raw.bgFile(), raw.bgUrl()), null, addedTs));
                    if (seriesTitleKey != null) seriesAt.put(seriesTitleKey, items.size() - 1);
                }
            }

            synchronized (relocatedShows) {
                if (!newlyRelocatedShows.isEmpty()) {
                    for (var e : newlyRelocatedShows.entrySet()) {
                        relocatedShows.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
                if (rowCategory == CinemaHomeLoader.CAT_SHOWS && !relocatedShows.isEmpty()) {
                    for (var entry : relocatedShows.entrySet()) {
                        String k = CinemaPosters.query(entry.getValue().value).title().toLowerCase(Locale.ROOT);
                        if (seenKeys.add(entry.getKey()) && (k.isEmpty() || seenKeys.add(k))) {
                            items.add(entry.getValue());
                        }
                    }
                }
            }

            if (loaderId == 1) {
                io.execute(() -> CinemaWatchStore.purgeNonLibraryContinueEntries(app));
                for (var s : CinemaWatchStore.listContinueStreams(app)) {
                    if (!isValidContinuePath(s.streamUri())
                            || CinemaWatchStore.isNonLibraryOrPhoneCast(s.streamKey(), s.streamUri(), s.format(), s.title(), s.mediaType(), s.imdbId(), s.tmdbId())) {
                        continue;
                    }
                    Uri u = Uri.parse(s.streamUri());
                    var identity = CinemaWatchStore.getIdentity(app, s.streamKey());
                    if (identity == null && !s.streamUri().isBlank()) {
                        identity = CinemaWatchStore.getIdentityByUri(app, s.streamUri());
                    }
                    String title = identity != null && !identity.title().isBlank() ? identity.title() : s.title();
                    Uri idPoster = (identity != null && identity.posterUrl() != null && !identity.posterUrl().isBlank())
                            ? Uri.parse(identity.posterUrl()) : null;
                    var v = new com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo(u, u, title, idPoster);
                    Item streamItem = new Item(v, null, s);
                    if (isOptimisticallyRemovedFromContinue(streamItem, s.updatedAt())) continue;
                    if (s.episode() > 0) {
                        String episodeKey = continueEpisodeKey(s.title(), s.season(), s.episode());
                        if (!episodeKey.isEmpty() && !seenKeys.add(episodeKey)) continue;
                    } else {
                        String sNorm = CinemaWatchStore.normalizeTitle(s.title());
                        String sQuery = CinemaPosters.query(v, false).title().toLowerCase(Locale.ROOT);
                        if ((!sNorm.isEmpty() && seenKeys.contains("norm:" + sNorm))
                                || (!sQuery.isEmpty() && seenKeys.contains("q:" + sQuery))) continue;
                        if (!sNorm.isEmpty()) seenKeys.add("norm:" + sNorm);
                        if (!sQuery.isEmpty()) seenKeys.add("q:" + sQuery);
                    }
                    items.add(streamItem);
                }
                for (var ti : CinemaWatchStore.listTraktItems(app, "continue")) {
                    if (("episode".equals(ti.mediaType()) || "show".equals(ti.mediaType()))
                            && !CinemaWatchStore.isEpisodePlayback(ti.season(), ti.episode(), ti.positionMs(), ti.durationMs())) continue;
                    if (ti.durationMs() > 0 && ti.positionMs() * 100L >= ti.durationMs() * 85L) {
                        continue;
                    }
                    String vPath = "trakt://" + ti.listType() + "/" + Uri.encode(ti.itemKey());
                    long synthId = 900_000_000L + (java.util.UUID.nameUUIDFromBytes(
                            vPath.getBytes(java.nio.charset.StandardCharsets.UTF_8)).getMostSignificantBits() & ((1L << 52) - 1));
                    Video v;
                    if ("episode".equals(ti.mediaType()) || "show".equals(ti.mediaType()) || ti.season() > 0 || ti.episode() > 0) {
                        String epLabel = ti.season() > 0 ? String.format(Locale.US, "S%02dE%02d", ti.season(), ti.episode()) : "Trakt";
                        v = new com.archos.mediacenter.video.browser.adapters.object.Episode(
                                synthId, synthId, Math.max(1, ti.season()), Math.max(1, ti.episode()), epLabel,
                                Math.max(1L, ti.positionMs()), 0f, "", "", ti.title(), vPath, null, null, 0, 0,
                                0, 0, false, true, false, false, ti.tmdbId(), ti.updatedAt() / 1000L,
                                0, 0, "", "", "", "", 0, 1, Math.max(1000L, ti.durationMs()));
                    } else {
                        v = new Movie(synthId, vPath, ti.title(), Math.max(0L, ti.tmdbId()), "", ti.year(), 0f, "", null, 0, 0,
                                0, 0, false, true, false, false, ti.tmdbId(), ti.updatedAt() / 1000L,
                                0, 0, "", "", "", "", 0, 1, Math.max(1L, ti.positionMs()), Math.max(1000L, ti.durationMs()));
                    }
                    Item traktItem = new Item(v, null, null, ti.updatedAt() / 1000L, ti);
                    if (isOptimisticallyRemovedFromContinue(traktItem, ti.updatedAt())) continue;
                    if (ti.episode() > 0) {
                        String episodeKey = continueEpisodeKey(ti.title(), ti.season(), ti.episode());
                        if (!episodeKey.isEmpty() && !seenKeys.add(episodeKey)) continue;
                    } else {
                        String sNorm = CinemaWatchStore.normalizeTitle(ti.title());
                        if (!sNorm.isEmpty() && (seenKeys.contains("q:" + sNorm) || !seenKeys.add("norm:" + sNorm))) continue;
                    }
                    CinemaMediaTime.rememberState(synthId, Uri.parse(vPath),
                            Math.max(1000L, ti.durationMs()), Math.max(1L, ti.positionMs()));
                    items.add(traktItem);
                }
            } else if (loaderId == 6) {
                for (var s : CinemaWatchStore.listWatchedStreams(app)) {
                    Uri u = Uri.parse(s.streamUri());
                    var v = new com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo(u, u, s.title(), null);
                    String sNorm = CinemaWatchStore.normalizeTitle(s.title());
                    if (!sNorm.isEmpty()) seenKeys.add("norm:" + sNorm);
                    items.add(new Item(v, null, s));
                }
                for (var ti : CinemaWatchStore.listTraktItems(app, "watched")) {
                    String sNorm = CinemaWatchStore.normalizeTitle(ti.title());
                    if (!sNorm.isEmpty() && !seenKeys.add("norm:" + sNorm)) continue;
                    String vPath = "trakt://" + ti.listType() + "/" + Uri.encode(ti.itemKey());
                    long synthId = 900_000_000L + (java.util.UUID.nameUUIDFromBytes(
                            vPath.getBytes(java.nio.charset.StandardCharsets.UTF_8)).getMostSignificantBits() & ((1L << 52) - 1));
                    Video v;
                    if ("episode".equals(ti.mediaType()) || "show".equals(ti.mediaType()) || ti.season() > 0 || ti.episode() > 0) {
                        String epLabel = ti.season() > 0 ? String.format(Locale.US, "S%02dE%02d", ti.season(), ti.episode()) : "Trakt";
                        v = new com.archos.mediacenter.video.browser.adapters.object.Episode(
                                synthId, synthId, Math.max(1, ti.season()), Math.max(1, ti.episode()), epLabel,
                                CinemaPlaybackRouter.BOOKMARK_COMPLETED, 0f, "", "", ti.title(), vPath, null, null, 0, 0,
                                0, 0, true, true, false, false, ti.tmdbId(), ti.updatedAt() / 1000L,
                                0, 0, "", "", "", "", 0, 1, 0L);
                    } else {
                        v = new Movie(synthId, vPath, ti.title(), Math.max(0L, ti.tmdbId()), "", ti.year(), 0f, "", null, 0, 0,
                                0, 0, true, true, false, false, ti.tmdbId(), ti.updatedAt() / 1000L,
                                0, 0, "", "", "", "", 0, 1, CinemaPlaybackRouter.BOOKMARK_COMPLETED, 0L);
                    }
                    items.add(new Item(v, null, null, ti.updatedAt() / 1000L, ti));
                }
            }

            List<Item> sortedItems = loaderId != 1 ? applyRowSortWithApp(loaderId, items, app, lang, arabicMode) : items;
            for (Item it : sortedItems) {
                populateCachedItemFields(it, app, lang, arabicMode, uiArabic, cardWidth);
            }

            androidx.recyclerview.widget.DiffUtil.DiffResult precomputedDiff = null;
            if (adapterOldSnapshot != null && !adapterOldSnapshot.isEmpty()) {
                for (Item oldIt : adapterOldSnapshot) {
                    if (oldIt.cachedPosterKey == null) {
                        populateCachedItemFields(oldIt, app, lang, arabicMode, uiArabic, cardWidth);
                    }
                }
                precomputedDiff = computePostersDiff(adapterOldSnapshot, sortedItems);
            }

            final List<Item> finalItems = sortedItems;
            final androidx.recyclerview.widget.DiffUtil.DiffResult finalDiff = precomputedDiff;
            final boolean hadRelocated = !newlyRelocatedShows.isEmpty();

            handler.post(() -> {
                if (root == null || !isAdded() || viewGeneration != viewToken
                        || !Integer.valueOf(loadToken).equals(rowLoadTokens.get(loaderId))) {
                    return;
                }
                if (hadRelocated) {
                    int showsRowId = findShowsRowId();
                    if (showsRowId != loaderId && data.containsKey(showsRowId)) {
                        List<Item> existingShows = new ArrayList<>(data.getOrDefault(showsRowId, List.of()));
                        java.util.Set<String> existingKeys = new java.util.HashSet<>();
                        for (Item it : existingShows) {
                            existingKeys.add(mediaKey(it.value));
                            existingKeys.add(CinemaPosters.query(it.value).title().toLowerCase(Locale.ROOT));
                        }
                        boolean changed = false;
                        synchronized (relocatedShows) {
                            for (Item rel : relocatedShows.values()) {
                                String tKey = CinemaPosters.query(rel.value).title().toLowerCase(Locale.ROOT);
                                if (existingKeys.add(mediaKey(rel.value)) && (tKey.isEmpty() || existingKeys.add(tKey))) {
                                    populateCachedItemFields(rel, app, lang, arabicMode, uiArabic, cardWidth);
                                    existingShows.add(rel);
                                    changed = true;
                                }
                            }
                        }
                        if (changed) {
                            data.put(showsRowId, existingShows);
                            RecyclerView sList = lists.get(showsRowId);
                            if (sList != null && sList.getAdapter() instanceof Posters p) {
                                p.replace(existingShows);
                                updateVisibility();
                            }
                            for (Item it : existingShows) if (it.stream == null) queuePoster(it, false);
                            warmHeroArtwork(showsRowId, existingShows);
                        }
                    }
                }

                List<Item> previousItems = data.put(loaderId, finalItems);
                RecyclerView list = lists.get(loaderId);
                if (list != null && list.getAdapter() instanceof Posters posters) {
                    posters.applyPrecomputed(adapterOldSnapshot, finalItems, finalDiff);
                    updateVisibility();
                }
                if (loaderId == 1 && pendingContinueFocusPos >= 0) {
                    final int targetPos = pendingContinueFocusPos;
                    pendingContinueFocusPos = -1;
                    rows.post(() -> {
                        if (!isAdded() || root == null) return;
                        RecyclerView cwList = lists.get(1);
                        if (cwList != null && cwList.getAdapter() != null && cwList.getAdapter().getItemCount() > 0
                                && cwList.getParent() instanceof View pv && pv.getVisibility() == View.VISIBLE) {
                            int clamped = Math.min(targetPos, cwList.getAdapter().getItemCount() - 1);
                            focusPoster(cwList, clamped);
                        } else {
                            if (!focusAdjacentSection(HomePreferences.KEY_CONTINUE, 1, 0)) {
                                focusFirstVisibleSection();
                            }
                        }
                    });
                } else if (contentLoaded && selectedRow == loaderId) {
                    View current = root.findFocus();
                    if (current == null || current == root) rows.post(() -> {
                        if (root != null && (root.findFocus() == null || root.findFocus() == root)) focusContent();
                    });
                }
                boolean listMembershipChanged = previousItems == null || previousItems.size() != finalItems.size();
                if (!listMembershipChanged) {
                    for (int idx = 0; idx < finalItems.size(); idx++) {
                        if (!itemKey(previousItems.get(idx)).equals(itemKey(finalItems.get(idx)))) {
                            listMembershipChanged = true;
                            break;
                        }
                    }
                }
                if (listMembershipChanged) {
                    for (Item item : finalItems) queuePoster(item, false);
                    warmHeroArtwork(loaderId, finalItems);
                }
                boolean readyToReveal = librariesLoaded && data.keySet().containsAll(rowOrder());
                if (!contentLoaded && readyToReveal) {
                    contentLoaded = true;
                    rows.post(() -> {
                        if (root == null) return;
                        if (returningPoster != null) {
                            PosterLocation location = returningPoster;
                            if (restorePoster(location)) returningPoster = null;
                        } else if (persistedRestore != null) {
                            PosterLocation location = persistedRestore;
                            if (resolveRowForLocation(location) != null) {
                                persistedRestore = null;
                                restorePoster(location);
                            } else if (root.findFocus() == root || root.findFocus() == null) {
                                focusContent();
                            }
                        } else if (persistedDiscoverRestore != null) {
                            DiscoverLocation dLoc = persistedDiscoverRestore;
                            if (focusDiscover(dLoc)) persistedDiscoverRestore = null;
                            else if (root.findFocus() == root || root.findFocus() == null) focusContent();
                        } else if (root.findFocus() == root || root.findFocus() == null) {
                            focusContent();
                        }
                    });
                } else if (contentLoaded && persistedRestore != null && resolveRowForLocation(persistedRestore) == lists.get(loaderId)) {
                    PosterLocation location = persistedRestore;
                    persistedRestore = null;
                    rows.post(() -> { if (root != null) restorePoster(location); });
                }
            });
        });
    }
    private void warmHeroArtwork(int rowId,List<Item> items){
        java.util.concurrent.Future<?> previous=posterTasks.get(rowId);if(previous!=null)previous.cancel(true);
        Context app=requireContext().getApplicationContext();
        String language=MetadataLanguage.resolve(requireContext());
        posterTasks.put(rowId,posterIo.submit(()->{
            for(Item item:items){
                if(Thread.currentThread().isInterrupted())return;
                Base b=item.value;
                String key=mediaKey(b);
                try{
                    if(CinemaArtwork.getCachedHero(app,language+":"+key)==null){
                        long id=posterOnlineId(app,b);
                        boolean isShow=b instanceof Tvshow||isSeriesOrEpisodeFile(b,null);
                        CinemaArtwork.Art art=CinemaArtwork.loadForItem(app,b,id,isShow,language);
                        if(art!=null){
                            heroArt.put(key,art);
                            CinemaArtwork.putCachedHero(app,language+":"+key,art);
                        }
                    }
                    CinemaArtwork.Art warm=heroArt.get(key);if(warm==null)warm=CinemaArtwork.getCachedHero(app,language+":"+key);
                    if(warm!=null){
                        heroArt.put(key,warm);
                        // Warm Picasso memory cache with the exact sizes used by the hero so logo/backdrop appear instantly.
                        if(warm.logo()!=null)Picasso.get().load(warm.logo()).resize(dp(app,320),dp(app,75)).centerInside().fetch();
                        if(warm.backdrop()!=null){int bw=prefs.getString(HomePreferences.BACKDROP,"balanced").equals("high")?1920:1280;Picasso.get().load(warm.backdrop()).resize(bw,bw*9/16).centerCrop().fetch();}
                    }
                }catch(Exception ignored){/* The selected/default poster remains a valid fallback. */}
            }
        }));
    }
    @Override public void onLoaderReset(@NonNull Loader<Cursor> loader){data.remove(loader.getId());}
    private void updateVisibility(){
        boolean any=false;
        for(Map.Entry<Integer,RecyclerView> e:lists.entrySet()){
            boolean visible=!data.getOrDefault(e.getKey(),List.of()).isEmpty();
            ((View)e.getValue().getParent()).setVisibility(visible?View.VISIBLE:View.GONE);any|=visible;
        }
        for(View g:discoverGroups){
            if(g.getVisibility()==View.VISIBLE)any=true;
        }
        empty.setVisibility(any?View.GONE:View.VISIBLE);
        if(!any)empty.setText(lists.isEmpty()?requireContext().getString(com.archos.mediacenter.video.R.string.cinema_all_home_rows_are_hidden_enable_them_in_appearance_settings):
                libraryCount>0?requireContext().getString(com.archos.mediacenter.video.R.string.cinema_library_folders_are_saved_open_a_library_to_browse_files_or_choose_refresh_library_to_scan_and_fetch_movie_information):
                requireContext().getString(com.archos.mediacenter.video.R.string.cinema_add_your_library_folders_in_network_files_to_get_started));
    }
    private void renderRows() {
        if(rows==null)return;
        boolean restoreFocus=root!=null&&root.findFocus()!=null&&root.findFocus()==lastContentFocus;
        rows.removeAllViews();lists.clear();renderedSectionKeys.clear();
        Context c=requireContext();
        boolean rtl=root!=null&&root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
        int rowEdgePad=dp(c,40);
        rows.setPadding(0,0,0,Math.max(dp(c,24),getResources().getDisplayMetrics().heightPixels-dp(c,250)-posterHeight()-dp(c,48)));

        // Prepare the 3 discover/trailer groups cleanly (indexed 0..2) before placing them in unified order.
        prepareDiscoverGroups(c, rtl, rowEdgePad);

        List<String> sectionOrder = activeSectionOrder();
        for (String sectionKey : sectionOrder) {
            if (!HomePreferences.isSectionVisible(prefs, sectionKey)) continue;
            if (HomePreferences.isTrailerKey(sectionKey)) {
                int sIdx = HomePreferences.trailerIndex(sectionKey);
                if (sIdx >= 0 && sIdx < discoverGroups.size()) {
                    View group = discoverGroups.get(sIdx);
                    if (group.getParent() instanceof ViewGroup vg) vg.removeView(group);
                    rows.addView(group);
                    renderedSectionKeys.add(sectionKey);
                }
                continue;
            }
            Integer rowIdObj = sectionKeyToRowId.get(sectionKey);
            if (rowIdObj == null) continue;
            int id = rowIdObj;
            LinearLayout group=new LinearLayout(c);group.setOrientation(LinearLayout.VERTICAL);group.setClipChildren(false);
            LibraryRow source=libraryRows.get(id);
            String titleStr = source == null ? rowTitle(c, id) : source.name();

            LinearLayout headerRow=new LinearLayout(c);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            headerRow.setPaddingRelative(rowEdgePad,dp(c,4),rowEdgePad,dp(c,6));

            TextView header=text(c,CinemaTokens.ROW_TITLE,true);
            header.setGravity(rtl?Gravity.RIGHT|Gravity.CENTER_VERTICAL:Gravity.LEFT|Gravity.CENTER_VERTICAL);
            header.setText(titleStr);
            headerRow.addView(header, new LinearLayout.LayoutParams(-2,-2));
            group.addView(headerRow);

            RecyclerView list=new RecyclerView(c);
            LinearLayoutManager lm = new LinearLayoutManager(c,RecyclerView.HORIZONTAL,false);
            lm.setInitialPrefetchItemCount(7);
            list.setLayoutManager(lm);
            list.setRecycledViewPool(sharedPosterPool);
            list.setItemViewCacheSize(10);
            list.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
            list.setClipChildren(false);list.setClipToPadding(false);list.setPaddingRelative(rowEdgePad,dp(c,12),dp(c,28),dp(c,15));
            list.setItemAnimator(null);list.setPreserveFocusAfterLayout(true);list.setFocusable(false);
            List<Item> initialItems = sortRowItemsForBind(id, data.getOrDefault(id, List.of()));
            Context appCtx = c.getApplicationContext();
            String lang = MetadataLanguage.resolve(appCtx);
            boolean arMode = lang.startsWith("ar");
            boolean uiAr = CinemaUi.arabic(c);
            int cardW = posterWidth();
            for (Item it : initialItems) {
                populateCachedItemFields(it, appCtx, lang, arMode, uiAr, cardW);
            }
            list.setAdapter(new Posters(id, initialItems));
            group.addView(list,new LinearLayout.LayoutParams(-1,posterHeight()+dp(c,27)));
            rows.addView(group);lists.put(id,list);
            renderedSectionKeys.add(sectionKey);
        }
        if (empty.getParent() instanceof ViewGroup) ((ViewGroup)empty.getParent()).removeView(empty);
        rows.addView(empty);bindDiscoverData();appendOtherVideos();updateVisibility();
        if(restoreFocus)rows.post(this::focusContent);
        if(returningDiscover!=null&&isResumed()&&focusDiscover(returningDiscover))returningDiscover=null;
        else if(persistedDiscoverRestore!=null&&isResumed()&&focusDiscover(persistedDiscoverRestore))persistedDiscoverRestore=null;
    }
    /** Hides poster rows while "other videos" is focused so no poster fragments peek above its cards. */
    private void setPosterRowsShown(boolean shown){
        for(RecyclerView list:lists.values()){View group=(View)list.getParent();if(group.getVisibility()==View.GONE)continue;group.setVisibility(shown?View.VISIBLE:View.INVISIBLE);}
        for(View group:discoverGroups){if(group.getVisibility()==View.GONE)continue;group.setVisibility(shown?View.VISIBLE:View.INVISIBLE);}
    }
    // ---- Trending / New Trailers / Coming Soon (OpenTrailersProvider + optional TMDb, refreshed daily) ----
    private com.meedx.providers.OpenTrailersProvider.TrailersFeed discover;
    private boolean discoverLoading;
    private long discoverCheckedAt;
    private String discoverLanguage;
    private final Runnable refreshDiscover=this::loadDiscover;
    private final List<LinearLayout> discoverStrips=new ArrayList<>();
    private final List<View> discoverGroups=new ArrayList<>();
    private void prepareDiscoverGroups(Context c, boolean rtl, int rowEdgePad){
        discoverStrips.clear();discoverGroups.clear();
        String[] heads={
            label(c,"الإعلانات الرائجة","Trending Trailers"),
            c.getString(com.archos.mediacenter.video.R.string.cinema_new_trailers),
            c.getString(com.archos.mediacenter.video.R.string.cinema_coming_soon)
        };
        for(String head:heads){
            LinearLayout group=new LinearLayout(c);group.setOrientation(LinearLayout.VERTICAL);group.setClipChildren(false);group.setClipToPadding(false);group.setVisibility(View.GONE);
            TextView header=text(c,CinemaTokens.ROW_TITLE,true);header.setGravity(rtl?Gravity.RIGHT:Gravity.LEFT);header.setText(head);header.setPaddingRelative(rowEdgePad,dp(c,8),rowEdgePad,dp(c,6));group.addView(header);
            android.widget.HorizontalScrollView scroller=new android.widget.HorizontalScrollView(c){
                @Override public boolean executeKeyEvent(KeyEvent event){return false;}
                @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect){
                    rect.left-=dp(c,18);
                    rect.right+=dp(c,18);
                    return super.computeScrollDeltaToGetChildRectOnScreen(rect);
                }
            };
            scroller.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);
            scroller.setFillViewport(true);
            scroller.setHorizontalScrollBarEnabled(false);scroller.setClipChildren(false);scroller.setClipToPadding(false);scroller.setFocusable(false);
            LinearLayout strip=new LinearLayout(c);strip.setOrientation(LinearLayout.HORIZONTAL);strip.setLayoutDirection(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);strip.setGravity(Gravity.CENTER_VERTICAL|(rtl?Gravity.RIGHT:Gravity.LEFT));strip.setPaddingRelative(rowEdgePad,dp(c,10),dp(c,28),dp(c,18));strip.setClipChildren(false);strip.setClipToPadding(false);
            strip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                boolean rtlStrip = root != null && root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                if (rtlStrip && !strip.hasFocus()) {
                    int maxScroll = Math.max(0, strip.getWidth() - scroller.getWidth());
                    if (maxScroll > 0 && scroller.getScrollX() != maxScroll) {
                        scroller.scrollTo(maxScroll, 0);
                    }
                }
            });
            scroller.addView(strip);group.addView(scroller);
            discoverStrips.add(strip);discoverGroups.add(group);
        }
    }
    private void bindDiscoverData(){
        Context c=requireContext();
        Context app=c.getApplicationContext();
        String language=MetadataLanguage.resolve(c);
        boolean arabic=language.startsWith("ar");
        if(discover==null||!language.equals(discoverLanguage)){
            var cached=com.meedx.providers.OpenTrailersProvider.getCached(app,arabic);
            if(cached!=null&&!cached.isEmpty()){
                discover=cached;
                discoverLanguage=language;
                discoverCheckedAt=cached.lastSyncMs;
            }
        }
        if(discover!=null)fillDiscover();
        loadDiscover();
    }
    private void loadDiscover(){
        if(root==null||discoverStrips.size()<3||discoverLoading)return;
        Context app=requireContext().getApplicationContext();
        String language=MetadataLanguage.resolve(requireContext());
        boolean arabic=language.startsWith("ar");
        long delay=86400000L-(System.currentTimeMillis()-discoverCheckedAt);
        if(discover!=null&&!discover.isEmpty()&&language.equals(discoverLanguage)&&delay>0&&!com.meedx.providers.OpenTrailersProvider.needsDailyRefresh(app,arabic)){
            handler.removeCallbacks(refreshDiscover);
            handler.postDelayed(refreshDiscover,delay);
            return;
        }
        discoverLoading=true;
        int token=viewGeneration;
        com.meedx.providers.OpenTrailersProvider.refreshAsync(app,arabic,false,result->{
            discoverLoading=false;
            if(root==null||viewGeneration!=token)return;
            if(result!=null&&!result.isEmpty()){
                discover=result;
                discoverLanguage=language;
                discoverCheckedAt=System.currentTimeMillis();
                fillDiscover();
                handler.removeCallbacks(refreshDiscover);
                handler.postDelayed(refreshDiscover,86400000L);
            }
        });
    }
    private void fillDiscover(){
        if(discover==null||discoverStrips.size()<3)return;
        View focused=root.findFocus();
        boolean restore=focused!=null&&selectedDiscover!=null&&focused==lastContentFocus&&selectedRow==5;
        fillStrip(0,discover.trending,true);
        fillStrip(1,discover.fresh,true);
        fillStrip(2,discover.comingSoon,true);
        updateVisibility();
        if(returningDiscover!=null&&isResumed()&&focusDiscover(returningDiscover))returningDiscover=null;
        else if(persistedDiscoverRestore!=null&&isResumed()&&focusDiscover(persistedDiscoverRestore))persistedDiscoverRestore=null;
        else if(restore)focusDiscover(selectedDiscover);
    }
    private void fillStrip(int s,List<com.meedx.providers.OpenTrailersProvider.TrailerItem> entries,boolean wide){
        LinearLayout strip=discoverStrips.get(s);strip.removeAllViews();
        boolean sectionEnabled = HomePreferences.isSectionVisible(prefs, HomePreferences.trailerSectionKey(s));
        boolean show = sectionEnabled && entries != null && !entries.isEmpty();
        discoverGroups.get(s).setVisibility(show ? View.VISIBLE : View.GONE);
        if(show){
            appendDiscoverCards(s,entries,wide);
            if(strip.getParent() instanceof android.widget.HorizontalScrollView hsv){
                boolean rtlStrip=root!=null&&root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
                hsv.post(()->{
                    if(rtlStrip) hsv.scrollTo(Math.max(0,strip.getWidth()-hsv.getWidth()),0);
                    else hsv.scrollTo(0,0);
                });
            }
        }
    }
    private static String discoverKey(com.meedx.providers.OpenTrailersProvider.TrailerItem entry){
        if(entry.imdbId!=null&&!entry.imdbId.isBlank())return entry.imdbId;
        return entry.title+":"+entry.year;
    }
    private void appendDiscoverCards(int s,List<com.meedx.providers.OpenTrailersProvider.TrailerItem> entries,boolean wide){
        Context c=requireContext();LinearLayout strip=discoverStrips.get(s);
        boolean arabic=MetadataLanguage.resolve(c).startsWith("ar");
        boolean rtlStrip=root!=null&&root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
        int w=wide?posterWidth()*3/2:posterWidth(),h=wide?w*9/16:posterHeight();
        for(int n=0;n<entries.size();n++){
            final int index=strip.getChildCount();final com.meedx.providers.OpenTrailersProvider.TrailerItem entry=entries.get(n);
            LinearLayout column=new LinearLayout(c);column.setOrientation(LinearLayout.VERTICAL);column.setLayoutDirection(rtlStrip?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);column.setClipChildren(false);column.setClipToPadding(false);
            column.setPadding(dp(c,4),dp(c,6),dp(c,4),dp(c,6));
            FrameLayout card=new FrameLayout(c);card.setId(View.generateViewId());card.setLayoutDirection(rtlStrip?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR);card.setFocusable(true);card.setClickable(true);card.setContentDescription(entry.title);card.setClipToOutline(true);
            GradientDrawable surface=new GradientDrawable();surface.setColor(0xff202020);surface.setCornerRadius(dp(c,8));card.setBackground(surface);
            ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setClipToOutline(true);image.setBackground(surface);
            card.addView(image,new FrameLayout.LayoutParams(-1,-1));
            String source=!entry.thumbnailUrl.isEmpty()?entry.thumbnailUrl:(!entry.backdropUrl.isEmpty()?entry.backdropUrl:null);
            if(source!=null)Picasso.get().load(source).resize(w,h).centerCrop().into(image);
            TextView play=text(c,18,true);play.setText("▶");play.setGravity(Gravity.CENTER);play.setBackground(rounded(0x99000000,dp(c,18),0));card.addView(play,new FrameLayout.LayoutParams(dp(c,36),dp(c,36),Gravity.CENTER));
            TextView typeBadge=text(c,9,true);
            typeBadge.setText("series".equalsIgnoreCase(entry.mediaType)?label(c,"مسلسل","SERIES"):label(c,"فيلم","MOVIE"));
            typeBadge.setTextColor(0xffffffff);typeBadge.setPadding(dp(c,6),dp(c,2),dp(c,6),dp(c,2));
            typeBadge.setBackground(rounded(0xb8101113,dp(c,4),0));
            FrameLayout.LayoutParams tbLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
            tbLp.setMargins(dp(c,6),dp(c,6),0,0);card.addView(typeBadge,tbLp);
            column.addView(card,new LinearLayout.LayoutParams(w,h));
            TextView captionTitle=text(c,12,true);captionTitle.setGravity(Gravity.START);captionTitle.setSingleLine(true);captionTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);captionTitle.setPadding(dp(c,2),0,dp(c,2),0);captionTitle.setText(entry.title);
            LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(w,-2);titleLp.topMargin=dp(c,8);column.addView(captionTitle,titleLp);
            String sub=!entry.releaseDate.isEmpty()?entry.releaseDate:(!entry.year.isEmpty()?entry.year:entry.genres);
            if(sub!=null&&!sub.isEmpty()){
                TextView captionDate=text(c,11,false);captionDate.setTextColor(CinemaTokens.SECONDARY);captionDate.setGravity(Gravity.START);captionDate.setSingleLine(true);captionDate.setEllipsize(android.text.TextUtils.TruncateAt.END);captionDate.setPadding(dp(c,2),dp(c,2),dp(c,2),0);captionDate.setText(sub);column.addView(captionDate,new LinearLayout.LayoutParams(w,-2));
            }
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.setMarginEnd(dp(c,14));strip.addView(column,cp);
            DiscoverLocation location=new DiscoverLocation(s,discoverKey(entry));
            card.setTag(location);
            card.setOnClickListener(v->openDiscoverTrailer(entry,location));
            card.setOnFocusChangeListener((v,focus)->{
                setPosterFocus(card,focus);
                column.setTranslationZ(focus?dp(c,4):0f);
                card.setTranslationZ(focus?dp(c,4):0f);
                if(focus){setPosterRowsShown(true);lastContentFocus=v;selectedRow=5;selectedDiscover=location;showDiscoverHero(entry,arabic);
                    if(index==0&&strip.getParent() instanceof android.widget.HorizontalScrollView hsv){
                        boolean rtlRow=root!=null&&root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
                        hsv.post(()->{if(rtlRow)hsv.smoothScrollTo(Math.max(0,strip.getWidth()-hsv.getWidth()),0);else hsv.smoothScrollTo(0,0);});
                    }
                    View group=discoverGroups.get(s);contentScroll.post(()->{if(root!=null&&contentScroll!=null&&v.hasFocus()&&group.getParent()==rows){if(motion(c))contentScroll.smoothScrollTo(0,group.getTop());else contentScroll.scrollTo(0,group.getTop());}});}
            });
            card.setOnKeyListener((v,key,event)->{
                key=logicalKey(v,key);
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                switch(key){
                    case KeyEvent.KEYCODE_DPAD_LEFT:if(index==0){if(rail.getChildCount()>0)rail.getChildAt(0).requestFocus();}else ((ViewGroup)strip.getChildAt(index-1)).getChildAt(0).requestFocus();return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:if(index<strip.getChildCount()-1)((ViewGroup)strip.getChildAt(index+1)).getChildAt(0).requestFocus();return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return focusAdjacentSection(HomePreferences.trailerSectionKey(s), -1, index);
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        return focusAdjacentSection(HomePreferences.trailerSectionKey(s), 1, index);
                    default:return false;
                }
            });
        }
        int total=strip.getChildCount();
        int railTargetId=rail!=null&&rail.getChildCount()>0?rail.getChildAt(0).getId():(rail!=null?rail.getId():View.NO_ID);
        for(int idx=0;idx<total;idx++){
            View cCard=((ViewGroup)strip.getChildAt(idx)).getChildAt(0);
            int prevId=idx>0?((ViewGroup)strip.getChildAt(idx-1)).getChildAt(0).getId():railTargetId;
            int nextId=idx+1<total?((ViewGroup)strip.getChildAt(idx+1)).getChildAt(0).getId():cCard.getId();
            if(rtlStrip){
                if(prevId!=View.NO_ID)cCard.setNextFocusRightId(prevId);
                cCard.setNextFocusLeftId(nextId);
            }else{
                if(prevId!=View.NO_ID)cCard.setNextFocusLeftId(prevId);
                cCard.setNextFocusRightId(nextId);
            }
        }
    }
    /** Focuses a card in the nearest non-empty discover strip starting at {@code from} walking by {@code step}. */
    private boolean focusDiscover(DiscoverLocation location){
        if(location.strip()>=discoverStrips.size())return false;
        LinearLayout strip=discoverStrips.get(location.strip());
        for(int n=0;n<strip.getChildCount();n++){
            View card=((ViewGroup)strip.getChildAt(n)).getChildAt(0);
            if(card.getTag() instanceof DiscoverLocation candidate&&candidate.key().equals(location.key()))
                return card.requestFocus();
        }
        return false;
    }
    private boolean focusStrip(int from,int step,int index){
        for(int n=from;n>=0&&n<discoverStrips.size();n+=step){
            if (!HomePreferences.isSectionVisible(prefs, HomePreferences.trailerSectionKey(n))) continue;
            if (n < discoverGroups.size() && discoverGroups.get(n).getVisibility() == View.GONE) continue;
            LinearLayout strip=discoverStrips.get(n);
            if(strip.getChildCount()>0){((ViewGroup)strip.getChildAt(Math.min(index,strip.getChildCount()-1))).getChildAt(0).requestFocus();return true;}
        }
        return false;
    }
    private boolean focusLastPosterRow(int index){
        List<Integer> order=rowOrder();
        setPosterRowsShown(true);
        for(int k=order.size()-1;k>=0;k--){RecyclerView list=lists.get(order.get(k));if(list!=null&&list.getAdapter().getItemCount()>0)return focusPoster(list,Math.min(index,list.getAdapter().getItemCount()-1));}
        return true;
    }
    private void showDiscoverHero(com.meedx.providers.OpenTrailersProvider.TrailerItem entry,boolean arabic){
        Context c=requireContext();
        int viewToken=viewGeneration;
        pending=null;if(artworkTask!=null)artworkTask.cancel(true);handler.removeCallbacks(selectPending);
        Picasso.get().cancelRequest(logo);Picasso.get().cancelRequest(backdrop);logo.setVisibility(View.GONE);
        String background=!entry.backdropUrl.isEmpty()?entry.backdropUrl:(!entry.thumbnailUrl.isEmpty()?entry.thumbnailUrl:null);
        if(background!=null)Picasso.get().load(background).resize(1280,720).centerCrop().into(backdrop);else backdrop.setImageDrawable(null);
        GradientDrawable.Orientation shadeOrient=root!=null&&root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL
                ?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.LEFT_RIGHT;
        horizontalShade.setBackground(new GradientDrawable(shadeOrient,new int[]{CinemaTokens.CANVAS,0xb0171717,0x10171717}));
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0x55171717,CinemaTokens.CANVAS}));
        title.setText(entry.title);title.setVisibility(View.VISIBLE);
        if(!entry.logoUrl.isEmpty()&&isAdded()){
            Context app=c.getApplicationContext();
            logo.setVisibility(View.VISIBLE);
            Picasso.get().load(entry.logoUrl).resize(dp(app,320),dp(app,75)).centerInside().into(logo,new com.squareup.picasso.Callback(){
                @Override public void onSuccess(){if(root!=null&&viewGeneration==viewToken&&pending==null)title.setVisibility(View.GONE);}
                @Override public void onError(Exception e){if(root!=null&&viewGeneration==viewToken&&pending==null){logo.setVisibility(View.GONE);title.setVisibility(View.VISIBLE);}}
            });
        }
        List<String> info=new ArrayList<>();
        if(!entry.releaseDate.isEmpty())info.add(entry.releaseDate);
        else if(!entry.year.isEmpty())info.add(entry.year);
        if(!entry.runtime.isEmpty())info.add(entry.runtime);
        if(!entry.genres.isEmpty())info.add(entry.genres);
        if(!entry.ageRating.isEmpty())info.add(entry.ageRating);
        if(entry.rating!=null&&entry.rating>0)info.add(String.format(Locale.US,"★ %.1f",entry.rating));
        meta.setText(String.join("  ·  ",info));
        String fullPlot=entry.description==null?"":entry.description;
        if(!entry.cast.isEmpty()){
            fullPlot+=(fullPlot.isEmpty()?"":"\n")+(arabic?"الممثلون: ":"Cast: ")+entry.cast;
        }
        plot.setText(fullPlot);
    }
    private void openDiscoverTrailer(com.meedx.providers.OpenTrailersProvider.TrailerItem entry,DiscoverLocation location){
        returningDiscover=location;
        int parsedYear=0;
        try{if(entry.year!=null&&entry.year.length()>=4)parsedYear=Integer.parseInt(entry.year.substring(0,4));}catch(Exception ignored){}
        if(entry.ytId!=null&&!entry.ytId.isBlank()){
            CinemaYouTubeLauncher.watchTrailer(requireContext(),entry.ytId,entry.title,parsedYear);
            return;
        }
        int token=viewGeneration;
        int yr=parsedYear;
        Context app=requireContext().getApplicationContext();
        io.submit(()->{
            String resolved=com.meedx.providers.OpenTrailersProvider.resolveTrailerUrl(
                    app,entry.title,yr,entry.imdbId,"series".equalsIgnoreCase(entry.mediaType));
            handler.post(()->{
                if(root==null||viewGeneration!=token||!isResumed())return;
                CinemaYouTubeLauncher.watchTrailer(requireContext(),resolved,entry.title,yr);
            });
        });
    }
    private void alignOtherVideos() {
        if (otherVideos != null && otherVideos.getParent() == rows && otherVideos.hasFocus()
                && contentScroll.getScrollY() != otherVideos.getTop()) {
            contentScroll.scrollTo(0, otherVideos.getTop());
        }
    }

    private void appendOtherVideos() {
        Context c = requireContext();
        boolean rtl = root != null && root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int rowEdgePad = dp(c, 40);
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        group.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        group.setClipChildren(false);
        group.setClipToPadding(false);
        rows.addView(group);
        otherVideos = group;

        TextView heading = text(c, 24, true);
        heading.setText(c.getString(com.archos.mediacenter.video.R.string.cinema_choose_a_video));
        heading.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        heading.setPaddingRelative(rowEdgePad, 0, rowEdgePad, 0);
        group.addView(heading);
        TextView description = text(c, CinemaTokens.LABEL, false);
        description.setTextColor(CinemaTokens.SECONDARY);
        description.setMaxLines(2);
        description.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        description.setPaddingRelative(rowEdgePad, dp(c, 4), rowEdgePad, 0);
        group.addView(description);

        LinearLayout cards = new LinearLayout(c);
        cards.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        cards.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        int inset = dp(c, 12);
        cards.setPaddingRelative(rowEdgePad, inset, rowEdgePad, inset);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        var stripLayout = new LinearLayout.LayoutParams(-1, -2);
        stripLayout.topMargin = dp(c, 8);
        group.addView(cards, stripLayout);

        String fileHint = c.getString(com.archos.mediacenter.video.R.string.cinema_choose_a_file_from_storage_or_your_network_to_play_manually);
        description.setText(fileHint);
        otherDescription = description;
        String[] names = {
                c.getString(com.archos.mediacenter.video.R.string.cinema_choose_a_video),
                c.getString(com.archos.mediacenter.video.R.string.cinema_network_files),
                c.getString(com.archos.mediacenter.video.R.string.cinema_link_phone_qr),
                CinemaUi.label(c, "إدارة الريموت", "Remote Manager"),
                c.getString(com.archos.mediacenter.video.R.string.addon_hub_title),
                c.getString(com.archos.mediacenter.video.R.string.cinema_ambient_title)
        };
        int[] icons = {
                com.archos.mediacenter.video.R.drawable.cinema_folder,
                com.archos.mediacenter.video.R.drawable.cinema_network,
                com.archos.mediacenter.video.R.drawable.cinema_qr,
                com.archos.mediacenter.video.R.drawable.cinema_ic_remote,
                com.archos.mediacenter.video.R.drawable.cinema_addons,
                com.archos.mediacenter.video.R.drawable.cinema_ambient_frame
        };
        tileNetworkLed = null;
        tileQrLed = null;
        tileRemoteLed = null;
        tileAddonsLed = null;
        otherRemoteCard = null;
        for (int index = 0; index < names.length; index++) {
            final int item = index;
            FrameLayout card = new FrameLayout(c);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setId(View.generateViewId());
            card.setClickable(true);
            card.setContentDescription(names[item]);
            card.setBackgroundResource(com.archos.mediacenter.video.R.drawable.bg_other_video_card);

            var size = new LinearLayout.LayoutParams(posterWidth(), Math.min(posterHeight(), dp(c, 152)));
            size.setMarginEnd(dp(c, 16));
            cards.addView(card, size);
            if (item == 0) otherFirst = card;
            else {
                View previous = cards.getChildAt(item - 1);
                if (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                    previous.setNextFocusLeftId(card.getId());
                    card.setNextFocusRightId(previous.getId());
                } else {
                    previous.setNextFocusRightId(card.getId());
                    card.setNextFocusLeftId(previous.getId());
                }
            }

            ImageView image = new ImageView(c);
            image.setImageResource(icons[item]);
            image.setColorFilter(0xffe5b84b);
            card.addView(image, new FrameLayout.LayoutParams(dp(c, 48), dp(c, 48), Gravity.CENTER));
            TextView caption = text(c, 14, true);
            caption.setGravity(Gravity.CENTER);
            caption.setText(names[item]);
            caption.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 12));
            card.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            if (item != 0 && item != 5) {
                View led = new View(c);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setStroke(dp(c, 1), 0xff141517);
                dotBg.setColor(0xffef4444);
                led.setBackground(dotBg);
                FrameLayout.LayoutParams ledLp = new FrameLayout.LayoutParams(dp(c, 11), dp(c, 11), Gravity.TOP | Gravity.RIGHT);
                ledLp.topMargin = dp(c, 12);
                ledLp.rightMargin = dp(c, 12);
                card.addView(led, ledLp);
                if (item == 1) tileNetworkLed = led;
                else if (item == 2) tileQrLed = led;
                else if (item == 4) tileAddonsLed = led;
                else {
                    tileRemoteLed = led;
                    otherRemoteCard = card;
                }
            }

            card.setOnClickListener(v -> {
                if (item == 0) chooseVideo();
                else if (item == 1) startActivity(new Intent(c, CinemaNetworkActivity.class));
                else if (item == 2) CinemaPhoneLink.show(c);
                else if (item == 4) startActivity(new Intent(c, CinemaAddonsActivity.class));
                else if (item == 5) CinemaAmbientLauncherDialog.show(c);
                else if (getActivity() != null) {
                    TvRemotePermissionKeeper.showRemoteManagerDialog(getActivity(), this::refreshTileStatusDots);
                }
            });
            card.setOnFocusChangeListener((v, focused) -> {
                card.animate().cancel();
                float scale = focused ? 1.06f : 1f;
                if (motion(c)) card.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
                else {
                    card.setScaleX(scale);
                    card.setScaleY(scale);
                }
                image.setColorFilter(focused ? 0xffffd37a : 0xffe5b84b);
                if (!focused) return;

                group.animate().cancel();
                group.setTranslationY(0f);
                cards.animate().cancel();
                cards.setTranslationY(0f);
                setPosterRowsShown(false);
                lastContentFocus = v;
                selectedRow = 5;
                selectedDiscover = null;
                pending = null;
                if (artworkTask != null) artworkTask.cancel(true);
                handler.removeCallbacks(selectPending);
                Picasso.get().cancelRequest(logo);
                Picasso.get().cancelRequest(backdrop);
                logo.setVisibility(View.GONE);
                backdrop.setImageResource(com.archos.mediacenter.video.R.drawable.cinema_other_videos_bg);
                GradientDrawable.Orientation shadeOrient = rtl
                        ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT;
                horizontalShade.setBackground(new GradientDrawable(shadeOrient,
                        new int[]{CinemaTokens.CANVAS, 0xb0171717, 0x10171717}));
                bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x00000000, 0x55171717, CinemaTokens.CANVAS}));
                title.setText(requireContext().getString(com.archos.mediacenter.video.R.string.cinema_other_videos));
                title.setVisibility(View.VISIBLE);
                meta.setText("");
                plot.setText("");
                heading.setText(names[item]);
                description.setText(otherTileDescription(c, item, fileHint));
                alignOtherVideos();
            });
            card.setOnKeyListener((v, key, event) -> {
                key=logicalKey(v,key);
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    setPosterRowsShown(true);
                    return focusLastVisibleSection(item);
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && item == 0) {
                    rail.getChildAt(0).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && item > 0)
                    return cards.getChildAt(item - 1).requestFocus();
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && item + 1 < names.length)
                    return cards.getChildAt(item + 1).requestFocus();
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && item == names.length - 1) return true;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    alignOtherVideos();
                    return true;
                }
                return false;
            });
        }
        refreshTileStatusDots();
    }

    private String otherTileDescription(Context c, int item, String fileHint) {
        if (item == 2) return CinemaPhoneLink.instructions(c);
        if (item == 4) return c.getString(com.archos.mediacenter.video.R.string.addon_hub_description);
        if (item == 5) return c.getString(com.archos.mediacenter.video.R.string.cinema_ambient_description);
        if (item == 3) {
            return switch (TvRemotePermissionKeeper.evaluateRemoteLed(c)) {
                case GREEN -> CinemaUi.label(c,
                        "كلا المسارين مفعّلان (ADB المحلي + الويب/سهولة الوصول) — اضغط للفتح والاختبار",
                        "Hybrid Active (Local ADB + Accessibility/Web) — press to manage & test");
                case BLUE -> CinemaUi.label(c,
                        "وضع الويب فقط — الصلاحيات محدودة خارج التطبيق",
                        "Web Only Mode — Permissions limited outside app");
                case RED -> CinemaUi.label(c,
                        "الريموت غير مفعّل — اضغط للتفعيل والاختبار",
                        "Remote Inactive — press to enable & test");
            };
        }
        return fileHint;
    }

    private void refreshTileStatusDots() {
        if (!isAdded() || root == null) return;
        Context c = requireContext();
        setLedColor(tileNetworkLed, CinemaLinkServer.isServerRunning() ? 0xff22c55e : 0xffef4444);
        setLedColor(tileQrLed, CinemaLinkServer.hasOnlineController() ? 0xff22c55e : 0xffef4444);
        TvRemotePermissionKeeper.RemoteLed remoteLed = TvRemotePermissionKeeper.evaluateRemoteLed(c);
        setLedColor(tileRemoteLed, TvRemotePermissionKeeper.remoteLedColor(remoteLed));
        boolean synced = c.getSharedPreferences("cinema_addon_state", 0).getBoolean("from_phone", false);
        setLedColor(tileAddonsLed, synced && cachedAddonsEnabled ? 0xff22c55e : 0xff554d3f);
        Context app = c.getApplicationContext();
        io.execute(() -> {
            boolean hasEnabled = com.meedx.providers.StremioAddons.hasEnabled(app);
            if (hasEnabled != cachedAddonsEnabled) {
                cachedAddonsEnabled = hasEnabled;
                handler.post(() -> {
                    if (!isAdded() || root == null) return;
                    boolean syn = app.getSharedPreferences("cinema_addon_state", 0).getBoolean("from_phone", false);
                    setLedColor(tileAddonsLed, syn && cachedAddonsEnabled ? 0xff22c55e : 0xff554d3f);
                });
            }
        });
        if (otherRemoteCard != null && otherRemoteCard.hasFocus() && otherDescription != null) {
            otherDescription.setText(otherTileDescription(c, 3,
                    c.getString(com.archos.mediacenter.video.R.string.cinema_choose_a_file_from_storage_or_your_network_to_play_manually)));
        }
    }

    private static void setLedColor(View led, int color) {
        if (led == null) return;
        if (led.getBackground() instanceof GradientDrawable gd) {
            gd.setColor(color);
            led.invalidate();
        }
    }
    private static final int FILE_BROWSER_REQUEST=72;

    private void chooseVideo(){
        startActivityForResult(new Intent(requireContext(),CinemaFileBrowserActivity.class),FILE_BROWSER_REQUEST);
    }

    private void chooseSystemVideo(){
        Intent pick=new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        for(android.content.pm.ResolveInfo choice:requireContext().getPackageManager().queryIntentActivities(pick,0)){
            if(choice.activityInfo.name.contains("Stub")||choice.activityInfo.packageName.contains("frameworkpackagestubs"))continue;
            try{pick.setComponent(new android.content.ComponentName(choice.activityInfo.packageName,choice.activityInfo.name));startActivityForResult(pick,71);return;}catch(android.content.ActivityNotFoundException ignored){}
        }
        startActivity(new Intent(requireContext(),CinemaNetworkActivity.class));
    }
    @Override public void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);
        if(result!=android.app.Activity.RESULT_OK||intent==null)return;
        if(request==FILE_BROWSER_REQUEST&&intent.getBooleanExtra("system_picker",false)){
            chooseSystemVideo();
            return;
        }
        if((request==71||request==FILE_BROWSER_REQUEST)&&intent.getData()!=null){
            Uri rawUri=intent.getData();
            Uri resolvedUri=rawUri;
            try{Uri real=com.archos.filecorelibrary.FileUtils.getRealUriFromVideoURI(requireContext(),rawUri);if(real!=null)resolvedUri=real;}catch(Exception ignored){}
            String displayName=com.archos.filecorelibrary.FileUtils.getName(resolvedUri);
            com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo item=new com.archos.mediacenter.video.browser.adapters.object.NonIndexedVideo(resolvedUri,resolvedUri,displayName,null);
            Intent details=new Intent(requireContext(),CinemaDetailsActivity.class);
            details.setData(rawUri);
            details.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            details.putExtra(VideoDetailsFragment.EXTRA_VIDEO,item);
            startActivity(details);
        }
    }
    private int posterWidth(){
        int available=getResources().getDisplayMetrics().widthPixels-dp(requireContext(),144);
        int count=Math.max(4,Math.min(9,prefs.getInt(HomePreferences.COUNT,6)));
        float scale=switch(prefs.getString(HomePreferences.SIZE,"medium")){case "small"->0.85f;case "large"->1.15f;default->1f;};
        return Math.round((available/count-dp(requireContext(),16))*scale);
    }
    private int posterHeight(){return posterWidth()*3/2;}
    private volatile Item pending;
    private final Runnable selectPending=()->{if(pending!=null&&root!=null)showHero(pending);};
    private void select(Item item){
        pending=item;
        pendingRow=selectedRow;
        handler.removeCallbacks(selectPending);
        handler.postDelayed(selectPending,16);
    }

    static String stripGenericEpisodeTokens(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceFirst("(?i)^\\s*(?:(?:Season\\s*\\p{Nd}+\\s*[·: -]*\\s*Episode\\s*\\p{Nd}+|الموسم\\s*\\p{Nd}+\\s*[·: -]*\\s*الحلقة\\s*\\p{Nd}+)\\s*[—–:\\-·]*\\s*)+", "")
                .replaceAll("(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b", " ")
                .replaceAll("(?i)\\b\\d{1,2}x\\d{1,3}\\b", " ")
                .replaceAll("^[\\s\\-·:]+|[\\s\\-·:]+$", "")
                .trim();
        return cleaned;
    }

    private String continueHeroEpisodeMeta(Item item, boolean arabicMode) {
        if (item == null || !isAdded()) return null;
        int season = 0, episode = 0;
        String epName = "";
        long dur = 0L, res = 0L;
        if (item.stream != null && (item.stream.season() > 0 || item.stream.episode() > 0)) {
            season = item.stream.season();
            episode = item.stream.episode();
            epName = stripGenericEpisodeTokens(item.stream.title());
            dur = item.stream.durationMs();
            res = item.stream.positionMs();
        } else if (item.value instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep) {
            season = ep.getSeasonNumber();
            episode = ep.getEpisodeNumber();
            epName = stripGenericEpisodeTokens(ep.getEpisodeName());
            dur = CinemaMediaTime.known(ep.getId(), ep.getFilePath(), ep.getDurationMs());
            res = CinemaMediaTime.knownResume(ep.getId(), ep.getFilePath(), ep.getResumeMs());
        } else if (item.value instanceof Video v && isSeriesOrEpisodeFile(v, v.getFilePath())) {
            int[] se = CinemaPosters.extractSeasonEpisode(v.getFilePath(), v.getName());
            season = se[0];
            episode = se[1];
            dur = CinemaMediaTime.known(v.getId(), v.getFilePath(), v.getDurationMs());
            res = CinemaMediaTime.knownResume(v.getId(), v.getFilePath(), v.getResumeMs());
        } else if (pendingRow == 1 && item.value instanceof Video v) {
            dur = CinemaMediaTime.known(v.getId(), v.getFilePath(), v.getDurationMs());
            res = CinemaMediaTime.knownResume(v.getId(), v.getFilePath(), v.getResumeMs());
            String rem = CinemaMediaTime.formatRemaining(dur, res);
            if (rem != null) {
                return arabicMode ? ("متبقية " + rem) : (rem + " left");
            }
            return null;
        }
        if (season <= 0 && episode <= 0) return null;
        String base = arabicMode
                ? String.format(Locale.US, "الموسم %d · الحلقة %d", season, episode)
                : String.format(Locale.US, "Season %d · Episode %d", season, episode);
        if (!epName.isEmpty()) base += " — " + epName;
        String rem = CinemaMediaTime.formatRemaining(dur, res);
        if (rem != null) {
            base += arabicMode ? (" · متبقية " + rem) : (" · " + rem + " left");
        }
        return base;
    }

    private void applyHeroArt(Base b,CinemaArtwork.Art art,int viewToken,Item item){
        if(art==null)return;
        boolean arabicMode=MetadataLanguage.resolve(requireContext()).startsWith("ar");
        String continueLine = pendingRow == 1 ? continueHeroEpisodeMeta(item, arabicMode) : null;
        if(art.title()!=null&&!art.title().isBlank()){title.setText(art.title());title.setVisibility(View.VISIBLE);}
        if(continueLine!=null){
            meta.setText(continueLine);
            meta.setTextColor(CinemaTokens.GOLD);
        }else if(art.year()>0){
            meta.setTextColor(CinemaTokens.SECONDARY);
            java.util.List<String> info=new java.util.ArrayList<>();info.add(String.valueOf(art.year()));
            if(b instanceof Video v&&v.getDurationMs()>0)info.add(CinemaMediaTime.format(CinemaMediaTime.known(v.getFilePath(),v.getDurationMs())));
            else if(b instanceof Tvshow show)info.add(String.format(Locale.US,"%d seasons",show.getSeasonCount()));
            if(art.genres()!=null&&!art.genres().isBlank()){String[] genres=art.genres().split(" · ");info.add(genres[0]+(genres.length>1?", "+genres[1]:""));}
            if(art.certification()!=null&&!art.certification().isBlank())info.add(art.certification());meta.setText(String.join("  ·  ",info));
        }else{
            meta.setTextColor(CinemaTokens.SECONDARY);
        }
        if(art.overview()!=null&&!art.overview().isBlank())plot.setText(art.overview());
        if(art.logo()!=null&&isAdded()){
            Context app=requireContext().getApplicationContext();
            logo.setVisibility(View.VISIBLE);Picasso.get().load(art.logo()).resize(dp(app,320),dp(app,75)).centerInside().into(logo,new com.squareup.picasso.Callback(){
                @Override public void onSuccess(){if(root!=null&&viewGeneration==viewToken&&pending==item)title.setVisibility(View.GONE);}
                @Override public void onError(Exception e){if(root!=null&&viewGeneration==viewToken&&pending==item){logo.setVisibility(View.GONE);title.setVisibility(View.VISIBLE);}}
            });
        }
    }
    private static boolean hasArabicScrapedData(Base b) {
        if (b == null) return false;
        if (com.meedx.providers.OpenMetadata.containsArabic(b.getName())) return true;
        if (b instanceof Tvshow s && com.meedx.providers.OpenMetadata.containsArabic(s.getPlot())) return true;
        if (b instanceof Video v && com.meedx.providers.OpenMetadata.containsArabic(v.getDescriptionBody())) return true;
        return false;
    }

    private void showHero(Item item){
        int viewToken=viewGeneration;
        Base b=item.value;
        String key=mediaKey(b);
        String language=MetadataLanguage.resolve(requireContext());
        boolean arabicMode=language.startsWith("ar");
        String rawCleanName=(b instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep && ep.getShowName()!=null && !ep.getShowName().isBlank())
                ? ep.getShowName() : CinemaPosters.cleanDisplayTitle(b,arabicMode);
        String cleanName=rawCleanName;
        CinemaArtwork.Art cachedArt=heroArt.get(key);
        if(cachedArt!=null&&!arabicMode&&(com.meedx.providers.OpenMetadata.containsArabic(cachedArt.title())||com.meedx.providers.OpenMetadata.containsArabic(cachedArt.overview()))){
            heroArt.remove(key);
            cachedArt=null;
        }
        if(cachedArt==null)cachedArt=CinemaArtwork.getCachedHero(requireContext(),language+":"+key);
        if(cachedArt!=null)heroArt.put(key,cachedArt);
        String initialTitle=cachedArt!=null&&cachedArt.title()!=null&&!cachedArt.title().isBlank()?cachedArt.title():cleanName;
        title.setText(initialTitle!=null?initialTitle:"");title.setVisibility(View.VISIBLE);
        Picasso.get().cancelRequest(logo);logo.setVisibility(View.GONE);
        String summary="",details="";
        if(b instanceof Video v){summary=v.getDescriptionBody();if(v.getDurationMs()>0)details=CinemaMediaTime.format(CinemaMediaTime.known(v.getFilePath(),v.getDurationMs()));}
        if(b instanceof Movie m)details=String.format(Locale.US,"%d  ·  %s  ·  %s",m.getYear(),details,m.getContentRating()==null?"":m.getContentRating());
        if(b instanceof Tvshow s){summary=s.getPlot();details=String.format(Locale.US,"%d  ·  %d",s.getYear(),s.getSeasonCount());}
        if(!arabicMode&&com.meedx.providers.OpenMetadata.containsArabic(summary))summary="";
        String continueLine = pendingRow == 1 ? continueHeroEpisodeMeta(item, arabicMode) : null;
        plot.setText(summary!=null?summary:"");
        if(continueLine!=null){
            meta.setText(continueLine);
            meta.setTextColor(CinemaTokens.GOLD);
        }else{
            meta.setText(details);
            meta.setTextColor(CinemaTokens.SECONDARY);
        }
        Picasso.get().cancelRequest(backdrop);
        Uri initialBackground=cachedArt!=null&&cachedArt.backdrop()!=null?Uri.parse(cachedArt.backdrop()):item.background;
        if(initialBackground==null)backdrop.setImageDrawable(null);
        int width=prefs.getString(HomePreferences.BACKDROP,"balanced").equals("high")?1920:1280;
        if(initialBackground!=null)loadBackdrop(initialBackground,width,item);
        if(cachedArt!=null){
            applyHeroArt(b,cachedArt,viewToken,item);
            return;
        }
        if(artworkTask!=null)artworkTask.cancel(true);
        Context app=requireContext().getApplicationContext();
        artworkTask=io.submit(()->{try{
            if(pending!=item||Thread.currentThread().isInterrupted())return;
            long id=posterOnlineId(app,b);
            boolean isShow=b instanceof Tvshow||isSeriesOrEpisodeFile(b,null);
            CinemaArtwork.Art art=CinemaArtwork.loadForItem(app,b,id,isShow,language);
            handler.post(()->{
                if(root==null||viewGeneration!=viewToken||pending!=item)return;if(art==null){title.setText(cleanName);title.setVisibility(View.VISIBLE);return;}
                heroArt.put(key,art);
                CinemaArtwork.putCachedHero(app,language+":"+key,art);
                if(art.backdrop()!=null&&!Uri.parse(art.backdrop()).equals(initialBackground))loadBackdrop(Uri.parse(art.backdrop()),width,item);
                applyHeroArt(b,art,viewToken,item);
            });
        }catch(Exception ignored){handler.post(()->{if(root!=null&&viewGeneration==viewToken&&pending==item){title.setText(cleanName);title.setVisibility(View.VISIBLE);}});}});
    }
    private void applyBackdropTint(int tint) {
        if (root == null) return;
        GradientDrawable.Orientation shadeOrient = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT;
        horizontalShade.setBackground(new GradientDrawable(shadeOrient, new int[]{
                tint,
                androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 190),
                androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 20)}));
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                0x00000000,
                androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 100),
                tint}));
    }

    private void loadBackdrop(Uri uri,int width,Item selected){
        int viewToken=viewGeneration;
        String uriKey = uri.toString();
        Picasso.get().load(uri).resize(width,width*9/16).centerCrop().into(backdrop,new com.squareup.picasso.Callback(){
            @Override public void onError(Exception e){if(root!=null&&viewGeneration==viewToken&&pending==selected&&selected.background!=null&&!selected.background.equals(uri))loadBackdrop(selected.background,width,selected);}
            @Override public void onSuccess(){
                if(root==null||viewGeneration!=viewToken||pending!=selected||!(backdrop.getDrawable() instanceof android.graphics.drawable.BitmapDrawable))return;
                Integer cachedTint = backdropTintCache.get(uriKey);
                if (cachedTint != null) {
                    applyBackdropTint(cachedTint);
                    return;
                }
                android.graphics.Bitmap bitmap=((android.graphics.drawable.BitmapDrawable)backdrop.getDrawable()).getBitmap();
                androidx.palette.graphics.Palette.from(bitmap).maximumColorCount(8).generate(palette->{
                    if(root==null||viewGeneration!=viewToken||pending!=selected||palette==null)return;
                    int tint=androidx.core.graphics.ColorUtils.blendARGB(palette.getDominantColor(CinemaTokens.CANVAS),CinemaTokens.CANVAS,0.82f);
                    backdropTintCache.put(uriKey, tint);
                    applyBackdropTint(tint);
                });
            }
        });
    }


    private void openTraktHomeItemMenu(Video video, CinemaWatchStore.TraktItem ti, int rowId) {
        if (!isAdded() || ti == null) return;
        Context c = requireContext();
        Context app = c.getApplicationContext();
        boolean isSeries = "show".equals(ti.mediaType()) || "episode".equals(ti.mediaType()) || ti.season() > 0;
        java.util.List<CinemaContextDialog.Option> options = new java.util.ArrayList<>();
        if (com.meedx.providers.StremioAddons.hasEnabled(c) && ti.imdbId() != null && ti.imdbId().matches("tt[0-9]+")) {
            options.add(new CinemaContextDialog.Option(
                    com.archos.mediacenter.video.R.drawable.cinema_addons,
                    label(c, "تشغيل عبر الإضافات (Nuvio / Stremio)", "Stream via Add-ons"),
                    label(c, "جلب مصادر البث المباشر للعمل", "Browse streams from installed add-ons"),
                    () -> {
                        var meta = new com.meedx.providers.OpenMetadata.Item(ti.title(), ti.year() > 0 ? String.valueOf(ti.year()) : "",
                                "", "", "", ti.imdbId(), null, null, "");
                        CinemaAddonPicker.show(requireActivity(), io, handler, video, meta, Math.max(1, ti.season()), Math.max(1, ti.episode()));
                    }));
        }
        boolean isWatchedRow = rowId == 6;
        Item syntheticItem = new Item(video, null, null, ti.updatedAt() / 1000L, ti);
        options.add(new CinemaContextDialog.Option(
                com.archos.mediacenter.video.R.drawable.cinema_ic_trakt,
                isWatchedRow
                        ? label(c, "تحديد كغير مُشاهَد في Trakt", "Mark as Unwatched on Trakt")
                        : label(c, "تحديد كمُشاهَد في Trakt", "Mark as Watched on Trakt"),
                null,
                () -> {
                    applyOptimisticWatchedToggle(rowId, syntheticItem, !isWatchedRow);
                    watchActionIo.execute(() -> {
                        if (!isWatchedRow) {
                            CinemaWatchStore.recordContinueTombstone(app, ti.itemKey(), "", "", ti.title(),
                                    ti.imdbId(), ti.tmdbId(), ti.season(), ti.episode());
                        }
                        com.meedx.providers.TraktSyncClient.syncHistoryItem(app, isSeries, ti.imdbId(), ti.tmdbId(),
                                Math.max(1, ti.season()), Math.max(1, ti.episode()), !isWatchedRow);
                        if (!isWatchedRow) {
                            com.meedx.providers.TraktSyncClient.removePlaybackProgress(
                                    app, ti.imdbId(), ti.tmdbId(), ti.season(), ti.episode(), ti.title());
                        }
                        try { CinemaWatchStore.syncWithTrakt(app); } catch (Exception ignored) {}
                        handler.post(this::restartActiveLoaders);
                    });
                }));
        if (!isWatchedRow) {
            options.add(new CinemaContextDialog.Option(
                    com.archos.mediacenter.video.R.drawable.cinema_restart,
                    c.getString(com.archos.mediacenter.video.R.string.cinema_remove_from_continue_watching),
                    null,
                    () -> {
                        applyOptimisticRemoveFromContinue(rowId, syntheticItem);
                        Toast.makeText(c, c.getString(com.archos.mediacenter.video.R.string.cinema_removed_from_continue_watching), Toast.LENGTH_SHORT).show();
                        watchActionIo.execute(() -> {
                            CinemaWatchStore.removeTraktContinue(app, ti);
                            handler.post(this::restartActiveLoaders);
                        });
                    }));
        }
        CinemaContextDialog.show(c, com.archos.mediacenter.video.R.drawable.cinema_ic_trakt,
                ti.title(), label(c, "خيارات العرض", "Media Options"), options);
    }

    private void open(Item item,int rowId){
        clearOptimisticRemovedForPlay(item);
        if (item.value instanceof Video v && v.getFilePath() != null && v.getFilePath().startsWith("trakt://")) {
            CinemaWatchStore.TraktItem ti = item.trakt;
            if (ti != null) {
                if (rowId == 1) {
                    openContinueEpisode(ti.title(), ti.imdbId(), ti.tmdbId(), ti.season(), ti.episode(),
                            ti.progressPct(), () -> {
                                var meta = new com.meedx.providers.OpenMetadata.Item(ti.title(),
                                        ti.year() > 0 ? String.valueOf(ti.year()) : "", "", "", "", ti.imdbId(), null, null, "");
                                CinemaAddonPicker.show(requireActivity(), io, handler, v, meta, ti.season(), ti.episode());
                            });
                } else openTraktHomeItemMenu(v, ti, rowId);
                return;
            }
        }
        if(item.stream!=null){
            var stream = item.stream;
            if (rowId == 1 && stream.episode() > 0) {
                double progress = stream.durationMs() > 0 ? stream.positionMs() * 100.0 / stream.durationMs() : 0;
                openContinueEpisode(stream.title(), stream.imdbId(), stream.tmdbId(), stream.season(), stream.episode(),
                        progress, () -> playContinueStream(stream));
            } else {
                playContinueStream(stream);
            }
            return;
        }
        if (rowId == 1 && item.value instanceof Video video && !isSeriesRowItem(video, rowId)) {
            CinemaPlaybackRouter.playVideo(requireActivity(), video, false,
                    CinemaMediaTime.knownResume(video.getId(), video.getFilePath(), video.getResumeMs()));
            return;
        }
        Intent intent;
        if(item.value instanceof Tvshow tvshow){
            intent=new Intent(requireContext(),CinemaSeriesActivity.class);
            intent.putExtra(TvshowFragment.EXTRA_TVSHOW,tvshow);
        }else if(item.value instanceof Video video && isSeriesRowItem(video,rowId)){
            if(rowId==1){
                playEpisodeDirectlyFromContinueWatching(video);
                return;
            }
            long showId=lookupShowIdForVideo(requireContext(),video.getId());
            Tvshow tvshow=convertSeriesToTvshow(video,showId,null,video.getDescriptionBody(),1,video.getFilePath());
            intent=new Intent(requireContext(),CinemaSeriesActivity.class);
            intent.putExtra(TvshowFragment.EXTRA_TVSHOW,tvshow);
        }else{
            intent=new Intent(requireContext(),CinemaDetailsActivity.class);
            intent.putExtra(VideoDetailsFragment.EXTRA_VIDEO,(Video)item.value);
        }
        startActivity(intent);
    }

    private static String continueEpisodeKey(String title, int season, int episode) {
        String normalized = CinemaWatchStore.normalizeTitle(title);
        if (normalized.isEmpty() || season < 0 || episode <= 0) return "";
        return "episode:" + normalized + ":" + season + ":" + episode;
    }

    private void openContinueEpisode(String title, String imdb, long tmdb, int season, int episode,
                                     double progress, Runnable fallback) {
        if (episode <= 0) {
            fallback.run();
            return;
        }
        Context app = requireContext().getApplicationContext();
        io.execute(() -> {
            Video local = CinemaWatchStore.findLocalEpisode(app, title, imdb, tmdb, season, episode);
            handler.post(() -> {
                if (!isAdded() || root == null) return;
                if (local == null) {
                    fallback.run();
                    return;
                }
                long resume = CinemaMediaTime.knownResume(local.getId(), local.getFilePath(), local.getResumeMs());
                long duration = CinemaMediaTime.known(local.getId(), local.getFilePath(), local.getDurationMs());
                if (resume <= 1 && duration > 0 && progress > 0 && progress < 85) {
                    resume = Math.round(duration * progress / 100.0);
                }
                playEpisodeDirectlyFromContinueWatching(local, resume);
            });
        });
    }

    private void playContinueStream(CinemaWatchStore.StreamEntry stream) {
        if (stream.streamKey().startsWith("addon:")) {
            String id = stream.streamKey().substring(6).replaceFirst(":s[0-9]+e[0-9]+$", "");
            Context app = requireContext().getApplicationContext();
            CinemaAddonPicker.request(requireActivity(), io, handler, () -> {
                var identity = CinemaWatchStore.getIdentity(app, stream.streamKey());
                String poster = identity == null ? "" : identity.posterUrl();
                String heading = identity == null || identity.title().isBlank() ? stream.title() : identity.title();
                return new CinemaAddonPicker.Selection(stream.title(), id, stream.tmdbId(),
                        stream.episode() > 0 ? "series" : "movie", stream.season(), stream.episode(), null, poster, heading);
            }, this::isAdded);
            return;
        }
        CinemaPlaybackRouter.playStream(requireContext(), CinemaPlaybackRouter.StreamRequest.basicVod(
                Uri.parse(stream.streamUri()),
                stream.format(),
                stream.title(),
                stream.streamKey(),
                stream.mediaType(),
                stream.imdbId(),
                stream.tmdbId(),
                stream.season(),
                stream.episode(),
                stream.positionMs() > 0 && !stream.removedFromContinue() ? stream.positionMs() : 0L));
    }

    private void playEpisodeDirectlyFromContinueWatching(Video video) {
        if (video == null || !isAdded()) return;
        playEpisodeDirectlyFromContinueWatching(video,
                CinemaMediaTime.knownResume(video.getId(), video.getFilePath(), video.getResumeMs()));
    }

    private void playEpisodeDirectlyFromContinueWatching(Video video, long rawResume) {
        final Context app = requireContext().getApplicationContext();
        final long resumeMs = rawResume > 1 ? rawResume : 0L;
        io.execute(() -> {
            com.archos.mediacenter.video.browser.adapters.object.Episode targetEp =
                    video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep ? ep : null;
            int[] se = targetEp != null
                    ? new int[]{targetEp.getSeasonNumber(), targetEp.getEpisodeNumber()}
                    : CinemaPosters.extractSeasonEpisode(video.getFilePath(), video.getName());
            int targetSeason = se[0];
            int targetEpNum = se[1];
            long showId = lookupShowIdForVideo(app, video.getId());
            List<com.archos.mediacenter.video.browser.adapters.object.Episode> seasonList = new ArrayList<>();
            if (showId > 0) {
                try (Cursor c = app.getContentResolver().query(
                        VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                        com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,
                        VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID + "=? AND "
                                + VideoStore.Video.VideoColumns.SCRAPER_E_SEASON + "=?",
                        new String[]{Long.toString(showId), Integer.toString(targetSeason)},
                        VideoStore.Video.VideoColumns.SCRAPER_E_EPISODE + " ASC, " + VideoStore.Files.FileColumns._ID + " ASC")) {
                    if (c != null) {
                        VideoCursorMapper mapper = new VideoCursorMapper();
                        mapper.bindColumns(c);
                        while (c.moveToNext()) {
                            Object rowObj = mapper.bind(c);
                            if (rowObj instanceof com.archos.mediacenter.video.browser.adapters.object.Episode e) {
                                seasonList.add(e);
                                if (e.getId() == video.getId()) targetEp = e;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            } else {
                String folderPrefix = CinemaPosters.seriesRootPath(video.getFilePath());
                String seriesTitle = CinemaPosters.query(video, true).title();
                String normTarget = seriesTitle.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
                if (folderPrefix != null && !folderPrefix.isBlank()) {
                    String esc = folderPrefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                    try (Cursor c = app.getContentResolver().query(
                            VideoStore.Video.Media.EXTERNAL_CONTENT_URI,
                            com.archos.mediacenter.video.browser.loader.VideoLoader.mProjection,
                            VideoStore.MediaColumns.DATA + " LIKE ? ESCAPE '!'",
                            new String[]{esc},
                            VideoStore.MediaColumns.DATA + " ASC LIMIT 400")) {
                        if (c != null) {
                            VideoCursorMapper mapper = new VideoCursorMapper();
                            mapper.bindColumns(c);
                            while (c.moveToNext()) {
                                Object rowObj = mapper.bind(c);
                                if (!(rowObj instanceof Video rowVideo)) continue;
                                if (!isSeriesOrEpisodeFile(rowVideo, rowVideo.getFilePath())) continue;
                                String cand = CinemaPosters.query(rowVideo, true).title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
                                if (!normTarget.isEmpty() && !cand.equals(normTarget) && !cand.contains(normTarget) && !normTarget.contains(cand)) continue;
                                int[] rowSe = CinemaPosters.extractSeasonEpisode(rowVideo.getFilePath(), rowVideo.getName());
                                if (rowSe[0] != targetSeason) continue;
                                String epLabel = String.format(Locale.US, "S%02dE%02d", rowSe[0], rowSe[1]);
                                com.archos.mediacenter.video.browser.adapters.object.Episode syn =
                                        rowVideo instanceof com.archos.mediacenter.video.browser.adapters.object.Episode e ? e
                                                : new com.archos.mediacenter.video.browser.adapters.object.Episode(
                                                rowVideo.getId(), rowVideo.getId(), rowSe[0], rowSe[1], epLabel,
                                                0L, 0f, null, rowVideo.getDescriptionBody(),
                                                seriesTitle.isBlank() ? video.getName() : seriesTitle,
                                                rowVideo.getFilePath(), rowVideo.getPosterUri(), rowVideo.getPosterUri(),
                                                rowVideo.getDurationMs(), rowVideo.getResumeMs(),
                                                rowVideo.is3D() ? 1 : 0, rowVideo.getGuessedDefinition(), rowVideo.isWatched(),
                                                rowVideo.isTraktLibrary(), rowVideo.hasSubs(), rowVideo.isUserHidden(),
                                                0L, rowVideo.getLastPlayed(), -1, -1,
                                                rowVideo.getCalculatedBestAudioFormat(), rowVideo.getCalculatedVideoFormat(),
                                                rowVideo.getGuessedAudioFormat(), rowVideo.getGuessedVideoFormat(),
                                                rowVideo.getCalculatedBestAudiotrack(), rowVideo.getOccurencies(), rowVideo.getSize());
                                seasonList.add(syn);
                                if (syn.getId() == video.getId()) targetEp = syn;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (targetEp == null) {
                String epLabel = String.format(Locale.US, "S%02dE%02d", targetSeason, targetEpNum);
                String sTitle = CinemaPosters.query(video, true).title();
                targetEp = new com.archos.mediacenter.video.browser.adapters.object.Episode(
                        video.getId(), Math.max(0L, showId), targetSeason, targetEpNum, epLabel,
                        0L, 0f, null, video.getDescriptionBody(),
                        sTitle.isBlank() ? video.getName() : sTitle,
                        video.getFilePath(), video.getPosterUri(), video.getPosterUri(),
                        video.getDurationMs(), video.getResumeMs(),
                        video.is3D() ? 1 : 0, video.getGuessedDefinition(), video.isWatched(),
                        video.isTraktLibrary(), video.hasSubs(), video.isUserHidden(),
                        0L, video.getLastPlayed(), -1, -1,
                        video.getCalculatedBestAudioFormat(), video.getCalculatedVideoFormat(),
                        video.getGuessedAudioFormat(), video.getGuessedVideoFormat(),
                        video.getCalculatedBestAudiotrack(), video.getOccurencies(), video.getSize());
            }
            seasonList.sort(java.util.Comparator
                    .comparingInt(com.archos.mediacenter.video.browser.adapters.object.Episode::getEpisodeNumber)
                    .thenComparingLong(com.archos.mediacenter.video.browser.adapters.object.Episode::getId));
            if (seasonList.isEmpty()) seasonList.add(targetEp);
            final com.archos.mediacenter.video.browser.adapters.object.Episode launchEp = targetEp;
            final String showTitle = launchEp.getShowName() != null && !launchEp.getShowName().isBlank()
                    ? launchEp.getShowName()
                    : CinemaPosters.query(video, true).title();
            handler.post(() -> {
                if (!isAdded() || root == null) return;
                CinemaPlaybackRouter.playEpisode(
                        requireContext(), launchEp, seasonList, showTitle, null,
                        resumeMs <= 0L, false, resumeMs, -1);
            });
        });
    }
    private boolean isItemWatched(Item item){
        if (item == null) return false;
        if (!optimisticWatchedState.isEmpty()) {
            for (String k : matchKeysForItem(item)) {
                Boolean ov = optimisticWatchedState.get(k);
                if (ov != null) return ov;
            }
        }
        if(item.stream!=null)return item.stream.watched();
        if(item.trakt!=null&&"watched".equals(item.trakt.listType()))return true;
        if(item.value instanceof Video v){
            long res=CinemaMediaTime.knownResume(v.getId(),v.getFilePath(),v.getResumeMs());
            return v.isWatched()||CinemaPlaybackRouter.isCompletedBookmark(res);
        }
        if(item.value instanceof Tvshow s){
            return s.isWatched();
        }
        return false;
    }
    private void itemContextMenu(int rowId,Item item){
        if (item.value instanceof Video tvVid && tvVid.getFilePath() != null && tvVid.getFilePath().startsWith("trakt://")) {
            CinemaWatchStore.TraktItem ti = item.trakt;
            if (ti != null) {
                openTraktHomeItemMenu(tvVid, ti, rowId);
                return;
            }
        }
        if(item.stream!=null){
            var s=item.stream;
            Context c=requireContext();
            boolean w=isItemWatched(item);
            java.util.List<CinemaContextDialog.Option> options = new java.util.ArrayList<>();
            options.add(new CinemaContextDialog.Option(
                    w ? com.archos.mediacenter.video.R.drawable.cinema_restart : com.archos.mediacenter.video.R.drawable.cinema_check,
                    c.getString(w?com.archos.mediacenter.video.R.string.cinema_mark_as_unwatched:com.archos.mediacenter.video.R.string.cinema_mark_as_watched_cinemadetailsactivity),
                    null,
                    () -> {
                        applyOptimisticWatchedToggle(rowId, item, !w);
                        watchActionIo.execute(() -> {
                            Context app = c.getApplicationContext();
                            CinemaWatchStore.markStreamWatched(app, s.streamKey(), !w);
                            if (!w) {
                                CinemaWatchStore.removePlaybackProgressFromTraktAsync(
                                        app, 0L, s.streamUri(), s.title(), s.imdbId(), s.tmdbId(), s.season(), s.episode());
                            }
                            CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForStream(
                                    CinemaWatchStore.getStream(app, s.streamKey())));
                            CinemaWatchStore.syncWithTraktAsync(app);
                            handler.post(this::restartActiveLoaders);
                        });
                    }));
            if (rowId == 1 || (!s.removedFromContinue() && s.positionMs() > 0)) {
                options.add(new CinemaContextDialog.Option(
                        com.archos.mediacenter.video.R.drawable.cinema_restart,
                        c.getString(com.archos.mediacenter.video.R.string.cinema_remove_from_continue_watching),
                        null,
                        () -> {
                            applyOptimisticRemoveFromContinue(rowId, item);
                            Toast.makeText(c, c.getString(com.archos.mediacenter.video.R.string.cinema_removed_from_continue_watching), Toast.LENGTH_SHORT).show();
                            watchActionIo.execute(() -> {
                                Context app = c.getApplicationContext();
                                CinemaWatchStore.removeStreamFromContinue(app, s.streamKey());
                                CinemaWatchStore.clearMatchingContinue(app, s.imdbId(), s.tmdbId(), s.season(), s.episode());
                                CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForStream(
                                        CinemaWatchStore.getStream(app, s.streamKey())));
                                CinemaWatchStore.removePlaybackProgressFromTraktAsync(
                                        app, 0L, s.streamUri(), s.title(), s.imdbId(), s.tmdbId(), s.season(), s.episode());
                                handler.post(this::restartActiveLoaders);
                            });
                        }));
            }
            CinemaContextDialog.show(c, com.archos.mediacenter.video.R.drawable.cinema_more,
                    s.title(), label(c, "خيارات العرض", "Media Options"), options);
            return;
        }
        if(!(item.value instanceof Video video)){
            Context c=requireContext();
            java.util.List<CinemaContextDialog.Option> options = new java.util.ArrayList<>();
            options.add(new CinemaContextDialog.Option(
                    com.archos.mediacenter.video.R.drawable.cinema_play_movie,
                    c.getString(com.archos.mediacenter.video.R.string.cinema_open_details),
                    label(c, "عرض المواسم والحلقات والتقييمات", "Browse seasons, episodes & ratings"),
                    () -> open(item, rowId)));
            options.add(new CinemaContextDialog.Option(
                    com.archos.mediacenter.video.R.drawable.cinema_ic_posters,
                    c.getString(com.archos.mediacenter.video.R.string.cinema_refresh_poster_choose_another_poster),
                    label(c, "اختيار بوستر بديل وتحديث الغلاف", "Choose another poster artwork"),
                    () -> choosePoster(item, rowId)));
            if (rowId != 1) {
                LibraryRow source = libraryRows.get(rowId);
                String rowTitle = source == null ? rowTitle(c, rowId) : source.name();
                options.add(new CinemaContextDialog.Option(
                        com.archos.mediacenter.video.R.drawable.cinema_ic_sort,
                        label(c, "فرز الصف", "Sort By"),
                        sortPillLabel(c, rowSortMode(rowId)),
                        () -> showSortDialog(rowId, rowTitle)));
            }
            CinemaContextDialog.show(c, com.archos.mediacenter.video.R.drawable.cinema_more,
                    CinemaPosters.cleanDisplayTitle(item.value, CinemaUi.arabic(c)),
                    label(c, "خيارات العرض", "Media Options"), options);
            return;
        }
        Context c=requireContext();
        boolean currentlyWatched=isItemWatched(item);
        long res=CinemaMediaTime.knownResume(video.getId(),video.getFilePath(),video.getResumeMs());
        boolean canRemoveContinue=rowId==1||(res>0&&!CinemaPlaybackRouter.isCompletedBookmark(res));
        java.util.List<CinemaContextDialog.Option> options = new java.util.ArrayList<>();
        options.add(new CinemaContextDialog.Option(
                com.archos.mediacenter.video.R.drawable.cinema_play_movie,
                c.getString(com.archos.mediacenter.video.R.string.cinema_open_details),
                label(c, "عرض القصة وطاقم التمثيل وخيارات التشغيل", "Synopsis, cast, ratings & playback options"),
                () -> open(item, rowId)));
        options.add(new CinemaContextDialog.Option(
                currentlyWatched ? com.archos.mediacenter.video.R.drawable.cinema_restart : com.archos.mediacenter.video.R.drawable.cinema_check,
                c.getString(currentlyWatched?com.archos.mediacenter.video.R.string.cinema_mark_as_unwatched:com.archos.mediacenter.video.R.string.cinema_mark_as_watched_cinemadetailsactivity),
                null,
                () -> {
                    applyOptimisticWatchedToggle(rowId, item, !currentlyWatched);
                    Context app=c.getApplicationContext();
                    watchActionIo.execute(()->{
                        try{
                            if(currentlyWatched)com.archos.mediacenter.video.utils.DbUtils.markAsNotRead(app,video);
                            else {
                                com.archos.mediacenter.video.utils.DbUtils.markAsRead(app,video);
                                CinemaWatchStore.removePlaybackProgressFromTraktAsync(
                                        app, video.getId(), video.getFilePath(), video.getName(), "", 0L, 0, 0);
                            }
                            CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForVideo(
                                    app,video,0L,!currentlyWatched,currentlyWatched));
                            CinemaWatchStore.pushVideoWatchedToTraktAsync(app, video, !currentlyWatched);
                        }catch(Exception ignored){}
                        handler.post(this::restartActiveLoaders);
                    });
                }));
        if(canRemoveContinue){
            options.add(new CinemaContextDialog.Option(
                    com.archos.mediacenter.video.R.drawable.cinema_restart,
                    c.getString(com.archos.mediacenter.video.R.string.cinema_remove_from_continue_watching),
                    label(c, "مسح نقطة التوقف الحالية وإعادة الوقت للبداية", "Clear saved progress and reset playback time"),
                    () -> {
                        boolean preserveWatched = currentlyWatched;
                        applyOptimisticRemoveFromContinue(rowId, item);
                        Toast.makeText(c,c.getString(com.archos.mediacenter.video.R.string.cinema_removed_from_continue_watching),Toast.LENGTH_SHORT).show();
                        Context app=c.getApplicationContext();
                        watchActionIo.execute(()->{
                            try{
                                CinemaWatchStore.recordContinueTombstone(
                                        app, "", video.getId() > 0 ? "v:" + video.getId() : "",
                                        video.getFilePath(), video.getName(), "",
                                        video instanceof Movie m ? m.getOnlineId() : 0L,
                                        video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep ? ep.getSeasonNumber() : 0,
                                        video instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep ? ep.getEpisodeNumber() : 0);
                                com.archos.mediacenter.video.utils.DbUtils.clearResumeOnly(app, video);
                                CinemaLinkServer.broadcastWatchSync(CinemaWatchStore.buildSyncRecordForVideo(
                                        app,video,0L,preserveWatched,true));
                                var identity = CinemaWatchStore.resolvePlaybackDescriptor(
                                        app, video.getId(), video.getFilePath(), video.getName());
                                CinemaWatchStore.recordContinueTombstone(
                                        app, identity.canonicalKey(), identity.streamKey(),
                                        video.getFilePath(), video.getName(), identity.imdbId(), identity.tmdbId(),
                                        identity.season(), identity.episode());
                                CinemaWatchStore.clearMatchingContinue(app, identity.imdbId(), identity.tmdbId(),
                                        identity.season(), identity.episode());
                                CinemaWatchStore.removePlaybackProgressFromTraktAsync(app, video.getId(),
                                        video.getFilePath(), video.getName(), identity.imdbId(), identity.tmdbId(),
                                        identity.season(), identity.episode());
                            }catch(Exception ignored){}
                            handler.post(this::restartActiveLoaders);
                        });
                    }));
        }
        options.add(new CinemaContextDialog.Option(
                com.archos.mediacenter.video.R.drawable.cinema_more,
                c.getString(com.archos.mediacenter.video.R.string.cinema_more_details_and_advanced_actions),
                label(c, "مواصفات الملف والمسارات والإجراءات المتقدمة", "Codec info, tracks, add-on streams & file actions"),
                () -> {
                    if (!isAdded() || getActivity() == null) return;
                    CinemaMediaMoreDialog.with(requireActivity())
                            .video(video)
                            .displayTitle(CinemaPosters.cleanDisplayTitle(video, CinemaUi.arabic(c)))
                            .onOpenDetails(() -> open(item, rowId))
                            .onPlayResume(() -> CinemaPlaybackRouter.playVideo(requireActivity(), video, false,
                                    CinemaMediaTime.knownResume(video.getId(), video.getFilePath(), video.getResumeMs())))
                            .onPlayFromBeginning(() -> CinemaPlaybackRouter.playVideo(requireActivity(), video, true, 0L))
                            .onChangePoster(() -> choosePoster(item, rowId))
                            .onStateChanged(this::restartActiveLoaders)
                            .show();
                }));
        options.add(new CinemaContextDialog.Option(
                com.archos.mediacenter.video.R.drawable.cinema_ic_posters,
                c.getString(com.archos.mediacenter.video.R.string.cinema_refresh_poster_choose_another_poster),
                label(c, "اختيار بوستر بديل وتحديث الغلاف", "Choose another poster artwork"),
                () -> choosePoster(item, rowId)));
        if (rowId != 1) {
            LibraryRow source = libraryRows.get(rowId);
            String rowTitle = source == null ? rowTitle(c, rowId) : source.name();
            options.add(new CinemaContextDialog.Option(
                    com.archos.mediacenter.video.R.drawable.cinema_ic_sort,
                    label(c, "فرز الصف", "Sort By"),
                    sortPillLabel(c, rowSortMode(rowId)),
                    () -> showSortDialog(rowId, rowTitle)));
        }
        CinemaContextDialog.show(c, com.archos.mediacenter.video.R.drawable.cinema_more,
                CinemaPosters.cleanDisplayTitle(video, CinemaUi.arabic(c)),
                label(c, "خيارات العرض", "Media Options"), options);
    }

    private String rowSortPrefKey(int id) {
        LibraryRow row = libraryRows.get(id);
        return "cinema_sort_row_" + (row != null ? ("cat_" + row.category() + "_" + row.name()) : ("id_" + id));
    }

    private String rowSortMode(int id) {
        return prefs == null ? SORT_LATEST : prefs.getString(rowSortPrefKey(id), SORT_LATEST);
    }

    private String sortPillLabel(Context c, String mode) {
        return switch (mode) {
            case SORT_OLDEST -> label(c, "الأقدم إضافةً", "Oldest Added");
            case SORT_NAME -> label(c, "الاسم (أ – ي)", "Title (A–Z)");
            case SORT_NAME_DESC -> label(c, "الاسم (ي – أ)", "Title (Z–A)");
            case SORT_YEAR -> label(c, "سنة الإصدار (الأحدث)", "Release Year (Newest)");
            case SORT_YEAR_OLDEST -> label(c, "سنة الإصدار (الأقدم)", "Release Year (Oldest)");
            case SORT_RATING -> label(c, "الأعلى تقييماً", "Top Rated");
            case SORT_WATCHED -> label(c, "الأعمال المشاهدة", "Watched First");
            default -> label(c, "الأحدث إضافةً", "Newest Added");
        };
    }

    private List<Item> sortRowItemsForBind(int id, List<Item> input) {
        return id == 1 ? input : applyRowSort(id, input);
    }

    private List<Item> applyRowSort(int id, List<Item> input) {
        Context c = getContext();
        if (c == null) return input == null ? List.of() : input;
        Context app = c.getApplicationContext();
        String lang = MetadataLanguage.resolve(app);
        return applyRowSortWithApp(id, input, app, lang, lang.startsWith("ar"));
    }

    private List<Item> applyRowSortWithApp(int id, List<Item> input, Context app, String lang, boolean arabicMode) {
        if (input == null || input.size() <= 1) return input == null ? List.of() : input;
        String mode = rowSortMode(id);
        List<Item> sorted = new ArrayList<>(input);
        switch (mode) {
            case SORT_OLDEST -> sorted.sort((a, b) -> Long.compare(a.dateAdded, b.dateAdded));
            case SORT_NAME, SORT_NAME_DESC -> {
                Map<Item, String> titles = new java.util.IdentityHashMap<>(sorted.size());
                for (Item it : sorted) {
                    String pk = lang + ":" + mediaKey(it.value);
                    CinemaArtwork.Art art = app != null ? CinemaArtwork.getCachedHero(app, pk) : null;
                    String t = (art != null && art.title() != null && !art.title().isBlank())
                            ? art.title() : CinemaPosters.cleanDisplayTitle(it.value, arabicMode);
                    titles.put(it, t == null ? "" : t);
                }
                boolean desc = SORT_NAME_DESC.equals(mode);
                sorted.sort((a, b) -> desc
                        ? titles.get(b).compareToIgnoreCase(titles.get(a))
                        : titles.get(a).compareToIgnoreCase(titles.get(b)));
            }
            case SORT_YEAR, SORT_YEAR_OLDEST -> {
                Map<Item, Integer> years = new java.util.IdentityHashMap<>(sorted.size());
                for (Item it : sorted) {
                    String pk = lang + ":" + mediaKey(it.value);
                    CinemaArtwork.Art art = app != null ? CinemaArtwork.getCachedHero(app, pk) : null;
                    int y = (art != null && art.year() > 0) ? art.year()
                            : (it.value instanceof Movie m && m.getYear() > 0 ? m.getYear()
                            : (it.value instanceof Tvshow s && s.getYear() > 0 ? s.getYear()
                            : CinemaPosters.query(it.value).year()));
                    years.put(it, y);
                }
                if (SORT_YEAR.equals(mode)) {
                    sorted.sort((a, b) -> {
                        int yCmp = Integer.compare(years.get(b), years.get(a));
                        return yCmp != 0 ? yCmp : Long.compare(b.dateAdded, a.dateAdded);
                    });
                } else {
                    sorted.sort((a, b) -> {
                        int ya = years.get(a);
                        int yb = years.get(b);
                        if (ya <= 0 && yb > 0) return 1;
                        if (yb <= 0 && ya > 0) return -1;
                        int yCmp = Integer.compare(ya, yb);
                        return yCmp != 0 ? yCmp : Long.compare(b.dateAdded, a.dateAdded);
                    });
                }
            }
            case SORT_RATING -> {
                Map<Item, Double> ratings = new java.util.IdentityHashMap<>(sorted.size());
                for (Item it : sorted) {
                    String pk = lang + ":" + mediaKey(it.value);
                    CinemaArtwork.Art art = app != null ? CinemaArtwork.getCachedHero(app, pk) : null;
                    double r = (art != null && art.rating() > 0) ? art.rating()
                            : (it.value instanceof Movie m && m.getRating() > 0 ? m.getRating()
                            : (it.value instanceof Tvshow s && s.getRating() > 0 ? s.getRating() : 0.0));
                    ratings.put(it, r);
                }
                sorted.sort((a, b) -> {
                    int rCmp = Double.compare(ratings.get(b), ratings.get(a));
                    return rCmp != 0 ? rCmp : Long.compare(b.dateAdded, a.dateAdded);
                });
            }
            case SORT_WATCHED -> sorted.sort((a, b) -> {
                int wCmp = Boolean.compare(isItemWatched(b), isItemWatched(a));
                return wCmp != 0 ? wCmp : Long.compare(b.dateAdded, a.dateAdded);
            });
            default -> sorted.sort((a, b) -> Long.compare(b.dateAdded, a.dateAdded));
        }
        return sorted;
    }

    private void populateCachedItemFields(Item item, Context app, String lang, boolean arabicMode, boolean uiArabic, int cardWidth) {
        String pk = lang + ":" + mediaKey(item.value);
        item.cachedPosterKey = pk;
        CinemaArtwork.Art art = app != null ? CinemaArtwork.getCachedHero(app, pk) : null;
        if (item.stream != null && item.stream.episode() > 0) {
            String episode = stripGenericEpisodeTokens(item.stream.title());
            item.cachedCardTitle = episode.isBlank() ? item.value.getName() : episode;
        } else if (item.stream != null) {
            item.cachedCardTitle = item.stream.title();
        } else {
            item.cachedCardTitle = (art != null && art.title() != null && !art.title().isBlank())
                    ? art.title() : CinemaPosters.cleanDisplayTitle(item.value, arabicMode);
        }
        item.cachedWatched = isItemWatched(item);
        int progress = 0;
        if (item.stream != null) {
            if (!item.stream.removedFromContinue() && item.stream.durationMs() > 0 && item.stream.positionMs() > 0) {
                progress = (int) Math.min(cardWidth, (long) cardWidth * item.stream.positionMs() / item.stream.durationMs());
            }
        } else if (item.value instanceof Video v) {
            long dur = CinemaMediaTime.known(v.getId(), v.getFilePath(), v.getDurationMs());
            long res = CinemaMediaTime.knownResume(v.getId(), v.getFilePath(), v.getResumeMs());
            if (dur > 0 && res > 0 && !CinemaPlaybackRouter.isCompletedBookmark(res)) {
                progress = (int) Math.min(cardWidth, (long) cardWidth * res / dur);
            }
        }
        item.cachedProgressWidth = progress;
        int badgeSeason = 0, badgeEp = 0;
        if (item.stream != null && "episode".equalsIgnoreCase(item.stream.mediaType()) && item.stream.episode() > 0) {
            badgeSeason = item.stream.season();
            badgeEp = item.stream.episode();
        } else if (item.value instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep) {
            badgeSeason = ep.getSeasonNumber();
            badgeEp = ep.getEpisodeNumber();
        } else if (item.value instanceof Video v && isSeriesOrEpisodeFile(v, v.getFilePath())) {
            int[] se = CinemaPosters.findSeasonEpisode(v.getFilePath(), v.getName());
            if (se[1] > 0) {
                badgeSeason = Math.max(1, se[0]);
                badgeEp = se[1];
            }
        }
        item.cachedEpisodeTag = badgeEp > 0
                ? (uiArabic ? String.format(Locale.US, "م%d · ح%d", badgeSeason, badgeEp)
                            : String.format(Locale.US, "S%02d E%02d", badgeSeason, badgeEp))
                : null;
    }

    private static androidx.recyclerview.widget.DiffUtil.DiffResult computePostersDiff(List<Item> oldItems, List<Item> newItems) {
        return androidx.recyclerview.widget.DiffUtil.calculateDiff(
                new androidx.recyclerview.widget.DiffUtil.Callback() {
                    @Override public int getOldListSize() { return oldItems.size(); }
                    @Override public int getNewListSize() { return newItems.size(); }
                    @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                        return itemKey(oldItems.get(oldPos)).equals(itemKey(newItems.get(newPos)));
                    }
                    @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                        Item a = oldItems.get(oldPos);
                        Item b = newItems.get(newPos);
                        if (!java.util.Objects.equals(a.cachedPosterKey, b.cachedPosterKey)) return false;
                        if (!java.util.Objects.equals(a.background, b.background)) return false;
                        if (!java.util.Objects.equals(a.cachedCardTitle, b.cachedCardTitle)) return false;
                        if (a.cachedWatched != b.cachedWatched) return false;
                        if (a.cachedProgressWidth != b.cachedProgressWidth) return false;
                        return java.util.Objects.equals(a.cachedEpisodeTag, b.cachedEpisodeTag);
                    }
                    @Override public Object getChangePayload(int oldPos, int newPos) {
                        Item a = oldItems.get(oldPos);
                        Item b = newItems.get(newPos);
                        if (java.util.Objects.equals(a.cachedPosterKey, b.cachedPosterKey)
                                && java.util.Objects.equals(a.background, b.background)) {
                            return Posters.PAYLOAD_STATE;
                        }
                        return null;
                    }
                }, true);
    }

    private void showSortDialog(int rowId, String rowTitle) {
        Context c = requireContext();
        String curMode = rowSortMode(rowId);
        String[] modes = {
                SORT_LATEST, SORT_OLDEST, SORT_NAME, SORT_NAME_DESC,
                SORT_YEAR, SORT_YEAR_OLDEST, SORT_RATING, SORT_WATCHED
        };
        int[] icons = {
                com.archos.mediacenter.video.R.drawable.cinema_ic_timer,
                com.archos.mediacenter.video.R.drawable.cinema_ic_timer,
                com.archos.mediacenter.video.R.drawable.cinema_ic_alpha,
                com.archos.mediacenter.video.R.drawable.cinema_ic_alpha,
                com.archos.mediacenter.video.R.drawable.cinema_ic_calendar,
                com.archos.mediacenter.video.R.drawable.cinema_ic_calendar,
                com.archos.mediacenter.video.R.drawable.cinema_ic_star,
                com.archos.mediacenter.video.R.drawable.cinema_check
        };
        String[] titles = {
                label(c, "الأحدث إضافةً", "Newest Added"),
                label(c, "الأقدم إضافةً", "Oldest Added"),
                label(c, "الاسم (أ – ي)", "Title (A–Z)"),
                label(c, "الاسم (ي – أ)", "Title (Z–A)"),
                label(c, "سنة الإصدار: الأحدث أولاً", "Release Year: Newest First"),
                label(c, "سنة الإصدار: الأقدم أولاً", "Release Year: Oldest First"),
                label(c, "الأعلى تقييماً", "Top Rated"),
                label(c, "الأعمال المشاهدة أولاً", "Watched First")
        };
        String[] subtitles = {
                label(c, "أحدث الملفات المضافة أولاً (الافتراضي)", "Most recently added items first (Default)"),
                label(c, "أقدم الملفات المضافة أولاً", "Earliest added items first"),
                label(c, "ترتيب أبجدي تصاعدي حسب العنوان", "Alphabetical order ascending"),
                label(c, "ترتيب أبجدي تنازلي حسب العنوان", "Alphabetical order descending"),
                label(c, "الأعمال الأحدث إصداراً أولاً", "Newest release year first"),
                label(c, "الأعمال الأقدم إصداراً أولاً", "Oldest release year first"),
                label(c, "ترتيب تنازلي حسب تقييم العمل", "Highest rated titles first"),
                label(c, "عرض الأعمال التي تمت مشاهدتها في المقدمة", "Group watched titles first with exact order")
        };
        List<CinemaContextDialog.Option> options = new ArrayList<>(modes.length);
        for (int i = 0; i < modes.length; i++) {
            final String chosen = modes[i];
            options.add(new CinemaContextDialog.Option(
                    icons[i],
                    titles[i],
                    subtitles[i],
                    chosen.equals(curMode),
                    () -> {
                        prefs.edit().putString(rowSortPrefKey(rowId), chosen).apply();
                        List<Item> existing = data.get(rowId);
                        if (existing != null) {
                            List<Item> sorted = applyRowSort(rowId, existing);
                            data.put(rowId, sorted);
                            RecyclerView list = lists.get(rowId);
                            if (list != null && list.getAdapter() instanceof Posters adapter) {
                                adapter.replace(sorted);
                                list.scrollToPosition(0);
                            }
                        }
                    }));
        }
        CinemaContextDialog.show(
                c,
                com.archos.mediacenter.video.R.drawable.cinema_ic_sort,
                label(c, "فرز الصف", "Sort By"),
                rowTitle,
                options);
    }
    private void bindPoster(PosterHolder holder, Item item, Uri target, Uri fallback) {
        bindPosterWithRetry(holder, item, target, fallback, 0);
    }

    private void bindPosterWithRetry(PosterHolder holder, Item item, Uri target, Uri fallback, int attempt) {
        if (target == null) return;
        String key = posterKey(item.value);
        Context app = requireContext().getApplicationContext();
        int decodeW = Math.max(1, Math.min(posterWidth(), 300));
        int decodeH = Math.max(1, Math.min(posterHeight(), 450));
        Picasso.get().load(target).resize(decodeW, decodeH).centerCrop().noFade().noPlaceholder()
                .into(holder.image, new com.squareup.picasso.Callback() {
                    @Override public void onSuccess() {
                        if (key.equals(holder.image.getTag())) {
                            holder.name.setVisibility(View.GONE);
                            if (holder.image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable bd) {
                                CinemaArtwork.putMemoryPosterBitmap(target, bd.getBitmap());
                            }
                        }
                    }

                    @Override public void onError(Exception error) {
                        if (root == null || !key.equals(holder.image.getTag())) return;
                        if (attempt < 1 && !"file".equals(target.getScheme())) {
                            handler.postDelayed(() -> {
                                if (root != null && key.equals(holder.image.getTag())) {
                                    bindPosterWithRetry(holder, item, target, fallback, attempt + 1);
                                }
                            }, 1200L);
                            return;
                        }
                        CinemaArtwork.removeCachedPoster(app, key, target);
                        android.util.Log.i(CinemaPosters.TAG, "Card image failed for " + key);
                        if (fallback != null && !fallback.equals(target)) bindPosterWithRetry(holder, item, fallback, null, 0);
                        if (failedPosterLoads.add(key)) queuePoster(item, true, false);
                    }
                });
    }

    private long posterOnlineId(Context app, Base item) {
        if (item instanceof Movie movie) return movie.getOnlineId();
        if (item instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep && ep.getOnlineId() > 0) return ep.getOnlineId();
        if (item instanceof Tvshow) {
            var tags = item.getFullScraperTags(app);
            if (tags != null) return tags.getOnlineId();
        }
        return 0;
    }

    private void queuePoster(Item item, boolean force) {
        queuePoster(item, force, true);
    }

    private void queuePoster(Item item, boolean force, boolean deduplicate) {
        String key = posterKey(item.value);
        if (deduplicate && !attemptedPosters.add(key)) return;
        if (!force && !pendingPosters.add(key)) {
            attemptedPosters.remove(key);
            return;
        }
        Context app = requireContext().getApplicationContext();
        int viewToken = viewGeneration;
        String language = MetadataLanguage.resolve(requireContext());
        boolean arabicMode = language.startsWith("ar");
        CinemaPosters.QUEUE.execute(() -> {
            try {
                Uri cached = CinemaArtwork.getCachedPoster(app, key, null);
                Uri original = item.value.getPosterUri();
                boolean rejectOriginal = !arabicMode && hasArabicScrapedData(item.value);
                if (!force && CinemaPosters.valid(cached)) return;
                if (!force && item.stream != null) {
                    var sid = CinemaWatchStore.getIdentity(app, item.stream.streamKey());
                    if (sid == null && !item.stream.streamUri().isBlank()) {
                        sid = CinemaWatchStore.getIdentityByUri(app, item.stream.streamUri());
                    }
                    if (sid != null && sid.posterUrl() != null && sid.posterUrl().startsWith("http")) {
                        try {
                            Uri savedFromIdentity = CinemaPosters.save(app, key, Uri.parse(sid.posterUrl()));
                            if (savedFromIdentity != null) {
                                posterChanged(key, savedFromIdentity, viewToken);
                                return;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                Uri localized = null;
                long onlineId = posterOnlineId(app, item.value);
                if (onlineId <= 0 && item.stream != null && item.stream.tmdbId() > 0) onlineId = item.stream.tmdbId();
                if (onlineId <= 0 && item.trakt != null && item.trakt.tmdbId() > 0) onlineId = item.trakt.tmdbId();
                boolean isShow = item.value instanceof Tvshow
                        || (item.stream != null && ("episode".equalsIgnoreCase(item.stream.mediaType()) || item.stream.episode() > 0))
                        || (item.trakt != null && ("episode".equalsIgnoreCase(item.trakt.mediaType()) || "show".equalsIgnoreCase(item.trakt.mediaType()) || item.trakt.episode() > 0))
                        || isSeriesOrEpisodeFile(item.value, null);
                try {
                    var art = CinemaArtwork.loadForItem(app, item.value, onlineId, isShow, language);
                    if (art != null) {
                        CinemaArtwork.putCachedHero(app, key, art);
                        if (art.poster() != null) localized = CinemaPosters.save(app, key, Uri.parse(art.poster()));
                    }
                } catch (Exception ignored) { /* Fall back to search or indexed file artwork. */ }
                if (localized != null) {
                    posterChanged(key, localized, viewToken);
                    return;
                }
                if (cached != null) CinemaArtwork.removeCachedPoster(app, key, cached);
                Uri repaired = null;
                var query = CinemaPosters.query(item.value, isShow);
                if (query.title().isBlank() && item.stream != null) {
                    query = CinemaPosters.parse(item.stream.title(), 0, isShow);
                } else if (query.title().isBlank() && item.trakt != null) {
                    query = CinemaPosters.parse(item.trakt.title(), item.trakt.year(), isShow);
                }
                android.util.Log.i(CinemaPosters.TAG, "Searching " + key + " " + query.title() + " (" + query.year() + ")");
                for (var choice : CinemaPosters.search(app, query, onlineId, false)) {
                    if (!choice.matches() && item.stream == null && item.trakt == null) continue;
                    try {
                        repaired = CinemaPosters.save(app, key, choice);
                        if (item.stream != null) {
                            String fullPoster = choice.path().startsWith("https://")
                                    ? choice.path() : "https://image.tmdb.org/t/p/w500" + choice.path();
                            CinemaWatchStore.saveIdentity(app, item.stream.streamKey(), item.stream.streamUri(),
                                    item.stream.mediaType(), !choice.imdbId().isEmpty() ? choice.imdbId() : item.stream.imdbId(),
                                    choice.id() > 0 ? choice.id() : item.stream.tmdbId(),
                                    item.stream.season(), item.stream.episode(),
                                    choice.title(), choice.parsedYear(), fullPoster, choice.backdrop(), choice.provider());
                        } else {
                            CinemaWatchStore.saveForBase(app, item.value, choice.imdbId(), choice.id(),
                                    choice.title(), choice.parsedYear(), choice.path(), choice.backdrop(), choice.provider());
                        }
                        android.util.Log.i(CinemaPosters.TAG, "Saved " + key + " " + choice.title() + " (" + choice.year() + ")");
                        break;
                    } catch (java.io.IOException error) {
                        android.util.Log.i(CinemaPosters.TAG, "Provider image unavailable for " + key);
                    }
                }
                if (repaired != null) {
                    posterChanged(key, repaired, viewToken);
                    return;
                }
                if (!force && !rejectOriginal && CinemaPosters.valid(original)) {
                    posterChanged(key, original, viewToken);
                    return;
                }
                android.util.Log.i(CinemaPosters.TAG, "No matching poster for " + key);
            } catch (Exception error) {
                android.util.Log.w(CinemaPosters.TAG, "Poster repair failed for " + key + ": "
                        + error.getClass().getSimpleName() + (error.getClass().getSimpleName().equals("HttpFailure") ? " " + error.getMessage() : ""));
            } finally {
                if (!force) pendingPosters.remove(key);
            }
        });
    }

    private void posterChanged(String key, Uri poster, int viewToken) {
        Picasso.get().invalidate(poster);
        handler.post(() -> {
            if (root == null || viewGeneration != viewToken) return;
            for (RecyclerView list : lists.values()) {
                if (list.getAdapter() instanceof Posters adapter) {
                    int position = adapter.findPoster(key);
                    if (position >= 0) adapter.notifyItemChanged(position);
                }
            }
        });
    }

    private void choosePoster(Item item, int rowId) {
        Context context = requireContext();
        Context app = context.getApplicationContext();
        int viewToken = viewGeneration;
        boolean forceShow = item.value instanceof Tvshow || isSeriesRowItem(item.value, rowId);
        Toast.makeText(context, context.getString(com.archos.mediacenter.video.R.string.cinema_finding_posters), Toast.LENGTH_SHORT).show();
        CinemaPosters.QUEUE.execute(() -> {
            List<CinemaPosters.Choice> matches;
            try {
                matches = CinemaPosters.search(app, CinemaPosters.query(item.value, forceShow), posterOnlineId(app, item.value), true);
            } catch (Exception error) {
                matches = List.of();
            }
            List<CinemaPosters.Choice> choices = matches.subList(0, Math.min(matches.size(), 10));
            handler.post(() -> {
                if (root == null || viewGeneration != viewToken || !isResumed()) return;
                if (choices.isEmpty()) {
                    Toast.makeText(context, context.getString(com.archos.mediacenter.video.R.string.cinema_no_posters_available), Toast.LENGTH_SHORT).show();
                    return;
                }
                ListView list = new ListView(context);
                list.setAdapter(new android.widget.ArrayAdapter<CinemaPosters.Choice>(context, android.R.layout.simple_list_item_1, choices) {
                    @NonNull @Override public View getView(int position, View old, @NonNull ViewGroup parent) {
                        var choice = getItem(position);
                        LinearLayout row = new LinearLayout(context);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 6));
                        ImageView preview = new ImageView(context);
                        row.addView(preview, new LinearLayout.LayoutParams(dp(context, 40), dp(context, 60)));
                        Picasso.get().load(choice.preview()).resize(dp(context, 40), dp(context, 60)).centerCrop().into(preview);
                        TextView caption = text(context, 16, false);
                        caption.setPadding(dp(context, 12), 0, 0, 0);
                        caption.setText(choice.title() + (choice.year().isEmpty() ? "" : " (" + choice.year() + ")"));
                        row.addView(caption);
                        return row;
                    }
                });
                var dialog = CinemaUi.dialog(context).setTitle(context.getString(com.archos.mediacenter.video.R.string.cinema_choose_poster))
                        .setView(list).create();
                list.setOnItemClickListener((parent, row, position, id) -> {
                    dialog.dismiss();
                    String key = posterKey(item.value);
                    CinemaPosters.QUEUE.execute(() -> {
                        try {
                            posterChanged(key, CinemaPosters.save(app, key, choices.get(position)), viewToken);
                        } catch (Exception error) {
                            handler.post(() -> {
                                if (root != null && viewGeneration == viewToken) Toast.makeText(context,
                                        context.getString(com.archos.mediacenter.video.R.string.cinema_could_not_save_poster), Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
                });
                dialog.show();
            });
        });
    }

    private class Posters extends RecyclerView.Adapter<PosterHolder> {
        private static final Object PAYLOAD_STATE = new Object();
        private List<Item> items;
        private final int rowId;
        Posters(int rowId,List<Item> items){this.rowId=rowId;this.items=items;setHasStableIds(true);}
        int find(String key){for(int n=0;n<items.size();n++)if(itemKey(items.get(n)).equals(key))return n;return -1;}
        int findPoster(String key){for(int n=0;n<items.size();n++)if(posterKey(items.get(n).value).equals(key))return n;return -1;}
        private int computeProgressWidth(Item item) {
            if (isOptimisticallyRemovedFromContinue(item, 0L)) return 0;
            if(item.stream!=null){
                if(!item.stream.removedFromContinue()&&item.stream.durationMs()>0&&item.stream.positionMs()>0){
                    return (int)Math.min(posterWidth(),(long)posterWidth()*item.stream.positionMs()/item.stream.durationMs());
                }
                return 0;
            }
            if(item.value instanceof Video v){
                long dur=CinemaMediaTime.known(v.getId(),v.getFilePath(),v.getDurationMs()),res=CinemaMediaTime.knownResume(v.getId(),v.getFilePath(),v.getResumeMs());
                if(dur>0&&res>0&&!CinemaPlaybackRouter.isCompletedBookmark(res)) return (int)Math.min(posterWidth(),(long)posterWidth()*res/dur);
            }
            return 0;
        }
        private String computeEpisodeTag(Item item) {
            boolean uiArabic=CinemaUi.arabic(requireContext());
            int badgeSeason=0, badgeEp=0;
            if(item.stream!=null&&"episode".equalsIgnoreCase(item.stream.mediaType())&&item.stream.episode()>0){
                badgeSeason=item.stream.season(); badgeEp=item.stream.episode();
            }else if(item.value instanceof com.archos.mediacenter.video.browser.adapters.object.Episode ep){
                badgeSeason=ep.getSeasonNumber(); badgeEp=ep.getEpisodeNumber();
            }else if(item.value instanceof Video v && isSeriesOrEpisodeFile(v, v.getFilePath())){
                int[] se=CinemaPosters.findSeasonEpisode(v.getFilePath(), v.getName());
                if (se[1] > 0) { badgeSeason=Math.max(1,se[0]); badgeEp=se[1]; }
            }
            if(badgeEp>0){
                return uiArabic
                        ? String.format(Locale.US,"م%d · ح%d",badgeSeason,badgeEp)
                        : String.format(Locale.US,"S%02d E%02d",badgeSeason,badgeEp);
            }
            return null;
        }
        private String resolveCardTitle(Item item, Context app, String key, boolean arabicMode) {
            CinemaArtwork.Art art = CinemaArtwork.getCachedHero(app, key);
            String cleanFallbackName = CinemaPosters.cleanDisplayTitle(item.value, arabicMode);
            if (item.stream != null && item.stream.episode() > 0) {
                String episode = stripGenericEpisodeTokens(item.stream.title());
                return episode.isBlank() ? item.value.getName() : episode;
            }
            return item.stream != null ? item.stream.title()
                    : (art == null || art.title() == null || art.title().isBlank() ? cleanFallbackName : art.title());
        }
        List<Item> snapshot() {
            return items == null ? List.of() : List.copyOf(items);
        }
        void applyPrecomputed(List<Item> expectedOld, List<Item> updated,
                              androidx.recyclerview.widget.DiffUtil.DiffResult precomputed) {
            final List<Item> newItems = updated == null ? List.of() : updated;
            if (this.items == null || this.items.isEmpty()) {
                this.items = newItems;
                notifyDataSetChanged();
                return;
            }
            if (precomputed != null && expectedOld != null && expectedOld.size() == this.items.size()) {
                boolean sameBase = true;
                for (int i = 0; i < expectedOld.size(); i++) {
                    if (expectedOld.get(i) != this.items.get(i)) {
                        sameBase = false;
                        break;
                    }
                }
                if (sameBase) {
                    this.items = newItems;
                    precomputed.dispatchUpdatesTo(this);
                    return;
                }
            }
            replace(newItems);
        }
        void replace(List<Item> updated){
            final List<Item> oldItems = this.items;
            final List<Item> newItems = updated == null ? List.of() : updated;
            if (oldItems == null || oldItems.isEmpty()) {
                this.items = newItems;
                notifyDataSetChanged();
                return;
            }
            final Context app = requireContext().getApplicationContext();
            final String lang = MetadataLanguage.resolve(app);
            final boolean arabicMode = lang.startsWith("ar");
            final boolean uiAr = CinemaUi.arabic(requireContext());
            final int w = posterWidth();
            for (Item it : oldItems) {
                if (it.cachedPosterKey == null) populateCachedItemFields(it, app, lang, arabicMode, uiAr, w);
            }
            for (Item it : newItems) {
                if (it.cachedPosterKey == null) populateCachedItemFields(it, app, lang, arabicMode, uiAr, w);
            }
            androidx.recyclerview.widget.DiffUtil.DiffResult diff = computePostersDiff(oldItems, newItems);
            this.items = newItems;
            diff.dispatchUpdatesTo(this);
        }
        @Override public long getItemId(int position){Item it=items.get(position);if(it.stream!=null)return -(1000000000L+(it.stream.streamKey().hashCode()&0x7fffffffL));Base b=it.value;if(b instanceof Video v)return v.getId();long sid=((Tvshow)b).getTvshowId();return sid>0?-sid:-(500000000L+(mediaKey(b).hashCode()&0x3fffffffL));}
        @Override public int getItemCount(){return items.size();}
        @NonNull @Override public PosterHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){
            Context c=parent.getContext();FrameLayout card=new FrameLayout(c);card.setFocusable(true);card.setLongClickable(true);card.setClipToOutline(true);
            card.setBackground(rounded(0xff292929,dp(c,8),0));
            RecyclerView.LayoutParams p=new RecyclerView.LayoutParams(posterWidth(),posterHeight());p.setMarginEnd(dp(c,16));card.setLayoutParams(p);
            ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(image,new FrameLayout.LayoutParams(-1,-1));
            TextView epBadge=text(c,11,true);epBadge.setTextColor(0xfff5d47a);epBadge.setPadding(dp(c,7),dp(c,3),dp(c,7),dp(c,3));epBadge.setBackground(rounded(0xe6101113,dp(c,6),CinemaTokens.GOLD));epBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams epLp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);epLp.topMargin=dp(c,10);epLp.leftMargin=dp(c,12);epLp.rightMargin=dp(c,12);epLp.setMarginStart(dp(c,12));epLp.setMarginEnd(dp(c,12));card.addView(epBadge,epLp);
            TextView checkBadge=text(c,11,true);checkBadge.setText("✓");checkBadge.setTextColor(0xff101113);checkBadge.setGravity(Gravity.CENTER);checkBadge.setBackground(rounded(CinemaTokens.GOLD,dp(c,10),0));checkBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams chkLp=new FrameLayout.LayoutParams(dp(c,20),dp(c,20),Gravity.TOP|Gravity.LEFT);chkLp.topMargin=dp(c,5);chkLp.leftMargin=dp(c,8);card.addView(checkBadge,chkLp);
            TextView name=text(c,11,true);name.setMaxLines(2);name.setPadding(dp(c,6),dp(c,4),dp(c,6),dp(c,4));name.setBackgroundColor(0xd9202020);
            card.addView(name,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            View progress=new View(c);progress.setBackgroundColor(CinemaTokens.GOLD);FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(0,dp(c,3),Gravity.BOTTOM);card.addView(progress,pp);
            return new PosterHolder(card,image,name,progress,epBadge,checkBadge);
        }
        private void bindCardState(@NonNull PosterHolder h, Item item, String title, boolean keepExistingVisibility) {
            h.name.setText(title);
            if (!keepExistingVisibility) {
                h.name.setVisibility(View.VISIBLE);
            }
            h.itemView.setContentDescription(title);
            int progress = computeProgressWidth(item);
            if (h.progress.getLayoutParams().width != progress) {
                h.progress.getLayoutParams().width = progress;
                h.progress.requestLayout();
            }
            String epTag = item.cachedEpisodeTag != null || item.cachedPosterKey != null ? item.cachedEpisodeTag : computeEpisodeTag(item);
            if(epTag!=null){h.epBadge.setText(epTag);h.epBadge.setVisibility(View.VISIBLE);}
            else h.epBadge.setVisibility(View.GONE);
            boolean watched = isItemWatched(item);
            h.checkBadge.setVisibility(watched ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams epLp = (FrameLayout.LayoutParams) h.epBadge.getLayoutParams();
            int episodeGravity = Gravity.TOP | (watched ? Gravity.RIGHT : Gravity.START);
            if (epLp.gravity != episodeGravity) {
                epLp.gravity = episodeGravity;
                h.epBadge.setLayoutParams(epLp);
            }
            h.itemView.setOnFocusChangeListener((v,focused)->{
                setPosterFocus(v,focused);
                if(focused){setPosterRowsShown(true);lastContentFocus=v;selectedRow=rowId;selectedKey=itemKey(item);int fPos=h.getBindingAdapterPosition();if(fPos>=0)selectedPosition=fPos;select(item);
                    RecyclerView list=lists.get(rowId);if(list!=null)list.post(()->{if(root!=null&&contentScroll!=null){if(!v.hasFocus()||v.getParent()!=list)return;
                        int inset=Math.round(v.getWidth()*0.03f)+dp(requireContext(),4);
                        int right=v.getRight()-list.getWidth()+inset;
                        int left=v.getLeft()-inset;
                        if(right>0)list.scrollBy(right,0);
                        else if(left<0)list.scrollBy(left,0);
                        int y=((View)list.getParent()).getTop();if(motion(requireContext()))contentScroll.smoothScrollTo(0,y);else contentScroll.scrollTo(0,y);}});
                }
            });
            setPosterFocus(h.itemView,h.itemView.hasFocus());
            h.itemView.setOnClickListener(v->{
                int current=h.getBindingAdapterPosition();
                if(current==RecyclerView.NO_POSITION)return;
                selectedPosition=current;
                returningPoster=capturePosterLocation(rowId,itemKey(item),current);
                open(item,rowId);
            });
            h.itemView.setOnLongClickListener(v->{
                itemContextMenu(rowId,item);
                return true;
            });
            boolean[] held = {false};
            h.itemView.setOnKeyListener((v,k,e)->{
                k=logicalKey(v,k);
                if (rowId == 1 && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                        || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A)) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN) {
                        if (e.getRepeatCount() == 0) held[0] = false;
                        else if (!held[0] && e.getEventTime() - e.getDownTime()
                                >= android.view.ViewConfiguration.getLongPressTimeout()) {
                            held[0] = true;
                            v.performLongClick();
                        }
                    } else if (e.getAction() == KeyEvent.ACTION_UP && !held[0] && !e.isCanceled()) {
                        v.performClick();
                    }
                    return true;
                }
                if(e.getAction()==KeyEvent.ACTION_DOWN&&k==KeyEvent.KEYCODE_MENU){
                    itemContextMenu(rowId,item);
                    return true;
                }
                if(e.getAction()==KeyEvent.ACTION_DOWN&&(k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_DPAD_UP)){
                    int pos = h.getBindingAdapterPosition();
                    int step = k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                    String curKey = rowIdToSectionKey.get(rowId);
                    if (curKey != null) {
                        return focusAdjacentSection(curKey, step, Math.max(0, pos));
                    }
                    List<Integer> order=rowOrder();int from=order.indexOf(rowId);
                    for(int n=from+step;n>=0&&n<order.size();n+=step){RecyclerView next=lists.get(order.get(n));if(next!=null&&next.getAdapter()!=null&&next.getAdapter().getItemCount()>0)return focusPoster(next,Math.min(Math.max(0,pos),next.getAdapter().getItemCount()-1));}if(step>0&&focusStrip(0,1,0))return true;if(step>0&&otherFirst!=null)return otherFirst.requestFocus();return true;
                }
                if((k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT)&&e.getAction()==KeyEvent.ACTION_DOWN&&v.getParent() instanceof RecyclerView strip){
                    int pos=strip.getChildAdapterPosition(v);if(pos<0)pos=h.getBindingAdapterPosition();
                    if(pos>=0){
                        if(k==KeyEvent.KEYCODE_DPAD_LEFT){if(pos==0){if(rail.getChildCount()>0)rail.getChildAt(0).requestFocus();return true;}return focusNeighbour(strip,pos-1);}
                        if(pos>=strip.getAdapter().getItemCount()-1)return true;
                        return focusNeighbour(strip,pos+1);
                    }
                }

                return false;
            });
        }
        @Override public void onBindViewHolder(@NonNull PosterHolder h,int position,@NonNull List<Object> payloads){
            if(!payloads.isEmpty()&&payloads.contains(PAYLOAD_STATE)){
                Item item=items.get(position);
                String key=posterKey(item.value);
                Context app=requireContext().getApplicationContext();
                boolean arabicMode=MetadataLanguage.resolve(requireContext()).startsWith("ar");
                String title=resolveCardTitle(item,app,key,arabicMode);
                bindCardState(h,item,title,h.image.getDrawable()!=null);
                return;
            }
            onBindViewHolder(h,position);
        }
        @Override public void onBindViewHolder(@NonNull PosterHolder h,int position){
            Item item=items.get(position);
            String key=item.cachedPosterKey!=null?item.cachedPosterKey:posterKey(item.value);
            Context app = requireContext().getApplicationContext();
            boolean arabicMode = key.startsWith("ar");
            boolean rejectOriginal = !arabicMode && hasArabicScrapedData(item.value);
            Uri cached = CinemaArtwork.getCachedPosterInstant(app, key, mediaKey(item.value), null);
            boolean exactReady = cached != null && CinemaArtwork.hasExactLanguagePoster(app, key);
            Uri fallback = rejectOriginal ? null : item.value.getPosterUri();
            Uri target = cached != null ? cached : fallback;
            String title = item.cachedCardTitle != null ? item.cachedCardTitle : resolveCardTitle(item, app, key, arabicMode);
            boolean sameDisplayedKey = key.equals(h.image.getTag()) && h.image.getDrawable() != null;
            android.graphics.Bitmap instantBmp = CinemaArtwork.getMemoryPosterBitmap(target);
            bindCardState(h, item, title, instantBmp != null || sameDisplayedKey);
            Picasso.get().cancelRequest(h.image);
            h.image.setTag(key);
            if(instantBmp!=null){
                h.image.setImageBitmap(instantBmp);
                h.name.setVisibility(View.GONE);
            }else{
                if(!sameDisplayedKey){
                    h.image.setImageResource(com.archos.mediacenter.video.R.drawable.cinema_tr_movies_poster);
                    h.name.setVisibility(View.VISIBLE);
                }
                if(target!=null){
                    bindPoster(h, item, target, fallback);
                }
            }
            if(target==null || !exactReady){
                long now=android.os.SystemClock.elapsedRealtime();
                Long prev=posterRetryAt.get(key);
                if(prev==null||now-prev>45_000L){
                    posterRetryAt.put(key, now);
                    queuePoster(item, false, false);
                }
            }
        }
        @Override public void onViewRecycled(@NonNull PosterHolder h){Picasso.get().cancelRequest(h.image);h.itemView.animate().cancel();h.itemView.setForeground(null);h.itemView.setScaleX(1);h.itemView.setScaleY(1);super.onViewRecycled(h);}
    }
    static class PosterHolder extends RecyclerView.ViewHolder {
        final ImageView image;final TextView name;final View progress;final TextView epBadge;final TextView checkBadge;
        PosterHolder(View root,ImageView image,TextView name,View progress,TextView epBadge,TextView checkBadge){super(root);this.image=image;this.name=name;this.progress=progress;this.epBadge=epBadge;this.checkBadge=checkBadge;}
    }
}
