package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;

import com.archos.mediacenter.video.R;

final class AmbientYoutubeSettingsDialog {
    static final String QUALITY = "quality", PLAYER = "player";
    static final int[] HEIGHTS = {0, 2160, 1440, 1080, 720};

    static void show(Context context, SharedPreferences prefs) {
        var options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        int inset = CinemaUi.dp(context, 20);
        options.setPaddingRelative(inset, inset, inset, inset);
        CinemaArtSettingsSheet.option(options, prefs, QUALITY, 0, R.string.cinema_ambient_quality,
                R.array.cinema_ambient_qualities, () -> {});
        CinemaArtSettingsSheet.option(options, prefs, PLAYER, 0, R.string.cinema_ambient_player,
                R.array.cinema_ambient_players, () -> {});
        var dialog = CinemaUi.dialog(context).setTitle(R.string.cinema_ambient_playback_settings)
                .setView(options).setNegativeButton(android.R.string.ok, null).show();
        CinemaUi.focusDialogControl(dialog, options.getChildAt(0));
    }

    private AmbientYoutubeSettingsDialog() {}
}
