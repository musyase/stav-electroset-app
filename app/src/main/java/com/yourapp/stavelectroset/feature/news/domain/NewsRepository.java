package com.yourapp.stavelectroset.feature.news.domain;

import androidx.lifecycle.LiveData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import retrofit2.Retrofit;

import com.yourapp.stavelectroset.feature.news.data.local.NewsDao;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;
import com.yourapp.stavelectroset.feature.news.data.remote.NewsLoader;

public final class NewsRepository {
    private final NewsDao dao;
    private final NewsLoader loader;

    public NewsRepository(NewsDao dao, Retrofit retrofit) {
        this.dao = dao;
        this.loader = new NewsLoader(retrofit);
    }

    public LiveData<List<NewsEntity>> observe() { return dao.observeAll(); }

    public void refresh() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<NewsEntity> list = loader.load();
                if (list == null) list = new ArrayList<>();
                dao.upsertAll(list);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
