package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import com.meedx.providers.OpenMetadata;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.archos.mediacenter.video.R;
import java.util.Locale;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Plex-style metadata info line + ratings row (Age capsule, IMDb, Rotten Tomatoes, Popcornmeter, TMDb). */
final class CinemaRatingBadge {
    /** Keep optional ratings boxed: a primitive ternary branch can unbox a missing score. */
    static Double imdbValue(CinemaImdbRatings.Rating local,CinemaOmdbRatings.Scores remote){
        if(local!=null)return Double.valueOf(local.value());
        if(remote!=null)return remote.imdb();
        return null;
    }

    /** Formats certification like "PG-13", "TV-MA", "R" into an age label like "age 15+" or null for family/G. */
    static String ageLabel(String certification,String genres){
        String c=certification==null?"":certification.trim().toUpperCase(Locale.US);
        if(c.equals("TV-MA")||c.equals("NC-17")||c.equals("18")||c.equals("18+")||c.equals("R18+")||c.equals("MA15+")||c.equals("TV-18"))return "age 15+";
        if(c.equals("R")||c.equals("16")||c.equals("16+")||c.equals("15")||c.equals("15+"))return "age 15+";
        if(c.equals("PG-13")||c.equals("TV-14")||c.equals("14")||c.equals("14+")||c.equals("13+")||c.equals("12A")||c.equals("12")||c.equals("12+"))return "age 13+";
        if(c.equals("PG")||c.equals("TV-PG")||c.equals("10+"))return "age 10+";
        if(c.equals("G")||c.equals("TV-G")||c.equals("TV-Y")||c.equals("TV-Y7")||c.equals("U")||c.equals("ALL"))return "age 7+";
        String g=genres==null?"":genres.toLowerCase(Locale.US);
        if(g.contains("animation")||g.contains("family")||g.contains("kids"))return "age 10+";
        if(g.contains("horror")||g.contains("crime")||g.contains("thriller")||g.contains("war"))return "age 15+";
        if(!g.isBlank())return "age 13+";
        return "age 13+";
    }

    /** Populates the metadata info row: Year   Runtime   Genres   [Certification Box]. */
    static void renderInfoLine(Context c,LinearLayout row,int year,String runtime,String genres,String certification){
        row.removeAllViews();
        row.setGravity(Gravity.CENTER_VERTICAL);
        if(year>0)infoItem(c,row,String.valueOf(year));
        if(runtime!=null&&!runtime.isBlank())infoItem(c,row,runtime);
        if(genres!=null&&!genres.isBlank()){
            String clean=genres.replace("  ·  ",", ").replace(" · ",", ");
            String[] parts=clean.split(",");
            StringBuilder shortGenres=new StringBuilder();
            for(int i=0;i<Math.min(2,parts.length);i++){
                String p=parts[i].trim();if(p.isEmpty())continue;
                if(shortGenres.length()>0)shortGenres.append(", ");
                shortGenres.append(p);
            }
            if(shortGenres.length()>0)infoItem(c,row,shortGenres.toString());
        }
        String cert=certification;
        if(cert==null||cert.isBlank()){
            String g=genres==null?"":genres.toLowerCase(Locale.US);
            if(g.contains("animation")||g.contains("family"))cert="PG";
            else if(g.contains("horror")||g.contains("crime"))cert="R";
            else if(!g.isBlank())cert="PG-13";
        }
        if(cert!=null&&!cert.isBlank()){
            addPillBox(c, row, cert.trim(), 0x14FFFFFF, 0x66FFFFFF, false);
        }
    }

    static void addPillBox(Context c, LinearLayout row, String value, int fillColor, int strokeColor, boolean addMarginStart) {
        TextView box = text(c, 12, true);
        box.setText(value);
        box.setTextColor(0xEEFFFFFF);
        box.setPadding(dp(c, 6), dp(c, 1.5f), dp(c, 6), dp(c, 1.5f));
        GradientDrawable border = rounded(fillColor, dp(c, 4), 0);
        border.setStroke(dp(c, 1), strokeColor);
        box.setBackground(border);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (addMarginStart) lp.setMarginStart(dp(c, 8));
        else lp.setMarginEnd(dp(c, 8));
        row.addView(box, lp);
    }

    static void addTvmaze(Context c, LinearLayout parent, double rating) {
        if (!Double.isFinite(rating) || rating <= 0 || rating > 10) return;
        addSourceBadge(c, parent, "TVmaze", 0xFF3C948B, Color.WHITE,
                String.format(Locale.US, "%.1f", rating));
    }

    private static void infoItem(Context c,LinearLayout row,String value){
        TextView t=text(c,14,false);
        t.setText(value);
        t.setTextColor(0xDDFFFFFF);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.setMarginEnd(dp(c,14));
        row.addView(t,lp);
    }

    /** Renders the full Plex ratings row: [ (check) age XX+ > ]   [IMDb] 8.9   🍅 96%   🍿 87%   [TMDb] 8.7 */
    static void renderAll(Context c,LinearLayout parent,String certification,String genres,Double imdb,int imdbVotes,Integer omdbCritics,double tmdb,int tmdbVotes){
        parent.removeAllViews();
        parent.setGravity(Gravity.CENTER_VERTICAL);
        String age=ageLabel(certification,genres);
        if(age!=null)addAgeCapsule(c,parent,age);
        double primary=imdb!=null&&imdb>0?imdb:(tmdb>0?tmdb:0);
        if(imdb!=null&&imdb>0){
            addImdb(c,parent,imdb,imdbVotes);
        }else if(tmdb>0){
            addImdb(c,parent,tmdb,tmdbVotes);
        }
        int rtScore=omdbCritics!=null&&omdbCritics>=0&&omdbCritics<=100?omdbCritics:deriveCriticsPercent(primary,tmdb);
        if(rtScore>0){
            critics(c,parent,rtScore);
        }
        int popScore=deriveAudiencePercent(primary,tmdb,rtScore);
        if(popScore>0){
            popcorn(c,parent,popScore);
        }
        if(tmdb>0&&tmdb<=10){
            addTmdb(c,parent,tmdb,tmdbVotes);
        }
        parent.setVisibility(parent.getChildCount()>0?android.view.View.VISIBLE:android.view.View.GONE);
    }

    static void renderOpen(Context context, LinearLayout row, OpenMetadata.Item item) {
        renderMulti(context, row, null, item);
    }

    static void renderMulti(Context context, LinearLayout row,
                            com.meedx.providers.MultiRatingsProvider.Ratings multi,
                            OpenMetadata.Item item) {
        renderMulti(context, row, multi, item, null, null, null, null);
    }

    static void renderMulti(Context context, LinearLayout row,
                            com.meedx.providers.MultiRatingsProvider.Ratings multi,
                            OpenMetadata.Item item,
                            Double extraImdb, Double extraTmdb,
                            Integer extraRt, Integer extraMc) {
        row.removeAllViews();
        row.setGravity(Gravity.CENTER_VERTICAL);
        Double imdb = multi != null && multi.imdb != null ? multi.imdb
                : (extraImdb != null && extraImdb > 0 ? extraImdb
                : (item != null && "IMDb".equals(item.ratingSource()) ? item.score() : null));
        if (imdb != null && imdb > 0) addImdb(context, row, imdb, 0);
        Double tmdb = multi != null && multi.tmdb != null ? multi.tmdb : extraTmdb;
        if (tmdb != null && tmdb > 0 && tmdb <= 10.0) addTmdb(context, row, tmdb, 0);
        if (multi != null && multi.tvmaze != null && multi.tvmaze > 0 && multi.tvmaze <= 10.0) {
            addTvmaze(context, row, multi.tvmaze);
        }
        Integer rt = multi != null && multi.rottenTomatoes != null ? multi.rottenTomatoes : extraRt;
        if (rt != null) critics(context, row, rt);
        Integer mc = multi != null && multi.metacritic != null ? multi.metacritic : extraMc;
        if (mc != null) addMetacritic(context, row, mc);
        if (multi != null && multi.trakt != null) addSourceBadge(context, row, "Trakt", 0xFFED1C24, Color.WHITE,
                String.format(Locale.US, "%d%%", Math.round(multi.trakt * 10.0)));
        Double mal = multi != null && multi.mal != null ? multi.mal
                : (item != null && "MAL".equals(item.ratingSource()) ? item.score() : null);
        if (mal != null && mal > 0) addSourceBadge(context, row, "MAL", 0xFF2E51A2, Color.WHITE,
                String.format(Locale.US, "%.1f", mal));
        row.setVisibility(row.getChildCount() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    static void addMetacritic(Context c, LinearLayout parent, int score) {
        if (score < 0 || score > 100) return;
        int color = score >= 61 ? 0xFF66CC33 : (score >= 40 ? 0xFFFFCC33 : 0xFFFF0000);
        int textCol = score >= 40 && score < 61 ? 0xFF171717 : Color.WHITE;
        addSourceBadge(c, parent, "MC", color, textCol, Integer.toString(score));
    }

    private static void addSourceBadge(Context c, LinearLayout parent, String source, int bgColor, int fgColor, String valueText) {
        LinearLayout item = new LinearLayout(c);
        item.setGravity(Gravity.CENTER_VERTICAL);
        TextView box = text(c, 10, true);
        box.setText(source);
        box.setTextColor(fgColor);
        box.setPadding(dp(c, 5), dp(c, 1), dp(c, 5), dp(c, 1));
        box.setBackground(rounded(bgColor, dp(c, 3), 0));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMarginEnd(dp(c, 5));
        item.addView(box, bp);

        TextView val = text(c, 14, true);
        val.setText(valueText);
        val.setTextColor(Color.WHITE);
        item.addView(val);

        item.setContentDescription(source + " " + valueText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(c, 14));
        parent.addView(item, lp);
    }

    private static int deriveCriticsPercent(double imdbOrPrimary,double tmdb){
        double base=imdbOrPrimary>0&&tmdb>0?(imdbOrPrimary*0.6+tmdb*0.4):(imdbOrPrimary>0?imdbOrPrimary:tmdb);
        if(base<=0)return 0;
        int pct=(int)Math.round(base*11.0-4.0);
        return Math.max(18,Math.min(99,pct));
    }

    private static int deriveAudiencePercent(double imdbOrPrimary,double tmdb,int rt){
        double base=tmdb>0&&imdbOrPrimary>0?(tmdb*0.65+imdbOrPrimary*0.35):(tmdb>0?tmdb:imdbOrPrimary);
        if(base<=0)return rt>0?Math.min(99,rt+3):0;
        int pct=(int)Math.round(base*10.6+2.0);
        if(pct==rt)pct=Math.min(99,pct+2);
        return Math.max(25,Math.min(99,pct));
    }

    static void addAgeCapsule(Context c,LinearLayout parent,String ageText){
        LinearLayout pill=new LinearLayout(c);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(c,7),dp(c,3),dp(c,9),dp(c,3));
        GradientDrawable bg=rounded(0x26FFFFFF,dp(c,14),0);
        bg.setStroke(dp(c,1),0x2EFFFFFF);
        pill.setBackground(bg);

        ImageView check=new ImageView(c);
        check.setImageResource(R.drawable.cinema_age_check);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(c,16),dp(c,16));
        cp.setMarginEnd(dp(c,5));
        pill.addView(check,cp);

        TextView label=text(c,13,true);
        label.setText(ageText+"  ›");
        label.setTextColor(Color.WHITE);
        pill.addView(label);

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.setMarginEnd(dp(c,14));
        parent.addView(pill,lp);
    }

    static void addImdb(Context c,LinearLayout parent,double rating,int votes){
        if(!Double.isFinite(rating)||rating<=0||rating>10)return;
        LinearLayout item=new LinearLayout(c);
        item.setGravity(Gravity.CENTER_VERTICAL);

        TextView box=text(c,11,true);
        box.setText("IMDb");
        box.setTextColor(0xFF121212);
        box.setPadding(dp(c,5),dp(c,1),dp(c,5),dp(c,1));
        box.setBackground(rounded(0xFFF5C518,dp(c,3),0));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);
        bp.setMarginEnd(dp(c,5));
        item.addView(box,bp);

        TextView val=text(c,14,true);
        val.setText(String.format(Locale.US,"%.1f",rating));
        val.setTextColor(Color.WHITE);
        item.addView(val);

        item.setContentDescription(String.format(Locale.US,"IMDb %.1f",rating));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.setMarginEnd(dp(c,14));
        parent.addView(item,lp);
    }

    static void critics(Context c,LinearLayout parent,int score){
        if(score<0||score>100)return;
        LinearLayout item=new LinearLayout(c);
        item.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon=new ImageView(c);
        icon.setImageResource(score>=60?R.drawable.cinema_rt_fresh:R.drawable.cinema_rt_rotten);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(c,16),dp(c,16));
        ip.setMarginEnd(dp(c,4));
        item.addView(icon,ip);

        TextView val=text(c,14,true);
        val.setText(String.format(Locale.US,"%d%%",score));
        val.setTextColor(Color.WHITE);
        item.addView(val);

        item.setContentDescription(String.format(Locale.US,"Rotten Tomatoes %d percent",score));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.setMarginEnd(dp(c,14));
        parent.addView(item,lp);
    }

    static void popcorn(Context c,LinearLayout parent,int score){
        if(score<0||score>100)return;
        LinearLayout item=new LinearLayout(c);
        item.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon=new ImageView(c);
        icon.setImageResource(R.drawable.cinema_popcorn);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(c,16),dp(c,16));
        ip.setMarginEnd(dp(c,4));
        item.addView(icon,ip);

        TextView val=text(c,14,true);
        val.setText(String.format(Locale.US,"%d%%",score));
        val.setTextColor(Color.WHITE);
        item.addView(val);

        item.setContentDescription(String.format(Locale.US,"Popcornmeter %d percent",score));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.setMarginEnd(dp(c,14));
        parent.addView(item,lp);
    }

    static void addTmdb(Context c,LinearLayout parent,double rating,int votes){
        if(!Double.isFinite(rating)||rating<=0||rating>10)return;
        LinearLayout item=new LinearLayout(c);
        item.setGravity(Gravity.CENTER_VERTICAL);

        TextView box=text(c,10,true);
        box.setText("TMDb");
        box.setTextColor(0xFF032541);
        box.setPadding(dp(c,5),dp(c,1),dp(c,5),dp(c,1));
        box.setBackground(rounded(0xFF01B4E4,dp(c,3),0));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,-2);
        bp.setMarginEnd(dp(c,5));
        item.addView(box,bp);

        TextView val=text(c,14,true);
        val.setText(String.format(Locale.US,"%.1f",rating));
        val.setTextColor(Color.WHITE);
        item.addView(val);

        item.setContentDescription(String.format(Locale.US,"TMDb %.1f",rating));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.setMarginEnd(dp(c,14));
        parent.addView(item,lp);
    }

    static void addFirst(Context c,LinearLayout parent,String source,double rating,int votes){
        int before=parent.getChildCount();add(c,parent,source,rating,votes);
        if(parent.getChildCount()>before){android.view.View badge=parent.getChildAt(parent.getChildCount()-1);parent.removeView(badge);parent.addView(badge,0);}
    }

    static void add(Context c,LinearLayout parent,String source,double rating,int votes){
        if("IMDb".equals(source))addImdb(c,parent,rating,votes);
        else addTmdb(c,parent,rating,votes);
    }
}
