package com.saulls.airpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.FetchMediaTask;
import dji.sdk.media.FetchMediaTaskContent;
import dji.sdk.media.FetchMediaTaskScheduler;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Browse the drone's SD card and copy photos and videos to the phone's gallery.
 * The camera is switched to download mode while this screen is open and put back
 * into photo mode when you leave.
 */
public class MediaActivity extends Activity {

    private static final String SAVED_KEY = "saved_media";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private GridView grid;
    private TextView status, message, progressText;
    private View progressRow;
    private ProgressBar progress;
    private Button downloadButton, selectNewButton;

    private Camera camera;
    private MediaManager mediaManager;
    private FetchMediaTaskScheduler scheduler;
    private boolean usedPlayback;

    private final List<MediaFile> files = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private Set<String> saved;
    private final MediaAdapter adapter = new MediaAdapter();

    // Download queue state
    private List<MediaFile> queue;
    private int queueIndex;
    private int savedCount;
    private final List<String> failures = new ArrayList<>();
    private boolean downloading;
    private boolean cancelRequested;
    private MediaFile current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        grid = findViewById(R.id.grid);
        status = findViewById(R.id.status);
        message = findViewById(R.id.message);
        progressRow = findViewById(R.id.progress_row);
        progress = findViewById(R.id.progress);
        progressText = findViewById(R.id.progress_text);
        downloadButton = findViewById(R.id.btn_download);
        selectNewButton = findViewById(R.id.btn_select_new);

        saved = new HashSet<>(Prefs.raw(this).getStringSet(SAVED_KEY, Collections.emptySet()));

        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (downloading) return;
            String key = key(files.get(position));
            if (!selected.remove(key)) selected.add(key);
            adapter.notifyDataSetChanged();
            updateButtons();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        selectNewButton.setOnClickListener(v -> toggleSelectNew());
        downloadButton.setOnClickListener(v -> startDownload());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> cancelDownload());

        updateButtons();
        connect();
    }

    // ---------------------------------------------------------------- setup

    private void connect() {
        BaseProduct product = DJISDKManager.getInstance().getProduct();
        camera = product != null && product.isConnected() ? product.getCamera() : null;
        if (camera == null) {
            showMessage("Connect the drone to see its photos and videos.\n\n"
                    + "Plug the phone into the controller and turn on the controller and drone.");
            return;
        }
        if (Telemetry.get(this).last().flying) {
            showMessage("Land first. Downloading pauses the live video, so it only works on the ground.");
            camera = null;
            return;
        }
        if (!camera.isMediaDownloadModeSupported()) {
            showMessage("This drone doesn't allow downloads through the app.\n"
                    + "Take the SD card out of the drone and copy the files instead.");
            camera = null;
            return;
        }
        mediaManager = camera.getMediaManager();
        if (mediaManager == null) {
            showMessage("The drone's media manager isn't available. Try again in a few seconds.");
            return;
        }

        setStatus("Opening the drone's SD card…");
        CommonCallbacks.CompletionCallback<DJIError> afterMode = error -> {
            if (error != null) {
                ui(() -> showMessage("Couldn't switch the camera to download mode:\n" + error.getDescription()));
                return;
            }
            refreshList();
        };
        if (camera.isFlatCameraModeSupported()) {
            usedPlayback = true;
            camera.enterPlayback(afterMode);
        } else {
            camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, afterMode);
        }
    }

    private void refreshList() {
        mediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, error -> {
            if (error != null) {
                ui(() -> showMessage("Couldn't read the SD card:\n" + error.getDescription()
                        + "\n\nCheck there's an SD card in the drone."));
                return;
            }
            List<MediaFile> list = mediaManager.getSDCardFileListSnapshot();
            List<MediaFile> usable = new ArrayList<>();
            if (list != null) {
                for (MediaFile f : list) {
                    if (f != null && f.getMediaType() != MediaFile.MediaType.JSON) usable.add(f);
                }
            }
            // Newest first
            Collections.sort(usable, (a, b) -> Long.compare(b.getTimeCreated(), a.getTimeCreated()));
            ui(() -> {
                files.clear();
                files.addAll(usable);
                adapter.notifyDataSetChanged();
                if (files.isEmpty()) {
                    showMessage("No photos or videos on the drone's SD card.");
                } else {
                    message.setVisibility(View.GONE);
                    grid.setVisibility(View.VISIBLE);
                }
                updateButtons();
                loadThumbnails();
            });
        });
    }

    private void loadThumbnails() {
        scheduler = mediaManager.getScheduler();
        if (scheduler == null) return;
        scheduler.resume(error -> {
            if (error != null) return;
            for (MediaFile f : new ArrayList<>(files)) {
                if (f.getThumbnail() != null) continue;
                scheduler.moveTaskToEnd(new FetchMediaTask(f, FetchMediaTaskContent.THUMBNAIL,
                        (file, content, err) -> {
                            if (err == null) ui(adapter::notifyDataSetChanged);
                        }));
            }
        });
    }

    // ---------------------------------------------------------------- selection

    private void toggleSelectNew() {
        Set<String> fresh = new HashSet<>();
        for (MediaFile f : files) {
            if (!saved.contains(key(f))) fresh.add(key(f));
        }
        if (!fresh.isEmpty() && selected.equals(fresh)) {
            selected.clear();
        } else {
            selected.clear();
            selected.addAll(fresh.isEmpty() ? allKeys() : fresh);
        }
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private Set<String> allKeys() {
        Set<String> all = new HashSet<>();
        for (MediaFile f : files) all.add(key(f));
        return all;
    }

    private void updateButtons() {
        boolean ready = !files.isEmpty() && !downloading;
        selectNewButton.setEnabled(ready);
        selectNewButton.setAlpha(ready ? 1f : 0.4f);
        int n = selected.size();
        downloadButton.setEnabled(ready && n > 0);
        downloadButton.setAlpha(ready && n > 0 ? 1f : 0.4f);
        downloadButton.setText(n > 0 ? "Download " + n : "Download");

        if (!downloading && !files.isEmpty()) {
            int photos = 0, videos = 0, fresh = 0;
            for (MediaFile f : files) {
                if (isVideo(f)) videos++;
                else photos++;
                if (!saved.contains(key(f))) fresh++;
            }
            setStatus(photos + " photos · " + videos + " videos · " + fresh + " not saved yet");
            boolean allFreshSelected = fresh > 0 && selected.size() == fresh;
            selectNewButton.setText(fresh == 0 ? "Select all" : allFreshSelected ? "Clear" : "Select new");
        }
    }

    // ---------------------------------------------------------------- download

    private void startDownload() {
        if (selected.isEmpty() || mediaManager == null) return;
        queue = new ArrayList<>();
        // Download oldest first so the gallery keeps flight order.
        for (int i = files.size() - 1; i >= 0; i--) {
            if (selected.contains(key(files.get(i)))) queue.add(files.get(i));
        }
        queueIndex = 0;
        savedCount = 0;
        failures.clear();
        cancelRequested = false;
        downloading = true;
        progressRow.setVisibility(View.VISIBLE);
        updateButtons();
        if (scheduler != null) scheduler.suspend(null); // free the link for the download
        downloadNext();
    }

    private void downloadNext() {
        if (cancelRequested || queueIndex >= queue.size()) {
            finishDownload();
            return;
        }
        current = queue.get(queueIndex);
        File tempDir = new File(getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir(), "download");
        if (!tempDir.exists()) tempDir.mkdirs();
        String baseName = baseName(current.getFileName());
        clearTemp(tempDir, baseName);
        updateProgress(0);

        current.fetchFileData(tempDir, baseName, new DownloadListener<String>() {
            @Override
            public void onStart() {
            }

            @Override
            public void onRateUpdate(long total, long downloaded, long rate) {
            }

            @Override
            public void onRealtimeDataUpdate(byte[] data, long position, boolean isLastPack) {
            }

            @Override
            public void onProgress(long total, long downloaded) {
                if (total > 0) ui(() -> updateProgress((int) (1000 * downloaded / total)));
            }

            @Override
            public void onSuccess(String path) {
                MediaFile file = current;
                io.execute(() -> {
                    File downloaded = findDownloaded(tempDir, baseName, path);
                    try {
                        if (downloaded == null) throw new java.io.IOException("downloaded file not found");
                        GallerySaver.save(MediaActivity.this, downloaded);
                        ui(() -> markSaved(file));
                    } catch (Exception e) {
                        synchronized (failures) {
                            failures.add(file.getFileName() + ": " + e.getMessage());
                        }
                    } finally {
                        if (downloaded != null) downloaded.delete();
                        ui(() -> {
                            queueIndex++;
                            downloadNext();
                        });
                    }
                });
            }

            @Override
            public void onFailure(DJIError error) {
                MediaFile file = current;
                ui(() -> {
                    if (!cancelRequested) {
                        failures.add(file.getFileName() + ": "
                                + (error == null ? "download failed" : error.getDescription()));
                    }
                    queueIndex++;
                    downloadNext();
                });
            }
        });
    }

    private void updateProgress(int perMille) {
        int total = queue.size();
        int overall = (int) ((queueIndex * 1000L + perMille) / Math.max(1, total));
        progress.setProgress(overall);
        progressText.setText(String.format(Locale.US, "%d of %d  ·  %d%%",
                Math.min(queueIndex + 1, total), total, perMille / 10));
        setStatus("Downloading " + (current != null ? current.getFileName() : "") + "…");
    }

    private void cancelDownload() {
        if (!downloading) return;
        cancelRequested = true;
        if (current != null) current.stopFetchingFileData(null);
        setStatus("Cancelling…");
    }

    private void finishDownload() {
        downloading = false;
        current = null;
        progressRow.setVisibility(View.GONE);
        selected.clear();
        adapter.notifyDataSetChanged();
        updateButtons();
        if (scheduler != null) scheduler.resume(null);

        String summary = savedCount + (savedCount == 1 ? " file" : " files")
                + " saved to your gallery in the \"" + GallerySaver.ALBUM + "\" album.";
        if (cancelRequested) summary = "Cancelled. " + summary;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Download finished");
        if (failures.isEmpty()) {
            b.setMessage(summary);
        } else {
            b.setMessage(summary + "\n\nNot saved:\n" + android.text.TextUtils.join("\n", failures));
        }
        b.setPositiveButton("OK", null);
        if (savedCount > 0) {
            b.setNeutralButton("Open gallery", (d, w) -> openGallery());
        }
        b.show();
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void markSaved(MediaFile f) {
        savedCount++;
        saved.add(key(f));
        SharedPreferences.Editor e = Prefs.raw(this).edit();
        e.putStringSet(SAVED_KEY, new HashSet<>(saved));
        e.apply();
        adapter.notifyDataSetChanged();
    }

    private static File findDownloaded(File dir, String baseName, String reportedPath) {
        if (reportedPath != null) {
            File f = new File(reportedPath);
            if (f.isFile()) return f;
        }
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile() && f.getName().startsWith(baseName)) return f;
        }
        return null;
    }

    private static void clearTemp(File dir, String baseName) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.getName().startsWith(baseName)) f.delete();
        }
    }

    // ---------------------------------------------------------------- teardown

    @Override
    public void onBackPressed() {
        if (downloading) {
            new AlertDialog.Builder(this)
                    .setTitle("Stop downloading?")
                    .setMessage("Files already saved stay in your gallery.")
                    .setPositiveButton("Stop", (d, w) -> {
                        cancelDownload();
                        finish();
                    })
                    .setNegativeButton("Keep going", null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.stopFetchingFileData(null);
        if (scheduler != null) scheduler.removeAllTasks();
        if (mediaManager != null) mediaManager.exitMediaDownloading();
        if (camera != null) {
            // Put the camera back so the fly screen works.
            if (usedPlayback) camera.exitPlayback(null);
            else camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, null);
        }
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- helpers

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        grid.setVisibility(View.GONE);
        setStatus("");
        updateButtons();
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void ui(Runnable r) {
        main.post(() -> {
            if (!isDestroyed()) r.run();
        });
    }

    private static boolean isVideo(MediaFile f) {
        MediaFile.MediaType t = f.getMediaType();
        return t == MediaFile.MediaType.MP4 || t == MediaFile.MediaType.MOV;
    }

    private static String key(MediaFile f) {
        return f.getFileName() + "@" + f.getTimeCreated();
    }

    private static String baseName(String fileName) {
        if (fileName == null) return "DJI_" + System.currentTimeMillis();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private final class MediaAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public MediaFile getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(MediaActivity.this).inflate(R.layout.item_media, parent, false);
            MediaFile f = getItem(position);
            ImageView thumb = v.findViewById(R.id.thumb);
            thumb.setImageBitmap(f.getThumbnail());

            TextView badge = v.findViewById(R.id.badge);
            if (isVideo(f)) {
                badge.setText("▶ " + Units.duration((long) f.getDurationInSeconds()));
            } else if (f.getMediaType() == MediaFile.MediaType.RAW_DNG) {
                badge.setText("RAW");
            } else {
                badge.setText("");
            }
            badge.setVisibility(badge.getText().length() > 0 ? View.VISIBLE : View.GONE);

            boolean isSelected = selected.contains(key(f));
            v.findViewById(R.id.selected_overlay).setVisibility(isSelected ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.check).setVisibility(isSelected ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.saved).setVisibility(saved.contains(key(f)) ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
