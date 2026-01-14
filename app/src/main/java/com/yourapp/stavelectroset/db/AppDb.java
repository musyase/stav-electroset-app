package com.yourapp.stavelectroset.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestEntity;
import com.yourapp.stavelectroset.feature.news.data.local.NewsDao;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;

@Database(
        entities = { NewsEntity.class, UserEntity.class, CertificateRequestEntity.class },
        version = 6,            // +1
        exportSchema = false
)
public abstract class AppDb extends RoomDatabase {
    public abstract NewsDao news();
    public abstract UserDao  user();
    public abstract CertificateRequestDao certificateRequests();
}
