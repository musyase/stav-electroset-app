package com.yourapp.stavelectroset;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;

import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.data.local.NewsDao;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;
import com.yourapp.stavelectroset.feature.news.domain.NewsRepository;
import com.yourapp.stavelectroset.feature.news.ui.NewsAdapter;
import com.yourapp.stavelectroset.feature.news.ui.NewsDetailActivity;

public class MainActivity extends AppCompatActivity {
    private SwipeRefreshLayout swipe;
    private NewsRepository repo;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("Новости");

        RecyclerView list = findViewById(R.id.list);
        swipe = findViewById(R.id.swipe);

        list.setLayoutManager(new LinearLayoutManager(this));
        NewsAdapter adapter = new NewsAdapter(e -> {
            Intent i = new Intent(this, NewsDetailActivity.class);
            i.putExtra(NewsDetailActivity.EXTRA_TITLE, e.title);
            i.putExtra(NewsDetailActivity.EXTRA_URL, e.url);
            i.putExtra(NewsDetailActivity.EXTRA_HTML, e.contentHtml);
            i.putExtra(NewsDetailActivity.EXTRA_IMAGE, e.imageUrl);
            i.putExtra(NewsDetailActivity.EXTRA_DATE_UTC, e.dateUtc);
            startActivity(i);
        });
        list.setAdapter(adapter);

        // DI
        ServiceLocator sl = ServiceLocator.get(this);
        NewsDao dao = sl.db.news();
        repo = new NewsRepository(dao, sl.retrofit);

        // LiveData из Room
        LiveData<List<NewsEntity>> live = dao.observeAll();
        live.observe(this, adapter::submit);

        swipe.setOnRefreshListener(() -> {
            repo.refresh();
            swipe.setRefreshing(false);
        });

        // первичная загрузка
        repo.refresh();
    }
}