package com.secureauth.simulator.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.secureauth.simulator.databinding.ActivitySplashBinding;

/** Launcher screen: app name and a "Get started" button. */
public class SplashActivity extends BaseActivity {

    private ActivitySplashBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.splashRoot);

        binding.buttonGetStarted.setOnClickListener(v -> {
            // Explicit Intent: we name the exact Activity to open
            startActivity(new Intent(this, LoginActivity.class));
            finish(); // the splash should not be reachable with the Back button
        });
    }
}
