package com.yourapp.stavelectroset.di;

import android.content.Context;

import androidx.room.Room;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yourapp.stavelectroset.db.AppDb;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class ServiceLocator {
    private static volatile ServiceLocator I;
    public static ServiceLocator get(Context c) {
        if (I == null) {
            synchronized (ServiceLocator.class) {
                if (I == null) I = new ServiceLocator(c.getApplicationContext());
            }
        }
        return I;
    }

    public final OkHttpClient http;
    public final Gson gson;
    public final Retrofit retrofit;
    public final AppDb db;

    private static final String BASE = "https://stavels.ru/";

    private ServiceLocator(Context app) {
        http = new OkHttpClient.Builder().build();
        gson = new GsonBuilder().create();
        retrofit = new Retrofit.Builder()
                .baseUrl(BASE)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
        db = Room.databaseBuilder(app, AppDb.class, "app.db")
                .fallbackToDestructiveMigration()
                .build();
    }
}
