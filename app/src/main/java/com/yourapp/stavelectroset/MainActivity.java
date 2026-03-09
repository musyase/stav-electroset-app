package com.yourapp.stavelectroset;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import androidx.core.content.ContextCompat;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.consumers.ConsumersMapActivity;
import com.yourapp.stavelectroset.feature.news.domain.NewsRepository;
import com.yourapp.stavelectroset.feature.news.ui.NewsAdapter;

/**
 * Главный экран: новости.
 * Нижняя навигация: Новости / Карта / Профиль.
 *
 * Важно:
 * - НЕ логируем Supabase ключи.
 * - При переходе в профиль: если не залогинен -> LoginActivity (иначе ProfileActivity).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String KEY_RV_STATE = "rv_state";

    private SwipeRefreshLayout srl;
    private RecyclerView rv;
    private View progress, emptyView;
    private MaterialButton retryButton;

    private NewsAdapter adapter;

    private NewsRepository newsRepo;
    private AuthRepository authRepo;

    private Parcelable savedRvState;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) setSupportActionBar(toolbar);

        // DI
        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        newsRepo = sl.newsRepository();
        authRepo = sl.authRepository();

        // UI
        srl = findViewById(R.id.swipe);
        rv = findViewById(R.id.recycler);
        progress = findViewById(R.id.progress);
        emptyView = findViewById(R.id.emptyView);
        retryButton = findViewById(R.id.stateRetry);

        setupRecycler();
        setupObservers();
        setupRefresh();
        setupBottomNav();

        showLoading(true);
        doRefresh();
    }

    private void setupRecycler() {
        adapter = new NewsAdapter();
        rv.setAdapter(adapter);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setHasFixedSize(true);
    }

    private void setupObservers() {
        if (newsRepo == null) {
            showLoading(false);
            showEmpty(true);
            return;
        }

        newsRepo.observeAll().observe(this, list -> {
            adapter.submitList(list);

            if (srl != null && srl.isRefreshing()) srl.setRefreshing(false);
            showLoading(false);

            boolean isEmpty = (list == null || list.isEmpty());
            showEmpty(isEmpty);

            if (savedRvState != null && rv.getLayoutManager() != null) {
                rv.getLayoutManager().onRestoreInstanceState(savedRvState);
                savedRvState = null;
            }
        });
    }

    private void setupRefresh() {
        if (srl != null) {
            srl.setColorSchemeColors(
                    ContextCompat.getColor(this, R.color.app_primary),
                    ContextCompat.getColor(this, R.color.app_secondary)
            );
            srl.setProgressBackgroundColorSchemeColor(
                    ContextCompat.getColor(this, R.color.app_surface)
            );
            srl.setOnRefreshListener(this::doRefresh);
        }

        if (emptyView != null) emptyView.setOnClickListener(v -> doRefresh());
        if (retryButton != null) retryButton.setOnClickListener(v -> doRefresh());
    }

    private void setupBottomNav() {
        BottomNavigationView bottom = findViewById(R.id.bottomNav);
        if (bottom == null) return;

        bottom.setSelectedItemId(R.id.nav_news);

        bottom.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_news) {
                return true;
            }

            if (id == R.id.nav_map) {
                startActivity(new Intent(this, ConsumersMapActivity.class));
                // false — чтобы выбранный пункт остался "Новости" (мы остаёмся на этом экране)
                return false;
            }

            if (id == R.id.nav_profile) {
                openProfileOrLogin();
                return false;
            }

            return false;
        });
    }

    private void openProfileOrLogin() {
        try {
            boolean loggedIn = authRepo != null && authRepo.isLoggedIn();
            Intent i;

            if (loggedIn) {
                i = new Intent(this,
                        com.yourapp.stavelectroset.feature.news.auth.data.local.ui.ProfileActivity.class);
            } else {
                i = new Intent(this,
                        com.yourapp.stavelectroset.feature.news.auth.data.local.ui.LoginActivity.class);
            }

            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "openProfileOrLogin failed", e);
            // в сомнительном состоянии лучше отправить на логин
            startActivity(new Intent(this,
                    com.yourapp.stavelectroset.feature.news.auth.data.local.ui.LoginActivity.class));
        }
    }

    private void doRefresh() {
        if (newsRepo == null) {
            if (srl != null) srl.setRefreshing(false);
            showLoading(false);
            showEmpty(true);
            return;
        }

        if (srl != null && !srl.isRefreshing()) srl.setRefreshing(true);
        showLoading(true);

        newsRepo.refresh(() -> {
            if (srl != null) srl.setRefreshing(false);
            showLoading(false);
        });
    }

    private void showLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showEmpty(boolean empty) {
        if (emptyView != null) emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (rv != null && rv.getLayoutManager() != null) {
            outState.putParcelable(KEY_RV_STATE, rv.getLayoutManager().onSaveInstanceState());
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        savedRvState = savedInstanceState.getParcelable(KEY_RV_STATE);
    }

}
