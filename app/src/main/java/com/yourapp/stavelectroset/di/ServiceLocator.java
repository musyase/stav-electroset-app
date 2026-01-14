package com.yourapp.stavelectroset.di;

import android.content.Context;

import androidx.room.Room;

import com.yourapp.stavelectroset.db.AppDb;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestDao;
import com.yourapp.stavelectroset.feature.news.data.remote.NewsLoader;
import com.yourapp.stavelectroset.feature.news.domain.NewsRepository;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class ServiceLocator {

    private static volatile ServiceLocator instance;

    public final AppDb db;
    public final Retrofit retrofit;

    private final ExecutorService io;              // пул для фоновых задач
    private volatile NewsRepository newsRepository; // кэш репозитория

    private ServiceLocator(Context app) {
        db = Room.databaseBuilder(app, AppDb.class, "app.db")
                .fallbackToDestructiveMigration()
                .build();

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient ok = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        retrofit = new Retrofit.Builder()
                .baseUrl("https://stavels.ru/")            // слэш обязателен
                .addConverterFactory(GsonConverterFactory.create())
                .client(ok)
                .build();

        io = Executors.newSingleThreadExecutor();
    }

    public static ServiceLocator get(Context ctx) {
        if (instance == null) {
            synchronized (ServiceLocator.class) {
                if (instance == null) {
                    instance = new ServiceLocator(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** Отдаём Executor, если где-то нужен напрямую. */
    public Executor io() { return io; }

    /** Репозиторий новостей (ленивая и потокобезопасная инициализация). */
    public NewsRepository newsRepository() {
        NewsRepository local = newsRepository;
        if (local == null) {
            synchronized (this) {
                local = newsRepository;
                if (local == null) {
                    // ВАЖНО: у твоего NewsLoader конструктор с 1 аргументом — прокидываем Retrofit
                    local = new NewsRepository(db.news(), new NewsLoader(retrofit), io);
                    newsRepository = local;
                }
            }
        }
        return local;
    }
    public CertificateRequestDao certificateRequests() {
        return db.certificateRequests();
    }

}
