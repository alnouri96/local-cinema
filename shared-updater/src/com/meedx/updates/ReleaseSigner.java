package com.meedx.updates;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public final class ReleaseSigner {
    // Public certificate fingerprint shared by the TV and Phone production builds.
    private static final String OFFICIAL_SHA256 =
            "ea8ac52649340e79b6fcddf3a9e9942e56c4c5e16f2de1f63d7d70e99cf398ec";

    private ReleaseSigner() {}

    @SuppressWarnings("deprecation")
    public static void verify(Context context) {
        try {
            var manager = context.getPackageManager();
            boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
            int flags = modern ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), flags);
            Signature[] signers = modern
                    ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
                    : info.signatures;
            if (signers == null || signers.length != 1 || !Arrays.equals(
                    MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()), officialFingerprint())) {
                throw new SecurityException("Unexpected Local Cinema signing certificate");
            }
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException error) {
            throw new SecurityException("Could not verify Local Cinema signing certificate", error);
        }
    }

    private static byte[] officialFingerprint() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(OFFICIAL_SHA256.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
