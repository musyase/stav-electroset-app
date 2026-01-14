package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.MainActivity;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.Captcha;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.PasswordHasher;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.SessionManager;
import com.yourapp.stavelectroset.web.WebPageActivity;

import java.util.concurrent.Executors;

public class RegisterActivity extends AppCompatActivity {

    // поля из layout
    private TextInputLayout tilLogin, tilFullName, tilAccount, tilPassword, tilPassword2,
            tilEmail, tilPhone, tilSerials, tilCaptcha;
    private TextInputEditText etLogin, etFullName, etAccount, etPassword, etPassword2,
            etEmail, etPhone, etSerials, etCaptcha;
    private MaterialButton btnEmailVerify, btnCaptchaRefresh, btnRegister;
    private ImageView ivCaptcha;

    private boolean emailVerified = false;
    private Captcha.Image currentCaptcha;

    private UserDao userDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        setTitle("Регистрация");

        userDao = ServiceLocator.get(this).db.user();

        // bind views
        tilLogin   = findViewById(R.id.tilLogin);
        tilFullName = findViewById(R.id.tilFullName);
        tilAccount = findViewById(R.id.tilAccount);
        tilPassword = findViewById(R.id.tilPassword);
        tilPassword2 = findViewById(R.id.tilPassword2);
        tilEmail   = findViewById(R.id.tilEmail);
        tilPhone   = findViewById(R.id.tilPhone);
        tilSerials = findViewById(R.id.tilSerials);
        tilCaptcha = findViewById(R.id.tilCaptcha);

        etLogin    = findViewById(R.id.etLogin);
        etFullName = findViewById(R.id.etFullName);
        etAccount  = findViewById(R.id.etAccount);
        etPassword = findViewById(R.id.etPassword);
        etPassword2 = findViewById(R.id.etPassword2);
        etEmail    = findViewById(R.id.etEmail);
        etPhone    = findViewById(R.id.etPhone);
        etSerials  = findViewById(R.id.etSerials);
        etCaptcha  = findViewById(R.id.etCaptcha);

        btnEmailVerify   = findViewById(R.id.btnEmailVerify);
        btnCaptchaRefresh = findViewById(R.id.btnCaptchaRefresh);
        btnRegister      = findViewById(R.id.btnRegister);
        ivCaptcha        = findViewById(R.id.ivCaptcha);

        // локальное «подтверждение» email
        btnEmailVerify.setOnClickListener(v -> {
            String email = textOf(etEmail);
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                tilEmail.setError("Неверный e-mail");
                emailVerified = false;
            } else {
                tilEmail.setError(null);
                emailVerified = true;
                Toast.makeText(this, "E-mail подтверждён", Toast.LENGTH_SHORT).show();
            }
        });

        btnCaptchaRefresh.setOnClickListener(v -> refreshCaptcha());
        btnRegister.setOnClickListener(v -> doRegister());

        refreshCaptcha();

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        setupBottomNav(bottomNav);
    }

    private void setupBottomNav(BottomNavigationView bottomNav) {
        if (bottomNav == null) return;

        // На регистрации логично подсветить профиль/кабинет, если он есть
        try {
            bottomNav.setSelectedItemId(R.id.nav_profile);
        } catch (Exception ignored) {}

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_news) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
                return true;
            } else if (id == R.id.nav_map) {
                // пока просто открываем сайт
                startActivity(new Intent(this, WebPageActivity.class)
                        .putExtra("open_tab", "company"));
                return true;
            } else if (id == R.id.nav_profile) {
                // с регистрации по «кабинету» ведём на экран входа
                startActivity(new Intent(this, LoginActivity.class));
                finish();
                return true;
            }
            return false;
        });
    }

    private void refreshCaptcha() {
        currentCaptcha = Captcha.generate(520, 180);
        ivCaptcha.setImageBitmap(currentCaptcha.bitmap);
        etCaptcha.setText("");
    }

    private void doRegister() {
        final String login       = textOf(etLogin);
        final String fullName    = textOf(etFullName);
        final String account     = textOf(etAccount);
        final String pass        = textOf(etPassword);
        final String pass2       = textOf(etPassword2);
        final String email       = textOf(etEmail);
        final String phone       = textOf(etPhone);
        final String serials     = textOf(etSerials);
        final String captchaText = textOf(etCaptcha);

        boolean ok = true;

        if (TextUtils.isEmpty(login)) {
            tilLogin.setError("Введите логин");
            ok = false;
        } else tilLogin.setError(null);

        if (TextUtils.isEmpty(fullName)) {
            tilFullName.setError("Введите ФИО");
            ok = false;
        } else tilFullName.setError(null);

        if (TextUtils.isEmpty(account)) {
            tilAccount.setError("Номер лицевого счёта обязателен");
            ok = false;
        } else tilAccount.setError(null);

        if (TextUtils.isEmpty(pass)) {
            tilPassword.setError("Введите пароль");
            ok = false;
        } else tilPassword.setError(null);

        if (!TextUtils.equals(pass, pass2)) {
            tilPassword2.setError("Пароли не совпадают");
            ok = false;
        } else tilPassword2.setError(null);

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Неверный e-mail");
            ok = false;
        } else tilEmail.setError(null);

        if (TextUtils.isEmpty(phone)) {
            tilPhone.setError("Введите телефон");
            ok = false;
        } else {
            tilPhone.setError(null);
        }

        if (TextUtils.isEmpty(serials)) {
            tilSerials.setError("Укажите серийные номера приборов учёта");
            ok = false;
        } else {
            tilSerials.setError(null);
        }

        if (!emailVerified) {
            Toast.makeText(this, "Подтвердите e-mail кнопкой рядом", Toast.LENGTH_SHORT).show();
            ok = false;
        }

        if (currentCaptcha == null || !currentCaptcha.text.equalsIgnoreCase(captchaText)) {
            tilCaptcha.setError("Неверная капча");
            refreshCaptcha();
            ok = false;
        } else {
            tilCaptcha.setError(null);
        }

        if (!ok) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean loginUsed = userDao.existsLogin(login) > 0;
            boolean emailUsed = userDao.existsEmail(email) > 0;

            if (loginUsed || emailUsed) {
                runOnUiThread(() -> {
                    if (loginUsed) tilLogin.setError("Логин уже занят");
                    if (emailUsed) tilEmail.setError("Почта уже используется");
                });
                return;
            }

            String hash;
            try {
                hash = PasswordHasher.hash(pass);
            } catch (Exception e) {
                hash = "";
            }

            long now = System.currentTimeMillis();

            UserEntity u = new UserEntity();
            u.login         = login;
            u.fullName      = fullName;
            u.accountNumber = account;
            u.email         = email;
            u.phone         = phone;
            u.meterSerials  = serials;
            u.passwordHash  = hash;
            u.createdAt     = now;

            long id = userDao.insert(u);
            userDao.markLogin(id, now);
            SessionManager.login(this, id);

            runOnUiThread(() -> {
                Toast.makeText(this, "Добро пожаловать!", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, ProfileActivity.class));
                finish();
            });
        });
    }

    private static String textOf(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }
}
