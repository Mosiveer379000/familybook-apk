package com.familybook.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** ইন্টারনেট আছে কি নেই (Firebase .info/connected-এর বদলে Android ConnectivityManager)। */
public class Net {

    public interface Callback { void onChange(boolean online); }

    private static volatile boolean online = true;
    private static Callback callback;
    private static final java.util.List<Callback> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static boolean isOnline() { return online; }

    public static void setCallback(Callback c) { callback = c; }

    /** একাধিক শ্রোতা (যেমন PostQueue) — setCallback-এর সাথে ঝামেলা করে না */
    public static void addListener(Callback c) { if (c != null && !LISTENERS.contains(c)) LISTENERS.add(c); }

    public static void removeListener(Callback c) { LISTENERS.remove(c); }

    public static synchronized void start(Context app) {
        final ConnectivityManager cm =
                (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        publish(currentlyOnline(cm));
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { publish(true); }
            @Override public void onLost(Network n) { publish(currentlyOnline(cm)); }
        });
    }

    private static boolean currentlyOnline(ConnectivityManager cm) {
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void publish(boolean v) {
        online = v;
        MAIN.post(() -> {
            Callback c = callback;
            if (c != null) c.onChange(v);
            for (Callback l : LISTENERS) l.onChange(v);
        });
    }

    /** অফলাইনে কোনো কাজ করতে দেয় না। */
    public static boolean check(Context c) {
        if (online) return true;
        Toast.makeText(c, "ইন্টারনেট নেই — নেট এলে আবার চেষ্টা করুন", Toast.LENGTH_SHORT).show();
        return false;
    }
}