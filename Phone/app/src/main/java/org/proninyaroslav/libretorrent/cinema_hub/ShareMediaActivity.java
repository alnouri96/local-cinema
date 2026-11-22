package org.proninyaroslav.libretorrent.cinema_hub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class ShareMediaActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var shared = getIntent();
        String action = shared.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            var target = new Intent(this, TvHubActivity.class).setAction(action).setType(shared.getType())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            target.setClipData(shared.getClipData());
            if (Intent.ACTION_SEND.equals(action)) {
                android.net.Uri uri = shared.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) target.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                java.util.ArrayList<android.net.Uri> uris = shared.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (uris != null) target.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            startActivity(target);
        }
        finish();
    }
}
