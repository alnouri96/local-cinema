package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import com.archos.mediacenter.video.browser.loader.VideoLoader;
import com.archos.mediaprovider.video.VideoStore;
import com.archos.mediascraper.BaseTags;
import java.util.ArrayList;
import java.util.List;

/** Uses NOVA's real provider and change notifications, on CursorLoader's worker. */
public final class CinemaHomeLoader extends VideoLoader {
    public static final int CAT_DEFAULT = 0;
    public static final int CAT_MOVIES = 1;
    public static final int CAT_SHOWS = 2;
    public static final int CAT_CARTOON = 3;

    private final int row;
    private final List<String> sourcePrefixes;
    private final int category;

    public CinemaHomeLoader(Context context, int row) {
        this(context, row, (List<String>) null, row == 3 ? CAT_MOVIES : (row == 4 ? CAT_SHOWS : CAT_DEFAULT));
    }

    CinemaHomeLoader(Context context, int row, String sourcePrefix) {
        this(context, row, sourcePrefix == null ? null : List.of(sourcePrefix), CAT_DEFAULT);
    }

    CinemaHomeLoader(Context context, int row, List<String> sourcePrefixes, int category) {
        super(context);
        this.row = row;
        this.sourcePrefixes = (sourcePrefixes == null || sourcePrefixes.isEmpty()) ? null : List.copyOf(sourcePrefixes);
        this.category = category;
        init();
        setUpdateThrottle(1500);
    }

    @Override public String getSelection() {
        String common = super.getSelection();
        String filter;
        if (sourcePrefixes != null) {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < sourcePrefixes.size(); i++) {
                if (i > 0) sb.append(" OR ");
                sb.append(VideoStore.MediaColumns.DATA).append(" LIKE ? ESCAPE '!' OR ")
                  .append(VideoStore.MediaColumns.DATA).append(" LIKE ? ESCAPE '!'");
            }
            sb.append(")");
            filter = sb.toString();
        } else {
            filter = switch (row) {
                case 1 -> VideoStore.Video.VideoColumns.BOOKMARK + ">1";
                case 6 -> "(" + VideoStore.Video.VideoColumns.BOOKMARK + "=" + com.archos.mediacenter.video.player.PlayerActivity.LAST_POSITION_END
                        + " OR " + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + "=1)";
                case 3 -> VideoStore.Video.VideoColumns.ARCHOS_MEDIA_SCRAPER_TYPE + "=" + BaseTags.MOVIE
                        + " AND (" + VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID + " IS NULL OR "
                        + VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID + "<=0)";
                default -> "1=1";
            };
        }
        String selection = common.isEmpty() ? filter : "(" + common + ") AND " + filter;
        if (sourcePrefixes == null) return selection;
        String show = VideoStore.Video.VideoColumns.SCRAPER_SHOW_ID;
        // The provider wraps selection in parentheses, as in AllTvshowsLoader.
        return selection + ") GROUP BY (CASE WHEN " + show + ">0 THEN 's:' || " + show + " ELSE 'v:' || _id END";
    }

    @Override public String[] getProjection() {
        String[] projection = super.getProjection();
        if (sourcePrefixes == null) return projection;
        String[] withShow = java.util.Arrays.copyOf(projection, projection.length + 4);
        withShow[projection.length] = VideoStore.Video.VideoColumns.SCRAPER_S_COVER;
        withShow[projection.length + 1] = VideoStore.Video.VideoColumns.SCRAPER_S_PLOT;
        withShow[projection.length + 2] = "MAX(" + VideoStore.MediaColumns.DATE_ADDED + ") AS cinema_added";
        withShow[projection.length + 3] = "COUNT(DISTINCT " + VideoStore.Video.VideoColumns.SCRAPER_E_SEASON + ") AS cinema_seasons";
        return withShow;
    }

    @Override public String[] getSelectionArgs() {
        if (sourcePrefixes == null) return super.getSelectionArgs();
        List<String> args = new ArrayList<>(sourcePrefixes.size() * 2);
        for (String prefix : sourcePrefixes) {
            args.add(prefixPattern(prefix));
            args.add(prefixPattern(android.net.Uri.decode(prefix)));
        }
        return args.toArray(new String[0]);
    }

    private static String prefixPattern(String prefix) {
        return prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    @Override public String getSortOrder() {
        if (sourcePrefixes != null) return "cinema_added DESC LIMIT 400";
        if (row == 1) return VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED + " DESC LIMIT 50";
        if (row == 6) return VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED + " DESC, " + VideoStore.MediaColumns.DATE_MODIFIED + " DESC LIMIT 50";
        return VideoStore.MediaColumns.DATE_ADDED + " DESC LIMIT 50";
    }
}
