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
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
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
import com.yourapp.stavelectroset.BuildConfig;
import com.yourapp.stavelectroset.MainActivity;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.auth.data.local.ui.LoginActivity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.List;

public class ConsumersMapActivity extends AppCompatActivity {

    private static final String TAG = "ConsumersMapActivity";

    private static boolean MAPKIT_INITIALIZED = false;

    private static final Point HQ_PT = new Point(45.017308, 41.938622);
    private static final String HQ_TITLE = "АО «Ставэлектросеть»";
    private static final String HQ_ADDR = "Ставропольский край, г. Ставрополь, ул. Шпаковская, д. 76/6";
    private static final String HQ_PHONE = "+7 (8652) 74-88-01";
    private static final String HQ_SITE = "https://stavels.ru";

    private MapView mapView;
    private Map yandexMap;
    private MapObjectCollection mapObjects;

    private final List<Branch> branches = new ArrayList<>();
    @Nullable
    private Branch currentBranch;
    private MapObjectTapListener branchTapListener;

    private FusedLocationProviderClient fused;
    @Nullable
    private Point myPoint;
    @Nullable
    private UserLocationLayer userLocationLayer;

    private BottomSheetBehavior<View> sheetBehavior;
    private View branchSheet;
    private TextView sheetTitle;
    private TextView sheetAddress;
    private TextView sheetDesc;

    private ActivityResultLauncher<String[]> locationPermLauncher;

    private AuthRepository authRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ensureMapKitInitialized();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consumers_map);

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();

        setupToolbar();
        setupMap();
        initBranches();
        setupBottomSheet();
        setupSheetButtons();
        setupFabs();
        initBottomNav();
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
        if (mapView != null) mapView.onStart();
    }

    @Override
    protected void onStop() {
        if (mapView != null) mapView.onStop();
        MapKitFactory.getInstance().onStop();
        super.onStop();
    }

    private void ensureMapKitInitialized() {
        if (MAPKIT_INITIALIZED) return;

        try {
            String apiKey = getString(R.string.yandex_mapkit_api_key);

            if (!TextUtils.isEmpty(apiKey)) {
                MapKitFactory.setApiKey(apiKey);
            }
            MapKitFactory.initialize(getApplicationContext());
            MAPKIT_INITIALIZED = true;

        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "MapKit init failed: " + e.getMessage());
            }
        }
    }

    private void setupToolbar() {
        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            setSupportActionBar(tb);
            setTitle("Карта филиалов");
        }
    }

    private void initBottomNav() {
        bottomNav = findViewById(R.id.bottomNav);
        if (bottomNav == null) return;

        setBottomNavVisible(true, false);

        bottomNav.setSelectedItemId(R.id.nav_map);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_news) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
                return true;
            }

            if (id == R.id.nav_map) {
                return true;
            }

            if (id == R.id.nav_profile) {
                openProfileOrLogin();
                return true;
            }

            return false;
        });

        bottomNav.setOnItemReselectedListener(item -> {
            // top-level экран, ничего не делаем
        });
    }

    private void openProfileOrLogin() {
        try {
            boolean loggedIn = authRepository != null && authRepository.isLoggedIn();
            Intent i = loggedIn
                    ? new Intent(this, ProfileActivity.class)
                    : new Intent(this, LoginActivity.class);

            startActivity(i);
            finish();
        } catch (Exception e) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        }
    }

    private void setupMap() {
        mapView = findViewById(R.id.mapView);
        if (mapView == null) {
            toast("Ошибка: mapView не найден в layout");
            finish();
            return;
        }

        yandexMap = mapView.getMapWindow().getMap();
        mapObjects = yandexMap.getMapObjects();

        fused = LocationServices.getFusedLocationProviderClient(this);
    }

    private void setupBottomSheet() {
        branchSheet = findViewById(R.id.branchSheet);
        if (branchSheet == null) {
            toast("Ошибка: branchSheet не найден в layout");
            return;
        }

        sheetBehavior = BottomSheetBehavior.from(branchSheet);
        sheetTitle = findViewById(R.id.sheetTitle);
        sheetAddress = findViewById(R.id.sheetAddress);
        sheetDesc = findViewById(R.id.sheetDesc);

        sheetBehavior.setHideable(true);
        sheetBehavior.setSkipCollapsed(true);
        sheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        // Когда sheet скрыт — держим нижний отступ под bottom nav
        updateBranchSheetBottomMargin(true);


        sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    updateBranchSheetBottomMargin(true);
                    setBottomNavVisible(true, true);
                    return;
                }

                if (newState == BottomSheetBehavior.STATE_EXPANDED
                        || newState == BottomSheetBehavior.STATE_DRAGGING
                        || newState == BottomSheetBehavior.STATE_SETTLING) {
                    updateBranchSheetBottomMargin(false);
                    setBottomNavVisible(false, true);
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // здесь специально ничего не делаем,
                // чтобы не дергать анимацию на каждом пикселе
            }
        });
    }

    private void setupSheetButtons() {
        MaterialButton btnRoute = findViewById(R.id.btnRoute);
        MaterialButton btnCall = findViewById(R.id.btnCall);
        MaterialButton btnSite = findViewById(R.id.btnSite);

        if (btnRoute != null) {
            btnRoute.setOnClickListener(v -> {
                Branch target = currentBranch != null
                        ? currentBranch
                        : (!branches.isEmpty() ? branches.get(0) : null);

                if (target != null) {
                    openExternalRoute(target);
                }
            });
        }

        if (btnCall != null) {
            btnCall.setOnClickListener(v -> {
                Branch target = currentBranch != null
                        ? currentBranch
                        : (!branches.isEmpty() ? branches.get(0) : null);

                if (target == null || TextUtils.isEmpty(target.phone)) return;

                Uri telUri = Uri.parse("tel:" + target.phone);
                startActivity(new Intent(Intent.ACTION_DIAL, telUri));
            });
        }

        if (btnSite != null) {
            btnSite.setOnClickListener(v -> {
                Branch target = currentBranch != null
                        ? currentBranch
                        : (!branches.isEmpty() ? branches.get(0) : null);

                if (target == null || TextUtils.isEmpty(target.site)) return;

                Uri uri = Uri.parse(target.site);
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            });
        }
    }

    private void setupFabs() {
        View fabMy = findViewById(R.id.fabMyLocation);
        View fabIn = findViewById(R.id.fabZoomIn);
        View fabOut = findViewById(R.id.fabZoomOut);

        if (fabMy != null) fabMy.setOnClickListener(v -> moveToMyLocation());
        if (fabIn != null) fabIn.setOnClickListener(v -> zoomBy(+1f));
        if (fabOut != null) fabOut.setOnClickListener(v -> zoomBy(-1f));
    }

    private void setupMapTapToHideSheet() {
        if (yandexMap == null || sheetBehavior == null) return;

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

    private void initBranches() {
        branches.clear();

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
                "Ставропольский край, Минеральные Воды, Ставропольская улица, 37, офис 2",
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
        if (mapObjects == null) return;

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

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Marker tapped: " + branch.title);
                    }

                    showSheetForBranch(branch);
                    return true;
                }
                return false;
            };
        }
        return branchTapListener;
    }

    private void showSheetForBranch(@NonNull Branch branch) {
        currentBranch = branch;

        if (sheetTitle != null) sheetTitle.setText(branch.title);
        if (sheetAddress != null) sheetAddress.setText(branch.address);

        if (sheetDesc != null) {
            if (TextUtils.isEmpty(branch.description)) {
                sheetDesc.setVisibility(View.GONE);
            } else {
                sheetDesc.setText(branch.description);
                sheetDesc.setVisibility(View.VISIBLE);
            }
        }

        // Сначала освобождаем место снизу и уводим nav,
        // потом открываем sheet
        updateBranchSheetBottomMargin(false);
        setBottomNavVisible(false, true);

        if (sheetBehavior != null) {
            sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    private void moveCameraToAllBranches() {
        if (branches.isEmpty() || yandexMap == null) return;

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

        CameraPosition pos = yandexMap.cameraPosition(Geometry.fromBoundingBox(bbox));
        yandexMap.move(
                pos,
                new Animation(Animation.Type.SMOOTH, 0.6f),
                null
        );
    }

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
        if (mapView == null) return;

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

        if (fused == null) return;

        fused.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null) {
                updateMyLocation(loc);
            } else {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener(this::updateMyLocation);
            }
        });
    }

    private void updateMyLocation(@Nullable Location loc) {
        if (loc == null || yandexMap == null) return;

        myPoint = new Point(loc.getLatitude(), loc.getLongitude());
        yandexMap.move(
                new CameraPosition(myPoint, 15f, 0f, 0f),
                new Animation(Animation.Type.SMOOTH, 0.5f),
                null
        );
    }

    private void openExternalRoute(@NonNull Branch branch) {
        double lat = branch.point.getLatitude();
        double lon = branch.point.getLongitude();

        if (tryOpenUri(Uri.parse("yandexmaps://build_route_on_map?lat_to=" + lat + "&lon_to=" + lon))) return;
        if (tryOpenUri(Uri.parse("yandexnavi://build_route_on_map?lat_to=" + lat + "&lon_to=" + lon))) return;

        Uri gmmUri = Uri.parse("google.navigation:q=" + lat + "," + lon + "&mode=d");
        Intent gmmIntent = new Intent(Intent.ACTION_VIEW, gmmUri);
        gmmIntent.setPackage("com.google.android.apps.maps");
        if (gmmIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(gmmIntent);
            return;
        }

        Uri webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + lat + "," + lon);
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

    private void updateBranchSheetBottomMargin(boolean reserveSpaceForBottomNav) {
        if (branchSheet == null) return;

        ViewGroup.LayoutParams rawLp = branchSheet.getLayoutParams();
        if (!(rawLp instanceof ViewGroup.MarginLayoutParams)) return;

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;

        int targetMargin;
        if (reserveSpaceForBottomNav) {
            targetMargin =
                    getResources().getDimensionPixelSize(R.dimen.bottom_nav_height)
                            + getResources().getDimensionPixelSize(R.dimen.bottom_nav_bottom_margin)
                            + getResources().getDimensionPixelSize(R.dimen.space_12);
        } else {
            targetMargin = getResources().getDimensionPixelSize(R.dimen.space_12);
        }

        if (lp.bottomMargin != targetMargin) {
            lp.bottomMargin = targetMargin;
            branchSheet.setLayoutParams(lp);
        }
    }

    private void setBottomNavVisible(boolean visible, boolean animate) {
        if (bottomNav == null) return;

        bottomNav.animate().cancel();

        int hiddenTranslation =
                getResources().getDimensionPixelSize(R.dimen.bottom_nav_height)
                        + getResources().getDimensionPixelSize(R.dimen.bottom_nav_bottom_margin)
                        + getResources().getDimensionPixelSize(R.dimen.space_12);

        bottomNavVisible = visible;

        if (visible) {
            if (bottomNav.getVisibility() != View.VISIBLE) {
                bottomNav.setVisibility(View.VISIBLE);
            }

            bottomNav.setClickable(true);
            bottomNav.setEnabled(true);

            if (animate) {
                bottomNav.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(180L)
                        .start();
            } else {
                bottomNav.setAlpha(1f);
                bottomNav.setTranslationY(0f);
            }
            return;
        }

        bottomNav.setClickable(false);
        bottomNav.setEnabled(false);

        if (animate) {
            bottomNav.animate()
                    .alpha(0f)
                    .translationY(hiddenTranslation)
                    .setDuration(160L)
                    .withEndAction(() -> {
                        if (!bottomNavVisible && bottomNav != null) {
                            bottomNav.setVisibility(View.INVISIBLE);
                        }
                    })
                    .start();
        } else {
            bottomNav.setAlpha(0f);
            bottomNav.setTranslationY(hiddenTranslation);
            bottomNav.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (sheetBehavior != null
                && sheetBehavior.getState() != BottomSheetBehavior.STATE_HIDDEN) {
            sheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            return;
        }
        super.onBackPressed();
    }
    private BottomNavigationView bottomNav;
    private boolean bottomNavVisible = true;
    private void zoomBy(float delta) {
        if (yandexMap == null) return;

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
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "vectorToImageProvider failed: " + e.getMessage());
            }
            return null;
        }
    }

    private void toast(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

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