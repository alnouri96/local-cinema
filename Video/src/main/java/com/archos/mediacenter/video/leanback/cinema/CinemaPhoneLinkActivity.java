package com.archos.mediacenter.video.leanback.cinema;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import org.json.JSONObject;
import static com.archos.mediacenter.video.leanback.cinema.CinemaUi.*;

public final class CinemaPhoneLinkActivity extends LeanbackActivity {
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private android.content.SharedPreferences prefs;
    private LinearLayout profiles;
    private TextView status;
    private TextView address;
    private ImageView code;
    private String renderedPayload;
    private String renderedProfiles;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 750);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        CinemaLinkServer.startFor(this);
        prefs = getSharedPreferences("cinema_link", 0);
        LinearLayout page = column();
        page.setBackground(surface());
        page.setPadding(dp(this, 48), dp(this, 24), dp(this, 48), dp(this, 24));
        setContentView(page);
        page.addView(heading(getString(com.archos.mediacenter.video.R.string.link_title), 28));
        LinearLayout columns = new LinearLayout(this);
        page.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout pairing = panel(columns);
        pairing.setGravity(Gravity.CENTER_HORIZONTAL);
        code = new ImageView(this);
        code.setContentDescription(getString(com.archos.mediacenter.video.R.string.link_qr_description));
        code.setBackground(rounded(0xffe5b84b, dp(this, 12), 0));
        code.setPadding(dp(this, 3), dp(this, 3), dp(this, 3), dp(this, 3));
        pairing.addView(code, new LinearLayout.LayoutParams(dp(this, 166), dp(this, 166)));
        status = heading("", 14);
        status.setGravity(Gravity.CENTER);
        pairing.addView(status);
        address = heading("", 12);
        address.setGravity(Gravity.CENTER);
        pairing.addView(address);
        TextView refresh = action(pairing, getString(com.archos.mediacenter.video.R.string.link_refresh), () -> {
            CinemaLinkServer.refresh();
            render();
        });
        LinearLayout saved = panel(columns);
        saved.addView(heading(getString(com.archos.mediacenter.video.R.string.link_paired_phones), 20));
        ScrollView list = new ScrollView(this);
        saved.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        profiles = column();
        list.addView(profiles);
        action(page, getString(com.archos.mediacenter.video.R.string.link_settings), this::settings);
        refresh.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        update.run();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(update);
        super.onStop();
    }

    private void render() {
        try {
            String payload = CinemaLinkServer.payload();
            if (!java.util.Objects.equals(payload, renderedPayload)) {
                renderedPayload = payload;
                if (payload != null) {
                    var endpoint = new JSONObject(payload);
                    android.util.Log.i("CinemaLink", "QR endpoint " + endpoint.optString("ip")
                            + ":" + endpoint.optInt("port"));
                }
                code.setImageBitmap(payload == null ? null : CinemaPhoneLink.qrBitmap(payload, 512));
            }
            code.setVisibility(payload == null ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            var endpoint = payload == null ? null : new JSONObject(payload);
            updateText(address, endpoint == null ? CinemaLinkServer.availability()
                    : getString(com.archos.mediacenter.video.R.string.link_server_active, "\u2066" + endpoint.optString("ip") + ":" + endpoint.optInt("port") + "\u2069"));
            String active = prefs.getString("active", "");
            var keys = new java.util.TreeSet<>(prefs.getAll().keySet());
            StringBuilder signature = new StringBuilder(active);
            String connected = null;
            for (String key : keys) {
                if (!key.startsWith("phone:")) continue;
                String id = key.substring(6);
                boolean online = CinemaLinkServer.online(id);
                String raw = prefs.getString(key, "{}");
                signature.append(key).append(raw).append(online);
                if (online && (connected == null || active.equals(id)))
                    connected = new JSONObject(raw).optString("name");
            }
            updateText(status, connected == null ? (payload == null ? "" : getString(com.archos.mediacenter.video.R.string.link_waiting))
                    : getString(com.archos.mediacenter.video.R.string.link_connected, connected));
            status.setTextColor(connected == null ? 0xffe5b84b : 0xffa9d8bb);
            if (signature.toString().equals(renderedProfiles)) return;
            renderedProfiles = signature.toString();
            var focused = profiles.findFocus();
            Object selected = focused == null ? null : focused.getTag();
            profiles.removeAllViews();
            for (String key : keys) {
                if (!key.startsWith("phone:")) continue;
                String id = key.substring(6);
                var profile = new JSONObject(prefs.getString(key, "{}"));
                TextView item = action(profiles, profile.optString("name") + (active.equals(id)
                        ? "  ·  Active Controller" : ""), () -> {
                    prefs.edit().putString("active", id).apply();
                    render();
                });
                item.setTag(id);
                TextView detail = heading((CinemaLinkServer.online(id) ? getString(com.archos.mediacenter.video.R.string.link_online) : getString(com.archos.mediacenter.video.R.string.link_offline)) + " · "
                        + android.text.format.DateUtils.getRelativeTimeSpanString(profile.optLong("last")), 13);
                detail.setTextColor(CinemaLinkServer.online(id) ? 0xffa9d8bb : 0xffb8b8b8);
                profiles.addView(detail);
                var forget = action(profiles, getString(com.archos.mediacenter.video.R.string.link_forget), () -> dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.link_forget_prompt))
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(getString(com.archos.mediacenter.video.R.string.link_forget), (d, which) -> {
                            CinemaLinkServer.forget(id);
                            render();
                        }).show());
                forget.setTag("forget:" + id);
                if (id.equals(selected)) item.requestFocus();
                if (("forget:" + id).equals(selected)) forget.requestFocus();
            }
            if (profiles.getChildCount() == 0) profiles.addView(heading(getString(com.archos.mediacenter.video.R.string.link_no_phones), 16));
        } catch (Exception error) {
            status.setText(getString(com.archos.mediacenter.video.R.string.link_load_failed));
        }
    }

    private void updateText(TextView view, String value) {
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void settings() {
        LinearLayout options = column();
        action(options, getString(com.archos.mediacenter.video.R.string.link_remote), () -> startActivity(
                new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        options.setPadding(dp(this, 24), 0, dp(this, 24), 0);
        setting(options, getString(com.archos.mediacenter.video.R.string.link_auto_connect), "auto_connect");
        setting(options, getString(com.archos.mediacenter.video.R.string.link_select_single), "select_single");
        action(options, "WebSocket Port · " + prefs.getInt("port", 8765), this::editPort);
        action(options, getString(com.archos.mediacenter.video.R.string.link_clear_profiles), () -> dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.link_clear_prompt))
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(com.archos.mediacenter.video.R.string.cinema_link_clear, (d, which) -> {
                    for (String key : prefs.getAll().keySet()) {
                        if (key.startsWith("phone:")) CinemaLinkServer.forget(key.substring(6));
                    }
                    CinemaLinkServer.refresh();
                    render();
                }).show());
        dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.link_settings)).setView(options)
                .setPositiveButton(com.archos.mediacenter.video.R.string.cinema_link_done, null).show();
    }

    private void editPort() {
        var input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_LTR);
        input.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        input.setText(String.valueOf(prefs.getInt("port", 8765)));
        var editor = dialog(this).setTitle(getString(com.archos.mediacenter.video.R.string.link_port)).setView(input)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(com.archos.mediacenter.video.R.string.cinema_link_save, null).show();
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int port;
            try {
                port = Integer.parseInt(input.getText().toString().trim());
                if (port < 1024 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                input.setError(getString(com.archos.mediacenter.video.R.string.link_invalid_port));
                return;
            }
            prefs.edit().putInt("port", port).apply();
            android.widget.Toast.makeText(this, getString(com.archos.mediacenter.video.R.string.link_restart), 1).show();
            editor.dismiss();
        });
    }

    private void setting(LinearLayout parent, String title, String key) {
        var option = new android.widget.CheckBox(this);
        option.setText(title);
        option.setChecked(prefs.getBoolean(key, true));
        option.setOnCheckedChangeListener((v, enabled) -> prefs.edit().putBoolean(key, enabled).apply());
        parent.addView(option);
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout panel = column();
        panel.setBackground(rounded(0xff1c1c1e, dp(this, 16), 0));
        panel.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 12));
        var layout = new LinearLayout.LayoutParams(0, -1, 1);
        layout.setMargins(dp(this, 6), dp(this, 12), dp(this, 6), dp(this, 12));
        parent.addView(panel, layout);
        return panel;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView heading(String title, int size) {
        TextView view = text(this, size, size >= 20);
        view.setText(title);
        return view;
    }

    private TextView action(LinearLayout parent, String title, Runnable run) {
        TextView view = heading(title, 15);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(this, 48));
        view.setPadding(dp(this, 12), 0, dp(this, 12), 0);
        focusable(view);
        view.setOnClickListener(v -> run.run());
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
}
