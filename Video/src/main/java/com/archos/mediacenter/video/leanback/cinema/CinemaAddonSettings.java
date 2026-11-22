package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.archos.mediacenter.video.R;
import com.meedx.providers.StremioAddons;

import java.util.List;
import java.util.concurrent.ExecutorService;

final class CinemaAddonSettings {
    private final Activity host;
    private final ExecutorService io;
    private final Handler main;
    private final Runnable onClosed;
    private LinearLayout rows;
    private TextView count;
    private boolean changed;

    CinemaAddonSettings(Activity host, ExecutorService io, Handler main, Runnable onClosed) {
        this.host = host;
        this.io = io;
        this.main = main;
        this.onClosed = onClosed;
    }

    void show() {
        rows = column();
        count = CinemaUi.text(host, 16, true);
        count.setTextColor(CinemaTokens.GOLD);
        rows.addView(count);
        updateCount();
        var history = choice(rows, host.getString(R.string.addon_continue_watching), "",
                CinemaWatchStore.includeAddonContinue(host));
        history.setMinHeight(dp(66));
        updateHistoryLabel(history, history.isChecked());
        history.setOnCheckedChangeListener((button, enabled) -> {
            CinemaWatchStore.setIncludeAddonContinue(host, enabled);
            updateHistoryLabel(history, enabled);
            changed = true;
        });
        rows.addView(card(R.drawable.cinema_ic_system, R.string.addon_installed_settings, 0, this::manageInstalled));
        rows.addView(card(R.drawable.cinema_ic_addons, R.string.addon_choose_enabled, R.string.addon_choose_hint, this::chooseInstalled));
        rows.addView(card(R.drawable.cinema_ic_posters, R.string.addon_recommended, R.string.addon_recommended_hint, this::chooseRecommended));
        rows.addView(card(R.drawable.cinema_ic_addons, R.string.addon_add_manifest, 0, this::paste));
        rows.addView(card(R.drawable.cinema_ic_system, R.string.addon_sync_now, 0, () -> Toast.makeText(host,
                CinemaLinkServer.requestAddonSync() ? R.string.addon_sync_requested : R.string.addon_pair_phone, Toast.LENGTH_SHORT).show()));
        rows.addView(card(R.drawable.cinema_ic_timer, R.string.addon_connection_test, 0, this::diagnose));
        var dialog = sheet(R.string.addon_manage, rows).setNegativeButton(R.string.addon_back, null).create();
        dialog.setOnDismissListener(d -> { if (changed && !host.isDestroyed() && !host.isFinishing()) onClosed.run(); });
        showSheet(dialog, rows);
    }

    private void updateHistoryLabel(android.widget.CheckBox history, boolean enabled) {
        history.setText(host.getString(R.string.addon_continue_watching) + "\n" + host.getString(
                enabled ? R.string.addon_enabled_label : R.string.addon_disabled_label));
    }

    private List<StremioAddons.Addon> installed() {
        return StremioAddons.list(host).stream().filter(addon -> !StremioAddons.subtitleOnly(addon))
                .collect(java.util.stream.Collectors.toList());
    }

    private void updateCount() {
        count.setText(host.getString(R.string.addon_active_count, installed().stream().filter(addon -> addon.enabled).count()));
    }

    private void manageInstalled() {
        var content = column();
        var dialog = sheet(R.string.addon_installed_settings, content)
                .setNegativeButton(R.string.addon_back, null).create();
        renderInstalled(content, dialog);
        showSheet(dialog, content);
    }

    private void renderInstalled(LinearLayout content, AlertDialog dialog) {
        content.removeAllViews();
        for (var addon : installed()) {
            int index = content.getChildCount();
            var item = CinemaUi.text(host, 17, true);
            item.setText(addon.name + "\n" + host.getString(addon.enabled
                    ? R.string.addon_enabled_label : R.string.addon_disabled_label));
            decorate(item);
            item.setOnClickListener(v -> manageAddon(addon, () -> {
                renderInstalled(content, dialog);
                View next = content.getChildAt(Math.min(index, content.getChildCount() - 1));
                CinemaUi.focusDialogControl(dialog, next != null ? next : dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            }));
            content.addView(item, cardSize());
        }
    }

    private void manageAddon(StremioAddons.Addon addon, Runnable refresh) {
        var content = column();
        var dialog = sheet(R.string.addon_installed_settings, content).setTitle(addon.name)
                .setNegativeButton(R.string.addon_back, null).create();
        var needsRefresh = new boolean[1];
        dialog.setOnDismissListener(d -> { if (needsRefresh[0]) refresh.run(); });
        var enabled = choice(content, host.getString(R.string.addon_enabled_label), "", addon.enabled);
        enabled.setOnCheckedChangeListener((button, value) -> {
            StremioAddons.setEnabled(host, addon.manifestUrl, value);
            CinemaLinkServer.sendAddonEnabled(addon.manifestUrl, value);
            changed = true;
            updateCount();
            needsRefresh[0] = true;
        });
        content.addView(card(R.drawable.cinema_ic_addons, R.string.addon_edit_manifest, R.string.addon_edit_manifest_hint,
                () -> editManifest(addon, () -> { needsRefresh[0] = true; dialog.dismiss(); })));
        content.addView(card(R.drawable.cinema_ic_timer, R.string.addon_test_movie, R.string.addon_test_movie_hint, () -> {
            if (!StremioAddons.mayStream(addon)) { toast(R.string.addon_cannot_stream_movies); return; }
            CinemaAddonPicker.load(host, io, main, R.string.addon_connection_testing,
                    () -> StremioAddons.testMovie(addon), health -> {
                        if (health == null) return;
                        int message = health.unavailable() ? R.string.addon_connection_failed : R.string.addon_health_sources;
                        String result = health.unavailable()
                                ? host.getString(message, addon.name, health.millis())
                                : host.getString(message, addon.name, health.results(), health.millis());
                        CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(host).setTitle(R.string.addon_test_movie)
                                .setMessage(result).setNegativeButton(R.string.addon_back, null), AlertDialog.BUTTON_NEGATIVE);
                    });
        }));
        content.addView(card(R.drawable.cinema_ic_system, R.string.addon_remove, 0, () ->
                CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(host).setTitle(R.string.addon_remove)
                        .setMessage(host.getString(R.string.addon_remove_confirm, addon.name))
                        .setNegativeButton(R.string.addon_back, null)
                        .setPositiveButton(R.string.addon_remove, (d, w) -> {
                            StremioAddons.remove(host, addon.manifestUrl);
                            CinemaLinkServer.sendAddonRemoved(addon.manifestUrl);
                            changed = true;
                            updateCount();
                            needsRefresh[0] = true;
                            dialog.dismiss();
                        }), AlertDialog.BUTTON_POSITIVE)));
        showSheet(dialog, content);
    }

    private void editManifest(StremioAddons.Addon addon, Runnable onSaved) {
        var field = new EditText(host);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        field.setText(addon.manifestUrl);
        field.selectAll();
        var configUrl = StremioAddons.configurationPage(addon);
        decorate(field);
        var dialog = CinemaUi.dialog(host).setTitle(R.string.addon_edit_manifest)
                .setView(CinemaPhoneLink.withPasteBadge(field))
                .setNegativeButton(R.string.addon_back, null)
                .setNeutralButton(R.string.addon_configure_on_web, (d, w) -> {
                    try {
                        host.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(configUrl)));
                    } catch (android.content.ActivityNotFoundException missing) {
                        CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(host).setTitle(R.string.addon_configure_on_web)
                                .setMessage(configUrl).setNegativeButton(R.string.addon_back, null), AlertDialog.BUTTON_NEGATIVE);
                    }
                })
                .setPositiveButton(R.string.addon_save_apply, (d, w) -> {
                    String url = field.getText().toString();
                    CinemaAddonPicker.load(host, io, main, R.string.addon_loading, () -> {
                            String normalized = StremioAddons.normalizeManifestUrl(url);
                            StremioAddons.replaceManifest(host.getApplicationContext(), addon, normalized);
                            if (!normalized.equals(addon.manifestUrl)) CinemaLinkServer.sendAddonRemoved(addon.manifestUrl);
                            CinemaLinkServer.broadcastAddonsSync(host.getApplicationContext());
                            return true;
                        }, saved -> {
                            if (!Boolean.TRUE.equals(saved)) { toast(R.string.addon_manifest_error); return; }
                            changed = true;
                            updateCount();
                            onSaved.run();
                        });
                }).show();
        CinemaUi.focusDialogControl(dialog, field);
    }

    private void chooseInstalled() {
        var addons = installed();
        if (addons.isEmpty()) { toast(R.string.addon_empty); return; }
        var content = column();
        var checks = new java.util.ArrayList<android.widget.CheckBox>();
        for (var addon : addons) {
            String description = StremioAddons.localizedDescription(addon.description, host);
            if (StremioAddons.connectionIssue(addon)) description = host.getString(R.string.addon_unavailable) + " · " + description;
            checks.add(choice(content, addon.name, description, addon.enabled));
        }
        var dialog = sheet(R.string.addon_choose_enabled, content)
                .setNegativeButton(R.string.addon_back, null)
                .setPositiveButton(R.string.addon_save_apply, null).create();
        showSheet(dialog, content);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            var updates = new java.util.LinkedHashMap<String, Boolean>();
            var current = installed();
            for (int i = 0; i < checks.size(); i++) {
                String url = addons.get(i).manifestUrl;
                boolean enabled = checks.get(i).isChecked();
                if (current.stream().anyMatch(addon -> addon.manifestUrl.equals(url) && addon.enabled != enabled)) {
                    updates.put(url, enabled);
                }
            }
            StremioAddons.setEnabled(host, updates);
            updates.forEach(CinemaLinkServer::sendAddonEnabled);
            changed |= !updates.isEmpty();
            updateCount();
            toast(R.string.addon_preferences_saved);
        });
    }

    private void chooseRecommended() {
        CinemaAddonPicker.load(host, io, main, R.string.addon_loading, () -> StremioAddons.fetchCurated(host), recommendations -> {
            if (recommendations == null || recommendations.isEmpty()) { toast(R.string.addon_manifest_error); return; }
            var content = column();
            var checks = new java.util.ArrayList<android.widget.CheckBox>();
            for (var addon : recommendations) {
                boolean active = installed().stream().anyMatch(item -> item.enabled && item.manifestUrl.equals(addon.manifestUrl));
                checks.add(choice(content, addon.name, addon.description, active));
            }
            var dialog = sheet(R.string.addon_recommended, content).setNegativeButton(R.string.addon_back, null)
                    .setPositiveButton(R.string.addon_save_apply, null).create();
            showSheet(dialog, content);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                var urls = new java.util.ArrayList<String>();
                for (int i = 0; i < checks.size(); i++) {
                    var addon = recommendations.get(i);
                    if (checks.get(i).isChecked() && installed().stream().noneMatch(item -> item.enabled
                            && item.manifestUrl.equals(addon.manifestUrl))) urls.add(addon.manifestUrl);
                }
                if (!urls.isEmpty()) install(urls);
            });
        });
    }

    private void paste() {
        var field = new EditText(host);
        field.setHint(R.string.addon_manifest_hint);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        decorate(field);
        var dialog = CinemaUi.dialog(host).setTitle(R.string.addon_add_manifest).setView(CinemaPhoneLink.withPasteBadge(field))
                .setNegativeButton(R.string.addon_back, null).setPositiveButton(R.string.addon_save_apply,
                        (d, w) -> { if (!field.getText().toString().isBlank()) install(List.of(field.getText().toString())); }).show();
        CinemaUi.focusDialogControl(dialog, field);
    }

    private void install(List<String> urls) {
        CinemaAddonPicker.load(host, io, main, R.string.addon_installing_on_tv, () -> {
            int saved = 0;
            for (String url : urls) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    StremioAddons.fetchAndAdd(host.getApplicationContext(), url);
                    saved++;
                } catch (Exception ignored) {}
            }
            if (saved > 0) CinemaLinkServer.broadcastAddonsSync(host.getApplicationContext());
            return saved;
        }, saved -> {
            if (saved == null) return;
            changed |= saved > 0;
            updateCount();
            Toast.makeText(host, host.getString(R.string.addon_install_summary, saved, urls.size() - saved), Toast.LENGTH_LONG).show();
        });
    }

    private void diagnose() {
        CinemaAddonPicker.load(host, io, main, R.string.addon_connection_testing,
                () -> StremioAddons.probeAddons(installed()), timings -> {
                    if (timings == null || timings.isEmpty()) { toast(R.string.addon_empty); return; }
                    var content = column();
                    for (var item : timings) {
                        var label = CinemaUi.text(host, 16, true);
                        label.setText(host.getString(item.ok() ? R.string.addon_connection_ok : R.string.addon_connection_failed,
                                item.name(), item.millis()));
                        decorate(label);
                        content.addView(label, cardSize());
                    }
                    CinemaUi.showDialogWithButtonFocus(sheet(R.string.addon_connection_test, content)
                            .setNegativeButton(R.string.addon_back, null), AlertDialog.BUTTON_NEGATIVE);
                });
    }

    private void toast(int message) { Toast.makeText(host, message, Toast.LENGTH_SHORT).show(); }

    private LinearLayout column() {
        var content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPaddingRelative(dp(20), dp(10), dp(20), dp(10));
        return content;
    }

    private void showSheet(AlertDialog dialog, LinearLayout content) {
        dialog.show();
        for (int index = 0; index < content.getChildCount(); index++) {
            View option = content.getChildAt(index);
            if (option.isFocusable() && option.isEnabled() && option.isShown()) {
                CinemaUi.focusDialogControl(dialog, option);
                return;
            }
        }
        CinemaUi.focusDialogControl(dialog, dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
    }

    private androidx.appcompat.app.AlertDialog.Builder sheet(int heading, LinearLayout content) {
        var scroll = new ScrollView(host);
        scroll.setFillViewport(false);
        scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        var frame = new android.widget.FrameLayout(host);
        frame.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1,
                Math.min(dp(420), host.getResources().getDisplayMetrics().heightPixels * 53 / 100)));
        return CinemaUi.dialog(host).setTitle(heading).setView(frame);
    }

    private View card(int icon, int title, int description, Runnable action) {
        var view = CinemaUi.text(host, 17, true);
        view.setMaxLines(3);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setText(description == 0 ? host.getString(title) : host.getString(title) + "\n" + host.getString(description));
        var drawable = androidx.core.content.ContextCompat.getDrawable(host, icon).mutate();
        drawable.setTint(0xffe5a93b);
        drawable.setBounds(0, 0, dp(24), dp(24));
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(12));
        decorate(view);
        view.setOnClickListener(v -> action.run());
        view.setLayoutParams(cardSize());
        return view;
    }

    private android.widget.CheckBox choice(LinearLayout parent, String name, String description, boolean selected) {
        var check = new android.widget.CheckBox(host);
        check.setText(description.isBlank() ? name : name + "\n" + description);
        check.setMaxLines(3);
        check.setTextColor(0xfff5efdf);
        check.setButtonDrawable(CinemaUi.createCinemaCheckDrawable(host));
        check.setButtonTintList(null);
        check.setChecked(selected);
        decorate(check);
        parent.addView(check, cardSize());
        return check;
    }

    private void decorate(TextView view) {
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(54));
        view.setPaddingRelative(dp(14), dp(7), dp(14), dp(7));
        view.setFocusable(true);
        var background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, CinemaUi.rounded(0xff392b18, dp(11), 0xffe5a93b));
        background.addState(new int[]{}, CinemaUi.rounded(0xff24211d, dp(11), 0xff54432c));
        view.setBackground(background);
    }

    private LinearLayout.LayoutParams cardSize() {
        var size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(7);
        return size;
    }

    private int dp(int n) { return CinemaUi.dp(host, n); }
}
