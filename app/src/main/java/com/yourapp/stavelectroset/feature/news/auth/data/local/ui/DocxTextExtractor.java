package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.File;
import java.io.FileInputStream;

public final class DocxTextExtractor {
    private DocxTextExtractor() {}

    @Nullable
    public static String extract(@NonNull File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream in = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {

            for (XWPFParagraph p : doc.getParagraphs()) {
                if (p == null) continue;
                String t = p.getText();
                if (t != null && !t.trim().isEmpty()) {
                    sb.append(t).append("\n\n");
                }
            }
        }
        return sb.toString();
    }
}