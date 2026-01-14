package com.yourapp.stavelectroset.feature.news.admin.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateRequestEntity;
import com.yourapp.stavelectroset.feature.news.auth.data.local.CertificateWithUser;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CertificateAdminAdapter extends RecyclerView.Adapter<CertificateAdminAdapter.VH> {

    public interface Listener {
        void onIssueClick(CertificateWithUser item);
    }

    private final List<CertificateWithUser> data;
    private final Listener listener;
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());

    public CertificateAdminAdapter(List<CertificateWithUser> data, Listener listener) {
        this.data = data;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_admin_certificate, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        CertificateWithUser item = data.get(position);
        CertificateRequestEntity r = item.request;

        // Если ФИО есть — показываем его, иначе fallback по id
        String name = (item.userFullName != null && !item.userFullName.isEmpty())
                ? item.userFullName
                : "id=" + r.userId;

        h.tvUserTitle.setText("Пользователь: " + name);

        String reqDate = sdf.format(new Date(r.requestedAt));
        h.tvRequestedAt.setText("Запрошено: " + reqDate);

        if (r.issued) {
            String date = (r.issuedDate == null || r.issuedDate.isEmpty())
                    ? ""
                    : " (" + r.issuedDate + ")";
            h.tvStatus.setText("Статус: выдано" + date);
            h.tvStatus.setTextColor(Color.parseColor("#4CAF50")); // зелёный
        } else {
            h.tvStatus.setText("Статус: не выдано");
            h.tvStatus.setTextColor(Color.parseColor("#F44336")); // красный
        }

        h.btnIssue.setOnClickListener(v -> {
            if (listener != null) listener.onIssueClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvUserTitle, tvRequestedAt, tvStatus;
        MaterialButton btnIssue;

        VH(@NonNull View itemView) {
            super(itemView);
            tvUserTitle  = itemView.findViewById(R.id.tvUserId);
            tvRequestedAt = itemView.findViewById(R.id.tvRequestedAt);
            tvStatus      = itemView.findViewById(R.id.tvStatus);
            btnIssue      = itemView.findViewById(R.id.btnIssue);
        }
    }
}
