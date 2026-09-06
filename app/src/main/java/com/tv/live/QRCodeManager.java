package com.tv.live;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class QRCodeManager {

    private final Context context;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public QRCodeManager(Context context) {
        this.context = context;
    }

    public void showQRCodeDialog(String content) {
        final ImageView iv = new ImageView(context);
        iv.setBackgroundColor(Color.LTGRAY);

        new Thread(() -> {
            final Bitmap bitmap = createQR(content, 250);

            mainHandler.post(() -> {
                if (bitmap != null) {
                    iv.setImageBitmap(bitmap);
                } else {
                    iv.setBackgroundColor(Color.LTGRAY);
                }
            });
        }).start();

        new AlertDialog.Builder(context)
                .setTitle("扫码管理")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show();
    }

    public Bitmap createQR(String text, int size) {
        try {

            if (size < 150) {
                size = 200;
            }

            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);

            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
