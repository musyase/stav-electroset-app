package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.yourapp.stavelectroset.R;

import java.util.Locale;

public class AdminDashboardActivity extends AppCompatActivity {

    private static final String PREFS_PROFILE = "profile";
    private static final String KEY_ROLE_CACHE = "role_cache";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_dashboard);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setNavigationIcon(R.drawable.ic_arrow_back_24);
            tb.setNavigationOnClickListener(v -> finish());
        }

        TextView tvRole = findViewById(R.id.tvRole);

        MaterialButton btnManage = findViewById(R.id.btnManageRoles);
        MaterialButton btnRequests = findViewById(R.id.btnRequestsAdmin);
        MaterialButton btnComplaints = findViewById(R.id.btnComplaintsAdmin);

        // ✅ Новая кнопка (нужно добавить в XML): @id/btnInfoLettersAdmin
        MaterialButton btnInfoLetters = findViewById(R.id.btnInfoLettersAdmin);

        String role = readRole();
        if (tvRole != null) tvRole.setText("Роль: " + role);

        boolean isOwner = "owner".equals(role);
        boolean isStaff = isOwner || "deputy".equals(role) || "admin".equals(role);

        // Управление ролями — только Owner
        if (btnManage != null) {
            btnManage.setEnabled(isOwner);
            btnManage.setOnClickListener(v ->
                    startActivity(new Intent(this, RoleManagementActivity.class))
            );
        }

        // Заявки — Owner/Deputy/Admin
        if (btnRequests != null) {
            btnRequests.setEnabled(isStaff);
            btnRequests.setOnClickListener(v ->
                    startActivity(new Intent(this, AdminRequestsActivity.class))
            );
        }

        // Жалобы — Owner/Deputy/Admin
        if (btnComplaints != null) {
            btnComplaints.setEnabled(isStaff);
            btnComplaints.setOnClickListener(v ->
                    startActivity(new Intent(this, AdminComplaintsActivity.class))
            );
        }

        // ✅ Информационные письма — Owner/Deputy/Admin
        // (Если кнопки нет в layout — просто ничего не будет, поэтому следующий шаг: правим XML)
        if (btnInfoLetters != null) {
            btnInfoLetters.setEnabled(isStaff);
            btnInfoLetters.setOnClickListener(v ->
                    startActivity(new Intent(this, AdminInfoLettersActivity.class))
            );
        }
    }

    private String readRole() {
        SharedPreferences p = getSharedPreferences(PREFS_PROFILE, MODE_PRIVATE);
        String r = p.getString(KEY_ROLE_CACHE, "user");
        if (r == null) return "user";
        r = r.trim().toLowerCase(Locale.ROOT);
        return r.isEmpty() ? "user" : r;
    }
}