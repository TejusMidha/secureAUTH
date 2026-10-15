package com.secureauth.simulator.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.secureauth.simulator.R;
import com.secureauth.simulator.data.DbHelper;
import com.secureauth.simulator.data.SessionManager;
import com.secureauth.simulator.data.TotpAccount;
import com.secureauth.simulator.data.TotpAccountDao;
import com.secureauth.simulator.data.User;
import com.secureauth.simulator.data.UserDao;
import com.secureauth.simulator.databinding.ActivityProfileBinding;
import com.secureauth.simulator.io.AvatarStore;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Who you are in the app: username, member-since date, the issuer of your authenticator and a
 * circular profile photo. The photo comes from the phone's camera app or from the gallery; either
 * way it is copied into the app's private storage, shrunk, and its path saved in users.avatar_uri.
 */
public class ProfileActivity extends BaseActivity {

    private static final String STATE_CAMERA_FILE = "camera_file";
    private static final String CAMERA_DIR = "camera";
    private static final String CAMERA_FILE = "profile_capture.jpg";

    /** What one load of the screen found. */
    private static final class Loaded {
        final User user;
        final String issuer;        // null if no authenticator is set up
        final Bitmap avatar;        // null if there is no photo

        Loaded(User user, String issuer, Bitmap avatar) {
            this.user = user;
            this.issuer = issuer;
            this.avatar = avatar;
        }
    }

    private ActivityProfileBinding binding;
    private UserDao userDao;
    private TotpAccountDao accountDao;
    private long userId;

    /** Where the camera app is writing the picture right now (kept across rotation). */
    private String cameraFilePath;

    // The phone's gallery picker. A null answer means the user closed it without choosing.
    private final ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), this::onPhotoPicked);

    // The camera app (implicit Intent ACTION_IMAGE_CAPTURE) writes into the Uri we give it and
    // answers true if a picture was taken
    private final ActivityResultLauncher<Uri> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), this::onPhotoTaken);

    // The system "allow the camera?" dialog; the answer is true if the user allowed it
    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), this::onCameraPermissionResult);

    @Override
    protected boolean requiresSession() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!enforceSession()) {
            return;
        }
        userId = SessionManager.getInstance().getUserId();
        if (savedInstanceState != null) {
            cameraFilePath = savedInstanceState.getString(STATE_CAMERA_FILE);
        }

        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applySystemBarPadding(binding.profileRoot);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        DbHelper db = DbHelper.getInstance(this);
        userDao = new UserDao(db);
        accountDao = new TotpAccountDao(db);

        binding.buttonChangePhoto.setOnClickListener(v -> showPhotoChoices());
        loadProfile();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CAMERA_FILE, cameraFilePath);
    }

    // ---- loading and showing ----------------------------------------------------------------

    private void loadProfile() {
        final Context appContext = getApplicationContext();
        runInBackground(() -> {
            User user = userDao.getById(userId);
            if (user == null) {
                return null;
            }
            List<TotpAccount> accounts = accountDao.listForUser(userId);
            String issuer = accounts.isEmpty() ? null : accounts.get(0).issuer;
            Bitmap avatar = AvatarStore.load(appContext, user.avatarUri, AvatarStore.MAX_SIDE_PX);
            return new Loaded(user, issuer, avatar);
        }, this::showProfile);
    }

    private void showProfile(@Nullable Loaded loaded) {
        if (loaded == null) {
            finish();
            return;
        }
        binding.textUsername.setText(loaded.user.username);
        binding.textMemberSince.setText(getString(R.string.profile_member_since,
                DateFormat.getDateInstance(DateFormat.LONG).format(new Date(loaded.user.createdAt))));
        binding.textIssuer.setText(loaded.issuer == null
                ? getString(R.string.profile_not_enrolled)
                : getString(R.string.profile_authenticator, loaded.issuer));

        if (loaded.avatar == null) {
            binding.imageAvatar.setImageResource(R.drawable.ic_avatar_placeholder);
            binding.imageAvatar.setContentDescription(getString(R.string.cd_avatar_none));
        } else {
            binding.imageAvatar.setImageBitmap(loaded.avatar);
            binding.imageAvatar.setContentDescription(getString(R.string.cd_avatar));
        }
    }

    // ---- choosing a photo -------------------------------------------------------------------

    /** "Take photo" (only if the phone has a camera) or "Choose from gallery". */
    private void showPhotoChoices() {
        boolean hasCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        String[] items = hasCamera
                ? new String[]{getString(R.string.photo_take), getString(R.string.photo_gallery)}
                : new String[]{getString(R.string.photo_gallery)};

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.change_photo_title)
                .setItems(items, (dialog, which) -> {
                    boolean camera = hasCamera && which == 0;
                    if (camera) {
                        startCamera();
                    } else {
                        startGallery();
                    }
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void startGallery() {
        try {
            galleryLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } catch (ActivityNotFoundException e) {
            showToast(R.string.photo_failed); // no picker or file manager at all
        }
    }

    /**
     * Android refuses to start the camera app for an app that has the CAMERA permission in its
     * manifest but was not given it, so it is asked for here. A short explanation comes BEFORE the
     * system dialog, the same way as for location; a "no" just points to the gallery.
     */
    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camera_rationale_title)
                .setMessage(R.string.camera_rationale_message)
                .setPositiveButton(R.string.button_allow,
                        (d, w) -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA))
                .setNegativeButton(R.string.button_not_now, null)
                .show();
    }

    /** The user answered the system permission dialog. */
    void onCameraPermissionResult(boolean granted) {
        if (granted) {
            launchCamera();
        } else {
            showToast(R.string.photo_camera_denied);
        }
    }

    /**
     * Hands the camera app a place to write the picture. The place is a file in our cache folder,
     * made reachable for that one app through FileProvider.
     */
    private void launchCamera() {
        File dir = new File(getCacheDir(), CAMERA_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            showToast(R.string.photo_failed);
            return;
        }
        File file = new File(dir, CAMERA_FILE);
        file.delete(); // never show a leftover from an earlier try
        cameraFilePath = file.getAbsolutePath();

        try {
            Uri target = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            cameraLauncher.launch(target);
        } catch (ActivityNotFoundException | SecurityException e) {
            cameraFilePath = null;
            showToast(R.string.photo_no_camera);
        }
    }

    // ---- the answers ------------------------------------------------------------------------

    /** Gallery answer: the chosen picture, or null if the picker was closed. */
    void onPhotoPicked(@Nullable Uri picked) {
        if (picked == null) {
            return; // cancelled: nothing changes
        }
        savePhoto(picked, null);
    }

    /** Camera answer: true if the camera app wrote a picture to the file we gave it. */
    void onPhotoTaken(boolean taken) {
        String path = cameraFilePath;
        cameraFilePath = null;
        if (path == null) {
            return;
        }
        File file = new File(path);
        if (!taken || !file.isFile()) {
            file.delete(); // cancelled: remove the empty or partial file
            return;
        }
        savePhoto(Uri.fromFile(file), file);
    }

    /**
     * Copies the picture into private storage and records it for this user, off the UI thread.
     * {@code temporary} (may be null) is the camera's file, deleted once it has been copied.
     */
    private void savePhoto(Uri source, @Nullable File temporary) {
        final Context appContext = getApplicationContext();
        binding.buttonChangePhoto.setEnabled(false);
        runInBackground(() -> {
            try {
                String saved = AvatarStore.saveFrom(appContext, userId, source);
                if (saved == null) {
                    return false;
                }
                User user = userDao.getById(userId);
                String previous = user == null ? null : user.avatarUri;
                userDao.setAvatarUri(userId, saved);
                AvatarStore.delete(appContext, previous); // the old photo is no longer needed
                return true;
            } finally {
                if (temporary != null) {
                    temporary.delete();
                }
            }
        }, ok -> {
            binding.buttonChangePhoto.setEnabled(true);
            showToast(ok ? R.string.photo_updated : R.string.photo_failed);
            if (ok) {
                loadProfile();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
