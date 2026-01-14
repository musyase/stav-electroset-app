package com.yourapp.stavelectroset.feature.news.admin.ui;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;
import com.yourapp.stavelectroset.feature.news.data.local.NewsDao;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.Intent;
import com.google.android.material.button.MaterialButton;

public class AdminPanelActivity extends AppCompatActivity {

    private TextView tvMeta, tvUsersData, tvNewsData;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_panel);

        tvMeta      = findViewById(R.id.tvMeta);
        tvUsersData = findViewById(R.id.tvUsersData);
        tvNewsData  = findViewById(R.id.tvNewsData);

        MaterialButton btnOpenUsersEditor = findViewById(R.id.btnOpenUsersEditor);
        if (btnOpenUsersEditor != null) {
            btnOpenUsersEditor.setOnClickListener(v ->
                    startActivity(new Intent(this, AdminUsersActivity.class))
            );
        }
        MaterialButton btnOpenCertificates = findViewById(R.id.btnOpenCertificates);
        if (btnOpenCertificates != null) {
            btnOpenCertificates.setOnClickListener(v ->
                    startActivity(new Intent(this, AdminCertificatesActivity.class))
            );
        }

        loadDumpSafe();
    }

    private void loadDumpSafe() {
        io.execute(() -> {
            try {
                ServiceLocator sl = ServiceLocator.get(this);
                UserDao userDao   = sl.db.user();
                NewsDao newsDao   = sl.db.news();

                List<UserEntity> users = userDao.getAllSync();
                List<NewsEntity> news  = newsDao.getAllSync();

                StringBuilder ub = new StringBuilder();
                for (UserEntity u : users) {
                    ub.append(String.format(Locale.US,
                            "id=%d | login=%s | email=%s | name=%s | account=%s | phone=%s | meters=%s | type=%s | created=%d | last=%s\n",
                            u.id,
                            nz(u.login),
                            nz(u.email),
                            nz(u.fullName),
                            nz(u.accountNumber),
                            nz(u.phone),
                            nz(u.meterSerials),
                            nz(u.subscriberType),
                            u.createdAt,
                            u.lastLogin == null ? "-" : String.valueOf(u.lastLogin)));
                }

                StringBuilder nb = new StringBuilder();
                for (NewsEntity n : news) {
                    // не трогаем возможное поле id — выводим надёжно
                    nb.append("• ").append(n.title).append('\n')
                            .append(n.url).append('\n')
                            .append("dateUtc=").append(n.dateUtc).append("\n\n");
                }

                runOnUiThread(() -> {
                    tvMeta.setText(String.format(Locale.US,
                            "users=%d  news=%d", users.size(), news.size()));
                    tvUsersData.setText(ub.toString());
                    tvNewsData.setText(nb.toString());
                });

            } catch (Throwable t) {
                Log.e("AdminPanel", "loadDump failed", t);
                runOnUiThread(() ->
                        Toast.makeText(this, "Ошибка админ-панели: " + t.getClass().getSimpleName(),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private String nz(String s){ return s == null ? "" : s; }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
