package com.yourapp.stavelectroset.feature.news.consumers;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.MapKitFactory;
import com.yandex.mapkit.geometry.Geometry;
import com.yandex.mapkit.geometry.LinearRing;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polygon;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.Cluster;
import com.yandex.mapkit.map.ClusterizedPlacemarkCollection;
import com.yandex.mapkit.map.Map;                    // ВАЖНО: именно Map из MapKit
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.mapview.MapView;
import com.yandex.mapkit.search.Response;
import com.yandex.mapkit.search.SearchFactory;
import com.yandex.mapkit.search.SearchManager;
import com.yandex.mapkit.search.SearchManagerType;
import com.yandex.mapkit.search.SearchOptions;
import com.yandex.mapkit.search.SearchType;
import com.yandex.mapkit.search.Session;
import com.yandex.mapkit.user_location.UserLocationLayer;
import com.yandex.runtime.Error;
import com.yandex.runtime.image.ImageProvider;
import com.yandex.mapkit.GeoObject;
import com.yandex.mapkit.GeoObjectCollection;


import com.yourapp.stavelectroset.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ConsumersMapFragment extends Fragment {

    // --- UI
    private MapView mapView;
    private Map yandexMap;
    private BottomSheetBehavior<View> sheetBehavior;
    private TextView sheetTitle, sheetAddress, sheetDesc;
    private MaterialButton btnRoute, btnCall, btnSite;

    // --- Map objects
    private MapObjectCollection root;
    private ClusterizedPlacemarkCollection cluster;

    // --- Search & location
    private SearchManager searchManager;
    private FusedLocationProviderClient fused;
    private Point myPoint;

    // --- Данные филиалов (пока пример + HQ)
    private final List<Branch> branches = new ArrayList<>(Arrays.asList(
            new Branch(
                    "hq",
                    "АО «Ставэлектросеть» (Головной офис)",
                    "Ставрополь, проспект Карла Маркса, 1",
                    new Point(45.0428, 41.9734),
                    "+7 (8652) 00-00-00",
                    "https://example.ru",
                    "Время работы: пн–пт 9:00–18:00"
            )
    ));

    private Branch selectedBranch;
    // ===== Permissions + UserLocationLayer =====

    @Nullable
    private UserLocationLayer userLocationLayer;

    private final ActivityResultLauncher<String[]> locPermsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean fine = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarse = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));

                if (fine || coarse) {
                    enableUserLocationLayer();
                } else {
                    if (getView() != null) {
                        Snackbar.make(getView(), "Геолокация не разрешена — работаем без неё", Snackbar.LENGTH_SHORT).show();
                    }
                }
            });

    private void requestLocationPermissionsIfNeeded() {
        boolean fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (fine || coarse) {
            enableUserLocationLayer();
        } else {
            locPermsLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private void enableUserLocationLayer() {
        if (!isAdded() || mapView == null) return;

        if (userLocationLayer == null) {
            userLocationLayer = MapKitFactory.getInstance().createUserLocationLayer(mapView.getMapWindow());
            // В MapKit это называется setHeadingModeActive, а не setHeadingEnabled :contentReference[oaicite:1]{index=1}
            userLocationLayer.setHeadingModeActive(true);
        }
        userLocationLayer.setVisible(true);
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_consumers_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        // Map
        mapView = v.findViewById(R.id.mapView);
        yandexMap = mapView.getMapWindow().getMap();
        root = yandexMap.getMapObjects();
        cluster = root.addClusterizedPlacemarkCollection(this::onClusterAdded);

        // Services
        searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED);
        fused = LocationServices.getFusedLocationProviderClient(requireContext());

        // Bottom sheet
        View sheet = v.findViewById(R.id.branchSheet);
        if (sheet != null) {
            sheetBehavior = BottomSheetBehavior.from(sheet);
            sheetTitle   = v.findViewById(R.id.sheetTitle);
            sheetAddress = v.findViewById(R.id.sheetAddress);
            sheetDesc    = v.findViewById(R.id.sheetDesc);
            btnRoute     = v.findViewById(R.id.btnRoute);
            btnCall      = v.findViewById(R.id.btnCall);
            btnSite      = v.findViewById(R.id.btnSite);

            btnRoute.setOnClickListener(x -> {
                if (selectedBranch != null && selectedBranch.point != null) {
                    if (myPoint != null) openRouteInYandexApp(myPoint, selectedBranch.point);
                    else openPlaceInYandexApp(selectedBranch.point);
                }
            });
            btnCall.setOnClickListener(x -> {
                if (selectedBranch != null && !TextUtils.isEmpty(selectedBranch.phone)) {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + selectedBranch.phone)));
                }
            });
            btnSite.setOnClickListener(x -> {
                if (selectedBranch != null && !TextUtils.isEmpty(selectedBranch.site)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(selectedBranch.site)));
                }
            });
        } else {
            // листа нет — просто работаем без него
            sheetBehavior = null;
        }

        // FABs
        v.findViewById(R.id.fabMyLocation).setOnClickListener(x -> moveToMyLocation());
        v.findViewById(R.id.fabZoomIn).setOnClickListener(x -> zoomBy(+1f));
        v.findViewById(R.id.fabZoomOut).setOnClickListener(x -> zoomBy(-1f));

        // Камера стартом на HQ
        yandexMap.move(
                new CameraPosition(new Point(45.0428, 41.9734), 14.5f, 0f, 0f),
                new Animation(Animation.Type.SMOOTH, 0.6f),
                null
        );

        // Метки
        renderBranches();

        // Разрешения/слой «Я»
        requestLocationPermissionsIfNeeded();
    }

    // ---- Метки + кластеризация
    private void renderBranches() {
        cluster.clear();
        ImageProvider pin = ImageProvider.fromResource(requireContext(), R.drawable.ic_marker_alert);

        for (Branch b : branches) {
            if (b.point != null) {
                addPlacemarkForBranch(b, pin);
            } else if (!TextUtils.isEmpty(b.address)) {
                geocodeAndAdd(b, pin);
            }
        }
        cluster.clusterPlacemarks(60, 15);
    }

    private void addPlacemarkForBranch(Branch b, ImageProvider pin) {
        PlacemarkMapObject pm = cluster.addPlacemark(b.point, pin);
        pm.setUserData(b);
        pm.addTapListener((mapObject, point) -> {
            showBranchSheet(b);
            return true;
        });
    }

    private void geocodeAndAdd(Branch b, ImageProvider pin) {
        List<Point> ring = Arrays.asList(
                new Point( 85, -179), new Point( 85, 179),
                new Point(-85,  179), new Point(-85,-179), new Point(85,-179)
        );
        Geometry world = Geometry.fromPolygon(new Polygon(new LinearRing(ring), new ArrayList<>()));

        SearchOptions opt = new SearchOptions();
        opt.setSearchTypes(SearchType.GEO.value);

        searchManager.submit(b.address, world, opt, new Session.SearchListener() {
            @Override public void onSearchResponse(@NonNull Response response) {
                Point pt = null;

                if (response.getCollection() != null && !response.getCollection().getChildren().isEmpty()) {
                    GeoObjectCollection.Item item = response.getCollection().getChildren().get(0);
                    GeoObject obj = item.getObj();

                    if (obj != null && !obj.getGeometry().isEmpty()) {
                        // Берём первую геометрию, в которой реально есть Point
                        for (com.yandex.mapkit.geometry.Geometry g : obj.getGeometry()) {
                            if (g.getPoint() != null) { pt = g.getPoint(); break; }
                        }
                    }
                }

                if (pt != null) {
                    b.point = pt;
                    addPlacemarkForBranch(b, pin);
                    cluster.clusterPlacemarks(60, 15);
                }
            }
            @Override public void onSearchError(@NonNull Error error) { /* no-op */ }
        });
    }

    private void showBranchSheet(Branch b) {
        selectedBranch = b;

        if (sheetBehavior != null) {
            sheetTitle.setText(b.title);
            sheetAddress.setText(!TextUtils.isEmpty(b.address)
                    ? b.address
                    : (b.point != null
                    ? String.format(Locale.US, "%.6f, %.6f", b.point.getLatitude(), b.point.getLongitude())
                    : "—"));
            if (!TextUtils.isEmpty(b.description)) {
                sheetDesc.setText(b.description);
                sheetDesc.setVisibility(View.VISIBLE);
            } else {
                sheetDesc.setVisibility(View.GONE);
            }
            sheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        } else {
            // Фолбэк — диалог
            MaterialAlertDialogBuilder dlg = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(b.title)
                    .setMessage(
                            (!TextUtils.isEmpty(b.address) ? b.address : "") +
                                    (!TextUtils.isEmpty(b.description) ? ("\n\n" + b.description) : "")
                    )
                    .setNegativeButton("Закрыть", null);

            if (b.point != null) {
                dlg.setPositiveButton("Маршрут", (d, w) -> {
                    if (myPoint != null) openRouteInYandexApp(myPoint, b.point);
                    else openPlaceInYandexApp(b.point);
                });
            }
            if (!TextUtils.isEmpty(b.phone)) {
                dlg.setNeutralButton("Звонок", (d, w) -> {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + b.phone)));
                });
            }
            dlg.show();
        }
    }


    private void onClusterAdded(@NonNull Cluster clusterObj) {
        clusterObj.addClusterTapListener(c -> {
            CameraPosition cp = yandexMap.getCameraPosition();
            yandexMap.move(
                    new CameraPosition(c.getAppearance().getGeometry(), Math.min(cp.getZoom() + 1.5f, 19f), 0f, 0f),
                    new Animation(Animation.Type.SMOOTH, 0.3f),
                    null
            );
            return true;
        });
    }

    // ---- Зум
    private void zoomBy(float delta) {
        CameraPosition cp = yandexMap.getCameraPosition();
        float zoom = Math.max(3f, Math.min(19f, cp.getZoom() + delta));
        yandexMap.move(new CameraPosition(cp.getTarget(), zoom, cp.getAzimuth(), cp.getTilt()),
                new Animation(Animation.Type.SMOOTH, 0.25f), null);
    }

    // ---- Разрешения и «моё местоположение»
//    private void requestLocationPermissionsIfNeeded() {
//        boolean fine   = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED;
//        boolean coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
//        if (!fine && !coarse) {
//            locPermsLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
//        } else {
//            enableUserLocationLayer();
//        }
//    }
//
//    private void enableUserLocationLayer() {
//        UserLocationLayer layer = MapKitFactory.getInstance().createUserLocationLayer(mapView.getMapWindow());
//        layer.setVisible(true);
//    }

    private void moveToMyLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        fused.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null) updateMyLocation(loc);
            else fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(this::updateMyLocation);
        });
    }

    private void updateMyLocation(@NonNull Location loc) {
        myPoint = new Point(loc.getLatitude(), loc.getLongitude());
        yandexMap.move(new CameraPosition(myPoint, 15f, 0f, 0f),
                new Animation(Animation.Type.SMOOTH, 0.7f), null);
    }

    // ---- Открытие Яндекс.Карт
    private void openRouteInYandexApp(Point from, Point to) {
        String rtext = from.getLatitude() + "," + from.getLongitude()
                + "~" + to.getLatitude() + "," + to.getLongitude();
        Uri uriApp = Uri.parse("yandexmaps://maps.yandex.ru/?rtext=" + rtext + "&rtt=auto");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uriApp));
            return;
        } catch (ActivityNotFoundException ignore) { }
        Uri uriWeb = Uri.parse("https://yandex.ru/maps/?rtext=" + rtext + "&rtt=auto");
        startActivity(new Intent(Intent.ACTION_VIEW, uriWeb));
    }

    private void openPlaceInYandexApp(Point to) {
        String ll = to.getLongitude() + "," + to.getLatitude();
        Uri uriApp = Uri.parse("yandexmaps://maps.yandex.ru/?ll=" + ll + "&z=16");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uriApp));
            return;
        } catch (ActivityNotFoundException ignore) { }
        Uri uriWeb = Uri.parse("https://yandex.ru/maps/?ll=" + ll + "&z=16");
        startActivity(new Intent(Intent.ACTION_VIEW, uriWeb));
    }

    @Override public void onStart() {
        super.onStart();
        MapKitFactory.getInstance().onStart();
        mapView.onStart();
    }

    @Override public void onStop() {
        mapView.onStop();
        MapKitFactory.getInstance().onStop();
        super.onStop();
    }

    // ---- модель филиала (если у тебя уже есть отдельный файл Branch.java — удали этот внутренний класс)
    public static class Branch {
        public final String id;
        public final String title;
        public final String address;
        @Nullable public Point point;
        @Nullable public String phone;
        @Nullable public String site;
        @Nullable public String description;

        public Branch(String id, String title, String address, @Nullable Point point,
                      @Nullable String phone, @Nullable String site, @Nullable String description) {
            this.id = id;
            this.title = title;
            this.address = address == null ? "" : address;
            this.point = point;
            this.phone = phone;
            this.site = site;
            this.description = description;
        }
    }
}
