package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;

import java.util.Locale;

/**
 * Универсальный обработчик deep-link из Supabase писем.
 * - email_change (обычно query: ?type=email_change&token_hash=...)
 * - recovery (обычно fragment: #access_token=...&refresh_token=...)
 *
 * После успешной обработки:
 * - recovery -> ResetPasswordActivity
 * - остальные -> ProfileActivity с сообщением
 */
public class AuthCallbackActivity extends AppCompatActivity {

    private boolean handled = false;
    private AuthRepository authRepository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authRepository = ServiceLocator.get(getApplicationContext()).authRepository();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(@Nullable Intent intent) {
        if (handled) return;

        Uri uri = intent != null ? intent.getData() : null;
        if (uri == null) {
            showFatal("Не удалось открыть ссылку. Откройте её ещё раз из письма.");
            return;
        }

        String type = normalize(uri.getQueryParameter("type"));

        authRepository.handleAuthCallbackUri(uri, result -> runOnUiThread(() -> {
            if (result == null || !result.ok) {
                showFatal(result != null && !TextUtils.isEmpty(result.error)
                        ? result.error
                        : "Ссылка недействительна или устарела.");
                return;
            }

            handled = true;

            // recovery -> экран смены пароля
            if ("recovery".equals(type)) {
                startActivity(new Intent(this, ResetPasswordActivity.class)
                        .setData(uri)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
                return;
            }

            // email_change / signup / magiclink / invite -> просто в профиль
            String msg = !TextUtils.isEmpty(result.warning) ? result.warning : "Действие подтверждено";
            Intent go = new Intent(this, ProfileActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("toast", msg);
            startActivity(go);
            finish();
        }));
    }

    private void showFatal(String msg) {
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

    private static String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }
}