package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;

import com.yourapp.stavelectroset.MainActivity;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.admin.ui.AdminPanelActivity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestEntity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.SessionManager;

public class ProfileActivity extends AppCompatActivity {

    private static final int REQ_PICK_AVATAR = 1001;

    private ShapeableImageView ivAvatar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        // Если не авторизован и не админ — на экран логина
        if (!SessionManager.isLoggedIn(this) && !SessionManager.isAdmin(this)) {
            goToLoginAndClearTask();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setTitle("Личный кабинет");

        ivAvatar    = findViewById(R.id.ivAvatar);
        TextView tvLogin      = findViewById(R.id.tvLogin);
        TextView tvEmail      = findViewById(R.id.tvEmail);
        TextView tvFullName   = findViewById(R.id.tvFullName);
        TextView tvAccount    = findViewById(R.id.tvAccountNumber);
        TextView tvPhone      = findViewById(R.id.tvPhone);
        TextView tvSerials    = findViewById(R.id.tvMeterSerials);
        TextView tvSubscriber = findViewById(R.id.tvSubscriberType);
        MaterialButton btnLogout        = findViewById(R.id.btnLogout);
        MaterialButton btnAdmin         = findViewById(R.id.btnAdmin);
        MaterialButton btnRequestCert   = findViewById(R.id.btnRequestCertificate);

        // --- аватар: подгружаем сохранённый, если есть ---
        String savedAvatar = getSharedPreferences("profile", MODE_PRIVATE)
                .getString("avatar_uri", null);
        if (savedAvatar != null && !savedAvatar.isEmpty()) {
            ivAvatar.setImageURI(Uri.parse(savedAvatar));
        }

        ivAvatar.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(pick, REQ_PICK_AVATAR);
        });

        if (SessionManager.isAdmin(this)) {
            // Режим администратора
            tvLogin.setText("Логин: admin");
            tvEmail.setText("Почта: —");
            tvFullName.setText("ФИО: Администратор");

            if (tvAccount != null)   tvAccount.setText("Лицевой счёт: —");
            if (tvPhone != null)     tvPhone.setText("Телефон: —");
            if (tvSerials != null)   tvSerials.setText("Приборы учёта: —");
            if (tvSubscriber != null) tvSubscriber.setText("Тип абонента: —");

            btnAdmin.setVisibility(View.VISIBLE);
            btnAdmin.setOnClickListener(v ->
                    startActivity(new Intent(this, AdminPanelActivity.class))
            );

            // Администратору кнопка заказа справки не нужна
            if (btnRequestCert != null) {
                btnRequestCert.setVisibility(View.GONE);
            }
        } else {
            // Обычный пользователь
            btnAdmin.setVisibility(View.GONE);

            long uid = SessionManager.currentUserId(this);
            if (uid < 0) {
                goToLoginAndClearTask();
                return;
            }

            UserDao dao = ServiceLocator.get(this).db.user();
            dao.observeById(uid).observe(this, (UserEntity user) -> {
                if (user == null) return;

                tvLogin.setText("Логин: " + safe(user.login));
                tvEmail.setText("Почта: " + safe(user.email));
                tvFullName.setText("ФИО: " + safe(user.fullName));

                if (tvAccount != null) {
                    tvAccount.setText("Лицевой счёт: " + safe(user.accountNumber));
                }
                if (tvPhone != null) {
                    tvPhone.setText("Телефон: " + safe(user.phone));
                }
                if (tvSerials != null) {
                    tvSerials.setText("Приборы учёта: " + safe(user.meterSerials));
                }
                if (tvSubscriber != null) {
                    tvSubscriber.setText("Тип абонента: " + safe(user.subscriberType));
                }
            });

            // Обработчик кнопки "Заказать справку"
            if (btnRequestCert != null) {
                btnRequestCert.setOnClickListener(v -> {
                    CertificateRequestDao certDao =
                            ServiceLocator.get(ProfileActivity.this).certificateRequests();

                    new Thread(() -> {
                        CertificateRequestEntity existing =
                                certDao.getByUserSync(uid);

                        if (existing != null && !existing.issued) {
                            runOnUiThread(() ->
                                    Toast.makeText(ProfileActivity.this,
                                            "У вас уже есть активная заявка на справку",
                                            Toast.LENGTH_SHORT).show());
                        } else {
                            CertificateRequestEntity r = new CertificateRequestEntity();
                            r.userId = uid;
                            r.requestedAt = System.currentTimeMillis();
                            r.issued = false;
                            r.issuedDate = null;
                            certDao.insert(r);

                            runOnUiThread(() ->
                                    Toast.makeText(ProfileActivity.this,
                                            "Заявка на справку отправлена",
                                            Toast.LENGTH_SHORT).show());
                        }
                    }).start();
                });
            }
        }

        btnLogout.setOnClickListener(v -> {
            SessionManager.logout(this);
            Toast.makeText(this, "Вы вышли", Toast.LENGTH_SHORT).show();
            goToLoginAndClearTask();
        });

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_profile);
            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_news) {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                    return true;
                }   else if (id == R.id.nav_map) {
                startActivity(new Intent(this,
                        com.yourapp.stavelectroset.feature.news.consumers.ConsumersMapActivity.class));
                    return true;
                } else if (id == R.id.nav_profile) {
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_AVATAR && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                ivAvatar.setImageURI(uri);
                getSharedPreferences("profile", MODE_PRIVATE)
                        .edit()
                        .putString("avatar_uri", uri.toString())
                        .apply();
            }
        }
    }

    private void goToLoginAndClearTask() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
