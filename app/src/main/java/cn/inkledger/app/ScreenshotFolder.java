package cn.inkledger.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.Locale;
import java.util.Set;

final class ScreenshotFolder {
    private static final String KEY = "screenshot_tree";
    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final Set<String> NAMES = Set.of(
            "screenshots", "screenshot", "截屏录屏", "截图录屏", "截图和录屏", "截屏和录屏", "截屏", "截图");

    private ScreenshotFolder() {}

    static boolean resumeEnabled(Context context) {
        return context.getSharedPreferences("capture", Context.MODE_PRIVATE).getBoolean("resume", false);
    }

    static void setResumeEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit()
                .putBoolean("resume", enabled).apply();
    }

    static long scanSince(Context context, Uri folder) {
        var preferences = context.getSharedPreferences("capture", Context.MODE_PRIVATE);
        String key = "scan_since:" + folder;
        long since = preferences.getLong(key, 0);
        if (since == 0) {
            since = System.currentTimeMillis() / 1000 * 1000;
            preferences.edit().putLong(key, since).apply();
        }
        return since;
    }

    static Uri selected(Context context) {
        String saved = context.getSharedPreferences("capture", Context.MODE_PRIVATE).getString(KEY, null);
        if (saved == null) return null;
        Uri tree = Uri.parse(saved);
        for (var grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (grant.isReadPermission() && grant.getUri().equals(tree)) return tree;
        }
        return null;
    }

    static boolean select(Context context, Uri tree) {
        if (tree == null || !EXTERNAL_STORAGE.equals(tree.getAuthority())
                || !DocumentsContract.isTreeUri(tree)) return false;
        String id = DocumentsContract.getTreeDocumentId(tree);
        String name = id.substring(Math.max(id.lastIndexOf(':'), id.lastIndexOf('/')) + 1);
        if (!NAMES.contains(name.toLowerCase(Locale.ROOT))) return false;

        Uri previous = selected(context);
        try {
            context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException denied) {
            return false;
        }
        context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit()
                .putString(KEY, tree.toString()).apply();
        if (previous != null && !previous.equals(tree)) {
            try {
                context.getContentResolver().releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException alreadyRevoked) {
                // The old directory grant is already gone.
            }
        }
        return true;
    }
}
