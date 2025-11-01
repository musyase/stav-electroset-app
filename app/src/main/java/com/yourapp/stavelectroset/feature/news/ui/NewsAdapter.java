package com.yourapp.stavelectroset.feature.news.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.feature.news.data.local.NewsEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NewsAdapter extends RecyclerView.Adapter<NewsAdapter.Holder> {

    public interface OnClick {
        void onItemClick(NewsEntity e);
    }

    private final List<NewsEntity> data = new ArrayList<>();
    private final OnClick onClick;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy", new Locale("ru"));

    public NewsAdapter(OnClick onClick) {
        this.onClick = onClick;
    }

    public void submit(List<NewsEntity> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_news, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Context ctx = h.itemView.getContext();
        NewsEntity e = data.get(position);

        h.title.setText(e.title != null ? e.title : "");
        h.excerpt.setText(e.excerpt != null ? e.excerpt : "");

        String dateText = (e.dateUtc > 0) ? fmt.format(new Date(e.dateUtc)) : "";
        h.date.setText(dateText);

        if (e.imageUrl != null && !e.imageUrl.isEmpty()) {
            h.image.setVisibility(View.VISIBLE);
            Glide.with(h.image)
                    .load(e.imageUrl)
                    .transform(new CenterCrop(), new RoundedCorners(dp(ctx, 12))) // было 0 — меняем на 12
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_placeholder)
                    .into(h.image);
        } else {
            h.image.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> onClick.onItemClick(e));

        // меню «…»
        h.overflow.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(v.getContext(), v);
            pm.getMenu().add(0, 1, 0, "Открыть в браузере");
            pm.getMenu().add(0, 2, 1, "Поделиться");
            pm.setOnMenuItemClickListener((MenuItem item) -> {
                if (item.getItemId() == 1) {
                    v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.url)));
                    return true;
                } else if (item.getItemId() == 2) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, e.title);
                    i.putExtra(Intent.EXTRA_TEXT, e.title + "\n" + e.url);
                    v.getContext().startActivity(Intent.createChooser(i, "Поделиться"));
                    return true;
                }
                return false;
            });
            pm.show();
        });
    }

    @Override public int getItemCount() { return data.size(); }

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static class Holder extends RecyclerView.ViewHolder {
        ImageView image;
        View scrim;
        TextView title, date, excerpt;
        ImageButton overflow;
        public Holder(@NonNull View v) {
            super(v);
            image = v.findViewById(R.id.image);
            scrim = v.findViewById(R.id.scrim);
            title = v.findViewById(R.id.title);
            date = v.findViewById(R.id.date);
            excerpt = v.findViewById(R.id.excerpt);
            overflow = v.findViewById(R.id.overflow);
        }
    }
}
