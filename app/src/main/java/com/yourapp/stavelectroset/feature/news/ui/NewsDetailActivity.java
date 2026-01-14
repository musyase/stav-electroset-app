package com.yourapp.stavelectroset.feature.news.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.yourapp.stavelectroset.R;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class NewsDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE     = "title";
    public static final String EXTRA_URL       = "url";
    public static final String EXTRA_HTML      = "html";
    public static final String EXTRA_IMAGE     = "imageUrl";
    public static final String EXTRA_DATE_UTC  = "dateUtc";

    private String url;
    private String title;

    private WebView web;
    private ProgressBar progress;
    private TextView tvSource;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_news_detail);

        title   = getIntent().getStringExtra(EXTRA_TITLE);
        url     = getIntent().getStringExtra(EXTRA_URL);
        String html  = getIntent().getStringExtra(EXTRA_HTML);
        String image = getIntent().getStringExtra(EXTRA_IMAGE);
        long whenUtc = getIntent().getLongExtra(EXTRA_DATE_UTC, 0L);

        // toolbar
        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar()!=null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(title == null ? "" : title);
        }
        tb.setNavigationOnClickListener(v -> onBackPressed());

        // views
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        tvSource = findViewById(R.id.tvSource);
        if (tvSource != null && url != null) {
            tvSource.setText("Источник: stavels.ru");
            tvSource.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        }

        // webview settings
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= 21) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        // прячем спиннер, когда страница дорисована
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (progress != null) progress.setVisibility(ProgressBar.GONE);
            }
        });

        // чтобы нижняя карточка не перекрывала текст
        web.setPadding(0, 0, 0, (int) (96 * getResources().getDisplayMetrics().density));
        web.setClipToPadding(false);

        final String dateStr = whenUtc > 0
                ? new SimpleDateFormat("dd MMMM yyyy", new Locale("ru")).format(new Date(whenUtc))
                : "";

        // если HTML уже есть — сразу показываем, иначе парсим со страницы
        if (html != null && !html.isEmpty()) {
            String tpl = buildTpl(title, dateStr, image, html);
            web.loadDataWithBaseURL(url, tpl, "text/html", "utf-8", null);
        } else {
            if (progress != null) progress.setVisibility(ProgressBar.VISIBLE);
            new Thread(() -> {
                String inner = "";
                String hero = image;
                try {
                    Document doc = Jsoup.connect(url).get();

                    // основная статья
                    Element article = null;
                    String[] sels = {
                            "article .entry-content","article",".single-content",
                            ".post-content",".entry-content",".content",".post"
                    };
                    for (String s : sels) { article = doc.selectFirst(s); if (article!=null) break; }
                    if (article == null) article = doc.body();

                    // абсолютные ссылки и картинки
                    for (Element img : article.select("img")) {
                        // подтянем lazy-атрибуты
                        if (img.hasAttr("data-src")) img.attr("src", img.absUrl("data-src"));
                        if (img.hasAttr("data-lazy-src")) img.attr("src", img.absUrl("data-lazy-src"));
                        img.attr("src", img.absUrl("src"));
                        img.attr("style","max-width:100%;height:auto;border-radius:12px");
                    }
                    article.select("script,noscript,style").remove();

                    // героизображение при отсутствии
                    if (hero == null || hero.isEmpty()) {
                        Element og = doc.selectFirst("meta[property=og:image]");
                        if (og != null) hero = og.attr("content");
                    }
                    inner = article.html();
                } catch (Throwable ignore) {}

                final String htmlTpl = buildTpl(title, dateStr, hero, inner);
                runOnUiThread(() -> web.loadDataWithBaseURL(url, htmlTpl, "text/html", "utf-8", null));
            }).start();
        }
    }

    private static String buildTpl(String title, String date, String heroUrl, String inner) {
        String t = title == null ? "" : title;
        String d = date  == null ? "" : date;
        String h = heroUrl == null ? "" : heroUrl;
        String body = inner == null ? "" : inner;

        // !!! Источник тут больше не выводим — он в отдельной карточке снизу
        return "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'/>"
                + "<style>"
                + ":root{--fg:#111;--muted:#666;--bg:#FAFAFA;--card:#FFFFFF;--border:#E7E7E7}"
                + "@media (prefers-color-scheme: dark){:root{--fg:#EEE;--muted:#AAA;--bg:#111;--card:#1A1A1A;--border:#2A2A2A}}"
                + "body{margin:0;background:var(--bg);color:var(--fg);font-family:sans-serif;line-height:1.65}"
                + ".wrap{max-width:860px;margin:0 auto;padding:12px 12px 24px 12px}"
                + ".card{background:var(--card);border:1px solid var(--border);border-radius:16px;overflow:hidden;box-shadow:0 2px 6px rgba(0,0,0,.04)}"
                + ".hero{width:100%;height:auto;display:block;object-fit:cover}"
                + ".pad{padding:16px}"
                + "h1{font-size:22px;margin:0 0 10px 0}"
                + ".meta{color:var(--muted);font-size:12px;margin-bottom:12px}"
                + "p{margin:0 0 14px 0} ul,ol{margin:0 0 14px 20px} li{margin-bottom:6px}"
                + "blockquote{margin:12px 0;padding:8px 12px;border-left:4px solid #1e88e5;background:rgba(30,136,229,.08)}"
                + "table{border-collapse:collapse;width:100%;margin:12px 0} th,td{border:1px solid var(--border);padding:6px;text-align:left}"
                + "img,video,iframe{max-width:100%;height:auto;border-radius:12px}"
                + "</style></head><body>"
                + "<div class='wrap'><div class='card'>"
                + (h.isEmpty()? "" : "<img class='hero' src='"+h+"' alt=''/>")
                + "<div class='pad'><h1>"+t+"</h1>"
                + (d.isEmpty()? "" : "<div class='meta'>"+d+"</div>")
                + body
                + "</div></div></div>"
                + "</body></html>";
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_news_detail, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open && url != null) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        } else if (id == R.id.action_share) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, (title==null?"":title) + (url==null?"":"\n"+url));
            startActivity(Intent.createChooser(i, "Поделиться"));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
