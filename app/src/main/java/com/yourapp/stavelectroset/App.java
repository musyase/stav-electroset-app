package com.yourapp.stavelectroset;

import android.app.Application;
import com.yandex.mapkit.MapKitFactory;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // ВАЖНО: ставим ключ ДО любого MapView
        MapKitFactory.setApiKey(getString(R.string.yandex_mapkit_api_key));
    }
}