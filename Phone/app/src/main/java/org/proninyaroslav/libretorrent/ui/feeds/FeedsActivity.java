package org.proninyaroslav.libretorrent.ui.feeds;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.proninyaroslav.libretorrent.MainActivity;

/**
 * Entry point to open the RSS Channels & Feeds screen ({@link FeedFragment}) from RSS Settings
 * or external callers while keeping full MainActivity / NavBarFragment host support and Back navigation.
 */
public final class FeedsActivity extends AppCompatActivity {

    public static void open(@NonNull Context context) {
        if (context instanceof MainActivity mainActivity && mainActivity.openFeedsScreen()) {
            return;
        }
        Intent intent = new Intent(context, FeedsActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
        if (context instanceof Activity act) {
            act.overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(MainActivity.EXTRA_OPEN_FEEDS, true));
        overridePendingTransition(0, 0);
        finish();
    }
}
