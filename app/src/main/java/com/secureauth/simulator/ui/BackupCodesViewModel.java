package com.secureauth.simulator.ui;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Holds a freshly generated set of backup codes until the user taps "Done".
 *
 * Why a ViewModel: the plain codes exist only in memory (the database keeps just hashes), so if
 * the screen were rebuilt by a rotation while the codes were showing, they would be lost for
 * good. A ViewModel survives rotation. It also runs the slow generation itself, so a rotation in
 * the middle of it cannot throw the result away.
 */
public class BackupCodesViewModel extends ViewModel {

    /** What a successful generation produced. */
    static final class Generation {
        final List<String> codes;
        final String username;
        final long createdAt;

        Generation(List<String> codes, String username, long createdAt) {
            this.codes = codes;
            this.username = username;
            this.createdAt = createdAt;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Runnable onChange;

    /** True while codes are being created (several seconds: every code is hashed with PBKDF2). */
    boolean generating;
    /** The codes waiting to be shown; null when there is nothing to show. */
    @Nullable Generation pending;
    /** Set when the last attempt failed; the screen shows a message and clears it. */
    boolean failed;

    /** The screen registers here to hear about changes (null to unregister). Runs on the UI thread. */
    void setOnChange(@Nullable Runnable listener) {
        this.onChange = listener;
        if (listener != null) {
            listener.run();
        }
    }

    /** Runs {@code work} off the UI thread. The work must not capture the Activity. */
    void generate(Supplier<Generation> work) {
        if (generating) {
            return;
        }
        generating = true;
        failed = false;
        notifyChanged();
        executor.execute(() -> {
            Generation result = work.get();
            main.post(() -> {
                generating = false;
                pending = result;
                failed = result == null;
                notifyChanged();
            });
        });
    }

    /** The user is done with the codes: forget them. */
    void clearPending() {
        pending = null;
    }

    private void notifyChanged() {
        if (onChange != null) {
            onChange.run();
        }
    }

    @Override
    protected void onCleared() {
        pending = null;
        onChange = null;
        executor.shutdown();
    }
}
