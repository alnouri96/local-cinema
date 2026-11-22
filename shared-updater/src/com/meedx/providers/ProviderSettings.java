package com.meedx.providers;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

public final class ProviderSettings {
    public interface FieldLayout {
        View wrap(EditText input);
    }

    private static final String[] FIELDS = {
            ProviderKeys.TMDB, ProviderKeys.TRAKT_ID, ProviderKeys.TRAKT_SECRET, ProviderKeys.OPEN_SUBTITLES
    };
    private static final String[] LABELS = {
            "TMDb API Key", "Trakt Client ID", "Trakt Client Secret", "OpenSubtitles API Key"
    };

    private ProviderSettings() {}

    private static String text(Activity activity, String arabic, String english) {
        return "ar".equals(activity.getResources().getConfiguration().locale.getLanguage()) ? arabic : english;
    }

    public static String title(Activity activity) {
        return text(activity, "مفاتيح الخدمات والترجمة", "API Keys");
    }

    public static void show(Activity activity, FieldLayout layout, java.util.function.Consumer<String> changed, boolean tv) {
        String[] options = new String[FIELDS.length + 1];
        for (int i = 0; i < FIELDS.length; i++) {
            boolean saved = !ProviderKeys.get(activity, FIELDS[i]).isEmpty();
            options[i] = LABELS[i] + (saved ? text(activity, " — محفوظ", " — saved") : "");
        }
        options[FIELDS.length] = text(activity, "كيفية الحصول على المفاتيح", "How to get API keys");
        new AlertDialog.Builder(activity).setTitle(title(activity))
                .setItems(options, (dialog, index) -> {
                    if (index == FIELDS.length) {
                        help(activity, tv);
                    } else {
                        edit(activity, layout, changed, index);
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    public static void edit(Activity activity, FieldLayout layout, String key) {
        edit(activity, layout, key, null);
    }

    public static void edit(Activity activity, FieldLayout layout, String key, java.util.function.Consumer<String> changed) {
        for (int i = 0; i < FIELDS.length; i++) {
            if (FIELDS[i].equals(key)) {
                edit(activity, layout, changed, i);
                return;
            }
        }
    }

    private static void edit(Activity activity, FieldLayout layout, java.util.function.Consumer<String> changed, int index) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2048)});
        input.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        input.setHint(LABELS[index]);
        View content;
        if (layout != null) {
            content = layout.wrap(input);
        } else {
            LinearLayout container = new LinearLayout(activity);
            int padding = (int)(20 * activity.getResources().getDisplayMetrics().density);
            container.setPadding(padding, padding / 2, padding, 0);
            container.addView(input, new LinearLayout.LayoutParams(-1, -2));
            content = container;
        }
        String name = FIELDS[index];
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(LABELS[index])
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(text(activity, "إزالة", "Remove"), null)
                .setPositiveButton(text(activity, "حفظ", "Save"), null).create();
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty() || value.matches("(?s).*\\s.*")) {
                    input.setError(text(activity, "ألصق القيمة دون مسافات", "Paste the value without spaces"));
                    return;
                }
                save(activity, dialog, changed, name, value);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(button -> save(activity, dialog, changed, name, ""));
        });
        dialog.show();
    }

    private static void save(Activity activity, AlertDialog dialog, java.util.function.Consumer<String> changed, String name, String value) {
        if (!ProviderKeys.save(activity, name, value)) {
            Toast.makeText(activity, text(activity, "تعذر حفظ المفتاح", "Could not save API key"), Toast.LENGTH_LONG).show();
            return;
        }
        if (changed != null) changed.accept(name);
        dialog.dismiss();
    }

    private static void help(Activity activity, boolean tv) {
        String guide = text(activity,
                "TMDb: افتح themoviedb.org ثم Settings > API، اطلب مفتاح المطوّر وانسخ API Key (v3).\n\n"
                        + "OpenSubtitles: افتح opensubtitles.com ثم API Consumers في ملفك، وأنشئ Consumer وانسخ مفتاحه. أدخل بيانات حسابك في شاشة الترجمة عند طلبها.\n\n"
                        + "Trakt: افتح trakt.tv/oauth/applications وأنشئ تطبيقاً. فعّل Device Flow عند توفر الخيار. أدخل Client ID وClient Secret إذا صدر، ثم اربط حسابك من إعدادات Trakt على التلفاز.",
                "TMDb: At themoviedb.org, open Settings > API, request a developer key and copy the API Key (v3).\n\n"
                        + "OpenSubtitles: At opensubtitles.com, open API Consumers in your profile, create a consumer and copy its API key. Enter your OpenSubtitles account credentials when prompted.\n\n"
                        + "Trakt: At trakt.tv/oauth/applications, create an app and enable Device Flow if offered. Paste the Client ID and Client Secret when issued, then link your account in the TV Trakt settings.");
        guide += tv ? text(activity,
                "\n\nبعد اقتران الهاتف، افتح حقل المفتاح هنا ثم استخدم خيار اللصق في كتابة الريموت على شبكة موثوقة.",
                "\n\nOn a trusted network, pair the phone, open a key field here, then use Paste in the remote keyboard.")
                : text(activity,
                "\n\nالمفاتيح هنا محلية للهاتف. لتفعيل خدمات التلفاز افتح الحقل المقابل على التلفاز ثم الصق القيمة عبر ريموت الهاتف.",
                "\n\nKeys entered here are local to the phone. To enable TV services, open the matching TV field and paste via the phone remote.");
        new AlertDialog.Builder(activity).setTitle(text(activity, "كيفية الحصول على المفاتيح", "How to get API keys"))
                .setMessage(guide).setPositiveButton(android.R.string.ok, null).show();
    }
}
