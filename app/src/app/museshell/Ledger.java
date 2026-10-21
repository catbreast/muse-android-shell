package app.museshell;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Append-only JSONL ledger of every tool call / approval / lifecycle event. */
public class Ledger {
    private static final Object LOCK = new Object();
    private static File dir;

    public static synchronized void init(Context c) {
        File ext = new File(Environment.getExternalStorageDirectory(), "MuseShell");
        if (dir != null) {
            // Reprobe: if we fell back to the app dir at first init (perms not yet
            // granted) but /sdcard/MuseShell is writable now, switch to it.
            if (!dir.equals(ext) && extWritable(ext)) dir = ext;
            return;
        }
        File target = ext;
        try {
            if (!ext.exists()) ext.mkdirs();
            File probe = new File(ext, ".probe");
            FileWriter w = new FileWriter(probe);
            w.write("ok");
            w.close();
            probe.delete();
        } catch (Exception e) {
            target = c.getExternalFilesDir(null);
            if (target == null) target = c.getFilesDir();
        }
        dir = target;
    }

    private static boolean extWritable(File ext) {
        try {
            if (!ext.exists()) ext.mkdirs();
            File probe = new File(ext, ".probe");
            FileWriter w = new FileWriter(probe);
            w.write("ok");
            w.close();
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static File dir(Context c) {
        init(c);
        return dir;
    }

    public static void log(Context c, String event, JSONObject details) {
        try {
            init(c);
            JSONObject row = new JSONObject();
            row.put("ts", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            row.put("event", event);
            if (details != null) row.put("details", details);
            synchronized (LOCK) {
                FileWriter w = new FileWriter(new File(dir, "ledger.jsonl"), true);
                w.write(row.toString());
                w.write("\n");
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    public static String recent(Context c, int n) {
        try {
            init(c);
            File f = new File(dir, "ledger.jsonl");
            if (!f.exists()) return "(暂无日志)";
            List<String> lines = new ArrayList<String>();
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
            r.close();
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.size() - n);
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }
}
