package com.saulls.airpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
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
 * Browse the drone's SD card grouped by day. Tap to view, or Select to pick files
 * (or whole days) and copy them to the phone's gallery, then send them to Drive.
 * The camera is switched to download mode while this screen is open and put back
 * into photo mode when you leave.
 */
public class MediaActivity extends Activity {

    private static final String SAVED_KEY = "saved_media";
    /** Started right after landing: download files from this flight, then return. */
    public static final String EXTRA_AUTO_SINCE = "auto_since";
    private long autoSince = -1;
    private static final int TYPE_HEADER = 0, TYPE_ITEM = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private RecyclerView grid;
    private TextView status, message, progressText;
    private View progressRow;
    private ProgressBar progress;
    private Button downloadButton, selectNewButton, selectButton;

    // viewer
    private View viewer;
    private ImageView viewerImage;
    private TextView viewerInfo, viewerPlay;
    private Button viewerDownload, viewerOpen;
    private int viewerIndex = -1;

    private Camera camera;
    private MediaManager mediaManager;
    private FetchMediaTaskScheduler scheduler;
    private boolean usedPlayback;

    private final List<MediaFile> files = new ArrayList<>();
    /** Rows shown in the grid: a String (day header) or a MediaFile. */
    private final List<Object> rows = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private Set<String> saved;
    private boolean selectMode;
    private final MediaAdapter adapter = new MediaAdapter();

    // Download queue state
    private List<MediaFile> queue;
    private int queueIndex;
    private final ArrayList<Uri> savedUris = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private boolean downloading;
    private boolean cancelRequested;
    private boolean openWhenDone;
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
        selectButton = findViewById(R.id.btn_select);

        viewer = findViewById(R.id.viewer);
        viewerImage = findViewById(R.id.viewer_image);
        viewerInfo = findViewById(R.id.viewer_info);
        viewerPlay = findViewById(R.id.viewer_play);
        viewerDownload = findViewById(R.id.viewer_download);
        viewerOpen = findViewById(R.id.viewer_open);

        saved = new HashSet<>(Prefs.raw(this).getStringSet(SAVED_KEY, Collections.emptySet()));
        autoSince = getIntent().getLongExtra(EXTRA_AUTO_SINCE, -1);

        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int span = Math.max(3, (widthDp - 32) / 170);
        GridLayoutManager glm = new GridLayoutManager(this, span);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return rows.get(position) instanceof String ? span : 1;
            }
        });
        grid.setLayoutManager(glm);
        grid.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        selectButton.setOnClickListener(v -> setSelectMode(!selectMode));
        selectNewButton.setOnClickListener(v -> toggleSelectNew());
        downloadButton.setOnClickListener(v -> startDownload(selectedFiles(), false));
        findViewById(R.id.btn_cancel).setOnClickListener(v -> cancelDownload());

        findViewById(R.id.viewer_close).setOnClickListener(v -> closeViewer());
        findViewById(R.id.viewer_prev).setOnClickListener(v -> showViewer(viewerIndex - 1));
        findViewById(R.id.viewer_next).setOnClickListener(v -> showViewer(viewerIndex + 1));
        viewerDownload.setOnClickListener(v -> downloadFromViewer(false));
        viewerOpen.setOnClickListener(v -> downloadFromViewer(true));
        viewerPlay.setOnClickListener(v -> downloadFromViewer(true));

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
        modeAttempt = 0;
        // Right after landing the camera can still be finishing a video; make sure it's stopped.
        camera.stopRecordVideo(null);
        main.postDelayed(this::enterDownloadMode, 800);
    }

    private int modeAttempt;
    private static final int MODE_ATTEMPTS = 6;

    /** Switch the camera to download mode, retrying while it's busy, then trying the other method. */
    private void enterDownloadMode() {
        if (isDestroyed() || camera == null) return;
        modeAttempt++;
        // Alternate methods on later attempts: some DJI cameras only accept one of them.
        boolean playback = camera.isFlatCameraModeSupported() ? modeAttempt <= 3 : modeAttempt > 3;
        usedPlayback = playback;
        CommonCallbacks.CompletionCallback<DJIError> afterMode = error -> ui(() -> {
            if (error == null) {
                refreshList();
            } else if (modeAttempt < MODE_ATTEMPTS) {
                setStatus("Camera busy, retrying… (" + modeAttempt + "/" + MODE_ATTEMPTS + ")");
                main.postDelayed(this::enterDownloadMode, 2000);
            } else {
                showMessage("Couldn't switch the camera to download mode:\n" + error.getDescription()
                        + "\n\nWait for the propellers to stop, then tap here to try again.");
                message.setOnClickListener(v -> {
                    message.setOnClickListener(null);
                    message.setVisibility(View.GONE);
                    connect();
                });
            }
        });
        if (playback) camera.enterPlayback(afterMode);
        else camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, afterMode);
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
            Collections.sort(usable, (a, b) -> Long.compare(b.getTimeCreated(), a.getTimeCreated()));
            ui(() -> {
                files.clear();
                files.addAll(usable);
                rebuildRows();
                if (files.isEmpty()) {
                    showMessage("No photos or videos on the drone's SD card.");
                } else {
                    message.setVisibility(View.GONE);
                    grid.setVisibility(View.VISIBLE);
                }
                updateButtons();
                if (autoSince > 0) {
                    autoDownload();
                } else {
                    loadThumbnails();
                }
            });
        });
    }

    /** Groups files under day headers, newest day first. */
    private void rebuildRows() {
        rows.clear();
        String lastDay = null;
        for (MediaFile f : files) {
            String day = dayLabel(f.getTimeCreated());
            if (!day.equals(lastDay)) {
                rows.add(day);
                lastDay = day;
            }
            rows.add(f);
        }
        adapter.notifyDataSetChanged();
    }

    private static String dayLabel(long millis) {
        Calendar c = Calendar.getInstance();
        Calendar today = Calendar.getInstance();
        c.setTimeInMillis(millis);
        if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "Today";
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "Yesterday";
        return DateFormat.getDateInstance(DateFormat.FULL).format(new Date(millis));
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
                            if (err == null) ui(() -> notifyFile(file));
                        }));
            }
        });
    }

    private void notifyFile(MediaFile f) {
        int i = rows.indexOf(f);
        if (i >= 0) adapter.notifyItemChanged(i);
        if (viewerIndex >= 0 && viewerIndex < files.size() && files.get(viewerIndex) == f) bindViewerImage(f);
    }

    /** Picks this flight's new files (with a fallback if the drone's clock is off) and downloads them. */
    private void autoDownload() {
        List<MediaFile> pick = new ArrayList<>();
        for (MediaFile f : files) {
            if (!saved.contains(key(f)) && f.getTimeCreated() >= autoSince - 60_000) pick.add(f);
        }
        if (pick.isEmpty()) {
            long newestSaved = 0;
            for (MediaFile f : files) if (saved.contains(key(f))) newestSaved = Math.max(newestSaved, f.getTimeCreated());
            for (MediaFile f : files) {
                if (!saved.contains(key(f)) && f.getTimeCreated() > newestSaved
                        && f.getTimeCreated() > System.currentTimeMillis() - 24 * 3600_000L) pick.add(f);
            }
        }
        if (pick.isEmpty()) {
            android.widget.Toast.makeText(this, "No new photos or videos from this flight.", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setStatus("Downloading " + pick.size() + " new " + (pick.size() == 1 ? "file" : "files") + " from this flight…");
        startDownload(pick, false);
    }

    // ---------------------------------------------------------------- selection

    private void setSelectMode(boolean on) {
        selectMode = on;
        if (!on) selected.clear();
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private void toggleFile(MediaFile f) {
        String k = key(f);
        if (!selected.remove(k)) selected.add(k);
        int i = rows.indexOf(f);
        if (i >= 0) adapter.notifyItemChanged(i);
        updateButtons();
    }

    private void toggleDay(String day) {
        List<String> keys = new ArrayList<>();
        for (MediaFile f : files) if (dayLabel(f.getTimeCreated()).equals(day)) keys.add(key(f));
        boolean allSelected = selected.containsAll(keys);
        if (allSelected) selected.removeAll(keys);
        else selected.addAll(keys);
        selectMode = true;
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private void toggleSelectNew() {
        Set<String> fresh = new HashSet<>();
        for (MediaFile f : files) if (!saved.contains(key(f))) fresh.add(key(f));
        if (!fresh.isEmpty() && selected.equals(fresh)) {
            selected.clear();
        } else {
            selected.clear();
            if (fresh.isEmpty()) {
                for (MediaFile f : files) selected.add(key(f));
            } else {
                selected.addAll(fresh);
            }
        }
        selectMode = !selected.isEmpty();
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private List<MediaFile> selectedFiles() {
        List<MediaFile> out = new ArrayList<>();
        for (MediaFile f : files) if (selected.contains(key(f))) out.add(f);
        return out;
    }

    private void updateButtons() {
        boolean ready = !files.isEmpty() && !downloading;
        for (Button b : new Button[]{selectNewButton, selectButton}) {
            b.setEnabled(ready);
            b.setAlpha(ready ? 1f : 0.4f);
        }
        selectButton.setText(selectMode ? "Done" : "Select");
        int n = selected.size();
        downloadButton.setVisibility(n > 0 || !selectMode ? View.VISIBLE : View.GONE);
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
            setStatus(selectMode ? (n + " selected") : (photos + " photos · " + videos + " videos · " + fresh + " new"));
            boolean allFreshSelected = fresh > 0 && selected.size() == fresh;
            selectNewButton.setText(fresh == 0 ? "Select all" : allFreshSelected ? "Clear" : "Select new");
        }
    }

    // ---------------------------------------------------------------- viewer

    private void showViewer(int index) {
        if (index < 0 || index >= files.size()) return;
        viewerIndex = index;
        MediaFile f = files.get(index);
        viewer.setVisibility(View.VISIBLE);
        bindViewerImage(f);
        boolean video = isVideo(f);
        viewerPlay.setVisibility(video ? View.VISIBLE : View.GONE);
        viewerOpen.setText(video ? "Download & play" : "Download & open");
        viewerDownload.setText(saved.contains(key(f)) ? "Download again" : "Download");
        String info = (index + 1) + " of " + files.size() + "  ·  " + f.getFileName() + "  ·  "
                + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(f.getTimeCreated()))
                + "  ·  " + sizeText(f.getFileSize());
        if (video) info += "  ·  " + Units.duration((long) f.getDurationInSeconds());
        if (saved.contains(key(f))) info += "  ·  saved";
        viewerInfo.setText(info);
        findViewById(R.id.viewer_prev).setAlpha(index > 0 ? 1f : 0.25f);
        findViewById(R.id.viewer_next).setAlpha(index < files.size() - 1 ? 1f : 0.25f);

        // Photos: ask the drone for a sharper preview image (jump the thumbnail queue).
        if (!video && f.getPreview() == null && scheduler != null && !downloading) {
            scheduler.moveTaskToNext(new FetchMediaTask(f, FetchMediaTaskContent.PREVIEW,
                    (file, content, err) -> {
                        if (err == null) ui(() -> notifyFile(file));
                    }));
        }
    }

    private void bindViewerImage(MediaFile f) {
        Bitmap b = f.getPreview() != null ? f.getPreview() : f.getThumbnail();
        viewerImage.setImageBitmap(b);
    }

    private void closeViewer() {
        viewer.setVisibility(View.GONE);
        viewerIndex = -1;
    }

    private void downloadFromViewer(boolean open) {
        if (viewerIndex < 0 || downloading) return;
        MediaFile f = files.get(viewerIndex);
        closeViewer();
        List<MediaFile> one = new ArrayList<>();
        one.add(f);
        startDownload(one, open);
    }

    // ---------------------------------------------------------------- download

    private void startDownload(List<MediaFile> list, boolean openAfter) {
        if (list.isEmpty() || mediaManager == null || downloading) return;
        queue = new ArrayList<>(list);
        Collections.reverse(queue); // oldest first so the gallery keeps flight order
        queueIndex = 0;
        savedUris.clear();
        failures.clear();
        cancelRequested = false;
        openWhenDone = openAfter;
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
                        Uri uri = GallerySaver.save(MediaActivity.this, downloaded, file.getTimeCreated());
                        ui(() -> markSaved(file, uri));
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
        selectMode = false;
        adapter.notifyDataSetChanged();
        updateButtons();
        if (scheduler != null) scheduler.resume(null);

        if (autoSince > 0) {
            String msg = savedUris.size() + (savedUris.size() == 1 ? " file" : " files") + " saved to the Air Pilot album";
            if (DriveSync.isEnabled(this) && !savedUris.isEmpty()) msg += " and queued for Google Drive";
            if (!failures.isEmpty()) msg += ". " + failures.size() + " failed (open Photos & Videos to retry)";
            android.widget.Toast.makeText(this, msg + ".", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (openWhenDone && savedUris.size() == 1 && failures.isEmpty() && !cancelRequested) {
            openUri(savedUris.get(0));
            return;
        }

        int count = savedUris.size();
        String summary = count + (count == 1 ? " file" : " files")
                + " saved to your gallery in the \"" + GallerySaver.ALBUM + "\" album"
                + (Prefs.byDate(this) ? ", in a folder for each day." : ".");
        if (cancelRequested) summary = "Cancelled. " + summary;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Download finished");
        if (failures.isEmpty()) {
            b.setMessage(summary);
        } else {
            b.setMessage(summary + "\n\nNot saved:\n" + TextUtils.join("\n", failures));
        }
        b.setNegativeButton("OK", null);
        if (count > 0) {
            ArrayList<Uri> uris = new ArrayList<>(savedUris);
            b.setPositiveButton("Send to Drive…", (d, w) ->
                    FlightExport.shareUris(this, "Send to Google Drive", "*/*", uris));
            b.setNeutralButton("Open gallery", (d, w) -> openGallery());
        }
        b.show();
    }

    private void openUri(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            String mime = getContentResolver().getType(uri);
            i.setDataAndType(uri, mime != null ? mime : "*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            openGallery();
        }
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

    private void markSaved(MediaFile f, Uri uri) {
        savedUris.add(uri);
        DriveSync.enqueue(this, uri, f.getFileName(), GallerySaver.subFolder(this, f.getTimeCreated()));
        saved.add(key(f));
        SharedPreferences.Editor e = Prefs.raw(this).edit();
        e.putStringSet(SAVED_KEY, new HashSet<>(saved));
        e.apply();
        notifyFile(f);
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
        if (viewer.getVisibility() == View.VISIBLE) {
            closeViewer();
            return;
        }
        if (selectMode && !downloading) {
            setSelectMode(false);
            return;
        }
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

    private static String sizeText(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
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

    // ---------------------------------------------------------------- grid

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) {
            super(v);
        }
    }

    private final class MediaAdapter extends RecyclerView.Adapter<Holder> {
        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_HEADER ? R.layout.item_media_header : R.layout.item_media;
            return new Holder(LayoutInflater.from(MediaActivity.this).inflate(layout, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Object row = rows.get(position);
            View v = h.itemView;
            if (row instanceof String) {
                String day = (String) row;
                int count = 0, sel = 0;
                for (MediaFile f : files) {
                    if (dayLabel(f.getTimeCreated()).equals(day)) {
                        count++;
                        if (selected.contains(key(f))) sel++;
                    }
                }
                ((TextView) v.findViewById(R.id.day)).setText(day + "  ·  " + count);
                TextView selDay = v.findViewById(R.id.select_day);
                selDay.setText(sel == count && count > 0 ? "Clear day" : "Select day");
                selDay.setOnClickListener(x -> toggleDay(day));
                return;
            }
            MediaFile f = (MediaFile) row;
            ((ImageView) v.findViewById(R.id.thumb)).setImageBitmap(f.getThumbnail());

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

            v.setOnClickListener(x -> {
                if (downloading) return;
                if (selectMode) toggleFile(f);
                else showViewer(files.indexOf(f));
            });
            v.setOnLongClickListener(x -> {
                if (downloading) return true;
                selectMode = true;
                toggleFile(f);
                return true;
            });
        }
    }
}
