package com.yourapp.stavelectroset.feature.news.auth.data.local.domain;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

public final class Captcha {
    private Captcha(){}

    public static final class Image {
        public final String text;
        public final Bitmap bitmap;
        public Image(String t, Bitmap b){ this.text = t; this.bitmap = b; }
    }

    /** Сгенерировать и картинку, и текст. */
    public static Image generate(int w, int h){
        String text = random(5);
        return new Image(text, drawInternal(text, w, h));
    }

    /** Совместимость: отрисовать только по тексту, размеры по умолчанию. */
    public static Bitmap draw(String text){
        return drawInternal(text, 520, 180);
    }

    // ---------- helpers ----------
    private static String random(int n){
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<n;i++) sb.append(abc.charAt((int)(Math.random()*abc.length())));
        return sb.toString();
    }

    private static Bitmap drawInternal(String text, int w, int h){
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(96f);
        p.setColor(Color.BLACK);
        float x = 40, y = h/2f + 30;
        c.drawText(text, x, y, p);
        p.setStrokeWidth(3f);
        for (int i = 0; i < 8; i++) {
            p.setColor(Color.rgb((int)(50+Math.random()*205),(int)(50+Math.random()*205),(int)(50+Math.random()*205)));
            c.drawLine((float)Math.random()*w, (float)Math.random()*h, (float)Math.random()*w, (float)Math.random()*h, p);
        }
        return bmp;
    }
}
