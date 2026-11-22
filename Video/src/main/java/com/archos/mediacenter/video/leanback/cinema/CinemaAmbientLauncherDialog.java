package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;

import com.archos.mediacenter.video.R;

final class CinemaAmbientLauncherDialog {
    static void show(Context context) {
        var options = new LinearLayout(context);
        options.setPaddingRelative(CinemaUi.dp(context, 24), CinemaUi.dp(context, 18),
                CinemaUi.dp(context, 24), CinemaUi.dp(context, 18));
        var dialog = CinemaUi.dialog(context).setTitle(R.string.cinema_ambient_title).setView(options).create();
        var art = add(options, R.string.cinema_art_mode, R.drawable.cinema_ambient_frame, () -> {
            dialog.dismiss();
            context.startActivity(new Intent(context, CinemaArtModeActivity.class));
        });
        add(options, R.string.cinema_ambient_youtube, R.drawable.cinema_live_tv, () -> {
            dialog.dismiss();
            context.startActivity(new Intent(context, CinemaAmbientYoutubeActivity.class));
        });
        dialog.show();
        CinemaUi.focusDialogControl(dialog, art);
    }

    private static android.view.View add(LinearLayout options, int label, int icon, Runnable open) {
        Context context = options.getContext();
        var tile = CinemaAddonTilePresenter.create(context, context.getString(label), icon, null,
                null, new Handler(Looper.getMainLooper()), 144, open);
        var size = new LinearLayout.LayoutParams(0, CinemaUi.dp(context, 144), 1);
        if (options.getChildCount() > 0) size.setMarginStart(CinemaUi.dp(context, 14));
        options.addView(tile, size);
        return tile;
    }
}
