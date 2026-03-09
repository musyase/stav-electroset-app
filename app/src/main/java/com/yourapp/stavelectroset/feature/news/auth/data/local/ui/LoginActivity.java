package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yourapp.stavelectroset.MainActivity;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.consumers.ConsumersMapActivity;

/**
 * Login:
 * - вход по email или login (через AuthRepository)
 * - если есть refresh token -> тихий refresh один раз
 * - если email не подтверждён -> OTP диалог прямо здесь
 *
 * + ВОССТАНОВЛЕНИЕ ПАРОЛЯ:
 * - "Забыли пароль?" -> отправляем recovery письмо на email
 * - по ссылке откроется ResetPasswordActivity (deep link)
 */
public class LoginActivity extends AppCompatActivity {

    private static final long RESEND_COOLDOWN_MS = 60_000L;

    private TextInputLayout tilLogin, tilPassword;
    private TextInputEditText etLogin, etPassword;
    private MaterialButton btnLogin, btnGoRegister;

    private android.widget.TextView tvForgotPassword;

    private AuthRepository authRepository;
    private boolean silentRefreshTried = false;

    private long lastOtpSendAtElapsed = 0L;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        setTitle("Вход");

        authRepository = ServiceLocator.get(getApplicationContext()).authRepository();

        tilLogin = findViewById(R.id.tilLogin);
        tilPassword = findViewById(R.id.tilPassword);
        etLogin = findViewById(R.id.etLogin);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnGoRegister = findViewById(R.id.btnGoRegister);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);

        // Если есть сессия — пробуем тихо восстановить (один раз)
        trySilentRefreshOnce();

        btnGoRegister.setOnClickListener(v ->
                openForward(new Intent(this, RegisterActivity.class), true)
        );

        btnLogin.setOnClickListener(v -> tryLogin());

        if (tvForgotPassword != null) {
            tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        setupBottomNav(bottomNav);
    }

    private void trySilentRefreshOnce() {
        if (silentRefreshTried) return;
        silentRefreshTried = true;

        if (authRepository == null || !authRepository.isLoggedIn()) return;

        setLoading(true);
        authRepository.refresh(result -> runOnUiThread(() -> {
            setLoading(false);
            if (result != null && result.ok) {
                goProfile();
            } else {
                authRepository.logout();
            }
        }));
    }

    private void setupBottomNav(@Nullable BottomNavigationView bottomNav) {
        if (bottomNav == null) return;

        try {
            bottomNav.setSelectedItemId(R.id.nav_profile);
        } catch (Exception ignored) {
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_news) {
                openForward(
                        new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        true
                );
                return true;
            }

            if (id == R.id.nav_map) {
                openForward(
                        new Intent(this, ConsumersMapActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        true
                );
                return true;
            }

            return id == R.id.nav_profile;
        });

        bottomNav.setOnItemReselectedListener(item -> {
            // ничего не делаем
        });
    }

    private void tryLogin() {
        final String loginOrEmail = textOf(etLogin);
        final String pass = textOf(etPassword);

        boolean ok = true;

        if (TextUtils.isEmpty(loginOrEmail)) {
            tilLogin.setError("Введите логин или e-mail");
            ok = false;
        } else {
            tilLogin.setError(null);
        }

        if (TextUtils.isEmpty(pass)) {
            tilPassword.setError("Введите пароль");
            ok = false;
        } else {
            tilPassword.setError(null);
        }

        if (!ok) return;

        setLoading(true);

        authRepository.login(loginOrEmail, pass, result -> runOnUiThread(() -> {
            setLoading(false);

            tilLogin.setError(null);
            tilPassword.setError(null);

            if (result == null) {
                toast("Пустой ответ");
                return;
            }

            if (!result.ok) {
                String err = !isEmpty(result.error) ? result.error : "Ошибка входа";

                // Подтверждение почты
                if (looksLikeEmailNotConfirmed(err)) {
                    tilLogin.setError("Подтвердите e-mail (код из письма).");
                    handleEmailConfirmNeeded(loginOrEmail);
                    return;
                }

                // Неверные креды
                if (containsIgnoreCase(err, "invalid")
                        || containsIgnoreCase(err, "credentials")
                        || containsIgnoreCase(err, "парол")
                        || containsIgnoreCase(err, "невер")) {
                    tilPassword.setError("Неверный логин/e-mail или пароль");
                    return;
                }

                if (!isEmpty(result.warning)) {
                    toastLong(result.warning);
                } else {
                    toast(err);
                }
                return;
            }

            if (!isEmpty(result.warning)) {
                toastLong(result.warning);
            } else {
                toast("С возвращением!");
            }

            goProfile();
        }));
    }

    private boolean looksLikeEmailNotConfirmed(@NonNull String err) {
        return containsIgnoreCase(err, "confirm")
                || containsIgnoreCase(err, "confirmed")
                || containsIgnoreCase(err, "подтверж")
                || containsIgnoreCase(err, "verification");
    }

    /**
     * Если требуется подтверждение:
     * - если введён EMAIL -> показываем OTP диалог здесь
     * - если введён LOGIN -> просим ввести EMAIL (OTP привязан к email)
     */
    private void handleEmailConfirmNeeded(@NonNull String loginOrEmail) {
        String v = loginOrEmail.trim();

        if (Patterns.EMAIL_ADDRESS.matcher(v).matches()) {
            showOtpDialog(v);
            return;
        }

        toastLong("Для подтверждения нужен e-mail. Введите e-mail и повторите вход.");
    }

    private void showOtpDialog(@NonNull String email) {
        TextInputLayout til = new TextInputLayout(this);
        til.setHint("Код из письма");
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        TextInputEditText et = new TextInputEditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setMaxLines(1);
        til.addView(et);

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Подтверждение e-mail")
                .setMessage("Введите код, который пришёл на:\n" + email + "\n\n" +
                        "Если письма нет — проверь «Спам».\n" +
                        "Повторно можно отправлять не чаще 1 раза в минуту.")
                .setView(til)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Отправить ещё раз", null)
                .setPositiveButton("Подтвердить", null)
                .create();

        dlg.setOnShowListener(dialog -> {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                long elapsed = SystemClock.elapsedRealtime() - lastOtpSendAtElapsed;
                long left = RESEND_COOLDOWN_MS - elapsed;
                if (left > 0) {
                    toast("Подожди " + ((left + 999) / 1000) + " сек");
                    return;
                }

                setLoading(true);
                authRepository.resendSignupOtp(email, (ok, err) -> runOnUiThread(() -> {
                    setLoading(false);
                    if (ok) {
                        lastOtpSendAtElapsed = SystemClock.elapsedRealtime();
                        toast("Код отправлен повторно");
                    } else {
                        toast(!isEmpty(err) ? err : "Не удалось отправить код");
                    }
                }));
            });

            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String code = et.getText() == null ? "" : et.getText().toString().trim();
                if (TextUtils.isEmpty(code)) {
                    til.setError("Введите код");
                    return;
                }
                til.setError(null);

                setLoading(true);
                authRepository.verifySignupOtp(email, code, res -> runOnUiThread(() -> {
                    setLoading(false);

                    if (res == null) {
                        toast("Пустой ответ");
                        return;
                    }

                    if (!res.ok) {
                        toast(!isEmpty(res.error) ? res.error : "Неверный код");
                        return;
                    }

                    toast("E-mail подтверждён");
                    dlg.dismiss();
                    goProfile();
                }));
            });
        });

        dlg.show();
    }

    // ================= FORGOT PASSWORD =================

    private void showForgotPasswordDialog() {
        String fromField = textOf(etLogin);
        String prefillEmail = "";
        if (!TextUtils.isEmpty(fromField) && Patterns.EMAIL_ADDRESS.matcher(fromField).matches()) {
            prefillEmail = fromField.trim();
        }

        TextInputLayout tilEmail = new TextInputLayout(this);
        tilEmail.setHint("E-mail");
        tilEmail.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        TextInputEditText etEmail = new TextInputEditText(this);
        etEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        etEmail.setMaxLines(1);
        etEmail.setText(prefillEmail);
        tilEmail.addView(etEmail);

        AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Восстановление пароля")
                .setMessage("Мы отправим письмо со ссылкой для смены пароля.\n" +
                        "Откройте ссылку — приложение откроет экран, где вы зададите новый пароль.")
                .setView(tilEmail)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить", null)
                .create();

        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String email = etEmail.getText() == null ? "" : etEmail.getText().toString().trim();

                if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    tilEmail.setError("Введите корректный e-mail");
                    return;
                }
                tilEmail.setError(null);

                setLoading(true);
                authRepository.requestPasswordRecoveryEmail(email, (ok, err) -> runOnUiThread(() -> {
                    setLoading(false);

                    if (!ok) {
                        toastLong(!isEmpty(err) ? err : "Не удалось отправить письмо");
                        return;
                    }

                    toastLong("Письмо отправлено. Проверь почту и «Спам».");
                    dlg.dismiss();
                }));
            });
        });

        dlg.show();
    }

    // ================= NAV / UI =================

    private void goProfile() {
        openForward(new Intent(this, ProfileActivity.class), true);
    }

    private void openForward(@NonNull Intent intent, boolean finishCurrent) {
        startActivity(intent);
        overridePendingTransition(R.anim.screen_enter_forward, R.anim.screen_exit_forward);
        if (finishCurrent) finish();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.screen_enter_back, R.anim.screen_exit_back);
    }

    private void setLoading(boolean loading) {
        if (btnLogin != null) btnLogin.setEnabled(!loading);
        if (btnGoRegister != null) btnGoRegister.setEnabled(!loading);
        if (tvForgotPassword != null) tvForgotPassword.setEnabled(!loading);
    }

    private static String textOf(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private static boolean containsIgnoreCase(@Nullable String text, @Nullable String part) {
        if (text == null || part == null) return false;
        return text.toLowerCase().contains(part.toLowerCase());
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private void toast(@Nullable String msg) {
        if (isFinishing() || isDestroyed()) return;
        String m = (msg == null || msg.trim().isEmpty()) ? "Ошибка" : msg.trim();
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(@Nullable String msg) {
        if (isFinishing() || isDestroyed()) return;
        String m = (msg == null || msg.trim().isEmpty()) ? "Ошибка" : msg.trim();
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }
}