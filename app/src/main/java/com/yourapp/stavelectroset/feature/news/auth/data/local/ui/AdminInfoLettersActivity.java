package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;

public class AdminInfoLettersActivity extends AppCompatActivity {

    private static final String PREFS_PROFILE = "profile";
    private static final String KEY_ROLE_CACHE = "role_cache";

    private static final String BUCKET = "attachments";
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private SwipeRefreshLayout swipe;
    private RecyclerView rv;
    private TextView tvEmpty;

    private MaterialButton btnCreateLetter;

    private AuthRepository authRepository;
    private StorageApi storageApi;
    private RpcApi rpcApi;

    private ActivityResultLauncher<String[]> pickAttachmentLauncher;

    private final List<DraftAttachment> draftAttachments = new ArrayList<>();
    @Nullable private UserHit draftTargetUser = null;
    private boolean draftBroadcast = true;

    private final SimpleAdapter adapter = new SimpleAdapter();

    @Nullable private OnAttachmentPicked pendingAttachmentCb;

    private boolean rpcRetryUsed = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_admin_info_letters);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setTitle("Информационные письма (админ)");
            tb.setNavigationOnClickListener(v -> finish());
        }

        btnCreateLetter = findViewById(R.id.btnCreateLetter);
        swipe = findViewById(R.id.swipe);
        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);

        String role = readRole();
        if (!isStaff(role)) {
            toastLong("Нет прав: нужен Owner/Deputy/Admin");
            finish();
            return;
        }

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(adapter);
        }

        if (tvEmpty != null) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("Нажмите «Создать письмо», чтобы отправить сообщение пользователям.");
        }

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();
        storageApi = sl.supabaseStorageRetrofit().create(StorageApi.class);
        rpcApi = sl.supabaseRestRetrofit().create(RpcApi.class);

        if (btnCreateLetter != null) {
            btnCreateLetter.setOnClickListener(v -> showComposeDialog());
        }

        if (swipe != null) swipe.setOnRefreshListener(this::ensureSessionThenIdle);

        pickAttachmentLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    OnAttachmentPicked cb = pendingAttachmentCb;
                    pendingAttachmentCb = null;
                    if (cb != null) cb.onPicked(uri);
                }
        );

        ensureSessionThenIdle();
    }

    private void ensureSessionThenIdle() {
        setLoading(true);

        if (!isEmpty(authRepository.getAccessToken())) {
            setLoading(false);
            return;
        }

        authRepository.refresh(result -> runOnUiThread(() -> {
            setLoading(false);
            if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                authRepository.logout();
                goToLoginAndClearTask();
            }
        }));
    }

    // =========================
    // Compose UI
    // =========================

    private void showComposeDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView tvMode = new TextView(this);
        tvMode.setText("Получатель: " + (draftBroadcast ? "Все пользователи"
                : (draftTargetUser != null ? userLabel(draftTargetUser) : "Не выбран")));
        tvMode.setAlpha(0.9f);
        root.addView(tvMode);

        MaterialButton btnPickRecipient = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnPickRecipient.setText("Выбрать получателя");
        LinearLayout.LayoutParams lpPick = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpPick.topMargin = dp(10);
        btnPickRecipient.setLayoutParams(lpPick);
        root.addView(btnPickRecipient);

        btnPickRecipient.setOnClickListener(v -> showRecipientPicker(selected -> {
            draftBroadcast = selected.isBroadcast;
            draftTargetUser = selected.user;
            tvMode.setText("Получатель: " + (draftBroadcast ? "Все пользователи"
                    : (draftTargetUser != null ? userLabel(draftTargetUser) : "Не выбран")));
        }));

        TextInputLayout tilTitle = new TextInputLayout(this);
        tilTitle.setHint("Заголовок (необязательно)");
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpT.topMargin = dp(12);
        tilTitle.setLayoutParams(lpT);

        TextInputEditText etTitle = new TextInputEditText(this);
        etTitle.setInputType(InputType.TYPE_CLASS_TEXT);
        tilTitle.addView(etTitle);
        root.addView(tilTitle);

        TextInputLayout tilBody = new TextInputLayout(this);
        tilBody.setHint("Текст письма (обязательно)");
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpB.topMargin = dp(12);
        tilBody.setLayoutParams(lpB);

        TextInputEditText etBody = new TextInputEditText(this);
        etBody.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etBody.setMinLines(4);
        tilBody.addView(etBody);
        root.addView(tilBody);

        TextView tvAtt = new TextView(this);
        tvAtt.setText("Вложения (PDF/JPG, до 20MB):");
        tvAtt.setAlpha(0.9f);
        LinearLayout.LayoutParams lpL = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpL.topMargin = dp(14);
        tvAtt.setLayoutParams(lpL);
        root.addView(tvAtt);

        final LinearLayout attList = new LinearLayout(this);
        attList.setOrientation(LinearLayout.VERTICAL);
        root.addView(attList);

        MaterialButton btnAddAtt = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAddAtt.setText("Добавить вложение");
        LinearLayout.LayoutParams lpAdd = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpAdd.topMargin = dp(10);
        btnAddAtt.setLayoutParams(lpAdd);
        root.addView(btnAddAtt);

        redrawAttachmentList(attList);

        btnAddAtt.setOnClickListener(v -> {
            pendingAttachmentCb = uri -> {
                DraftAttachment da = readDraftAttachment(uri);
                if (da == null) return;

                if (!isAllowedMime(da.mimeType)) {
                    toast("Разрешены только PDF и JPG/JPEG");
                    return;
                }

                if (da.sizeBytes > 0 && da.sizeBytes > MAX_FILE_BYTES) {
                    toast("Файл слишком большой (макс 20MB)");
                    return;
                }

                draftAttachments.add(da);
                redrawAttachmentList(attList);
            };

            if (pickAttachmentLauncher != null) {
                pickAttachmentLauncher.launch(new String[]{"image/*", "application/pdf"});
            }
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("Создать письмо")
                .setView(root)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить", (d, w) -> {
                    String title = textOf(etTitle);
                    String body = textOf(etBody);

                    if (isEmpty(body)) {
                        toast("Текст письма обязателен");
                        return;
                    }

                    if (!draftBroadcast && (draftTargetUser == null || isEmpty(draftTargetUser.id))) {
                        toast("Выбери конкретного пользователя или переключись на «всем»");
                        return;
                    }

                    sendLetter(title, body, draftBroadcast, draftTargetUser, new ArrayList<>(draftAttachments));
                })
                .show();
    }

    private void redrawAttachmentList(@NonNull LinearLayout attList) {
        attList.removeAllViews();

        if (draftAttachments.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Пока нет вложений");
            tv.setAlpha(0.75f);
            attList.addView(tv);
            return;
        }

        for (int i = 0; i < draftAttachments.size(); i++) {
            DraftAttachment a = draftAttachments.get(i);

            MaterialCardView card = new MaterialCardView(this);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            clp.topMargin = dp(8);
            card.setLayoutParams(clp);
            card.setUseCompatPadding(true);
            card.setCardElevation(dp(1));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView t1 = new TextView(this);
            t1.setText(safe(a.displayName));
            t1.setTypeface(t1.getTypeface(), android.graphics.Typeface.BOLD);
            row.addView(t1);

            TextView t2 = new TextView(this);
            t2.setAlpha(0.8f);
            String meta = "";
            if (!isEmpty(a.mimeType)) meta += a.mimeType;
            if (a.sizeBytes > 0) meta += (meta.isEmpty() ? "" : " • ") + prettyBytes(a.sizeBytes);
            t2.setText(meta);
            row.addView(t2);

            MaterialButton btnRemove = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnRemove.setText("Удалить");
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rlp.topMargin = dp(8);
            btnRemove.setLayoutParams(rlp);
            row.addView(btnRemove);

            int idx = i;
            btnRemove.setOnClickListener(v -> {
                if (idx >= 0 && idx < draftAttachments.size()) {
                    draftAttachments.remove(idx);
                    redrawAttachmentList(attList);
                }
            });

            card.addView(row);
            attList.addView(card);
        }
    }

    // =========================
    // Send flow
    // =========================

    private void sendLetter(@Nullable String title,
                            @NonNull String body,
                            boolean broadcast,
                            @Nullable UserHit targetUser,
                            @NonNull List<DraftAttachment> atts) {

        setLoading(true);
        rpcRetryUsed = false;

        if (isEmpty(authRepository.getAccessToken())) {
            authRepository.refresh(result -> runOnUiThread(() -> {
                if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                    setLoading(false);
                    authRepository.logout();
                    goToLoginAndClearTask();
                    return;
                }
                uploadThenRpc(title, body, broadcast, targetUser, atts);
            }));
            return;
        }

        uploadThenRpc(title, body, broadcast, targetUser, atts);
    }

    private void uploadThenRpc(@Nullable String title,
                               @NonNull String body,
                               boolean broadcast,
                               @Nullable UserHit targetUser,
                               @NonNull List<DraftAttachment> atts) {

        if (atts.isEmpty()) {
            callCreateRpc(title, body, broadcast, targetUser, new JsonArray());
            return;
        }

        String batchId = UUID.randomUUID().toString();

        uploadAt(0, batchId, atts, new ArrayList<>(), (ok, uploadedMeta) -> {
            if (!ok) {
                setLoading(false);
                return;
            }
            JsonArray arr = new JsonArray();
            for (UploadedAttachmentMeta m : uploadedMeta) {
                JsonObject o = new JsonObject();
                o.addProperty("bucket_id", BUCKET);
                o.addProperty("object_path", m.objectPath);
                o.addProperty("file_name", m.fileName);
                o.addProperty("mime_type", m.mimeType);
                o.addProperty("size_bytes", m.sizeBytes);
                arr.add(o);
            }
            callCreateRpc(title, body, broadcast, targetUser, arr);
        });
    }

    private interface UploadDone {
        void onDone(boolean ok, @NonNull List<UploadedAttachmentMeta> uploadedMeta);
    }

    private void uploadAt(int idx,
                          @NonNull String batchId,
                          @NonNull List<DraftAttachment> atts,
                          @NonNull List<UploadedAttachmentMeta> out,
                          @NonNull UploadDone cb) {

        if (idx >= atts.size()) {
            cb.onDone(true, out);
            return;
        }

        DraftAttachment da = atts.get(idx);
        if (da == null || da.uri == null) {
            cb.onDone(false, out);
            return;
        }

        FileInfo fi = readFileInfo(da.uri);
        if (fi == null || fi.bytes == null || fi.bytes.length == 0) {
            toast("Не удалось прочитать файл: " + safe(da.displayName));
            cb.onDone(false, out);
            return;
        }

        if (fi.bytes.length > MAX_FILE_BYTES) {
            toast("Файл слишком большой: " + safe(fi.displayName));
            cb.onDone(false, out);
            return;
        }

        if (!isAllowedMime(fi.mimeType)) {
            toast("Нельзя прикрепить: " + safe(fi.displayName));
            cb.onDone(false, out);
            return;
        }

        String adminUid = authRepository.getUserUid();
        if (isEmpty(adminUid)) {
            toast("Не удалось прочитать uid. Выйди и войди заново.");
            cb.onDone(false, out);
            return;
        }

        String safeName = makeSafeName(fi.displayName);
        String objectPath = "info_letters/" + adminUid + "/" + batchId + "/" + safeName;

        RequestBody rb = RequestBody.create(fi.bytes, MediaType.parse(fi.mimeType));

        storageApi.uploadObject(BUCKET, objectPath, fi.mimeType, rb).enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (!response.isSuccessful()) {
                    toast("Storage upload ошибка: " + response.code() + "\n" + readError(response));
                    cb.onDone(false, out);
                    return;
                }

                UploadedAttachmentMeta meta = new UploadedAttachmentMeta();
                meta.objectPath = objectPath;
                meta.fileName = fi.displayName;
                meta.mimeType = normalizeMime(fi.mimeType);
                meta.sizeBytes = fi.bytes.length;
                out.add(meta);

                uploadAt(idx + 1, batchId, atts, out, cb);
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                toast("Сеть (upload): " + safeMsg(t));
                cb.onDone(false, out);
            }
        });
    }

    private void callCreateRpc(@Nullable String title,
                               @NonNull String body,
                               boolean broadcast,
                               @Nullable UserHit targetUser,
                               @NonNull JsonArray attachments) {

        JsonObject req = new JsonObject();
        req.addProperty("p_broadcast", broadcast);

        if (targetUser == null || isEmpty(targetUser.id)) {
            req.add("p_target_user_id", com.google.gson.JsonNull.INSTANCE);
        } else {
            req.addProperty("p_target_user_id", targetUser.id);
        }

        req.addProperty("p_title", title == null ? "" : title);
        req.addProperty("p_body", body);
        req.add("p_attachments", attachments);

        rpcApi.createInfoLetter(req).enqueue(new Callback<List<CreateRespRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<CreateRespRow>> call, @NonNull Response<List<CreateRespRow>> response) {

                if (response.code() == 401 && !rpcRetryUsed) {
                    rpcRetryUsed = true;
                    authRepository.refresh(r -> runOnUiThread(() -> {
                        if (r == null || !r.ok || isEmpty(authRepository.getAccessToken())) {
                            setLoading(false);
                            authRepository.logout();
                            goToLoginAndClearTask();
                            return;
                        }
                        callCreateRpc(title, body, broadcast, targetUser, attachments);
                    }));
                    return;
                }

                setLoading(false);

                if (!response.isSuccessful()) {
                    toast("RPC ошибка: " + response.code() + "\n" + readError(response));
                    return;
                }

                int cnt = 0;
                String letterId = null;
                List<CreateRespRow> rows = response.body();
                if (rows != null && !rows.isEmpty() && rows.get(0) != null) {
                    cnt = rows.get(0).createdCount;
                    letterId = rows.get(0).letterId;
                }

                toast("Отправлено: " + (cnt > 0 ? cnt : 1));

                draftAttachments.clear();
                draftTargetUser = null;
                draftBroadcast = true;
            }

            @Override
            public void onFailure(@NonNull Call<List<CreateRespRow>> call, @NonNull Throwable t) {
                setLoading(false);
                toast("Сеть (rpc): " + safeMsg(t));
            }
        });
    }

    // =========================
    // Recipient picker + search
    // =========================

    private static class RecipientPick {
        boolean isBroadcast;
        @Nullable UserHit user;
    }

    private interface OnRecipientPicked { void onPicked(@NonNull RecipientPick p); }

    private void showRecipientPicker(@NonNull OnRecipientPicked cb) {
        final String[] items = new String[]{"Всем пользователям", "Конкретному пользователю"};
        final int[] selected = new int[]{draftBroadcast ? 0 : 1};

        new MaterialAlertDialogBuilder(this)
                .setTitle("Кому отправить?")
                .setSingleChoiceItems(items, selected[0], (d, which) -> selected[0] = which)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Далее", (d, w) -> {
                    if (selected[0] == 0) {
                        RecipientPick p = new RecipientPick();
                        p.isBroadcast = true;
                        p.user = null;
                        cb.onPicked(p);
                        return;
                    }
                    showUserSearchDialog(user -> {
                        RecipientPick p = new RecipientPick();
                        p.isBroadcast = false;
                        p.user = user;
                        cb.onPicked(p);
                    });
                })
                .show();
    }

    private interface OnUserPicked { void onPicked(@NonNull UserHit user); }

    private void showUserSearchDialog(@NonNull OnUserPicked cb) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextInputLayout til = new TextInputLayout(this);
        til.setHint("Поиск: UUID / ФИО / email / логин / телефон / лицевой счёт");
        TextInputEditText et = new TextInputEditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        til.addView(et);
        root.addView(til);

        MaterialButton btnSearch = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnSearch.setText("Найти");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        btnSearch.setLayoutParams(lp);
        root.addView(btnSearch);

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        results.setLayoutParams(rlp);
        root.addView(results);

        TextView tvState = new TextView(this);
        tvState.setAlpha(0.75f);
        tvState.setText("Введите запрос и нажмите «Найти»");
        results.addView(tvState);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Выбор пользователя")
                .setView(root)
                .setNegativeButton("Закрыть", null)
                .create();

        btnSearch.setOnClickListener(v -> {
            String q = textOf(et);
            if (isEmpty(q)) {
                toast("Введите запрос");
                return;
            }

            setLoading(true);
            rpcRetryUsed = false;

            JsonObject body = new JsonObject();
            body.addProperty("p_q", q);
            body.addProperty("p_limit", 12);

            rpcApi.searchProfiles(body).enqueue(new Callback<List<UserHit>>() {
                @Override
                public void onResponse(@NonNull Call<List<UserHit>> call, @NonNull Response<List<UserHit>> response) {
                    setLoading(false);

                    if (response.code() == 401 && !rpcRetryUsed) {
                        rpcRetryUsed = true;
                        authRepository.refresh(r -> runOnUiThread(() -> {
                            if (r == null || !r.ok || isEmpty(authRepository.getAccessToken())) {
                                authRepository.logout();
                                goToLoginAndClearTask();
                                return;
                            }
                            rpcApi.searchProfiles(body).enqueue(this);
                        }));
                        return;
                    }

                    if (!response.isSuccessful() || response.body() == null) {
                        toast("Поиск ошибка: " + response.code() + "\n" + readError(response));
                        return;
                    }

                    List<UserHit> list = response.body();
                    if (list == null) list = new ArrayList<>();

                    results.removeAllViews();
                    if (list.isEmpty()) {
                        TextView tv = new TextView(AdminInfoLettersActivity.this);
                        tv.setText("Ничего не найдено");
                        tv.setAlpha(0.75f);
                        results.addView(tv);
                        return;
                    }

                    for (UserHit u : list) {
                        MaterialCardView card = new MaterialCardView(AdminInfoLettersActivity.this);
                        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        clp.topMargin = dp(8);
                        card.setLayoutParams(clp);
                        card.setUseCompatPadding(true);
                        card.setCardElevation(dp(1));

                        LinearLayout row = new LinearLayout(AdminInfoLettersActivity.this);
                        row.setOrientation(LinearLayout.VERTICAL);
                        row.setPadding(dp(12), dp(10), dp(12), dp(10));

                        TextView t1 = new TextView(AdminInfoLettersActivity.this);
                        t1.setTypeface(t1.getTypeface(), android.graphics.Typeface.BOLD);
                        t1.setText(isEmpty(u.fullName) ? "Без ФИО" : u.fullName);
                        row.addView(t1);

                        TextView t2 = new TextView(AdminInfoLettersActivity.this);
                        t2.setAlpha(0.8f);
                        t2.setText(userLabel(u));
                        row.addView(t2);

                        card.addView(row);
                        card.setOnClickListener(vv -> {
                            try { dialog.dismiss(); } catch (Exception ignored) {}
                            cb.onPicked(u);
                        });

                        results.addView(card);
                    }
                }

                @Override
                public void onFailure(@NonNull Call<List<UserHit>> call, @NonNull Throwable t) {
                    setLoading(false);
                    toast("Сеть (поиск): " + safeMsg(t));
                }
            });
        });

        dialog.show();
    }

    // =========================
    // Retrofit
    // =========================

    interface StorageApi {
        @POST("object/{bucket}/{path}")
        Call<JsonObject> uploadObject(
                @Path("bucket") String bucket,
                @Path(value = "path", encoded = true) String path,
                @retrofit2.http.Header("Content-Type") String contentType,
                @Body RequestBody body
        );
    }

    interface RpcApi {
        @POST("rpc/admin_search_profiles")
        @Headers("Content-Type: application/json")
        Call<List<UserHit>> searchProfiles(@Body JsonObject body);

        @POST("rpc/admin_create_info_letter")
        @Headers("Content-Type: application/json")
        Call<List<CreateRespRow>> createInfoLetter(@Body JsonObject body);
    }

    static class CreateRespRow {
        @SerializedName("created_count") int createdCount;
        @SerializedName("letter_id") String letterId;
    }

    static class UserHit {
        @SerializedName("id") String id;
        @SerializedName("full_name") String fullName;
        @SerializedName("login") String login;
        @SerializedName("email") String email;
        @SerializedName("phone") String phone;
        @SerializedName("account_number") String accountNumber;
    }

    // =========================
    // Draft attachments
    // =========================

    private interface OnAttachmentPicked { void onPicked(@NonNull Uri uri); }

    static class DraftAttachment {
        Uri uri;
        String displayName;
        String mimeType;
        long sizeBytes;
    }

    static class UploadedAttachmentMeta {
        String objectPath;
        String fileName;
        String mimeType;
        long sizeBytes;
    }

    static class FileInfo {
        byte[] bytes;
        String displayName;
        String mimeType;
    }

    @Nullable
    private DraftAttachment readDraftAttachment(@NonNull Uri uri) {
        try {
            try {
                final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}

            DraftAttachment a = new DraftAttachment();
            a.uri = uri;

            String name = "file";
            long size = 0L;

            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idxName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idxName >= 0) {
                        String v = c.getString(idxName);
                        if (v != null && !v.trim().isEmpty()) name = v.trim();
                    }
                    int idxSize = c.getColumnIndex(OpenableColumns.SIZE);
                    if (idxSize >= 0) {
                        try { size = c.getLong(idxSize); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            a.displayName = name;

            String mime = null;
            try { mime = getContentResolver().getType(uri); } catch (Exception ignored) {}
            if (mime == null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".pdf")) mime = "application/pdf";
                else if (lower.endsWith(".jpg")) mime = "image/jpg";
                else if (lower.endsWith(".jpeg")) mime = "image/jpeg";
            }
            if (mime == null) mime = "application/octet-stream";
            a.mimeType = mime;
            a.sizeBytes = size;

            return a;
        } catch (Exception e) {
            toast("Не удалось добавить файл");
            return null;
        }
    }

    @Nullable
    private FileInfo readFileInfo(@NonNull Uri uri) {
        FileInfo info = new FileInfo();

        String name = "file";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String v = c.getString(idx);
                    if (v != null && !v.trim().isEmpty()) name = v.trim();
                }
            }
        } catch (Exception ignored) {}
        info.displayName = name;

        String mime = null;
        try { mime = getContentResolver().getType(uri); } catch (Exception ignored) {}
        if (mime == null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) mime = "application/pdf";
            else if (lower.endsWith(".jpg")) mime = "image/jpg";
            else if (lower.endsWith(".jpeg")) mime = "image/jpeg";
        }
        if (mime == null) mime = "application/octet-stream";
        info.mimeType = mime;

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            info.bytes = bos.toByteArray();
        } catch (Exception e) {
            return null;
        }

        return info;
    }

    private void setLoading(boolean loading) {
        if (swipe != null) swipe.setRefreshing(loading);
        if (btnCreateLetter != null) btnCreateLetter.setEnabled(!loading);
    }

    private void goToLoginAndClearTask() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        try { finish(); } catch (Exception ignored) {}
    }

    private String readRole() {
        SharedPreferences p = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE);
        String r = p.getString(KEY_ROLE_CACHE, "user");
        if (r == null) return "user";
        r = r.trim().toLowerCase(Locale.ROOT);
        return r.isEmpty() ? "user" : r;
    }

    private static boolean isStaff(@Nullable String roleRaw) {
        String r = roleRaw == null ? "" : roleRaw.trim().toLowerCase(Locale.ROOT);
        return r.equals("owner") || r.equals("deputy") || r.equals("admin");
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

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(@Nullable String s) { return s == null ? "" : s; }

    private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }

    private static String textOf(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        return et.getText().toString().trim();
    }

    private static boolean isAllowedMime(@Nullable String mime) {
        String m = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        return m.equals("application/pdf") || m.equals("image/jpeg") || m.equals("image/jpg");
    }

    private static String normalizeMime(@NonNull String mime) {
        String m = mime.trim().toLowerCase(Locale.ROOT);
        if (m.equals("image/jpg")) return "image/jpg";
        if (m.equals("image/jpeg")) return "image/jpeg";
        if (m.equals("application/pdf")) return "application/pdf";
        return m;
    }

    private static String makeSafeName(@NonNull String name) {
        String n = name.trim();
        if (n.isEmpty()) n = "file";
        n = n.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return System.currentTimeMillis() + "_" + n;
    }

    private static String prettyBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private static String userLabel(@NonNull UserHit u) {
        StringBuilder sb = new StringBuilder();
        if (!isEmpty(u.id)) sb.append(u.id);
        if (!isEmpty(u.email)) sb.append("\n").append(u.email);
        if (!isEmpty(u.login)) sb.append("\nлогин: ").append(u.login);
        if (!isEmpty(u.phone)) sb.append("\nтел: ").append(u.phone);
        if (!isEmpty(u.accountNumber)) sb.append("\nл/с: ").append(u.accountNumber);
        return sb.toString().trim();
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

    static class SimpleAdapter extends RecyclerView.Adapter<SimpleAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setPadding(24, 24, 24, 24);
            tv.setAlpha(0.75f);
            tv.setText("—");
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) { }
        @Override public int getItemCount() { return 0; }
        static class VH extends RecyclerView.ViewHolder {
            VH(@NonNull View itemView) { super(itemView); }
        }
    }
}