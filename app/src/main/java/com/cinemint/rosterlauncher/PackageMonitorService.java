// PackageMonitorService.java

package com.cinemint.rosterlauncher;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Service to monitor package changes and notify the launcher
 * This provides more reliable package change detection than a simple BroadcastReceiver
 */
public class PackageMonitorService extends Service {
    private static final String TAG = "PackageMonitorService";
    private static final long DEBOUNCE_DELAY_MS = 500;

    private final IBinder binder = new LocalBinder();
    private PackageChangeReceiver packageChangeReceiver;
    private Handler handler;
    private final Set<PackageChangeListener> listeners = new HashSet<>();

    // Track recent changes to avoid duplicates
    private final Set<String> recentlyChangedPackages = new HashSet<>();
    private final Runnable clearRecentChangesRunnable = () -> recentlyChangedPackages.clear();

    public interface PackageChangeListener {
        void onPackageAdded(String packageName);
        void onPackageRemoved(String packageName);
        void onPackageChanged(String packageName);
    }

    public class LocalBinder extends Binder {
        public PackageMonitorService getService() {
            return PackageMonitorService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        setupPackageChangeReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Service will be recreated if killed
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (packageChangeReceiver != null) {
            try {
                unregisterReceiver(packageChangeReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }
        handler.removeCallbacksAndMessages(null);
    }

    public void addListener(PackageChangeListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(PackageChangeListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void setupPackageChangeReceiver() {
        packageChangeReceiver = new PackageChangeReceiver();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");

        try {
            registerReceiver(packageChangeReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "Error registering receiver", e);
        }
    }

    private class PackageChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getData() == null) {
                return;
            }

            String action = intent.getAction();
            String packageName = intent.getData().getSchemeSpecificPart();

            if (action == null || packageName == null) {
                return;
            }

            // Skip if we recently processed this package
            synchronized (recentlyChangedPackages) {
                if (recentlyChangedPackages.contains(packageName)) {
                    return;
                }
                recentlyChangedPackages.add(packageName);
            }

            // Clear recent changes after delay
            handler.removeCallbacks(clearRecentChangesRunnable);
            handler.postDelayed(clearRecentChangesRunnable, DEBOUNCE_DELAY_MS * 2);

            // Check if it's a replacement (update)
            boolean isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

            Log.d(TAG, "Package change: " + action + " - " + packageName +
                    " (replacing: " + isReplacing + ")");

            // Handle the change
            handler.post(() -> {
                switch (action) {
                    case Intent.ACTION_PACKAGE_ADDED:
                        if (!isReplacing) {
                            notifyPackageAdded(packageName);
                        }
                        break;

                    case Intent.ACTION_PACKAGE_REMOVED:
                    case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                        if (!isReplacing) {
                            notifyPackageRemoved(packageName);
                        }
                        break;

                    case Intent.ACTION_PACKAGE_REPLACED:
                    case Intent.ACTION_PACKAGE_CHANGED:
                        notifyPackageChanged(packageName);
                        break;
                }
            });
        }
    }

    private void notifyPackageAdded(String packageName) {
        synchronized (listeners) {
            for (PackageChangeListener listener : listeners) {
                try {
                    listener.onPackageAdded(packageName);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener", e);
                }
            }
        }
    }

    private void notifyPackageRemoved(String packageName) {
        synchronized (listeners) {
            for (PackageChangeListener listener : listeners) {
                try {
                    listener.onPackageRemoved(packageName);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener", e);
                }
            }
        }
    }

    private void notifyPackageChanged(String packageName) {
        synchronized (listeners) {
            for (PackageChangeListener listener : listeners) {
                try {
                    listener.onPackageChanged(packageName);
                } catch (Exception e) {
                    Log.e(TAG, "Error notifying listener", e);
                }
            }
        }
    }
}