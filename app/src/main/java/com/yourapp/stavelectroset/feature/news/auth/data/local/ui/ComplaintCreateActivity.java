package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.yourapp.stavelectroset.MainActivity;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.news.consumers.ConsumersMapActivity;
import com.yourapp.stavelectroset.web.WebPageActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;

public class ComplaintCreateActivity extends AppCompatActivity {

    private static final int MAX_FILES = 5;
    private static final long MAX_BYTES = 5L * 1024L * 1024L; // 5MB per file
    private static final String BUCKET = "attachments";

    private static final TopicItem[] COMPLAINT_TOPICS = new TopicItem[]{
            new TopicItem("Нарушение в электроснабжении", "power_violation"),
            new TopicItem("Нарушение сроков или отказ в технологическом присоединении", "tech_connection_deadlines_or_refusal"),
            new TopicItem("Неправомерное отключение от сети", "illegal_disconnection"),
            new TopicItem("Нарушения при установке или замене приборов учета (счетчиков)", "meter_install_replace_violation"),
    };

    private TextInputLayout tilTopic, tilMessage;
    private MaterialAutoCompleteTextView actTopic;
    private TextInputEditText etMessage;

    private android.widget.TextView tvFilesCounter;
    private com.google.android.material.chip.ChipGroup chipFiles;
    private com.google.android.material.button.MaterialButton btnPickFiles, btnSubmit;

    private AuthRepository authRepository;
    private ExecutorService io;

    private ComplaintsRpcApi complaintsRpcApi;
    private AttachmentsApi attachmentsApi;
    private StorageApi storageApi;

    private final List<PickedFile> pickedFiles = new ArrayList<>();
    private final Set<String> pickedUriSet = new HashSet<>();

    private ActivityResultLauncher<String[]> pickFilesLauncher;
    private boolean submitting = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_complaint);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> finish());
        }
        setTitle("Жалоба");

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();
        io = sl.ioExecutor();

        complaintsRpcApi = sl.supabaseRetrofit().create(ComplaintsRpcApi.class);
        attachmentsApi = sl.supabaseRetrofit().create(AttachmentsApi.class);
        storageApi = sl.supabaseRetrofit().create(StorageApi.class);

        bindViews();
        setupTopicDropdown();
        setupPickFiles();
        setupValidation();
        setupBottomNav();

        btnPickFiles.setOnClickListener(v -> openPicker());
        btnSubmit.setOnClickListener(v -> submit());

        updateFilesCounter();
        updateUiState();
    }

    private void bindViews() {
        tilTopic = findViewById(R.id.tilTopic);
        tilMessage = findViewById(R.id.tilMessage);
        actTopic = findViewById(R.id.actTopic);
        etMessage = findViewById(R.id.etMessage);

        tvFilesCounter = findViewById(R.id.tvFilesCounter);
        chipFiles = findViewById(R.id.chipFiles);

        btnPickFiles = findViewById(R.id.btnPickFiles);
        btnSubmit = findViewById(R.id.btnSubmit);
    }

    private void setupTopicDropdown() {
        List<String> labels = new ArrayList<>();
        for (TopicItem t : COMPLAINT_TOPICS) labels.add(t.label);

        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        actTopic.setAdapter(adapter);

        actTopic.setOnItemClickListener((parent, view, position, id) -> {
            tilTopic.setError(null);
            updateUiState();
        });
    }

    private void setupPickFiles() {
        pickFilesLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris == null) return;
                    handlePickedUris(uris);
                }
        );
    }

    private void openPicker() {
        if (submitting) return;

        if (pickedFiles.size() >= MAX_FILES) {
            toast("Можно прикрепить максимум " + MAX_FILES + " файлов");
            return;
        }

        pickFilesLauncher.launch(new String[]{
                "application/pdf",
                "image/jpeg"
        });
    }

    private void handlePickedUris(@NonNull List<Uri> uris) {
        int skippedTooBig = 0;
        int skippedLimit = 0;
        int added = 0;

        for (Uri uri : uris) {
            if (pickedFiles.size() >= MAX_FILES) {
                skippedLimit++;
                break;
            }
            if (uri == null) continue;

            // ✅ ВОТ ЗДЕСЬ uri виден
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {}

            String key = uri.toString();
            if (pickedUriSet.contains(key)) continue;

            PickedFile meta = readMeta(uri);
            if (meta == null) continue;

            if (meta.sizeBytes > 0 && meta.sizeBytes > MAX_BYTES) {
                skippedTooBig++;
                continue;
            }

            pickedFiles.add(meta);
            pickedUriSet.add(key);
            added++;
        }

        renderChips();
        updateFilesCounter();
        updateUiState();

        if (added > 0) toast("Добавлено файлов: " + added);
        if (skippedTooBig > 0) toast("Пропущено (больше 5 МБ): " + skippedTooBig);
        if (skippedLimit > 0) toast("Лимит файлов: максимум " + MAX_FILES);
    }


    @Nullable
    private PickedFile readMeta(@NonNull Uri uri) {
        String name = null;
        long size = -1;

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);

                if (nameIdx >= 0) name = cursor.getString(nameIdx);
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Exception ignored) {}
        }

        if (TextUtils.isEmpty(name)) {
            String last = uri.getLastPathSegment();
            name = (last == null) ? "file" : last;
        }

        String mime = null;
        try { mime = getContentResolver().getType(uri); } catch (Exception ignored) {}

        return new PickedFile(uri, name, size, mime);
    }

    private void renderChips() {
        if (chipFiles == null) return;
        chipFiles.removeAllViews();

        for (PickedFile f : pickedFiles) {
            Chip chip = new Chip(this);
            chip.setText(formatChipText(f));
            chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> removeFile(f));
            chipFiles.addView(chip);
        }
    }

    private String formatChipText(@NonNull PickedFile f) {
        String base = f.displayName;
        if (f.sizeBytes > 0) return base + " (" + humanSize(f.sizeBytes) + ")";
        return base;
    }

    private void removeFile(@NonNull PickedFile f) {
        pickedFiles.remove(f);
        pickedUriSet.remove(f.uri.toString());
        renderChips();
        updateFilesCounter();
        updateUiState();
    }

    private void updateFilesCounter() {
        if (tvFilesCounter != null) {
            tvFilesCounter.setText("Файлов: " + pickedFiles.size() + "/" + MAX_FILES + " (необязательно)");
        }
    }

    private void setupValidation() {
        etMessage.addTextChangedListener(simpleWatcher(() -> {
            tilMessage.setError(null);
            updateUiState();
        }));

        actTopic.addTextChangedListener(simpleWatcher(() -> {
            tilTopic.setError(null);
            updateUiState();
        }));
    }

    private void updateUiState() {
        boolean topicOk = !isEmpty(findTopicKeyByLabel(textOf(actTopic))); // строго из списка
        boolean msgOk = !isEmpty(textOf(etMessage));
        boolean canSubmit = topicOk && msgOk && !submitting;

        if (btnSubmit != null) btnSubmit.setEnabled(canSubmit);
        if (btnPickFiles != null) btnPickFiles.setEnabled(!submitting);
        if (btnSubmit != null) btnSubmit.setText(submitting ? "Отправка..." : "Отправить жалобу");
    }

    private void setSubmitting(boolean v) {
        submitting = v;
        updateUiState();
    }

    private void submit() {
        String topicLabel = textOf(actTopic);
        String topicKey = findTopicKeyByLabel(topicLabel);
        String msg = textOf(etMessage);

        boolean ok = true;

        if (isEmpty(topicKey)) {
            tilTopic.setError("Выберите тему из списка");
            ok = false;
        } else {
            tilTopic.setError(null);
        }

        if (isEmpty(msg)) {
            tilMessage.setError("Опишите ситуацию");
            ok = false;
        } else {
            tilMessage.setError(null);
        }

        if (!ok) return;

        if (authRepository == null || isEmpty(authRepository.getAccessToken())) {
            toast("Нет сессии. Войдите заново.");
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setSubmitting(true);

        io.execute(() -> {
            String uid = authRepository.getUserUid();
            if (isEmpty(uid)) uid = jwtGetString(authRepository.getAccessToken(), "sub");

            if (isEmpty(uid)) {
                runOnUiThread(() -> {
                    setSubmitting(false);
                    toast("Не удалось прочитать пользователя, войдите заново");
                });
                return;
            }

            String complaintId = null;
            final List<String> uploadedPaths = new ArrayList<>();

            try {
                // 1) create complaint (status=new)
                Response<ResponseBody> r1 = complaintsRpcApi.createComplaint(new CreateComplaintBody(topicKey, msg)).execute();
                if (!r1.isSuccessful()) {
                    throw new RuntimeException("create complaint: " + r1.code() + " / " + readErr(r1));
                }

                String raw = (r1.body() != null) ? r1.body().string() : "";
                complaintId = parseRpcUuid(raw);
                if (isEmpty(complaintId)) throw new RuntimeException("create complaint: empty id");

                // 2) если файлов нет — просто успех
                if (pickedFiles.isEmpty()) {
                    String finalId = complaintId;
                    runOnUiThread(() -> {
                        setSubmitting(false);
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("Жалоба отправлена")
                                .setMessage("ID жалобы: " + finalId)
                                .setPositiveButton("Ок", (d, w) -> finish())
                                .show();
                    });
                    return;
                }

                // 3) upload + insert attachments
                for (PickedFile pf : pickedFiles) {
                    byte[] bytes = readAllBytes(pf.uri);
                    if (bytes == null) throw new RuntimeException("Не удалось прочитать файл: " + pf.displayName);

                    if (bytes.length > MAX_BYTES) {
                        throw new RuntimeException("Файл больше 5 МБ: " + pf.displayName);
                    }

                    String mime = normalizeMime(pf.mime, pf.displayName);
                    if (!isAllowedMime(mime)) {
                        throw new RuntimeException("Недопустимый тип файла: " + pf.displayName + " (" + mime + ")");
                    }

                    String safeName = sanitizeFileName(pf.displayName);

                    // ВАЖНО под твою Storage policy:
                    // complaints/{uid}/{complaintId}/...
                    String objectPath = "complaints/" + uid + "/" + complaintId + "/" +
                            System.currentTimeMillis() + "_" + UUID.randomUUID() + "_" + safeName;

                    MediaType mt = MediaType.parse(mime);
                    if (mt == null) mt = MediaType.parse("application/octet-stream");
                    RequestBody body = RequestBody.create(bytes, mt);

                    Response<ResponseBody> up = storageApi.uploadObject(BUCKET, objectPath, body, "false").execute();
                    if (!up.isSuccessful()) {
                        throw new RuntimeException("upload failed: " + up.code() + " / " + readErr(up));
                    }

                    uploadedPaths.add(objectPath);

                    AttachmentInsert ins = new AttachmentInsert(
                            "complaint",
                            complaintId,
                            uid,
                            BUCKET,
                            objectPath,
                            pf.displayName,
                            mime,
                            bytes.length
                    );

                    Response<ResponseBody> insRes = attachmentsApi.insertAttachment(ins, "return=minimal").execute();
                    if (!insRes.isSuccessful()) {
                        try { storageApi.deleteObject(BUCKET, objectPath).execute(); } catch (Exception ignored) {}
                        throw new RuntimeException("attachments insert failed: " + insRes.code() + " / " + readErr(insRes));
                    }
                }

                String finalId = complaintId;
                runOnUiThread(() -> {
                    setSubmitting(false);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Жалоба отправлена")
                            .setMessage("ID жалобы: " + finalId + "\nФайлов: " + pickedFiles.size())
                            .setPositiveButton("Ок", (d, w) -> finish())
                            .show();
                });

            } catch (Exception e) {
                // ВАЖНО: сначала удаляем объекты (пока complaint.status = 'new' -> delete policy пускает),
                // потом отменяем жалобу.
                for (String path : uploadedPaths) {
                    try { storageApi.deleteObject(BUCKET, path).execute(); } catch (Exception ignored) {}
                }
                if (!isEmpty(complaintId)) {
                    try { complaintsRpcApi.cancelComplaint(new CancelBody(complaintId)).execute(); } catch (Exception ignored) {}
                }

                String err = (e.getMessage() == null) ? "Ошибка" : trimForUi(e.getMessage());
                runOnUiThread(() -> {
                    setSubmitting(false);
                    toast(err);
                });
            }
        });
    }

    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        if (bottomNav == null) return;

        try { bottomNav.setSelectedItemId(R.id.nav_profile); } catch (Exception ignored) {}

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_news) {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
                return true;
            } else if (id == R.id.nav_map) {
                try {
                    startActivity(new Intent(this, ConsumersMapActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (Exception e) {
                    startActivity(new Intent(this, WebPageActivity.class).putExtra("open_tab", "company"));
                }
                return true;
            } else if (id == R.id.nav_profile) {
                finish();
                return true;
            }
            return false;
        });
    }

    // ===== Retrofit APIs =====

    interface ComplaintsRpcApi {
        @POST("rest/v1/rpc/rpc_create_complaint")
        Call<ResponseBody> createComplaint(@Body CreateComplaintBody body);

        @POST("rest/v1/rpc/rpc_cancel_complaint")
        Call<ResponseBody> cancelComplaint(@Body CancelBody body);
    }

    interface AttachmentsApi {
        @POST("rest/v1/attachments")
        Call<ResponseBody> insertAttachment(@Body AttachmentInsert body,
                                            @retrofit2.http.Header("Prefer") String prefer);
    }

    interface StorageApi {
        @Headers({"x-client-info: android"})
        @POST("storage/v1/object/{bucket}/{path}")
        Call<ResponseBody> uploadObject(
                @Path("bucket") String bucket,
                @Path(value = "path", encoded = true) String path,
                @Body RequestBody body,
                @retrofit2.http.Header("x-upsert") String upsert
        );

        @DELETE("storage/v1/object/{bucket}/{path}")
        Call<ResponseBody> deleteObject(
                @Path("bucket") String bucket,
                @Path(value = "path", encoded = true) String path
        );
    }

    static class CreateComplaintBody {
        public final String p_topic;
        public final String p_description;
        CreateComplaintBody(String topic, String description) {
            this.p_topic = topic;
            this.p_description = description;
        }
    }

    static class CancelBody {
        public final String p_complaint_id;
        CancelBody(String id) { this.p_complaint_id = id; }
    }

    static class AttachmentInsert {
        public final String owner_type;
        public final String owner_id;
        public final String user_id;

        public final String bucket_id;
        public final String object_path;
        public final String file_name;
        public final String mime_type;
        public final long size_bytes;

        AttachmentInsert(String ownerType, String ownerId, String userId,
                         String bucketId, String objectPath,
                         String fileName, String mimeType, long sizeBytes) {
            this.owner_type = ownerType;
            this.owner_id = ownerId;
            this.user_id = userId;
            this.bucket_id = bucketId;
            this.object_path = objectPath;
            this.file_name = fileName;
            this.mime_type = mimeType;
            this.size_bytes = sizeBytes;
        }
    }

    // ===== Utils =====

    @Nullable
    private byte[] readAllBytes(@NonNull Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > (int) MAX_BYTES + 1024) break;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAllowedMime(@Nullable String mime) {
        if (mime == null) return false;
        return "application/pdf".equalsIgnoreCase(mime) || "image/jpeg".equalsIgnoreCase(mime);
    }

    @NonNull
    private static String normalizeMime(@Nullable String mime, @NonNull String fileName) {
        if (mime != null) {
            String m = mime.trim().toLowerCase();
            if ("image/jpg".equals(m)) return "image/jpeg";
            return m;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String name) {
        String n = name.replace("/", "_").replace("\\", "_").trim();
        n = n.replaceAll("[^a-zA-Z0-9._\\-() ]", "_");
        n = n.replace(" ", "_");
        if (n.length() > 120) n = n.substring(0, 120);
        if (n.isEmpty()) n = "file";
        return n;
    }

    @Nullable
    private static String findTopicKeyByLabel(@Nullable String label) {
        if (label == null) return null;
        String t = label.trim();
        for (TopicItem it : COMPLAINT_TOPICS) {
            if (it.label.equalsIgnoreCase(t)) return it.key;
        }
        return null;
    }

    private static TextWatcher simpleWatcher(@NonNull Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { r.run(); }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private static String textOf(@Nullable android.widget.TextView tv) {
        return tv == null ? "" : String.valueOf(tv.getText()).trim();
    }

    private void toast(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        long kb = bytes / 1024;
        if (kb < 1024) return kb + " KB";
        long mb = kb / 1024;
        return mb + " MB";
    }

    @NonNull
    private static String parseRpcUuid(@Nullable String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t.trim();
    }

    @NonNull
    private static String readErr(@NonNull Response<?> r) {
        try {
            if (r.errorBody() != null) return r.errorBody().string();
        } catch (Exception ignored) {}
        return "";
    }

    @NonNull
    private static String trimForUi(@NonNull String s) {
        String t = s.replace("\n", " ").replace("\r", " ").trim();
        return t.length() > 220 ? (t.substring(0, 220) + "...") : t;
    }

    @Nullable
    private static String jwtGetString(@NonNull String jwt, @NonNull String key) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;

            byte[] decoded = android.util.Base64.decode(parts[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);

            org.json.JSONObject obj = new org.json.JSONObject(new String(decoded));
            if (!obj.has(key)) return null;
            return obj.optString(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    static class TopicItem {
        final String label;
        final String key;
        TopicItem(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }

    static class PickedFile {
        final Uri uri;
        final String displayName;
        final long sizeBytes;
        final String mime;

        PickedFile(Uri uri, String displayName, long sizeBytes, String mime) {
            this.uri = uri;
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
            this.mime = mime;
        }
    }
}
