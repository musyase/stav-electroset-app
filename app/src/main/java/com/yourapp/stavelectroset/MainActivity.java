package com.yourapp.stavelectroset;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.consumers.ConsumersMapActivity;
import com.yourapp.stavelectroset.feature.news.domain.NewsRepository;
import com.yourapp.stavelectroset.feature.news.ui.NewsAdapter;
import com.yourapp.stavelectroset.feature.news.ui.UiSpaceItemDecoration;

public class MainActivity extends AppCompatActivity {

    private static final String KEY_RV_STATE = "rv_state";

    private SwipeRefreshLayout srl;
    private RecyclerView rv;
    private View progress, emptyView;
    private NewsAdapter adapter;
    private NewsRepository repo;

    private Parcelable savedRvState;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) setSupportActionBar(toolbar);

        // DI / Repo
        repo = ServiceLocator.get(this).newsRepository();

        // UI
        srl = findViewById(R.id.swipe);
        rv = findViewById(R.id.recycler);
        progress = findViewById(R.id.progress);
        emptyView = findViewById(R.id.emptyView);

        // Recycler
        adapter = new NewsAdapter();
        rv.setAdapter(adapter);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new UiSpaceItemDecoration(12));

        // Observe DB
        repo.observeAll().observe(this, list -> {
            adapter.submitList(list);
            if (srl != null && srl.isRefreshing()) srl.setRefreshing(false);
            if (progress != null) progress.setVisibility(View.GONE);
            if (emptyView != null) emptyView.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);

            if (savedRvState != null && rv.getLayoutManager() != null) {
                rv.getLayoutManager().onRestoreInstanceState(savedRvState);
                savedRvState = null;
            }
        });

        // Swipe-to-refresh
        if (srl != null) srl.setOnRefreshListener(this::doRefresh);
        if (emptyView != null) emptyView.setOnClickListener(v -> doRefresh());

        // BottomNav: новости (текущий), потребителям (карта в отдельной Activity), кабинет
        BottomNavigationView bottom = findViewById(R.id.bottomNav);
        if (bottom != null) {
            bottom.setSelectedItemId(R.id.nav_news);
            bottom.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_news) {
                    // уже здесь
                    return true;
                } else if (id == R.id.nav_map) {
                    startActivity(new Intent(this, ConsumersMapActivity.class));
                    // оставляем подсветку «Новости»
                    bottom.setSelectedItemId(R.id.nav_news);
                    return true;
                } else if (id == R.id.nav_profile) {
                    startActivity(new Intent(this, com.yourapp.stavelectroset.feature.news.auth.data.local.ui.ProfileActivity.class));
                    // можно не finish(), чтобы не терять позицию списка
                    bottom.setSelectedItemId(R.id.nav_news);
                    return true;
                }
                return false;
            });
        }

        if (progress != null) progress.setVisibility(View.VISIBLE);
        doRefresh();
    }

    private void doRefresh() {
        if (srl != null && !srl.isRefreshing()) srl.setRefreshing(true);
        if (progress != null) progress.setVisibility(View.VISIBLE);
        repo.refresh(() -> {
            if (srl != null) srl.setRefreshing(false);
            if (progress != null) progress.setVisibility(View.GONE);
        });
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (rv != null && rv.getLayoutManager() != null) {
            outState.putParcelable(KEY_RV_STATE, rv.getLayoutManager().onSaveInstanceState());
        }
    }

    @Override protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) savedRvState = savedInstanceState.getParcelable(KEY_RV_STATE);
    }
}
