package com.sinicablekaveh.qrstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int OPEN_PHOTO = 401;
    private static final int SAVE_DOCUMENT = 402;
    private static final int DARK = Color.rgb(15, 23, 34);
    private static final int PANEL = Color.rgb(27, 39, 52);
    private static final int GOLD = Color.rgb(244, 188, 51);
    private static final int WHITE = Color.rgb(242, 246, 250);
    private static final int MUTED = Color.rgb(173, 192, 204);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Bitmap photo;
    private Bitmap qr;
    private Bitmap pendingBitmap;
    private Preview preview;
    private Button saveButton;
    private Switch hdSwitch;
    private TextView help;
    private TextView resolutionText;
    private TextView qrStatus;
    private Switch jpgSwitch;
    private String pendingFilename;
    private boolean pendingJpeg;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        qr = FixedQr.getBitmap();
        buildUi();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    private int d(float dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int background, float radius) {
        GradientDrawable b = new GradientDrawable();
        b.setColor(background);
        b.setCornerRadius(d(radius));
        return b;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(android.view.Gravity.CENTER);
        return v;
    }

    private Button button(String title, int bg, int textColor) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(textColor);
        b.setBackground(rounded(bg, 13));
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(0, d(top), 0, d(bottom));
        return p;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(DARK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(d(16), d(18), d(16), d(28));
        scroll.addView(root);
        setContentView(scroll);

        TextView brand = text("KAVEH  •  QR STUDIO", 23, GOLD);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(brand, lp(-1, -2, 0, 3));
        root.addView(text("سینی کابل کاوه  |  QR ثابت و آماده  |  نسخه حرفه‌ای 2.0", 13, MUTED), lp(-1,-2,0,12));

        preview = new Preview();
        preview.setBackground(rounded(PANEL, 17));
        root.addView(preview, lp(-1, d(346), 0, 8));

        help = text("یک عکس انتخاب کنید؛ QR کاوه خودکار روی آن قرار می‌گیرد.", 13, MUTED);
        root.addView(help, lp(-1,-2,0,4));
        resolutionText = text("تصویر انتخاب نشده است", 12, MUTED);
        root.addView(resolutionText, lp(-1,-2,0,10));

        Button choose = button("📷  انتخاب عکس از گالری", GOLD, DARK);
        choose.setOnClickListener(v -> openPhoto());
        root.addView(choose, lp(-1, d(53), 0, 10));

        saveButton = button("⬇  ذخیره تصویر نهایی در گالری", Color.rgb(48, 119, 93), WHITE);
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> saveImage());
        root.addView(saveButton, lp(-1, d(53), 0, 13));

        TextView sizeLabel = text("اندازه QR در عکس", 14, WHITE);
        root.addView(sizeLabel, lp(-1,-2,0,0));
        SeekBar size = new SeekBar(this);
        size.setMax(65);
        size.setProgress(22);
        if (Build.VERSION.SDK_INT >= 21) {
            size.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));
            size.setThumbTintList(android.content.res.ColorStateList.valueOf(GOLD));
        }
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean user) {
                preview.setQrFraction(0.14f + progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        root.addView(size, lp(-1, d(43), 0, 8));

        TextView presetsLabel = text("جای قرارگیری سریع QR", 14, WHITE);
        root.addView(presetsLabel, lp(-1, -2, 0, 4));
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        addPreset(presets, "بالا راست", 0);
        addPreset(presets, "بالا چپ", 1);
        addPreset(presets, "پایین راست", 2);
        addPreset(presets, "پایین چپ", 3);
        root.addView(presets, lp(-1, d(44), 0, 14));

        root.addView(text("تنظیم کیفیت و نور تصویر (بدون تأثیر روی کد QR)", 13, WHITE), lp(-1,-2,0,2));
        addPhotoSlider(root, "روشنایی عکس", 50, true);
        addPhotoSlider(root, "کنتراست عکس", 50, false);

        hdSwitch = new Switch(this);
        hdSwitch.setText("افزایش اندازه خروجی تا ۲ برابر + شارپ‌سازی ملایم (نه AI)");
        hdSwitch.setTextSize(13);
        hdSwitch.setTextColor(WHITE);
        hdSwitch.setChecked(false);
        if (Build.VERSION.SDK_INT >= 21) hdSwitch.setThumbTintList(android.content.res.ColorStateList.valueOf(GOLD));
        root.addView(hdSwitch, lp(-1,-2,0,6));
        jpgSwitch = new Switch(this);
        jpgSwitch.setText("فایل خروجی JPG کم‌حجم (PNG برای QR و چاپ مطمئن‌تر است)");
        jpgSwitch.setTextSize(13);
        jpgSwitch.setTextColor(WHITE);
        jpgSwitch.setChecked(false);
        root.addView(jpgSwitch, lp(-1, -2, 0, 8));
        qrStatus = text("لینک QR ثابت و بدون کوتاه‌کننده است.", 12, GOLD);
        root.addView(qrStatus, lp(-1,-2,0,8));
        Button linkTest = button("🔗  آزمایش بازشدن لینک QR", PANEL, GOLD);
        linkTest.setOnClickListener(v -> {
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(FixedQr.DESTINATION));
                startActivity(browser);
            } catch (Exception e) {
                Toast.makeText(this, "مرورگری برای باز کردن لینک پیدا نشد.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(linkTest, lp(-1, d(48), 0, 12));
        root.addView(text("QR ثابتِ برند کاوه. عکس را فقط یک‌بار انتخاب کن؛ با لمس جابه‌جا و با دو انگشت اندازه را تغییر بده.\nخروجی پیش‌فرض PNG با اندازه اصلی عکس است. بزرگ‌نمایی پیکسلی، جزئیات از دست رفته عکس را برنمی‌گرداند.\nویرایش و ذخیره آفلاین است؛ بازکردن لینک QR اینترنت می‌خواهد.", 12, MUTED), lp(-1,-2,0,0));
    }

    private void addPreset(LinearLayout row, String title, int position) {
        Button b = button(title, Color.rgb(44, 62, 77), WHITE);
        b.setTextSize(12);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(d(2), 0, d(2), 0);
        row.addView(b, p);
        b.setOnClickListener(v -> preview.setPreset(position));
    }

    private void addPhotoSlider(LinearLayout root, String name, int initial, boolean brightness) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = text(name, 12, MUTED);
        row.addView(label, new LinearLayout.LayoutParams(d(108), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(initial);
        if (Build.VERSION.SDK_INT >= 21) {
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(GOLD));
        }
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                if (brightness) preview.brightness = (progress - 50f) * .8f;
                else preview.contrast = .80f + progress * .004f;
                preview.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, d(40), 1f));
        root.addView(row, lp(-1,-2,0,4));
    }

    private void openPhoto() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, OPEN_PHOTO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == OPEN_PHOTO) {
            ProgressDialog p = progress("در حال بارگذاری عکس...");
            worker.execute(() -> {
                try {
                    Bitmap result = decodeImage(uri);
                    if (result == null) throw new Exception("عکس قابل خواندن نیست.");
                    runOnUiThread(() -> {
                        p.dismiss();
                        if (photo != null && photo != result && !photo.isRecycled()) photo.recycle();
                        photo = result;
                        preview.setPhoto(result);
                        saveButton.setEnabled(true);
                        help.setText("QR ثابت با لوگوی کاوه روی عکس قرار گرفت. موقعیت را تنظیم کن.");
                        resolutionText.setText("کیفیت عکس ورودی: " + result.getWidth() + " × " + result.getHeight() + " پیکسل");
                    });
                } catch (Exception ex) {
                    runOnUiThread(() -> { p.dismiss(); error(ex); });
                }
            });
        } else if (requestCode == SAVE_DOCUMENT && pendingBitmap != null) {
            Bitmap output = pendingBitmap;
            pendingBitmap = null;
            ProgressDialog p = progress("در حال ذخیره...");
            worker.execute(() -> {
                try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    if (stream == null || !output.compress(pendingJpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, pendingJpeg ? 98 : 100, stream)) throw new Exception("ذخیره انجام نشد.");
                    stream.flush();
                    runOnUiThread(() -> { p.dismiss(); Toast.makeText(this, "تصویر ذخیره شد ✅", Toast.LENGTH_LONG).show(); });
                } catch (Exception ex) { runOnUiThread(() -> { p.dismiss(); error(ex); }); }
                finally { output.recycle(); }
            });
        }
    }

    private ProgressDialog progress(String msg) {
        ProgressDialog p = new ProgressDialog(this);
        p.setMessage(msg);
        p.setCancelable(false);
        p.show();
        return p;
    }

    private void error(Exception ex) {
        Toast.makeText(this, "خطا: " + ex.getMessage(), Toast.LENGTH_LONG).show();
    }

    private Bitmap decodeImage(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Exception("فرمت عکس پشتیبانی نمی‌شود.");
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 5500) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, opts);
        }
        if (bitmap == null) throw new Exception("بارگذاری عکس ناموفق بود.");
        int rotation = 0;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(stream);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) { }
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (rotated != bitmap) bitmap.recycle();
            bitmap = rotated;
        }
        return bitmap;
    }

    private void saveImage() {
        if (photo == null) { Toast.makeText(this, "ابتدا عکس انتخاب کنید.", Toast.LENGTH_SHORT).show(); return; }
        boolean hd = hdSwitch.isChecked();
        boolean jpeg = jpgSwitch.isChecked();
        final Bitmap chosen = photo;
        float qx = preview.qx, qy = preview.qy, qFraction = preview.qFraction;
        final float brightness = preview.brightness, contrast = preview.contrast;
        float finalScale = hd ? Math.min(2f, 6000f / Math.max(chosen.getWidth(), chosen.getHeight())) : 1f;
        final int qrPixels = Math.round(Math.min(chosen.getWidth(), chosen.getHeight()) * qFraction * Math.max(1f, finalScale));
        if (qrPixels < 220) {
            new AlertDialog.Builder(this)
                .setTitle("QR برای اسکن کوچک است")
                .setMessage("اندازه QR در خروجی حدود " + qrPixels + " پیکسل می‌شود. برای چاپ، QR حدود ۲۵۰ پیکسل یا بیشتر بهتر است. اندازه آن را زیاد کن یا خروجی را ادامه بده.")
                .setNegativeButton("اصلاح اندازه", (dialog, which) -> {})
                .setPositiveButton("ادامه ذخیره", (dialog, which) -> doSave(chosen, qr, qx, qy, qFraction, brightness, contrast, hd, jpeg))
                .show();
            return;
        }
        doSave(chosen, qr, qx, qy, qFraction, brightness, contrast, hd, jpeg);
    }

    private void doSave(Bitmap chosen, Bitmap qrBitmap, float qx, float qy, float qFraction, float brightness, float contrast, boolean hd, boolean jpeg) {
        ProgressDialog p = progress("در حال ساخت خروجی باکیفیت...");
        worker.execute(() -> {
            try {
                Bitmap image = render(chosen, qrBitmap, qx, qy, qFraction, brightness, contrast, hd);
                runOnUiThread(() -> {
                    p.dismiss();
                    saveBitmap(image, jpeg);
                });
            } catch (OutOfMemoryError oom) {
                runOnUiThread(() -> { p.dismiss(); Toast.makeText(this, "عکس برای حافظه گوشی بسیار بزرگ است. گزینه HD را خاموش کنید.", Toast.LENGTH_LONG).show(); });
            } catch (Exception ex) { runOnUiThread(() -> { p.dismiss(); error(ex); }); }
        });
    }

    private static Bitmap render(Bitmap source, Bitmap qr, float qx, float qy, float fraction, float brightness, float contrast, boolean hd) {
        int sw = source.getWidth(), sh = source.getHeight();
        float factor = (hd && Math.max(sw, sh) < 6000) ? Math.min(2f, 6000f / Math.max(sw, sh)) : 1f;
        if (factor <= 0f) factor = 1f;
        int w = Math.max(1, Math.round(sw * factor)), h = Math.max(1, Math.round(sh * factor));
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint base = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG | Paint.ANTI_ALIAS_FLAG);
        base.setColorFilter(photoFilter(brightness, contrast));
        canvas.drawBitmap(source, null, new RectF(0, 0, w, h), base);
        if (hd && factor > 1.01f) enhanceSharpness(result);
        float side = Math.min(w, h) * fraction;
        float cx = qx * w, cy = qy * h;
        cx = Math.max(side * .5f, Math.min(w - side * .5f, cx));
        cy = Math.max(side * .5f, Math.min(h - side * .5f, cy));
        RectF qrRect = new RectF(cx - side/2, cy - side/2, cx + side/2, cy + side/2);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.WHITE);
        float pad = Math.max(2, side * .018f);
        canvas.drawRoundRect(new RectF(qrRect.left - pad, qrRect.top - pad, qrRect.right + pad, qrRect.bottom + pad), pad * 2, pad * 2, bg);
        Paint qrPaint = new Paint(); // nearest-neighbor rendering preserves exact QR modules
        qrPaint.setFilterBitmap(false);
        qrPaint.setAntiAlias(false);
        canvas.drawBitmap(qr, null, qrRect, qrPaint);
        return result;
    }

    private static ColorMatrixColorFilter photoFilter(float brightness, float contrast) {
        float t = 128f * (1f - contrast) + brightness;
        ColorMatrix m = new ColorMatrix(new float[] {
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f
        });
        return new ColorMatrixColorFilter(m);
    }

    // Gentle unsharp mask with three scanline buffers, avoiding a huge secondary bitmap.
    // The QR is composited after this filter, so the QR pattern stays exact and scannable.
    private static void enhanceSharpness(Bitmap bmp) {
        final int w = bmp.getWidth(), h = bmp.getHeight();
        if (w < 3 || h < 3) return;
        int[] prev = new int[w], cur = new int[w], next = new int[w], out = new int[w];
        bmp.getPixels(prev,0,w,0,0,w,1);
        bmp.getPixels(cur,0,w,0,1,w,1);
        for (int y=1; y<h-1; y++) {
            bmp.getPixels(next,0,w,0,y+1,w,1);
            System.arraycopy(cur,0,out,0,w);
            for (int x=1; x<w-1; x++) {
                int c=cur[x], a=prev[x], b=next[x], l=cur[x-1], r=cur[x+1];
                int rr=sharp(Color.red(c),Color.red(a),Color.red(b),Color.red(l),Color.red(r));
                int gg=sharp(Color.green(c),Color.green(a),Color.green(b),Color.green(l),Color.green(r));
                int bb=sharp(Color.blue(c),Color.blue(a),Color.blue(b),Color.blue(l),Color.blue(r));
                out[x]=Color.argb(Color.alpha(c),rr,gg,bb);
            }
            bmp.setPixels(out,0,w,0,y,w,1);
            int[] tmp=prev; prev=cur; cur=next; next=tmp;
        }
    }

    private static int sharp(int c,int up,int down,int left,int right) {
        int blur=(up+down+left+right+2)/4;
        return Math.max(0,Math.min(255,c+Math.round((c-blur)*0.30f)));
    }

    private void saveBitmap(Bitmap bitmap, boolean jpeg) {
        String suffix = jpeg ? ".jpg" : ".png";
        Bitmap.CompressFormat format = jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
        String mime = jpeg ? "image/jpeg" : "image/png";
        String file = "Kaveh_QR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + suffix;
        if (Build.VERSION.SDK_INT < 29) {
            pendingBitmap = bitmap;
            pendingJpeg = jpeg;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_TITLE, file);
            startActivityForResult(intent, SAVE_DOCUMENT);
            return;
        }
        ProgressDialog dialog = progress("در حال ذخیره عکس در گالری...");
        worker.execute(() -> {
            Uri resultUri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, file);
                values.put(MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KavehQRStudio");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                resultUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (resultUri == null) throw new Exception("فضای ذخیره‌سازی در دسترس نیست.");
                try (OutputStream output = getContentResolver().openOutputStream(resultUri)) {
                    if (output == null || !bitmap.compress(format, jpeg ? 98 : 100, output)) throw new Exception("خطا در نوشتن تصویر");
                    output.flush();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(resultUri, done, null, null);
                runOnUiThread(() -> { dialog.dismiss(); Toast.makeText(this, "ذخیره شد ✅  Pictures/KavehQRStudio", Toast.LENGTH_LONG).show(); });
            } catch (Exception ex) {
                if (resultUri != null) getContentResolver().delete(resultUri, null, null);
                runOnUiThread(() -> { dialog.dismiss(); error(ex); });
            } finally { bitmap.recycle(); }
        });
    }

    private class Preview extends View {
        private Bitmap image;
        private float qx = .72f, qy = .74f, qFraction = .36f;
        private float brightness = 0f, contrast = 1.0f;
        private final RectF photoRect = new RectF();
        private final RectF qrRect = new RectF();
        private boolean dragging;
        private float offX, offY, pinchDist, pinchSize;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint qrPaint = new Paint();
        private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Preview() { super(MainActivity.this); whitePaint.setColor(Color.WHITE); outlinePaint.setColor(GOLD); outlinePaint.setStyle(Paint.Style.STROKE); outlinePaint.setStrokeWidth(d(2)); }
        void setPhoto(Bitmap bmp) {
            image = bmp;
            qx = bmp.getWidth() > bmp.getHeight() ? .76f : .50f;
            qy = bmp.getWidth() > bmp.getHeight() ? .72f : .78f;
            ensureBounds(); invalidate();
        }
        void setQrFraction(float f) { qFraction = Math.max(.12f,Math.min(.78f,f)); ensureBounds(); invalidate(); }
        void setPreset(int preset) {
            if (image == null) return;
            float rx = qFraction * Math.min(image.getWidth(), image.getHeight()) / image.getWidth() / 2f;
            float ry = qFraction * Math.min(image.getWidth(), image.getHeight()) / image.getHeight() / 2f;
            qx = (preset == 0 || preset == 2) ? 1f - rx - .025f : rx + .025f;
            qy = (preset == 0 || preset == 1) ? ry + .025f : 1f - ry - .025f;
            ensureBounds();
            invalidate();
        }
        private void ensureBounds() {
            if (image == null) return;
            float halfWidth = qFraction * Math.min(image.getWidth(),image.getHeight()) / image.getWidth() / 2;
            float halfHeight= qFraction * Math.min(image.getWidth(),image.getHeight()) / image.getHeight() / 2;
            qx=Math.max(halfWidth+.008f,Math.min(1-halfWidth-.008f,qx));
            qy=Math.max(halfHeight+.008f,Math.min(1-halfHeight-.008f,qy));
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (image == null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(MUTED); p.setTextAlign(Paint.Align.CENTER);p.setTextSize(d(16));
                c.drawText("📷", getWidth()/2f, getHeight()/2f-d(18),p);
                c.drawText("عکس دلخواهت را وارد کن", getWidth()/2f, getHeight()/2f+d(18),p);
                return;
            }
            float scale=Math.min((getWidth()-d(10))/(float)image.getWidth(),(getHeight()-d(10))/(float)image.getHeight());
            float width=image.getWidth()*scale,height=image.getHeight()*scale;
            photoRect.set((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
            imagePaint.setColorFilter(photoFilter(brightness, contrast));
            c.drawBitmap(image,null,photoRect,imagePaint);
            float side=Math.min(width,height)*qFraction;
            float centerX=photoRect.left+width*qx,centerY=photoRect.top+height*qy;
            qrRect.set(centerX-side/2,centerY-side/2,centerX+side/2,centerY+side/2);
            float pad=d(3);
            c.drawRoundRect(new RectF(qrRect.left-pad,qrRect.top-pad,qrRect.right+pad,qrRect.bottom+pad),d(4),d(4),whitePaint);
            qrPaint.setFilterBitmap(false);
            qrPaint.setAntiAlias(false);
            c.drawBitmap(qr,null,qrRect,qrPaint);
            c.drawRoundRect(qrRect,d(3),d(3),outlinePaint);
        }
        private void move(float x,float y) {
            if (photoRect.width()<=0||photoRect.height()<=0) return;
            qx=(x-photoRect.left)/photoRect.width();
            qy=(y-photoRect.top)/photoRect.height();
            ensureBounds();invalidate();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (image == null) return false;
            switch(e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging=true;
                    offX = qrRect.contains(e.getX(),e.getY()) ? qrRect.centerX()-e.getX() : 0;
                    offY = qrRect.contains(e.getX(),e.getY()) ? qrRect.centerY()-e.getY() : 0;
                    move(e.getX()+offX,e.getY()+offY);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount()>=2) {
                        dragging=false;
                        pinchDist=span(e);
                        pinchSize=qFraction;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (e.getPointerCount()>=2 && pinchDist>1) {
                        setQrFraction(pinchSize*span(e)/pinchDist);
                    } else if (dragging) move(e.getX()+offX,e.getY()+offY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging=false;pinchDist=0;return true;
                case MotionEvent.ACTION_POINTER_UP:
                    dragging=false;pinchDist=0;return true;
            }
            return true;
        }
        private float span(MotionEvent e){float dx=e.getX(0)-e.getX(1),dy=e.getY(0)-e.getY(1);return (float)Math.hypot(dx,dy);}
    }
}