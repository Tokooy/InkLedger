package cn.inkledger.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public final class MainActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Button start = new Button(this);
        start.setText("启用截图识别");
        start.setOnClickListener(view -> {
            if (ScreenshotService.hasImageAccess(this)) {
                startForegroundService(new Intent(this, ScreenshotService.class));
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                        Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        });
        setContentView(start);
    }
}
