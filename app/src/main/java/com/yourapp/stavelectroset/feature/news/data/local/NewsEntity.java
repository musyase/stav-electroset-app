package com.yourapp.stavelectroset.feature.news.data.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "news")
public class NewsEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    @Nullable public String title;
    @Nullable public String url;

    public long dateUtc = 0L;

    @Nullable public String excerpt;
    @Nullable public String imageUrl;
    @Nullable public String contentHtml;

    public NewsEntity() {
        // Пустая конструкция для рум
    }

    @NonNull
    public static String makeIdFromUrl(@NonNull String url) {
        return Integer.toHexString(url.hashCode());
    }
}
