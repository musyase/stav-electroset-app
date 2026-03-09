package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;

public class ResetPasswordActivity extends AppCompatActivity {

    private TextInputLayout tilPass1, tilPass2;
    private TextInputEditText etPass1, etPass2;
    private View btnSetPass, progress;

    private AuthRepository authRepository;

    private boolean linkHandled = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_password);
        setTitle("Восстановление пароля");

        authRepository = ServiceLocator.get(getApplicationContext()).authRepository();

        tilPass1 = findViewById(R.id.tilNewPass);
        tilPass2 = findViewById(R.id.tilRepeatPass);
        etPass1 = findViewById(R.id.etNewPass);
        etPass2 = findViewById(R.id.etRepeatPass);
        btnSetPass = findViewById(R.id.btnSetPassword);
        progress = findViewById(R.id.progress);

        if (etPass1 != null) etPass1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (etPass2 != null) etPass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        btnSetPass.setOnClickListener(v -> submitNewPassword());

        // Если сессия уже есть (например, пользователь второй раз открыл ссылку),
        // мы можем не блокировать UI.
        setLoading(true);
        handleIncomingLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingLink(intent);
    }

    private void handleIncomingLink(@Nullable Intent intent) {
        if (linkHandled) {
            setLoading(false);
            return;
        }

        Uri uri = (intent == null) ? null : intent.getData();

        // Если uri нет, но токен уже есть — даём менять пароль
        if (uri == null) {
            if (!TextUtils.isEmpty(authRepository.getAccessToken())) {
                linkHandled = true;
                setLoading(false);
                snack("Сессия уже активна. Задайте новый пароль.");
                return;
            }
            setLoading(false);
            showFatal("Не удалось открыть ссылку. Попробуйте ещё раз из письма.");
            return;
        }

        authRepository.handleAuthCallbackUri(uri, result -> runOnUiThread(() -> {
            // Важно: при повторном открытии может вернуться otp_expired, но сессия уже сохранена —
            // AuthRepository вернёт ok с warning.
            if (result == null || !result.ok) {
                // если всё равно есть access token — даём продолжить
                if (!TextUtils.isEmpty(authRepository.getAccessToken())) {
                    linkHandled = true;
                    setLoading(false);
                    snack("Ссылка уже использована, но сессия активна. Задайте новый пароль.");
                    return;
                }

                setLoading(false);
                showFatal(result != null && !TextUtils.isEmpty(result.error)
                        ? result.error
                        : "Ссылка недействительна или устарела.");
                return;
            }

            linkHandled = true;
            setLoading(false);
            snack(!TextUtils.isEmpty(result.warning)
                    ? result.warning
                    : "Ссылка подтверждена. Задайте новый пароль.");
        }));
    }

    private void submitNewPassword() {
        clearErrors();

        String p1 = textOf(etPass1);
        String p2 = textOf(etPass2);

        if (TextUtils.isEmpty(p1)) {
            if (tilPass1 != null) tilPass1.setError("Введите новый пароль");
            return;
        }
        if (p1.length() < 8) {
            if (tilPass1 != null) tilPass1.setError("Минимум 8 символов");
            return;
        }
        if (!p1.equals(p2)) {
            if (tilPass2 != null) tilPass2.setError("Пароли не совпадают");
            return;
        }

        setLoading(true);
        authRepository.requestPasswordChange(p1, (ok, err) -> runOnUiThread(() -> {
            setLoading(false);

            if (!ok) {
                snack(!TextUtils.isEmpty(err) ? err : "Не удалось сменить пароль");
                return;
            }

            new MaterialAlertDialogBuilder(this)
                    .setTitle("Готово")
                    .setMessage("Пароль изменён. Теперь можно войти с новым паролем.")
                    .setPositiveButton("Перейти ко входу", (d, w) -> {
                        startActivity(new Intent(this, LoginActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
                        finish();
                    })
                    .setCancelable(false)
                    .show();
        }));
    }

    private void clearErrors() {
        if (tilPass1 != null) tilPass1.setError(null);
        if (tilPass2 != null) tilPass2.setError(null);
    }

    private void setLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (btnSetPass != null) btnSetPass.setEnabled(!loading);
        if (etPass1 != null) etPass1.setEnabled(!loading);
        if (etPass2 != null) etPass2.setEnabled(!loading);
    }

    private void showFatal(@NonNull String msg) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Ошибка")
                .setMessage(msg)
                .setPositiveButton("Ок", (d, w) -> {
                    startActivity(new Intent(this, LoginActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void snack(@NonNull String msg) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
    }

    @NonNull
    private static String textOf(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }
}