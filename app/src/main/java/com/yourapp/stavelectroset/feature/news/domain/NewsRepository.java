package com.yourapp.stavelectroset.feature.news.domain;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.yourapp.stavelectroset.feature.news.data.local.NewsDao;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;
import com.yourapp.stavelectroset.feature.news.data.remote.NewsLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

public class NewsRepository {

    private static final String TAG = "NewsRepository";

    private final NewsDao dao;
    private final NewsLoader loader;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());

    public NewsRepository(@NonNull NewsDao dao,
                          @NonNull NewsLoader loader,
                          @NonNull ExecutorService io) {
        this.dao = Objects.requireNonNull(dao, "dao == null");
        this.loader = Objects.requireNonNull(loader, "loader == null");
        this.io = Objects.requireNonNull(io, "io == null");
    }

    @NonNull
    public LiveData<List<NewsEntity>> observeAll() {
        return dao.observeAll();
    }

    /** onFinish вызывается ВСЕГДА (и при успехе, и при ошибке) */
    public void refresh(@Nullable Runnable onFinish) {
        io.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try {
                Log.d(TAG, "refresh: start");

                // 1) сеть/парсинг
                List<NewsEntity> loaded;
                try {
                    loaded = loader.load();
                } catch (Exception e) {
                    Log.e(TAG, "refresh: loader.load failed", e);
                    return;
                }

                int loadedCount = (loaded == null) ? 0 : loaded.size();
                Log.d(TAG, "refresh: loaded=" + loadedCount);

                if (loaded == null || loaded.isEmpty()) {
                    Log.w(TAG, "refresh: loaded empty list");
                    return;
                }

                // 2) санитайзинг перед Room (очень важно для @NonNull PK)
                List<NewsEntity> safe = sanitize(loaded);
                Log.d(TAG, "refresh: sanitized=" + safe.size());

                if (safe.isEmpty()) {
                    Log.w(TAG, "refresh: sanitized list is empty (all items invalid)");
                    return;
                }

                // 3) запись в БД
                try {
                    dao.upsertAll(safe);
                } catch (Exception e) {
                    Log.e(TAG, "refresh: dao.upsertAll failed", e);
                    return;
                }

                // 4) быстрая проверка: что реально лежит в БД
                try {
                    List<NewsEntity> dbNow = dao.getAllSync();
                    Log.d(TAG, "refresh: dbSize=" + (dbNow == null ? 0 : dbNow.size()));
                } catch (Exception e) {
                    // не критично, просто лог
                    Log.w(TAG, "refresh: db check failed", e);
                }

            } finally {
                long dt = SystemClock.elapsedRealtime() - t0;
                Log.d(TAG, "refresh: finish in " + dt + "ms");
                if (onFinish != null) {
                    main.post(onFinish);
                }
            }
        });
    }

    /**
     * Делает список безопасным для Room и стабильным:
     * - выкидывает null и элементы без url
     * - гарантирует id (PK) из url
     * - убирает дубли по id (последний побеждает)
     */
    @NonNull
    private static List<NewsEntity> sanitize(@NonNull List<NewsEntity> input) {
        Map<String, NewsEntity> byId = new LinkedHashMap<>();

        for (NewsEntity e : input) {
            if (e == null) continue;

            // url — основной источник id
            String url = trimToNull(e.url);
            if (url == null) continue;

            // id обязательно для Room (@NonNull PK)
            String id = trimToNull(e.id);
            if (id == null) {
                id = NewsEntity.makeIdFromUrl(url);
                e.id = id;
            }

            // косметика: подчищаем поля
            e.url = url;
            e.title = trimToNull(e.title);
            e.excerpt = trimToNull(e.excerpt);
            e.imageUrl = trimToNull(e.imageUrl);
            e.contentHtml = trimToNull(e.contentHtml);

            byId.put(id, e);
        }

        return new ArrayList<>(byId.values());
    }

    @Nullable
    private static String trimToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
