package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.Query;

public class RequestDetailsActivity extends AppCompatActivity {

    public static final String EXTRA_REQUEST_ID = "request_id";

    private static final String BUCKET = "attachments";

    // requests fields (как в админке)
    private static final String REQUEST_SELECT =
            "id,public_number,status,topic,created_at,description,user_id";

    // admin_responses fields (как в админке)
    private static final String ADMIN_RESPONSE_SELECT =
            "id,message,updated_at";

    // attachments fields (как в админке)
    private static final String ATTACH_SELECT =
            "id,file_name,mime_type,size_bytes,object_path,created_at";

    private AuthRepository authRepository;
    private RequestsApi requestsApi;
    private AdminResponsesApi responsesApi;
    private AttachmentsApi attachmentsApi;

    @Nullable private volatile Call<List<RequestRow>> inFlightRequest;
    @Nullable private volatile Call<List<AdminResponseRow>> inFlightResponse;

    private TextView tvPublicNumber, tvTopic, tvStatus, tvCreatedAt, tvDescription;
    private TextView tvAdminResponseEmpty, tvAdminResponseText;
    private MaterialButton btnMyAttachments, btnAdminAttachments;

    @Nullable private String requestId;
    @Nullable private String adminResponseId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request_details);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setTitle("Заявка");
            tb.setNavigationOnClickListener(v -> finish());
        }

        tvPublicNumber = findViewById(R.id.tvPublicNumber);
        tvTopic = findViewById(R.id.tvTopic);
        tvStatus = findViewById(R.id.tvStatus);
        tvCreatedAt = findViewById(R.id.tvCreatedAt);
        tvDescription = findViewById(R.id.tvDescription);

        tvAdminResponseEmpty = findViewById(R.id.tvAdminResponseEmpty);
        tvAdminResponseText = findViewById(R.id.tvAdminResponseText);

        btnMyAttachments = findViewById(R.id.btnMyAttachments);
        btnAdminAttachments = findViewById(R.id.btnAdminAttachments);

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();
        requestsApi = sl.supabaseRetrofit().create(RequestsApi.class);
        responsesApi = sl.supabaseRetrofit().create(AdminResponsesApi.class);
        attachmentsApi = sl.supabaseRetrofit().create(AttachmentsApi.class);

        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        if (isEmpty(requestId)) {
            toast("Не передан request_id");
            finish();
            return;
        }

        if (btnMyAttachments != null) {
            btnMyAttachments.setOnClickListener(v ->
                    showAttachmentsFor("request", safe(requestId), "Мои вложения"));
        }

        if (btnAdminAttachments != null) {
            btnAdminAttachments.setOnClickListener(v -> {
                if (isEmpty(adminResponseId)) {
                    toast("Пока нет ответа (и файлов тоже)");
                    return;
                }
                showAttachmentsFor("admin_response", safe(adminResponseId), "Вложения ответа админа");
            });
        }

        ensureSessionThenLoad();
    }

    private void ensureSessionThenLoad() {
        if (!isEmpty(authRepository.getAccessToken())) {
            loadAll();
            return;
        }

        authRepository.refresh(result -> runOnUiThread(() -> {
            if (result == null || !result.ok || isEmpty(authRepository.getAccessToken())) {
                toast("Сессия истекла, войдите заново");
                finish();
                return;
            }
            loadAll();
        }));
    }

    private void loadAll() {
        loadRequest();
        loadAdminResponse();
    }

    private void loadRequest() {
        Call<List<RequestRow>> old = inFlightRequest;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) {} }

        Call<List<RequestRow>> call = requestsApi.getById(
                REQUEST_SELECT,
                "eq." + safe(requestId)
        );
        inFlightRequest = call;

        call.enqueue(new Callback<List<RequestRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<RequestRow>> call,
                                   @NonNull Response<List<RequestRow>> response) {
                if (isFinishing() || isDestroyed()) return;

                if (!response.isSuccessful()) {
                    toast("Ошибка заявки: " + response.code());
                    return;
                }

                List<RequestRow> rows = response.body();
                if (rows == null || rows.isEmpty()) {
                    toast("Заявка не найдена");
                    finish();
                    return;
                }

                renderRequest(rows.get(0));
            }

            @Override
            public void onFailure(@NonNull Call<List<RequestRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                toast("Сеть (заявка): " + safeMsg(t));
            }
        });
    }

    private void renderRequest(@NonNull RequestRow r) {
        String num = isEmpty(r.publicNumber) ? "—" : r.publicNumber.trim();
        if (tvPublicNumber != null) tvPublicNumber.setText("Номер: " + num);

        if (tvTopic != null) tvTopic.setText("Тема: " + topicRu(r.topic));
        if (tvStatus != null) tvStatus.setText("Статус: " + statusRu(r.status));
        if (tvCreatedAt != null) tvCreatedAt.setText("Создано: " + safeOrDash(r.createdAt));

        if (tvDescription != null) {
            String d = isEmpty(r.description) ? "—" : r.description;
            tvDescription.setText("Описание: " + d);
        }
    }

    private void loadAdminResponse() {
        Call<List<AdminResponseRow>> old = inFlightResponse;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) {} }

        Call<List<AdminResponseRow>> call = responsesApi.getByOwner(
                ADMIN_RESPONSE_SELECT,
                "eq.request",
                "eq." + safe(requestId),
                1
        );
        inFlightResponse = call;

        call.enqueue(new Callback<List<AdminResponseRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<AdminResponseRow>> call,
                                   @NonNull Response<List<AdminResponseRow>> response) {
                if (isFinishing() || isDestroyed()) return;

                if (!response.isSuccessful()) {
                    showAdminEmpty(true);
                    return;
                }

                List<AdminResponseRow> rows = response.body();
                if (rows == null || rows.isEmpty()) {
                    showAdminEmpty(true);
                    return;
                }

                showAdmin(rows.get(0));
            }

            @Override
            public void onFailure(@NonNull Call<List<AdminResponseRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                showAdminEmpty(true);
            }
        });
    }

    private void showAdminEmpty(boolean empty) {
        if (tvAdminResponseEmpty != null) tvAdminResponseEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (tvAdminResponseText != null) tvAdminResponseText.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (btnAdminAttachments != null) btnAdminAttachments.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (!empty && tvAdminResponseText != null && isEmpty(tvAdminResponseText.getText() + "")) {
            tvAdminResponseText.setText("—");
        }
        if (empty) adminResponseId = null;
    }

    private void showAdmin(@NonNull AdminResponseRow r) {
        adminResponseId = r.id;

        if (tvAdminResponseText != null) {
            String text = isEmpty(r.message) ? "—" : r.message;
            tvAdminResponseText.setText(text);
            tvAdminResponseText.setVisibility(View.VISIBLE);
        }
        if (tvAdminResponseEmpty != null) tvAdminResponseEmpty.setVisibility(View.GONE);

        if (btnAdminAttachments != null) btnAdminAttachments.setVisibility(View.VISIBLE);
    }

    // ===== Attachments =====

    private void showAttachmentsFor(@NonNull String ownerType, @NonNull String ownerId, @NonNull String title) {
        attachmentsApi.list(
                ATTACH_SELECT,
                "eq." + ownerType,
                "eq." + ownerId,
                "created_at.desc",
                100
        ).enqueue(new Callback<List<AttachmentRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<AttachmentRow>> call,
                                   @NonNull Response<List<AttachmentRow>> response) {
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
            String name = safeOrDash(a.fileName);
            String size = a.sizeBytes != null ? (" (" + prettyBytes(a.sizeBytes) + ")") : "";
            items[i] = name + size;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items, (d, which) -> {
                    AttachmentRow a = rows.get(which);
                    if (a == null || isEmpty(a.objectPath)) return;
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
                    isEmpty(a.fileName) ? "Вложение" : a.fileName);
            startActivity(i);
        } catch (Exception e) {
            toast("Не удалось открыть внутри приложения");
        }
    }

    // ===== Retrofit =====

    interface RequestsApi {
        @GET("rest/v1/requests")
        @Headers("Accept: application/json")
        Call<List<RequestRow>> getById(
                @Query("select") String select,
                @Query("id") String idEq
        );
    }

    interface AdminResponsesApi {
        @GET("rest/v1/admin_responses")
        @Headers("Accept: application/json")
        Call<List<AdminResponseRow>> getByOwner(
                @Query("select") String select,
                @Query("owner_type") String ownerTypeEq,
                @Query("owner_id") String ownerIdEq,
                @Query("limit") Integer limit
        );
    }

    interface AttachmentsApi {
        @GET("rest/v1/attachments")
        @Headers("Accept: application/json")
        Call<List<AttachmentRow>> list(
                @Query("select") String select,
                @Query("owner_type") String ownerTypeEq,
                @Query("owner_id") String ownerIdEq,
                @Query("order") String order,
                @Query("limit") Integer limit
        );
    }

    static class RequestRow {
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

    // ===== Utils =====

    private void toast(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String safeOrDash(@Nullable String s) {
        return isEmpty(s) ? "—" : s;
    }

    private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }

    private static String normalize(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String statusRu(@Nullable String s) {
        String v = normalize(s);
        switch (v) {
            case "draft": return "Черновик";
            case "new": return "Новая";
            case "in_progress": return "В работе";
            case "resolved": return "Решено";
            case "rejected": return "Отклонено";
            case "cancelled": return "Отменено";
            default: return v.isEmpty() ? "—" : v;
        }
    }

    private static String topicRu(@Nullable String t) {
        String v = normalize(t);
        switch (v) {
            case "tech_connection": return "Техприсоединение";
            case "power_restore": return "Восстановление электроэнергии";
            case "maintenance_repair": return "Ремонт/обслуживание";
            case "tenders_participation": return "Участие в тендерах";
            default: return v.isEmpty() ? "—" : v;
        }
    }

    private static String prettyBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    @Override
    protected void onDestroy() {
        Call<List<RequestRow>> c1 = inFlightRequest;
        if (c1 != null) { try { c1.cancel(); } catch (Exception ignored) {} }
        inFlightRequest = null;

        Call<List<AdminResponseRow>> c2 = inFlightResponse;
        if (c2 != null) { try { c2.cancel(); } catch (Exception ignored) {} }
        inFlightResponse = null;

        super.onDestroy();
    }
}