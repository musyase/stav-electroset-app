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
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public class AdminComplaintsActivity extends AppCompatActivity {

    private static final String PREFS_PROFILE = "profile";
    private static final String KEY_ROLE_CACHE = "role_cache";

    private static final String SELECT =
            "id,public_number,status,topic,created_at,description,user_id";

    private static final String[] FILTER_STATUS_KEYS = new String[]{
            "", "new", "in_progress", "resolved", "rejected", "cancelled"
    };
    private static final String[] FILTER_STATUS_LABELS = new String[]{
            "Все статусы", "Новая", "В работе", "Решено", "Отклонено", "Отменено"
    };

    private static final String[] EDIT_STATUS_KEYS = new String[]{
            "new", "in_progress", "resolved", "rejected", "cancelled"
    };
    private static final String[] EDIT_STATUS_LABELS = new String[]{
            "Новая", "В работе", "Решено", "Отклонено", "Отменено"
    };

    private static final String BUCKET = "attachments";

    private MaterialButton btnFilter;
    private SwipeRefreshLayout swipe;
    private RecyclerView rv;
    private TextView tvEmpty;

    private AuthRepository authRepository;
    private ComplaintsApi complaintsApi;
    private AdminResponsesApi responsesApi;
    private AttachmentsApi attachmentsApi;
    private StorageApi storageApi;

    private ComplaintsAdapter adapter;

    @Nullable private Call<List<ComplaintRow>> inFlight;
    private final AtomicBoolean retryAfterRefreshUsed = new AtomicBoolean(false);

    private int filterIndex = 0;

    private ActivityResultLauncher<String[]> pickResponseFileLauncher;
    @Nullable private String pendingAdminResponseId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_complaints);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setTitle("Жалобы (админ)");
            tb.setNavigationOnClickListener(v -> finish());
        }

        btnFilter = findViewById(R.id.btnFilter);
        swipe = findViewById(R.id.swipe);
        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);

        String role = readRole();
        if (!isStaff(role)) {
            toastLong("Нет прав: нужен Owner/Deputy/Admin");
            finish();
            return;
        }

        // ✅ ВАЖНО: переехали на новые Retrofit'ы из ServiceLocator:
        // REST/RPC -> supabaseRestRetrofit() (…/rest/v1/)
        // Storage   -> supabaseStorageRetrofit() (…/storage/v1/)
        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();

        complaintsApi = sl.supabaseRestRetrofit().create(ComplaintsApi.class);
        responsesApi = sl.supabaseRestRetrofit().create(AdminResponsesApi.class);
        attachmentsApi = sl.supabaseRestRetrofit().create(AttachmentsApi.class);

        storageApi = sl.supabaseStorageRetrofit().create(StorageApi.class);

        adapter = new ComplaintsAdapter(this::openEditorForComplaint);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        if (btnFilter != null) {
            btnFilter.setText("Фильтр: " + FILTER_STATUS_LABELS[filterIndex]);
            btnFilter.setOnClickListener(v -> showFilterDialog());
        }

        if (swipe != null) swipe.setOnRefreshListener(this::ensureSessionThenLoad);

        pickResponseFileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    String arId = pendingAdminResponseId;
                    if (isEmpty(arId)) {
                        toast("Не найден admin_response_id (попробуй ещё раз)");
                        return;
                    }
                    uploadResponseAttachment(arId, uri);
                }
        );

        ensureSessionThenLoad();
    }

    private void showFilterDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Фильтр по статусу")
                .setSingleChoiceItems(FILTER_STATUS_LABELS, filterIndex, (d, which) -> filterIndex = which)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (d, w) -> {
                    if (btnFilter != null) btnFilter.setText("Фильтр: " + FILTER_STATUS_LABELS[filterIndex]);
                    ensureSessionThenLoad();
                })
                .show();
    }

    private void ensureSessionThenLoad() {
        setLoading(true);

        if (!isEmpty(authRepository.getAccessToken())) {
            setLoading(false);
            load();
            return;
        }

        authRepository.refresh(result -> runOnUiThread(() -> {
            setLoading(false);
            if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                authRepository.logout();
                goToLoginAndClearTask();
                return;
            }
            load();
        }));
    }

    private void load() {
        cancelInFlight();
        retryAfterRefreshUsed.set(false);

        String status = FILTER_STATUS_KEYS[filterIndex];
        String statusEq = status.isEmpty() ? null : "eq." + status;

        setLoading(true);

        inFlight = complaintsApi.listComplaints(
                SELECT,
                statusEq,
                "created_at.desc",
                200
        );

        inFlight.enqueue(new Callback<List<ComplaintRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<ComplaintRow>> call, @NonNull Response<List<ComplaintRow>> response) {
                if (isFinishing() || isDestroyed()) return;

                if (response.code() == 401 || response.code() == 403) {
                    if (!retryAfterRefreshUsed.getAndSet(true)) {
                        refreshAndRetry();
                        return;
                    }
                }

                if (!response.isSuccessful()) {
                    setLoading(false);
                    showEmpty(true);
                    toast("Ошибка загрузки: " + response.code() + "\n" + readError(response));
                    return;
                }

                List<ComplaintRow> rows = response.body();
                if (rows == null) rows = new ArrayList<>();

                adapter.submit(rows);
                showEmpty(rows.isEmpty());
                setLoading(false);
            }

            @Override
            public void onFailure(@NonNull Call<List<ComplaintRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                runOnUiThread(() -> {
                    setLoading(false);
                    showEmpty(true);
                    toast("Сеть: " + safeMsg(t));
                });
            }
        });
    }

    private void refreshAndRetry() {
        authRepository.refresh(result -> runOnUiThread(() -> {
            if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                authRepository.logout();
                goToLoginAndClearTask();
                return;
            }
            load();
        }));
    }

    // ===== Editor =====

    private void openEditorForComplaint(@NonNull ComplaintRow c) {
        responsesApi.getByOwner(
                "id,message,updated_at",
                "eq.complaint",
                "eq." + safe(c.id),
                1
        ).enqueue(new Callback<List<AdminResponseRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<AdminResponseRow>> call,
                                   @NonNull Response<List<AdminResponseRow>> response) {
                String existing = "";
                String existingId = null;
                if (response.isSuccessful() && response.body() != null && !response.body().isEmpty()) {
                    AdminResponseRow row = response.body().get(0);
                    existing = row != null && row.message != null ? row.message : "";
                    existingId = row != null ? row.id : null;
                }
                showEditorDialog(c, existingId, existing);
            }

            @Override
            public void onFailure(@NonNull Call<List<AdminResponseRow>> call, @NonNull Throwable t) {
                showEditorDialog(c, null, "");
            }
        });
    }

    private void showEditorDialog(@NonNull ComplaintRow c,
                                  @Nullable String existingAdminResponseId,
                                  @NonNull String existingMessage) {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView tvInfo = new TextView(this);
        tvInfo.setText(
                "Номер: " + safe(c.publicNumber) + "\n" +
                        "Тема: " + topicRu(c.topic) + "\n" +
                        "Текущий статус: " + statusRu(c.status) + "\n\n" +
                        "Описание:\n" + safe(c.description) + "\n\n" +
                        "UID клиента:\n" + safe(c.userId)
        );
        tvInfo.setTextSize(14);
        tvInfo.setAlpha(0.9f);
        root.addView(tvInfo);

        final int[] pickedStatusIdx = new int[]{findStatusIndex(c.status)};
        MaterialButton btnPickStatus = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnPickStatus.setText("Статус: " + EDIT_STATUS_LABELS[pickedStatusIdx[0]]);
        LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpBtn.topMargin = dp(12);
        btnPickStatus.setLayoutParams(lpBtn);
        root.addView(btnPickStatus);

        btnPickStatus.setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Выберите статус")
                        .setSingleChoiceItems(EDIT_STATUS_LABELS, pickedStatusIdx[0], (d, which) -> pickedStatusIdx[0] = which)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Ок", (d, w) ->
                                btnPickStatus.setText("Статус: " + EDIT_STATUS_LABELS[pickedStatusIdx[0]]))
                        .show()
        );

        TextInputLayout tilMsg = new TextInputLayout(this);
        tilMsg.setHint("Ответ пользователю (можно текст + файлы)");
        LinearLayout.LayoutParams lpTil = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpTil.topMargin = dp(12);
        tilMsg.setLayoutParams(lpTil);

        TextInputEditText etMsg = new TextInputEditText(this);
        etMsg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etMsg.setMinLines(3);
        etMsg.setText(existingMessage);
        tilMsg.addView(etMsg);
        root.addView(tilMsg);

        MaterialButton btnUserFiles = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnUserFiles.setText("Вложения пользователя");
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpA.topMargin = dp(12);
        btnUserFiles.setLayoutParams(lpA);
        root.addView(btnUserFiles);

        btnUserFiles.setOnClickListener(v -> showAttachmentsFor("complaint", safe(c.id), "Вложения пользователя"));

        MaterialButton btnAnswerFiles = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAnswerFiles.setText("Файлы в ответе");
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpB.topMargin = dp(8);
        btnAnswerFiles.setLayoutParams(lpB);
        root.addView(btnAnswerFiles);

        btnAnswerFiles.setOnClickListener(v -> {
            if (!isEmpty(existingAdminResponseId)) {
                showAttachmentsFor("admin_response", existingAdminResponseId, "Файлы в ответе");
                return;
            }
            toast("Пока нет ответа/файлов. Добавь файл — и появится.");
        });

        MaterialButton btnAddFile = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnAddFile.setText("Прикрепить файл к ответу");
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lpC.topMargin = dp(8);
        btnAddFile.setLayoutParams(lpC);
        root.addView(btnAddFile);

        btnAddFile.setOnClickListener(v -> {
            String msgNow = textOf(etMsg);

            ensureAdminResponseIdForTicket("complaint", safe(c.id), msgNow, arId -> {
                if (isEmpty(arId)) {
                    toast("Не удалось создать ответ (admin_response)");
                    return;
                }
                pendingAdminResponseId = arId;
                if (pickResponseFileLauncher != null) {
                    pickResponseFileLauncher.launch(new String[]{"image/*", "application/pdf"});
                }
            });
        });

        String title = isEmpty(c.publicNumber) ? "Жалоба" : c.publicNumber;

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    String newStatus = EDIT_STATUS_KEYS[pickedStatusIdx[0]];
                    String msg = textOf(etMsg);

                    boolean statusChanged = !normalize(c.status).equals(newStatus);
                    if (statusChanged && isEmpty(msg)) {
                        toast("Если меняешь статус — напиши хотя бы короткий ответ");
                        return;
                    }

                    saveAdminActions(safe(c.id), newStatus, statusChanged, msg);
                })
                .show();
    }

    private void saveAdminActions(@NonNull String complaintId,
                                  @NonNull String newStatus,
                                  boolean statusChanged,
                                  @NonNull String message) {

        setLoading(true);

        if (statusChanged) {
            JsonObject body = new JsonObject();
            body.addProperty("status", newStatus);

            complaintsApi.updateStatus("eq." + complaintId, body).enqueue(new Callback<Void>() {
                @Override
                public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                    if (!response.isSuccessful()) {
                        setLoading(false);
                        toast("Не удалось сменить статус: " + response.code() + "\n" + readError(response));
                        return;
                    }
                    upsertResponseThenReload("complaint", complaintId, message);
                }

                @Override
                public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                    setLoading(false);
                    toast("Сеть (статус): " + safeMsg(t));
                }
            });
            return;
        }

        upsertResponseThenReload("complaint", complaintId, message);
    }

    private void upsertResponseThenReload(@NonNull String ownerType,
                                          @NonNull String ownerId,
                                          @NonNull String message) {

        if (isEmpty(message)) {
            setLoading(false);
            toast("Готово");
            load();
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("owner_type", ownerType);
        body.addProperty("owner_id", ownerId);
        body.addProperty("message", message);

        String adminUid = authRepository.getUserUid();
        if (!isEmpty(adminUid)) body.addProperty("admin_user_id", adminUid);

        responsesApi.upsert("owner_type,owner_id", body).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                setLoading(false);
                if (!response.isSuccessful()) {
                    toast("Не удалось сохранить ответ: " + response.code() + "\n" + readError(response));
                    return;
                }
                toast("Сохранено");
                load();
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                setLoading(false);
                toast("Сеть (ответ): " + safeMsg(t));
            }
        });
    }

    // ===== Attachments view =====

    private void showAttachmentsFor(@NonNull String ownerType, @NonNull String ownerId, @NonNull String title) {
        attachmentsApi.list(
                "id,file_name,mime_type,size_bytes,object_path,created_at",
                "eq." + ownerType,
                "eq." + ownerId,
                "created_at.desc",
                100
        ).enqueue(new Callback<List<AttachmentRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<AttachmentRow>> call, @NonNull Response<List<AttachmentRow>> response) {
                if (!response.isSuccessful()) {
                    toast("Не удалось загрузить вложения: " + response.code());
                    return;
                }
                List<AttachmentRow> rows = response.body();
                if (rows == null) rows = new ArrayList<>();
                showAttachmentsDialog(title, rows);
            }

            @Override
            public void onFailure(@NonNull Call<List<AttachmentRow>> call, @NonNull Throwable t) {
                toast("Сеть (вложения): " + safeMsg(t));
            }
        });
    }

    private void showAttachmentsDialog(@NonNull String title, @NonNull List<AttachmentRow> rows) {
        if (rows.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(title)
                    .setMessage("Файлов нет")
                    .setPositiveButton("Ок", null)
                    .show();
            return;
        }

        CharSequence[] items = new CharSequence[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            AttachmentRow a = rows.get(i);
            String name = safe(a.fileName);
            String size = a.sizeBytes != null ? (" (" + prettyBytes(a.sizeBytes) + ")") : "";
            items[i] = name + size;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items, (d, which) -> {
                    AttachmentRow a = rows.get(which);
                    if (a == null || isEmpty(a.objectPath)) return;

                    // ✅ Открываем внутри приложения
                    openAttachmentInApp(a);
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void openAttachmentInApp(@NonNull AttachmentRow a) {
        try {
            Intent i = new Intent(this, AttachmentViewerActivity.class);
            i.putExtra(AttachmentViewerActivity.EXTRA_BUCKET, BUCKET);
            i.putExtra(AttachmentViewerActivity.EXTRA_OBJECT_PATH, a.objectPath);
            i.putExtra(AttachmentViewerActivity.EXTRA_FILE_NAME, a.fileName);
            i.putExtra(AttachmentViewerActivity.EXTRA_MIME, a.mimeType);
            i.putExtra(AttachmentViewerActivity.EXTRA_TITLE,
                    (a.fileName == null || a.fileName.trim().isEmpty()) ? "Вложение" : a.fileName);
            startActivity(i);
        } catch (Exception e) {
            toast("Не удалось открыть внутри приложения");
        }
    }

    // ===== Upload to admin_response =====

    interface OnIdReady { void onReady(@Nullable String id); }

    private void ensureAdminResponseIdForTicket(@NonNull String ownerType,
                                                @NonNull String ticketId,
                                                @NonNull String messageNow,
                                                @NonNull OnIdReady cb) {
        JsonObject body = new JsonObject();
        body.addProperty("owner_type", ownerType);
        body.addProperty("owner_id", ticketId);
        body.addProperty("message", messageNow == null ? "" : messageNow);

        String adminUid = authRepository.getUserUid();
        if (!isEmpty(adminUid)) body.addProperty("admin_user_id", adminUid);

        responsesApi.upsertReturn("owner_type,owner_id", body).enqueue(new Callback<List<AdminResponseRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<AdminResponseRow>> call,
                                   @NonNull Response<List<AdminResponseRow>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().isEmpty()) {
                    cb.onReady(null);
                    return;
                }
                cb.onReady(response.body().get(0).id);
            }

            @Override
            public void onFailure(@NonNull Call<List<AdminResponseRow>> call, @NonNull Throwable t) {
                cb.onReady(null);
            }
        });
    }

    private void uploadResponseAttachment(@NonNull String adminResponseId, @NonNull Uri uri) {
        try {
            try {
                final int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}

            FileInfo info = readFileInfo(uri);
            if (info == null || info.bytes == null || info.bytes.length == 0) {
                toast("Не удалось прочитать файл");
                return;
            }

            long max = 20L * 1024 * 1024;
            if (info.bytes.length > max) {
                toast("Файл слишком большой (макс 20MB)");
                return;
            }

            if (!isAllowedMime(info.mimeType)) {
                toast("Разрешены только PDF и JPG/JPEG");
                return;
            }

            String safeName = makeSafeName(info.displayName);
            String objectPath = "admin_responses/" + adminResponseId + "/" + safeName;

            RequestBody rb = RequestBody.create(info.bytes, MediaType.parse(info.mimeType));
            setLoading(true);

            storageApi.uploadObject(BUCKET, objectPath, info.mimeType, rb).enqueue(new Callback<JsonObject>() {
                @Override
                public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                    if (!response.isSuccessful()) {
                        setLoading(false);
                        toast("Не удалось загрузить в Storage: " + response.code() + "\n" + readError(response));
                        return;
                    }

                    JsonObject a = new JsonObject();
                    a.addProperty("owner_type", "admin_response");
                    a.addProperty("owner_id", adminResponseId);
                    a.addProperty("bucket_id", BUCKET);
                    a.addProperty("object_path", objectPath);
                    a.addProperty("file_name", info.displayName);
                    a.addProperty("mime_type", normalizeMime(info.mimeType));
                    a.addProperty("size_bytes", info.bytes.length);

                    attachmentsApi.insert(a).enqueue(new Callback<Void>() {
                        @Override
                        public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> resp) {
                            setLoading(false);
                            if (!resp.isSuccessful()) {
                                toast("Файл загружен, но запись в attachments не создалась: " + resp.code() + "\n" + readError(resp));
                                return;
                            }
                            toast("Файл прикреплён");
                        }

                        @Override
                        public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                            setLoading(false);
                            toast("Сеть (attachments): " + safeMsg(t));
                        }
                    });
                }

                @Override
                public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                    setLoading(false);
                    toast("Сеть (upload): " + safeMsg(t));
                }
            });

        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    // ===== Retrofit =====

    interface ComplaintsApi {
        @GET("complaints")
        @Headers("Accept: application/json")
        Call<List<ComplaintRow>> listComplaints(
                @Query("select") String select,
                @Query("status") String statusEq,
                @Query("order") String order,
                @Query("limit") Integer limit
        );

        @PATCH("complaints")
        @Headers({
                "Content-Type: application/json",
                "Prefer: return=minimal"
        })
        Call<Void> updateStatus(
                @Query("id") String idEq,
                @Body JsonObject body
        );
    }

    interface AdminResponsesApi {
        @GET("admin_responses")
        @Headers("Accept: application/json")
        Call<List<AdminResponseRow>> getByOwner(
                @Query("select") String select,
                @Query("owner_type") String ownerTypeEq,
                @Query("owner_id") String ownerIdEq,
                @Query("limit") Integer limit
        );

        @POST("admin_responses")
        @Headers({
                "Content-Type: application/json",
                "Prefer: resolution=merge-duplicates,return=minimal"
        })
        Call<Void> upsert(
                @Query("on_conflict") String onConflict,
                @Body JsonObject body
        );

        @POST("admin_responses")
        @Headers({
                "Content-Type: application/json",
                "Prefer: resolution=merge-duplicates,return=representation"
        })
        Call<List<AdminResponseRow>> upsertReturn(
                @Query("on_conflict") String onConflict,
                @Body JsonObject body
        );
    }

    interface AttachmentsApi {
        @GET("attachments")
        @Headers("Accept: application/json")
        Call<List<AttachmentRow>> list(
                @Query("select") String select,
                @Query("owner_type") String ownerTypeEq,
                @Query("owner_id") String ownerIdEq,
                @Query("order") String order,
                @Query("limit") Integer limit
        );

        @POST("attachments")
        @Headers({
                "Content-Type: application/json",
                "Prefer: return=minimal"
        })
        Call<Void> insert(@Body JsonObject body);
    }

    interface StorageApi {
        // baseUrl уже .../storage/v1/, поэтому "object/..."
        @POST("object/{bucket}/{path}")
        Call<JsonObject> uploadObject(
                @Path("bucket") String bucket,
                @Path(value = "path", encoded = true) String path,
                @retrofit2.http.Header("Content-Type") String contentType,
                @Body RequestBody body
        );
    }

    static class ComplaintRow {
        @SerializedName("id") String id;
        @SerializedName("public_number") String publicNumber;
        @SerializedName("status") String status;
        @SerializedName("topic") String topic;
        @SerializedName("created_at") String createdAt;
        @SerializedName("description") String description;
        @SerializedName("user_id") String userId;
    }

    static class AdminResponseRow {
        @SerializedName("id") String id;
        @SerializedName("message") String message;
        @SerializedName("updated_at") String updatedAt;
    }

    static class AttachmentRow {
        @SerializedName("id") String id;
        @SerializedName("file_name") String fileName;
        @SerializedName("mime_type") String mimeType;
        @SerializedName("size_bytes") Long sizeBytes;
        @SerializedName("object_path") String objectPath;
        @SerializedName("created_at") String createdAt;
    }

    // ===== Adapter =====

    interface OnComplaintClick { void onClick(@NonNull ComplaintRow c); }

    static class ComplaintsAdapter extends RecyclerView.Adapter<ComplaintsAdapter.VH> {

        private final List<ComplaintRow> items = new ArrayList<>();
        private final OnComplaintClick onClick;

        ComplaintsAdapter(@NonNull OnComplaintClick onClick) { this.onClick = onClick; }

        void submit(@NonNull List<ComplaintRow> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
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
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            subLp.topMargin = dp(parent, 6);
            tvSub.setLayoutParams(subLp);
            root.addView(tvSub);

            return new VH(card, tvTitle, tvSub);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ComplaintRow c = items.get(position);

            String title = isEmpty(c.publicNumber) ? "Жалоба" : c.publicNumber;
            String sub = "Статус: " + statusRu(c.status) + " • " + topicRu(c.topic);

            h.tvTitle.setText(title);
            h.tvSub.setText(sub);

            h.itemView.setOnClickListener(v -> onClick.onClick(c));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTitle;
            final TextView tvSub;
            VH(@NonNull View itemView, @NonNull TextView tvTitle, @NonNull TextView tvSub) {
                super(itemView);
                this.tvTitle = tvTitle;
                this.tvSub = tvSub;
            }
        }

        private static int dp(@NonNull ViewGroup parent, int dp) {
            float d = parent.getResources().getDisplayMetrics().density;
            return Math.round(dp * d);
        }
    }

    // ===== UI helpers =====

    private void showEmpty(boolean empty) {
        if (tvEmpty != null) tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (rv != null) rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        if (swipe != null) swipe.setRefreshing(loading);
        if (btnFilter != null) btnFilter.setEnabled(!loading);
    }

    private void cancelInFlight() {
        Call<List<ComplaintRow>> c = inFlight;
        if (c != null) { try { c.cancel(); } catch (Exception ignored) {} }
        inFlight = null;
    }

    private void goToLoginAndClearTask() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        try { finish(); } catch (Exception ignored) {}
    }

    // ===== helpers =====

    private String readRole() {
        SharedPreferences p = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE);
        String r = p.getString(KEY_ROLE_CACHE, "user");
        if (r == null) return "user";
        r = r.trim().toLowerCase();
        return r.isEmpty() ? "user" : r;
    }

    private boolean isStaff(@Nullable String roleRaw) {
        String r = normalize(roleRaw);
        return r.equals("owner") || r.equals("deputy") || r.equals("admin");
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

    private static String normalize(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

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

    private int findStatusIndex(@Nullable String statusRaw) {
        String s = normalize(statusRaw);
        for (int i = 0; i < EDIT_STATUS_KEYS.length; i++) {
            if (EDIT_STATUS_KEYS[i].equals(s)) return i;
        }
        return 0;
    }

    private static String statusRu(@Nullable String s) {
        String v = normalize(s);
        switch (v) {
            case "new": return "Новая";
            case "in_progress": return "В работе";
            case "resolved": return "Решено";
            case "rejected": return "Отклонено";
            case "cancelled": return "Отменено";
            default: return (v.isEmpty() ? "—" : v);
        }
    }

    private static String topicRu(@Nullable String t) {
        String v = normalize(t);
        switch (v) {
            case "power_violation": return "Нарушение качества эл.энергии";
            case "tech_connection_deadlines_or_refusal": return "Сроки/отказ техприсоединения";
            case "illegal_disconnection": return "Незаконное отключение";
            case "meter_install_replace_violation": return "Нарушения по счётчикам";
            default: return (v.isEmpty() ? "—" : v);
        }
    }

    private static String prettyBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    private static String normalizeMime(@NonNull String mime) {
        String m = mime.trim().toLowerCase(Locale.ROOT);
        if (m.equals("image/jpg")) return "image/jpg";
        if (m.equals("image/jpeg")) return "image/jpeg";
        if (m.equals("application/pdf")) return "application/pdf";
        return m;
    }

    private static boolean isAllowedMime(@Nullable String mime) {
        String m = normalize(mime);
        return m.equals("application/pdf") || m.equals("image/jpeg") || m.equals("image/jpg");
    }

    private static String makeSafeName(@NonNull String name) {
        String n = name.trim();
        if (n.isEmpty()) n = "file";
        n = n.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return System.currentTimeMillis() + "_" + n;
    }

    static class FileInfo {
        byte[] bytes;
        String displayName;
        String mimeType;
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

    @Override
    protected void onDestroy() {
        cancelInFlight();
        super.onDestroy();
    }
}