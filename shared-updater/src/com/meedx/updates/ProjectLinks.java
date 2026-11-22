package com.meedx.updates;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

public final class ProjectLinks {
    public static final String SOURCE = "https://github.com/alnouri96/local-cinema";
    public static final String LICENSES = SOURCE + "/blob/main/docs/THIRD_PARTY.md";

    private ProjectLinks() {}

    public static void open(Context context, String url, int unavailableMessage) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(context, unavailableMessage, Toast.LENGTH_LONG).show();
        }
    }
}
