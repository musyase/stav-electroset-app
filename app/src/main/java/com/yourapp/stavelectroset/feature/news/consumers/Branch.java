package com.yourapp.stavelectroset.feature.news.consumers;

import androidx.annotation.Nullable;
import com.yandex.mapkit.geometry.Point;

public class Branch {
    public final String id;
    public final String title;
    public final String address;     // можно оставить пустым, если есть point
    @Nullable public Point point;    // можно оставить null, если есть address
    @Nullable public String phone;
    @Nullable public String site;
    @Nullable public String description;

    public Branch(String id, String title, String address, @Nullable Point point,
                  @Nullable String phone, @Nullable String site, @Nullable String description) {
        this.id = id;
        this.title = title;
        this.address = address == null ? "" : address;
        this.point = point;
        this.phone = phone;
        this.site = site;
        this.description = description;
    }
}
