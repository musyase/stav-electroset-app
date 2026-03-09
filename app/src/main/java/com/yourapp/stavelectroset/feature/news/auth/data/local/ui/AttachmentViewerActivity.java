package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;

public class AttachmentViewerActivity extends AppCompatActivity {

    public static final String EXTRA_BUCKET = "bucket";
    public static final String EXTRA_OBJECT_PATH = "object_path";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_TITLE = "title";

    private static final String DEFAULT_BUCKET = "attachments";

    private MaterialToolbar toolbar;
    private ProgressBar progress;

    private ImageView ivImage;

    private View pdfPanel;
    private ImageView ivPdf;
    private Button btnPrev;
    private Button btnNext;
    private TextView tvPdfPage;

    private ScrollView textPanel;
    private TextView tvText;

    private Button btnOpenExternal;

    private StorageApi storageApi;
    private final OkHttpClient http = new OkHttpClient();

    // pdf
    @Nullable private PdfRenderer pdfRenderer;
    @Nullable private ParcelFileDescriptor pfd;
    private int pdfPageIndex = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attachment_viewer);

        toolbar = findViewById(R.id.toolbar);
        progress = findViewById(R.id.progress);

        ivImage = findViewById(R.id.ivImage);

        pdfPanel = findViewById(R.id.pdfPanel);
        ivPdf = findViewById(R.id.ivPdf);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        tvPdfPage = findViewById(R.id.tvPdfPage);

        textPanel = findViewById(R.id.textPanel);
        tvText = findViewById(R.id.tvText);

        btnOpenExternal = findViewById(R.id.btnOpenExternal);

        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        // ✅ ВАЖНО: теперь берём Retrofit именно для Storage (/storage/v1/)
        storageApi = ServiceLocator.get(getApplicationContext())
                .supabaseStorageRetrofit()
                .create(StorageApi.class);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "Вложение";
        if (toolbar != null) toolbar.setTitle(title);

        String bucket = getIntent().getStringExtra(EXTRA_BUCKET);
        if (bucket == null || bucket.trim().isEmpty()) bucket = DEFAULT_BUCKET;

        String objectPath = getIntent().getStringExtra(EXTRA_OBJECT_PATH);
        String fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        String mime = getIntent().getStringExtra(EXTRA_MIME);

        if (objectPath == null || objectPath.trim().isEmpty()) {
            toast("object_path пустой");
            finish();
            return;
        }

        setLoading(true);

        // 1) Получаем signed url
        JsonObject body = new JsonObject();
        body.addProperty("expiresIn", 600);

        String finalBucket = bucket;
        storageApi.signObject(bucket, objectPath, body).enqueue(new Callback<SignedUrlResp>() {
            @Override
            public void onResponse(@NonNull Call<SignedUrlResp> call, @NonNull Response<SignedUrlResp> response) {
                if (!response.isSuccessful() || response.body() == null || isEmpty(response.body().signedURL)) {
                    setLoading(false);
                    toast("Не удалось получить ссылку: " + response.code());
                    finish();
                    return;
                }

                // ✅ Берём нормализованный baseUrl из ServiceLocator
                String base = ServiceLocator.get(getApplicationContext()).supabaseBaseUrl();
                String signed = response.body().signedURL;

                // signedURL часто приходит как "/object/sign/...."
                // полный будет: https://xxx.supabase.co/storage/v1 + signedURL
                String fullUrl = signed.startsWith("http")
                        ? signed
                        : base + "storage/v1" + (signed.startsWith("/") ? signed : ("/" + signed));

                // 2) Скачиваем файл в cache и открываем внутри приложения
                downloadToCacheAndOpen(fullUrl, fileName, mime, finalBucket, objectPath);
            }

            @Override
            public void onFailure(@NonNull Call<SignedUrlResp> call, @NonNull Throwable t) {
                setLoading(false);
                toast("Сеть: " + safeMsg(t));
                finish();
            }
        });
    }

    private void downloadToCacheAndOpen(@NonNull String url,
                                        @Nullable String fileName,
                                        @Nullable String mime,
                                        @NonNull String bucket,
                                        @NonNull String objectPath) {
        new Thread(() -> {
            try {
                Request req = new Request.Builder().url(url).get().build();
                okhttp3.Response res = http.newCall(req).execute();
                if (!res.isSuccessful() || res.body() == null) {
                    runOnUiThread(() -> {
                        setLoading(false);
                        toast("Не удалось скачать файл: " + res.code());
                        finish();
                    });
                    return;
                }

                String guessedMime = mime;
                if (isEmpty(guessedMime)) guessedMime = guessMime(fileName, res.body());

                File out = new File(getCacheDir(), makeCacheName(fileName, guessedMime));
                try (ResponseBody body = res.body();
                     InputStream in = body.byteStream();
                     FileOutputStream fos = new FileOutputStream(out)) {

                    byte[] buf = new byte[16 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                }

                String finalMime = normalize(guessedMime);

                runOnUiThread(() -> {
                    setLoading(false);
                    showOnlyNone();

                    // кнопка "открыть внешним" на всякий случай
                    if (btnOpenExternal != null) {
                        btnOpenExternal.setVisibility(View.VISIBLE);
                        String finalUrl = url;
                        btnOpenExternal.setOnClickListener(v -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)));
                            } catch (Exception e) {
                                toast("Не удалось открыть внешним приложением");
                            }
                        });
                    }

                    if (finalMime.startsWith("image/")) {
                        showImage(out);
                        return;
                    }

                    if (finalMime.equals("application/pdf")) {
                        showPdf(out);
                        return;
                    }

                    // DOCX: показываем текст (упрощенно)
                    if (finalMime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                            || (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".docx"))) {
                        showDocxAsText(out);
                        return;
                    }

                    // TXT / JSON / etc — тоже текстом
                    if (finalMime.startsWith("text/") || finalMime.contains("json")) {
                        showFileAsText(out);
                        return;
                    }

                    // остальное — внешним приложением по ссылке
                    toast("Формат не поддержан внутри приложения, открою внешне");
                    if (btnOpenExternal != null) btnOpenExternal.performClick();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    toast("Ошибка: " + e.getMessage());
                    finish();
                });
            }
        }).start();
    }

    private void showImage(@NonNull File file) {
        if (ivImage == null) return;
        try {
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            ivImage.setImageBitmap(bmp);
            ivImage.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            toast("Не удалось показать изображение");
        }
    }

    private void showPdf(@NonNull File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            toast("PDF внутри приложения: нужен Android 5.0+");
            return;
        }
        if (pdfPanel == null || ivPdf == null || btnPrev == null || btnNext == null || tvPdfPage == null) return;

        try {
            closePdf();

            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(pfd);
            pdfPageIndex = 0;

            pdfPanel.setVisibility(View.VISIBLE);
            renderPdfPage();

            btnPrev.setOnClickListener(v -> {
                if (pdfRenderer == null) return;
                if (pdfPageIndex > 0) {
                    pdfPageIndex--;
                    renderPdfPage();
                }
            });

            btnNext.setOnClickListener(v -> {
                if (pdfRenderer == null) return;
                if (pdfPageIndex < pdfRenderer.getPageCount() - 1) {
                    pdfPageIndex++;
                    renderPdfPage();
                }
            });

        } catch (Exception e) {
            toast("Не удалось открыть PDF");
        }
    }

    private void renderPdfPage() {
        if (pdfRenderer == null || ivPdf == null || tvPdfPage == null) return;

        PdfRenderer.Page page = null;
        try {
            page = pdfRenderer.openPage(pdfPageIndex);

            int w = Math.max(1, page.getWidth());
            int h = Math.max(1, page.getHeight());

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            ivPdf.setImageBitmap(bmp);
            tvPdfPage.setText((pdfPageIndex + 1) + " / " + pdfRenderer.getPageCount());
        } catch (Exception e) {
            toast("Ошибка PDF");
        } finally {
            if (page != null) page.close();
        }
    }

    /**
     * DOCX -> текст.
     * Чтобы это работало, нужно добавить зависимость Apache POI (как у тебя уже было/будет в проекте).
     */
    private void showDocxAsText(@NonNull File file) {
        if (textPanel == null || tvText == null) return;

        try {
            // Apache POI (если нет зависимости, будет ClassNotFound)
            String text = DocxTextExtractor.extract(file);
            tvText.setText(text == null || text.trim().isEmpty() ? "Документ пустой" : text);
            textPanel.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            toast("DOCX не получилось разобрать. Открой внешним приложением.");
        }
    }

    private void showFileAsText(@NonNull File file) {
        if (textPanel == null || tvText == null) return;
        try {
            String txt = SimpleTextReader.read(file, 200_000); // ограничим размер
            tvText.setText(txt == null || txt.trim().isEmpty() ? "Файл пустой" : txt);
            textPanel.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            toast("Не удалось показать текст");
        }
    }

    private void setLoading(boolean loading) {
        if (progress != null) progress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showOnlyNone() {
        if (ivImage != null) ivImage.setVisibility(View.GONE);
        if (pdfPanel != null) pdfPanel.setVisibility(View.GONE);
        if (textPanel != null) textPanel.setVisibility(View.GONE);
        if (btnOpenExternal != null) btnOpenExternal.setVisibility(View.GONE);
    }

    private void closePdf() {
        try { if (pdfRenderer != null) pdfRenderer.close(); } catch (Exception ignored) {}
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        pdfRenderer = null;
        pfd = null;
    }

    @Override
    protected void onDestroy() {
        closePdf();
        super.onDestroy();
    }

    // ===== Retrofit =====
    interface StorageApi {
        // ✅ Теперь baseUrl уже .../storage/v1/, поэтому тут без "storage/v1/"
        @POST("object/sign/{bucket}/{path}")
        @Headers("Content-Type: application/json")
        Call<SignedUrlResp> signObject(
                @Path("bucket") String bucket,
                @Path(value = "path", encoded = true) String path,
                @Body JsonObject body
        );
    }

    static class SignedUrlResp {
        @SerializedName("signedURL") String signedURL;
    }

    // ===== Utils =====
    private void toast(@NonNull String msg) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeMsg(@NonNull Throwable t) {
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? "неизвестная ошибка" : m.trim();
    }

    private static String normalize(@Nullable String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String makeCacheName(@Nullable String fileName, @Nullable String mime) {
        String ext = "";
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) ext = fileName.substring(dot);
        }
        if (ext.isEmpty() && mime != null) {
            if (mime.equals("application/pdf")) ext = ".pdf";
            else if (mime.startsWith("image/")) ext = ".jpg";
            else if (mime.contains("wordprocessingml")) ext = ".docx";
        }
        return "att_" + System.currentTimeMillis() + ext;
    }

    private static String guessMime(@Nullable String fileName, @NonNull ResponseBody body) {
        try {
            String ct = body.contentType() != null ? body.contentType().toString() : null;
            if (ct != null && !ct.trim().isEmpty()) return ct;
        } catch (Exception ignored) {}

        if (fileName != null) {
            String guessed = URLConnection.guessContentTypeFromName(fileName);
            if (guessed != null) return guessed;
        }
        return "application/octet-stream";
    }
}