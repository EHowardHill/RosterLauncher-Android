// MainActivity.java

package com.cinemint.rosterlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static class AppInfo {
        String name;
        String packageName;
        Drawable icon;
        boolean isPinned;

        AppInfo(String name, String packageName, Drawable icon) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isPinned = false;
        }
    }

    private static final String PREF_NAME = "RosterLauncherPrefs";
    private static final String PINNED_APPS_KEY = "pinned_apps";

    private LauncherViewModel viewModel;
    private LauncherPagerAdapter pagerAdapter;
    private TabLayoutMediator tabMediator;
    private boolean wasInBackground = false;
    private ExecutorService executorService;

    // Package changes receiver
    private BroadcastReceiver packageChangesReceiver;
    private Handler refreshHandler;

    // Improved uninstall tracking
    private String packageBeingUninstalled = null;
    private long uninstallStartTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            com.cinemint.rosterlauncher.databinding.ActivityMainBinding binding =
                    com.cinemint.rosterlauncher.databinding.ActivityMainBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());

            // Initialize handlers
            refreshHandler = new Handler(Looper.getMainLooper());

            // Initialize executor service for background tasks
            executorService = Executors.newSingleThreadExecutor();

            // Initialize ViewModel
            viewModel = new ViewModelProvider(this).get(LauncherViewModel.class);

            // Setup UI components
            TabLayout tabLayout = findViewById(R.id.tabs);
            ViewPager2 viewPager = findViewById(R.id.viewPager);

            if (tabLayout == null || viewPager == null) {
                throw new IllegalStateException("Required views not found in layout");
            }

            // Setup package changes listener
            setupPackageChangesReceiver();

            // Initialize apps
            if (!viewModel.isInitialized()) {
                loadAppsAsync();
            }

            // Setup ViewPager with fragments
            pagerAdapter = new LauncherPagerAdapter(this);
            viewPager.setAdapter(pagerAdapter);

            // Connect TabLayout with ViewPager2
            tabMediator = new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
                tab.setText(position == 0 ? "Pinned" : "All");
            });
            tabMediator.attach();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error initializing launcher: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setupPackageChangesReceiver() {
        packageChangesReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                        Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                        Intent.ACTION_PACKAGE_REPLACED.equals(action)) {

                    // Don't process if it's a package replacement
                    boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                    if (replacing) return;

                    String packageName = intent.getData() != null ?
                            intent.getData().getSchemeSpecificPart() : null;

                    if (packageName != null) {
                        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                            handlePackageRemoved(packageName);
                        } else {
                            // For added/replaced packages, just refresh
                            refreshHandler.postDelayed(() -> loadAppsAsync(), 500);
                        }
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");

        registerReceiver(packageChangesReceiver, filter);
    }

    private void handlePackageRemoved(String packageName) {
        // Immediately remove from ViewModel
        viewModel.removeApp(packageName);

        // Also remove from pinned apps if it was pinned
        executorService.execute(() -> {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Set<String> pinnedPackages = new HashSet<>(
                    prefs.getStringSet(PINNED_APPS_KEY, new HashSet<>())
            );

            if (pinnedPackages.remove(packageName)) {
                prefs.edit().putStringSet(PINNED_APPS_KEY, pinnedPackages).apply();
            }
        });

        // Schedule a full refresh after a delay to ensure consistency
        refreshHandler.postDelayed(this::loadAppsAsync, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        wasInBackground = true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (wasInBackground) {
            wasInBackground = false;

            // Check if we were uninstalling and enough time has passed
            if (packageBeingUninstalled != null &&
                    System.currentTimeMillis() - uninstallStartTime > 2000) {

                // Force a complete refresh
                recreateActivityIfNeeded();
            } else {
                // Normal refresh
                refreshHandler.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        loadAppsAsync();
                    }
                }, 100);
            }
        }
    }

    private void recreateActivityIfNeeded() {
        // If uninstall detection isn't working properly, force recreate
        if (packageBeingUninstalled != null) {
            packageBeingUninstalled = null;
            uninstallStartTime = 0;

            // Force complete refresh by recreating activity
            refreshHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    recreate();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister receiver
        if (packageChangesReceiver != null) {
            try {
                unregisterReceiver(packageChangesReceiver);
            } catch (Exception ignored) {}
        }

        // Clean up resources
        if (tabMediator != null) {
            tabMediator.detach();
        }

        if (executorService != null) {
            executorService.shutdown();
        }

        if (refreshHandler != null) {
            refreshHandler.removeCallbacksAndMessages(null);
        }
    }

    private void loadAppsAsync() {
        executorService.execute(() -> {
            try {
                List<AppInfo> apps = getInstalledApps();
                loadPinnedApps(apps);

                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        viewModel.setAllApps(apps);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Error loading apps", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void loadPinnedApps(List<AppInfo> apps) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Set<String> pinnedPackages = prefs.getStringSet(PINNED_APPS_KEY, new HashSet<>());

            // Clean up pinned apps that no longer exist
            Set<String> existingPackages = new HashSet<>();
            for (AppInfo app : apps) {
                existingPackages.add(app.packageName);
                app.isPinned = pinnedPackages.contains(app.packageName);
            }

            // Remove non-existent apps from pinned set
            boolean changed = pinnedPackages.retainAll(existingPackages);
            if (changed) {
                prefs.edit().putStringSet(PINNED_APPS_KEY, new HashSet<>(pinnedPackages)).apply();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void savePinnedApps() {
        executorService.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                Set<String> pinnedPackages = new HashSet<>();
                List<AppInfo> apps = viewModel.getAllApps().getValue();
                if (apps != null) {
                    for (AppInfo app : apps) {
                        if (app.isPinned) {
                            pinnedPackages.add(app.packageName);
                        }
                    }
                }
                prefs.edit().putStringSet(PINNED_APPS_KEY, pinnedPackages).apply();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private List<AppInfo> getInstalledApps() {
        List<AppInfo> apps = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            String selfPackageName = getPackageName();

            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            @SuppressLint("QueryPermissionsNeeded")
            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent,
                    PackageManager.MATCH_ALL);

            for (ResolveInfo resolveInfo : resolveInfos) {
                try {
                    String packageName = resolveInfo.activityInfo.packageName;

                    // Skip your own app
                    if (packageName.equals(selfPackageName)) {
                        continue;
                    }

                    String appName = resolveInfo.loadLabel(pm).toString();
                    Drawable appIcon = resolveInfo.loadIcon(pm);

                    apps.add(new AppInfo(appName, packageName, appIcon));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Sort alphabetically
            Collections.sort(apps, (a, b) -> a.name.compareToIgnoreCase(b.name));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return apps;
    }

    public void launchUninstall(String packageName) {
        packageBeingUninstalled = packageName;
        uninstallStartTime = System.currentTimeMillis();

        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(android.net.Uri.parse("package:" + packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Unable to launch uninstaller", Toast.LENGTH_SHORT).show();
            packageBeingUninstalled = null;
            uninstallStartTime = 0;
        }
    }

    // ViewPager adapter for tabs
    private static class LauncherPagerAdapter extends FragmentStateAdapter {
        public LauncherPagerAdapter(@NonNull AppCompatActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return AppsFragment.newInstance(position == 0);
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }
}