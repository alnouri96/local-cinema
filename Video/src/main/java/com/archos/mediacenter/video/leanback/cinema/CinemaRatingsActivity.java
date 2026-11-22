package com.archos.mediacenter.video.leanback.cinema;

import android.os.Bundle;
import android.widget.*;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

/** Provider provenance and an explicit, personal-use dataset download. */
public final class CinemaRatingsActivity extends LeanbackActivity {
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView status,download;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        ScrollView scroll=new ScrollView(this);scroll.setBackground(surface());LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(this,48),dp(this,24),dp(this,48),dp(this,24));scroll.addView(column);setContentView(scroll);
        if(arabic(this))scroll.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
        TextView title=text(this,CinemaTokens.PAGE_TITLE,true);title.setText(label(this,"مصادر التقييمات","Rating sources"));column.addView(title);
        status=text(this,CinemaTokens.LABEL,false);
        status.setText(label(this,
                "TMDb: مزوّد البيانات الوصفية المدمج. IMDb: قاعدة البيانات الرسمية للاستخدام الشخصي غير التجاري، محفوظة على هذا الجهاز فقط. تقييمات Rotten Tomatoes تبقى مخفية بدون مزوّد مرخّص.",
                "TMDb: built-in metadata provider. IMDb: official personal/non-commercial dataset, stored only on this device. Rotten Tomatoes stays hidden without an authorized provider."));
        status.setPadding(0,dp(this,16),0,dp(this,16));column.addView(status);
        TextView attribution=text(this,CinemaTokens.CAPTION,false);
        attribution.setText(label(this,
                "يستخدم هذا المنتج واجهة برمجة تطبيقات TMDB ولكنه غير معتمد أو مصادق عليه من قِبل TMDB.\n" +
                "المعلومات مقدمة بإذن من IMDb (https://www.imdb.com).\n" +
                "بيانات التقييم الاختيارية: OMDb API (omdbapi.com) بترخيص CC BY-NC 4.0. شارات RT تصف تقييمات النقاد المقدمة من ذلك المزوّد.",
                "This product uses the TMDB API but is not endorsed or certified by TMDB.\n"+CinemaImdbRatings.CREDIT+"\nOptional rating data: OMDb API (omdbapi.com), CC BY-NC 4.0. RT labels describe critic ratings supplied by that provider."));
        attribution.setTextColor(CinemaTokens.SECONDARY);attribution.setPadding(0,0,0,dp(this,16));column.addView(attribution);
        download=text(this,CinemaTokens.BODY,false);
        download.setText(label(this,"تحميل / تحديث بيانات IMDb للاستخدام الشخصي","Download / update IMDb personal-use data"));
        download.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));focusable(download);column.addView(download);
        download.setOnClickListener(v->{
            download.setEnabled(false);
            status.setText(label(this,"جارٍ تحميل قاعدة بيانات تقييمات IMDb الرسمية…","Downloading the official IMDb ratings dataset…"));
            io.execute(()->{
                try{
                    CinemaImdbRatings.download(getApplicationContext());
                    handler.post(()->done(label(this,"تم تحديث تقييمات IMDb. أعد فتح أي فيلم لقراءة تقييمه.","IMDb ratings updated. Reopen a film to read its rating.")));
                }catch(Exception failure){
                    handler.post(()->done(label(this,"تعذّر تحديث IMDb. تحقّق من الاتصال وحاول مجدداً. تم الاحتفاظ بالبيانات الحالية.","Could not update IMDb. Check the connection and try again. Existing data is preserved.")));
                }
            });
        });
        TextView terms=text(this,CinemaTokens.BODY,false);
        terms.setText(label(this,"شروط مصدر IMDb للاستخدام غير التجاري","IMDb source and non-commercial terms"));
        terms.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));focusable(terms);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(this,8);column.addView(terms,lp);terms.setOnClickListener(v->{try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(CinemaImdbRatings.TERMS)));}catch(android.content.ActivityNotFoundException e){status.setText(CinemaImdbRatings.TERMS);}});
        TextView omdb=text(this,CinemaTokens.BODY,false);
        omdb.setText(label(this,"تعيين مفتاح OMDb API (اختياري لتقييمات IMDb / RT)","Set OMDb API key (optional IMDb / RT)"));
        omdb.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));focusable(omdb);column.addView(omdb,lp);
        omdb.setOnClickListener(v->{
            EditText input=new EditText(this);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            CinemaUi.dialog(this)
                .setTitle(label(this,"مفتاح OMDb API","OMDb API key"))
                .setMessage(label(this,"يُحفظ مفتاح OMDb الخاص بك بشكل منفصل عن Subsource. بيانات التقييم من OMDb API بترخيص CC BY-NC 4.0.","Your own OMDb key is stored separately from Subsource. Rating data: OMDb API / CC BY-NC 4.0. No audience score is inferred."))
                .setView(CinemaPhoneLink.withPasteBadge(input))
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(label(this,"حفظ","Save"),(d,w)->{
                    String key=input.getText().toString().trim();
                    if(!key.matches("[A-Za-z0-9]{8,64}")){
                        status.setText(label(this,"صيغة مفتاح OMDb غير صالحة.","Invalid OMDb key format."));
                        return;
                    }
                    io.execute(()->{
                        try{
                            new ApiKeyStore(getApplicationContext(),"omdb").save(java.util.List.of(key));
                            handler.post(()->{if(!isDestroyed())status.setText(label(this,"تم حفظ مفتاح OMDb بأمان. أعد فتح أي فيلم لجلب التقييمات المتاحة.","OMDb key saved securely. Reopen a film to request available ratings."));});
                        }catch(Exception failure){
                            handler.post(()->{if(!isDestroyed())status.setText(label(this,"تعذّر حفظ مفتاح OMDb.","Could not save OMDb key."));});
                        }
                    });
                }).show();
        });
        TextView remove=text(this,CinemaTokens.BODY,false);
        remove.setText(label(this,"إزالة مفتاح OMDb","Remove OMDb API key"));
        remove.setPadding(dp(this,16),dp(this,12),dp(this,16),dp(this,12));focusable(remove);column.addView(remove,lp);
        remove.setOnClickListener(v->io.execute(()->{
            try{
                new ApiKeyStore(getApplicationContext(),"omdb").save(java.util.List.of());
                handler.post(()->{if(!isDestroyed())status.setText(label(this,"تمت إزالة مفتاح OMDb.","OMDb key removed."));});
            }catch(Exception ignored){}
        }));
        download.requestFocus();
    }
    private void done(String text){if(isDestroyed())return;status.setText(text);download.setEnabled(true);download.requestFocus();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
