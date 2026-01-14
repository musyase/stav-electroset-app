package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.MainActivity; // <— импорт главной
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.PasswordHasher;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.SessionManager;
import com.yourapp.stavelectroset.web.WebPageActivity;

import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private TextInputLayout tilLogin, tilPassword;
    private TextInputEditText etLogin, etPassword;
    private MaterialButton btnLogin, btnGoRegister;

    private UserDao userDao;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        setTitle("Вход");

        userDao = ServiceLocator.get(this).db.user();

        tilLogin = findViewById(R.id.tilLogin);
        tilPassword = findViewById(R.id.tilPassword);
        etLogin = findViewById(R.id.etLogin);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnGoRegister = findViewById(R.id.btnGoRegister);

        btnGoRegister.setOnClickListener(v -> {
            startActivity(new Intent(this, RegisterActivity.class));
            finish();
        });

        btnLogin.setOnClickListener(v -> tryLogin());

        // bottom nav
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        setupBottomNav(bottomNav);
    }

    private void setupBottomNav(BottomNavigationView bottomNav) {
        if (bottomNav == null) return;

        // Подсветим "Кабинет", если такой id есть
        int idProfile = getIdRes("menu_profile");
        if (idProfile != 0) bottomNav.setSelectedItemId(idProfile);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            String title = String.valueOf(item.getTitle());

            int idNews = getIdRes("menu_news");
            int idCompany = getIdRes("menu_company");
            int idProf = getIdRes("menu_profile");

            if (idNews != 0 && id == idNews || "Новости".equalsIgnoreCase(title)) {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                return true;
            }
            if (idCompany != 0 && id == idCompany || "О компании".equalsIgnoreCase(title)) {
                startActivity(new Intent(this, WebPageActivity.class)
                        .putExtra("open_tab", "company")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                return true;
            }
            if (idProf != 0 && id == idProf || "Кабинет".equalsIgnoreCase(title) || "Профиль".equalsIgnoreCase(title)) {
                // Мы уже на экране входа — оставляем как есть
                return true;
            }
            return false;
        });
    }

    private int getIdRes(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }

    private void tryLogin() {
        final String loginOrEmail = textOf(etLogin);
        final String pass = textOf(etPassword);

        boolean ok = true;
        if (TextUtils.isEmpty(loginOrEmail)) { tilLogin.setError("Введите логин или e-mail"); ok = false; } else tilLogin.setError(null);
        if (TextUtils.isEmpty(pass)) { tilPassword.setError("Введите пароль"); ok = false; } else tilPassword.setError(null);
        if (!ok) return;

        // === спец-вход для админа ===
        if ("admin".equals(loginOrEmail) && "admin".equals(pass)) {
            SessionManager.loginAdmin(this);
            Toast.makeText(this, "Вход как администратор", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, ProfileActivity.class));
            finish();
            return;
        }

        Executors.newSingleThreadExecutor().execute(() -> {
            // пробуем по логину, потом по почте
            UserEntity u = userDao.getByLogin(loginOrEmail);
            if (u == null) u = userDao.getByEmail(loginOrEmail);

            if (u == null) {
                final String msg = "Пользователь не найден";
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
                return;
            }

            boolean okPw;
            try { okPw = PasswordHasher.verify(pass, u.passwordHash); }
            catch (Exception e) { okPw = false; }

            if (!okPw) {
                runOnUiThread(() -> tilPassword.setError("Неверный пароль"));
                return;
            }

            long now = System.currentTimeMillis();
            userDao.markLogin(u.id, now);
            SessionManager.login(this, u.id);

            runOnUiThread(() -> {
                Toast.makeText(this, "С возвращением!", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, ProfileActivity.class));
                finish();
            });
        });
    }

    private static String textOf(TextInputEditText et){
        return et.getText()==null ? "" : et.getText().toString().trim();
    }
}
