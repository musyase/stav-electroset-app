package com.yourapp.stavelectroset.feature.auth.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import androidx.appcompat.app.AppCompatActivity;

import com.yourapp.stavelectroset.R;
import com.yourapp.stavelectroset.di.ServiceLocator;
import com.yourapp.stavelectroset.feature.auth.data.local.UserDao;
import com.yourapp.stavelectroset.feature.auth.domain.AuthRepository;
import com.yourapp.stavelectroset.feature.auth.domain.Captcha;
import com.yourapp.stavelectroset.feature.auth.domain.SessionManager;

public class RegisterActivity extends AppCompatActivity {

    private AuthRepository repo;
    private Captcha currentCaptcha;

    private TextInputEditText etLogin, etEmail, etFullName, etPass, etPass2, etCaptcha;
    private TextView tvCaptchaQ;
    private ProgressBar strengthBar;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        setTitle("Регистрация");

        etLogin = findViewById(R.id.etLogin);
        etEmail = findViewById(R.id.etEmail);
        etFullName = findViewById(R.id.etFullName);
        etPass = findViewById(R.id.etPass);
        etPass2 = findViewById(R.id.etPass2);
        etCaptcha = findViewById(R.id.etCaptcha);
        tvCaptchaQ = findViewById(R.id.tvCaptchaQ);
        strengthBar = findViewById(R.id.strengthBar);
        MaterialButton btnCaptcha = findViewById(R.id.btnCaptcha);
        MaterialButton btnRegister = findViewById(R.id.btnRegister);

        // DI
        UserDao dao = ServiceLocator.get(this).db.user();
        repo = new AuthRepository(dao);

        regenCaptcha();
        btnCaptcha.setOnClickListener(v -> regenCaptcha());

        // оценка силы пароля (очень простая визуализация)
        etPass.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { updateStrength(s); }
            public void afterTextChanged(Editable s) { updateStrength(s); }
        });

        btnRegister.setOnClickListener(v -> onRegister());
    }

    private void updateStrength(CharSequence s){
        String p = s==null? "": s.toString();
        int score = 0;
        if (p.length() >= 8) score += 30;
        if (p.matches(".*[A-Z].*")) score += 20;
        if (p.matches(".*[a-z].*")) score += 20;
        if (p.matches(".*\\d.*"))   score += 15;
        if (p.matches(".*[^A-Za-z0-9].*")) score += 15;
        if (score > 100) score = 100;
        strengthBar.setProgress(score);
    }

    private void regenCaptcha(){
        currentCaptcha = Captcha.next();
        tvCaptchaQ.setText("Капча: " + currentCaptcha.question);
        etCaptcha.setText("");
    }

    private void onRegister() {
        String login = val(etLogin);
        String email = val(etEmail);
        String full  = val(etFullName);
        String p1    = val(etPass);
        String p2    = val(etPass2);
        String cap   = val(etCaptcha);

        if (cap.isEmpty()) { toast("Введите ответ капчи"); return; }
        try {
            int ans = Integer.parseInt(cap);
            if (ans != currentCaptcha.answer) { toast("Неверная капча"); regenCaptcha(); return; }
        } catch (NumberFormatException e) { toast("Неверная капча"); regenCaptcha(); return; }

        // регистрация в фоновом потоке
        new Thread(() -> {
            AuthRepository.Result r = repo.register(login, email, full, p1, p2);
            runOnUiThread(() -> {
                if (r.ok) {
                    SessionManager.login(this, r.userId);
                    toast("Готово! Аккаунт создан");
                    finish(); // вернуться назад или открыть ProfileActivity
                } else {
                    toast(r.error);
                }
            });
        }).start();
    }

    private String val(TextInputEditText e){ return e.getText()==null? "": e.getText().toString().trim(); }
    private void toast(String m){ Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}
