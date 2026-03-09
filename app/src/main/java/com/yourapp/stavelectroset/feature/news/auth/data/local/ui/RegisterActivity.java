package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yourapp.stavelectroset.MainActivity;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.Captcha;
import com.yourapp.stavelectroset.feature.news.consumers.ConsumersMapActivity;

import java.util.regex.Pattern;

/**
 * Регистрация:
 * 1) login + ФИО + пароль + повтор + email
 * 2) "Получить код на e-mail"
 * 3) OTP подтверждение
 * 4) после подтверждения — капча
 * 5) "Завершить регистрацию"
 */
public class RegisterActivity extends AppCompatActivity {

    private static final long RESEND_COOLDOWN_MS = 60_000L;
    private static final Pattern LOGIN_PATTERN = Pattern.compile("^[a-z0-9._-]+$");

    private static final String STATE_EMAIL_CONFIRMED = "state_email_confirmed";
    private static final String STATE_CONFIRMED_EMAIL = "state_confirmed_email";
    private static final String STATE_LAST_OTP_SEND_ELAPSED = "state_last_otp_send_elapsed";
    private static final String STATE_OTP_VISIBLE = "state_otp_visible";
    private static final String STATE_OTP_EMAIL = "state_otp_email";

    private TextInputLayout tilLogin, tilFullName, tilPassword, tilPassword2,
            tilEmail, tilCaptcha, tilOtp;

    private TextInputEditText etLogin, etFullName, etPassword, etPassword2,
            etEmail, etCaptcha, etOtp;

    private MaterialButton btnEmailVerify, btnCaptchaRefresh, btnRegister, btnOtpConfirm, btnOtpResend;
    private ImageView ivCaptcha;
    private BottomNavigationView bottomNav;

    private android.view.View otpContainer;

    private boolean emailConfirmed = false;
    private String confirmedEmail = null;

    private boolean otpVisible = false;
    private String otpEmail = null;

    private Captcha.Image currentCaptcha;
    private AuthRepository authRepository;

    private long lastOtpSendAtElapsed = 0L;
    private boolean loading = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        setTitle("Регистрация");

        authRepository = ServiceLocator.get(getApplicationContext()).authRepository();

        bindViews();
        restoreState(savedInstanceState);
        applyPrefillFromIntent();

        btnEmailVerify.setOnClickListener(v -> startEmailConfirmFlow());
        btnCaptchaRefresh.setOnClickListener(v -> refreshCaptcha());
        btnRegister.setOnClickListener(v -> finishRegistration());
        btnOtpConfirm.setOnClickListener(v -> verifyOtpFromField());
        btnOtpResend.setOnClickListener(v -> resendOtp());

        setupEmailWatcher();
        refreshCaptcha();

        bottomNav = findViewById(R.id.bottomNav);
        setupBottomNav(bottomNav);

        applyOtpUi();
        syncEmailVerifyButton();
        updateButtonsState();
    }

    private void bindViews() {
        tilLogin = findViewById(R.id.tilLogin);
        tilFullName = findViewById(R.id.tilFullName);
        tilPassword = findViewById(R.id.tilPassword);
        tilPassword2 = findViewById(R.id.tilPassword2);
        tilEmail = findViewById(R.id.tilEmail);
        tilCaptcha = findViewById(R.id.tilCaptcha);

        etLogin = findViewById(R.id.etLogin);
        etFullName = findViewById(R.id.etFullName);
        etPassword = findViewById(R.id.etPassword);
        etPassword2 = findViewById(R.id.etPassword2);
        etEmail = findViewById(R.id.etEmail);
        etCaptcha = findViewById(R.id.etCaptcha);

        btnEmailVerify = findViewById(R.id.btnEmailVerify);
        btnCaptchaRefresh = findViewById(R.id.btnCaptchaRefresh);
        btnRegister = findViewById(R.id.btnRegister);
        ivCaptcha = findViewById(R.id.ivCaptcha);

        otpContainer = findViewById(R.id.otpContainer);
        tilOtp = findViewById(R.id.tilOtp);
        etOtp = findViewById(R.id.etOtp);
        btnOtpConfirm = findViewById(R.id.btnOtpConfirm);
        btnOtpResend = findViewById(R.id.btnOtpResend);
    }

    private void restoreState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        emailConfirmed = savedInstanceState.getBoolean(STATE_EMAIL_CONFIRMED, false);
        confirmedEmail = savedInstanceState.getString(STATE_CONFIRMED_EMAIL, null);
        lastOtpSendAtElapsed = savedInstanceState.getLong(STATE_LAST_OTP_SEND_ELAPSED, 0L);

        otpVisible = savedInstanceState.getBoolean(STATE_OTP_VISIBLE, false);
        otpEmail = savedInstanceState.getString(STATE_OTP_EMAIL, null);
    }

    private void applyPrefillFromIntent() {
        Intent intent = getIntent();
        if (intent == null) return;

        String prefill = intent.getStringExtra("prefill_login_or_email");
        if (isEmpty(prefill)) return;

        String v = prefill.trim();
        if (Patterns.EMAIL_ADDRESS.matcher(v).matches()) {
            if (etEmail != null) etEmail.setText(v);
        } else {
            if (etLogin != null) etLogin.setText(v);
        }
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
                openForward(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), true);
                return true;
            }

            if (id == R.id.nav_map) {
                openForward(new Intent(this, ConsumersMapActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), true);
                return true;
            }

            if (id == R.id.nav_profile) {
                openForward(new Intent(this, LoginActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), true);
                return true;
            }

            return false;
        });

        bottomNav.setOnItemReselectedListener(item -> {
            // ничего не делаем
        });
    }

    private void setupEmailWatcher() {
        if (etEmail == null) return;

        etEmail.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String email = s == null ? "" : s.toString().trim();

                if (emailConfirmed && !isEmpty(confirmedEmail) && !confirmedEmail.equalsIgnoreCase(email)) {
                    emailConfirmed = false;
                    confirmedEmail = null;
                    toast("E-mail изменён — нужно подтвердить заново");
                }

                if (otpVisible && !isEmpty(otpEmail) && !otpEmail.equalsIgnoreCase(email)) {
                    otpVisible = false;
                    otpEmail = null;
                    if (etOtp != null) etOtp.setText("");
                    if (tilOtp != null) tilOtp.setError(null);
                }

                applyOtpUi();
                syncEmailVerifyButton();
                updateButtonsState();
            }
        });
    }

    private void refreshCaptcha() {
        currentCaptcha = Captcha.generate(520, 180);
        if (ivCaptcha != null && currentCaptcha != null) {
            ivCaptcha.setImageBitmap(currentCaptcha.bitmap);
        }
        if (etCaptcha != null) etCaptcha.setText("");
        if (tilCaptcha != null) tilCaptcha.setError(null);
    }

    private void startEmailConfirmFlow() {
        final String loginRaw = textOf(etLogin);
        final String fullName = textOf(etFullName);
        final String email = textOf(etEmail);
        final String pass = textOf(etPassword);
        final String pass2 = textOf(etPassword2);

        boolean ok = true;

        String normalizedLogin = normalizeLoginOrNull(loginRaw);
        if (normalizedLogin == null) {
            if (tilLogin != null) tilLogin.setError("Логин: латиница, цифры и . _ -");
            ok = false;
        } else {
            if (tilLogin != null) tilLogin.setError(null);
        }

        if (TextUtils.isEmpty(fullName)) {
            if (tilFullName != null) tilFullName.setError("Введите ФИО");
            ok = false;
        } else {
            if (tilFullName != null) tilFullName.setError(null);
        }

        if (isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (tilEmail != null) tilEmail.setError("Неверный e-mail");
            ok = false;
        } else {
            if (tilEmail != null) tilEmail.setError(null);
        }

        if (TextUtils.isEmpty(pass)) {
            if (tilPassword != null) tilPassword.setError("Введите пароль");
            ok = false;
        } else {
            if (tilPassword != null) tilPassword.setError(null);
        }

        if (!TextUtils.equals(pass, pass2)) {
            if (tilPassword2 != null) tilPassword2.setError("Пароли не совпадают");
            ok = false;
        } else {
            if (tilPassword2 != null) tilPassword2.setError(null);
        }

        if (!ok) return;

        if (emailConfirmed && !isEmpty(confirmedEmail) && confirmedEmail.equalsIgnoreCase(email)) {
            toast("E-mail уже подтверждён");
            return;
        }

        setLoading(true);

        authRepository.register(
                normalizedLogin,
                email,
                fullName,
                "",
                "",
                "",
                pass,
                pass2,
                result -> runOnUiThread(() -> {
                    setLoading(false);

                    if (result == null) {
                        toast("Пустой ответ");
                        return;
                    }

                    if (!result.ok) {
                        toast(!isEmpty(result.error) ? result.error : "Ошибка регистрации");
                        return;
                    }

                    if (result.needsEmailConfirm) {
                        lastOtpSendAtElapsed = SystemClock.elapsedRealtime();
                        otpVisible = true;
                        otpEmail = email;

                        applyOtpUi();
                        syncEmailVerifyButton();
                        updateButtonsState();

                        toast("Код отправлен. Введите его ниже.");
                        return;
                    }

                    markEmailConfirmed(email);
                    toast("E-mail подтверждён");
                    updateButtonsState();
                })
        );
    }

    private void verifyOtpFromField() {
        final String email = textOf(etEmail);

        if (isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (tilEmail != null) tilEmail.setError("Неверный e-mail");
            return;
        } else {
            if (tilEmail != null) tilEmail.setError(null);
        }

        if (!otpVisible) {
            toast("Сначала нажмите «Получить код на e-mail»");
            return;
        }

        String code = textOf(etOtp);
        if (isEmpty(code)) {
            if (tilOtp != null) tilOtp.setError("Введите код");
            return;
        } else {
            if (tilOtp != null) tilOtp.setError(null);
        }

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

            markEmailConfirmed(email);

            otpVisible = false;
            otpEmail = null;
            if (etOtp != null) etOtp.setText("");

            applyOtpUi();
            syncEmailVerifyButton();
            updateButtonsState();

            toast("E-mail подтверждён. Теперь введите капчу и завершите регистрацию.");
        }));
    }

    private void resendOtp() {
        final String email = textOf(etEmail);

        if (isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (tilEmail != null) tilEmail.setError("Неверный e-mail");
            return;
        } else {
            if (tilEmail != null) tilEmail.setError(null);
        }

        if (!otpVisible) {
            toast("Сначала нажмите «Получить код на e-mail»");
            return;
        }

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
    }

    private void finishRegistration() {
        final String email = textOf(etEmail);
        final String captchaText = textOf(etCaptcha);

        if (!emailConfirmed || isEmpty(confirmedEmail) || !confirmedEmail.equalsIgnoreCase(email)) {
            toast("Сначала подтвердите e-mail");
            return;
        }

        if (currentCaptcha == null || TextUtils.isEmpty(captchaText)
                || !currentCaptcha.text.equalsIgnoreCase(captchaText)) {
            if (tilCaptcha != null) tilCaptcha.setError("Неверная капча");
            refreshCaptcha();
            return;
        } else {
            if (tilCaptcha != null) tilCaptcha.setError(null);
        }

        if (authRepository == null || !authRepository.isLoggedIn()) {
            toast("Нет активной сессии. Подтвердите код заново или войдите снова.");
            openForward(new Intent(this, LoginActivity.class), true);
            return;
        }

        toast("Регистрация завершена");
        openForward(new Intent(this, ProfileActivity.class), true);
    }

    private void applyOtpUi() {
        if (otpContainer != null) {
            otpContainer.setVisibility(otpVisible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private void markEmailConfirmed(@NonNull String email) {
        emailConfirmed = true;
        confirmedEmail = email;
        syncEmailVerifyButton();
    }

    private void syncEmailVerifyButton() {
        if (btnEmailVerify == null) return;

        if (emailConfirmed) {
            btnEmailVerify.setText("E-mail подтверждён");
            btnEmailVerify.setEnabled(false);
            return;
        }

        if (otpVisible) {
            btnEmailVerify.setText("Код отправлен на e-mail");
        } else {
            btnEmailVerify.setText("Получить код на e-mail");
        }
    }

    private void setLoading(boolean v) {
        loading = v;
        updateButtonsState();
    }

    private void updateButtonsState() {
        String email = textOf(etEmail);
        boolean emailValid = !isEmpty(email) && Patterns.EMAIL_ADDRESS.matcher(email).matches();

        if (btnEmailVerify != null && !emailConfirmed) {
            btnEmailVerify.setEnabled(!loading && emailValid);
        }

        if (btnOtpConfirm != null) {
            btnOtpConfirm.setEnabled(!loading && otpVisible);
        }

        if (btnOtpResend != null) {
            btnOtpResend.setEnabled(!loading && otpVisible);
        }

        if (btnRegister != null) {
            btnRegister.setEnabled(!loading && emailConfirmed);
        }

        if (btnCaptchaRefresh != null) {
            btnCaptchaRefresh.setEnabled(!loading);
        }
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

    private static String textOf(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private void toast(@Nullable String msg) {
        if (isFinishing() || isDestroyed()) return;
        String m = (msg == null || msg.trim().isEmpty()) ? "Ошибка" : msg.trim();
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private static String normalize(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    @Nullable
    private static String normalizeLoginOrNull(@Nullable String login) {
        String n = normalize(login);
        if (n.isEmpty()) return null;
        if (!LOGIN_PATTERN.matcher(n).matches()) return null;
        return n;
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putBoolean(STATE_EMAIL_CONFIRMED, emailConfirmed);
        outState.putString(STATE_CONFIRMED_EMAIL, confirmedEmail);
        outState.putLong(STATE_LAST_OTP_SEND_ELAPSED, lastOtpSendAtElapsed);

        outState.putBoolean(STATE_OTP_VISIBLE, otpVisible);
        outState.putString(STATE_OTP_EMAIL, otpEmail);
    }
}