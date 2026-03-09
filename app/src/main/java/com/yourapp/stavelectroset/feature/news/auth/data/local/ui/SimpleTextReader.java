package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;

public final class SimpleTextReader {
    private SimpleTextReader() {}

    @Nullable
    public static String read(@NonNull File file, int maxChars) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), Math.max(1, maxChars))];
            int r = in.read(buf);
            if (r <= 0) return "";
            return new String(buf, 0, r);
        }
    }
}