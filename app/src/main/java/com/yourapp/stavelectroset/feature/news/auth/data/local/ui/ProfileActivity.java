package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.MainActivity;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.databinding.ActivityProfileBinding;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.ProfileRepository;
import com.yourapp.stavelectroset.feature.news.consumers.ConsumersMapActivity;
import com.yourapp.stavelectroset.web.WebPageActivity;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;

import androidx.annotation.DimenRes;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.Query;

public class ProfileActivity extends AppCompatActivity {

    private static final String PREFS_PROFILE = "profile";
    private static final String KEY_AVATAR_URI = "avatar_uri";
    private static final String KEY_ROLE_CACHE = "role_cache";

    // ✅ Экран писем
    private static final String INFO_LETTERS_ACTIVITY =
            "com.yourapp.stavelectroset.feature.news.auth.data.local.ui.InfoLettersActivity";

    public static final String EXTRA_OPEN_LETTER_ID = "open_letter_id";

    // profiles (+ profile_state)
    private static final String PROFILE_SELECT =
            "full_name,login,phone,account_number,meter_serials,subscriber_type,role,profile_state";

    // info letters (published_at есть)
    private static final String LETTERS_SELECT =
            "id,title,body,published_at";

    // my requests
    private static final String REQUESTS_SELECT =
            "id,topic,status,created_at";

    // my complaints
    private static final String COMPLAINTS_SELECT =
            "id,topic,status,created_at";

    private ActivityProfileBinding binding;

    private AuthRepository authRepository;
    private ProfilesApi profilesApi;
    private InfoLettersApi infoLettersApi;
    private RequestsApi requestsApi;
    private ComplaintsApi complaintsApi;

    private ProfileRepository profileRepository;
    private ProfileStateGuard profileStateGuard;

    private ActivityResultLauncher<String[]> pickAvatarLauncher;

    private final AtomicBoolean profileRetryAfterRefreshUsed = new AtomicBoolean(false);
    private final AtomicBoolean lettersRetryAfterRefreshUsed = new AtomicBoolean(false);

    @Nullable private volatile Call<List<ProfileRow>> inFlightProfileCall;
    @Nullable private volatile Call<List<InfoLetterRow>> inFlightLettersCall;
    @Nullable private volatile Call<List<RequestRow>> inFlightRequestsCall;
    @Nullable private volatile Call<List<ComplaintRow>> inFlightComplaintsCall;

    private InfoLettersAdapter lettersAdapter;
    private RequestsAdapter requestsAdapter;
    private ComplaintsAdapter complaintsAdapter;

    // ✅ FIX: чтобы старые ответы не "перетирали" UI
    private final AtomicLong profileReqSeq = new AtomicLong(0);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        setTitle("Личный кабинет");

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();

        // ✅ baseUrl уже .../rest/v1/
        profilesApi = sl.supabaseRestRetrofit().create(ProfilesApi.class);
        infoLettersApi = sl.supabaseRestRetrofit().create(InfoLettersApi.class);
        requestsApi = sl.supabaseRestRetrofit().create(RequestsApi.class);
        complaintsApi = sl.supabaseRestRetrofit().create(ComplaintsApi.class);

        profileRepository = sl.profileRepository();
        profileStateGuard = new ProfileStateGuard(getApplicationContext());

        initAvatar();
        initButtons();
        initBottomNav();
        initMyRequestsUi();
        initMyComplaintsUi();
        initInfoLettersUi();

        // ✅ НОВОЕ: кнопка "Открыть все письма"
        initOpenAllLettersButton();

        applyRoleUi(readCachedRole());

        ensureSessionThenLoadAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        softRefreshProfileState();
        reloadProfileOnly();
        String toast = getIntent().getStringExtra("toast");
        if (toast != null && !toast.trim().isEmpty()) {
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
            getIntent().removeExtra("toast");
        }
    }

    // ===== Toolbar menu (⚙) =====

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_profile, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_profile_settings) {
            openProfileSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openProfileSettings() {
        startActivity(new Intent(this, ProfileSettingsActivity.class));
    }

    // ✅ НОВОЕ: обработчик кнопки "Открыть все письма"
    private void initOpenAllLettersButton() {
        if (binding == null) return;
        if (binding.btnOpenInfoLetters == null) return;

        binding.btnOpenInfoLetters.setOnClickListener(v -> openInfoLettersActivity(null));
    }

    private void openInfoLettersActivity(@Nullable String letterId) {
        try {
            Class<?> cls = Class.forName(INFO_LETTERS_ACTIVITY);
            Intent i = new Intent(this, cls);
            if (letterId != null && !letterId.trim().isEmpty()) {
                i.putExtra(EXTRA_OPEN_LETTER_ID, letterId);
            }
            startActivity(i);
        } catch (Exception e) {
            toast("Экран писем не найден (проверь Manifest)");
        }
    }

    // ===== Avatar =====

    private void initAvatar() {
        SharedPreferences prefs = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE);
        String savedAvatar = prefs.getString(KEY_AVATAR_URI, null);

        if (!isEmpty(savedAvatar) && binding.ivAvatar != null) {
            try { binding.ivAvatar.setImageURI(Uri.parse(savedAvatar)); } catch (Exception ignored) {}
        }

        pickAvatarLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null || binding == null) return;

                    try {
                        final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(uri, flags);
                    } catch (Exception ignored) {}

                    try {
                        binding.ivAvatar.setImageURI(null);
                        binding.ivAvatar.setImageURI(uri);
                    } catch (Exception ignored) {}

                    getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_AVATAR_URI, uri.toString())
                            .apply();
                }
        );

        if (binding.ivAvatar != null) {
            binding.ivAvatar.setOnClickListener(v -> {
                if (pickAvatarLauncher != null) {
                    pickAvatarLauncher.launch(new String[]{"image/*"});
                }
            });
        }
    }

    private void initButtons() {
        if (binding.btnLogout != null) {
            binding.btnLogout.setOnClickListener(v -> {
                authRepository.logout();
                if (profileRepository != null) profileRepository.clearCache();
                toast("Вы вышли");
                goToLoginAndClearTask();
            });
        }

        if (binding.btnRequest != null) {
            binding.btnRequest.setOnClickListener(v -> {
                if (profileStateGuard == null) return;
                profileStateGuard.guardOrBlock(this, () ->
                        startActivity(new Intent(this, RequestCreateActivity.class))
                );
            });
        }

        if (binding.btnComplaint != null) {
            binding.btnComplaint.setOnClickListener(v -> {
                if (profileStateGuard == null) return;
                profileStateGuard.guardOrBlock(this, () ->
                        startActivity(new Intent(this, ComplaintCreateActivity.class))
                );
            });
        }

        if (binding.btnAdmin != null) {
            binding.btnAdmin.setOnClickListener(v -> {
                startActivity(new Intent(this, AdminDashboardActivity.class));
            });
        }
    }

    private void initBottomNav() {
        BottomNavigationView bottomNav = binding.bottomNav;
        if (bottomNav == null) return;

        try {
            bottomNav.setSelectedItemId(R.id.nav_profile);
        } catch (Exception ignored) {}

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_news) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
                return true;
            }

            if (id == R.id.nav_map) {
                try {
                    startActivity(new Intent(this, ConsumersMapActivity.class));
                } catch (Exception e) {
                    startActivity(
                            new Intent(this, WebPageActivity.class)
                                    .putExtra("open_tab", "company")
                    );
                }
                finish();
                return true;
            }

            return id == R.id.nav_profile;
        });

        bottomNav.setOnItemReselectedListener(item -> {
            // ничего не делаем, чтобы не было лишних перезапусков текущего top-level экрана
        });
    }

    private void initMyRequestsUi() {
        if (binding.rvMyRequests == null) return;

        binding.rvMyRequests.setLayoutManager(new LinearLayoutManager(this));
        requestsAdapter = new RequestsAdapter(this::openRequestDetails);
        binding.rvMyRequests.setAdapter(requestsAdapter);

        showRequestsEmpty(true);
    }

    private void initMyComplaintsUi() {
        if (binding.rvMyComplaints == null) return;

        binding.rvMyComplaints.setLayoutManager(new LinearLayoutManager(this));
        complaintsAdapter = new ComplaintsAdapter(this::openComplaintDetails);
        binding.rvMyComplaints.setAdapter(complaintsAdapter);

        showComplaintsEmpty(true);
    }

    private void initInfoLettersUi() {
        if (binding.rvInfoLetters == null) return;

        binding.rvInfoLetters.setLayoutManager(new LinearLayoutManager(this));

        // ✅ Клик по карточке письма -> открываем экран писем и авт-открываем письмо по id
        lettersAdapter = new InfoLettersAdapter(letter -> openInfoLettersActivity(letter == null ? null : letter.id));
        binding.rvInfoLetters.setAdapter(lettersAdapter);

        showLettersEmpty(true);
    }

    // ===== load all =====

    private void ensureSessionThenLoadAll() {
        setLoading(true);

        if (!isEmpty(authRepository.getAccessToken())) {
            setLoading(false);
            loadAll();
            return;
        }

        authRepository.refresh(result -> runOnUiThread(() -> {
            setLoading(false);
            if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                authRepository.logout();
                goToLoginAndClearTask();
                return;
            }
            loadAll();
        }));
    }

    private void loadAll() {
        String accessToken = authRepository.getAccessToken();
        if (isEmpty(accessToken)) {
            authRepository.logout();
            goToLoginAndClearTask();
            return;
        }

        String uid = authRepository.getUserUid();
        if (isEmpty(uid)) uid = jwtGetString(accessToken, "sub");

        if (isEmpty(uid)) {
            toast("Не удалось прочитать сессию, войдите заново");
            authRepository.logout();
            goToLoginAndClearTask();
            return;
        }

        String email = jwtGetString(accessToken, "email");
        if (binding != null && binding.tvEmail != null) {
            if (!isEmpty(email)) {
                binding.tvEmail.setText("Почта: " + email);
                binding.tvEmail.setVisibility(View.VISIBLE);
            } else {
                binding.tvEmail.setVisibility(View.GONE);
            }
        }

        softRefreshProfileState();

        fetchProfileFromSupabase(uid);
        fetchMyRequests(uid);
        fetchMyComplaints(uid);
        fetchInfoLetters();
    }

    private void softRefreshProfileState() {
        if (profileRepository == null) return;

        profileRepository.refresh(result -> runOnUiThread(() -> {
            applyStateToButtons(profileRepository.getCachedIfFresh(ProfileRepository.DEFAULT_TTL_MS));
        }));
    }

    private void applyStateToButtons(@NonNull ProfileRepository.ProfileState st) {
        if (binding == null) return;
    }

    private void reloadProfileOnly() {
        String accessToken = authRepository.getAccessToken();
        if (isEmpty(accessToken)) return;

        String uid = authRepository.getUserUid();
        if (isEmpty(uid)) uid = jwtGetString(accessToken, "sub");
        if (isEmpty(uid)) return;

        fetchProfileFromSupabase(uid);
    }

    // ===== profile =====

    private void fetchProfileFromSupabase(@NonNull String uid) {
        final long reqId = profileReqSeq.incrementAndGet();
        setLoading(true);

        Call<List<ProfileRow>> call = profilesApi.getProfile(PROFILE_SELECT, "eq." + uid);
        inFlightProfileCall = call;

        call.enqueue(new Callback<List<ProfileRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ProfileRow>> call,
                                   @NonNull Response<List<ProfileRow>> response) {
                if (binding == null) return;
                if (reqId != profileReqSeq.get()) return;

                if (response.code() == 401 || response.code() == 403) {
                    if (!profileRetryAfterRefreshUsed.getAndSet(true)) {
                        refreshAndRetryProfile(uid);
                        return;
                    }
                }

                if (!response.isSuccessful()) {
                    setLoading(false);
                    toast("Ошибка профиля: " + response.code());
                    return;
                }

                List<ProfileRow> rows = response.body();
                if (rows == null || rows.isEmpty()) {
                    setLoading(false);
                    toast("Профиль не найден (проверь trigger/RLS)");
                    return;
                }

                profileRetryAfterRefreshUsed.set(false);
                setLoading(false);
                renderProfile(rows.get(0));
            }

            @Override
            public void onFailure(@NonNull Call<List<ProfileRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                runOnUiThread(() -> {
                    if (binding == null) return;
                    if (reqId != profileReqSeq.get()) return;
                    setLoading(false);
                    toast("Сеть (профиль): " + safeMsg(t));
                });
            }
        });
    }

    private void renderProfile(@NonNull ProfileRow p) {
        if (binding == null) return;

        if (binding.tvFullName != null) binding.tvFullName.setText("ФИО: " + safe(p.fullName));
        if (binding.tvLogin != null) binding.tvLogin.setText("Логин: " + safe(p.login));

        if (binding.tvPhone != null) {
            if (!isEmpty(p.phone)) {
                binding.tvPhone.setText("Телефон: " + safe(p.phone));
                binding.tvPhone.setVisibility(View.VISIBLE);
            } else binding.tvPhone.setVisibility(View.GONE);
        }

        if (binding.tvAccountNumber != null) {
            if (!isEmpty(p.accountNumber)) {
                binding.tvAccountNumber.setText("Лицевой счёт: " + safe(p.accountNumber));
                binding.tvAccountNumber.setVisibility(View.VISIBLE);
            } else binding.tvAccountNumber.setVisibility(View.GONE);
        }

        if (binding.tvMeterSerials != null) {
            if (!isEmpty(p.meterSerials)) {
                binding.tvMeterSerials.setText("Счётчики: " + safe(p.meterSerials));
                binding.tvMeterSerials.setVisibility(View.VISIBLE);
            } else binding.tvMeterSerials.setVisibility(View.GONE);
        }

        if (binding.tvSubscriberType != null) {
            if (!isEmpty(p.subscriberType)) {
                binding.tvSubscriberType.setText("Тип абонента: " + safe(p.subscriberType));
                binding.tvSubscriberType.setVisibility(View.VISIBLE);
            } else binding.tvSubscriberType.setVisibility(View.GONE);
        }

        String role = normalizeRole(p.role);
        cacheRole(role);
        applyRoleUi(role);

        if (profileRepository != null) {
            ProfileRepository.ProfileState st = ProfileRepository.ProfileState.from(p.profileState);
            profileRepository.saveCache(st);
            applyStateToButtons(st);
        }
    }

    private void refreshAndRetryProfile(@NonNull String uid) {
        authRepository.refresh(result -> runOnUiThread(() -> {
            if (binding == null) return;

            if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                toast("Сессия истекла, войдите заново");
                authRepository.logout();
                goToLoginAndClearTask();
                return;
            }

            fetchProfileFromSupabase(uid);
        }));
    }

    // ===== my requests =====

    private void fetchMyRequests(@NonNull String uid) {
        Call<List<RequestRow>> old = inFlightRequestsCall;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) {} }

        showRequestsEmpty(false);

        Call<List<RequestRow>> call = requestsApi.listMyRequests(
                REQUESTS_SELECT,
                "eq." + uid,
                "created_at.desc"
        );
        inFlightRequestsCall = call;

        call.enqueue(new Callback<List<RequestRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<RequestRow>> call,
                                   @NonNull Response<List<RequestRow>> response) {
                if (binding == null) return;

                if (!response.isSuccessful()) {
                    showRequestsEmpty(true);
                    return;
                }

                List<RequestRow> rows = response.body();
                if (rows == null) rows = new ArrayList<>();

                if (requestsAdapter != null) requestsAdapter.submit(rows);
                showRequestsEmpty(rows.isEmpty());
            }

            @Override
            public void onFailure(@NonNull Call<List<RequestRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                runOnUiThread(() -> {
                    if (binding == null) return;
                    showRequestsEmpty(true);
                });
            }
        });
    }

    private void showRequestsEmpty(boolean empty) {
        if (binding == null) return;

        if (binding.tvMyRequestsEmpty != null) {
            binding.tvMyRequestsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (binding.rvMyRequests != null) {
            binding.rvMyRequests.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void openRequestDetails(@NonNull RequestRow request) {
        Intent i = new Intent(this, RequestDetailsActivity.class);
        i.putExtra(RequestDetailsActivity.EXTRA_REQUEST_ID, request.id);
        startActivity(i);
    }

    // ===== my complaints =====

    private void fetchMyComplaints(@NonNull String uid) {
        Call<List<ComplaintRow>> old = inFlightComplaintsCall;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) {} }

        showComplaintsEmpty(false);

        Call<List<ComplaintRow>> call = complaintsApi.listMyComplaints(
                COMPLAINTS_SELECT,
                "eq." + uid,
                "created_at.desc"
        );
        inFlightComplaintsCall = call;

        call.enqueue(new Callback<List<ComplaintRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ComplaintRow>> call,
                                   @NonNull Response<List<ComplaintRow>> response) {
                if (binding == null) return;

                if (!response.isSuccessful()) {
                    showComplaintsEmpty(true);
                    return;
                }

                List<ComplaintRow> rows = response.body();
                if (rows == null) rows = new ArrayList<>();

                if (complaintsAdapter != null) complaintsAdapter.submit(rows);
                showComplaintsEmpty(rows.isEmpty());
            }

            @Override
            public void onFailure(@NonNull Call<List<ComplaintRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                runOnUiThread(() -> {
                    if (binding == null) return;
                    showComplaintsEmpty(true);
                });
            }
        });
    }

    private void showComplaintsEmpty(boolean empty) {
        if (binding == null) return;

        if (binding.tvMyComplaintsEmpty != null) {
            binding.tvMyComplaintsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (binding.rvMyComplaints != null) {
            binding.rvMyComplaints.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void openComplaintDetails(@NonNull ComplaintRow complaint) {
        Intent i = new Intent(this, ComplaintDetailsActivity.class);
        i.putExtra(ComplaintDetailsActivity.EXTRA_COMPLAINT_ID, complaint.id);
        startActivity(i);
    }

    // ===== info letters =====

    private void fetchInfoLetters() {
        Call<List<InfoLetterRow>> old = inFlightLettersCall;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) {} }

        showLettersEmpty(false);

        Call<List<InfoLetterRow>> call = infoLettersApi.listLetters(LETTERS_SELECT, "published_at.desc", 50);
        inFlightLettersCall = call;

        call.enqueue(new Callback<List<InfoLetterRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<InfoLetterRow>> call,
                                   @NonNull Response<List<InfoLetterRow>> response) {
                if (binding == null) return;

                if (response.code() == 401 || response.code() == 403) {
                    if (!lettersRetryAfterRefreshUsed.getAndSet(true)) {
                        refreshAndRetryLetters();
                        return;
                    }
                }

                if (!response.isSuccessful()) {
                    showLettersEmpty(true);
                    return;
                }

                lettersRetryAfterRefreshUsed.set(false);

                List<InfoLetterRow> rows = response.body();
                if (rows == null) rows = new ArrayList<>();

                if (lettersAdapter != null) lettersAdapter.submit(rows);
                showLettersEmpty(rows.isEmpty());
            }

            @Override
            public void onFailure(@NonNull Call<List<InfoLetterRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                runOnUiThread(() -> {
                    if (binding == null) return;
                    showLettersEmpty(true);
                });
            }
        });
    }

    private void refreshAndRetryLetters() {
        authRepository.refresh(result -> runOnUiThread(() -> {
            if (binding == null) return;

            if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                showLettersEmpty(true);
                return;
            }
            fetchInfoLetters();
        }));
    }

    private void showLettersEmpty(boolean empty) {
        if (binding == null) return;

        if (binding.tvInfoLettersEmpty != null) {
            binding.tvInfoLettersEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (binding.rvInfoLetters != null) {
            binding.rvInfoLetters.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    // ===== Role UI =====

    private void applyRoleUi(@Nullable String roleRaw) {
        if (binding == null || binding.btnAdmin == null) return;

        String role = roleRaw == null ? "" : roleRaw.trim().toLowerCase(Locale.ROOT);
        boolean isStaff = role.equals("owner") || role.equals("deputy") || role.equals("admin");

        binding.btnAdmin.setVisibility(isStaff ? View.VISIBLE : View.GONE);
        if (isStaff) binding.btnAdmin.setText("Админ-панель");
    }

    @NonNull
    private String normalizeRole(@Nullable String role) {
        if (role == null) return "user";
        String r = role.trim().toLowerCase(Locale.ROOT);
        return r.isEmpty() ? "user" : r;
    }

    private void cacheRole(@NonNull String role) {
        getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE)
                .edit()
                .putString(KEY_ROLE_CACHE, role)
                .apply();
    }

    @Nullable
    private String readCachedRole() {
        return getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE).getString(KEY_ROLE_CACHE, null);
    }

    // ===== UI =====

    private void setLoading(boolean loading) {
        if (binding == null) return;

        if (binding.btnLogout != null) binding.btnLogout.setEnabled(!loading);
        if (binding.btnRequest != null) binding.btnRequest.setEnabled(!loading);
        if (binding.btnComplaint != null) binding.btnComplaint.setEnabled(!loading);
        if (binding.btnAdmin != null) binding.btnAdmin.setEnabled(!loading);
        if (binding.btnOpenInfoLetters != null) binding.btnOpenInfoLetters.setEnabled(!loading);
    }

    private void toast(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void goToLoginAndClearTask() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        try { finish(); } catch (Exception ignored) {}
    }

    // ===== Retrofit API + DTO =====

    interface ProfilesApi {
        @GET("profiles")
        @Headers("Accept: application/json")
        Call<List<ProfileRow>> getProfile(
                @Query("select") String select,
                @Query("id") String idEq
        );
    }

    interface InfoLettersApi {
        @GET("info_letters_feed")
        @Headers("Accept: application/json")
        Call<List<InfoLetterRow>> listLetters(
                @Query("select") String select,
                @Query("order") String order,
                @Query("limit") Integer limit
        );
    }

    interface RequestsApi {
        @GET("requests")
        @Headers("Accept: application/json")
        Call<List<RequestRow>> listMyRequests(
                @Query("select") String select,
                @Query("user_id") String userIdEq,
                @Query("order") String order
        );
    }

    interface ComplaintsApi {
        @GET("complaints")
        @Headers("Accept: application/json")
        Call<List<ComplaintRow>> listMyComplaints(
                @Query("select") String select,
                @Query("user_id") String userIdEq,
                @Query("order") String order
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

    static class InfoLetterRow {
        @SerializedName("id") String id;
        @SerializedName("title") String title;
        @SerializedName("body") String body;
        @SerializedName("published_at") String publishedAt;
    }

    static class RequestRow {
        @SerializedName("id") String id;
        @SerializedName("topic") String topic;
        @SerializedName("status") String status;
        @SerializedName("created_at") String createdAt;
    }

    static class ComplaintRow {
        @SerializedName("id") String id;
        @SerializedName("topic") String topic;
        @SerializedName("status") String status;
        @SerializedName("created_at") String createdAt;
    }

    // ===== Adapter: InfoLetters =====

    interface OnLetterClick {
        void onClick(@NonNull InfoLetterRow letter);
    }

    static class InfoLettersAdapter extends RecyclerView.Adapter<InfoLettersAdapter.VH> {

        private final List<InfoLetterRow> items = new ArrayList<>();
        private final OnLetterClick onClick;

        InfoLettersAdapter(@NonNull OnLetterClick onClick) {
            this.onClick = onClick;
        }

        void submit(@NonNull List<InfoLetterRow> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = createProfileListCard(parent);

            LinearLayout root = createProfileListCardRoot(parent);
            card.addView(root);

            TextView tvTitle = createProfileListTitle(parent);
            root.addView(tvTitle);

            TextView tvSub = createProfileListSub(parent);
            root.addView(tvSub);

            return new VH(card, tvTitle, tvSub);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            InfoLetterRow it = items.get(position);

            String title = (it.title == null || it.title.trim().isEmpty())
                    ? "Информационное письмо"
                    : it.title.trim();

            String sub = (it.publishedAt == null ? "" : it.publishedAt.trim());
            if (sub.isEmpty()) sub = "Нажмите, чтобы открыть";

            h.tvTitle.setText(title);
            h.tvSub.setText(sub);

            h.itemView.setOnClickListener(v -> onClick.onClick(it));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle;
            final TextView tvSub;

            VH(@NonNull View itemView, @NonNull TextView tvTitle, @NonNull TextView tvSub) {
                super(itemView);
                this.tvTitle = tvTitle;
                this.tvSub = tvSub;
            }
        }
    }

    // ===== Adapter: Requests =====
    interface OnRequestClick { void onClick(@NonNull RequestRow request); }

    static class RequestsAdapter extends RecyclerView.Adapter<RequestsAdapter.VH> {
        private final List<RequestRow> items = new ArrayList<>();
        private final OnRequestClick onClick;

        RequestsAdapter(@NonNull OnRequestClick onClick) { this.onClick = onClick; }

        void submit(@NonNull List<RequestRow> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = createProfileListCard(parent);

            LinearLayout root = createProfileListCardRoot(parent);
            card.addView(root);

            TextView tvTitle = createProfileListTitle(parent);
            root.addView(tvTitle);

            TextView tvSub = createProfileListSub(parent);
            root.addView(tvSub);

            return new VH(card, tvTitle, tvSub);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            RequestRow it = items.get(position);
            String title = topicRuRequest(it.topic);
            String sub = "Статус: " + statusRu(it.status);
            h.tvTitle.setText(title);
            h.tvSub.setText(sub);
            h.itemView.setOnClickListener(v -> onClick.onClick(it));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle; final TextView tvSub;
            VH(@NonNull View itemView, @NonNull TextView tvTitle, @NonNull TextView tvSub) {
                super(itemView); this.tvTitle = tvTitle; this.tvSub = tvSub;
            }
        }

    }

    // ===== Adapter: Complaints =====
    interface OnComplaintClick { void onClick(@NonNull ComplaintRow complaint); }

    static class ComplaintsAdapter extends RecyclerView.Adapter<ComplaintsAdapter.VH> {
        private final List<ComplaintRow> items = new ArrayList<>();
        private final OnComplaintClick onClick;

        ComplaintsAdapter(@NonNull OnComplaintClick onClick) { this.onClick = onClick; }

        void submit(@NonNull List<ComplaintRow> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = createProfileListCard(parent);

            LinearLayout root = createProfileListCardRoot(parent);
            card.addView(root);

            TextView tvTitle = createProfileListTitle(parent);
            root.addView(tvTitle);

            TextView tvSub = createProfileListSub(parent);
            root.addView(tvSub);

            return new VH(card, tvTitle, tvSub);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ComplaintRow it = items.get(position);
            String title = topicRuComplaint(it.topic);
            String sub = "Статус: " + statusRu(it.status);
            h.tvTitle.setText(title);
            h.tvSub.setText(sub);
            h.itemView.setOnClickListener(v -> onClick.onClick(it));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle; final TextView tvSub;
            VH(@NonNull View itemView, @NonNull TextView tvTitle, @NonNull TextView tvSub) {
                super(itemView); this.tvTitle = tvTitle; this.tvSub = tvSub;
            }
        }

    }

    // ===== Русификация: topic/status =====
    @NonNull
    private static String statusRu(@Nullable String statusRaw) {
        String s = normalize(statusRaw);
        switch (s) {
            case "draft": return "Черновик";
            case "new": return "Новая";
            case "in_progress": return "В работе";
            case "resolved": return "Решено";
            case "rejected": return "Отклонено";
            case "cancelled": return "Отменено";
            default: return s.isEmpty() ? "—" : s;
        }
    }

    @NonNull
    private static String topicRuRequest(@Nullable String topicRaw) {
        String t = normalize(topicRaw);
        switch (t) {
            case "tech_connection": return "Техприсоединение";
            case "power_restore": return "Восстановление электроэнергии";
            case "maintenance_repair": return "Ремонт / обслуживание";
            case "tenders_participation": return "Участие в тендерах";
            default: return t.isEmpty() ? "Заявка" : t;
        }
    }

    @NonNull
    private static String topicRuComplaint(@Nullable String topicRaw) {
        String t = normalize(topicRaw);
        switch (t) {
            case "illegal_disconnection": return "Незаконное отключение";
            case "power_violation": return "Нарушение электроснабжения";
            case "quality_voltage": return "Качество напряжения";
            case "metering_issue": return "Проблемы с учётом / счётчиком";
            case "billing": return "Начисления / оплата";
            case "power_outage": return "Отключение / перебои";
            case "other": return "Другое";
            default: return t.isEmpty() ? "Жалоба" : t;
        }
    }

    // ===== Utils =====
    private static boolean isEmpty(@Nullable String s) { return s == null || s.trim().isEmpty(); }
    @NonNull private static String safe(@Nullable String s) { return s == null ? "" : s; }
    @NonNull private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }
    @NonNull private static String normalize(@Nullable String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }

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

    private static int dimen(@NonNull ViewGroup parent, @DimenRes int resId) {
        return parent.getResources().getDimensionPixelSize(resId);
    }

    @NonNull
    private static MaterialCardView createProfileListCard(@NonNull ViewGroup parent) {
        MaterialCardView card = new MaterialCardView(parent.getContext());

        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dimen(parent, R.dimen.card_gap);
        card.setLayoutParams(lp);

        card.setUseCompatPadding(false);
        card.setCardElevation(0f);
        card.setRadius(dimen(parent, R.dimen.card_radius));
        card.setCardBackgroundColor(ContextCompat.getColor(parent.getContext(), R.color.app_card));
        card.setStrokeColor(ContextCompat.getColor(parent.getContext(), R.color.app_outline));
        card.setStrokeWidth(dimen(parent, R.dimen.stroke_thin));
        card.setRippleColor(ColorStateList.valueOf(
                ContextCompat.getColor(parent.getContext(), R.color.app_ripple)
        ));
        card.setClickable(true);
        card.setFocusable(true);

        return card;
    }

    @NonNull
    private static LinearLayout createProfileListCardRoot(@NonNull ViewGroup parent) {
        LinearLayout root = new LinearLayout(parent.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                dimen(parent, R.dimen.card_padding_horizontal),
                dimen(parent, R.dimen.card_padding_vertical),
                dimen(parent, R.dimen.card_padding_horizontal),
                dimen(parent, R.dimen.card_padding_vertical)
        );
        return root;
    }

    @NonNull
    private static TextView createProfileListTitle(@NonNull ViewGroup parent) {
        TextView tv = new TextView(parent.getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.app_text_primary));
        return tv;
    }

    @NonNull
    private static TextView createProfileListSub(@NonNull ViewGroup parent) {
        TextView tv = new TextView(parent.getContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.app_text_secondary));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dimen(parent, R.dimen.space_6);
        tv.setLayoutParams(lp);

        return tv;
    }

    @Override
    protected void onDestroy() {
        Call<List<ProfileRow>> c1 = inFlightProfileCall;
        if (c1 != null) { try { c1.cancel(); } catch (Exception ignored) {} }
        inFlightProfileCall = null;

        Call<List<InfoLetterRow>> c2 = inFlightLettersCall;
        if (c2 != null) { try { c2.cancel(); } catch (Exception ignored) {} }
        inFlightLettersCall = null;

        Call<List<RequestRow>> c3 = inFlightRequestsCall;
        if (c3 != null) { try { c3.cancel(); } catch (Exception ignored) {} }
        inFlightRequestsCall = null;

        Call<List<ComplaintRow>> c4 = inFlightComplaintsCall;
        if (c4 != null) { try { c4.cancel(); } catch (Exception ignored) {} }
        inFlightComplaintsCall = null;

        super.onDestroy();
        binding = null;
    }
}