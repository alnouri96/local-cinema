package com.archos.mediacenter.video.leanback.cinema;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.rounded;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.text;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.meedx.providers.OpenTrailersProvider;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen cinematic TV Trailers hub featuring a Dynamic Hero Backdrop,
 * ClearLogo/Metadata/Age-Rating/Cast header, and three 16:9 landscape trailer rows
 * backed by open sources (Cinemeta + Jikan + optional TMDb) with a 24h daily disk cache.
 */
public final class CinemaTrailersActivity extends AppCompatActivity {
    private boolean arabic;
    private FrameLayout root;
    private ImageView heroBackdrop;
    private ImageView heroLogo;
    private TextView heroTitle;
    private TextView heroDate;
    private TextView heroRuntime;
    private TextView heroGenres;
    private TextView heroAgeBadge;
    private TextView heroDesc;
    private TextView heroCast;
    private TextView syncBadge;
    private TextView refreshBtn;
    private ProgressBar loadingBar;
    private ScrollView rowsScroll;
    private LinearLayout rowsContainer;
    private View railNav;
    private OpenTrailersProvider.TrailerItem focusedItem;
    private final List<RecyclerView> rowRecyclers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        arabic = CinemaUi.arabic(this);

        root = new FrameLayout(this);
        root.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(CinemaTokens.CANVAS);

        heroBackdrop = new ImageView(this);
        heroBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(heroBackdrop, new FrameLayout.LayoutParams(-1, -1));

        View horizontalScrim = new View(this);
        horizontalScrim.setBackground(new GradientDrawable(
                arabic ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{CinemaTokens.CANVAS, 0xdd171717, 0x33171717}));
        root.addView(horizontalScrim, new FrameLayout.LayoutParams(-1, -1));

        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x10171717, 0x99171717, CinemaTokens.CANVAS}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(-1, -1));

        // Top utility capsule (opposite side of Navigation Rail)
        LinearLayout topUtility = new LinearLayout(this);
        topUtility.setOrientation(LinearLayout.HORIZONTAL);
        topUtility.setGravity(Gravity.CENTER_VERTICAL);
        syncBadge = text(this, 12, false);
        syncBadge.setTextColor(CinemaTokens.SECONDARY);
        syncBadge.setPadding(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
        syncBadge.setBackground(rounded(CinemaTokens.CAPSULE, dp(this, 16), CinemaTokens.BORDER));
        topUtility.addView(syncBadge);

        refreshBtn = text(this, 13, true);
        refreshBtn.setText(label(this, "⟳ تحديث", "⟳ Refresh"));
        refreshBtn.setPadding(dp(this, 14), dp(this, 8), dp(this, 14), dp(this, 8));
        CinemaUi.focusable(refreshBtn);
        refreshBtn.setOnClickListener(v -> loadFeed(true));
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(-2, -2);
        rbLp.setMarginStart(dp(this, 10));
        topUtility.addView(refreshBtn, rbLp);

        FrameLayout.LayoutParams utilLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | (arabic ? Gravity.LEFT : Gravity.RIGHT));
        utilLp.topMargin = dp(this, 18);
        if (arabic) utilLp.leftMargin = dp(this, 28);
        else utilLp.rightMargin = dp(this, 28);
        root.addView(topUtility, utilLp);

        // Main Vertical Column: Dynamic Hero Area + 3 Scrollable Landscape Rows
        LinearLayout mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        if (arabic) {
            mainCol.setPadding(dp(this, 28), dp(this, 24), dp(this, 96), dp(this, 18));
        } else {
            mainCol.setPadding(dp(this, 96), dp(this, 24), dp(this, 28), dp(this, 18));
        }
        root.addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

        // Hero Identity & Details (Compact Cinematic Top Half)
        LinearLayout heroBox = new LinearLayout(this);
        heroBox.setOrientation(LinearLayout.VERTICAL);
        heroBox.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        mainCol.addView(heroBox, new LinearLayout.LayoutParams(dp(this, 620), dp(this, 236)));

        TextView pageHeader = text(this, 13, true);
        pageHeader.setTextColor(CinemaTokens.GOLD);
        pageHeader.setText(label(this, "الإعلانات السينمائية · التحديث التلقائي اليومي", "CINEMA TRAILERS · DAILY AUTO-REFRESH"));
        heroBox.addView(pageHeader);

        FrameLayout identity = new FrameLayout(this);
        LinearLayout.LayoutParams idLp = new LinearLayout.LayoutParams(-1, dp(this, 76));
        idLp.topMargin = dp(this, 4);
        heroBox.addView(identity, idLp);

        heroTitle = text(this, 32, true);
        heroTitle.setMaxLines(2);
        heroTitle.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(heroTitle, new FrameLayout.LayoutParams(-1, -1));

        heroLogo = new ImageView(this);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        heroLogo.setVisibility(View.GONE);
        identity.addView(heroLogo, new FrameLayout.LayoutParams(dp(this, 310), -1));

        // Metadata Pills Strip: Release Date · Runtime · Genres · Age Badge
        LinearLayout metaStrip = new LinearLayout(this);
        metaStrip.setOrientation(LinearLayout.HORIZONTAL);
        metaStrip.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(-1, -2);
        msLp.topMargin = dp(this, 6);
        heroBox.addView(metaStrip, msLp);

        heroDate = text(this, 13, true);
        heroDate.setTextColor(CinemaTokens.GOLD);
        metaStrip.addView(heroDate);

        heroRuntime = text(this, 13, false);
        heroRuntime.setTextColor(CinemaTokens.TEXT);
        heroRuntime.setPadding(dp(this, 10), 0, dp(this, 10), 0);
        metaStrip.addView(heroRuntime);

        heroGenres = text(this, 13, false);
        heroGenres.setTextColor(CinemaTokens.SECONDARY);
        heroGenres.setSingleLine(true);
        metaStrip.addView(heroGenres, new LinearLayout.LayoutParams(0, -2, 1f));

        heroAgeBadge = text(this, 12, true);
        heroAgeBadge.setTextColor(CinemaTokens.TEXT);
        heroAgeBadge.setPadding(dp(this, 8), dp(this, 2), dp(this, 8), dp(this, 2));
        heroAgeBadge.setBackground(rounded(0x88292929, dp(this, 6), CinemaTokens.GOLD));
        metaStrip.addView(heroAgeBadge);

        heroDesc = text(this, 14, false);
        heroDesc.setTextColor(CinemaTokens.SECONDARY);
        heroDesc.setMaxLines(3);
        heroDesc.setLineSpacing(dp(this, 2), 1f);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
        dLp.topMargin = dp(this, 8);
        heroBox.addView(heroDesc, dLp);

        heroCast = text(this, 13, false);
        heroCast.setTextColor(0xffd7c7a0);
        heroCast.setSingleLine(true);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
        cLp.topMargin = dp(this, 6);
        heroBox.addView(heroCast, cLp);

        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setVisibility(View.GONE);
        mainCol.addView(loadingBar, new LinearLayout.LayoutParams(-1, dp(this, 4)));

        // Rows ScrollView (3 Landscape 16:9 Trailer Rows)
        rowsScroll = new ScrollView(this);
        rowsScroll.setVerticalScrollBarEnabled(false);
        rowsScroll.setClipChildren(false);
        rowsScroll.setClipToPadding(false);
        rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        rowsContainer.setClipChildren(false);
        rowsContainer.setClipToPadding(false);
        rowsScroll.addView(rowsContainer, new FrameLayout.LayoutParams(-1, -2));
        mainCol.addView(rowsScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        railNav = CinemaNavigation.attach(this, root);
        setContentView(root);

        loadFeed(false);
    }

    private void loadFeed(boolean forceRefresh) {
        OpenTrailersProvider.TrailersFeed cached = OpenTrailersProvider.getCached(this, arabic);
        if (cached != null && !cached.isEmpty()) {
            renderFeed(cached);
        }
        boolean stale = OpenTrailersProvider.needsDailyRefresh(this, arabic);
        if (forceRefresh || stale || cached == null || cached.isEmpty()) {
            loadingBar.setVisibility(View.VISIBLE);
            syncBadge.setText(label(this, "جارٍ تحديث الإعلانات...", "Refreshing trailers..."));
            OpenTrailersProvider.refreshAsync(this, arabic, forceRefresh, fresh -> {
                if (isDestroyed() || isFinishing()) return;
                loadingBar.setVisibility(View.GONE);
                if (fresh != null && !fresh.isEmpty()) {
                    renderFeed(fresh);
                } else if (cached == null || cached.isEmpty()) {
                    syncBadge.setText(label(this, "تعذّر تحميل الإعلانات حالياً", "Unable to load trailers right now"));
                    Toast.makeText(this, label(this, "تحقّق من الاتصال بالإنترنت", "Check internet connection"), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void renderFeed(OpenTrailersProvider.TrailersFeed feed) {
        rowsContainer.removeAllViews();
        rowRecyclers.clear();

        String timeStr = feed.lastSyncMs > 0
                ? new SimpleDateFormat("MMM d · h:mm a", Locale.US).format(new Date(feed.lastSyncMs))
                : "Today";
        syncBadge.setText(label(this, "آخر تحديث يومي: ", "Daily sync: ") + timeStr);

        addTrailerRow(label(this, "الإعلانات الرائجة", "Trending Trailers"), feed.trending);
        addTrailerRow(label(this, "الإعلانات الجديدة", "New Trailers"), feed.fresh);
        addTrailerRow(label(this, "إعلانات قادم قريباً (أفلام ومسلسلات وأنمي)", "Coming Soon Trailers"), feed.comingSoon);

        if (!rowRecyclers.isEmpty()) {
            RecyclerView first = rowRecyclers.get(0);
            first.post(() -> {
                RecyclerView.ViewHolder vh = first.findViewHolderForAdapterPosition(0);
                if (vh != null) vh.itemView.requestFocus();
            });
        }
    }

    private void addTrailerRow(String rowTitle, List<OpenTrailersProvider.TrailerItem> items) {
        if (items == null || items.isEmpty()) return;

        int rowPad = dp(this, 16);
        TextView heading = text(this, CinemaTokens.ROW_TITLE, true);
        heading.setText(rowTitle);
        heading.setTextColor(CinemaTokens.TEXT);
        heading.setGravity(arabic ? Gravity.RIGHT : Gravity.LEFT);
        heading.setPaddingRelative(rowPad, 0, rowPad, 0);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2);
        hLp.topMargin = dp(this, rowRecyclers.isEmpty() ? 4 : 16);
        hLp.bottomMargin = dp(this, 8);
        rowsContainer.addView(heading, hLp);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setLayoutDirection(arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        rv.setClipToPadding(false);
        rv.setClipChildren(false);
        rv.setPaddingRelative(rowPad, dp(this, 10), dp(this, 24), dp(this, 14));
        rv.setAdapter(new TrailerRowAdapter(items));
        rowsContainer.addView(rv, new LinearLayout.LayoutParams(-1, dp(this, 196)));
        rowRecyclers.add(rv);
    }

    private void bindHero(OpenTrailersProvider.TrailerItem item) {
        if (item == null || item == focusedItem) return;
        focusedItem = item;

        if (!item.backdropUrl.isEmpty()) {
            heroBackdrop.setAlpha(0.7f);
            Picasso.get().load(item.backdropUrl).resize(1280, 720).centerCrop()
                    .into(heroBackdrop, new Callback() {
                        @Override public void onSuccess() {
                            if (!isDestroyed()) heroBackdrop.animate().alpha(1f).setDuration(220).start();
                        }
                        @Override public void onError(Exception e) {}
                    });
        }

        heroTitle.setText(item.title);
        heroTitle.setVisibility(View.VISIBLE);
        if (!item.logoUrl.isEmpty()) {
            heroLogo.setVisibility(View.VISIBLE);
            Picasso.get().load(item.logoUrl).resize(dp(this, 310), dp(this, 76)).centerInside()
                    .into(heroLogo, new Callback() {
                        @Override public void onSuccess() {
                            if (!isDestroyed() && focusedItem == item) heroTitle.setVisibility(View.GONE);
                        }
                        @Override public void onError(Exception e) {
                            if (!isDestroyed()) heroLogo.setVisibility(View.GONE);
                        }
                    });
        } else {
            heroLogo.setVisibility(View.GONE);
        }

        heroDate.setText(item.releaseDate.isEmpty() ? item.year : item.releaseDate);
        heroRuntime.setText(item.runtime.isEmpty() ? "" : ("· " + item.runtime));
        heroGenres.setText(item.genres.isEmpty() ? "" : ("· " + item.genres));
        if (item.ageRating.isEmpty()) {
            heroAgeBadge.setVisibility(View.GONE);
        } else {
            heroAgeBadge.setVisibility(View.VISIBLE);
            heroAgeBadge.setText(item.ageRating);
        }
        heroDesc.setText(item.description.isEmpty()
                ? label(this, "اضغط للمشاهدة مباشرة عبر تطبيق YouTube.", "Press OK to watch directly in YouTube.")
                : item.description);
        heroCast.setText(item.cast.isEmpty() ? ""
                : (label(this, "الممثلون: ", "Starring: ") + item.cast));
    }

    private void openTrailer(OpenTrailersProvider.TrailerItem item) {
        if (item == null) return;
        int year = 0;
        try {
            if (!item.year.isEmpty()) year = Integer.parseInt(item.year.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {}
        CinemaYouTubeLauncher.watchTrailer(this, item.ytId, item.title, year);
    }

    private final class TrailerRowAdapter extends RecyclerView.Adapter<TrailerRowAdapter.Holder> {
        private final List<OpenTrailersProvider.TrailerItem> items;

        TrailerRowAdapter(List<OpenTrailersProvider.TrailerItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setClipChildren(false);
            card.setClipToPadding(false);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(parent.getContext(), 224), -2);
            lp.setMarginEnd(dp(parent.getContext(), 14));
            card.setLayoutParams(lp);

            FrameLayout artFrame = new FrameLayout(parent.getContext());
            artFrame.setBackground(rounded(0xff222225, dp(parent.getContext(), CinemaTokens.POSTER_RADIUS), 0));
            artFrame.setClipToOutline(true);

            ImageView thumb = new ImageView(parent.getContext());
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            artFrame.addView(thumb, new FrameLayout.LayoutParams(-1, -1));

            TextView playBadge = text(parent.getContext(), 14, true);
            playBadge.setText("▶");
            playBadge.setTextColor(0xff141517);
            playBadge.setGravity(Gravity.CENTER);
            playBadge.setBackground(rounded(0xe6f2cc62, dp(parent.getContext(), 16), 0));
            FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                    dp(parent.getContext(), 32), dp(parent.getContext(), 32), Gravity.BOTTOM | Gravity.END);
            pbLp.bottomMargin = dp(parent.getContext(), 8);
            pbLp.rightMargin = dp(parent.getContext(), 8);
            artFrame.addView(playBadge, pbLp);

            card.addView(artFrame, new LinearLayout.LayoutParams(dp(parent.getContext(), 224), dp(parent.getContext(), 126)));

            TextView titleTv = text(parent.getContext(), 14, true);
            titleTv.setSingleLine(true);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
            tLp.topMargin = dp(parent.getContext(), 6);
            card.addView(titleTv, tLp);

            TextView dateTv = text(parent.getContext(), 12, false);
            dateTv.setTextColor(CinemaTokens.GOLD);
            dateTv.setSingleLine(true);
            dateTv.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(dateTv, new LinearLayout.LayoutParams(-1, -2));

            return new Holder(card, artFrame, thumb, titleTv, dateTv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            OpenTrailersProvider.TrailerItem item = items.get(position);
            h.titleTv.setText(item.title);
            h.dateTv.setText(!item.releaseDate.isEmpty() ? item.releaseDate : item.year);
            if (!item.thumbnailUrl.isEmpty()) {
                Picasso.get().load(item.thumbnailUrl).resize(448, 252).centerCrop().into(h.thumb);
            }
            h.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                CinemaUi.setPosterFocus(h.artFrame, hasFocus);
                if (hasFocus) bindHero(item);
            });
            h.itemView.setOnClickListener(v -> openTrailer(item));
            h.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int enterRailKey = arabic ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                int farEdgeKey = arabic ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                if (keyCode == enterRailKey && position == 0) {
                    if (railNav != null && railNav instanceof ViewGroup vg && vg.getChildCount() > 0) {
                        vg.getChildAt(Math.min(5, vg.getChildCount() - 1)).requestFocus();
                        return true;
                    }
                }
                if (keyCode == farEdgeKey && position >= items.size() - 1) {
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final FrameLayout artFrame;
            final ImageView thumb;
            final TextView titleTv;
            final TextView dateTv;

            Holder(View root, FrameLayout artFrame, ImageView thumb, TextView titleTv, TextView dateTv) {
                super(root);
                this.artFrame = artFrame;
                this.thumb = thumb;
                this.titleTv = titleTv;
                this.dateTv = dateTv;
            }
        }
    }
}
