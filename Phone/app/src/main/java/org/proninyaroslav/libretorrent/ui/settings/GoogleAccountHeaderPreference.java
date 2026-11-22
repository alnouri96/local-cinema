package org.proninyaroslav.libretorrent.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.os.ConfigurationCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.bumptech.glide.Glide;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.material.button.MaterialButton;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.cinema_hub.LocalCinemaBackupManager;

/**
 * Top Settings card for Google Account authentication & unified cloud backup (Phone + TV),
 * showing circular profile photo (loaded via Glide), user display name/email, live status,
 * Upload / Restore / Sign Out controls.
 */
public final class GoogleAccountHeaderPreference extends Preference {
    private static final int GOLD = 0xfff5c518;
    private static final int CARD_ID = 0x51000101;

    private final Runnable onSignIn;
    private final Runnable onUpload;
    private final Runnable onRestore;
    private final Runnable onSignOut;
    private final Runnable onSaveLocal;
    private final Runnable onRestoreLocal;
    private String liveStatus = "";
    private boolean liveError = false;

    public GoogleAccountHeaderPreference(@NonNull Context context,
                                         Runnable onSignIn,
                                         Runnable onUpload,
                                         Runnable onRestore,
                                         Runnable onSignOut,
                                         Runnable onSaveLocal,
                                         Runnable onRestoreLocal) {
        super(context);
        this.onSignIn = onSignIn;
        this.onUpload = onUpload;
        this.onRestore = onRestore;
        this.onSignOut = onSignOut;
        this.onSaveLocal = onSaveLocal;
        this.onRestoreLocal = onRestoreLocal;
        setKey("google_account_cloud_backup");
        setPersistent(false);
        setSelectable(false);
        setOrder(0);
        setLayoutResource(R.layout.pref_widget_summary); // replaced dynamically cleanly in onBindViewHolder
    }

    public void setLiveStatus(String text, boolean isError) {
        this.liveStatus = text == null ? "" : text;
        this.liveError = isError;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Context c = getContext();
        boolean ar = isArabic(c);

        if (!(holder.itemView instanceof ViewGroup rootGroup)) return;
        rootGroup.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        for (int i = 0; i < rootGroup.getChildCount(); i++) {
            View child = rootGroup.getChildAt(i);
            if (child.getId() != CARD_ID) child.setVisibility(View.GONE);
        }

        LinearLayout card = rootGroup.findViewById(CARD_ID);
        if (card != null) rootGroup.removeView(card);

        card = new LinearLayout(c);
        card.setId(CARD_ID);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1d1e21);
        bg.setStroke(dp(c, 1), 0xff4c4637);
        bg.setCornerRadius(dp(c, 16));
        card.setBackground(bg);
        card.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        rootGroup.addView(card, new ViewGroup.LayoutParams(-1, -2));

        TextView heading = new TextView(c);
        heading.setText(ar ? "حساب Google والنسخ الاحتياطي السحابي" : "Google Account & Cloud Backup");
        heading.setTextColor(GOLD);
        heading.setTextSize(16);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        card.addView(heading);

        GoogleSignInAccount account = LocalCinemaBackupManager.getSignedInGoogleAccount(c);

        // User Profile Header Row
        LinearLayout profileRow = new LinearLayout(c);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        var prLp = new LinearLayout.LayoutParams(-1, -2);
        prLp.topMargin = dp(c, 10);
        card.addView(profileRow, prLp);

        ImageView avatar = new ImageView(c);
        int avSize = dp(c, 52);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setShape(GradientDrawable.OVAL);
        avBg.setColor(0xff2a2b30);
        avBg.setStroke(dp(c, 2), GOLD);
        avatar.setBackground(avBg);
        avatar.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        Uri photoUrl = account != null ? account.getPhotoUrl() : null;
        if (photoUrl != null) {
            Glide.with(c)
                    .load(photoUrl)
                    .circleCrop()
                    .placeholder(R.drawable.ic_person_account_24)
                    .error(R.drawable.ic_person_account_24)
                    .into(avatar);
        } else {
            avatar.setImageResource(R.drawable.ic_person_account_24);
            avatar.setColorFilter(GOLD);
        }
        var avLp = new LinearLayout.LayoutParams(avSize, avSize);
        avLp.setMarginEnd(dp(c, 12));
        profileRow.addView(avatar, avLp);

        LinearLayout userInfo = new LinearLayout(c);
        userInfo.setOrientation(LinearLayout.VERTICAL);
        profileRow.addView(userInfo, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView nameTv = new TextView(c);
        nameTv.setTextSize(15);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(0xfff2ede7);
        nameTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);

        TextView emailTv = new TextView(c);
        emailTv.setTextSize(12);
        emailTv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);

        if (account != null) {
            String displayName = account.getDisplayName();
            if (displayName == null || displayName.isBlank()) {
                displayName = ar ? "حساب Google المرتبط" : "Connected Google Account";
            }
            nameTv.setText(displayName);
            String mail = account.getEmail();
            emailTv.setText((mail != null ? mail + "\n" : "")
                    + (ar ? "المزامنة السحابية جاهزة (الهاتف + الشاشة) ✓"
                          : "Cloud sync ready (Phone + TV) ✓"));
            emailTv.setTextColor(0xff9de0b8);
        } else {
            nameTv.setText(ar ? "غير مسجل الدخول" : "Not Signed In");
            emailTv.setText(ar
                    ? "سجّل الدخول لحفظ واسترجاع إعدادات الهاتف والشاشة عبر Google Drive بضغطة واحدة."
                    : "Sign in with Google to upload or restore unified Phone + TV backups cleanly.");
            emailTv.setTextColor(0xffb8b2a8);
        }
        userInfo.addView(nameTv);
        userInfo.addView(emailTv);

        if (!liveStatus.isBlank()) {
            TextView statusBanner = new TextView(c);
            statusBanner.setText(liveStatus);
            statusBanner.setTextSize(12);
            statusBanner.setSingleLine(false);
            statusBanner.setMaxLines(6);
            statusBanner.setTextColor(liveError ? 0xffffc7c7 : 0xffefe7d6);
            statusBanner.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
            GradientDrawable sbBg = new GradientDrawable();
            sbBg.setColor(liveError ? 0xff2b1f1f : 0xff23221d);
            sbBg.setStroke(dp(c, 1), liveError ? 0xff9e5454 : 0xff6e5e36);
            sbBg.setCornerRadius(dp(c, 10));
            statusBanner.setBackground(sbBg);
            var sbLp = new LinearLayout.LayoutParams(-1, -2);
            sbLp.topMargin = dp(c, 10);
            card.addView(statusBanner, sbLp);
        }

        // Action Buttons
        if (account == null) {
            var signBtn = makeBtn(c, ar ? "تسجيل الدخول بحساب Google" : "Sign In with Google", true, onSignIn);
            var lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(c, 10);
            card.addView(signBtn, lp);

            var restBtn = makeBtn(c, ar ? "استرجاع من Google وإطلاقها للشاشة" : "Restore from Google & Push to TV", false, onRestore);
            var rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = dp(c, 6);
            card.addView(restBtn, rLp);
        } else {
            var upBtn = makeBtn(c, ar ? "رفع النسخة الاحتياطية الآن (الهاتف + الشاشة)" : "Upload Backup Now (Phone + TV)", true, onUpload);
            var uLp = new LinearLayout.LayoutParams(-1, -2);
            uLp.topMargin = dp(c, 10);
            card.addView(upBtn, uLp);

            var restBtn = makeBtn(c, ar ? "استرجاع من Google وإطلاقها للشاشة" : "Restore from Google & Push to TV", false, onRestore);
            var rLp = new LinearLayout.LayoutParams(-1, -2);
            rLp.topMargin = dp(c, 6);
            card.addView(restBtn, rLp);

            var outBtn = makeBtn(c, ar ? "تسجيل الخروج" : "Sign Out", false, onSignOut);
            var oLp = new LinearLayout.LayoutParams(-1, -2);
            oLp.topMargin = dp(c, 6);
            card.addView(outBtn, oLp);
        }
        var saveLocal = makeBtn(c, ar ? "حفظ نسخة محلية (الهاتف + الشاشة)"
                : "Save Backup Locally (Phone + TV)", false, onSaveLocal);
        var saveParams = new LinearLayout.LayoutParams(-1, -2);
        saveParams.topMargin = dp(c, 12);
        card.addView(saveLocal, saveParams);

        var restoreLocal = makeBtn(c, ar ? "استعادة نسخة محلية وإرسالها إلى الشاشة"
                : "Restore Local Backup & Push to TV", false, onRestoreLocal);
        var restoreParams = new LinearLayout.LayoutParams(-1, -2);
        restoreParams.topMargin = dp(c, 6);
        card.addView(restoreLocal, restoreParams);
    }

    private static MaterialButton makeBtn(Context c, String text, boolean primary, Runnable click) {
        MaterialButton btn = new MaterialButton(c, null, primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setSingleLine(false);
        btn.setMaxLines(2);
        btn.setCornerRadius(dp(c, 12));
        btn.setMinHeight(dp(c, 42));
        if (primary) {
            btn.setBackgroundTintList(ColorStateList.valueOf(GOLD));
            btn.setTextColor(0xff141517);
        } else {
            btn.setTextColor(GOLD);
        }
        btn.setOnClickListener(v -> { if (click != null) click.run(); });
        return btn;
    }

    private static boolean isArabic(Context c) {
        var locale = ConfigurationCompat.getLocales(c.getResources().getConfiguration()).get(0);
        return locale != null && "ar".equals(locale.getLanguage());
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
