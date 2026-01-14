package com.yourapp.stavelectroset.feature.news.domain;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.yourapp.stavelectroset.feature.news.data.local.NewsDao;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;
import com.yourapp.stavelectroset.feature.news.data.remote.NewsLoader;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NewsRepository {
    private static final String TAG = "NewsRepository";

    private final NewsDao dao;
    private final NewsLoader loader;
    private final Executor io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public NewsRepository(NewsDao dao, NewsLoader loader, ExecutorService io) {
        this.dao = dao;
        this.loader = loader;
    }

    public androidx.lifecycle.LiveData<java.util.List<NewsEntity>> observeAll() {
        return dao.observeAll();
    }

    /** onFinish вызывается ВСЕГДА (и при успехе, и при ошибке) */
    public void refresh(Runnable onFinish) {
        io.execute(() -> {
            try {
                List<NewsEntity> items = loader.load();  // тут сеть/парсинг
                if (items != null && !items.isEmpty()) {
                    dao.upsertAll(items);
                } else {
                    Log.w(TAG, "refresh: loaded empty list");
                }
            } catch (Throwable t) {
                Log.e(TAG, "refresh failed", t);
            } finally {
                if (onFinish != null) main.post(onFinish);
            }
        });
    }
}
