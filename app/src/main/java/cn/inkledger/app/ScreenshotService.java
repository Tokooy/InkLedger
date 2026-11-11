package cn.inkledger.app;

import android.Manifest;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.MediaStore.Images.Media;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ScreenshotService extends Service {
    static final String STOP = "cn.inkledger.app.STOP";
    static final String CHANGED = "cn.inkledger.app.CHANGED";
    private static final String TAG = "InkLedger";
    private static final long POLL_INTERVAL = 20_000;
    private static final Pattern SCREENSHOT_NAME = Pattern.compile(
            "^(?:screenshot|screen_shot|截屏|截图)[_ -]?(?:20[0-9]{6}|20[0-9]{2}[-_][0-9]{2}[-_][0-9]{2}).*");

    private static volatile boolean active;
    private volatile boolean stopping;
    private final Map<String, Volume> volumes = new HashMap<>();
    private HandlerThread workerThread;
    private Handler worker;
    private ContentObserver observer;
    private TextRecognizer recognizer;
    private long startSecond;

    private record Volume(String version, long start, long scanned) {}
    private record Screenshot(Uri uri, String imageKey, long takenAt) {}
    private record Line(Rect bounds, String text) {}

    static boolean isActive() {
        return active;
    }

    static boolean hasImageAccess(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !hasImageAccess(this)) {
            stopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) return START_NOT_STICKY;

        try {
            startForeground(Notices.MONITOR_ID, Notices.monitor(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (IllegalStateException | SecurityException failure) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startSecond = System.currentTimeMillis() / 1000;
        workerThread = new HandlerThread("screenshot-ocr");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        observer = new ContentObserver(worker) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                worker.removeCallbacks(scan);
                worker.postDelayed(scan, 350);
            }
        };
        getContentResolver().registerContentObserver(MediaStore.AUTHORITY_URI, true, observer);
        worker.post(scan);
        return START_NOT_STICKY;
    }

    private final Runnable scan = new Runnable() {
        @Override
        public void run() {
            if (stopping) return;
            if (!hasImageAccess(ScreenshotService.this)) {
                stopping = true;
                stopSelf();
                return;
            }
            boolean retry = false;
            try {
                for (String name : MediaStore.getExternalVolumeNames(ScreenshotService.this)) {
                    if (stopping) return;
                    String version = MediaStore.getVersion(ScreenshotService.this, name);
                    if (version == null) continue;
                    long generation = MediaStore.getGeneration(ScreenshotService.this, name);
                    Volume previous = volumes.get(name);
                    if (previous == null || !version.equals(previous.version()) || generation < previous.scanned()) {
                        volumes.put(name, new Volume(version, generation, generation));
                    } else if (generation > previous.scanned()) {
                        if (scanVolume(name, previous, generation)) {
                            volumes.put(name, new Volume(version, previous.start(), generation));
                        } else {
                            retry = true;
                        }
                    }
                }
                if (!stopping && !active) {
                    active = true;
                    notifyChanged();
                }
            } catch (SecurityException denied) {
                stopping = true;
                stopSelf();
            } catch (RuntimeException failure) {
                Log.w(TAG, "Unable to query new media");
            } finally {
                if (!stopping) {
                    worker.removeCallbacks(this);
                    worker.postDelayed(this, retry ? 1_500 : POLL_INTERVAL);
                }
            }
        }
    };

    private boolean scanVolume(String name, Volume state, long upper) {
        Uri collection = Media.getContentUri(name);
        String[] columns = {Media._ID, Media.DISPLAY_NAME, Media.RELATIVE_PATH, Media.DATE_ADDED,
                Media.DATE_TAKEN, Media.GENERATION_ADDED};
        String selection = Media.GENERATION_ADDED + " > ? AND " + Media.GENERATION_MODIFIED + " > ? AND "
                + Media.GENERATION_MODIFIED + " <= ? AND " + Media.DATE_ADDED + " >= ?";
        String[] args = {Long.toString(state.start()), Long.toString(state.scanned()),
                Long.toString(upper), Long.toString(startSecond)};
        List<Screenshot> images = new ArrayList<>();
        try (Cursor rows = getContentResolver().query(collection, columns, selection, args, Media.GENERATION_ADDED + " ASC")) {
            if (rows == null) return false;
            while (rows.moveToNext()) {
                if (!isScreenshot(rows.getString(1), rows.getString(2))) continue;
                long added = rows.getLong(3) * 1000;
                long taken = rows.getLong(4);
                if (taken <= 0 || Math.abs(taken - added) > 600_000) taken = added;
                long id = rows.getLong(0);
                String imageKey = digest(name + ":" + state.version() + ":" + id + ":" + rows.getLong(5));
                images.add(new Screenshot(ContentUris.withAppendedId(collection, id), imageKey, taken));
            }
        }

        boolean complete = true;
        for (Screenshot screenshot : images) {
            if (stopping) return false;
            if (failures.getOrDefault(screenshot.imageKey(), 0) >= 2) continue;
            if (!recognize(screenshot)) {
                int count = failures.merge(screenshot.imageKey(), 1, Integer::sum);
                if (count < 2) complete = false;
            }
        }
        if (complete) images.forEach(image -> failures.remove(image.imageKey()));
        return complete;
    }

    private final Map<String, Integer> failures = new HashMap<>();

    private boolean recognize(Screenshot screenshot) {
        try {
            Text text = Tasks.await(recognizer.process(InputImage.fromFilePath(this, screenshot.uri())),
                    12, TimeUnit.SECONDS);
            if (stopping) return true;
            PaymentParser.Result result = PaymentParser.parse(readRows(text), screenshot.takenAt());
            if (result == null) return true;
            String orderKey = result.order().isEmpty() ? null : digest(result.order());
            if (Ledger.get(this).insert(result, screenshot.imageKey(), orderKey)) {
                notifyChanged();
                if (!stopping) {
                    try {
                        Notices.expense(this, result);
                    } catch (RuntimeException failure) {
                        Log.w(TAG, "Unable to show expense notification");
                    }
                }
            }
            return true;
        } catch (IOException | ExecutionException | TimeoutException ignored) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isScreenshot(String name, String relativePath) {
        if (name == null || relativePath == null) return false;
        String path = relativePath.toLowerCase(Locale.ROOT);
        if (!path.startsWith("dcim/") && !path.startsWith("pictures/")) return false;
        return ("/" + path).contains("/screenshots/")
                || SCREENSHOT_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    private static List<String> readRows(Text text) {
        List<Line> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect bounds = line.getBoundingBox();
                if (bounds != null) lines.add(new Line(bounds, line.getText()));
            }
        }
        lines.sort(Comparator.comparingInt(line -> line.bounds().centerY()));
        List<String> rows = new ArrayList<>();
        for (int from = 0; from < lines.size();) {
            Rect first = lines.get(from).bounds();
            int to = from + 1;
            while (to < lines.size()) {
                Rect next = lines.get(to).bounds();
                int tolerance = Math.max(3, Math.min(first.height(), next.height()) / 2);
                if (next.centerY() - first.centerY() > tolerance) break;
                to++;
            }
            List<Line> row = lines.subList(from, to);
            row.sort(Comparator.comparingInt(line -> line.bounds().left));
            rows.add(row.stream().map(Line::text).collect(Collectors.joining(" ")));
            from = to;
        }
        return rows;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(Character.forDigit((b & 0xf0) >>> 4, 16))
                    .append(Character.forDigit(b & 0x0f, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void notifyChanged() {
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        active = false;
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {
                recognizer.close();
                workerThread.quitSafely();
            });
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyChanged();
        super.onDestroy();
    }
}
