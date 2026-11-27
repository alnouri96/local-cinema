/*
 * Copyright (C) 2018-2025 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.ui.settings.pages;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.RepositoryHelper;
import org.proninyaroslav.libretorrent.core.settings.SettingsRepository;
import org.proninyaroslav.libretorrent.ui.settings.CustomPreferenceFragment;

public class FeedSettingsFragment extends CustomPreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = FeedSettingsFragment.class.getSimpleName();

    private static final String KEY_AUTO_REFRESH_SETTINGS_REQUEST = TAG + "_auto_refresh_settings";

    private AppCompatActivity activity;
    private SettingsRepository pref;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof AppCompatActivity a) {
            activity = a;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (activity == null) {
            activity = (AppCompatActivity) requireActivity();
        }

        pref = RepositoryHelper.getSettingsRepository(activity.getApplicationContext());

        String keyAutoRefresh = getString(R.string.pref_key_feed_auto_refresh);
        var autoRefresh = findPreference(keyAutoRefresh);
        if (autoRefresh != null) {
            autoRefresh.setSummary(pref.autoManage() ? R.string.switch_on : R.string.switch_off);
            autoRefresh.setOnPreferenceClickListener((p) -> {
                var action = FeedSettingsFragmentDirections.actionAutoRefreshSettings(
                        KEY_AUTO_REFRESH_SETTINGS_REQUEST
                );
                NavHostFragment.findNavController(this).navigate(action);
                return true;
            });
        }

        String keyKeepTime = getString(R.string.pref_key_feed_keep_items_time);
        ListPreference keepTime = findPreference(keyKeepTime);
        if (keepTime != null) {
            String time = Long.toString(pref.feedItemKeepTime());
            int timeIndex = keepTime.findIndexOfValue(time);
            if (timeIndex >= 0)
                keepTime.setValueIndex(timeIndex);
            bindOnPreferenceChangeListener(keepTime);
        }

        String keyStartTorrents = getString(R.string.pref_key_feed_start_torrents);
        SwitchPreferenceCompat startTorrents = findPreference(keyStartTorrents);
        if (startTorrents != null) {
            startTorrents.setChecked(pref.feedStartTorrents());
            bindOnPreferenceChangeListener(startTorrents);
        }

        String keyRemoveDuplicates = getString(R.string.pref_key_feed_remove_duplicates);
        SwitchPreferenceCompat removeDuplicates = findPreference(keyRemoveDuplicates);
        if (removeDuplicates != null) {
            removeDuplicates.setChecked(pref.feedRemoveDuplicates());
            bindOnPreferenceChangeListener(removeDuplicates);
        }

        setAutoRefreshSettingsListener();
    }

    private void setAutoRefreshSettingsListener() {
        getParentFragmentManager().setFragmentResultListener(
                KEY_AUTO_REFRESH_SETTINGS_REQUEST,
                this,
                (requestKey, result) -> {
                    boolean enabled = result.getBoolean(AutoManageSettingsFragment.KEY_RESULT);
                    var autoRefresh = findPreference(getString(R.string.pref_key_feed_auto_refresh));
                    if (autoRefresh != null) {
                        autoRefresh.setSummary(enabled ? R.string.switch_on : R.string.switch_off);
                    }
                }
        );
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.collapsingToolbarLayout.setTitleEnabled(false);
        android.view.ViewGroup.LayoutParams ctlLp = binding.collapsingToolbarLayout.getLayoutParams();
        if (ctlLp != null) {
            ctlLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            binding.collapsingToolbarLayout.setLayoutParams(ctlLp);
        }
        binding.appBar.setTitle(R.string.pref_header_feed);

        final String btnTag = "open_rss_feeds_header_btn";
        View existing = binding.appBar.findViewWithTag(btnTag);
        if (existing != null) {
            binding.appBar.removeView(existing);
        }
        Context ctx = requireContext();
        var locales = androidx.core.os.ConfigurationCompat.getLocales(ctx.getResources().getConfiguration());
        java.util.Locale loc = locales.isEmpty() ? java.util.Locale.getDefault() : locales.get(0);
        boolean ar = loc != null && "ar".equalsIgnoreCase(loc.getLanguage());
        float density = ctx.getResources().getDisplayMetrics().density;
        int hPx = Math.round(38f * density);
        int padH = Math.round(14f * density);
        int marginEnd = Math.round(12f * density);

        com.google.android.material.button.MaterialButton feedsBtn =
                new com.google.android.material.button.MaterialButton(ctx);
        feedsBtn.setTag(btnTag);
        feedsBtn.setAllCaps(false);
        feedsBtn.setSingleLine(true);
        feedsBtn.setText(ar ? "قنوات وخلاصات RSS" : "RSS Feeds");
        feedsBtn.setTextSize(13f);
        feedsBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        feedsBtn.setTextColor(0xFF171717);
        feedsBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE5A93B));
        feedsBtn.setIconResource(R.drawable.ic_rss_feed_24px);
        feedsBtn.setIconTint(android.content.res.ColorStateList.valueOf(0xFF171717));
        feedsBtn.setIconSize(Math.round(18f * density));
        feedsBtn.setIconPadding(Math.round(6f * density));
        feedsBtn.setCornerRadius(Math.round(12f * density));
        feedsBtn.setInsetTop(0);
        feedsBtn.setInsetBottom(0);
        feedsBtn.setMinHeight(hPx);
        feedsBtn.setMinimumHeight(hPx);
        feedsBtn.setPaddingRelative(padH, 0, padH, 0);
        feedsBtn.setOnClickListener(v ->
                org.proninyaroslav.libretorrent.ui.feeds.FeedsActivity.open(requireActivity()));

        androidx.appcompat.widget.Toolbar.LayoutParams lp = new androidx.appcompat.widget.Toolbar.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                hPx,
                android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        lp.setMarginEnd(marginEnd);
        binding.appBar.addView(feedsBtn, lp);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pref_feed, rootKey);
    }

    private void bindOnPreferenceChangeListener(Preference preference) {
        preference.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference.getKey().equals(getString(R.string.pref_key_feed_keep_items_time))) {
            long keepTime = Long.parseLong((String) newValue);
            pref.feedItemKeepTime(keepTime);
        } else if (preference.getKey().equals(getString(R.string.pref_key_feed_start_torrents))) {
            pref.feedStartTorrents((boolean) newValue);
        } else if (preference.getKey().equals(getString(R.string.pref_key_feed_remove_duplicates))) {
            pref.feedRemoveDuplicates((boolean) newValue);
        }
        return true;
    }
}
