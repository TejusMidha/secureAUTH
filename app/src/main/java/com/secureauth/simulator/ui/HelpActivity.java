package com.secureauth.simulator.ui;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

import com.secureauth.simulator.R;
import com.secureauth.simulator.databinding.ActivityHelpBinding;

/**
 * Help, shown in a WebView with two buttons: "How TOTP works" (a page that ships inside the app,
 * so it works offline) and "RFC 6238" (the standard on the internet). Links stay inside the
 * WebView, Back goes to the previous page before it closes the screen, a progress bar shows
 * loading, and only the two allowed sites can be opened.
 *
 * JavaScript stays off: neither page needs it, and a WebView that cannot run scripts or read
 * files is much harder to abuse.
 */
public class HelpActivity extends BaseActivity {

    private ActivityHelpBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHelpBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.helpRoot);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        WebSettings settings = binding.webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);       // does not affect file:///android_asset/ pages
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(true);    // the RFC page is long and small on a phone
        settings.setDisplayZoomControls(false);

        binding.webView.setWebViewClient(new PageClient());
        binding.webView.setWebChromeClient(new ProgressClient());

        binding.buttonHelpTotp.setOnClickListener(v -> show(HelpPages.LOCAL_URL));
        binding.buttonHelpRfc.setOnClickListener(v -> show(HelpPages.RFC_URL));

        // Back walks back through the pages shown so far, and only then leaves the screen
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState); // keeps the page and the history on rotation
        } else {
            show(HelpPages.LOCAL_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        binding.webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        // A WebView holds on to its Activity until it is destroyed
        binding.webView.destroy();
        super.onDestroy();
    }

    private void show(String url) {
        binding.textError.setVisibility(View.GONE);
        binding.webView.loadUrl(url);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Decides which links may open, and shows a message if a page cannot be loaded. */
    private class PageClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (HelpPages.isAllowed(request.getUrl().toString())) {
                return false; // let this WebView load it: the link stays inside the app
            }
            showToast(R.string.help_blocked);
            return true;      // anything else is not opened at all
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            binding.textError.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                binding.textError.setVisibility(View.VISIBLE); // for example: offline
            }
        }
    }

    /** Moves the progress bar while a page loads and hides it at 100%. */
    private class ProgressClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            binding.progressPage.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
            binding.progressPage.setProgress(newProgress);
        }
    }
}
