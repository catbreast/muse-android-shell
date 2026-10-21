package app.museshell;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class Prefs {
    private static final String NAME = "muse_shell";

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String baseUrl(Context c) {
        return sp(c).getString("base_url", "https://phone.example.com");
    }

    public static String token(Context c) {
        return sp(c).getString("token", "");
    }

    public static String deviceId(Context c) {
        String id = sp(c).getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8);
            sp(c).edit().putString("device_id", id).apply();
        }
        return id;
    }

    public static boolean killSwitch(Context c) {
        return sp(c).getBoolean("kill_switch", false);
    }

    public static void setKillSwitch(Context c, boolean on) {
        sp(c).edit().putBoolean("kill_switch", on).apply();
    }
}
