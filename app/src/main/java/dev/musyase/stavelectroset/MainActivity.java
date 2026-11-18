package dev.musyase.stavelectroset;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.List;

import dev.musyase.stavelectroset.di.ServiceLocator;
import dev.musyase.stavelectroset.feature.news.data.local.NewsDao;
import dev.musyase.stavelectroset.feature.news.data.local.NewsEntity;
import dev.musyase.stavelectroset.feature.news.domain.NewsRepository;
import dev.musyase.stavelectroset.feature.news.ui.NewsAdapter;
import dev.musyase.stavelectroset.feature.news.ui.NewsDetailActivity;

public class MainActivity extends AppCompatActivity {
    private SwipeRefreshLayout swipe;
    private NewsRepository repo;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle("Новости");

        RecyclerView list = findViewById(R.id.list);
        swipe = findViewById(R.id.swipe);
        View emptyState = findViewById(R.id.empty_state);

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
        live.observe(this, items -> {
            adapter.submit(items);
            emptyState.setVisibility(items == null || items.isEmpty() ? View.VISIBLE : View.GONE);
        });
        findViewById(R.id.retry).setOnClickListener(view -> repo.refresh());

        swipe.setOnRefreshListener(() -> {
            repo.refresh();
            swipe.setRefreshing(false);
        });

        // первичная загрузка
        repo.refresh();
    }
}
