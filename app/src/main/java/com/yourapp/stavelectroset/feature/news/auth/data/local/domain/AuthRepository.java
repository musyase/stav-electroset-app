package com.yourapp.stavelectroset.feature.news.auth.data.local.domain;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Query;
import retrofit2.http.Header;

public class AuthRepository {

    private static final String TAG = "AuthRepository";

    private static final boolean ENABLE_LOGIN_BY_LOGIN = true;
    private static final Pattern LOGIN_PATTERN = Pattern.compile("^[a-z0-9._-]+$");

    private static final long CONNECT_TIMEOUT_SEC = 12;
    private static final long READ_TIMEOUT_SEC = 25;
    private static final long WRITE_TIMEOUT_SEC = 25;

    public static final String AUTH_REDIRECT_URI = "stavelectroset://auth/callback";

    private final SupabaseAuthApi authApi;
    private final SupabaseAuthUserApi authUserApi;
    private final SupabaseFunctionsApi fnApi;
    private final TokenStore tokenStore;
    private final Gson gson = new Gson();

    public interface AuthCallback {
        void onResult(@NonNull Result result);
    }

    public interface SimpleCallback {
        void onResult(boolean ok, @Nullable String error);
    }

    public static class Result {
        public boolean ok;
        public String error;

        public String userUid;
        public boolean needsEmailConfirm;

        public String accessToken;
        public String refreshToken;

        public String warning;

        public static Result ok(@Nullable String uid, @NonNull String access, @NonNull String refresh) {
            Result r = new Result();
            r.ok = true;
            r.userUid = uid;
            r.accessToken = access;
            r.refreshToken = refresh;
            return r;
        }

        public static Result ok(@Nullable String uid, @NonNull String access, @NonNull String refresh, @Nullable String warning) {
            Result r = ok(uid, access, refresh);
            r.warning = warning;
            return r;
        }

        public static Result okNeedsConfirm(@Nullable String uid) {
            Result r = new Result();
            r.ok = true;
            r.needsEmailConfirm = true;
            r.userUid = uid;
            return r;
        }

        public static Result okWarning(@NonNull String warning) {
            Result r = new Result();
            r.ok = true;
            r.warning = warning;
            return r;
        }

        public static Result err(@NonNull String e) {
            Result r = new Result();
            r.ok = false;
            r.error = e;
            return r;
        }
    }

    public AuthRepository(@NonNull Context context) {
        validateBuildConfig();

        Context appCtx = context.getApplicationContext();
        this.tokenStore = new TokenStore(appCtx);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(new SupabaseHeadersInterceptor(tokenStore))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(ensureTrailingSlash(BuildConfig.SUPABASE_URL))
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        this.authApi = retrofit.create(SupabaseAuthApi.class);
        this.authUserApi = retrofit.create(SupabaseAuthUserApi.class);
        this.fnApi = retrofit.create(SupabaseFunctionsApi.class);
    }

    // ===== Registration (Signup) =====

    public void register(
            @NonNull String login,
            @NonNull String email,
            @NonNull String fullName,
            @Nullable String phone,
            @Nullable String accountNumber,
            @Nullable String meterSerials,
            @Nullable String subscriberType,
            @NonNull String pass,
            @NonNull String pass2,
            @NonNull AuthCallback cb
    ) {
        if (isBlank(login) || isBlank(email) || isBlank(fullName) || isBlank(pass) || isBlank(pass2)) {
            cb.onResult(Result.err("Заполните все обязательные поля"));
            return;
        }

        String normalizedLogin = normalizeLoginOrNull(login);
        if (normalizedLogin == null) {
            cb.onResult(Result.err("Логин: только латиница/цифры и символы . _ -"));
            return;
        }

        String trimmedEmail = email.trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            cb.onResult(Result.err("Некорректная почта"));
            return;
        }

        if (!pass.equals(pass2)) {
            cb.onResult(Result.err("Пароли не совпадают"));
            return;
        }

        String weak = checkStrength(pass);
        if (weak != null) {
            cb.onResult(Result.err(weak));
            return;
        }

        SignUpRequest req = new SignUpRequest();
        req.email = trimmedEmail;
        req.password = pass;

        req.data = new HashMap<>();
        req.data.put("login", normalizedLogin);
        req.data.put("full_name", fullName.trim());

        if (!isBlank(phone)) req.data.put("phone", phone.trim());
        if (!isBlank(accountNumber)) req.data.put("account_number", accountNumber.trim());
        if (!isBlank(meterSerials)) req.data.put("meter_serials", meterSerials.trim());
        if (!isBlank(subscriberType)) req.data.put("subscriber_type", subscriberType.trim());

        authApi.signUp(req).enqueue(new Callback<AuthResponse>() {
            @Override
            public void onResponse(@NonNull Call<AuthResponse> call, @NonNull retrofit2.Response<AuthResponse> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(Result.err(extractError(response)));
                    return;
                }

                AuthResponse body = response.body();
                String uid = (body != null && body.user != null) ? body.user.id : null;

                if (body == null || body.session == null || isBlank(body.session.accessToken)) {
                    cb.onResult(Result.okNeedsConfirm(uid));
                    return;
                }

                tokenStore.saveSession(body.session);
                String finalUid = (body.user != null && body.user.id != null) ? body.user.id : tokenStore.getUid();
                cb.onResult(Result.ok(finalUid, body.session.accessToken, body.session.refreshToken));
            }

            @Override
            public void onFailure(@NonNull Call<AuthResponse> call, @NonNull Throwable t) {
                cb.onResult(Result.err("Сеть: " + safeMsg(t)));
            }
        });
    }

    public void register(
            @NonNull String login,
            @NonNull String email,
            @NonNull String fullName,
            @Nullable String phone,
            @Nullable String accountNumber,
            @Nullable String meterSerials,
            @NonNull String pass,
            @NonNull String pass2,
            @NonNull AuthCallback cb
    ) {
        register(login, email, fullName, phone, accountNumber, meterSerials, null, pass, pass2, cb);
    }

    // ===== Email OTP =====

    public void resendSignupOtp(@NonNull String email, @NonNull SimpleCallback cb) {
        String trimmed = email.trim();
        if (isBlank(trimmed) || !Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) {
            cb.onResult(false, "Некорректная почта");
            return;
        }

        ResendRequest req = new ResendRequest();
        req.type = "signup";
        req.email = trimmed;

        authApi.resend(req).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull retrofit2.Response<JsonObject> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(false, extractError(response));
                    return;
                }
                cb.onResult(true, null);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                cb.onResult(false, "Сеть: " + safeMsg(t));
            }
        });
    }

    public void verifySignupOtp(@NonNull String email, @NonNull String token, @NonNull AuthCallback cb) {
        if (isBlank(email) || isBlank(token)) {
            cb.onResult(Result.err("Введите e-mail и код"));
            return;
        }

        String trimmedEmail = email.trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            cb.onResult(Result.err("Некорректная почта"));
            return;
        }

        VerifyOtpRequest req = new VerifyOtpRequest();
        req.type = "signup";
        req.email = trimmedEmail;
        req.token = token.trim();

        authApi.verifyOtp(req).enqueue(new Callback<Session>() {
            @Override
            public void onResponse(@NonNull Call<Session> call, @NonNull retrofit2.Response<Session> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(Result.err(extractError(response)));
                    return;
                }

                Session s = response.body();
                if (s == null || isBlank(s.accessToken) || isBlank(s.refreshToken)) {
                    cb.onResult(Result.err("Пустой ответ от сервера"));
                    return;
                }

                tokenStore.saveSession(s);
                String uid = (s.user != null) ? s.user.id : tokenStore.getUid();
                cb.onResult(Result.ok(uid, s.accessToken, s.refreshToken));
            }

            @Override
            public void onFailure(@NonNull Call<Session> call, @NonNull Throwable t) {
                cb.onResult(Result.err("Сеть: " + safeMsg(t)));
            }
        });
    }

    // ===== Login =====

    public void login(@NonNull String loginOrEmail, @NonNull String pass, @NonNull AuthCallback cb) {
        if (isBlank(loginOrEmail) || isBlank(pass)) {
            cb.onResult(Result.err("Введите логин/почту и пароль"));
            return;
        }

        String input = loginOrEmail.trim();
        boolean looksLikeEmail = Patterns.EMAIL_ADDRESS.matcher(input).matches();

        if (looksLikeEmail) {
            loginWithEmail(input, pass, cb);
            return;
        }

        String normalizedLogin = normalizeLoginOrNull(input);
        if (normalizedLogin == null) {
            cb.onResult(Result.err("Логин: только латиница/цифры и символы . _ -"));
            return;
        }

        if (!ENABLE_LOGIN_BY_LOGIN) {
            cb.onResult(Result.err("Пока вход только по EMAIL (введите e-mail)."));
            return;
        }

        loginWithLoginFunction(normalizedLogin, pass, cb);
    }

    public void reauthenticateByEmail(@NonNull String email, @NonNull String pass, @NonNull AuthCallback cb) {
        if (isBlank(email) || isBlank(pass)) {
            cb.onResult(Result.err("Введите email и текущий пароль"));
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            cb.onResult(Result.err("Некорректная почта"));
            return;
        }
        loginWithEmail(email.trim(), pass, cb);
    }

    private void loginWithEmail(@NonNull String email, @NonNull String pass, @NonNull AuthCallback cb) {
        TokenRequest req = new TokenRequest();
        req.email = email.trim();
        req.password = pass;

        authApi.passwordLogin("password", req).enqueue(new Callback<Session>() {
            @Override
            public void onResponse(@NonNull Call<Session> call, @NonNull retrofit2.Response<Session> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(Result.err(extractError(response)));
                    return;
                }

                Session s = response.body();
                if (s == null || isBlank(s.accessToken) || isBlank(s.refreshToken)) {
                    cb.onResult(Result.err("Пустой ответ от сервера"));
                    return;
                }

                tokenStore.saveSession(s);
                String uid = (s.user != null) ? s.user.id : tokenStore.getUid();
                cb.onResult(Result.ok(uid, s.accessToken, s.refreshToken));
            }

            @Override
            public void onFailure(@NonNull Call<Session> call, @NonNull Throwable t) {
                cb.onResult(Result.err("Сеть: " + safeMsg(t)));
            }
        });
    }

    private void loginWithLoginFunction(@NonNull String normalizedLogin, @NonNull String pass, @NonNull AuthCallback cb) {
        LoginWithLoginRequest req = new LoginWithLoginRequest();
        req.login = normalizedLogin;
        req.password = pass;

        fnApi.loginWithLogin(req).enqueue(new Callback<Session>() {
            @Override
            public void onResponse(@NonNull Call<Session> call, @NonNull retrofit2.Response<Session> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(Result.err(extractError(response)));
                    return;
                }

                Session s = response.body();
                if (s == null || isBlank(s.accessToken) || isBlank(s.refreshToken)) {
                    cb.onResult(Result.err("Пустой ответ от сервера"));
                    return;
                }

                tokenStore.saveSession(s);
                String uid = (s.user != null) ? s.user.id : tokenStore.getUid();
                cb.onResult(Result.ok(uid, s.accessToken, s.refreshToken));
            }

            @Override
            public void onFailure(@NonNull Call<Session> call, @NonNull Throwable t) {
                cb.onResult(Result.err("Сеть: " + safeMsg(t)));
            }
        });
    }

    // ===== Recovery password =====

    public void requestPasswordRecoveryEmail(@NonNull String email, @NonNull SimpleCallback cb) {
        String trimmed = email.trim();
        if (isBlank(trimmed) || !Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) {
            cb.onResult(false, "Некорректная почта");
            return;
        }

        RecoverRequest req = new RecoverRequest();
        req.email = trimmed;
        req.redirectTo = AUTH_REDIRECT_URI;

        authApi.recover(req).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull retrofit2.Response<JsonObject> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(false, extractError(response));
                    return;
                }
                cb.onResult(true, null);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                cb.onResult(false, "Сеть: " + safeMsg(t));
            }
        });
    }

    /**
     * Универсальная обработка callback uri:
     * - Fragment (#access_token=...&refresh_token=...)
     * - Query (?type=...&token_hash=...)
     * - Ошибки (#error=access_denied&error_code=otp_expired...)
     */
    public void handleAuthCallbackUri(@NonNull Uri uri, @NonNull AuthCallback cb) {
        try {
            // 0) Если пришла ошибка в fragment — аккуратно обработаем
            String fragment = uri.getFragment();
            if (!isBlank(fragment) && fragment.contains("error=")) {
                Map<String, String> errMap = parseQueryLike(fragment);
                String errorCode = n(errMap.get("error_code"));
                String errorDesc = n(errMap.get("error_description"));

                // allow open twice: если otp_expired, но токены уже есть — считаем ОК
                if ("otp_expired".equalsIgnoreCase(errorCode)) {
                    if (!isBlank(tokenStore.getAccessToken()) && !isBlank(tokenStore.getRefreshToken())) {
                        cb.onResult(Result.okWarning("Ссылка уже использована. Email уже подтверждён."));
                        return;
                    }
                    cb.onResult(Result.err("Ссылка устарела. Откройте самое новое письмо и попробуйте снова."));
                    return;
                }

                if (!isBlank(errorDesc)) {
                    cb.onResult(Result.err(Uri.decode(errorDesc)));
                    return;
                }
                cb.onResult(Result.err("Не удалось подтвердить действие по ссылке."));
                return;
            }

            // 1) Попытка: access/refresh токены в fragment (recovery чаще всего)
            String access = null;
            String refresh = null;

            if (!isBlank(fragment)) {
                Map<String, String> map = parseQueryLike(fragment);
                access = map.get("access_token");
                refresh = map.get("refresh_token");
            }

            // 2) Иногда может быть в query
            if (isBlank(access)) access = uri.getQueryParameter("access_token");
            if (isBlank(refresh)) refresh = uri.getQueryParameter("refresh_token");

            if (!isBlank(access) && !isBlank(refresh)) {
                Session s = new Session();
                s.accessToken = access.trim();
                s.refreshToken = refresh.trim();
                tokenStore.saveSession(s);

                cb.onResult(Result.ok(tokenStore.getUid(), s.accessToken, s.refreshToken,
                        "Ссылка подтверждена"));
                return;
            }

            // 3) Email-change чаще приходит как: ?type=email_change&token_hash=...
            String type = n(uri.getQueryParameter("type")).toLowerCase(Locale.ROOT);
            String tokenHash = n(uri.getQueryParameter("token_hash"));
            String token = n(uri.getQueryParameter("token"));

            if (!isBlank(type) && (!isBlank(tokenHash) || !isBlank(token))) {
                VerifyHashRequest vr = new VerifyHashRequest();
                vr.type = type;
                // supabase/goTrue может прислать token_hash или token — поддержим оба
                vr.tokenHash = !isBlank(tokenHash) ? tokenHash : token;

                authApi.verifyHash(vr).enqueue(new Callback<Session>() {
                    @Override
                    public void onResponse(@NonNull Call<Session> call, @NonNull retrofit2.Response<Session> response) {
                        if (!response.isSuccessful()) {
                            // allow open twice: если сервер сказал otp_expired, но токены уже есть — ОК
                            String err = extractError(response);
                            if (err != null && err.toLowerCase(Locale.ROOT).contains("otp_expired")) {
                                if (!isBlank(tokenStore.getAccessToken()) && !isBlank(tokenStore.getRefreshToken())) {
                                    cb.onResult(Result.okWarning("Ссылка уже использована. Email уже подтверждён."));
                                    return;
                                }
                                cb.onResult(Result.err("Ссылка устарела. Откройте самое новое письмо."));
                                return;
                            }
                            cb.onResult(Result.err(err != null ? err : ("Ошибка " + response.code())));
                            return;
                        }

                        Session s = response.body();
                        if (s == null || isBlank(s.accessToken) || isBlank(s.refreshToken)) {
                            // иногда verify может не вернуть session (зависит от настроек),
                            // но чаще возвращает. Если нет — попробуем просто не падать, если токены уже есть.
                            if (!isBlank(tokenStore.getAccessToken()) && !isBlank(tokenStore.getRefreshToken())) {
                                cb.onResult(Result.okWarning("Email подтверждён."));
                                return;
                            }
                            cb.onResult(Result.err("Не удалось получить сессию из подтверждения."));
                            return;
                        }

                        tokenStore.saveSession(s);
                        String uid = (s.user != null) ? s.user.id : tokenStore.getUid();
                        cb.onResult(Result.ok(uid, s.accessToken, s.refreshToken, "Email подтверждён."));
                    }

                    @Override
                    public void onFailure(@NonNull Call<Session> call, @NonNull Throwable t) {
                        cb.onResult(Result.err("Сеть: " + safeMsg(t)));
                    }
                });

                return;
            }

            cb.onResult(Result.err("Не удалось прочитать данные из ссылки.\nОжидалось либо #access_token, либо ?type=&token_hash=..."));

        } catch (Exception e) {
            cb.onResult(Result.err("Ошибка обработки ссылки: " + e.getMessage()));
        }
    }

    private static String n(@Nullable String s) { return s == null ? "" : s.trim(); }

    private Map<String, String> parseQueryLike(@NonNull String q) {
        Map<String, String> out = new HashMap<>();
        String[] parts = q.split("&");
        for (String p : parts) {
            int idx = p.indexOf('=');
            if (idx <= 0) continue;
            String k = Uri.decode(p.substring(0, idx));
            String v = Uri.decode(p.substring(idx + 1));
            out.put(k, v);
        }
        return out;
    }

    // ===== Change email / password =====

    public void requestEmailChange(@NonNull String newEmail, @NonNull SimpleCallback cb) {
        String trimmed = newEmail.trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) {
            cb.onResult(false, "Некорректный email");
            return;
        }

        String access = tokenStore.getAccessToken();
        if (isBlank(access)) {
            cb.onResult(false, "Нет access token (войдите заново)");
            return;
        }

        UpdateUserRequest body = new UpdateUserRequest();
        body.email = trimmed;

        // ВАЖНО: Redirect-To — чтобы письмо возвращало в приложение
        authUserApi.updateUser("Bearer " + access.trim(), AUTH_REDIRECT_URI, body)
                .enqueue(new Callback<JsonObject>() {
                    @Override
                    public void onResponse(@NonNull Call<JsonObject> call, @NonNull retrofit2.Response<JsonObject> response) {
                        if (!response.isSuccessful()) {
                            cb.onResult(false, extractError(response));
                            return;
                        }
                        cb.onResult(true, null);
                    }

                    @Override
                    public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                        cb.onResult(false, "Сеть: " + safeMsg(t));
                    }
                });
    }

    public void requestPasswordChange(@NonNull String newPassword, @NonNull SimpleCallback cb) {
        String p = newPassword == null ? "" : newPassword.trim();
        if (p.isEmpty()) {
            cb.onResult(false, "Введите новый пароль");
            return;
        }

        String weak = checkStrength(p);
        if (weak != null) {
            cb.onResult(false, weak);
            return;
        }

        String access = tokenStore.getAccessToken();
        if (isBlank(access)) {
            cb.onResult(false, "Нет access token (войдите заново)");
            return;
        }

        UpdateUserRequest body = new UpdateUserRequest();
        body.password = p;

        authUserApi.updateUser("Bearer " + access.trim(), null, body)
                .enqueue(new Callback<JsonObject>() {
                    @Override
                    public void onResponse(@NonNull Call<JsonObject> call, @NonNull retrofit2.Response<JsonObject> response) {
                        if (!response.isSuccessful()) {
                            cb.onResult(false, extractError(response));
                            return;
                        }
                        cb.onResult(true, null);
                    }

                    @Override
                    public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                        cb.onResult(false, "Сеть: " + safeMsg(t));
                    }
                });
    }

    // ===== Refresh / Logout / getters =====

    public void refresh(@NonNull AuthCallback cb) {
        String refresh = tokenStore.getRefreshToken();
        if (isBlank(refresh)) {
            cb.onResult(Result.err("Нет refresh token (нужно войти заново)"));
            return;
        }

        RefreshRequest req = new RefreshRequest();
        req.refreshToken = refresh;

        authApi.refreshToken("refresh_token", req).enqueue(new Callback<Session>() {
            @Override
            public void onResponse(@NonNull Call<Session> call, @NonNull retrofit2.Response<Session> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(Result.err(extractError(response)));
                    return;
                }

                Session s = response.body();
                if (s == null || isBlank(s.accessToken) || isBlank(s.refreshToken)) {
                    cb.onResult(Result.err("Пустой ответ от сервера"));
                    return;
                }

                tokenStore.saveSession(s);
                String uid = (s.user != null) ? s.user.id : tokenStore.getUid();
                cb.onResult(Result.ok(uid, s.accessToken, s.refreshToken));
            }

            @Override
            public void onFailure(@NonNull Call<Session> call, @NonNull Throwable t) {
                cb.onResult(Result.err("Сеть: " + safeMsg(t)));
            }
        });
    }

    public void logout() {
        tokenStore.clear();
    }

    public boolean isLoggedIn() {
        return !isBlank(tokenStore.getRefreshToken());
    }

    @Nullable
    public String getAccessToken() {
        return tokenStore.getAccessToken();
    }

    @Nullable
    public String getUserUid() {
        return tokenStore.getUid();
    }

    // ===== Retrofit API =====

    interface SupabaseAuthApi {
        @POST("auth/v1/signup")
        @Headers("Content-Type: application/json")
        Call<AuthResponse> signUp(@Body SignUpRequest body);

        @POST("auth/v1/token")
        @Headers("Content-Type: application/json")
        Call<Session> passwordLogin(@Query("grant_type") String grantType, @Body TokenRequest body);

        @POST("auth/v1/token")
        @Headers("Content-Type: application/json")
        Call<Session> refreshToken(@Query("grant_type") String grantType, @Body RefreshRequest body);

        @POST("auth/v1/resend")
        @Headers("Content-Type: application/json")
        Call<JsonObject> resend(@Body ResendRequest body);

        // OTP verify (signup)
        @POST("auth/v1/verify")
        @Headers("Content-Type: application/json")
        Call<Session> verifyOtp(@Body VerifyOtpRequest body);

        // token_hash verify (email_change / recovery sometimes)
        @POST("auth/v1/verify")
        @Headers("Content-Type: application/json")
        Call<Session> verifyHash(@Body VerifyHashRequest body);

        @POST("auth/v1/recover")
        @Headers("Content-Type: application/json")
        Call<JsonObject> recover(@Body RecoverRequest body);
    }

    interface SupabaseAuthUserApi {
        @PUT("auth/v1/user")
        @Headers("Content-Type: application/json")
        Call<JsonObject> updateUser(
                @Header("Authorization") String authorization,
                @Header("Redirect-To") String redirectTo,
                @Body UpdateUserRequest body
        );
    }

    interface SupabaseFunctionsApi {
        @POST("functions/v1/login_with_login")
        @Headers("Content-Type: application/json")
        Call<Session> loginWithLogin(@Body LoginWithLoginRequest body);
    }

    // ===== DTOs =====

    static class SignUpRequest {
        String email;
        String password;
        Map<String, Object> data;
    }

    static class TokenRequest {
        String email;
        String password;
    }

    static class RefreshRequest {
        @SerializedName("refresh_token")
        String refreshToken;
    }

    static class ResendRequest {
        String type;
        String email;
    }

    static class VerifyOtpRequest {
        String type;
        String email;
        String token;
    }

    static class VerifyHashRequest {
        String type;
        @SerializedName("token_hash")
        String tokenHash;
    }

    static class RecoverRequest {
        String email;
        @SerializedName("redirect_to")
        String redirectTo;
    }

    static class AuthResponse {
        User user;
        Session session;
    }

    static class LoginWithLoginRequest {
        String login;
        String password;
    }

    static class UpdateUserRequest {
        String email;
        String password;
    }

    static class Session {
        @SerializedName("access_token")
        String accessToken;

        @SerializedName("refresh_token")
        String refreshToken;

        @SerializedName("token_type")
        String tokenType;

        @SerializedName("expires_in")
        Long expiresIn;

        @SerializedName("expires_at")
        Long expiresAt;

        User user;
    }

    static class User {
        String id;
        String email;
    }

    // ===== Token storage =====

    static class TokenStore {
        private static final String PREF_ENC = "supabase_session_enc";
        private static final String PREF_PLAIN = "supabase_session_plain";

        private static final String K_ACCESS = "access";
        private static final String K_REFRESH = "refresh";
        private static final String K_UID = "uid";

        private final SharedPreferences prefs;

        TokenStore(Context ctx) {
            Context app = ctx.getApplicationContext();

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                prefs = app.getSharedPreferences(PREF_PLAIN, Context.MODE_PRIVATE);
                return;
            }

            SharedPreferences created;

            try {
                created = createEncrypted(app);
            } catch (Exception first) {
                Log.w(TAG, "TokenStore: encrypted prefs failed, wiping and retry", first);
                wipeSharedPrefsFile(app, PREF_ENC);

                try {
                    created = createEncrypted(app);
                } catch (Exception second) {
                    Log.e(TAG, "TokenStore: encrypted prefs still failed, fallback to plain", second);
                    created = app.getSharedPreferences(PREF_PLAIN, Context.MODE_PRIVATE);
                    created.edit().clear().commit();
                }
            }

            prefs = created;
        }

        private SharedPreferences createEncrypted(Context ctx) throws Exception {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    ctx,
                    PREF_ENC,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        }

        private void wipeSharedPrefsFile(Context ctx, String name) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ctx.deleteSharedPreferences(name);
                } else {
                    File f = new File(ctx.getApplicationInfo().dataDir + "/shared_prefs/" + name + ".xml");
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Exception ignored) {}
        }

        void saveSession(@NonNull Session s) {
            String access = safeTrim(s.accessToken);
            String refresh = safeTrim(s.refreshToken);

            if (access == null || refresh == null) return;

            String uid = (s.user != null) ? safeTrim(s.user.id) : null;

            SharedPreferences.Editor e = prefs.edit();
            e.putString(K_ACCESS, access);
            e.putString(K_REFRESH, refresh);

            if (uid != null) {
                e.putString(K_UID, uid);
            } else if (prefs.getString(K_UID, null) != null) {
                // keep
            } else {
                e.putString(K_UID, null);
            }

            e.commit();
        }

        @Nullable
        String getAccessToken() {
            return prefs.getString(K_ACCESS, null);
        }

        @Nullable
        String getRefreshToken() {
            return prefs.getString(K_REFRESH, null);
        }

        @Nullable
        String getUid() {
            return prefs.getString(K_UID, null);
        }

        void clear() {
            prefs.edit().clear().commit();
        }

        @Nullable
        private static String safeTrim(@Nullable String s) {
            if (s == null) return null;
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
    }

    // ===== Interceptor =====
    static class SupabaseHeadersInterceptor implements Interceptor {
        private final TokenStore tokenStore;

        SupabaseHeadersInterceptor(TokenStore tokenStore) {
            this.tokenStore = tokenStore;
        }

        @Override
        public Response intercept(@NonNull Chain chain) throws IOException {
            Request original = chain.request();
            String path = original.url().encodedPath();

            boolean isAuthEndpoint = path.startsWith("/auth/v1/");
            boolean isFunctionsEndpoint = path.startsWith("/functions/v1/");

            Request.Builder b = original.newBuilder()
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY);

            String existingAuth = original.header("Authorization");
            boolean hasAuthAlready = existingAuth != null && !existingAuth.trim().isEmpty();

            if (!hasAuthAlready) {
                if (isAuthEndpoint || isFunctionsEndpoint) {
                    b.header("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
                } else {
                    String access = tokenStore.getAccessToken();
                    if (access != null && !access.trim().isEmpty()) {
                        b.header("Authorization", "Bearer " + access.trim());
                    }
                }
            }

            return chain.proceed(b.build());
        }
    }

    // ===== Helpers =====

    private void validateBuildConfig() {
        String url = BuildConfig.SUPABASE_URL;
        String key = BuildConfig.SUPABASE_ANON_KEY;

        if (isBlank(url)) throw new IllegalStateException("BuildConfig.SUPABASE_URL пустой");
        if (isBlank(key)) throw new IllegalStateException("BuildConfig.SUPABASE_ANON_KEY пустой");

        if (!url.trim().startsWith("https://")) {
            throw new IllegalStateException("SUPABASE_URL должен начинаться с https://");
        }
    }

    private static String ensureTrailingSlash(String url) {
        if (url == null) return "";
        String t = url.trim();
        return t.endsWith("/") ? t : t + "/";
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    @Nullable
    private String normalizeLoginOrNull(@Nullable String login) {
        if (login == null) return null;
        String normalized = login.trim().toLowerCase();
        if (normalized.isEmpty()) return null;
        if (!LOGIN_PATTERN.matcher(normalized).matches()) return null;
        return normalized;
    }

    @Nullable
    private String checkStrength(@Nullable String p) {
        if (p == null) return "Пароль обязателен";
        if (p.length() < 8) return "Пароль слишком короткий (минимум 8 символов)";
        boolean hasU = false, hasL = false, hasD = false, hasS = false;
        for (char c : p.toCharArray()) {
            if (Character.isUpperCase(c)) hasU = true;
            else if (Character.isLowerCase(c)) hasL = true;
            else if (Character.isDigit(c)) hasD = true;
            else hasS = true;
        }
        int groups = (hasU ? 1 : 0) + (hasL ? 1 : 0) + (hasD ? 1 : 0) + (hasS ? 1 : 0);
        if (groups < 3) return "Добавьте буквы разного регистра, цифры и спецсимволы";
        return null;
    }

    private String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }

    private String extractError(@NonNull retrofit2.Response<?> response) {
        try {
            if (response.errorBody() == null) return "Ошибка " + response.code();
            String raw = response.errorBody().string();

            JsonObject obj;
            try {
                obj = gson.fromJson(raw, JsonObject.class);
            } catch (Exception ignore) {
                obj = null;
            }

            if (obj != null) {
                if (obj.has("error_description")) return obj.get("error_description").getAsString();
                if (obj.has("message")) return obj.get("message").getAsString();
                if (obj.has("msg")) return obj.get("msg").getAsString();
                if (obj.has("error")) return obj.get("error").getAsString();
            }

            if (raw != null && !raw.trim().isEmpty()) return raw.trim();
            return "Ошибка " + response.code();
        } catch (Exception e) {
            return "Ошибка " + response.code();
        }
    }
}