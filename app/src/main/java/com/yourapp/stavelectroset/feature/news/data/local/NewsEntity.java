package com.yourapp.stavelectroset.feature.news.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "news")
public class NewsEntity {
    @PrimaryKey @NonNull public String id;  // хеш URL/slug
    public String title;
    public String url;
    public long   dateUtc;
    public String excerpt;
    public String imageUrl;
    public String contentHtml;
}
