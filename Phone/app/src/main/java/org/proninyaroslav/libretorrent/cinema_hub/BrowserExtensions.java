package org.proninyaroslav.libretorrent.cinema_hub;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.ConfigurationCompat;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

final class BrowserExtensions {
    private static final int GOLD = 0xffe5b84b;
    private static final Pattern AMO_SLUG = Pattern.compile("(?i)/addon/([^/?#]+)");

    private final Context context;
    private final WebExtensionController controller;
    private final Runnable chooseFile;
    private final String bundledBlocker;
    private final BiConsumer<WebExtension, Boolean> onOpenUi;
    private final BiConsumer<WebExtension, Boolean> onToggled;
    private final Consumer<String> onUninstalled;
    private final Consumer<WebExtension> onInstalled;
    private final Runnable onReinstallBundledUbo;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean installing;
    private boolean resolvingLink;
    private BottomSheetDialog activeDialog;
    private LinearLayout listContainer;
    private LinearLayout progressRow;
    private TextView progressLabel;
    private TextView countLabel;
    private boolean uboPresent;

    BrowserExtensions(
            Context context,
            WebExtensionController controller,
            Runnable chooseFile,
            String bundledBlocker,
            BiConsumer<WebExtension, Boolean> onOpenUi,
            BiConsumer<WebExtension, Boolean> onToggled,
            Consumer<String> onUninstalled,
            Consumer<WebExtension> onInstalled,
            Runnable onReinstallBundledUbo) {
        this.context = context;
        this.controller = controller;
        this.chooseFile = chooseFile;
        this.bundledBlocker = bundledBlocker;
        this.onOpenUi = onOpenUi;
        this.onToggled = onToggled;
        this.onUninstalled = onUninstalled;
        this.onInstalled = onInstalled;
        this.onReinstallBundledUbo = onReinstallBundledUbo;
    }

    void show() {
        if (activeDialog != null && activeDialog.isShowing()) {
            renderList();
            return;
        }
        var dialog = new BottomSheetDialog(context);
        activeDialog = dialog;

        var panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(isArabic() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setPadding(dp(16), dp(10), dp(16), dp(16));
        var bg = new GradientDrawable();
        bg.setColor(0xff141517);
        float r = dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);

        var grip = new View(context);
        grip.setBackground(surface(0xff48484e, 3));
        var gripLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gripLp.gravity = Gravity.CENTER_HORIZONTAL;
        gripLp.bottomMargin = dp(10);
        panel.addView(grip, gripLp);

        var header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        var titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        var titleTv = text(l("إضافات المتصفح", "Extensions"), 19, GOLD, true);
        countLabel = text(l("جاري التحميل…", "Loading…"), 12, 0xffa1a1aa, false);
        titleCol.addView(titleTv);
        titleCol.addView(countLabel);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        var addBtn = actionButton("+ " + l("إضافة", "Add Extension"), true, this::showAddMenu);
        var addLp = new LinearLayout.LayoutParams(-2, dp(38));
        addLp.setMarginEnd(dp(6));
        header.addView(addBtn, addLp);

        var closeBtn = actionButton("✕", false, dialog::dismiss);
        closeBtn.setMinimumWidth(dp(38));
        closeBtn.setMinWidth(dp(38));
        closeBtn.setPadding(0, 0, 0, 0);
        header.addView(closeBtn, new LinearLayout.LayoutParams(dp(38), dp(38)));
        panel.addView(header);

        progressRow = new LinearLayout(context);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setBackground(surface(0xff1e2024, 12));
        progressRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        progressRow.setVisibility(View.GONE);
        var spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(GOLD));
        progressRow.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        progressLabel = text("", 12, 0xfffafafa, false);
        progressLabel.setPadding(dp(10), 0, 0, 0);
        progressRow.addView(progressLabel, new LinearLayout.LayoutParams(0, -2, 1));
        var progLp = new LinearLayout.LayoutParams(-1, -2);
        progLp.topMargin = dp(10);
        panel.addView(progressRow, progLp);

        var scroll = new ScrollView(context);
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(10), 0, dp(6));
        scroll.addView(listContainer);
        int maxH = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.62f);
        var scrollLp = new LinearLayout.LayoutParams(-1, maxH);
        scrollLp.topMargin = dp(4);
        panel.addView(scroll, scrollLp);

        dialog.setOnDismissListener(d -> {
            if (activeDialog == dialog) {
                activeDialog = null;
                listContainer = null;
                progressRow = null;
                progressLabel = null;
                countLabel = null;
            }
        });
        dialog.setContentView(panel);
        dialog.show();
        if (panel.getParent() instanceof View parent) parent.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        renderList();
    }

    boolean isShowing() {
        return activeDialog != null && activeDialog.isShowing();
    }

    void refreshIfShowing() {
        if (activeDialog != null && activeDialog.isShowing()) {
            renderList();
        }
    }

    private void dismissSheet() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
    }

    private void setBusy(String msg) {
        if (progressRow == null || progressLabel == null) return;
        if (msg == null || msg.isBlank()) {
            progressRow.setVisibility(View.GONE);
        } else {
            progressLabel.setText(msg);
            progressRow.setVisibility(View.VISIBLE);
        }
    }

    private void renderList() {
        if (listContainer == null) return;
        controller.list().accept(installed -> main.post(() -> {
            if (!installing && !resolvingLink) setBusy(null);
            if (listContainer == null) return;
            listContainer.removeAllViews();
            List<WebExtension> addons = new ArrayList<>();
            boolean foundUbo = false;
            int enabledCount = 0;
            if (installed != null) {
                for (var addon : installed) {
                    if (addon == null || addon.isBuiltIn) continue;
                    addons.add(addon);
                    if (bundledBlocker != null && bundledBlocker.equals(addon.id)) foundUbo = true;
                    if (addon.metaData != null && addon.metaData.enabled) enabledCount++;
                }
            }
            uboPresent = foundUbo;
            addons.sort(Comparator
                    .comparing((WebExtension a) -> !(bundledBlocker != null && bundledBlocker.equals(a.id)))
                    .thenComparing(a -> name(a).toLowerCase(Locale.ROOT)));

            if (countLabel != null) {
                countLabel.setText(l("مفعّلة: ", "Enabled: ") + enabledCount + " · "
                        + l("الإجمالي: ", "Installed: ") + addons.size());
            }

            if (addons.isEmpty()) {
                var empty = new LinearLayout(context);
                empty.setOrientation(LinearLayout.VERTICAL);
                empty.setBackground(cardBg(false));
                empty.setPadding(dp(14), dp(16), dp(14), dp(16));
                empty.addView(text(l("لا توجد إضافات مثبّتة حالياً.", "No extensions installed right now."),
                        14, 0xfffafafa, true));
                var hint = text(l("اضغط + إضافة لتثبيت ملف .xpi أو إضافة من Mozilla Add-ons.",
                        "Tap + Add Extension to install a local .xpi file or an HTTPS / Mozilla Add-ons link."),
                        12, 0xffa1a1aa, false);
                hint.setPadding(0, dp(6), 0, 0);
                empty.addView(hint);
                if (onReinstallBundledUbo != null) {
                    var uboBtn = actionButton(l("إعادة تثبيت uBlock Origin", "Install Bundled uBlock Origin"),
                            true, () -> {
                                setBusy(l("جاري تثبيت uBlock Origin…", "Installing uBlock Origin…"));
                                onReinstallBundledUbo.run();
                            });
                    var uLp = new LinearLayout.LayoutParams(-1, dp(42));
                    uLp.topMargin = dp(12);
                    empty.addView(uboBtn, uLp);
                }
                listContainer.addView(empty);
                return;
            }

            for (var addon : addons) {
                listContainer.addView(buildExtensionCard(addon));
            }
        }), error -> main.post(() -> {
            setBusy(null);
            message(l("تعذّر تحميل الإضافات", "Could not load extensions"));
        }));
    }

    private LinearLayout buildExtensionCard(WebExtension addon) {
        boolean enabled = addon.metaData != null && addon.metaData.enabled;
        boolean isUbo = bundledBlocker != null && bundledBlocker.equals(addon.id);
        var card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(enabled));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        var cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        var topRow = new LinearLayout(context);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        var infoCol = new LinearLayout(context);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        String titleText = name(addon);
        if (isUbo) titleText += " · " + l("حاجب الإعلانات", "Ad Blocker");
        var nameTv = text(titleText, 15, 0xfffafafa, true);
        infoCol.addView(nameTv);

        String version = (addon.metaData != null && addon.metaData.version != null && !addon.metaData.version.isBlank())
                ? "v" + addon.metaData.version + " · " : "";
        String statusText = version + (enabled ? l("مفعّلة", "Enabled") : l("معطّلة", "Disabled"));
        var statusTv = text(statusText, 12, enabled ? 0xff4ade80 : 0xffa1a1aa, false);
        statusTv.setPadding(0, dp(2), 0, 0);
        infoCol.addView(statusTv);
        topRow.addView(infoCol, new LinearLayout.LayoutParams(0, -2, 1));

        var toggle = new MaterialSwitch(context);
        toggle.setChecked(enabled);
        toggle.setThumbTintList(ColorStateList.valueOf(enabled ? 0xff141517 : 0xffd4d4d8));
        toggle.setTrackTintList(ColorStateList.valueOf(enabled ? GOLD : 0xff3f3f46));
        boolean[] reverting = {false};
        toggle.setOnCheckedChangeListener((buttonView, wantEnable) -> {
            if (reverting[0]) return;
            toggle.setEnabled(false);
            var call = wantEnable
                    ? controller.enable(addon, WebExtensionController.EnableSource.USER)
                    : controller.disable(addon, WebExtensionController.EnableSource.USER);
            call.accept(updated -> main.post(() -> {
                if (onToggled != null) onToggled.accept(updated != null ? updated : addon, wantEnable);
                renderList();
            }), error -> main.post(() -> {
                reverting[0] = true;
                toggle.setChecked(!wantEnable);
                reverting[0] = false;
                toggle.setEnabled(true);
                message(l("تعذّر تغيير حالة الإضافة", "Could not change extension status"));
            }));
        });
        topRow.addView(toggle);
        card.addView(topRow);

        String desc = addon.metaData != null && addon.metaData.description != null
                ? addon.metaData.description.trim() : "";
        if (!desc.isEmpty()) {
            var descTv = text(desc, 12, 0xffb4b4bc, false);
            descTv.setMaxLines(2);
            descTv.setEllipsize(TextUtils.TruncateAt.END);
            descTv.setPadding(0, dp(6), 0, 0);
            card.addView(descTv);
        }

        var actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        var actLp = new LinearLayout.LayoutParams(-1, -2);
        actLp.topMargin = dp(10);

        boolean hasOptions = isUbo || (addon.metaData != null
                && addon.metaData.optionsPageUrl != null && !addon.metaData.optionsPageUrl.isBlank());

        var popupBtn = actionButton(l("الواجهة", "Open Popup"), enabled, () -> {
            dismissSheet();
            if (onOpenUi != null) onOpenUi.accept(addon, false);
        });
        popupBtn.setEnabled(enabled);
        popupBtn.setAlpha(enabled ? 1.0f : 0.45f);
        var pLp = new LinearLayout.LayoutParams(0, dp(38), 1);
        pLp.setMarginEnd(dp(6));
        actionsRow.addView(popupBtn, pLp);

        if (hasOptions) {
            var settingsBtn = actionButton(l("الإعدادات", "Settings"), false, () -> {
                dismissSheet();
                if (onOpenUi != null) onOpenUi.accept(addon, true);
            });
            settingsBtn.setEnabled(enabled);
            settingsBtn.setAlpha(enabled ? 1.0f : 0.45f);
            var sLp = new LinearLayout.LayoutParams(0, dp(38), 1);
            sLp.setMarginEnd(dp(6));
            actionsRow.addView(settingsBtn, sLp);
        }

        var removeBtn = removeButton(l("إزالة", "Remove"), () -> confirmRemove(addon));
        actionsRow.addView(removeBtn, new LinearLayout.LayoutParams(0, dp(38), 1));
        card.addView(actionsRow, actLp);
        return card;
    }

    private void confirmRemove(WebExtension addon) {
        CinemaBrowser.styleDarkDialog(new AlertDialog.Builder(context)
                .setTitle(l("إزالة ", "Remove ") + name(addon) + "?")
                .setMessage(bundledBlocker != null && bundledBlocker.equals(addon.id)
                        ? l("يمكنك إعادة تثبيت uBlock Origin لاحقاً من قائمة + إضافة.",
                            "You can reinstall bundled uBlock Origin anytime from + Add Extension.")
                        : l("سيتم حذف الإضافة وبياناتها من المتصفح.",
                            "This will uninstall the extension from the browser."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(l("إزالة", "Remove"), (confirm, which) -> {
                    setBusy(l("جاري الإزالة…", "Removing extension…"));
                    controller.uninstall(addon).accept(ignored -> main.post(() -> {
                        setBusy(null);
                        if (onUninstalled != null) onUninstalled.accept(addon.id);
                        message(l("تمت إزالة ", "Removed ") + name(addon));
                        renderList();
                    }), error -> main.post(() -> {
                        setBusy(null);
                        message(l("تعذّر إزالة الإضافة", "Could not remove extension"));
                    }));
                }).show());
    }

    private void showAddMenu() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(l("تثبيت من ملف .xpi على الجهاز", "Install from local .xpi file"));
        actions.add(chooseFile);
        labels.add(l("تثبيت من رابط (.xpi أو Mozilla Add-ons)", "Install from link (.xpi or Mozilla Add-ons URL)"));
        actions.add(this::enterLink);
        if (!uboPresent && onReinstallBundledUbo != null) {
            labels.add(l("إعادة تثبيت uBlock Origin المدمج", "Reinstall bundled uBlock Origin"));
            actions.add(() -> {
                setBusy(l("جاري تثبيت uBlock Origin…", "Installing uBlock Origin…"));
                onReinstallBundledUbo.run();
            });
        }
        CinemaBrowser.styleDarkDialog(new AlertDialog.Builder(context)
                .setTitle("+ " + l("إضافة للمتصفح", "Add Extension"))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void enterLink() {
        var input = new EditText(context);
        input.setSingleLine(true);
        input.setTextColor(0xfff4f4f6);
        input.setHintTextColor(0xff8e8e98);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(org.proninyaroslav.libretorrent.R.string.cinema_extension_url_hint);
        var pad = new LinearLayout(context);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setPadding(dp(20), dp(8), dp(20), dp(4));
        pad.addView(input);

        var dialog = CinemaBrowser.styleDarkDialog(new AlertDialog.Builder(context)
                .setTitle(l("تثبيت إضافة من رابط", "Install Extension from Link"))
                .setView(pad)
                .setPositiveButton(l("تثبيت", "Install"), null)
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String raw = input.getText().toString().trim();
            Uri url = Uri.parse(raw);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) {
                input.setError(l("أدخل رابط HTTPS صحيح", "Enter a valid HTTPS link"));
                return;
            }
            dialog.dismiss();
            String host = url.getHost().toLowerCase(Locale.ROOT);
            String path = url.getPath() == null ? "" : url.getPath();
            if ((host.equals("addons.mozilla.org") || host.endsWith(".addons.mozilla.org"))
                    && !path.toLowerCase(Locale.ROOT).endsWith(".xpi")) {
                Matcher m = AMO_SLUG.matcher(path);
                if (m.find()) {
                    resolveAndInstallAmoSlug(m.group(1));
                    return;
                }
            }
            install(url.toString(), null);
        });
    }

    private void resolveAndInstallAmoSlug(String slug) {
        if (installing || resolvingLink) {
            message(l("عملية تثبيت أخرى قيد التنفيذ", "An extension installation is already in progress"));
            return;
        }
        resolvingLink = true;
        setBusy(l("جاري التحقق من Mozilla Add-ons…", "Resolving extension from Mozilla Add-ons…"));
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String apiUrl = "https://addons.mozilla.org/api/v5/addons/addon/" + Uri.encode(slug) + "/";
                conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setConnectTimeout(12_000);
                conn.setReadTimeout(12_000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                StringBuilder sb = new StringBuilder();
                try (var br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = br.read(buf)) != -1) {
                        if (sb.length() + n > 2_000_000) throw new IOException("Response too large");
                        sb.append(buf, 0, n);
                    }
                }
                JSONObject json = new JSONObject(sb.toString());
                JSONObject currentVersion = json.optJSONObject("current_version");
                JSONObject fileObj = currentVersion != null ? currentVersion.optJSONObject("file") : null;
                String xpiUrl = fileObj != null ? fileObj.optString("url", "") : "";
                Uri parsed = Uri.parse(xpiUrl);
                if (xpiUrl.isBlank() || !"https".equalsIgnoreCase(parsed.getScheme())) {
                    throw new IOException("No valid XPI download URL found");
                }
                main.post(() -> {
                    resolvingLink = false;
                    install(xpiUrl, null);
                });
            } catch (Exception e) {
                main.post(() -> {
                    resolvingLink = false;
                    setBusy(null);
                    message(l("تعذّر العثور على ملف .xpi من رابط Mozilla Add-ons",
                            "Could not resolve .xpi from Mozilla Add-ons URL"));
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "cinema-amo-resolve").start();
    }

    void installFile(Uri uri) {
        if (uri == null) return;
        main.post(() -> setBusy(l("جاري قراءة ملف الإضافة…", "Reading .xpi file…")));
        new Thread(() -> {
            File file = null;
            try {
                file = File.createTempFile("browser-extension-", ".xpi", context.getCacheDir());
                byte[] header = new byte[4];
                int headerRead = 0;
                try (var input = context.getContentResolver().openInputStream(uri);
                        var output = new FileOutputStream(file)) {
                    if (input == null) throw new IOException("Could not open file");
                    byte[] buffer = new byte[8192];
                    long size = 0;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        for (int i = 0; i < count && headerRead < 4; i++) {
                            header[headerRead++] = buffer[i];
                        }
                        size += count;
                        if (size > 64L * 1024 * 1024) throw new IOException("Extension file too large");
                        output.write(buffer, 0, count);
                    }
                    if (size < 22 || header[0] != 0x50 || header[1] != 0x4B) {
                        throw new IOException("Invalid XPI/ZIP signature");
                    }
                }
                File packageFile = file;
                main.post(() -> install(Uri.fromFile(packageFile).toString(), packageFile));
            } catch (IOException | SecurityException error) {
                if (file != null) file.delete();
                main.post(() -> {
                    setBusy(null);
                    String msg = "Invalid XPI/ZIP signature".equals(error.getMessage())
                            ? l("اختر ملف إضافة .xpi صالح", "Please select a valid .xpi extension file")
                            : l("تعذّر قراءة ملف الإضافة", "Could not read extension file");
                    message(msg);
                });
            }
        }, "cinema-extension-file").start();
    }

    private void install(String uri, File file) {
        if (installing) {
            if (file != null) file.delete();
            message(l("عملية تثبيت أخرى قيد التنفيذ", "An extension installation is already in progress"));
            return;
        }
        installing = true;
        setBusy(l("جاري تثبيت الإضافة…", "Installing extension…"));
        controller.install(uri, file == null ? WebExtensionController.INSTALLATION_METHOD_MANAGER
                : WebExtensionController.INSTALLATION_METHOD_FROM_FILE).accept(result -> main.post(() -> {
                    finishInstall(file);
                    setBusy(null);
                    if (onInstalled != null && result != null) onInstalled.accept(result);
                    message(l("تم تثبيت الإضافة بنجاح ✓", "Extension installed ✓"));
                    if (activeDialog != null && activeDialog.isShowing()) renderList();
                    else show();
                }), error -> main.post(() -> {
                    finishInstall(file);
                    setBusy(null);
                    message(l("فشل تثبيت الإضافة — تأكد أنها إضافة Firefox متوافقة",
                            "Extension install failed — verify it is a signed Firefox .xpi"));
                }));
    }

    private void finishInstall(File file) {
        installing = false;
        if (file != null) file.delete();
    }

    GeckoResult<WebExtension.PermissionPromptResponse> confirmInstall(WebExtension addon,
            String[] permissions, String[] origins, String[] dataPermissions) {
        var denied = new WebExtension.PermissionPromptResponse(false, false, false);
        if (!installing) return GeckoResult.fromValue(denied);
        var result = new GeckoResult<WebExtension.PermissionPromptResponse>();
        var requested = new ArrayList<String>();
        for (String[] group : new String[][]{permissions, origins, dataPermissions}) {
            if (group != null) requested.addAll(Arrays.asList(group));
        }
        var dialog = new AlertDialog.Builder(context)
                .setTitle(l("تثبيت ", "Install ") + name(addon) + "?")
                .setMessage(requested.isEmpty()
                        ? l("لا تطلب هذه الإضافة أذونات إضافية.", "This extension requests no additional permissions.")
                        : l("الأذونات المطلوبة:\n", "Requested access:\n") + String.join("\n", requested))
                .setNegativeButton(android.R.string.cancel, (window, which) -> result.complete(denied))
                .setPositiveButton(l("تثبيت", "Install"), (window, which) ->
                        result.complete(new WebExtension.PermissionPromptResponse(true, false, false)))
                .create();
        dialog.setOnCancelListener(window -> result.complete(denied));
        dialog.show();
        CinemaBrowser.styleDarkDialog(dialog);
        return result;
    }

    private String name(WebExtension addon) {
        if (addon == null) return "";
        return addon.metaData == null || addon.metaData.name == null || addon.metaData.name.isBlank()
                ? addon.id : addon.metaData.name;
    }

    private boolean isArabic() {
        var locales = ConfigurationCompat.getLocales(context.getResources().getConfiguration());
        var loc = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        return loc != null && "ar".equalsIgnoreCase(loc.getLanguage());
    }

    private String l(String ar, String en) {
        return isArabic() ? ar : en;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable surface(int color, int radiusDp) {
        var d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable cardBg(boolean active) {
        var d = new GradientDrawable();
        d.setColor(0xff1e2024);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), active ? 0x55e5b84b : 0xff2d2e35);
        return d;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        var tv = new TextView(context);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private MaterialButton actionButton(String label, boolean gold, Runnable onClick) {
        var b = new MaterialButton(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextSize(12);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setCornerRadius(dp(12));
        b.setStrokeWidth(dp(1));
        b.setStrokeColor(ColorStateList.valueOf(gold ? GOLD : 0x33ffffff));
        b.setBackgroundTintList(ColorStateList.valueOf(gold ? GOLD : 0xff25262c));
        b.setTextColor(gold ? 0xff141517 : 0xfffafafa);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private MaterialButton removeButton(String label, Runnable onClick) {
        var b = actionButton(label, false, onClick);
        b.setStrokeColor(ColorStateList.valueOf(0x66f87171));
        b.setBackgroundTintList(ColorStateList.valueOf(0xff2b181b));
        b.setTextColor(0xfffca5a5);
        return b;
    }

    private void message(String text) { CinemaSnackBanner.show(context, text); }
}
