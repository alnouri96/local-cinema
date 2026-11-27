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
    private final Runnable onDeleteCloud;
    private final Runnable onSaveLocal;
    private final Runnable onRestoreLocal;
    private String liveStatus = "";
    private boolean liveError = false;

    public GoogleAccountHeaderPreference(@NonNull Context context,
                                         Runnable onSignIn,
                                         Runnable onUpload,
                                         Runnable onRestore,
                                         Runnable onSignOut,
                                         Runnable onDeleteCloud,
                                         Runnable onSaveLocal,
                                         Runnable onRestoreLocal) {
        super(context);
        this.onSignIn = onSignIn;
        this.onUpload = onUpload;
        this.onRestore = onRestore;
        this.onSignOut = onSignOut;
        this.onDeleteCloud = onDeleteCloud;
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
        heading.setText(ar ? "النسخ السحابي" : "Cloud Backup");
        heading.setTextColor(GOLD);
        heading.setTextSize(15);
        heading.setSingleLine(true);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
                displayName = ar ? "حساب Google" : "Google Account";
            }
            nameTv.setText(displayName);
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String mail = account.getEmail();
            emailTv.setText(mail != null && !mail.isBlank() ? mail : (ar ? "جاهز للمزامنة ✓" : "Sync ready ✓"));
            emailTv.setSingleLine(true);
            emailTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            emailTv.setTextColor(0xff9de0b8);
        } else {
            nameTv.setText(ar ? "غير مسجل الدخول" : "Not Signed In");
            emailTv.setText(ar ? "حفظ واسترجاع إعدادات الهاتف والشاشة" : "Sync & restore Phone + TV settings");
            emailTv.setSingleLine(true);
            emailTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

        // Compact horizontal action row + "More options" menu so Settings stays clean & uncluttered
        LinearLayout btnRow = new LinearLayout(c);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        var rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(c, 10);
        card.addView(btnRow, rowLp);

        if (account == null) {
            var signBtn = makeBtn(c, ar ? "تسجيل الدخول" : "Sign In", true, onSignIn);
            var lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
            lp1.setMarginEnd(dp(c, 8));
            btnRow.addView(signBtn, lp1);

            var localBtn = makeBtn(c, ar ? "نسخ محلي" : "Local Backup", false,
                    () -> showBackupActions(c, null));
            btnRow.addView(localBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        } else {
            var upBtn = makeBtn(c, ar ? "رفع" : "Upload", true, onUpload);
            var lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
            lp1.setMarginEnd(dp(c, 6));
            btnRow.addView(upBtn, lp1);

            var restBtn = makeBtn(c, ar ? "استرجاع" : "Restore", false, onRestore);
            var lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
            lp2.setMarginEnd(dp(c, 6));
            btnRow.addView(restBtn, lp2);

            var moreBtn = makeBtn(c, ar ? "المزيد" : "More", false,
                    () -> showBackupActions(c, account));
            btnRow.addView(moreBtn, new LinearLayout.LayoutParams(0, -2, 0.9f));
        }
    }

    private void showBackupActions(Context context, GoogleSignInAccount account) {
        var actions = new java.util.ArrayList<CloudBackupDialog.Action>();
        if (account != null) {
            actions.add(new CloudBackupDialog.Action(R.string.cinema_backup_upload_action,
                    R.string.cinema_backup_upload_description, R.drawable.ic_backup_cloud_upload, onUpload));
        }
        actions.add(new CloudBackupDialog.Action(R.string.cinema_backup_restore_cloud,
                R.string.cinema_backup_restore_cloud_description, R.drawable.ic_backup_cloud_restore, onRestore));
        actions.add(new CloudBackupDialog.Action(R.string.cinema_backup_save_local,
                R.string.cinema_backup_save_local_description, R.drawable.ic_backup_local_save, onSaveLocal));
        actions.add(new CloudBackupDialog.Action(R.string.cinema_backup_restore_local,
                R.string.cinema_backup_restore_local_description, R.drawable.ic_backup_local_restore, onRestoreLocal));

        CharSequence connectedAccount = context.getText(R.string.cinema_backup_no_account);
        if (account != null) {
            String email = account.getEmail();
            connectedAccount = android.text.TextUtils.isEmpty(email)
                    ? context.getText(R.string.cinema_backup_connected)
                    : androidx.core.text.BidiFormatter.getInstance().unicodeWrap(email);
            actions.add(new CloudBackupDialog.Action(R.string.cinema_delete_cloud_backup,
                    R.string.cinema_backup_delete_description, R.drawable.ic_delete_24px,
                    CloudBackupDialog.RED, onDeleteCloud));
            actions.add(new CloudBackupDialog.Action(R.string.cinema_backup_sign_out,
                    R.string.cinema_backup_sign_out_description, R.drawable.ic_backup_sign_out,
                    CloudBackupDialog.MUTED, onSignOut));
        }
        CloudBackupDialog.showOptions(context, connectedAccount, actions);
    }

    private static MaterialButton makeBtn(Context c, String text, boolean primary, Runnable click) {
        MaterialButton btn = new MaterialButton(c, null, primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setSingleLine(true);
        btn.setMaxLines(1);
        btn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        btn.setTextSize(13f);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        btn.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        btn.setCornerRadius(dp(c, 12));
        btn.setMinHeight(dp(c, 40));
        if (primary) {
            btn.setBackgroundTintList(ColorStateList.valueOf(GOLD));
            btn.setTextColor(0xff141517);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            btn.setTextColor(GOLD);
            btn.setStrokeColor(ColorStateList.valueOf(0x66f5c518));
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
