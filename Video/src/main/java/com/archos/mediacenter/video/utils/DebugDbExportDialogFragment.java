// Copyright 2017 Archos SA
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.utils;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.DialogFragment;

import com.archos.filecorelibrary.zip.ZipUtils;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.ui.NovaProgressDialog;
import com.archos.mediaprovider.video.VideoOpenHelper;

import java.io.File;

/**
 * Created by vapillon on 13/08/15.
 */
public class DebugDbExportDialogFragment extends DialogFragment {

    private final static boolean DBG = false;
    private final static String TAG = "DebugDbExport";

    NovaProgressDialog mDialog = null;
    AsyncTask mExportTask = null;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (DBG) Log.d(TAG, "onCreateDialog");
        mDialog = new NovaProgressDialog(getActivity());
        mDialog.setIndeterminate(true);
        mDialog.setCancelable(false);
        mDialog.setMessage(getString(R.string.preparing_media_database));
        mDialog.setButton(Dialog.BUTTON_NEGATIVE, getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
        return mDialog;
    }

    @Override
    public void onResume() {
        if (DBG) Log.d(TAG, "onResume");
        super.onResume();

        if (mExportTask !=null) {
            mExportTask.cancel(true); // should not happen
        }
        mExportTask = new DbExportTask();
        mExportTask.execute();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (DBG) Log.d(TAG, "onDismiss");
        super.onDismiss(dialog);

        if (mExportTask !=null) {
            mExportTask.cancel(true);
            mExportTask = null;
        }
    }

    @Override
    public void onPause() {
        if (DBG) Log.d(TAG, "onPause");
        super.onPause();

        // Better hide the dialog in case of screen rotation, to avoid having to recreate a new zip
        // task while the previous one is maybe not yet fully finished...
        dismiss();
    }

    private class DbExportTask extends AsyncTask<Object, Void, File> {

        @Override
        protected void onPreExecute() {
            if (DBG) Log.d(TAG, "onPreExecute ");
            super.onPreExecute();
        }

        @Override
        protected File doInBackground(Object... params) {
            if (DBG) Log.d(TAG, "doInBackground");
            Context context = getContext();
            if (context == null) {
                return null;
            }
            File libFile = VideoOpenHelper.getDatabaseFile(context);
            if (!libFile.isFile()) {
                return null;
            }
            File exportDir = new File(context.getCacheDir(), "diagnostics");
            if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
                return null;
            }
            File zipFile = new File(exportDir, "video_db.zip");
            if (DBG) Log.d(TAG, "zipFile = "+zipFile);

            // Erase zipped library if there is already one
            if (zipFile.exists()) {
                zipFile.delete();
            }

            // Zip it!
            boolean zipSuccess = ZipUtils.compressFile(libFile, zipFile);
            if (DBG) Log.d(TAG, "Size uncompressed: " + libFile.length());
            if (DBG) Log.d(TAG, "Size compressed:   " + zipFile.length());
            return zipSuccess ? zipFile : null;
        }

        @Override
        protected void onPostExecute(File zipFile) {
            if (DBG) Log.d(TAG, "onPostExecute "+zipFile);
            Context context = getContext();
            if (context == null) {
                return;
            }
            if (zipFile != null) {
                Uri archive = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", zipFile);
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.zipped_library_mail_subject))
                        .putExtra(Intent.EXTRA_STREAM, archive)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(share, getString(R.string.share_media_database)));
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(context, R.string.no_media_database_share_app, Toast.LENGTH_SHORT).show();
                }
            }
            else {
                // Error
                new AlertDialog.Builder(getActivity())
                        .setMessage(R.string.error)
                        .show();
            }

            mDialog.dismiss();
        }
    }

}
