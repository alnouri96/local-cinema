package org.proninyaroslav.libretorrent.cinema_hub;

import android.os.Bundle;
import com.journeyapps.barcodescanner.CaptureActivity;
import org.proninyaroslav.libretorrent.R;

public final class TvQrScannerActivity extends CaptureActivity {
    @Override
    protected void onCreate(Bundle state) {
        setTheme(R.style.AppTheme);
        super.onCreate(state);
    }

    @Override
    protected com.journeyapps.barcodescanner.DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.cinema_qr_scanner);
        return findViewById(R.id.cinema_barcode_view);
    }
}
