package app.museshell;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView logView;
    private EditText urlEdit;
    private EditText tokenEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ledger.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 32, 36, 32);

        TextView title = new TextView(this);
        title.setText("Muse 壳 · 控制台");
        title.setTextSize(22);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, 16, 0, 16);
        root.addView(statusView);

        urlEdit = new EditText(this);
        urlEdit.setHint("服务地址，如 https://phone.example.com");
        urlEdit.setSingleLine(true);
        root.addView(urlEdit);

        tokenEdit = new EditText(this);
        tokenEdit.setHint("配对令牌（向你的 Muse 索取）");
        tokenEdit.setSingleLine(true);
        root.addView(tokenEdit);

        root.addView(btn("保存配对并启动服务", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.sp(MainActivity.this).edit()
                        .putString("base_url", urlEdit.getText().toString().trim())
                        .putString("token", tokenEdit.getText().toString().trim())
                        .apply();
                startBridge();
                toast("已保存，服务启动中");
                refresh();
            }
        }));

        LinearLayout row1 = row();
        row1.addView(btn("启动服务", new View.OnClickListener() {
            @Override public void onClick(View v) { startBridge(); refresh(); }
        }));
        row1.addView(btn("停止服务", new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, BridgeService.class));
                toast("服务已停止");
                refresh();
            }
        }));
        root.addView(row1);

        LinearLayout row2 = row();
        row2.addView(btn("文件权限", new View.OnClickListener() {
            @Override public void onClick(View v) { askFilePerms(); }
        }));
        row2.addView(btn("无障碍设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));
        root.addView(row2);

        LinearLayout row3 = row();
        row3.addView(btn("悬浮窗权限", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            }
        }));
        row3.addView(btn("电池白名单", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            }
        }));
        root.addView(row3);

        CheckBox kill = new CheckBox(this);
        kill.setText("急停开关（开启后拒绝一切工具调用）");
        kill.setChecked(Prefs.killSwitch(this));
        kill.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                Prefs.setKillSwitch(MainActivity.this, checked);
                toast(checked ? "急停已开启" : "急停已关闭");
                refresh();
            }
        });
        root.addView(kill);

        CheckBox shellTrust = new CheckBox(this);
        shellTrust.setText("root shell 本次会话免审（服务重启后失效）");
        shellTrust.setChecked(ToolDispatcher.shellTrustSession);
        shellTrust.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                ToolDispatcher.shellTrustSession = checked;
                toast(checked ? "shell 免审已开（本次会话）" : "shell 免审已关");
            }
        });
        root.addView(shellTrust);

        root.addView(btn("刷新状态与日志", new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        }));

        logView = new TextView(this);
        logView.setTextSize(11);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        urlEdit.setText(Prefs.baseUrl(this));
        tokenEdit.setText(Prefs.token(this));
        refresh();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void startBridge() {
        Intent svc = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
    }

    private void askFilePerms() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备 ID：").append(Prefs.deviceId(this)).append("\n");
        sb.append("服务地址：").append(Prefs.baseUrl(this)).append("\n");
        sb.append("已配对：").append(Prefs.token(this).length() > 0 ? "是" : "否").append("\n");
        sb.append("无障碍：").append(ShellA11yService.enabled() ? "已开启" : "未开启").append("\n");
        if (Build.VERSION.SDK_INT >= 30) {
            sb.append("所有文件访问：").append(Environment.isExternalStorageManager() ? "已授权" : "未授权").append("\n");
        }
        if (Build.VERSION.SDK_INT >= 23) {
            sb.append("悬浮窗：").append(Settings.canDrawOverlays(this) ? "已授权" : "未授权").append("\n");
        }
        sb.append("急停：").append(Prefs.killSwitch(this) ? "开启" : "关闭").append("\n");
        boolean perm = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        sb.append("存储权限：").append(perm ? "已授予" : "未授予").append("\n");
        statusView.setText(sb.toString());
        logView.setText(Ledger.recent(this, 30));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
