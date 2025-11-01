package com.yourapp.stavelectroset.feature.news.data.remote;

import com.google.gson.annotations.SerializedName;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Query;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewsLoader {
    private final Retrofit retrofit;
    private static final String ORIGIN = "https://stavels.ru/";
    private static final String NEWS_PATH = "news/";

    public NewsLoader(Retrofit retrofit) { this.retrofit = retrofit; }

    /* -------- WordPress REST: с _embed, чтобы получить featured image -------- */
    interface WpApi {
        @GET("wp-json/wp/v2/posts")
        Call<List<PostDto>> posts(@Query("per_page") int perPage,
                                  @Query("_embed") int embed); // 1 = включить _embedded
    }

    public static class PostDto {
        public int id;
        public String link;
        public String date;
        public Wrapped title;
        public Wrapped excerpt;
        public Wrapped content;
        @SerializedName("_embedded") public Embedded embedded;

        public static class Wrapped { public String rendered; }

        public static class Embedded {
            @SerializedName("wp:featuredmedia") public List<Media> featured;
        }
        public static class Media {
            @SerializedName("source_url") public String sourceUrl;
            @SerializedName("media_details") public MediaDetails mediaDetails;
        }
        public static class MediaDetails {
            public Sizes sizes;
        }
        public static class Sizes {
            public Size full; @SerializedName("large") public Size large;
            @SerializedName("medium_large") public Size mediumLarge; public Size medium;
        }
        public static class Size { @SerializedName("source_url") public String sourceUrl; }
    }

    public List<NewsEntity> load() throws Exception {
        // 1) REST с контентом + featured image
        try {
            WpApi api = retrofit.create(WpApi.class);
            Response<List<PostDto>> r = api.posts(20, 1).execute(); // _embed=1
            if (r.isSuccessful() && r.body()!=null && !r.body().isEmpty()) {
                return mapFromWp(r.body());
            }
        } catch (Throwable ignored) {}
        // 2) Fallback — парсим HTML
        return scrapeNewsList();
    }

    private List<NewsEntity> mapFromWp(List<PostDto> posts) {
        List<NewsEntity> out = new ArrayList<>();
        for (PostDto p : posts) {
            NewsEntity e = new NewsEntity();

            e.url  = p.link;
            e.id   = Integer.toHexString(e.url.hashCode()); // единый id по ссылке

            e.title = Jsoup.parse(p.title != null && p.title.rendered != null ? p.title.rendered : "").text();
            String t = Jsoup.parse(p.excerpt != null && p.excerpt.rendered != null ? p.excerpt.rendered : "").text();
            e.excerpt = (t.length() > 280) ? t.substring(0, 280) + "…" : t;
            e.dateUtc = parseIso8601ToMillis(p.date);

            // HTML контент из REST
            String html = (p.content != null) ? p.content.rendered : "";
            Document doc = Jsoup.parse(html, e.url);
            for (Element img : doc.select("img[src]")) {
                img.attr("src", img.absUrl("src"));
                img.attr("style","max-width:100%;height:auto;border-radius:8px");
            }
            doc.select("script,noscript,style").remove();
            e.contentHtml = doc.body().html();

            // FEATURED image: PostDto.Media, а не WpApi.Media !!!
            String featured = null;
            if (p.embedded != null && p.embedded.featured != null && !p.embedded.featured.isEmpty()) {
                PostDto.Media m = p.embedded.featured.get(0);
                if (m.mediaDetails != null && m.mediaDetails.sizes != null) {
                    if (m.mediaDetails.sizes.full != null && m.mediaDetails.sizes.full.sourceUrl != null) {
                        featured = m.mediaDetails.sizes.full.sourceUrl;
                    } else if (m.mediaDetails.sizes.large != null && m.mediaDetails.sizes.large.sourceUrl != null) {
                        featured = m.mediaDetails.sizes.large.sourceUrl;
                    } else if (m.mediaDetails.sizes.mediumLarge != null && m.mediaDetails.sizes.mediumLarge.sourceUrl != null) {
                        featured = m.mediaDetails.sizes.mediumLarge.sourceUrl;
                    } else if (m.mediaDetails.sizes.medium != null && m.mediaDetails.sizes.medium.sourceUrl != null) {
                        featured = m.mediaDetails.sizes.medium.sourceUrl;
                    }
                }
                if (featured == null || featured.isEmpty()) {
                    featured = m.sourceUrl;
                }
            }
            // если через _embed не пришло — пытаемся достать из контента
            if (featured == null || featured.isEmpty()) {
                Element hero = pickHeroImage(doc);
                if (hero != null) featured = chooseBestSrc(hero, e.url);
            }
            e.imageUrl = featured;

            out.add(e);
        }
        return out;
    }

    /* ----------------- HTML SCRAPE (fallback) ----------------- */
    private List<NewsEntity> scrapeNewsList() throws IOException {
        List<NewsEntity> out = new ArrayList<>();
        Document doc = Jsoup.connect(ORIGIN + NEWS_PATH).get();

        Elements titles = doc.select("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href]");
        for (Element a : titles) {
            String url = a.absUrl("href");
            if (url == null || url.isEmpty()) continue;

            Document post = Jsoup.connect(url).get();
            Extracted x = extractArticle(post, url);

            NewsEntity e = new NewsEntity();
            e.id = Integer.toHexString(url.hashCode());
            e.url = url;
            e.title = a.text();
            e.dateUtc = parseRuDateToMillis(findNearestDate(a));
            e.excerpt = x.excerpt;
            e.imageUrl = x.imageUrl;
            e.contentHtml = x.contentHtml;
            out.add(e);
        }
        return out;
    }

    private static class Extracted { String contentHtml=""; String excerpt=""; String imageUrl=null; }

    private Extracted extractArticle(Document post, String baseUrl) {
        Extracted x = new Extracted();

        Element article = null;
        String[] sels = {
                "article .entry-content", "article",
                ".single-content", ".post-content", ".entry-content", ".content", ".post"
        };
        for (String s : sels) { article = post.selectFirst(s); if (article!=null) break; }
        if (article == null) article = post.body();

        // приоритет: og:image → .post-thumbnail/wp-post-image → preload/lazy → первая
        String og = post.selectFirst("meta[property=og:image]") != null
                ? post.selectFirst("meta[property=og:image]").attr("content") : null;
        if (og != null && !og.isEmpty()) x.imageUrl = abs(og, baseUrl);
        if (x.imageUrl == null) {
            Element hero = post.selectFirst(".post-thumbnail img, img.wp-post-image");
            if (hero == null) hero = pickHeroImage(article);
            if (hero != null) x.imageUrl = chooseBestSrc(hero, baseUrl);
        }

        for (Element img : article.select("img[src], img[data-src], img[srcset]")) {
            String best = chooseBestSrc(img, baseUrl);
            if (best != null) img.attr("src", best);
            img.attr("style","max-width:100%;height:auto;border-radius:8px");
        }
        for (Element a : article.select("a[href]")) a.attr("href", a.absUrl("href"));
        article.select("script,noscript,style").remove();

        x.contentHtml = article.html();
        String text = article.text();
        x.excerpt = text.length()>280 ? text.substring(0,280)+"…" : text;
        return x;
    }

    private static Element pickHeroImage(Element scope) {
        Element byClass = scope.selectFirst("img.preload-me, img.lazy-load, img[class*=preload], img[class*=lazy]");
        if (byClass != null) return byClass;
        return scope.selectFirst("img");
    }

    private static String chooseBestSrc(Element img, String baseUrl) {
        String srcset = img.attr("srcset");
        if (srcset != null && !srcset.isEmpty()) {
            String best = pickLargestFromSrcset(srcset);
            if (best != null) return abs(best, baseUrl);
        }
        String data = img.attr("data-src");
        if (data != null && !data.isEmpty()) return abs(data, baseUrl);
        String src = img.attr("src");
        if (src != null && !src.isEmpty()) return abs(src, baseUrl);
        return null;
    }

    private static String pickLargestFromSrcset(String srcset) {
        String[] parts = srcset.split(",");
        int bestW = -1; String bestUrl = null;
        for (String part : parts) {
            String[] p = part.trim().split("\\s+");
            String url = p[0].trim();
            int w = -1;
            if (p.length >= 2 && p[1].endsWith("w")) {
                try { w = Integer.parseInt(p[1].substring(0, p[1].length()-1)); } catch (Exception ignored) {}
            }
            if (w > bestW) { bestW = w; bestUrl = url; }
        }
        return bestUrl;
    }

    /* ----------------- helpers ----------------- */
    private static String abs(String u, String baseUrl) {
        if (u == null || u.isEmpty()) return null;
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) {
            if (baseUrl != null && baseUrl.startsWith("http")) {
                int i = baseUrl.indexOf("://");
                int s = baseUrl.indexOf('/', i+3);
                String origin = (s > 0) ? baseUrl.substring(0, s+1) : baseUrl + "/";
                return origin + u.substring(1);
            }
            return ORIGIN + u.substring(1);
        }
        return u;
    }

    private static String findNearestDate(Element a) {
        Pattern p1 = Pattern.compile("(Янв|Фев|Мар|Апр|Май|Июн|Июл|Авг|Сен|Окт|Ноя|Дек)\\s+\\d{1,2}\\s+\\d{4}");
        Pattern p2 = Pattern.compile("\\b\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}\\b");
        Element cur = a;
        for (int i=0;i<6 && cur!=null;i++) {
            String t = cur.text();
            Matcher m = p1.matcher(t); if (m.find()) return m.group();
            m = p2.matcher(t); if (m.find()) return m.group();
            cur = cur.parent();
        }
        return "";
    }

    private static long parseRuDateToMillis(String s) {
        if (s==null || s.isEmpty()) return System.currentTimeMillis();
        Map<String,Integer> m = new HashMap<>();
        m.put("Янв",1); m.put("Фев",2); m.put("Мар",3); m.put("Апр",4); m.put("Май",5); m.put("Июн",6);
        m.put("Июл",7); m.put("Авг",8); m.put("Сен",9); m.put("Окт",10); m.put("Ноя",11); m.put("Дек",12);
        try {
            if (s.matches("\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}")) {
                String[] p = s.split("[./]");
                int d = Integer.parseInt(p[0]), mo = Integer.parseInt(p[1]); int y = Integer.parseInt(p[2]); if (y<100) y+=2000;
                Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                c.set(y, mo-1, d, 0,0,0); c.set(Calendar.MILLISECOND,0); return c.getTimeInMillis();
            } else {
                String[] p = s.split("\\s+");
                int mo = m.getOrDefault(p[0],1), d = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                c.set(y, mo-1, d, 0,0,0); c.set(Calendar.MILLISECOND,0); return c.getTimeInMillis();
            }
        } catch (Exception e) { return System.currentTimeMillis(); }
    }

    private static long parseIso8601ToMillis(String iso) {
        if (iso==null || iso.isEmpty()) return System.currentTimeMillis();
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso);
            return (d!=null) ? d.getTime() : System.currentTimeMillis();
        } catch (ParseException e) { return System.currentTimeMillis(); }
    }
}
