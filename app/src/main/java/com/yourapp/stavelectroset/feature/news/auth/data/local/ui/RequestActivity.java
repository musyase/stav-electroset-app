package com.yourapp.stavelectroset.feature.news.auth.data.local.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.yourapp.stavelectroset.R;

public class RequestActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setTitle("Заявка");
            tb.setNavigationOnClickListener(v -> finish());
        }
    }
}
