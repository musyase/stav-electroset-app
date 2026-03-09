package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.domain.AuthRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.Query;

public class InfoLettersActivity extends AppCompatActivity {

    // ✅ Должно совпадать с ProfileActivity.EXTRA_OPEN_LETTER_ID
    public static final String EXTRA_OPEN_LETTER_ID = "open_letter_id";

    // fallback bucket, если в строке attachments bucket_id пустой
    private static final String DEFAULT_BUCKET = "attachments";

    private SwipeRefreshLayout swipe;
    private RecyclerView rv;
    private TextView tvEmpty;
    private MaterialButton btnFilter; // тут используем как "Обновить"

    private AuthRepository authRepository;
    private InfoLettersApi infoApi;
    private AttachmentsApi attachmentsApi;

    private LettersAdapter adapter;

    @Nullable private Call<List<InfoLetterRow>> inFlight;
    private final AtomicBoolean retryAfterRefreshUsed = new AtomicBoolean(false);

    @Nullable private String pendingOpenLetterId; // ✅ открыть конкретное письмо после загрузки (1 раз)

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Переиспользуем существующий layout-скелет (toolbar + swipe + rv + empty + кнопка сверху).
        setContentView(R.layout.activity_admin_complaints);

        pendingOpenLetterId = getIntent() != null ? getIntent().getStringExtra(EXTRA_OPEN_LETTER_ID) : null;

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setTitle("Информационные письма");
            tb.setNavigationOnClickListener(v -> finish());
        }

        btnFilter = findViewById(R.id.btnFilter);
        swipe = findViewById(R.id.swipe);
        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);

        if (btnFilter != null) {
            btnFilter.setText("Обновить");
            btnFilter.setOnClickListener(v -> ensureSessionThenLoad());
        }

        ServiceLocator sl = ServiceLocator.get(getApplicationContext());
        authRepository = sl.authRepository();

        // ✅ ВАЖНО: у тебя в ProfileActivity используется info_letters_feed,
        // поэтому и тут используем тот же эндпоинт (совпадает с RLS/вью).
        infoApi = sl.supabaseRestRetrofit().create(InfoLettersApi.class);

        attachmentsApi = sl.supabaseRestRetrofit().create(AttachmentsApi.class);

        adapter = new LettersAdapter(this::openLetterDialog);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        if (swipe != null) swipe.setOnRefreshListener(this::ensureSessionThenLoad);

        ensureSessionThenLoad();
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

        setLoading(true);

        // select="*" — безопасно (лишнее Gson проигнорирует)
        // order — по published_at, чтобы было как в профиле
        inFlight = infoApi.list("*", "published_at.desc", 200);

        inFlight.enqueue(new Callback<List<InfoLetterRow>>() {
            @Override
            public void onResponse(@NonNull Call<List<InfoLetterRow>> call,
                                   @NonNull Response<List<InfoLetterRow>> response) {
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
                    toast("Ошибка загрузки: " + response.code());
                    return;
                }

                List<InfoLetterRow> rows = response.body();
                if (rows == null) rows = new ArrayList<>();

                adapter.submit(rows);
                showEmpty(rows.isEmpty());
                setLoading(false);

                // ✅ Авто-открытие конкретного письма (если пришли из ProfileActivity по клику)
                tryAutoOpen(rows);
            }

            @Override
            public void onFailure(@NonNull Call<List<InfoLetterRow>> call, @NonNull Throwable t) {
                if (call.isCanceled()) return;
                runOnUiThread(() -> {
                    setLoading(false);
                    showEmpty(true);
                    toast("Сеть: " + safeMsg(t));
                });
            }
        });
    }

    private void tryAutoOpen(@NonNull List<InfoLetterRow> rows) {
        String id = pendingOpenLetterId;
        if (isEmpty(id)) return;

        pendingOpenLetterId = null; // ✅ строго 1 раз

        int pos = -1;
        InfoLetterRow found = null;
        for (int i = 0; i < rows.size(); i++) {
            InfoLetterRow r = rows.get(i);
            if (r != null && id.equals(r.id)) {
                pos = i;
                found = r;
                break;
            }
        }

        if (found == null) {
            toast("Письмо не найдено (возможно, нет доступа или оно удалено)");
            return;
        }

        if (rv != null && pos >= 0) {
            try { rv.scrollToPosition(pos); } catch (Exception ignored) {}
        }
        openLetterDialog(found);
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

    private void openLetterDialog(@NonNull InfoLetterRow l) {
        String title = safe(l.title);
        if (title.trim().isEmpty()) title = "Информационное письмо";

        String body = safe(l.body);
        if (body.trim().isEmpty()) body = "—";

        String finalTitle = title;
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("Вложения", (d, w) -> {
                    if (isEmpty(l.id)) {
                        toast("Не найден id письма");
                        return;
                    }
                    loadAndShowAttachments(l.id, finalTitle);
                })
                .show();
    }

    private void loadAndShowAttachments(@NonNull String letterId, @NonNull String title) {
        attachmentsApi.list(
                "id,bucket_id,file_name,mime_type,size_bytes,object_path,created_at",
                "eq.info_letter",
                "eq." + letterId,
                "created_at.desc",
                50
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
                showAttachmentsDialog("Вложения • " + title, rows);
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
                    openAttachmentInApp(a);
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void openAttachmentInApp(@NonNull AttachmentRow a) {
        try {
            String bucket = isEmpty(a.bucketId) ? DEFAULT_BUCKET : a.bucketId;

            Intent i = new Intent(this, AttachmentViewerActivity.class);
            i.putExtra(AttachmentViewerActivity.EXTRA_BUCKET, bucket);
            i.putExtra(AttachmentViewerActivity.EXTRA_OBJECT_PATH, a.objectPath);
            i.putExtra(AttachmentViewerActivity.EXTRA_FILE_NAME, a.fileName);
            i.putExtra(AttachmentViewerActivity.EXTRA_MIME, a.mimeType);
            i.putExtra(AttachmentViewerActivity.EXTRA_TITLE,
                    (a.fileName == null || a.fileName.trim().isEmpty()) ? "Вложение" : a.fileName);
            startActivity(i);
        } catch (Exception e) {
            toast("Не удалось открыть вложение");
        }
    }

    // ===== Retrofit =====

    interface InfoLettersApi {
        // ✅ ВАЖНО: именно feed (как в ProfileActivity)
        @GET("info_letters_feed")
        @Headers("Accept: application/json")
        Call<List<InfoLetterRow>> list(
                @Query("select") String select,
                @Query("order") String order,
                @Query("limit") Integer limit
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
    }

    static class InfoLetterRow {
        @SerializedName("id") String id;
        @SerializedName("title") String title;
        @SerializedName("body") String body;
        @SerializedName("created_at") String createdAt;
        @SerializedName("published_at") String publishedAt;

        @SerializedName("is_system") Boolean isSystem;
        @SerializedName("system_key") String systemKey;
        @SerializedName("target_user_id") String targetUserId;
        @SerializedName("scope") String scope;
    }

    static class AttachmentRow {
        @SerializedName("id") String id;
        @SerializedName("bucket_id") String bucketId;
        @SerializedName("file_name") String fileName;
        @SerializedName("mime_type") String mimeType;
        @SerializedName("size_bytes") Long sizeBytes;
        @SerializedName("object_path") String objectPath;
        @SerializedName("created_at") String createdAt;
    }

    // ===== Adapter =====

    interface OnLetterClick { void onClick(@NonNull InfoLetterRow l); }

    static class LettersAdapter extends RecyclerView.Adapter<LettersAdapter.VH> {

        private final List<InfoLetterRow> items = new ArrayList<>();
        private final OnLetterClick onClick;

        LettersAdapter(@NonNull OnLetterClick onClick) { this.onClick = onClick; }

        void submit(@NonNull List<InfoLetterRow> list) {
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
            InfoLetterRow l = items.get(position);

            String title = safe(l.title);
            if (title.trim().isEmpty()) title = "Информационное письмо";

            String body = safe(l.body);
            body = body.replace("\n", " ").trim();
            if (body.length() > 120) body = body.substring(0, 120) + "…";
            if (body.isEmpty()) body = "—";

            h.tvTitle.setText(title);
            h.tvSub.setText(body);

            h.itemView.setOnClickListener(v -> onClick.onClick(l));
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
        if (tvEmpty != null) {
            tvEmpty.setText(empty ? "Писем нет" : "");
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (rv != null) rv.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        if (swipe != null) swipe.setRefreshing(loading);
        if (btnFilter != null) btnFilter.setEnabled(!loading);
    }

    private void cancelInFlight() {
        Call<List<InfoLetterRow>> c = inFlight;
        if (c != null) { try { c.cancel(); } catch (Exception ignored) {} }
        inFlight = null;
    }

    private void goToLoginAndClearTask() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        try { finish(); } catch (Exception ignored) {}
    }

    private void toast(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(@Nullable String s) { return s == null ? "" : s; }

    private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }

    private static String prettyBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }
}