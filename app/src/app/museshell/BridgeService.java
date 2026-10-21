package app.museshell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class BridgeService extends Service {
    private static final String CHANNEL = "muse_bridge";
    private volatile boolean running = false;
    private Thread worker;
    private WindowManager wm;
    private View ball;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Ledger.init(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Muse 壳连接", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(1, buildNotification("连接中…"));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "muse:bridge");
        wakeLock.setReferenceCounted(false);
        attachBall();
        try {
            Ledger.log(this, "service_start", null);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Muse 壳")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void setNotificationText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification(text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "muse-bridge");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    private void loop() {
        ToolDispatcher dispatcher = new ToolDispatcher(this);
        long backoff = 2000;
        while (running) {
            try {
                if (Prefs.killSwitch(this)) {
                    setNotificationText("急停已开启，服务待机");
                    Thread.sleep(3000);
                    continue;
                }
                String base = Prefs.baseUrl(this);
                String token = Prefs.token(this);
                String dev = Prefs.deviceId(this);
                HttpUtil.Resp r = HttpUtil.request("GET", base + "/poll", token, dev, null, 10000, 40000);
                if (r.code == 401) {
                    setNotificationText("令牌不对，检查配对设置");
                    Thread.sleep(10000);
                    continue;
                }
                if (r.code == 204 || r.body == null || r.body.length() == 0) {
                    backoff = 2000;
                    continue;
                }
                if (r.code != 200) {
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                    continue;
                }
                backoff = 2000;
                setNotificationText("已连接，执行命令中…");
                JSONObject cmd = new JSONObject(r.body);
                wakeLock.acquire(120000);
                JSONObject result;
                try {
                    result = dispatcher.dispatch(cmd);
                } finally {
                    if (wakeLock.isHeld()) wakeLock.release();
                }
                HttpUtil.request("POST", base + "/result", token, dev, result.toString(), 10000, 15000);
                setNotificationText("已连接，待机");
            } catch (Exception e) {
                try {
                    setNotificationText("连接中断，重连中…");
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    // ---------- floating ball: tap opens console, long-press toggles kill switch ----------

    private void attachBall() {
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            final TextView tv = new TextView(this);
            tv.setText("M");
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(18);
            tv.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(0xCC2962FF);
            tv.setBackground(gd);
            int size = 120;
            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    size, size,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = 24;
            lp.y = 240;
            tv.setOnTouchListener(new View.OnTouchListener() {
                private int startX, startY;
                private float touchX, touchY;
                private long downAt;
                private boolean longFired;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = lp.x;
                            startY = lp.y;
                            touchX = e.getRawX();
                            touchY = e.getRawY();
                            downAt = System.currentTimeMillis();
                            break;
                        case MotionEvent.ACTION_MOVE:
                            lp.x = startX - (int) (e.getRawX() - touchX);
                            lp.y = startY + (int) (e.getRawY() - touchY);
                            try { wm.updateViewLayout(tv, lp); } catch (Exception ignored) {}
                            break;
                        case MotionEvent.ACTION_UP:
                            long held = System.currentTimeMillis() - downAt;
                            int moved = Math.abs((int) (e.getRawX() - touchX))
                                    + Math.abs((int) (e.getRawY() - touchY));
                            if (held >= 800 && moved < 20) {
                                boolean on = !Prefs.killSwitch(BridgeService.this);
                                Prefs.setKillSwitch(BridgeService.this, on);
                                Toast.makeText(BridgeService.this,
                                        on ? "急停已开启" : "急停已关闭", Toast.LENGTH_SHORT).show();
                            } else if (moved < 20) {
                                Intent open = new Intent(BridgeService.this, MainActivity.class);
                                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(open);
                            }
                            break;
                    }
                    return true;
                }
            });
            ball = tv;
            wm.addView(tv, lp);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (wm != null && ball != null) {
            try { wm.removeView(ball); } catch (Exception ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { Ledger.log(this, "service_stop", null); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
