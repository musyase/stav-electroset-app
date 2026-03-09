package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Query;

public class RoleManagementActivity extends AppCompatActivity {

    private static final String PREFS_PROFILE = "profile";
    private static final String KEY_ROLE_CACHE = "role_cache";

    private static final Pattern LOGIN_PATTERN = Pattern.compile("^[a-z0-9._-]+$");

    private RecyclerView rv;
    private TextView tvEmpty;

    private AuthRepository authRepository;
    private ProfilesApi profilesApi;
    private FunctionsApi functionsApi;

    private UsersAdapter adapter;

    @Nullable private Call<List<ProfileRow>> inFlightList;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_management);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) tb.setNavigationOnClickListener(v -> finish());

        rv = findViewById(R.id.rvUsers);
        tvEmpty = findViewById(R.id.tvEmpty);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UsersAdapter(this::showActionsDialog);
        rv.setAdapter(adapter);

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();
        profilesApi = sl.supabaseRetrofit().create(ProfilesApi.class);
        functionsApi = sl.supabaseRetrofit().create(FunctionsApi.class);

        if (!"owner".equals(readRole())) {
            toastLong("Нет прав: только Owner может менять данные/роли");
            showEmpty(true);
            return;
        }

        loadUsers();
    }

    private void loadUsers() {
        cancelList();
        showEmpty(false);

        // добавил email, чтобы можно было редактировать и видеть
        String select = "id,full_name,login,email,role";

        inFlightList = profilesApi.listProfiles(select, "full_name.asc", 200);
        inFlightList.enqueue(new Callback<List<ProfileRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ProfileRow>> call, @NonNull Response<List<ProfileRow>> response) {
                if (!response.isSuccessful()) {
                    showEmpty(true);
                    toast("Не удалось загрузить пользователей: " + response.code() + "\n" + readError(response));
                    return;
                }
                List<ProfileRow> rows = response.body();
                if (rows == null) rows = new ArrayList<>();
                adapter.submit(rows);
                showEmpty(rows.isEmpty());
            }

            @Override
            public void onFailure(@NonNull Call<List<ProfileRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                showEmpty(true);
                toast("Сеть: " + safeMsg(t));
            }
        });
    }

    private void showActionsDialog(@NonNull ProfileRow user) {
        String[] items = new String[]{
                "Сменить роль",
                "Редактировать login / email / пароль",
                "Скопировать UID"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(safe(user.fullName) + " (" + safe(user.login) + ")")
                .setItems(items, (d, which) -> {
                    if (which == 0) showRoleDialog(user);
                    else if (which == 1) showEditUserDialog(user);
                    else if (which == 2) copyUid(user.id);
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    // ===== Role dialog (как было) =====

    private void showRoleDialog(@NonNull ProfileRow user) {
        final String[] roles  = new String[]{"user", "deputy", "owner"};
        final String[] labels = new String[]{"Пользователь", "Заместитель", "Owner"};

        int checked = 0;
        String cur = normalizeRole(user.role);
        for (int i = 0; i < roles.length; i++) {
            if (roles[i].equals(cur)) { checked = i; break; }
        }

        final int[] pick = new int[]{checked};

        new MaterialAlertDialogBuilder(this)
                .setTitle("Роль для: " + safe(user.fullName) + " (" + safe(user.login) + ")")
                .setSingleChoiceItems(labels, checked, (dlg, which) -> pick[0] = which)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (dlg, w) -> setRole(user.id, roles[pick[0]]))
                .show();
    }

    private void setRole(@Nullable String userId, @NonNull String newRole) {
        if (userId == null || userId.trim().isEmpty()) {
            toast("Пустой UID");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("role", newRole);

        profilesApi.setRole("eq." + userId.trim(), body).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                if (!response.isSuccessful()) {
                    toast("Ошибка смены роли: " + response.code() + "\n" + readError(response));
                    return;
                }
                toast("Роль обновлена: " + newRole);
                loadUsers();
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                toast("Сеть: " + safeMsg(t));
            }
        });
    }

    // ===== Edit user dialog (login/email/password) via Edge Function =====

    private void showEditUserDialog(@NonNull ProfileRow user) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextInputLayout tilLogin = new TextInputLayout(this);
        tilLogin.setHint("Логин (латиница/цифры . _ -)");
        TextInputEditText etLogin = new TextInputEditText(this);
        etLogin.setMaxLines(1);
        etLogin.setInputType(InputType.TYPE_CLASS_TEXT);
        etLogin.setText(safe(user.login));
        tilLogin.addView(etLogin);
        root.addView(tilLogin);

        TextInputLayout tilEmail = new TextInputLayout(this);
        tilEmail.setHint("Email (для входа)");
        TextInputEditText etEmail = new TextInputEditText(this);
        etEmail.setMaxLines(1);
        etEmail.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        etEmail.setText(safe(user.email));
        tilEmail.addView(etEmail);
        root.addView(tilEmail);

        TextInputLayout tilPass = new TextInputLayout(this);
        tilPass.setHint("Новый пароль (если нужно)");
        TextInputEditText etPass = new TextInputEditText(this);
        etPass.setMaxLines(1);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilPass.addView(etPass);
        root.addView(tilPass);

        TextInputLayout tilPass2 = new TextInputLayout(this);
        tilPass2.setHint("Повтор пароля");
        TextInputEditText etPass2 = new TextInputEditText(this);
        etPass2.setMaxLines(1);
        etPass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilPass2.addView(etPass2);
        root.addView(tilPass2);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Редактирование: " + safe(user.fullName))
                .setView(root)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String newLogin = textOf(etLogin).toLowerCase();
                    String newEmail = textOf(etEmail);
                    String p1 = textOf(etPass);
                    String p2 = textOf(etPass2);

                    // валидация login
                    if (!newLogin.isEmpty() && !LOGIN_PATTERN.matcher(newLogin).matches()) {
                        toast("Логин: только латиница/цифры и . _ -");
                        return;
                    }

                    // пароль (если заполнили)
                    if (!p1.isEmpty() || !p2.isEmpty()) {
                        if (p1.length() < 6) {
                            toast("Пароль должен быть не меньше 6");
                            return;
                        }
                        if (!p1.equals(p2)) {
                            toast("Пароли не совпадают");
                            return;
                        }
                    }

                    callAdminUpdateUser(user.id, newLogin, newEmail, p1);
                })
                .show();
    }

    private void callAdminUpdateUser(@Nullable String userId,
                                     @NonNull String newLogin,
                                     @NonNull String newEmail,
                                     @NonNull String newPasswordOrEmpty) {
        if (userId == null || userId.trim().isEmpty()) {
            toast("UID пустой");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("target_user_id", userId.trim());

        // передаём только если не пусто
        if (!newLogin.trim().isEmpty()) body.addProperty("new_login", newLogin.trim());
        if (!newEmail.trim().isEmpty()) body.addProperty("new_email", newEmail.trim());
        if (!newPasswordOrEmpty.trim().isEmpty()) body.addProperty("new_password", newPasswordOrEmpty);

        // чтобы email сразу считался подтверждённым (иначе может попросить подтверждение)
        body.addProperty("force_confirm_email", true);

        functionsApi.adminUpdateUser(body).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (!response.isSuccessful()) {
                    toast("Ошибка сохранения: " + response.code() + "\n" + readError(response));
                    return;
                }
                toast("Сохранено");
                loadUsers();
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                toast("Сеть: " + safeMsg(t));
            }
        });
    }

    private void copyUid(@Nullable String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            toast("UID пустой");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("uid", uid.trim()));
            toast("UID скопирован");
        }
    }

    private void showEmpty(boolean empty) {
        if (tvEmpty != null) tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (rv != null) rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void cancelList() {
        Call<List<ProfileRow>> c = inFlightList;
        if (c != null) { try { c.cancel(); } catch (Exception ignored) {} }
        inFlightList = null;
    }

    private String readRole() {
        SharedPreferences p = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE);
        String r = p.getString(KEY_ROLE_CACHE, "user");
        if (r == null) return "user";
        r = r.trim().toLowerCase();
        return r.isEmpty() ? "user" : r;
    }

    // ===== Retrofit =====

    interface ProfilesApi {
        @GET("rest/v1/profiles")
        @Headers("Accept: application/json")
        Call<List<ProfileRow>> listProfiles(
                @Query("select") String select,
                @Query("order") String order,
                @Query("limit") Integer limit
        );

        @PATCH("rest/v1/profiles")
        @Headers({
                "Content-Type: application/json",
                "Prefer: return=minimal"
        })
        Call<Void> setRole(
                @Query("id") String idEq,
                @Body JsonObject body
        );
    }

    interface FunctionsApi {
        @POST("functions/v1/admin_update_user")
        @Headers("Content-Type: application/json")
        Call<JsonObject> adminUpdateUser(@Body JsonObject body);
    }

    static class ProfileRow {
        @SerializedName("id") String id;
        @SerializedName("full_name") String fullName;
        @SerializedName("login") String login;
        @SerializedName("email") String email;
        @SerializedName("role") String role;
    }

    // ===== Adapter =====

    interface OnUserClick { void onClick(@NonNull ProfileRow user); }

    static class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.VH> {
        private final List<ProfileRow> items = new ArrayList<>();
        private final OnUserClick onClick;

        UsersAdapter(@NonNull OnUserClick onClick) { this.onClick = onClick; }

        void submit(@NonNull List<ProfileRow> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = dp(parent, 10);
            card.setLayoutParams(lp);
            card.setCardElevation(dp(parent, 2));
            card.setUseCompatPadding(true);

            LinearLayout root = new LinearLayout(parent.getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(parent, 14), dp(parent, 12), dp(parent, 14), dp(parent, 12));
            card.addView(root);

            TextView tvTitle = new TextView(parent.getContext());
            tvTitle.setTextSize(15);
            tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
            root.addView(tvTitle);

            TextView tvSub = new TextView(parent.getContext());
            tvSub.setTextSize(13);
            tvSub.setAlpha(0.85f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subLp.topMargin = dp(parent, 6);
            tvSub.setLayoutParams(subLp);
            root.addView(tvSub);

            TextView tvUid = new TextView(parent.getContext());
            tvUid.setTextSize(12);
            tvUid.setAlpha(0.7f);
            LinearLayout.LayoutParams uidLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            uidLp.topMargin = dp(parent, 6);
            tvUid.setLayoutParams(uidLp);
            root.addView(tvUid);

            return new VH(card, tvTitle, tvSub, tvUid);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ProfileRow u = items.get(position);

            String name = (u.fullName == null || u.fullName.trim().isEmpty()) ? "Без имени" : u.fullName.trim();
            String login = (u.login == null || u.login.trim().isEmpty()) ? "—" : u.login.trim();
            String role = (u.role == null || u.role.trim().isEmpty()) ? "user" : u.role.trim();

            h.tvTitle.setText(name);
            h.tvSub.setText("login: " + login + "   •   role: " + role);
            h.tvUid.setText("uid: " + (u.id == null ? "—" : u.id));

            h.itemView.setOnClickListener(v -> onClick.onClick(u));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle, tvSub, tvUid;
            VH(@NonNull View itemView, @NonNull TextView tvTitle, @NonNull TextView tvSub, @NonNull TextView tvUid) {
                super(itemView);
                this.tvTitle = tvTitle;
                this.tvSub = tvSub;
                this.tvUid = tvUid;
            }
        }

        private static int dp(@NonNull ViewGroup parent, int dp) {
            float d = parent.getResources().getDisplayMetrics().density;
            return Math.round(dp * d);
        }
    }

    // ===== helpers =====

    private static String normalizeRole(@Nullable String role) {
        if (role == null) return "user";
        String r = role.trim().toLowerCase();
        return r.isEmpty() ? "user" : r;
    }

    private static String safe(@Nullable String s) { return s == null ? "" : s; }

    private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }

    private static String readError(@NonNull Response<?> r) {
        try {
            ResponseBody b = r.errorBody();
            if (b == null) return "";
            String s = b.string();
            if (s == null) return "";
            s = s.trim();
            return s.isEmpty() ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String textOf(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private int dp(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(dp * d);
    }

    private void toast(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void toastLong(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        cancelList();
        super.onDestroy();
    }
}