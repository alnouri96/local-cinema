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
        options.setClipChildren(false);
        options.setClipToPadding(false);
        options.setPaddingRelative(CinemaUi.dp(context, 28), CinemaUi.dp(context, 26),
                CinemaUi.dp(context, 28), CinemaUi.dp(context, 26));
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
        var parent = options.getParent();
        for (int depth = 0; depth < 4 && parent instanceof android.view.ViewGroup group; depth++) {
            group.setClipChildren(false);
            group.setClipToPadding(false);
            parent = group.getParent();
        }
        CinemaUi.focusDialogControl(dialog, art);
    }

    private static android.view.View add(LinearLayout options, int label, int icon, Runnable open) {
        Context context = options.getContext();
        var tile = CinemaAddonTilePresenter.create(context, context.getString(label), icon, null,
                null, new Handler(Looper.getMainLooper()), 148, open);
        var size = new LinearLayout.LayoutParams(0, CinemaUi.dp(context, 148), 1);
        if (options.getChildCount() > 0) size.setMarginStart(CinemaUi.dp(context, 18));
        options.addView(tile, size);
        return tile;
    }
}
