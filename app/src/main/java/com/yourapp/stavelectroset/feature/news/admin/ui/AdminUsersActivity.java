package com.yourapp.stavelectroset.feature.news.admin.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.news.auth.data.local.UserEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdminUsersActivity extends AppCompatActivity implements UserAdminAdapter.Listener {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private UserDao userDao;
    private RecyclerView rv;
    private UserAdminAdapter adapter;
    private final List<UserEntity> users = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_users);

        MaterialToolbar toolbar = findViewById(R.id.toolbarAdminUsers);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        userDao = ServiceLocator.get(this).db.user();

        rv = findViewById(R.id.rvUsers);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UserAdminAdapter(users, this);
        rv.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fabAddUser);
        fab.setOnClickListener(v -> showEditDialog(null));

        loadUsers();
    }

    private void loadUsers() {
        io.execute(() -> {
            List<UserEntity> list = userDao.getAllSync();
            runOnUiThread(() -> {
                users.clear();
                users.addAll(list);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void showEditDialog(@Nullable UserEntity existing) {
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_user, null);

        TextInputEditText etLogin  = view.findViewById(R.id.etLogin);
        TextInputEditText etEmail  = view.findViewById(R.id.etEmail);
        TextInputEditText etFull   = view.findViewById(R.id.etFullName);
        TextInputEditText etAcc    = view.findViewById(R.id.etAccount);
        TextInputEditText etPhone  = view.findViewById(R.id.etPhone);
        TextInputEditText etMeters = view.findViewById(R.id.etMeters);
        TextInputEditText etSub    = view.findViewById(R.id.etSubscriberType);

        if (existing != null) {
            etLogin.setText(existing.login);
            etEmail.setText(existing.email);
            etFull.setText(existing.fullName);
            etAcc.setText(existing.accountNumber);
            etPhone.setText(existing.phone);
            etMeters.setText(existing.meterSerials);
            etSub.setText(existing.subscriberType);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "Новый пользователь" : "Редактировать пользователя")
                .setView(view)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String login  = textOf(etLogin);
                    String email  = textOf(etEmail);
                    String full   = textOf(etFull);
                    String acc    = textOf(etAcc);
                    String phone  = textOf(etPhone);
                    String meters = textOf(etMeters);
                    String sub    = textOf(etSub);

                    if (login.isEmpty() || email.isEmpty()) {
                        Toast.makeText(this, "Логин и почта обязательны", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    io.execute(() -> {
                        if (existing == null) {
                            UserEntity u = new UserEntity();
                            u.login = login;
                            u.email = email;
                            u.fullName = full;
                            u.accountNumber = acc;
                            u.phone = phone;
                            u.meterSerials = meters;
                            u.subscriberType = sub;
                            // createdAt, lastLogin можно оставить по умолчанию или выставить здесь
                            userDao.insert(u);
                        } else {
                            existing.login = login;
                            existing.email = email;
                            existing.fullName = full;
                            existing.accountNumber = acc;
                            existing.phone = phone;
                            existing.meterSerials = meters;
                            existing.subscriberType = sub;
                            userDao.update(existing);
                        }
                        runOnUiThread(this::loadUsers);
                    });
                })
                .show();
    }

    private String textOf(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    @Override
    public void onEdit(UserEntity u) {
        showEditDialog(u);
    }

    @Override
    public void onDelete(UserEntity u) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Удалить пользователя?")
                .setMessage("Точно удалить " + nz(u.login) + " (id=" + u.id + ")?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    io.execute(() -> {
                        userDao.delete(u);
                        runOnUiThread(this::loadUsers);
                    });
                })
                .show();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
