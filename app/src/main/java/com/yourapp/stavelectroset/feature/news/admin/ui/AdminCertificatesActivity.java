package com.yourapp.stavelectroset.feature.news.admin.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestEntity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateWithUser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminCertificatesActivity extends AppCompatActivity
        implements CertificateAdminAdapter.Listener {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private CertificateRequestDao certDao;
    private RecyclerView rv;
    private CertificateAdminAdapter adapter;
    private final List<CertificateWithUser> items = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_certificates);

        MaterialToolbar toolbar = findViewById(R.id.toolbarAdminCerts);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        certDao = ServiceLocator.get(this).certificateRequests();

        rv = findViewById(R.id.rvCertificates);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CertificateAdminAdapter(items, this);
        rv.setAdapter(adapter);

        loadRequests();
    }

    private void loadRequests() {
        io.execute(() -> {
            List<CertificateWithUser> list = certDao.getAllWithUser();
            runOnUiThread(() -> {
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
            });
        });
    }

    @Override
    public void onIssueClick(CertificateWithUser item) {
        CertificateRequestEntity r = item.request;

        if (r.issued) {
            Toast.makeText(this, "Справка уже выдана", Toast.LENGTH_SHORT).show();
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_issue_certificate, null);
        TextInputEditText etDate = view.findViewById(R.id.etIssueDate);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Выдать справку")
                .setView(view)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выдать", (dialog, which) -> {
                    String date = etDate.getText() == null
                            ? "" : etDate.getText().toString().trim();

                    io.execute(() -> {
                        r.issued = true;
                        r.issuedDate = date;
                        certDao.update(r);
                        runOnUiThread(this::loadRequests);
                    });
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}

