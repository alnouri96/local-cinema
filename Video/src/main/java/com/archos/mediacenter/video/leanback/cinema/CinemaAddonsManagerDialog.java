package com.archos.mediacenter.video.leanback.cinema;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.archos.mediacenter.video.R;
import com.meedx.providers.StremioAddons;

import java.util.List;
import java.util.concurrent.ExecutorService;

final class CinemaAddonsManagerDialog {
    private final Activity host;
    private final ExecutorService io;
    private final Handler main;
    private final Runnable onClosed;
    private LinearLayout rows;
    private TextView count;
    private TextView installedButton;

    CinemaAddonsManagerDialog(Activity host, ExecutorService io, Handler main, Runnable onClosed) {
        this.host = host;
        this.io = io;
        this.main = main;
        this.onClosed = onClosed;
    }

    /** Opens the Global Add-ons Settings dialog (intended for Add-ons Hub & System Settings only). */
    void show() {
        rows = column();
        refreshMain();
        var dialog = sheet(R.string.addon_manage, rows).setNegativeButton(R.string.addon_back, null).create();
        showSheet(dialog, rows);
    }

    /** Opens a dedicated settings dialog for ONE specific add-on (used inside individual add-on pages & Hub context menu). */
    void showAddon(StremioAddons.Addon addon) {
        showAddonOnly(addon, null);
    }

    void showAddonOnly(StremioAddons.Addon addon, Runnable onReloadCatalog) {
        if (addon == null) return;
        manageAddon(addon, () -> {
            if (onReloadCatalog != null) onReloadCatalog.run();
        }, onReloadCatalog);
    }

    private void refreshMain() {
        rows.removeAllViews();
        count = CinemaUi.text(host, 15, true);
        count.setTextColor(CinemaTokens.GOLD);
        count.setPaddingRelative(dp(4), 0, dp(4), dp(4));
        rows.addView(count);

        var historyRow = toggleRow(rows,
                host.getString(R.string.addon_continue_watching),
                host.getString(CinemaWatchStore.includeAddonContinue(host)
                        ? R.string.addon_enabled_label : R.string.addon_disabled_label),
                CinemaWatchStore.includeAddonContinue(host),
                (row, enabled) -> {
                    CinemaWatchStore.setIncludeAddonContinue(host, enabled);
                    row.setSubtitle(host.getString(enabled
                            ? R.string.addon_enabled_label : R.string.addon_disabled_label));
                    notifyChange();
                });
        historyRow.view.setMinimumHeight(dp(64));

        rows.addView(sectionLabel(CinemaUi.label(host, "الإضافات والمصادر", "Add-ons & Sources")));
        installedButton = card(R.drawable.cinema_ic_addons, installedSummaryTitle(),
                host.getString(R.string.addon_manage_installed_hint), this::openInstalledManager);
        rows.addView(installedButton);
        rows.addView(card(R.drawable.cinema_ic_addons, host.getString(R.string.addon_choose_enabled),
                host.getString(R.string.addon_choose_hint), this::chooseInstalled));
        rows.addView(card(R.drawable.cinema_ic_addons, host.getString(R.string.addon_add_manifest), "", this::paste));
        rows.addView(card(R.drawable.cinema_ic_posters, host.getString(R.string.addon_recommended),
                host.getString(R.string.addon_recommended_hint), this::chooseRecommended));

        rows.addView(sectionLabel(CinemaUi.label(host, "المزامنة والفحص", "Sync & Diagnostics")));
        rows.addView(card(R.drawable.cinema_ic_system, host.getString(R.string.addon_sync_now), "", () -> Toast.makeText(host,
                CinemaLinkServer.requestAddonSync() ? R.string.addon_sync_requested : R.string.addon_pair_phone,
                Toast.LENGTH_SHORT).show()));
        rows.addView(card(R.drawable.cinema_ic_timer, host.getString(R.string.addon_connection_test), "", this::diagnose));
        updateCount();
    }

    private String installedSummaryTitle() {
        var list = installed();
        return host.getString(R.string.addon_manage_installed_count, list.size());
    }

    private List<StremioAddons.Addon> installed() {
        return StremioAddons.list(host);
    }

    private void updateCount() {
        var list = installed();
        long active = list.stream().filter(addon -> addon.enabled).count();
        if (count != null) {
            count.setText(host.getString(R.string.addon_active_count, active) + " / " + list.size());
        }
        if (installedButton != null) {
            installedButton.setText(installedSummaryTitle() + "\n" + host.getString(R.string.addon_manage_installed_hint));
        }
    }

    private void openInstalledManager() {
        var list = installed();
        if (list.isEmpty()) {
            toast(R.string.addon_empty);
            return;
        }
        var content = column();
        var dialogRef = new AlertDialog[1];
        Runnable[] populate = new Runnable[1];
        populate[0] = () -> {
            content.removeAllViews();
            var current = installed();
            for (var addon : current) {
                String state = host.getString(addon.enabled ? R.string.addon_enabled_label : R.string.addon_disabled_label);
                String typeLabel = addonCapabilityLabel(addon);
                String subtitle = state + " · " + typeLabel;
                if (StremioAddons.connectionIssue(addon)) {
                    subtitle = host.getString(R.string.addon_unavailable) + " · " + subtitle;
                }
                var itemCard = card(R.drawable.cinema_ic_addons, addon.name, subtitle,
                        () -> manageAddon(addon, () -> {
                            populate[0].run();
                            updateCount();
                        }, null));
                content.addView(itemCard);
            }
        };
        populate[0].run();
        var dialog = sheet(R.string.addon_installed_settings, content)
                .setNegativeButton(R.string.addon_back, null)
                .create();
        dialogRef[0] = dialog;
        showSheet(dialog, content);
    }

    private void notifyChange() {
        if (!host.isDestroyed() && !host.isFinishing()) {
            updateCount();
            broadcastAddonsChanged();
            if (onClosed != null) onClosed.run();
        }
    }

    private void broadcastAddonsChanged() {
        try {
            host.sendBroadcast(new Intent(CinemaLinkServer.ACTION_ADDONS_CHANGED)
                    .setPackage(host.getPackageName()));
        } catch (Exception ignored) {}
    }

    private String addonCapabilityLabel(StremioAddons.Addon addon) {
        if (StremioAddons.isLiveChannelAddon(addon)) {
            return CinemaUi.label(host, "قنوات وبث مباشر", "Live TV & Channels");
        }
        if (StremioAddons.subtitleOnly(addon)) {
            return CinemaUi.label(host, "ترجمات", "Subtitles");
        }
        boolean cat = StremioAddons.mayCatalog(addon);
        boolean str = StremioAddons.mayStream(addon);
        if (cat && str) return CinemaUi.label(host, "كتالوج ومصادر بث", "Catalog & Streams");
        if (cat) return CinemaUi.label(host, "كتالوج محتوى", "Content Catalog");
        if (str) return CinemaUi.label(host, "مصادر بث وتورنت", "Streams & Torrents");
        return CinemaUi.label(host, "إضافة خارجية", "External Add-on");
    }

    private void manageAddon(StremioAddons.Addon addon, Runnable refresh, Runnable onReloadCatalog) {
        var content = column();
        var info = CinemaUi.text(host, 13, false);
        info.setTextColor(0xffc9beaa);
        info.setPaddingRelative(dp(6), 0, dp(6), dp(8));
        info.setMaxLines(3);
        info.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        info.setText(addonCapabilityLabel(addon) + "\n" + addon.manifestUrl);
        content.addView(info);

        var dialog = sheet(R.string.addon_single_settings_title, content).setTitle(addon.name)
                .setNegativeButton(R.string.addon_back, null).create();
        var needsRefresh = new boolean[1];
        dialog.setOnDismissListener(d -> {
            if (needsRefresh[0] && refresh != null) refresh.run();
        });

        toggleRow(content,
                host.getString(R.string.addon_enable_toggle_title),
                host.getString(addon.enabled ? R.string.addon_enabled_label : R.string.addon_disabled_label),
                addon.enabled,
                (row, value) -> {
                    StremioAddons.setEnabled(host, addon.manifestUrl, value);
                    CinemaLinkServer.sendAddonEnabled(addon.manifestUrl, value);
                    row.setSubtitle(host.getString(value ? R.string.addon_enabled_label : R.string.addon_disabled_label));
                    needsRefresh[0] = true;
                    notifyChange();
                });

        if (onReloadCatalog != null) {
            content.addView(card(R.drawable.cinema_ic_timer,
                    host.getString(R.string.addon_reload_this),
                    host.getString(R.string.addon_reload_this_hint),
                    () -> {
                        dialog.dismiss();
                        onReloadCatalog.run();
                    }));
        }

        content.addView(card(R.drawable.cinema_ic_addons, host.getString(R.string.addon_edit_manifest),
                host.getString(R.string.addon_edit_manifest_hint),
                () -> editManifest(addon, () -> {
                    needsRefresh[0] = true;
                    dialog.dismiss();
                })));

        content.addView(card(R.drawable.cinema_ic_timer, host.getString(R.string.addon_test_this_connection),
                host.getString(R.string.addon_test_this_connection_hint),
                () -> CinemaAddonPicker.load(host, io, main, R.string.addon_connection_testing,
                        () -> StremioAddons.probeAddons(List.of(addon)), timings -> {
                            if (timings == null || timings.isEmpty()) return;
                            var item = timings.get(0);
                            String msg = host.getString(
                                    item.ok() ? R.string.addon_connection_ok : R.string.addon_connection_failed,
                                    item.name(), item.millis());
                            CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(host)
                                    .setTitle(R.string.addon_test_this_connection)
                                    .setMessage(msg)
                                    .setNegativeButton(R.string.addon_back, null), AlertDialog.BUTTON_NEGATIVE);
                        })));

        if (StremioAddons.mayStream(addon) && !StremioAddons.isLiveChannelAddon(addon)) {
            content.addView(card(R.drawable.cinema_ic_posters, host.getString(R.string.addon_test_movie),
                    host.getString(R.string.addon_test_movie_hint), () -> {
                        CinemaAddonPicker.load(host, io, main, R.string.addon_connection_testing,
                                () -> StremioAddons.testMovie(addon), health -> {
                                    if (health == null) return;
                                    int message = health.unavailable()
                                            ? R.string.addon_connection_failed : R.string.addon_health_sources;
                                    String result = health.unavailable()
                                            ? host.getString(message, addon.name, health.millis())
                                            : host.getString(message, addon.name, health.results(), health.millis());
                                    CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(host).setTitle(R.string.addon_test_movie)
                                            .setMessage(result).setNegativeButton(R.string.addon_back, null),
                                            AlertDialog.BUTTON_NEGATIVE);
                                });
                    }));
        }

        if (StremioAddons.builtIn(addon) == null) {
            content.addView(card(R.drawable.cinema_ic_system, host.getString(R.string.addon_remove), "", () ->
                    CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(host).setTitle(R.string.addon_remove)
                            .setMessage(host.getString(R.string.addon_remove_confirm, addon.name))
                            .setNegativeButton(R.string.addon_back, null)
                            .setPositiveButton(R.string.addon_remove, (d, w) -> {
                                StremioAddons.remove(host, addon.manifestUrl);
                                CinemaLinkServer.sendAddonRemoved(addon.manifestUrl);
                                needsRefresh[0] = true;
                                notifyChange();
                                dialog.dismiss();
                            }), AlertDialog.BUTTON_POSITIVE)));
        }
        showSheet(dialog, content);
    }

    private void editManifest(StremioAddons.Addon addon, Runnable onSaved) {
        var field = new EditText(host);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        field.setEnabled(StremioAddons.builtIn(addon) == null);
        field.setText(addon.manifestUrl);
        field.selectAll();
        var configUrl = StremioAddons.configurationPage(addon);
        decorate(field);
        var dialog = CinemaUi.dialog(host).setTitle(R.string.addon_edit_manifest)
                .setView(CinemaPhoneLink.withPasteBadge(field))
                .setNegativeButton(R.string.addon_back, null)
                .setNeutralButton(R.string.addon_configure_on_web, (d, w) -> {
                    try {
                        host.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(configUrl)));
                    } catch (android.content.ActivityNotFoundException missing) {
                        CinemaUi.showDialogWithButtonFocus(CinemaUi.dialog(host).setTitle(R.string.addon_configure_on_web)
                                .setMessage(configUrl).setNegativeButton(R.string.addon_back, null), AlertDialog.BUTTON_NEGATIVE);
                    }
                })
                .setPositiveButton(R.string.addon_save_apply, (d, w) -> {
                    String url = field.getText().toString();
                    CinemaAddonPicker.load(host, io, main, R.string.addon_loading, () -> {
                        String normalized = StremioAddons.normalizeManifestUrl(url);
                        if (StremioAddons.builtIn(addon) != null && !normalized.equals(addon.manifestUrl)) return false;
                        StremioAddons.replaceManifest(host.getApplicationContext(), addon, normalized);
                        if (!normalized.equals(addon.manifestUrl)) CinemaLinkServer.sendAddonRemoved(addon.manifestUrl);
                        CinemaLinkServer.broadcastAddonsSync(host.getApplicationContext());
                        return true;
                    }, saved -> {
                        if (!Boolean.TRUE.equals(saved)) {
                            toast(R.string.addon_manifest_error);
                            return;
                        }
                        notifyChange();
                        onSaved.run();
                    });
                }).show();
        CinemaUi.focusDialogControl(dialog, field);
    }

    private void chooseInstalled() {
        var addons = installed();
        if (addons.isEmpty()) {
            toast(R.string.addon_empty);
            return;
        }
        var content = column();
        var checks = new java.util.ArrayList<ToggleRow>();
        for (var addon : addons) {
            String description = StremioAddons.localizedDescription(addon.description, host);
            if (StremioAddons.connectionIssue(addon)) {
                description = host.getString(R.string.addon_unavailable)
                        + (description.isBlank() ? "" : " · " + description);
            }
            checks.add(toggleRow(content, addon.name, description, addon.enabled, null));
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
                boolean enabled = checks.get(i).checked;
                if (current.stream().anyMatch(addon -> addon.manifestUrl.equals(url) && addon.enabled != enabled)) {
                    updates.put(url, enabled);
                }
            }
            StremioAddons.setEnabled(host, updates);
            updates.forEach(CinemaLinkServer::sendAddonEnabled);
            if (!updates.isEmpty()) notifyChange();
            toast(R.string.addon_preferences_saved);
            dialog.dismiss();
        });
    }

    private void chooseRecommended() {
        CinemaAddonPicker.load(host, io, main, R.string.addon_loading, () -> StremioAddons.fetchCurated(host), recommendations -> {
            if (recommendations == null || recommendations.isEmpty()) {
                toast(R.string.addon_manifest_error);
                return;
            }
            var content = column();
            var checks = new java.util.ArrayList<ToggleRow>();
            for (var addon : recommendations) {
                boolean active = installed().stream().anyMatch(item -> item.enabled && item.manifestUrl.equals(addon.manifestUrl));
                checks.add(toggleRow(content, addon.name, addon.description, active, null));
            }
            var dialog = sheet(R.string.addon_recommended, content).setNegativeButton(R.string.addon_back, null)
                    .setPositiveButton(R.string.addon_save_apply, null).create();
            showSheet(dialog, content);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                var urls = new java.util.ArrayList<String>();
                for (int i = 0; i < checks.size(); i++) {
                    var addon = recommendations.get(i);
                    if (checks.get(i).checked && installed().stream().noneMatch(item -> item.enabled
                            && item.manifestUrl.equals(addon.manifestUrl))) {
                        urls.add(addon.manifestUrl);
                    }
                }
                if (!urls.isEmpty()) {
                    dialog.dismiss();
                    install(urls);
                }
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
                        (d, w) -> {
                            if (!field.getText().toString().isBlank()) install(List.of(field.getText().toString()));
                        }).show();
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
            if (saved > 0) notifyChange();
            Toast.makeText(host, host.getString(R.string.addon_install_summary, saved, urls.size() - saved), Toast.LENGTH_LONG).show();
        });
    }

    private void diagnose() {
        CinemaAddonPicker.load(host, io, main, R.string.addon_connection_testing,
                () -> StremioAddons.probeAddons(installed()), timings -> {
                    if (timings == null || timings.isEmpty()) {
                        toast(R.string.addon_empty);
                        return;
                    }
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
        content.setLayoutDirection(CinemaUi.arabic(host) ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPaddingRelative(dp(20), dp(12), dp(20), dp(14));
        return content;
    }

    private TextView sectionLabel(String text) {
        var label = CinemaUi.text(host, 13, true);
        label.setText(text);
        label.setTextColor(0xffc9a66b);
        label.setPaddingRelative(dp(6), dp(12), dp(6), dp(2));
        return label;
    }

    private void showSheet(AlertDialog dialog, LinearLayout content) {
        dialog.show();
        if (dialog.getWindow() != null) {
            var metrics = host.getResources().getDisplayMetrics();
            dialog.getWindow().setLayout(Math.min(dp(840), (int) (metrics.widthPixels * 0.86f)),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        for (int index = 0; index < content.getChildCount(); index++) {
            View option = content.getChildAt(index);
            if (option.isFocusable() && option.isEnabled() && option.isShown()) {
                CinemaUi.focusDialogControl(dialog, option);
                return;
            }
        }
        CinemaUi.focusDialogControl(dialog, dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
    }

    private AlertDialog.Builder sheet(int heading, LinearLayout content) {
        var scroll = new ScrollView(host);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        var frame = new android.widget.FrameLayout(host);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        frame.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1,
                Math.min(dp(480), host.getResources().getDisplayMetrics().heightPixels * 64 / 100)));
        return CinemaUi.dialog(host).setTitle(heading).setView(frame);
    }

    private TextView card(int icon, String title, String description, Runnable action) {
        var view = CinemaUi.text(host, 16, true);
        view.setMaxLines(3);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setText(description == null || description.isBlank() ? title : title + "\n" + description);
        var drawable = androidx.core.content.ContextCompat.getDrawable(host, icon).mutate();
        drawable.setTint(0xffe5a93b);
        drawable.setBounds(0, 0, dp(22), dp(22));
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(14));
        decorate(view);
        view.setOnClickListener(v -> action.run());
        view.setLayoutParams(cardSize());
        return view;
    }

    private static final class ToggleRow {
        final LinearLayout view;
        final ImageView box;
        final TextView subtitleView;
        boolean checked;

        ToggleRow(LinearLayout view, ImageView box, TextView subtitleView, boolean checked) {
            this.view = view;
            this.box = box;
            this.subtitleView = subtitleView;
            this.checked = checked;
        }

        void setSubtitle(String text) {
            if (text == null || text.isBlank()) {
                subtitleView.setVisibility(View.GONE);
            } else {
                subtitleView.setText(text);
                subtitleView.setVisibility(View.VISIBLE);
            }
        }
    }

    private ToggleRow toggleRow(LinearLayout parent, String title, String subtitle, boolean initial,
                                java.util.function.BiConsumer<ToggleRow, Boolean> onChange) {
        var row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setPaddingRelative(dp(18), dp(10), dp(18), dp(10));
        row.setFocusable(true);
        row.setClickable(true);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setBackground(rowBackground());

        var checkIcon = new ImageView(host);
        checkIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        checkIcon.setImageDrawable(CinemaUi.createCinemaCheckDrawable(host, initial));
        var iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        iconLp.setMarginEnd(dp(16));
        row.addView(checkIcon, iconLp);

        var textCol = new LinearLayout(host);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        var titleView = CinemaUi.text(host, 16, true);
        titleView.setText(title);
        titleView.setTextColor(0xfff5efdf);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        var subView = CinemaUi.text(host, 13, false);
        subView.setTextColor(0xffc9beaa);
        subView.setMaxLines(2);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        if (subtitle != null && !subtitle.isBlank()) {
            subView.setText(subtitle);
            subView.setVisibility(View.VISIBLE);
        } else {
            subView.setVisibility(View.GONE);
        }
        var subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(2);
        textCol.addView(subView, subLp);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        var holder = new ToggleRow(row, checkIcon, subView, initial);
        row.setOnClickListener(v -> {
            holder.checked = !holder.checked;
            holder.box.setImageDrawable(CinemaUi.createCinemaCheckDrawable(host, holder.checked));
            if (onChange != null) onChange.accept(holder, holder.checked);
        });
        parent.addView(row, cardSize());
        return holder;
    }

    private android.graphics.drawable.StateListDrawable rowBackground() {
        var background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, CinemaUi.rounded(0xff392b18, dp(12), 0xffe5a93b));
        background.addState(new int[]{}, CinemaUi.rounded(0xff24211d, dp(12), 0xff54432c));
        return background;
    }

    private void decorate(TextView view) {
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(56));
        view.setPaddingRelative(dp(18), dp(10), dp(18), dp(10));
        view.setFocusable(true);
        view.setBackground(rowBackground());
    }

    private LinearLayout.LayoutParams cardSize() {
        var size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(8);
        return size;
    }

    private int dp(int n) { return CinemaUi.dp(host, n); }
}
