package cn.inkledger.app;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public final class ScreenshotService extends Service {
    static final String STOP = "cn.inkledger.app.STOP";
    static final String CHANGED = "cn.inkledger.app.CHANGED";
    private static final String TAG = "InkLedger";
    private static final long POLL_INTERVAL = 10_000;

    private static volatile boolean active;
    private volatile boolean stopping;
    private final Set<String> handled = new HashSet<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private HandlerThread workerThread;
    private Handler worker;
    private ContentObserver observer;
    private TextRecognizer recognizer;
    private Uri tree;
    private Uri children;
    private long scanSince;

    private static final class Screenshot {
        private final Uri uri;
        private final String imageKey;
        private final long takenAt;

        Screenshot(Uri uri, String imageKey, long takenAt) {
            this.uri = uri;
            this.imageKey = imageKey;
            this.takenAt = takenAt;
        }

        Uri uri() { return uri; }
        String imageKey() { return imageKey; }
        long takenAt() { return takenAt; }
    }
    private static final class Line {
        private final Rect bounds;
        private final String text;

        Line(Rect bounds, String text) {
            this.bounds = bounds;
            this.text = text;
        }

        Rect bounds() { return bounds; }
        String text() { return text; }
    }

    static boolean isActive() {
        return active;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            ScreenshotFolder.setResumeEnabled(this, false);
            stopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) {
            worker.removeCallbacks(scan);
            worker.post(scan);
            return START_STICKY;
        }
        tree = ScreenshotFolder.selected(this);
        if (tree == null || !ScreenshotFolder.resumeEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));

        try {
            startForeground(Notices.MONITOR_ID, Notices.monitor(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (IllegalStateException | SecurityException failure) {
            stopSelf();
            return START_NOT_STICKY;
        }
        scanSince = ScreenshotFolder.scanSince(this, tree);
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
        try {
            getContentResolver().registerContentObserver(children, true, observer);
        } catch (RuntimeException unsupported) {
            // Polling also covers document providers that do not deliver change callbacks.
            observer = null;
        }
        worker.post(scan);
        return START_STICKY;
    }

    private final Runnable scan = new Runnable() {
        @Override
        public void run() {
            if (stopping) return;
            boolean retry = false;
            try {
                if (!tree.equals(ScreenshotFolder.selected(ScreenshotService.this))) {
                    stopping = true;
                    stopSelf();
                    return;
                }
                retry = scanFolder();
            } catch (SecurityException denied) {
                stopping = true;
                stopSelf();
            } catch (RuntimeException failure) {
                Log.w(TAG, "Unable to list the screenshot directory");
            } finally {
                if (!stopping) {
                    worker.removeCallbacks(this);
                    worker.postDelayed(this, retry ? 1_500 : POLL_INTERVAL);
                }
            }
        }
    };

    private boolean scanFolder() {
        String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE};
        Set<String> present = new HashSet<>();
        List<Screenshot> images = new ArrayList<>();
        try (Cursor rows = getContentResolver().query(children, columns, null, null, null)) {
            if (rows == null) return false;
            while (rows.moveToNext()) {
                String type = rows.getString(1);
                if (type == null || !type.startsWith("image/")) continue;
                String id = rows.getString(0);
                present.add(id);
                if (handled.contains(id) || (!rows.isNull(3) && rows.getLong(3) == 0)) continue;
                long modified = rows.getLong(2);
                if (modified < scanSince) continue;
                long takenAt = modified;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String key = digest(uri + ":" + modified + ":" + rows.getLong(3));
                images.add(new Screenshot(uri, key, takenAt));
            }
        }
        handled.retainAll(present);
        if (!active) {
            if (!stopping) {
                active = true;
                notifyChanged();
            }
        }

        boolean retry = false;
        images.sort(Comparator.comparingLong(Screenshot::takenAt));
        for (Screenshot image : images) {
            if (stopping) return false;
            Ledger ledger = Ledger.get(this);
            if (ledger.hasProcessed(image.imageKey()) || recognize(image)) {
                ledger.markProcessed(image.imageKey());
                handled.add(DocumentsContract.getDocumentId(image.uri()));
                failures.remove(image.imageKey());
            } else if (failures.merge(image.imageKey(), 1, Integer::sum) < 3) {
                retry = true;
            } else {
                handled.add(DocumentsContract.getDocumentId(image.uri()));
                failures.remove(image.imageKey());
            }
        }
        failures.keySet().retainAll(images.stream().map(Screenshot::imageKey).collect(Collectors.toSet()));
        return retry;
    }

    private boolean recognize(Screenshot screenshot) {
        try {
            Text text = Tasks.await(recognizer.process(InputImage.fromFilePath(this, screenshot.uri())),
                    12, TimeUnit.SECONDS);
            if (stopping) return false;
            List<String> rows = readRows(text);
            PaymentParser.Result single = PaymentParser.parse(rows, screenshot.takenAt());
            List<PaymentParser.Result> payments = single != null ? List.of(single)
                    : BillListParser.parse(rows, screenshot.takenAt());
            PaymentParser.Result newest = null;
            Ledger ledger = Ledger.get(this);
            Map<String, Integer> repeatedRows = new HashMap<>();
            for (PaymentParser.Result result : payments) {
                if (stopping) return false;
                String orderKey = result.order().isEmpty() ? null : digest(result.order());
                String key = screenshot.imageKey();
                if (single == null) {
                    String row = result.merchant().replaceAll("\\s+", "") + ":" + result.cents() + ":" + result.paidAt();
                    int occurrence = repeatedRows.merge(row, 1, Integer::sum);
                    key = digest("bill-list:" + row + ":" + occurrence);
                }
                if (ledger.insert(result, key, orderKey)
                        && (newest == null || result.paidAt() > newest.paidAt())) newest = result;
            }
            if (newest != null) {
                notifyChanged();
                if (!stopping) {
                    try {
                        Notices.expense(this, newest);
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
