package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.util.Base64;
import android.util.Patterns;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.databinding.ActivityProfileSettingsBinding;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.ProfileRepository;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.PATCH;
import retrofit2.http.Query;

public class ProfileSettingsActivity extends AppCompatActivity {

    private static final String PROFILE_SELECT =
            "full_name,login,phone,account_number,meter_serials,subscriber_type,role,profile_state";

    private static final Pattern LOGIN_PATTERN = Pattern.compile("^[a-z0-9._-]+$");

    private ActivityProfileSettingsBinding binding;

    private AuthRepository authRepository;
    private ProfilesApi profilesApi;
    private ProfileRepository profileRepository;

    @Nullable private Call<List<ProfileRow>> inFlightGet;
    @Nullable private Call<List<ProfileRow>> inFlightPatch;

    private final AtomicBoolean retryGetAfterRefreshUsed = new AtomicBoolean(false);
    private final AtomicBoolean retryPatchAfterRefreshUsed = new AtomicBoolean(false);

    private String uid;
    private String role = "user";

    private ProfileSnapshot original;
    private boolean phoneSelfChange = false;

    // чтобы красиво показывать state (если захочешь потом)
    @NonNull private ProfileRepository.ProfileState lastState = ProfileRepository.ProfileState.UNKNOWN;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityProfileSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        setTitle("Настройки профиля");

        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();
        profilesApi = sl.supabaseRetrofit().create(ProfilesApi.class);
        profileRepository = sl.profileRepository();

        uid = safeTrim(authRepository.getUserUid());
        if (uid == null) {
            finish();
            return;
        }

        initUi();
        fillEmailFromJwt();
        loadProfile();
    }

    private void initUi() {
        if (binding == null) return;

        binding.btnSave.setEnabled(false);

        binding.etFullName.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { updateSaveState(); }
        });
        binding.etLogin.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { updateSaveState(); }
        });
        binding.etAccountNumber.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { updateSaveState(); }
        });
        binding.etMeterSerials.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { updateSaveState(); }
        });
        binding.etSubscriberType.addTextChangedListener(new SimpleTextWatcher() {
            @Override public void afterTextChanged(Editable s) { updateSaveState(); }
        });

        binding.etPhone.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (binding == null) return;
                if (phoneSelfChange) return;

                String raw = s == null ? "" : s.toString();
                int cursor = 0;
                try { cursor = Math.max(0, binding.etPhone.getSelectionStart()); } catch (Exception ignored) {}

                int[] newCursor = new int[]{cursor};
                String formatted = formatRuPhoneKeepCursor(raw, cursor, newCursor);

                if (!formatted.equals(raw)) {
                    phoneSelfChange = true;
                    binding.etPhone.setText(formatted);
                    try {
                        int pos = clamp(newCursor[0], 0, formatted.length());
                        binding.etPhone.setSelection(pos);
                    } catch (Exception ignored) {}
                    phoneSelfChange = false;
                }

                updateSaveState();
            }
        });

        binding.btnSave.setOnClickListener(v -> saveIfValid());

        binding.btnChangeEmail.setOnClickListener(v -> showChangeEmailDialog());
        binding.btnChangePassword.setOnClickListener(v -> showRecoveryPasswordDialog());

        setLoading(false);
    }

    private void loadProfile() {
        if (binding == null) return;

        cancelGet();
        setLoading(true);

        inFlightGet = profilesApi.getProfile(PROFILE_SELECT, "eq." + uid);
        inFlightGet.enqueue(new Callback<List<ProfileRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ProfileRow>> call,
                                   @NonNull Response<List<ProfileRow>> response) {
                if (binding == null) return;

                if (response.code() == 401 || response.code() == 403) {
                    if (!retryGetAfterRefreshUsed.getAndSet(true)) {
                        refreshAndRetryLoad();
                        return;
                    }
                }

                retryGetAfterRefreshUsed.set(false);

                if (!response.isSuccessful()) {
                    setLoading(false);
                    snack("Ошибка загрузки профиля: " + response.code());
                    return;
                }

                List<ProfileRow> rows = response.body();
                if (rows == null || rows.isEmpty()) {
                    setLoading(false);
                    snack("Профиль не найден (проверь trigger/RLS)");
                    return;
                }

                renderProfile(rows.get(0));
                original = snapshotFromUi();
                setLoading(false);
                updateSaveState();
            }

            @Override
            public void onFailure(@NonNull Call<List<ProfileRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                if (binding == null) return;
                setLoading(false);
                snack("Сеть: " + safeMsg(t));
            }
        });
    }

    private void refreshAndRetryLoad() {
        authRepository.refresh(result -> runOnUiThread(() -> {
            if (binding == null) return;

            if (result == null || !result.ok || isBlank(authRepository.getAccessToken())) {
                snack("Сессия истекла, войдите заново");
                finish();
                return;
            }

            fillEmailFromJwt();
            loadProfile();
        }));
    }

    private void renderProfile(@NonNull ProfileRow p) {
        if (binding == null) return;

        role = normalizeRole(p.role);
        lastState = ProfileRepository.ProfileState.from(p.profileState);
        profileRepository.saveCache(lastState); // чтобы guard сразу имел state

        binding.etFullName.setText(nullToEmpty(p.fullName));
        binding.etLogin.setText(nullToEmpty(p.login));
        binding.etPhone.setText(formatRuPhone(nullToEmpty(p.phone)));
        binding.etAccountNumber.setText(nullToEmpty(p.accountNumber));
        binding.etMeterSerials.setText(nullToEmpty(p.meterSerials));
        binding.etSubscriberType.setText(nullToEmpty(p.subscriberType));
    }

    private void fillEmailFromJwt() {
        if (binding == null) return;

        String access = authRepository.getAccessToken();
        String email = (access == null) ? null : jwtGetString(access, "email");
        binding.etEmail.setText(email == null ? "" : email);
    }

    private void saveIfValid() {
        if (binding == null) return;

        binding.tilFullName.setError(null);
        binding.tilLogin.setError(null);
        binding.tilPhone.setError(null);
        binding.tilAccountNumber.setError(null);
        binding.tilMeterSerials.setError(null);
        binding.tilSubscriberType.setError(null);

        String fullName = safeTrim(getText(binding.etFullName));
        String login = safeTrim(getText(binding.etLogin));
        String phone = safeTrim(getText(binding.etPhone));
        String accountNumber = safeTrim(getText(binding.etAccountNumber));
        String meterSerials = safeTrim(getText(binding.etMeterSerials));
        String subscriberType = safeTrim(getText(binding.etSubscriberType));

        if (isBlank(fullName)) {
            binding.tilFullName.setError("ФИО обязательно");
            snack("Заполните ФИО");
            return;
        }

        String normalizedLogin = normalizeLoginOrNull(login);
        if (normalizedLogin == null) {
            binding.tilLogin.setError("Только латиница/цифры и символы . _ -");
            snack("Некорректный логин");
            return;
        }

        // ВСЕ ПОЛЯ ОБЯЗАТЕЛЬНЫ (как ты решил)
        if (isBlank(accountNumber)) {
            binding.tilAccountNumber.setError("Лицевой счёт обязателен");
            snack("Заполните лицевой счёт");
            return;
        }

        if (isBlank(meterSerials)) {
            binding.tilMeterSerials.setError("Укажите прибор(ы) учёта");
            snack("Заполните приборы учёта");
            return;
        }

        if (isBlank(subscriberType)) {
            binding.tilSubscriberType.setError("Укажите тип абонента");
            snack("Заполните тип абонента");
            return;
        }

        // phone обязателен и должен быть полным (+7 и 10 цифр)
        if (isBlank(phone)) {
            binding.tilPhone.setError("Телефон обязателен");
            snack("Заполните телефон");
            return;
        }
        String phoneDigits = digitsOnly(phone);
        if (phoneDigits.length() < 11) {
            binding.tilPhone.setError("Введите телефон полностью");
            snack("Некорректный телефон");
            return;
        }

        if (original == null) {
            snack("Профиль ещё не загружен");
            return;
        }

        ProfileSnapshot now = snapshotFromValues(fullName, normalizedLogin, phone, accountNumber, meterSerials, subscriberType);
        JsonObject patch = buildPatch(original, now);

        if (patch.size() == 0) {
            snack("Нет изменений");
            updateSaveState();
            return;
        }

        cancelPatch();
        setLoading(true);

        inFlightPatch = profilesApi.patchProfile(patch, "eq." + uid);
        inFlightPatch.enqueue(new Callback<List<ProfileRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ProfileRow>> call,
                                   @NonNull Response<List<ProfileRow>> response) {
                if (binding == null) return;

                if (response.code() == 401 || response.code() == 403) {
                    if (!retryPatchAfterRefreshUsed.getAndSet(true)) {
                        authRepository.refresh(result -> runOnUiThread(() -> {
                            if (binding == null) return;

                            if (result == null || !result.ok || isBlank(authRepository.getAccessToken())) {
                                setLoading(false);
                                snack("Сессия истекла, войдите заново");
                                finish();
                                return;
                            }

                            retryPatchOnce(patch);
                        }));
                        return;
                    }
                }

                retryPatchAfterRefreshUsed.set(false);

                if (!response.isSuccessful()) {
                    setLoading(false);
                    snack("Ошибка сохранения: " + response.code());
                    return;
                }

                setLoading(false);
                snack("Сохранено");

                original = snapshotFromUi();
                updateSaveState();

                // 🔥 КЛЮЧЕВО: форсим refresh profile_state после сохранения
                profileRepository.refresh(r -> runOnUiThread(() -> {
                    if (binding == null) return;
                    if (r.ok) lastState = r.state;
                    // ничего не показываем — guard сам возьмёт обновлённый кеш
                }));
            }

            @Override
            public void onFailure(@NonNull Call<List<ProfileRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                if (binding == null) return;
                setLoading(false);
                snack("Сеть: " + safeMsg(t));
            }
        });
    }

    private void retryPatchOnce(@NonNull JsonObject patch) {
        if (binding == null) return;

        cancelPatch();
        setLoading(true);

        inFlightPatch = profilesApi.patchProfile(patch, "eq." + uid);
        inFlightPatch.enqueue(new Callback<List<ProfileRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ProfileRow>> call,
                                   @NonNull Response<List<ProfileRow>> response) {
                if (binding == null) return;

                retryPatchAfterRefreshUsed.set(false);

                if (!response.isSuccessful()) {
                    setLoading(false);
                    snack("Ошибка сохранения: " + response.code());
                    return;
                }

                setLoading(false);
                snack("Сохранено");

                original = snapshotFromUi();
                updateSaveState();

                profileRepository.refresh(r -> {});
            }

            @Override
            public void onFailure(@NonNull Call<List<ProfileRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                if (binding == null) return;
                setLoading(false);
                snack("Сеть: " + safeMsg(t));
            }
        });
    }

    private void updateSaveState() {
        if (binding == null) return;

        if (original == null) {
            binding.btnSave.setEnabled(false);
            return;
        }

        ProfileSnapshot now = snapshotFromUi();
        boolean changed = !original.equals(now);
        boolean loading = binding.progress.getVisibility() == View.VISIBLE;

        binding.btnSave.setEnabled(changed && !loading);
    }

    // ================= EMAIL CHANGE =================

    private void showChangeEmailDialog() {
        if (binding == null) return;

        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextInputLayout tilEmail = new TextInputLayout(this);
        tilEmail.setHint("Новый email");
        TextInputEditText etNewEmail = new TextInputEditText(this);
        etNewEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        tilEmail.addView(etNewEmail);

        TextInputLayout tilPass = new TextInputLayout(this);
        tilPass.setHint("Текущий пароль");
        TextInputEditText etPass = new TextInputEditText(this);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilPass.addView(etPass);

        root.addView(tilEmail);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp2.topMargin = dp(12);
        tilPass.setLayoutParams(lp2);
        root.addView(tilPass);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Смена email")
                .setMessage("На новый email придёт письмо для подтверждения.\nДля запроса смены нужно ввести текущий пароль.")
                .setView(root)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Продолжить", (d, which) -> {
                    String newEmail = safeTrim(etNewEmail.getText() != null ? etNewEmail.getText().toString() : null);
                    String pass = safeTrim(etPass.getText() != null ? etPass.getText().toString() : null);
                    doChangeEmail(newEmail, pass);
                })
                .show();
    }

    private void doChangeEmail(@Nullable String newEmail, @Nullable String currentPass) {
        if (binding == null) return;

        if (newEmail == null || !Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
            snack("Введите корректный новый email");
            return;
        }
        if (isBlank(currentPass)) {
            snack("Введите текущий пароль");
            return;
        }

        String access = authRepository.getAccessToken();
        String currentEmail = (access == null) ? null : jwtGetString(access, "email");
        if (isBlank(currentEmail)) {
            snack("Не удалось определить текущий email (перезайдите в аккаунт)");
            return;
        }

        setLoading(true);

        authRepository.reauthenticateByEmail(currentEmail.trim(), currentPass, result -> runOnUiThread(() -> {
            if (binding == null) return;

            if (result == null || !result.ok) {
                setLoading(false);
                snack(result != null && result.error != null ? result.error : "Неверный пароль");
                return;
            }

            authRepository.requestEmailChange(newEmail, (ok, error) -> runOnUiThread(() -> {
                if (binding == null) return;

                setLoading(false);

                if (!ok) {
                    snack(error != null ? error : "Ошибка смены email");
                    return;
                }

                binding.etEmail.setText(newEmail);

                // 🔥 После запроса смены email — форсим refresh state (скорее всего станет PENDING_EMAIL)
                profileRepository.refresh(r -> {});

                new MaterialAlertDialogBuilder(this)
                        .setTitle("Проверьте новый email")
                        .setMessage("Мы отправили письмо на новый email.\n\nПодтвердите смену по ссылке из письма.\n\n" +
                                "Если письмо не пришло — нажмите «Отправить повторно».")
                        .setNegativeButton("Отправить повторно", (d, w) -> {
                            profileRepository.resendEmailChange(newEmail.trim(), (rok, rerr) -> runOnUiThread(() -> {
                                snack(rok ? "Письмо отправлено повторно" : ("Не удалось отправить: " + (rerr == null ? "" : rerr)));
                            }));
                        })
                        .setPositiveButton("Ок", null)
                        .show();
            }));
        }));
    }

    // ================= PASSWORD (RECOVERY VIA EMAIL) =================

    private void showRecoveryPasswordDialog() {
        if (binding == null) return;

        String access = authRepository.getAccessToken();
        String email = (access == null) ? null : jwtGetString(access, "email");

        if (isBlank(email)) {
            snack("Не удалось определить email (перезайдите в аккаунт)");
            return;
        }

        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextInputLayout tilEmail = new TextInputLayout(this);
        tilEmail.setHint("Email для восстановления");
        TextInputEditText etEmail = new TextInputEditText(this);
        etEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        etEmail.setText(email.trim());
        tilEmail.addView(etEmail);

        root.addView(tilEmail);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Смена пароля через email")
                .setMessage("Мы отправим письмо со ссылкой.\nОткройте ссылку — приложение откроет экран восстановления, где вы зададите новый пароль.")
                .setView(root)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить письмо", (d, which) -> {
                    String targetEmail = safeTrim(etEmail.getText() != null ? etEmail.getText().toString() : null);
                    doSendRecoveryEmail(targetEmail);
                })
                .setNeutralButton("Открыть экран восстановления", (d, which) -> {
                    openResetPasswordScreen();
                })
                .show();
    }

    private void doSendRecoveryEmail(@Nullable String targetEmail) {
        if (binding == null) return;

        if (isBlank(targetEmail) || !Patterns.EMAIL_ADDRESS.matcher(targetEmail.trim()).matches()) {
            snack("Введите корректный email");
            return;
        }

        setLoading(true);

        authRepository.requestPasswordRecoveryEmail(targetEmail.trim(), (ok, error) -> runOnUiThread(() -> {
            if (binding == null) return;

            setLoading(false);

            if (!ok) {
                snack(error != null ? error : "Не удалось отправить письмо");
                return;
            }

            new MaterialAlertDialogBuilder(this)
                    .setTitle("Письмо отправлено")
                    .setMessage("Письмо со ссылкой для смены пароля отправлено на:\n" + targetEmail.trim()
                            + "\n\nОткройте ссылку из письма — приложение откроет экран восстановления.")
                    .setPositiveButton("Ок", null)
                    .show();
        }));
    }

    private void openResetPasswordScreen() {
        try {
            startActivity(new Intent(this, ResetPasswordActivity.class));
        } catch (Exception e) {
            snack("Не удалось открыть экран восстановления (проверь ResetPasswordActivity в манифесте)");
        }
    }

    private void setLoading(boolean loading) {
        if (binding == null) return;

        binding.progress.setVisibility(loading ? View.VISIBLE : View.GONE);

        binding.etFullName.setEnabled(!loading);
        binding.etLogin.setEnabled(!loading);
        binding.etPhone.setEnabled(!loading);
        binding.etAccountNumber.setEnabled(!loading);
        binding.etMeterSerials.setEnabled(!loading);
        binding.etSubscriberType.setEnabled(!loading);

        binding.btnChangeEmail.setEnabled(!loading);
        binding.btnChangePassword.setEnabled(!loading);

        if (loading) binding.btnSave.setEnabled(false);
        else updateSaveState();
    }

    private void snack(@NonNull String msg) {
        if (binding == null) return;
        Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_SHORT).show();
    }

    private int dp(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(dp * d);
    }

    @NonNull
    private static JsonObject buildPatch(@NonNull ProfileSnapshot oldS, @NonNull ProfileSnapshot newS) {
        JsonObject obj = new JsonObject();

        if (!eq(oldS.fullName, newS.fullName)) obj.addProperty("full_name", newS.fullName);
        if (!eq(oldS.login, newS.login)) obj.addProperty("login", newS.login);
        if (!eq(oldS.phone, newS.phone)) obj.addProperty("phone", blankToNull(newS.phone));
        if (!eq(oldS.accountNumber, newS.accountNumber)) obj.addProperty("account_number", blankToNull(newS.accountNumber));
        if (!eq(oldS.meterSerials, newS.meterSerials)) obj.addProperty("meter_serials", blankToNull(newS.meterSerials));
        if (!eq(oldS.subscriberType, newS.subscriberType)) obj.addProperty("subscriber_type", blankToNull(newS.subscriberType));

        return obj;
    }

    private ProfileSnapshot snapshotFromUi() {
        if (binding == null) return new ProfileSnapshot();
        return snapshotFromValues(
                safeTrim(getText(binding.etFullName)),
                safeTrim(getText(binding.etLogin)),
                safeTrim(getText(binding.etPhone)),
                safeTrim(getText(binding.etAccountNumber)),
                safeTrim(getText(binding.etMeterSerials)),
                safeTrim(getText(binding.etSubscriberType))
        );
    }

    private ProfileSnapshot snapshotFromValues(
            @Nullable String fullName,
            @Nullable String login,
            @Nullable String phone,
            @Nullable String accountNumber,
            @Nullable String meterSerials,
            @Nullable String subscriberType
    ) {
        ProfileSnapshot s = new ProfileSnapshot();
        s.fullName = fullName == null ? "" : fullName.trim();
        s.login = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
        s.phone = phone == null ? "" : phone.trim();
        s.accountNumber = accountNumber == null ? "" : accountNumber.trim();
        s.meterSerials = meterSerials == null ? "" : meterSerials.trim();
        s.subscriberType = subscriberType == null ? "" : subscriberType.trim();
        return s;
    }

    private void cancelGet() {
        Call<List<ProfileRow>> c = inFlightGet;
        if (c != null) { try { c.cancel(); } catch (Exception ignored) {} }
        inFlightGet = null;
    }

    private void cancelPatch() {
        Call<List<ProfileRow>> c = inFlightPatch;
        if (c != null) { try { c.cancel(); } catch (Exception ignored) {} }
        inFlightPatch = null;
    }

    @NonNull
    private static String normalizeRole(@Nullable String r) {
        if (r == null) return "user";
        String t = r.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? "user" : t;
    }

    @Nullable
    private static String normalizeLoginOrNull(@Nullable String login) {
        if (login == null) return null;
        String normalized = login.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if (!LOGIN_PATTERN.matcher(normalized).matches()) return null;
        return normalized;
    }

    @NonNull
    private static String digitsOnly(@Nullable String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) if (Character.isDigit(c)) b.append(c);
        return b.toString();
    }

    @NonNull
    private static String formatRuPhone(@Nullable String input) {
        return formatRuPhoneKeepCursor(input == null ? "" : input, (input == null ? 0 : input.length()), new int[]{0});
    }

    @NonNull
    private static String formatRuPhoneKeepCursor(@NonNull String raw, int cursor, @NonNull int[] newCursor) {
        int digitsBeforeCursor = 0;
        int safeCursor = clamp(cursor, 0, raw.length());
        for (int i = 0; i < safeCursor; i++) {
            if (Character.isDigit(raw.charAt(i))) digitsBeforeCursor++;
        }

        String d = digitsOnly(raw);

        if (d.startsWith("8")) d = "7" + d.substring(1);

        if (d.isEmpty()) {
            newCursor[0] = 0;
            return "";
        }

        if (!d.startsWith("7")) {
            newCursor[0] = safeCursor;
            return raw;
        }

        if (d.length() > 11) d = d.substring(0, 11);

        StringBuilder out = new StringBuilder();
        out.append("+7");

        int targetDigitIndex = Math.min(digitsBeforeCursor, d.length());
        int[] outCursor = new int[]{out.length()};

        int digitPos = 1;
        if (targetDigitIndex <= 1) outCursor[0] = out.length();

        if (d.length() >= 2) {
            out.append(" (");
            for (int i = 1; i < Math.min(4, d.length()); i++) {
                out.append(d.charAt(i));
                digitPos++;
                if (digitPos == targetDigitIndex) outCursor[0] = out.length();
            }
            if (d.length() >= 4) out.append(")");
        }

        if (d.length() >= 5) {
            out.append(" ");
            for (int i = 4; i < Math.min(7, d.length()); i++) {
                out.append(d.charAt(i));
                digitPos++;
                if (digitPos == targetDigitIndex) outCursor[0] = out.length();
            }
        }

        if (d.length() >= 8) {
            out.append("-");
            for (int i = 7; i < Math.min(9, d.length()); i++) {
                out.append(d.charAt(i));
                digitPos++;
                if (digitPos == targetDigitIndex) outCursor[0] = out.length();
            }
        }

        if (d.length() >= 10) {
            out.append("-");
            for (int i = 9; i < Math.min(11, d.length()); i++) {
                out.append(d.charAt(i));
                digitPos++;
                if (digitPos == targetDigitIndex) outCursor[0] = out.length();
            }
        }

        newCursor[0] = clamp(outCursor[0], 0, out.length());
        return out.toString();
    }

    private static int clamp(int v, int a, int b) {
        if (v < a) return a;
        if (v > b) return b;
        return v;
    }

    @Nullable
    private static String safeTrim(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    @NonNull
    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean eq(@Nullable String a, @Nullable String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        return x.equals(y);
    }

    @NonNull
    private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }

    @Nullable
    private static String getText(@NonNull TextInputEditText et) {
        return et.getText() == null ? null : et.getText().toString();
    }

    @Nullable
    private static String jwtGetString(@NonNull String jwt, @NonNull String key) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;

            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String json = new String(decoded);

            JSONObject obj = new JSONObject(json);
            if (!obj.has(key)) return null;
            return obj.optString(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    interface ProfilesApi {
        @GET("rest/v1/profiles")
        @Headers("Accept: application/json")
        Call<List<ProfileRow>> getProfile(
                @Query("select") String select,
                @Query("id") String idEq
        );

        @PATCH("rest/v1/profiles")
        @Headers({
                "Accept: application/json",
                "Content-Type: application/json",
                "Prefer: return=representation"
        })
        Call<List<ProfileRow>> patchProfile(
                @Body JsonObject body,
                @Query("id") String idEq
        );
    }

    static class ProfileRow {
        @SerializedName("full_name") String fullName;
        @SerializedName("login") String login;
        @SerializedName("phone") String phone;
        @SerializedName("account_number") String accountNumber;
        @SerializedName("meter_serials") String meterSerials;
        @SerializedName("subscriber_type") String subscriberType;
        @SerializedName("role") String role;
        @SerializedName("profile_state") String profileState;
    }

    static class ProfileSnapshot {
        String fullName = "";
        String login = "";
        String phone = "";
        String accountNumber = "";
        String meterSerials = "";
        String subscriberType = "";

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ProfileSnapshot)) return false;
            ProfileSnapshot s = (ProfileSnapshot) o;
            return eq(fullName, s.fullName)
                    && eq(login, s.login)
                    && eq(phone, s.phone)
                    && eq(accountNumber, s.accountNumber)
                    && eq(meterSerials, s.meterSerials)
                    && eq(subscriberType, s.subscriberType);
        }
    }

    static abstract class SimpleTextWatcher implements android.text.TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) {}
    }

    @Override
    protected void onDestroy() {
        cancelGet();
        cancelPatch();
        binding = null;
        super.onDestroy();
    }
}