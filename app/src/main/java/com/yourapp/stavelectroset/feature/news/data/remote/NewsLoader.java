package com.yourapp.stavelectroset.feature.news.data.remote;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Query;

public final class NewsLoader {

    private static final String TAG = "NewsLoader";

    private static final int DEFAULT_PER_PAGE = 20;
    private static final int EMBED_ENABLED = 1;

    private static final int MAX_EXCERPT_CHARS = 280;

    // Jsoup timeouts (мс)
    private static final int HTTP_TIMEOUT_MS = 25_000;

    private static final String USER_AGENT = "StavElectrosetApp/1.0 (Android)";

    private static final Pattern RU_MONTH_DATE_PATTERN = Pattern.compile(
            "(Янв|Фев|Мар|Апр|Май|Июн|Июл|Авг|Сен|Окт|Ноя|Дек)\\s+\\d{1,2}\\s+\\d{4}",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern NUMERIC_DATE_PATTERN = Pattern.compile(
            "\\b\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}\\b"
    );

    private static final Map<String, Integer> RU_MONTHS = createRuMonths();

    private final Retrofit retrofit;
    private final WpApi api;

    // Берём origin из retrofit.baseUrl() — меньше шансов рассинхрона
    private final String origin;
    private final String newsListUrl;

    public NewsLoader(@NonNull Retrofit retrofit) {
        this.retrofit = retrofit;
        this.api = retrofit.create(WpApi.class);

        HttpUrl base = retrofit.baseUrl();
        String baseUrl = base != null ? base.toString() : "https://stavels.ru/";
        this.origin = baseUrl;
        this.newsListUrl = resolveUrl(baseUrl, "news/");
    }

    /* -------- WordPress REST: с _embed, чтобы получить featured image -------- */
    interface WpApi {
        @GET("wp-json/wp/v2/posts")
        Call<List<PostDto>> posts(
                @Query("per_page") int perPage,
                @Query("_embed") int embed // 1 = включить _embedded
        );
    }

    public static class PostDto {
        public int id;
        public String link;
        public String date;
        public Wrapped title;
        public Wrapped excerpt;
        public Wrapped content;

        @SerializedName("_embedded")
        public Embedded embedded;

        public static class Wrapped {
            public String rendered;
        }

        public static class Embedded {
            @SerializedName("wp:featuredmedia")
            public List<Media> featured;
        }

        public static class Media {
            @SerializedName("source_url")
            public String sourceUrl;

            @SerializedName("media_details")
            public MediaDetails mediaDetails;
        }

        public static class MediaDetails {
            public Sizes sizes;
        }

        public static class Sizes {
            public Size full;

            @SerializedName("large")
            public Size large;

            @SerializedName("medium_large")
            public Size mediumLarge;

            public Size medium;
        }

        public static class Size {
            @SerializedName("source_url")
            public String sourceUrl;
        }
    }

    /**
     * 1) Пытаемся WP REST (быстро, меньше запросов)
     * 2) Если не получилось — fallback на HTML парсинг
     */
    @NonNull
    public List<NewsEntity> load() throws Exception {
        List<NewsEntity> fromWp = loadFromWp();
        if (fromWp != null && !fromWp.isEmpty()) {
            return fromWp;
        }
        return scrapeNewsList();
    }

    @Nullable
    private List<NewsEntity> loadFromWp() {
        try {
            Response<List<PostDto>> r = api.posts(DEFAULT_PER_PAGE, EMBED_ENABLED).execute();
            if (!r.isSuccessful()) {
                Log.w(TAG, "WP REST failed: code=" + r.code());
                return null;
            }
            List<PostDto> body = r.body();
            if (body == null || body.isEmpty()) {
                Log.w(TAG, "WP REST returned empty body");
                return null;
            }
            return mapFromWp(body);
        } catch (IOException e) {
            Log.w(TAG, "WP REST IO error, fallback to HTML", e);
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "WP REST runtime error, fallback to HTML", e);
            return null;
        }
    }

    @NonNull
    private List<NewsEntity> mapFromWp(@NonNull List<PostDto> posts) {
        List<NewsEntity> out = new ArrayList<>(posts.size());

        for (PostDto p : posts) {
            if (p == null) continue;
            if (p.link == null || p.link.trim().isEmpty()) continue;

            final String url = p.link.trim();

            NewsEntity e = new NewsEntity();
            e.url = url;
            e.id = stableIdFromUrl(url);

            e.title = stripHtml(p.title != null ? p.title.rendered : "");
            String excerptText = stripHtml(p.excerpt != null ? p.excerpt.rendered : "");
            e.excerpt = trimToMax(excerptText, MAX_EXCERPT_CHARS);
            e.dateUtc = parseIsoToMillis(p.date);

            // HTML контент
            String html = (p.content != null && p.content.rendered != null) ? p.content.rendered : "";
            Document doc = Jsoup.parse(html, url);
            sanitizeAndFixHtml(doc, url);
            e.contentHtml = doc.body() != null ? doc.body().html() : "";

            // Featured image
            String featured = extractFeaturedFromEmbedded(p);
            if (isBlank(featured)) {
                Element hero = pickHeroImage(doc);
                if (hero != null) featured = chooseBestSrc(hero, url);
            }
            e.imageUrl = isBlank(featured) ? null : featured;

            out.add(e);
        }

        return out;
    }

    @Nullable
    private static String extractFeaturedFromEmbedded(@NonNull PostDto p) {
        if (p.embedded == null || p.embedded.featured == null || p.embedded.featured.isEmpty()) return null;

        PostDto.Media m = p.embedded.featured.get(0);
        if (m == null) return null;

        // Приоритет: full -> large -> medium_large -> medium -> source_url
        if (m.mediaDetails != null && m.mediaDetails.sizes != null) {
            PostDto.Sizes s = m.mediaDetails.sizes;
            String full = s.full != null ? s.full.sourceUrl : null;
            if (!isBlank(full)) return full;

            String large = s.large != null ? s.large.sourceUrl : null;
            if (!isBlank(large)) return large;

            String ml = s.mediumLarge != null ? s.mediumLarge.sourceUrl : null;
            if (!isBlank(ml)) return ml;

            String med = s.medium != null ? s.medium.sourceUrl : null;
            if (!isBlank(med)) return med;
        }

        return isBlank(m.sourceUrl) ? null : m.sourceUrl;
    }

    /* ----------------- HTML SCRAPE (fallback) ----------------- */

    @NonNull
    private List<NewsEntity> scrapeNewsList() throws IOException {
        Document doc = jsoupGet(newsListUrl);

        Elements titles = doc.select("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href]");
        if (titles.isEmpty()) return Collections.emptyList();

        List<NewsEntity> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        int added = 0;
        for (Element a : titles) {
            if (a == null) continue;
            String url = a.absUrl("href");
            if (isBlank(url)) continue;

            // дедупликация
            if (!seenUrls.add(url)) continue;

            try {
                Document post = jsoupGet(url);
                Extracted x = extractArticle(post, url);

                NewsEntity e = new NewsEntity();
                e.id = stableIdFromUrl(url);
                e.url = url;
                e.title = a.text();
                e.dateUtc = parseRuDateToMillis(findNearestDate(a));
                e.excerpt = trimToMax(x.excerpt, MAX_EXCERPT_CHARS);
                e.imageUrl = x.imageUrl;
                e.contentHtml = x.contentHtml;

                out.add(e);
                added++;

                if (added >= DEFAULT_PER_PAGE) break; // чтобы не делать 100 запросов в fallback
            } catch (Exception ex) {
                // Не валим весь список из-за одного поста
                Log.w(TAG, "Failed to scrape post: " + url, ex);
            }
        }

        return out;
    }

    @NonNull
    private static Document jsoupGet(@NonNull String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(HTTP_TIMEOUT_MS)
                .followRedirects(true)
                .get();
    }

    private static class Extracted {
        String contentHtml = "";
        String excerpt = "";
        String imageUrl = null;
    }

    @NonNull
    private Extracted extractArticle(@NonNull Document post, @NonNull String baseUrl) {
        Extracted x = new Extracted();

        Element article = null;
        String[] sels = {
                "article .entry-content",
                "article",
                ".single-content",
                ".post-content",
                ".entry-content",
                ".content",
                ".post"
        };
        for (String s : sels) {
            article = post.selectFirst(s);
            if (article != null) break;
        }
        if (article == null) article = post.body();

        // Приоритет: og:image -> .post-thumbnail/wp-post-image -> preload/lazy -> первая
        Element ogTag = post.selectFirst("meta[property=og:image]");
        String og = (ogTag != null) ? ogTag.attr("content") : null;
        if (!isBlank(og)) x.imageUrl = toAbsoluteUrl(og, baseUrl);

        if (x.imageUrl == null) {
            Element hero = post.selectFirst(".post-thumbnail img, img.wp-post-image");
            if (hero == null) hero = pickHeroImage(article);
            if (hero != null) x.imageUrl = chooseBestSrc(hero, baseUrl);
        }

        sanitizeAndFixHtml(article, baseUrl);

        x.contentHtml = article != null ? article.html() : "";
        String text = article != null ? article.text() : "";
        x.excerpt = trimToMax(text, MAX_EXCERPT_CHARS);

        return x;
    }

    private static void sanitizeAndFixHtml(@NonNull Element scope, @NonNull String baseUrl) {
        // картинки
        for (Element img : scope.select("img[src], img[data-src], img[srcset]")) {
            String best = chooseBestSrc(img, baseUrl);
            if (best != null) img.attr("src", best);
            img.attr("style", "max-width:100%;height:auto;border-radius:8px");
        }

        // ссылки
        for (Element a : scope.select("a[href]")) {
            a.attr("href", a.absUrl("href"));
        }

        // опасные/мусорные
        scope.select("script,noscript,style").remove();
    }

    private static void sanitizeAndFixHtml(@NonNull Document doc, @NonNull String baseUrl) {
        // в документе удобнее править от body
        Element body = doc.body();
        if (body == null) return;
        sanitizeAndFixHtml(body, baseUrl);
    }

    @Nullable
    private static Element pickHeroImage(@Nullable Element scope) {
        if (scope == null) return null;
        Element byClass = scope.selectFirst("img.preload-me, img.lazy-load, img[class*=preload], img[class*=lazy]");
        if (byClass != null) return byClass;
        return scope.selectFirst("img");
    }

    @Nullable
    private static String chooseBestSrc(@NonNull Element img, @NonNull String baseUrl) {
        String srcset = img.attr("srcset");
        if (!isBlank(srcset)) {
            String best = pickLargestFromSrcset(srcset);
            if (!isBlank(best)) return toAbsoluteUrl(best, baseUrl);
        }

        String data = img.attr("data-src");
        if (!isBlank(data)) return toAbsoluteUrl(data, baseUrl);

        String src = img.attr("src");
        if (!isBlank(src)) return toAbsoluteUrl(src, baseUrl);

        return null;
    }

    @Nullable
    private static String pickLargestFromSrcset(@NonNull String srcset) {
        String[] parts = srcset.split(",");
        int bestW = -1;
        String bestUrl = null;

        for (String part : parts) {
            String[] p = part.trim().split("\\s+");
            if (p.length == 0) continue;

            String url = p[0].trim();
            int w = -1;

            if (p.length >= 2 && p[1].endsWith("w")) {
                try {
                    w = Integer.parseInt(p[1].substring(0, p[1].length() - 1));
                } catch (Exception ignored) {
                }
            }

            if (w > bestW) {
                bestW = w;
                bestUrl = url;
            }
        }

        return bestUrl;
    }

    /* ----------------- helpers ----------------- */

    @NonNull
    private static String stableIdFromUrl(@NonNull String url) {
        return Integer.toHexString(url.hashCode());
    }

    @NonNull
    private static String stripHtml(@Nullable String html) {
        if (html == null) return "";
        return Jsoup.parse(html).text();
    }

    @NonNull
    private static String trimToMax(@Nullable String text, int maxChars) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= maxChars) return t;
        return t.substring(0, maxChars) + "…";
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    @Nullable
    private static String toAbsoluteUrl(@Nullable String maybeRelative, @NonNull String baseUrl) {
        if (isBlank(maybeRelative)) return null;

        String u = maybeRelative.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;

        try {
            return URI.create(baseUrl).resolve(u).toString();
        } catch (Exception e) {
            // На крайний случай возвращаем как есть
            return u;
        }
    }

    @NonNull
    private static String resolveUrl(@NonNull String baseUrl, @NonNull String path) {
        try {
            return URI.create(baseUrl).resolve(path).toString();
        } catch (Exception e) {
            // Фоллбек — просто склеим
            if (baseUrl.endsWith("/") && path.startsWith("/")) return baseUrl + path.substring(1);
            if (!baseUrl.endsWith("/") && !path.startsWith("/")) return baseUrl + "/" + path;
            return baseUrl + path;
        }
    }

    @NonNull
    private static String findNearestDate(@NonNull Element a) {
        Element cur = a;
        for (int i = 0; i < 6 && cur != null; i++) {
            String t = cur.text();

            Matcher m1 = RU_MONTH_DATE_PATTERN.matcher(t);
            if (m1.find()) return m1.group();

            Matcher m2 = NUMERIC_DATE_PATTERN.matcher(t);
            if (m2.find()) return m2.group();

            cur = cur.parent();
        }
        return "";
    }

    private static long parseRuDateToMillis(@Nullable String s) {
        if (isBlank(s)) return System.currentTimeMillis();

        try {
            String text = s.trim();

            // 12.01.2026 / 12/01/26
            if (text.matches("\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}")) {
                String[] p = text.split("[./]");
                int d = Integer.parseInt(p[0]);
                int mo = Integer.parseInt(p[1]);
                int y = Integer.parseInt(p[2]);
                if (y < 100) y += 2000;

                java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                c.set(y, mo - 1, d, 0, 0, 0);
                c.set(java.util.Calendar.MILLISECOND, 0);
                return c.getTimeInMillis();
            }

            // "Янв 12 2026"
            String[] p = text.split("\\s+");
            if (p.length >= 3) {
                String monRaw = p[0];
                // Нормализуем регистр: "янв" -> "Янв"
                String mon = monRaw.substring(0, 1).toUpperCase(Locale.ROOT) + monRaw.substring(1).toLowerCase(Locale.ROOT);

                int mo = RU_MONTHS.getOrDefault(mon, 1);
                int d = Integer.parseInt(p[1]);
                int y = Integer.parseInt(p[2]);

                java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                c.set(y, mo - 1, d, 0, 0, 0);
                c.set(java.util.Calendar.MILLISECOND, 0);
                return c.getTimeInMillis();
            }

            return System.currentTimeMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static long parseIsoToMillis(@Nullable String iso) {
        if (isBlank(iso)) return System.currentTimeMillis();

        String v = iso.trim();
        try {
            // Пытаемся как OffsetDateTime (если есть Z / +03:00 и т.п.)
            try {
                OffsetDateTime odt = OffsetDateTime.parse(v, DateTimeFormatter.ISO_DATE_TIME);
                return odt.toInstant().toEpochMilli();
            } catch (DateTimeParseException ignore) {
                // Если оффсета нет (обычно WP "2026-01-16T12:34:56")
                LocalDateTime ldt = LocalDateTime.parse(v, DateTimeFormatter.ISO_DATE_TIME);
                return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
            }
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    @NonNull
    private static Map<String, Integer> createRuMonths() {
        Map<String, Integer> m = new HashMap<>();
        m.put("Янв", 1);
        m.put("Фев", 2);
        m.put("Мар", 3);
        m.put("Апр", 4);
        m.put("Май", 5);
        m.put("Июн", 6);
        m.put("Июл", 7);
        m.put("Авг", 8);
        m.put("Сен", 9);
        m.put("Окт", 10);
        m.put("Ноя", 11);
        m.put("Дек", 12);
        return Collections.unmodifiableMap(m);
    }
}
