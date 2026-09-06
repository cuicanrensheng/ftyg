package com.tv.live.security;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public final class IntegrityCheck {

    private IntegrityCheck() {}

    public static byte[] computeApkHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(new File(apkPath));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            fis.close();
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }

    public static byte[] computeDexHash(android.content.Context ctx) {
        try {
            String apkPath = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0).sourceDir;
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkPath);
            java.util.zip.ZipEntry entry = zf.getEntry("classes.dex");
            if (entry == null) { zf.close(); return null; }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            java.io.InputStream is = zf.getInputStream(entry);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            is.close();
            zf.close();
            return md.digest();
        } catch (Throwable t) {
            return null;
        }
    }
}
