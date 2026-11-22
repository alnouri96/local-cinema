package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.dp;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.label;

public final class CinemaPhoneLink {
    private CinemaPhoneLink() {}

    static String instructions(Context context) {
        return label(context, "افتح تطبيق Cinema Manager في هاتفك وامسح الرمز للربط",
                "Open Cinema Manager on your phone and scan the code to link");
    }

    static void show(Context context) {
        context.startActivity(new android.content.Intent(context, CinemaPhoneLinkActivity.class));
    }

    static Bitmap qrBitmap(String payload, int size) throws com.google.zxing.WriterException {
        var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    public static TextView pasteBadge(Context context) {
        TextView badge = CinemaUi.text(context, 12, false);
        badge.setText(label(context, "📲 جاهز لاستقبال اللصق من الهاتف", "📲 Ready to receive paste from phone"));
        badge.setTextColor(CinemaTokens.GOLD);
        badge.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
        return badge;
    }

    static LinearLayout withPasteBadge(EditText input) {
        LinearLayout content = column(input.getContext());
        content.addView(pasteBadge(input.getContext()));
        content.addView(input);
        CinemaLinkServer.registerDialogField(input);
        return content;
    }

    private static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(context, 24);
        column.setPadding(inset, dp(context, 12), inset, dp(context, 12));
        return column;
    }
}
