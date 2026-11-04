package com.sinicablekaveh.qrstudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/** Optional offline QA: scans the actual composited QR pixels, not the source URL string. */
final class QrVerifier {
    private QrVerifier() {}

    static boolean verify(Bitmap exported, float qx, float qy, float fraction, String expected) {
        if(exported == null || exported.isRecycled()) return false;
        Bitmap test = null;
        try {
            int w = exported.getWidth(), h = exported.getHeight();
            float side = Math.min(w,h) * fraction;
            if(side < 220) return false;
            float cx = Math.max(side*.5f, Math.min(w-side*.5f, qx*w));
            float cy = Math.max(side*.5f, Math.min(h-side*.5f, qy*h));
            // Add whitespace around the white QR border for a reliable quiet zone.
            float margin = Math.max(7f, side*.05f);
            float left=cx-side*.5f-margin, top=cy-side*.5f-margin;
            float right=cx+side*.5f+margin, bottom=cy+side*.5f+margin;
            int out = Math.min(1300, Math.max(360, Math.round(side + 2f*margin)));
            test = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(test);
            canvas.drawColor(Color.WHITE);
            Paint p = new Paint();
            p.setFilterBitmap(false);
            p.setAntiAlias(false);
            RectF d = new RectF(0,0,out,out);
            Rect src = new Rect(Math.max(0,(int)Math.floor(left)), Math.max(0,(int)Math.floor(top)),
                    Math.min(w,(int)Math.ceil(right)), Math.min(h,(int)Math.ceil(bottom)));
            // Adjust destination rectangle when source crop would extend off photo edges.
            d.left=(src.left-left)/(right-left)*out;
            d.top=(src.top-top)/(bottom-top)*out;
            d.right=(src.right-left)/(right-left)*out;
            d.bottom=(src.bottom-top)/(bottom-top)*out;
            canvas.drawBitmap(exported,src,d,p);
            int[] pixels = new int[out*out];
            test.getPixels(pixels,0,out,0,0,out,out);
            RGBLuminanceSource source = new RGBLuminanceSource(out,out,pixels);
            Result scan = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)));
            return expected.equals(scan.getText());
        } catch(Exception | OutOfMemoryError ignored) {
            return false;
        } finally {
            if(test!=null && !test.isRecycled()) test.recycle();
        }
    }
}