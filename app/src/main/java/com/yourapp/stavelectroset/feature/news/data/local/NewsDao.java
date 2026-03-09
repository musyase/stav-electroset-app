package com.yourapp.stavelectroset.feature.news.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface NewsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<NewsEntity> list);

    @Query("SELECT * FROM news ORDER BY dateUtc DESC")
    LiveData<List<NewsEntity>> observeAll();

    @Query("SELECT * FROM news ORDER BY dateUtc DESC")
    List<NewsEntity> getAllSync();

    @Query("DELETE FROM news")
    void clear();
}