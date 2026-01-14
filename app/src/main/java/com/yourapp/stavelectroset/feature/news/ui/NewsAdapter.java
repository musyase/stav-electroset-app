package com.yourapp.stavelectroset.feature.news.ui;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.VH> {

    private final List<NewsEntity> items = new ArrayList<>();
    private final SimpleDateFormat df = new SimpleDateFormat("dd MMM yyyy", new Locale("ru"));

    public void submitList(List<NewsEntity> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_news, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        NewsEntity n = items.get(position);

        h.title.setText(n.title == null ? "" : n.title);
        h.excerpt.setText(n.excerpt == null ? "" : n.excerpt);
        h.date.setText(n.dateUtc > 0 ? df.format(n.dateUtc) : "");

        // --- ищем превью (imageUrl -> из HTML -> из og:image)
        String imgUrl = n.imageUrl;
        if ((imgUrl == null || imgUrl.isEmpty()) && n.contentHtml != null && !n.contentHtml.isEmpty()) {
            try {
                Document d = Jsoup.parse(n.contentHtml);
                Element e = d.selectFirst("img[src], img[data-src], img[data-lazy-src], img[srcset]");
                if (e != null) {
                    imgUrl = firstNonEmpty(
                            e.absUrl("src"),
                            e.absUrl("data-src"),
                            e.absUrl("data-lazy-src"),
                            pickFromSrcset(e.attr("srcset"))
                    );
                }
                if (imgUrl == null || imgUrl.isEmpty()) {
                    Element og = d.selectFirst("meta[property=og:image]");
                    if (og != null) imgUrl = og.attr("content");
                }
            } catch (Throwable ignore) {}
        }
        final String thumb = imgUrl; // <<<< "эффективно final" для лямбд

        // --- загрузка картинки
        if (thumb == null || thumb.isEmpty()) {
            h.image.setImageResource(R.drawable.news_image_scrim);
        } else {
            Glide.with(h.image)
                    .load(thumb)
                    .placeholder(R.drawable.news_image_scrim)
                    .error(R.drawable.news_image_scrim)
                    .centerCrop()
                    .into(h.image);
        }

        // --- переход в деталку
        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(v.getContext(), NewsDetailActivity.class);
            i.putExtra(NewsDetailActivity.EXTRA_TITLE,    n.title);
            i.putExtra(NewsDetailActivity.EXTRA_URL,      n.url);
            i.putExtra(NewsDetailActivity.EXTRA_HTML,     n.contentHtml);
            i.putExtra(NewsDetailActivity.EXTRA_IMAGE,    thumb);
            i.putExtra(NewsDetailActivity.EXTRA_DATE_UTC, n.dateUtc);
            v.getContext().startActivity(i);
        });
    }

    @Override public int getItemCount() { return items.size(); }

    private static String firstNonEmpty(String... a) {
        for (String s : a) if (s != null && !s.isEmpty()) return s;
        return null;
    }

    private static String pickFromSrcset(String srcset) {
        if (srcset == null || srcset.isEmpty()) return null;
        String[] parts = srcset.split(",");
        if (parts.length == 0) return null;
        String first = parts[0].trim();
        int sp = first.indexOf(' ');
        return sp > 0 ? first.substring(0, sp) : first;
    }

    // делаем внутренним и приватным — гасим ворнинг про «exposed visibility scope»
    public static class VH extends RecyclerView.ViewHolder {
        final TextView title, excerpt, date;
        final ImageView image;
        VH(@NonNull View itemView) {
            super(itemView);
            title   = itemView.findViewById(R.id.tvTitle);
            excerpt = itemView.findViewById(R.id.tvExcerpt);
            date    = itemView.findViewById(R.id.tvDate);
            image   = itemView.findViewById(R.id.ivImage);
        }
    }
}
