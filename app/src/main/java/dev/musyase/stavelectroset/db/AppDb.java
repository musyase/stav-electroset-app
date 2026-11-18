package dev.musyase.stavelectroset.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import dev.musyase.stavelectroset.feature.news.data.local.NewsDao;
import dev.musyase.stavelectroset.feature.news.data.local.NewsEntity;

@Database(entities = { NewsEntity.class }, version = 2, exportSchema = true)
public abstract class AppDb extends RoomDatabase {
    public abstract NewsDao news();
}
