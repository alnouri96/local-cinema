/*
 * Copyright (C) 2025 Yaroslav Pronin <proninyaroslav@mail.ru>
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

package org.proninyaroslav.libretorrent.ui.home;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.meedx.updates.ProjectLinks;

import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.system.SystemFacadeHelper;
import org.proninyaroslav.libretorrent.databinding.DialogAboutBinding;

public class AboutDialog extends DialogFragment {
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        var activity = requireActivity();
        var binding = DialogAboutBinding.inflate(getLayoutInflater(), null, false);

        var builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.app_name)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.ok, (dialog, which) -> dismiss())
                .setNeutralButton(R.string.about_changelog, (dialog, which) -> openChangelogLink());

        String versionName = SystemFacadeHelper
                .getSystemFacade(activity.getApplicationContext())
                .getAppVersionName();
        if (versionName != null) {
            binding.version.setText(versionName);
        }

        var locales = androidx.core.os.ConfigurationCompat.getLocales(activity.getResources().getConfiguration());
        java.util.Locale loc = locales.isEmpty() ? java.util.Locale.getDefault() : locales.get(0);
        boolean ar = loc != null && "ar".equalsIgnoreCase(loc.getLanguage());
        binding.donateLink.setText(ar ? "دعم المشروع والمطور (Donate)" : "Support & Donate");
        binding.donateLink.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://meedx.pages.dev/")));
            } catch (Exception ignored) {}
        });
        binding.sourceLink.setOnClickListener(view ->
                ProjectLinks.open(activity, ProjectLinks.SOURCE, R.string.cinema_project_link_unavailable));
        binding.licensesLink.setOnClickListener(view ->
                ProjectLinks.open(activity, ProjectLinks.LICENSES, R.string.cinema_project_link_unavailable));

        return builder.create();
    }

    private void openChangelogLink() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(getString(R.string.about_changelog_link)));
        startActivity(i);
    }
}
