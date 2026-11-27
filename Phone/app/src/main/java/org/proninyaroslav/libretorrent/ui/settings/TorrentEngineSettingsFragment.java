package org.proninyaroslav.libretorrent.ui.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.ConfigurationCompat;
import androidx.core.os.LocaleListCompat;
import androidx.navigation.NavDirections;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.ui.settings.pages.AppearanceSettingsFragmentDirections;
import org.proninyaroslav.libretorrent.ui.settings.pages.BehaviorSettingsFragmentDirections;
import org.proninyaroslav.libretorrent.ui.settings.pages.FeedSettingsFragmentDirections;
import org.proninyaroslav.libretorrent.ui.settings.pages.LimitationsSettingsFragmentDirections;
import org.proninyaroslav.libretorrent.ui.settings.pages.NetworkSettingsFragmentDirections;
import org.proninyaroslav.libretorrent.ui.settings.pages.SchedulingSettingsFragmentDirections;
import org.proninyaroslav.libretorrent.ui.settings.pages.StorageSettingsFragmentDirections;
import org.proninyaroslav.libretorrent.ui.settings.pages.StreamingSettingsFragmentDirections;

import java.util.Locale;

/**
 * Isolated sub-screen hosting all 9 original LibreTorrent download-engine preference sections:
 * Storage, Appearance, App Language, Behavior, Streaming, Network, Limitations, Scheduling, and RSS.
 */
public final class TorrentEngineSettingsFragment extends CustomPreferenceFragment {
    public static final String KEY_ROOT = "pref_key_torrent_engine_settings";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.pref_headers);
        Context ctx = requireContext();
        PreferenceScreen screen = getPreferenceScreen();

        Preference storagePref = screen.findPreference(getString(R.string.pref_key_storage_settings));
        Preference appearancePref = screen.findPreference(getString(R.string.pref_key_appearance_settings));
        Preference behaviorPref = screen.findPreference(getString(R.string.pref_key_behavior_settings));
        Preference streamingPref = screen.findPreference(getString(R.string.pref_key_streaming_settings));
        Preference networkPref = screen.findPreference(getString(R.string.pref_key_network_settings));
        Preference limitationsPref = screen.findPreference(getString(R.string.pref_key_limitations_settings));
        Preference schedulingPref = screen.findPreference(getString(R.string.pref_key_scheduling_settings));
        Preference feedPref = screen.findPreference(getString(R.string.pref_key_feed_settings));
        screen.removeAll();

        Preference languagePref = new Preference(ctx);
        languagePref.setKey("app_language");
        languagePref.setTitle(R.string.locale_app);
        languagePref.setIcon(R.drawable.ic_language_24);
        languagePref.setPersistent(false);
        languagePref.setOnPreferenceClickListener(p -> {
            String current = AppCompatDelegate.getApplicationLocales().toLanguageTags();
            int checked = current.startsWith("ar") ? 1 : current.startsWith("en") ? 2 : 0;
            String[] names = {
                    getString(R.string.locale_system),
                    getString(R.string.locale_ar),
                    getString(R.string.locale_en)
            };
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.locale_app)
                    .setSingleChoiceItems(names, checked, (dialog, index) -> {
                        dialog.dismiss();
                        AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(new String[]{"", "ar", "en"}[index]));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        addEngineSection(screen, storagePref, StorageSettingsFragmentDirections.actionStorageSettings());
        addEngineSection(screen, appearancePref, AppearanceSettingsFragmentDirections.actionAppearanceSettings());
        screen.addPreference(languagePref);
        addEngineSection(screen, behaviorPref, BehaviorSettingsFragmentDirections.actionBehaviorSettings());
        addEngineSection(screen, streamingPref, StreamingSettingsFragmentDirections.actionStreamingSettings());
        addEngineSection(screen, networkPref, NetworkSettingsFragmentDirections.actionNetworkSettings());
        addEngineSection(screen, limitationsPref, LimitationsSettingsFragmentDirections.actionLimitationSettings());
        addEngineSection(screen, schedulingPref, SchedulingSettingsFragmentDirections.actionSchedulingSettings());
        addEngineSection(screen, feedPref, FeedSettingsFragmentDirections.actionFeedSettings());
    }

    private void addEngineSection(@NonNull PreferenceScreen screen,
                                  @Nullable Preference pref,
                                  @NonNull NavDirections action) {
        if (pref == null) return;
        pref.setOnPreferenceClickListener(p -> {
            NavHostFragment.findNavController(this).navigate(action);
            return true;
        });
        screen.addPreference(pref);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.appBar.setTitle(title(requireContext()));
    }

    public static String title(@NonNull Context ctx) {
        var locales = ConfigurationCompat.getLocales(ctx.getResources().getConfiguration());
        Locale loc = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        boolean ar = loc != null && "ar".equalsIgnoreCase(loc.getLanguage());
        return ar ? "إعدادات محرك التحميل (LibreTorrent)" : "Download Engine Settings (LibreTorrent)";
    }
}
