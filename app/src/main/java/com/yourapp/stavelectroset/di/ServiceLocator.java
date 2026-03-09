package com.yourapp.stavelectroset.di;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.room.Room;

import com.yourapp.stavelectroset.BuildConfig;
import com.yourapp.stavelectroset.db.AppDb;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.ProfileRepository;
import com.yourapp.stavelectroset.feature.news.data.remote.NewsLoader;
import com.yourapp.stavelectroset.feature.news.domain.NewsRepository;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class ServiceLocator {

    private static final String TAG = "ServiceLocator";
    private static volatile ServiceLocator instance;

    private static final String DB_NAME = "app.db";
    private static final String NEWS_BASE_URL = "https://stavels.ru/";

    private final Context appContext;
    private final AppDb db;
    private final ExecutorService ioExecutor;
    private final Retrofit newsRetrofit;

    /** Retrofit на корень проекта (https://xxx.supabase.co/) — оставили как было, чтобы ничего не ломать */
    private volatile Retrofit supabaseRetrofit;

    /** Новые Retrofit’ы с правильными baseUrl */
    private volatile Retrofit supabaseRestRetrofit;
    private volatile Retrofit supabaseStorageRetrofit;
    private volatile Retrofit supabaseFunctionsRetrofit;

    /**
     * ✅ ВАЖНО: переименовал поле, чтобы убрать потенциальную неоднозначность
     * (IDE/Gradle иногда путают method/property при одинаковых именах).
     */
    private volatile OkHttpClient supabaseOkHttpClient;

    private volatile String supabaseBaseUrlCached;

    private volatile NewsRepository newsRepository;
    private volatile AuthRepository authRepository;
    private volatile ProfileRepository profileRepository;

    private ServiceLocator(@NonNull Context appContext) {
        this.appContext = appContext.getApplicationContext();

        this.db = Room.databaseBuilder(this.appContext, AppDb.class, DB_NAME)
                .fallbackToDestructiveMigration()
                .build();

        this.ioExecutor = Executors.newFixedThreadPool(2, new NamedThreadFactory("io"));

        OkHttpClient newsOkHttp = createNewsOkHttpClient();
        this.newsRetrofit = new Retrofit.Builder()
                .baseUrl(NEWS_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(newsOkHttp)
                .build();
    }

    @NonNull
    public static ServiceLocator get(@NonNull Context context) {
        Objects.requireNonNull(context, "context == null");
        if (instance == null) {
            synchronized (ServiceLocator.class) {
                if (instance == null) {
                    instance = new ServiceLocator(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    @NonNull
    public AppDb db() {
        return db;
    }

    @NonNull
    public ExecutorService ioExecutor() {
        return ioExecutor;
    }

    @NonNull
    public Retrofit retrofit() {
        return newsRetrofit;
    }

    /**
     * ✅ Корень Supabase вида https://xxx.supabase.co/
     */
    @NonNull
    public String supabaseBaseUrl() {
        String local = supabaseBaseUrlCached;
        if (local == null) {
            synchronized (this) {
                local = supabaseBaseUrlCached;
                if (local == null) {
                    validateSupabaseConfig();
                    local = normalizeSupabaseBaseUrl(BuildConfig.SUPABASE_URL);
                    supabaseBaseUrlCached = local;
                }
            }
        }
        return local;
    }

    /**
     * ✅ Публичный доступ к OkHttp (с apikey + Authorization)
     */
    @NonNull
    public OkHttpClient supabaseOkHttp() {
        return getSupabaseOkHttpClient();
    }

    /**
     * ✅ Оставили как было: Retrofit с baseUrl = https://xxx.supabase.co/
     */
    @NonNull
    public Retrofit supabaseRetrofit() {
        Retrofit local = supabaseRetrofit;
        if (local == null) {
            synchronized (this) {
                local = supabaseRetrofit;
                if (local == null) {
                    validateSupabaseConfig();

                    OkHttpClient ok = getSupabaseOkHttpClient();
                    String base = supabaseBaseUrl();

                    local = new Retrofit.Builder()
                            .baseUrl(base)
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(ok)
                            .build();

                    supabaseRetrofit = local;
                }
            }
        }
        return local;
    }

    /**
     * ✅ Новый: Retrofit для PostgREST/RPC:
     * baseUrl = https://xxx.supabase.co/rest/v1/
     */
    @NonNull
    public Retrofit supabaseRestRetrofit() {
        Retrofit local = supabaseRestRetrofit;
        if (local == null) {
            synchronized (this) {
                local = supabaseRestRetrofit;
                if (local == null) {
                    validateSupabaseConfig();

                    OkHttpClient ok = getSupabaseOkHttpClient();
                    String base = supabaseBaseUrl() + "rest/v1/";

                    local = new Retrofit.Builder()
                            .baseUrl(base)
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(ok)
                            .build();

                    supabaseRestRetrofit = local;
                }
            }
        }
        return local;
    }

    /**
     * ✅ Новый: Retrofit для Storage:
     * baseUrl = https://xxx.supabase.co/storage/v1/
     */
    @NonNull
    public Retrofit supabaseStorageRetrofit() {
        Retrofit local = supabaseStorageRetrofit;
        if (local == null) {
            synchronized (this) {
                local = supabaseStorageRetrofit;
                if (local == null) {
                    validateSupabaseConfig();

                    OkHttpClient ok = getSupabaseOkHttpClient();
                    String base = supabaseBaseUrl() + "storage/v1/";

                    local = new Retrofit.Builder()
                            .baseUrl(base)
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(ok)
                            .build();

                    supabaseStorageRetrofit = local;
                }
            }
        }
        return local;
    }

    /**
     * ✅ Новый: Retrofit для Edge Functions:
     * baseUrl = https://xxx.supabase.co/functions/v1/
     */
    @NonNull
    public Retrofit supabaseFunctionsRetrofit() {
        Retrofit local = supabaseFunctionsRetrofit;
        if (local == null) {
            synchronized (this) {
                local = supabaseFunctionsRetrofit;
                if (local == null) {
                    validateSupabaseConfig();

                    OkHttpClient ok = getSupabaseOkHttpClient();
                    String base = supabaseBaseUrl() + "functions/v1/";

                    local = new Retrofit.Builder()
                            .baseUrl(base)
                            .addConverterFactory(GsonConverterFactory.create())
                            .client(ok)
                            .build();

                    supabaseFunctionsRetrofit = local;
                }
            }
        }
        return local;
    }

    // ===== Repositories =====

    @NonNull
    public NewsRepository newsRepository() {
        NewsRepository local = newsRepository;
        if (local == null) {
            synchronized (this) {
                local = newsRepository;
                if (local == null) {
                    local = new NewsRepository(
                            db.news(),
                            new NewsLoader(newsRetrofit),
                            ioExecutor
                    );
                    newsRepository = local;
                }
            }
        }
        return local;
    }

    @NonNull
    public AuthRepository authRepository() {
        AuthRepository local = authRepository;
        if (local == null) {
            synchronized (this) {
                local = authRepository;
                if (local == null) {
                    local = new AuthRepository(appContext);
                    authRepository = local;
                }
            }
        }
        return local;
    }

    @NonNull
    public ProfileRepository profileRepository() {
        ProfileRepository local = profileRepository;
        if (local == null) {
            synchronized (this) {
                local = profileRepository;
                if (local == null) {
                    local = new ProfileRepository(appContext);
                    profileRepository = local;
                }
            }
        }
        return local;
    }

    // ===== OkHttp clients =====

    @NonNull
    private OkHttpClient createNewsOkHttpClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BODY
                : HttpLoggingInterceptor.Level.NONE);

        Interceptor headers = chain -> {
            Request original = chain.request();
            Request req = original.newBuilder()
                    .header("User-Agent", "StavElectrosetApp/1.0 (Android)")
                    .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
                    .build();
            return chain.proceed(req);
        };

        Interceptor responseCodeLogger = chain -> {
            Response res = chain.proceed(chain.request());
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "NEWS HTTP " + res.code() + " " + res.request().method() + " " + res.request().url());
            }
            return res;
        };

        return new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(headers)
                .addInterceptor(responseCodeLogger)
                .addInterceptor(logging)
                .build();
    }

    @NonNull
    private OkHttpClient getSupabaseOkHttpClient() {
        OkHttpClient ok = supabaseOkHttpClient;
        if (ok == null) {
            synchronized (this) {
                ok = supabaseOkHttpClient;
                if (ok == null) {
                    ok = createSupabaseOkHttpClient();
                    supabaseOkHttpClient = ok;
                }
            }
        }
        return ok;
    }

    @NonNull
    private OkHttpClient createSupabaseOkHttpClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BODY
                : HttpLoggingInterceptor.Level.NONE);

        try {
            logging.redactHeader("Authorization");
            logging.redactHeader("apikey");
        } catch (Exception ignored) {}

        Interceptor headers = chain -> {
            Request original = chain.request();
            String path = original.url().encodedPath();
            String fullUrl = original.url().toString();

            String rawAccess = readAccessTokenSafe();
            String accessTrim = (rawAccess == null) ? null : rawAccess.trim();
            boolean hasAccess = accessTrim != null && !accessTrim.isEmpty();

            // Если есть access_token — используем его, иначе работаем как anon.
            String bearerToken = hasAccess ? accessTrim : BuildConfig.SUPABASE_ANON_KEY;

            Request.Builder b = original.newBuilder()
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Accept", "application/json");

            if (BuildConfig.DEBUG) {
                boolean isAuth = path != null && path.startsWith("/auth/v1/");
                boolean isStorage = path != null && path.startsWith("/storage/v1/");
                boolean isRest = path != null && path.startsWith("/rest/v1/");
                boolean isFn = path != null && path.startsWith("/functions/v1/");

                Log.d(TAG, "SUPABASE headers: mode=" + (hasAccess ? "access" : "anon")
                        + " auth=" + isAuth + " rest=" + isRest + " storage=" + isStorage + " fn=" + isFn
                        + " url=" + fullUrl);

                if (isFn) {
                    logTokenDiagnostics("fn", rawAccess, bearerToken);
                }
            }

            return chain.proceed(b.build());
        };

        Interceptor responseCodeLogger = chain -> {
            Response res = chain.proceed(chain.request());
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SUPABASE HTTP " + res.code() + " " + res.request().method() + " " + res.request().url());
            }
            return res;
        };

        return new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(40, TimeUnit.SECONDS)
                .addInterceptor(headers)
                .addInterceptor(responseCodeLogger)
                .addInterceptor(logging)
                .build();
    }

    private static void logTokenDiagnostics(@NonNull String tagSuffix,
                                            @Nullable String rawAccessFromStore,
                                            @NonNull String tokenActuallyUsed) {
        try {
            boolean looksJwt = tokenActuallyUsed.startsWith("eyJ");
            boolean hasBearerInside = tokenActuallyUsed.contains("Bearer ");
            int len = tokenActuallyUsed.length();

            String head = tokenActuallyUsed.length() >= 8 ? tokenActuallyUsed.substring(0, 8) : tokenActuallyUsed;
            String tail = tokenActuallyUsed.length() >= 8 ? tokenActuallyUsed.substring(tokenActuallyUsed.length() - 8) : tokenActuallyUsed;

            boolean rawHasNl = rawAccessFromStore != null && (rawAccessFromStore.contains("\n") || rawAccessFromStore.contains("\r"));
            int rawLen = rawAccessFromStore == null ? 0 : rawAccessFromStore.length();
            int trimmedLen = rawAccessFromStore == null ? 0 : rawAccessFromStore.trim().length();

            Log.d(TAG, "SUPABASE token(" + tagSuffix + "): looksJwt=" + looksJwt
                    + " hasBearerInside=" + hasBearerInside
                    + " len=" + len
                    + " head=" + head
                    + " tail=" + tail);

            Log.d(TAG, "SUPABASE token(" + tagSuffix + "): rawLen=" + rawLen
                    + " trimmedLen=" + trimmedLen
                    + " rawHasNewLine=" + rawHasNl);

            JwtPayload p = decodeJwtPayload(tokenActuallyUsed);
            if (p != null) {
                Log.d(TAG, "SUPABASE token(" + tagSuffix + "): sub=" + safeShort(p.sub)
                        + " exp=" + p.exp);
            } else {
                Log.d(TAG, "SUPABASE token(" + tagSuffix + "): payload decode failed");
            }
        } catch (Exception e) {
            Log.w(TAG, "logTokenDiagnostics failed", e);
        }
    }

    private static class JwtPayload {
        String sub;
        long exp;
    }

    @Nullable
    private static JwtPayload decodeJwtPayload(@NonNull String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String payloadB64 = parts[1];

            byte[] decoded = Base64.decode(payloadB64, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String json = new String(decoded, StandardCharsets.UTF_8);

            JSONObject obj = new JSONObject(json);
            JwtPayload p = new JwtPayload();
            p.sub = obj.optString("sub", null);
            p.exp = obj.optLong("exp", 0L);
            return p;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String safeShort(@Nullable String s) {
        if (s == null) return "null";
        return s.length() <= 10 ? s : (s.substring(0, 6) + "..." + s.substring(s.length() - 4));
    }

    @Nullable
    private String readAccessTokenSafe() {
        try {
            return authRepository().getAccessToken();
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "readAccessTokenSafe failed", e);
            return null;
        }
    }

    private void validateSupabaseConfig() {
        String url = BuildConfig.SUPABASE_URL;
        String key = BuildConfig.SUPABASE_ANON_KEY;

        if (url == null || url.trim().isEmpty()) {
            throw new IllegalStateException("BuildConfig.SUPABASE_URL пустой");
        }
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("BuildConfig.SUPABASE_ANON_KEY пустой");
        }
        if (!url.trim().startsWith("https://")) {
            throw new IllegalStateException("SUPABASE_URL должен начинаться с https://");
        }
    }

    private static String normalizeSupabaseBaseUrl(@NonNull String url) {
        String t = url.trim();
        String[] cut = new String[]{"/rest/v1", "/auth/v1", "/storage/v1", "/functions/v1"};
        for (String c : cut) {
            int idx = t.indexOf(c);
            if (idx > 0) {
                t = t.substring(0, idx);
                break;
            }
        }
        return ensureTrailingSlash(t);
    }

    private static String ensureTrailingSlash(String url) {
        if (url == null) return "";
        String t = url.trim();
        return t.endsWith("/") ? t : t + "/";
    }

    @VisibleForTesting
    public static void resetForTests() {
        instance = null;
    }

    @VisibleForTesting
    public void shutdown() {
        try { ioExecutor.shutdownNow(); } catch (Exception ignored) {}
        try { db.close(); } catch (Exception ignored) {}
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger idx = new AtomicInteger(1);

        private NamedThreadFactory(@NonNull String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, prefix + "-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}