package com.yourapp.stavelectroset.feature.news.auth.data.local.domain;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.di.ServiceLocator;

import java.util.List;
import java.util.concurrent.TimeUnit;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Body;
import retrofit2.http.Query;

public final class ProfileRepository {

    public enum ProfileState {
        INCOMPLETE,
        PENDING_EMAIL,
        ACTIVE,
        BLOCKED,
        UNKNOWN;

        @NonNull
        public static ProfileState from(@Nullable String raw) {
            if (raw == null) return UNKNOWN;
            String t = raw.trim().toUpperCase();
            if (t.isEmpty()) return UNKNOWN;
            try { return ProfileState.valueOf(t); }
            catch (Exception ignore) { return UNKNOWN; }
        }
    }

    public static final class CachedState {
        public final ProfileState state;
        public final long updatedAtMs;

        public CachedState(@NonNull ProfileState state, long updatedAtMs) {
            this.state = state;
            this.updatedAtMs = updatedAtMs;
        }

        public boolean isFresh(long ttlMs) {
            if (updatedAtMs <= 0) return false;
            long age = Math.abs(System.currentTimeMillis() - updatedAtMs);
            return age <= ttlMs;
        }
    }

    public interface StateCallback {
        void onResult(@NonNull Result result);
    }

    public static final class Result {
        public final boolean ok;
        @NonNull public final ProfileState state;
        @Nullable public final String error;

        public Result(boolean ok, @NonNull ProfileState state, @Nullable String error) {
            this.ok = ok;
            this.state = state;
            this.error = error;
        }

        @NonNull public static Result ok(@NonNull ProfileState s) { return new Result(true, s, null); }
        @NonNull public static Result fail(@NonNull ProfileState fallback, @Nullable String err) { return new Result(false, fallback, err); }
    }

    private static final String PREFS = "profile_state_cache";
    private static final String K_STATE = "state";
    private static final String K_TS = "ts";

    // TTL по умолчанию: 5 минут
    public static final long DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private static final String PROFILE_SELECT = "profile_state,updated_at";

    private final SharedPreferences prefs;
    private final AuthRepository authRepository;
    private final ProfilesApi profilesApi;
    private final AuthResendApi authResendApi;

    public ProfileRepository(@NonNull Context appContext) {
        Context c = appContext.getApplicationContext();
        this.prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        ServiceLocator sl = ServiceLocator.get(c);
        this.authRepository = sl.authRepository();
        this.profilesApi = sl.supabaseRetrofit().create(ProfilesApi.class);
        this.authResendApi = sl.supabaseRetrofit().create(AuthResendApi.class);
    }

    @NonNull
    public CachedState getCached() {
        String raw = prefs.getString(K_STATE, null);
        long ts = prefs.getLong(K_TS, 0L);
        return new CachedState(ProfileState.from(raw), ts);
    }

    public void clearCache() {
        prefs.edit().clear().apply();
    }

    /**
     * Быстрый доступ: если есть свежий кеш — отдаём его, иначе UNKNOWN.
     */
    @NonNull
    public ProfileState getCachedIfFresh(long ttlMs) {
        CachedState c = getCached();
        if (c.isFresh(ttlMs)) return c.state;
        return ProfileState.UNKNOWN;
    }

    /**
     * Принудительно обновляет state из Supabase profiles.
     * Fail-closed по твоему правилу: если сеть упала, возвращаем кеш/UNKNOWN.
     */
    public void refresh(@NonNull StateCallback cb) {
        String uid = safeTrim(authRepository.getUserUid());
        if (uid == null) {
            CachedState cached = getCached();
            cb.onResult(Result.fail(cached.state, "no uid"));
            return;
        }

        Call<List<ProfileStateRow>> call = profilesApi.getProfileState(PROFILE_SELECT, "eq." + uid);
        call.enqueue(new Callback<List<ProfileStateRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ProfileStateRow>> call,
                                   @NonNull Response<List<ProfileStateRow>> response) {

                if (response.code() == 401 || response.code() == 403) {
                    // Попробуем refresh token, затем повторим 1 раз
                    authRepository.refresh(r -> {
                        if (r == null || !r.ok) {
                            CachedState cached = getCached();
                            cb.onResult(Result.fail(cached.state, "session expired"));
                            return;
                        }
                        Call<List<ProfileStateRow>> retry = profilesApi.getProfileState(PROFILE_SELECT, "eq." + uid);
                        retry.enqueue(new Callback<List<ProfileStateRow>>() {
                            @Override
                            public void onResponse(@NonNull Call<List<ProfileStateRow>> call2,
                                                   @NonNull Response<List<ProfileStateRow>> resp2) {
                                handleProfileStateResponse(resp2, cb);
                            }

                            @Override
                            public void onFailure(@NonNull Call<List<ProfileStateRow>> call2, @NonNull Throwable t) {
                                CachedState cached = getCached();
                                cb.onResult(Result.fail(cached.state, safeMsg(t)));
                            }
                        });
                    });
                    return;
                }

                handleProfileStateResponse(response, cb);
            }

            @Override
            public void onFailure(@NonNull Call<List<ProfileStateRow>> call, @NonNull Throwable t) {
                CachedState cached = getCached();
                cb.onResult(Result.fail(cached.state, safeMsg(t)));
            }
        });
    }

    private void handleProfileStateResponse(@NonNull Response<List<ProfileStateRow>> response,
                                            @NonNull StateCallback cb) {
        if (!response.isSuccessful()) {
            CachedState cached = getCached();
            cb.onResult(Result.fail(cached.state, "http " + response.code()));
            return;
        }

        List<ProfileStateRow> rows = response.body();
        if (rows == null || rows.isEmpty()) {
            CachedState cached = getCached();
            cb.onResult(Result.fail(cached.state, "profile not found"));
            return;
        }

        ProfileStateRow r = rows.get(0);
        ProfileState st = ProfileState.from(r.profileState);

        saveCache(st);

        cb.onResult(Result.ok(st));
    }

    public void saveCache(@NonNull ProfileState st) {
        prefs.edit()
                .putString(K_STATE, st.name())
                .putLong(K_TS, System.currentTimeMillis())
                .apply();
    }

    /**
     * Resend для email change.
     * Supabase /auth/v1/resend, type=email_change, email=<target>
     */
    public void resendEmailChange(@NonNull String email, @NonNull ResendCallback cb) {
        String e = safeTrim(email);
        if (e == null) {
            cb.onResult(false, "email пустой");
            return;
        }

        ResendBody body = new ResendBody();
        body.type = "email_change";
        body.email = e;

        Call<Void> call = authResendApi.resend(body);
        call.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                if (!response.isSuccessful()) {
                    cb.onResult(false, "http " + response.code());
                    return;
                }
                cb.onResult(true, null);
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                cb.onResult(false, safeMsg(t));
            }
        });
    }

    public interface ResendCallback {
        void onResult(boolean ok, @Nullable String error);
    }

    interface ProfilesApi {
        @GET("rest/v1/profiles")
        @Headers("Accept: application/json")
        Call<List<ProfileStateRow>> getProfileState(
                @Query("select") String select,
                @Query("id") String idEq
        );
    }

    static class ProfileStateRow {
        @SerializedName("profile_state") String profileState;
        @SerializedName("updated_at") String updatedAt; // не используем сейчас, но пусть будет
    }

    interface AuthResendApi {
        @POST("auth/v1/resend")
        @Headers({
                "Accept: application/json",
                "Content-Type: application/json"
        })
        Call<Void> resend(@Body ResendBody body);
    }

    static class ResendBody {
        @SerializedName("type") String type;
        @SerializedName("email") String email;
    }

    @Nullable
    private static String safeTrim(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @NonNull
    private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }
}