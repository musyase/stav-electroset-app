package com.yourapp.stavelectroset.feature.news.admin.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;

import java.util.List;

public class UserAdminAdapter extends RecyclerView.Adapter<UserAdminAdapter.VH> {

    public interface Listener {
        void onEdit(UserEntity u);
        void onDelete(UserEntity u);
    }

    private final List<UserEntity> data;
    private final Listener listener;

    public UserAdminAdapter(List<UserEntity> data, Listener listener) {
        this.data = data;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_admin_user, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        UserEntity u = data.get(position);

        h.tvTitle.setText(u.login + " (id=" + u.id + ")");
        h.tvEmail.setText("Почта: " + nz(u.email));
        h.tvFullName.setText("ФИО: " + nz(u.fullName));
        h.tvPhone.setText("Телефон: " + nz(u.phone));
        h.tvAccount.setText("Лицевой счёт: " + nz(u.accountNumber));

        h.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(u);
        });
        h.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(u);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvEmail, tvFullName, tvPhone, tvAccount;
        MaterialButton btnEdit, btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvEmail = itemView.findViewById(R.id.tvEmail);
            tvFullName = itemView.findViewById(R.id.tvFullName);
            tvPhone = itemView.findViewById(R.id.tvPhone);
            tvAccount = itemView.findViewById(R.id.tvAccount);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
