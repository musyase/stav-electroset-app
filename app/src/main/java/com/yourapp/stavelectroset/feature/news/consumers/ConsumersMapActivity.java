package com.yourapp.stavelectroset.feature.news.consumers;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.geometry.BoundingBox;
import com.yandex.mapkit.geometry.Geometry;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.InputListener;
import com.yandex.mapkit.map.Map;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.MapObjectTapListener;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.mapkit.user_location.UserLocationLayer;
import com.yandex.runtime.image.ImageProvider;

import com.yourapp.stavelectroset.R;

import java.util.ArrayList;
import java.util.List;

public class ConsumersMapActivity extends AppCompatActivity {

    private static final String TAG = "ConsumersMapActivity";

    // ---- ДАННЫЕ ГЛАВНОГО ФИЛИАЛА ----
    private static final Point HQ_PT    = new Point(45.017308, 41.938622);
    private static final String HQ_TITLE = "АО «Ставэлектросеть»";
    private static final String HQ_ADDR  = "Ставропольский край, г. Ставрополь, ул. Шпаковская, д. 76/6";
    private static final String HQ_PHONE = "+7 (8652) 74-88-01";
    private static final String HQ_SITE  = "https://stavels.ru";

    // Карта
    private MapView mapView;
    private Map yandexMap;
    private MapObjectCollection mapObjects;

    // Филиалы и маркеры
    private final List<Branch> branches = new ArrayList<>();
    @Nullable
    private Branch currentBranch;
    private MapObjectTapListener branchTapListener;

    // Геолокация
    private FusedLocationProviderClient fused;
    @Nullable
    private Point myPoint;
    @Nullable
    private UserLocationLayer userLocationLayer;

    // BottomSheet
    private BottomSheetBehavior<View> sheetBehavior;
    private View branchSheet;
    private TextView sheetTitle;
    private TextView sheetAddress;
    private TextView sheetDesc;

    // Разрешения
    private ActivityResultLauncher<String[]> locationPermLauncher;

    // -------------------- ЖИЗНЕННЫЙ ЦИКЛ --------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consumers_map);

        setupToolbar();
        setupMap();
        initBranches();
        setupBottomSheet();
        setupSheetButtons();
        setupFabs();
        initLocationPermissionLauncher();
        requestLocationPermissionsIfNeeded();
        addBranchMarkers();
        moveCameraToAllBranches();
        setupMapTapToHideSheet();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MapKitFactory.getInstance().onStart();
        mapView.onStart();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        MapKitFactory.getInstance().onStop();
        super.onStop();
    }

    // -------------------- ИНИЦИАЛИЗАЦИЯ UI / КАРТЫ --------------------

    private void setupToolbar() {
        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setNavigationOnClickListener(v -> finish());
        }
    }

    private void setupMap() {
        mapView   = findViewById(R.id.mapView);
        yandexMap = mapView.getMapWindow().getMap();
        mapObjects = yandexMap.getMapObjects();

        fused = LocationServices.getFusedLocationProviderClient(this);
    }

    private void setupBottomSheet() {
        branchSheet = findViewById(R.id.branchSheet);
        sheetBehavior = BottomSheetBehavior.from(branchSheet);
        sheetTitle   = findViewById(R.id.sheetTitle);
        sheetAddress = findViewById(R.id.sheetAddress);
        sheetDesc    = findViewById(R.id.sheetDesc);

        sheetBehavior.setHideable(true);
        sheetBehavior.setSkipCollapsed(true); // только HIDDEN и EXPANDED
        sheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
    }

    private void setupSheetButtons() {
        MaterialButton btnRoute = findViewById(R.id.btnRoute);
        MaterialButton btnCall  = findViewById(R.id.btnCall);
        MaterialButton btnSite  = findViewById(R.id.btnSite);

        // "Маршрут" — во внешние карты
        btnRoute.setOnClickListener(v -> {
            Branch target = currentBranch != null
                    ? currentBranch
                    : (!branches.isEmpty() ? branches.get(0) : null);

            if (target != null) {
                openExternalRoute(target);
            }
        });

        // "Звонок"
        btnCall.setOnClickListener(v -> {
            Branch target = currentBranch != null
                    ? currentBranch
                    : (!branches.isEmpty() ? branches.get(0) : null);

            if (target == null || TextUtils.isEmpty(target.phone)) return;

            Uri telUri = Uri.parse("tel:" + target.phone);
            startActivity(new Intent(Intent.ACTION_DIAL, telUri));
        });

        // "Сайт"
        btnSite.setOnClickListener(v -> {
            Branch target = currentBranch != null
                    ? currentBranch
                    : (!branches.isEmpty() ? branches.get(0) : null);

            if (target == null || TextUtils.isEmpty(target.site)) return;

            Uri uri = Uri.parse(target.site);
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        });
    }

    private void setupFabs() {
        findViewById(R.id.fabMyLocation).setOnClickListener(v -> moveToMyLocation());
        findViewById(R.id.fabZoomIn).setOnClickListener(v -> zoomBy(+1f));
        findViewById(R.id.fabZoomOut).setOnClickListener(v -> zoomBy(-1f));
    }

    private void setupMapTapToHideSheet() {
        yandexMap.addInputListener(new InputListener() {
            @Override
            public void onMapTap(@NonNull Map map, @NonNull Point point) {
                if (sheetBehavior.getState() != BottomSheetBehavior.STATE_HIDDEN) {
                    sheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            }

            @Override
            public void onMapLongTap(@NonNull Map map, @NonNull Point point) {
                // не используем
            }
        });
    }

    // -------------------- ФИЛИАЛЫ И МЕТКИ --------------------

    private void initBranches() {
        branches.clear();

        // Главный офис
        branches.add(new Branch(
                HQ_TITLE,
                HQ_ADDR,
                HQ_PHONE,
                HQ_SITE,
                null,
                HQ_PT
        ));

        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Минеральные Воды",
                "тавропольский край, Минеральные Воды, Ставропольская улица, 37, офис 2",
                "+7 (87922) 5-71-86",
                "https://stavels.ru",
                null,
                new Point(44.207035, 43.120580)
        ));

        branches.add(new Branch(
                "Участок с.Александровское филиала АО «Ставэлектросеть» г.Минеральные Воды",
                "Ставропольский край, село Александровское, Пятигорская улица, 30",
                "+7 (86557) 2-78-70",
                "https://stavels.ru",
                null,
                new Point(44.699051, 43.000565)
        ));
        branches.add(new Branch(
                "Участок ст.Ессентукская филиала АО «Ставэлектросеть» г.Минеральные Воды",
                "Ставропольский край, Предгорный муниципальный округ, станица Ессентукская, Западная улица, 47",
                "+7 (87961) 5-39-44",
                "https://stavels.ru",
                null,
                new Point(44.019127, 42.842309)
        ));
        branches.add(new Branch(
                "Участок с.Курсавка филиала АО «Ставэлектросеть» г. Минеральные Воды",
                "Ставропольский край, Андроповский муниципальный округ, село Курсавка, улица Строителей, 34А",
                "+7 (86556) 6-13-55",
                "https://stavels.ru",
                null,
                new Point(44.463708, 42.514891)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Ипатово",
                "Ставропольский край, Ипатово, улица Чапаева, 36, офис 2",
                "+7 (86542) 5-60-42",
                "https://stavels.ru",
                null,
                new Point(45.708357, 42.893962)
        ));
        branches.add(new Branch(
                "Участок с.Дивное филиала АО «Ставэлектросеть» г. Ипатово",
                "Ставропольский край, Апанасенковский муниципальный округ, село Дивное, Шоссейная улица, 17",
                "+7 (86555) 4-54-24",
                "https://stavels.ru",
                null,
                new Point(45.905049, 43.362110)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Михайловск",
                "Почтовая улица, 1А, Михайловск, Шпаковский муниципальный округ, Ставропольский край",
                "+7 (86553) 6-60-71",
                "https://stavels.ru",
                null,
                new Point(45.123974, 42.028427)
        ));
        branches.add(new Branch(
                "Участок с. Кочубеевское филиала АО «Ставэлектросеть»  г.Михайловск",
                "Ставропольский край, село Кочубеевское, улица Скрипникова, 25,, офис 2",
                "+7 (86550) 2-02-18",
                "https://stavels.ru",
                null,
                new Point(44.692868, 41.826907)
        ));
        branches.add(new Branch(
                "Участок с. Грачевка филиала АО «Ставэлектросеть» г. Михайловск",
                "Ставропольский край, село Грачёвка, Ставропольская улица, 75А, офис 2",
                "+7 (86540) 4-13-68",
                "https://stavels.ru",
                null,
                new Point(45.200016, 42.396700)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть»  г.Нефтекумск",
                "Нефтекумск, Рабочая улица, 14 офис 2",
                "+7 (86558) 4-59-90",
                "https://stavels.ru",
                null,
                new Point(44.755079, 44.977245)
        ));
        branches.add(new Branch(
                "Участок с. Степное филиала АО «Ставэлектросеть» г. Нефтекумск",
                "Ставропольский край, село Степное, улица Мира, 90",
                "+7 (86563) 3-19-55",
                "https://stavels.ru",
                null,
                new Point(44.266388, 44.595811)
        ));
        branches.add(new Branch(
                "Участок с. Левокумское филиала АО «Ставэлектросеть» г.Нефтекумск",
                "Ставропольский край, село Левокумское, Шоссейная улица, 16",
                "+7 (86543) 3-15-84",
                "https://stavels.ru",
                null,
                new Point(44.833250, 44.654453)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Новоалександровск",
                "Ставропольский край, Новоалександровск, улица Ленина, 13 офис 2",
                "+7 (86544) 6-69-32",
                "https://stavels.ru",
                null,
                new Point(45.481867, 41.213618)
        ));
        branches.add(new Branch(
                "Участок с. Красногвардейское филиала АО «Ставэлектросеть» г.Новоалександровск",
                "Ставропольский край, село Красногвардейское, улица Лихачёва, 1/2, офис 2",
                "+7 (86541) 2-57-41",
                "https://stavels.ru",
                null,
                new Point(45.832813, 41.499399)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Изобильный",
                "Ставропольский край, Изобильный, улица Крупской, 2А",
                "+7 (86545) 2-76-01",
                "https://stavels.ru",
                null,
                new Point(45.379528, 41.727446)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Новопавловск",
                "Ставропольский край, Кировский муниципальный округ, Новопавловск, улица Правды, 10, офис 2",
                "+7 (87938) 5-10-42",
                "https://stavels.ru",
                null,
                new Point(43.972669, 43.621939)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Светлоград",
                "Ставропольский край, Петровский муниципальный округ, Светлоград, Светлая улица, 9А",
                "+7 (86547) 4-02-09",
                "https://stavels.ru",
                null,
                new Point(45.348435, 42.851409)
        ));
        branches.add(new Branch(
                "Участок г.Благодарный филиала АО «Ставэлектросеть» г.Светлоград",
                "Благодарный, Советская улица, 232",
                "+7 (86549) 5-13-86",
                "https://stavels.ru",
                null,
                new Point(45.096922, 43.421641)
        ));
        branches.add(new Branch(
                "Участок с. Летняя Ставка филиала АО «Ставэлектросеть» г.Светлоград",
                "Ставропольский край, Туркменский муниципальный округ, село Летняя Ставка, Почтовый переулок, 4А",
                "+7 (86565) 2-17-43",
                "https://stavels.ru",
                null,
                new Point(45.430265, 43.440524)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г.Зеленокумск",
                "Ставропольский край, Советский муниципальный округ, Зеленокумск, Короткий переулок, 20, офис 2",
                "+7 (86552) 6-05-34",
                "https://stavels.ru",
                null,
                new Point(44.418464, 43.891047)
        ));
        branches.add(new Branch(
                "Участок ст. Курская филиала АО «Ставэлектросеть» г. Зеленокумск",
                "Ставропольский край, станица Курская, Кольцевая улица, 42",
                "+7 (86564) 6-47-51",
                "https://stavels.ru",
                null,
                new Point(44.034138, 44.463031)
        ));
        branches.add(new Branch(
                "Филиал АО «Ставэлектросеть» г. Будённовск",
                "Ставропольский край, Будённовск, улица Полющенко, 141",
                "+7 (86559) 7-16-95",
                "https://stavels.ru",
                null,
                new Point(44.786812, 44.151828)
        ));
    }

    private void addBranchMarkers() {
        ImageProvider icon = vectorToImageProvider(R.drawable.ic_pin_purple, dp(36));

        for (Branch branch : branches) {
            PlacemarkMapObject placemark = mapObjects.addPlacemark(branch.point);
            if (icon != null) {
                placemark.setIcon(icon);
            }
            placemark.setZIndex(1000f);
            placemark.setOpacity(1f);
            placemark.setUserData(branch);
            placemark.addTapListener(getBranchTapListener());
        }
    }

    private MapObjectTapListener getBranchTapListener() {
        if (branchTapListener == null) {
            branchTapListener = (mapObject, point) -> {
                Object data = mapObject.getUserData();
                if (data instanceof Branch) {
                    Branch branch = (Branch) data;
                    Log.d(TAG, "Marker tapped: " + branch.title + " at "
                            + point.getLatitude() + "," + point.getLongitude());
                    showSheetForBranch(branch);
                    return true;
                }
                return false;
            };
        }
        return branchTapListener;
    }

    /** Показываем карточку для конкретного филиала. */
    private void showSheetForBranch(@NonNull Branch branch) {
        currentBranch = branch;

        sheetTitle.setText(branch.title);
        sheetAddress.setText(branch.address);

        if (TextUtils.isEmpty(branch.description)) {
            sheetDesc.setVisibility(View.GONE);
        } else {
            sheetDesc.setText(branch.description);
            sheetDesc.setVisibility(View.VISIBLE);
        }

        sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    /** Двигаем камеру так, чтобы были видны все филиалы (весь край). */
    private void moveCameraToAllBranches() {
        if (branches.isEmpty()) return;

        double minLat = 90.0, maxLat = -90.0;
        double minLon = 180.0, maxLon = -180.0;

        for (Branch branch : branches) {
            double lat = branch.point.getLatitude();
            double lon = branch.point.getLongitude();

            if (lat < minLat) minLat = lat;
            if (lat > maxLat) maxLat = lat;
            if (lon < minLon) minLon = lon;
            if (lon > maxLon) maxLon = lon;
        }

        if (minLat == maxLat && minLon == maxLon) {
            // один филиал — просто слегка приблизим к нему
            yandexMap.move(
                    new CameraPosition(branches.get(0).point, 12f, 0f, 0f),
                    new Animation(Animation.Type.SMOOTH, 0.6f),
                    null
            );
            return;
        }

        BoundingBox bbox = new BoundingBox(
                new Point(minLat, minLon),
                new Point(maxLat, maxLon)
        );

        // ВАЖНО: передаём Geometry, а не BoundingBox напрямую
        CameraPosition pos = yandexMap.cameraPosition(Geometry.fromBoundingBox(bbox));
        yandexMap.move(
                pos,
                new Animation(Animation.Type.SMOOTH, 0.6f),
                null
        );
    }

    // -------------------- ГЕОЛОКАЦИЯ --------------------

    private void initLocationPermissionLauncher() {
        locationPermLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.RequestMultiplePermissions(),
                        result -> {
                            boolean granted = false;
                            for (Boolean v : result.values()) {
                                if (Boolean.TRUE.equals(v)) {
                                    granted = true;
                                    break;
                                }
                            }
                            if (granted) {
                                enableUserLocationLayer();
                            }
                        }
                );
    }

    private void requestLocationPermissionsIfNeeded() {
        boolean fine = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;

        if (!fine && !coarse) {
            locationPermLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        } else {
            enableUserLocationLayer();
        }
    }

    private void enableUserLocationLayer() {
        if (userLocationLayer == null) {
            userLocationLayer = MapKitFactory.getInstance()
                    .createUserLocationLayer(mapView.getMapWindow());
        }
        userLocationLayer.setVisible(true);
    }

    private void moveToMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fused.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null) {
                updateMyLocation(loc);
            } else {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener(this::updateMyLocation);
            }
        });
    }

    private void updateMyLocation(@NonNull Location loc) {
        myPoint = new Point(loc.getLatitude(), loc.getLongitude());
        yandexMap.move(
                new CameraPosition(myPoint, 15f, 0f, 0f),
                new Animation(Animation.Type.SMOOTH, 0.5f),
                null
        );
    }

    // -------------------- ВНЕШНЯЯ НАВИГАЦИЯ --------------------

    /** Открыть маршрут до выбранного филиала во внешнем приложении/браузере. */
    private void openExternalRoute(@NonNull Branch branch) {
        double lat = branch.point.getLatitude();
        double lon = branch.point.getLongitude();

        // 1. Яндекс.Карты
        if (tryOpenUri(Uri.parse("yandexmaps://build_route_on_map?lat_to="
                + lat + "&lon_to=" + lon))) {
            return;
        }

        // 2. Яндекс.Навигатор
        if (tryOpenUri(Uri.parse("yandexnavi://build_route_on_map?lat_to="
                + lat + "&lon_to=" + lon))) {
            return;
        }

        // 3. Google Maps (если установлены)
        Uri gmmUri = Uri.parse("google.navigation:q=" + lat + "," + lon + "&mode=d");
        Intent gmmIntent = new Intent(Intent.ACTION_VIEW, gmmUri);
        gmmIntent.setPackage("com.google.android.apps.maps");
        if (gmmIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(gmmIntent);
            return;
        }

        // 4. Браузер — веб-версия Google Maps
        Uri webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query="
                + lat + "," + lon);
        startActivity(new Intent(Intent.ACTION_VIEW, webUri));
    }

    private boolean tryOpenUri(@NonNull Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
            return true;
        }
        return false;
    }

    // -------------------- УТИЛИТЫ --------------------

    private void zoomBy(float delta) {
        CameraPosition cp = yandexMap.getCameraPosition();
        float zoom = Math.max(3f, Math.min(19f, cp.getZoom() + delta));
        yandexMap.move(
                new CameraPosition(cp.getTarget(), zoom, cp.getAzimuth(), cp.getTilt()),
                new Animation(Animation.Type.SMOOTH, 0.25f),
                null
        );
    }

    private int dp(int d) {
        return Math.round(getResources().getDisplayMetrics().density * d);
    }

    @Nullable
    private ImageProvider vectorToImageProvider(int drawableId, int sizePx) {
        try {
            Drawable dr = AppCompatResources.getDrawable(this, drawableId);
            if (dr == null) return null;

            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            dr.setBounds(0, 0, sizePx, sizePx);
            dr.draw(c);
            return ImageProvider.fromBitmap(bmp);
        } catch (Exception e) {
            Log.w(TAG, "vectorToImageProvider failed", e);
            return null;
        }
    }

    // -------------------- МОДЕЛЬ ФИЛИАЛА --------------------

    private static class Branch {
        final String title;
        final String address;
        final String phone;
        final String site;
        @Nullable
        final String description;
        final Point point;

        Branch(String title,
               String address,
               String phone,
               String site,
               @Nullable String description,
               Point point) {
            this.title = title;
            this.address = address;
            this.phone = phone;
            this.site = site;
            this.description = description;
            this.point = point;
        }
    }
}
