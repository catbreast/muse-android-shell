package app.museshell;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Runs commands as root via su (ReSukiSU). Env sanitized, output capped. */
public class ShellRunner {
    private static final int MAX_OUT = 64 * 1024;
    private static Boolean rootOk;

    public static boolean checkRoot() {
        if (rootOk != null) return rootOk;
        try {
            JSONObject r = execRaw("id", 8);
            rootOk = r.optInt("exit", -1) == 0 && r.optString("stdout", "").contains("uid=0");
        } catch (Exception e) {
            rootOk = Boolean.FALSE;
        }
        return rootOk;
    }

    public static JSONObject exec(String cmd, int timeoutSec) throws Exception {
        if (!checkRoot()) throw new Exception("su_unavailable: 设备 root 不可用或未给本应用授权");
        return execRaw(cmd, timeoutSec);
    }

    /** Fire-and-forget detached spawn (for the vscreen server). */
    public static void spawnDetached(String cmd) throws Exception {
        if (!checkRoot()) throw new Exception("su_unavailable");
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        sanitize(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().close();
        p.getOutputStream().close();
    }

    private static void sanitize(ProcessBuilder pb) {
        pb.environment().remove("LD_LIBRARY_PATH");
        pb.environment().remove("LD_PRELOAD");
        pb.environment().remove("LD_DEBUG");
    }

    private static JSONObject execRaw(String cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        sanitize(pb);
        final Process p = pb.start();
        final StringBuilder outBuf = new StringBuilder();
        final StringBuilder errBuf = new StringBuilder();
        Thread tOut = gobble(p.getInputStream(), outBuf);
        Thread tErr = gobble(p.getErrorStream(), errBuf);
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new Exception("shell_timeout_" + timeoutSec + "s");
        }
        tOut.join(1000);
        tErr.join(1000);
        JSONObject o = new JSONObject();
        o.put("exit", p.exitValue());
        o.put("stdout", cap(outBuf));
        o.put("stderr", cap(errBuf));
        return o;
    }

    private static String cap(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString();
            if (s.length() > MAX_OUT) return s.substring(0, MAX_OUT) + "\n...(truncated)";
            return s;
        }
    }

    private static Thread gobble(final InputStream is, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        synchronized (sb) {
                            if (sb.length() < MAX_OUT * 2) sb.append(buf, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
