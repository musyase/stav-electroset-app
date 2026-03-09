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

public class ComplaintDetailsActivity extends AppCompatActivity {

    public static final String EXTRA_COMPLAINT_ID = "complaint_id";
    private static final String BUCKET = "attachments";

    private static final String COMPLAINT_SELECT =
            "id,public_number,status,topic,created_at,description";

    private static final String ADMIN_RESPONSE_SELECT =
            "id,message,updated_at";

    private static final String ATTACH_SELECT =
            "id,file_name,mime_type,size_bytes,object_path,created_at";

    private AuthRepository authRepository;
    private ComplaintsApi complaintsApi;
    private AdminResponsesApi responsesApi;
    private AttachmentsApi attachmentsApi;

    private TextView tvPublicNumber, tvTopic, tvStatus, tvCreatedAt, tvDescription;
    private TextView tvAdminResponseEmpty, tvAdminResponseText;
    private MaterialButton btnMyAttachments, btnAdminAttachments;

    @Nullable private String complaintId;
    @Nullable private String adminResponseId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_complaint_details);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();
        complaintsApi = sl.supabaseRetrofit().create(ComplaintsApi.class);
        responsesApi = sl.supabaseRetrofit().create(AdminResponsesApi.class);
        attachmentsApi = sl.supabaseRetrofit().create(AttachmentsApi.class);

        tvPublicNumber = findViewById(R.id.tvPublicNumber);
        tvTopic = findViewById(R.id.tvTopic);
        tvStatus = findViewById(R.id.tvStatus);
        tvCreatedAt = findViewById(R.id.tvCreatedAt);
        tvDescription = findViewById(R.id.tvDescription);
        tvAdminResponseEmpty = findViewById(R.id.tvAdminResponseEmpty);
        tvAdminResponseText = findViewById(R.id.tvAdminResponseText);
        btnMyAttachments = findViewById(R.id.btnMyAttachments);
        btnAdminAttachments = findViewById(R.id.btnAdminAttachments);

        complaintId = getIntent().getStringExtra(EXTRA_COMPLAINT_ID);

        btnMyAttachments.setOnClickListener(v ->
                showAttachmentsFor("complaint", complaintId, "Мои вложения"));

        btnAdminAttachments.setOnClickListener(v ->
                showAttachmentsFor("admin_response", adminResponseId, "Вложения ответа админа"));

        loadComplaint();
        loadAdminResponse();
    }

    private void loadComplaint() {
        complaintsApi.getById(COMPLAINT_SELECT, "eq." + complaintId)
                .enqueue(new Callback<List<ComplaintRow>>() {
                    @Override
                    public void onResponse(@NonNull Call<List<ComplaintRow>> call,
                                           @NonNull Response<List<ComplaintRow>> response) {

                        if (!response.isSuccessful() || response.body() == null || response.body().isEmpty()) {
                            toast("Жалоба не найдена");
                            finish();
                            return;
                        }

                        render(response.body().get(0));
                    }

                    @Override
                    public void onFailure(@NonNull Call<List<ComplaintRow>> call, @NonNull Throwable t) {
                        toast("Ошибка сети");
                    }
                });
    }

    private void render(ComplaintRow r) {
        tvPublicNumber.setText("Номер: " + safe(r.publicNumber));
        tvTopic.setText("Тема: " + safe(r.topic));
        tvStatus.setText("Статус: " + statusRu(r.status));
        tvCreatedAt.setText("Создано: " + safe(r.createdAt));
        tvDescription.setText("Описание: " + safe(r.description));
    }

    private void loadAdminResponse() {
        responsesApi.getByOwner(ADMIN_RESPONSE_SELECT,
                        "eq.complaint",
                        "eq." + complaintId,
                        1)
                .enqueue(new Callback<List<AdminResponseRow>>() {
                    @Override
                    public void onResponse(@NonNull Call<List<AdminResponseRow>> call,
                                           @NonNull Response<List<AdminResponseRow>> response) {

                        if (response.body() == null || response.body().isEmpty()) {
                            tvAdminResponseEmpty.setVisibility(View.VISIBLE);
                            return;
                        }

                        AdminResponseRow r = response.body().get(0);
                        adminResponseId = r.id;

                        tvAdminResponseEmpty.setVisibility(View.GONE);
                        tvAdminResponseText.setVisibility(View.VISIBLE);
                        tvAdminResponseText.setText(r.message);
                        btnAdminAttachments.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onFailure(@NonNull Call<List<AdminResponseRow>> call, @NonNull Throwable t) {
                        tvAdminResponseEmpty.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void showAttachmentsFor(String ownerType, String ownerId, String title) {
        attachmentsApi.list(ATTACH_SELECT,
                        "eq." + ownerType,
                        "eq." + ownerId,
                        "created_at.desc",
                        100)
                .enqueue(new Callback<List<AttachmentRow>>() {
                    @Override
                    public void onResponse(@NonNull Call<List<AttachmentRow>> call,
                                           @NonNull Response<List<AttachmentRow>> response) {

                        List<AttachmentRow> rows = response.body();
                        if (rows == null || rows.isEmpty()) {
                            toast("Файлов нет");
                            return;
                        }

                        CharSequence[] items = new CharSequence[rows.size()];
                        for (int i = 0; i < rows.size(); i++) {
                            items[i] = rows.get(i).fileName;
                        }

                        new MaterialAlertDialogBuilder(ComplaintDetailsActivity.this)
                                .setTitle(title)
                                .setItems(items, (d, which) -> {
                                    AttachmentRow a = rows.get(which);
                                    Intent i = new Intent(ComplaintDetailsActivity.this, AttachmentViewerActivity.class);
                                    i.putExtra(AttachmentViewerActivity.EXTRA_BUCKET, BUCKET);
                                    i.putExtra(AttachmentViewerActivity.EXTRA_OBJECT_PATH, a.objectPath);
                                    i.putExtra(AttachmentViewerActivity.EXTRA_FILE_NAME, a.fileName);
                                    i.putExtra(AttachmentViewerActivity.EXTRA_MIME, a.mimeType);
                                    startActivity(i);
                                })
                                .show();
                    }

                    @Override
                    public void onFailure(@NonNull Call<List<AttachmentRow>> call, @NonNull Throwable t) {
                        toast("Ошибка загрузки файлов");
                    }
                });
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private static String safe(String s) {
        return s == null ? "—" : s;
    }

    private static String statusRu(String s) {
        if (s == null) return "—";
        switch (s.toLowerCase(Locale.ROOT)) {
            case "new": return "Новая";
            case "in_progress": return "В работе";
            case "resolved": return "Решено";
            case "rejected": return "Отклонено";
            default: return s;
        }
    }

    interface ComplaintsApi {
        @GET("rest/v1/complaints")
        @Headers("Accept: application/json")
        Call<List<ComplaintRow>> getById(
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

    static class ComplaintRow {
        @SerializedName("id") String id;
        @SerializedName("public_number") String publicNumber;
        @SerializedName("status") String status;
        @SerializedName("topic") String topic;
        @SerializedName("created_at") String createdAt;
        @SerializedName("description") String description;
    }

    static class AdminResponseRow {
        @SerializedName("id") String id;
        @SerializedName("message") String message;
        @SerializedName("updated_at") String updatedAt;
    }

    static class AttachmentRow {
        @SerializedName("file_name") String fileName;
        @SerializedName("mime_type") String mimeType;
        @SerializedName("object_path") String objectPath;
    }
}