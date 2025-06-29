// MainActivity.java

package com.cinemint.rosterlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final String STATE_SEARCH_QUERY = "search_query";

    private LauncherViewModel viewModel;
    private LauncherPagerAdapter pagerAdapter;
    private String searchQuery = "";
    private TabLayoutMediator tabMediator;
    private boolean wasInBackground = false;
    private AlertDialog currentSearchDialog;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            com.cinemint.rosterlauncher.databinding.ActivityMainBinding binding =
                    com.cinemint.rosterlauncher.databinding.ActivityMainBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());

            // Initialize executor service for background tasks
            executorService = Executors.newSingleThreadExecutor();

            // Initialize ViewModel
            viewModel = new ViewModelProvider(this).get(LauncherViewModel.class);

            // Restore search query if available
            if (savedInstanceState != null) {
                searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
                viewModel.setSearchQuery(searchQuery);
            }

            // Setup UI components with null checks
            TabLayout tabLayout = findViewById(R.id.tabs);
            ViewPager2 viewPager = findViewById(R.id.viewPager);
            FloatingActionButton searchBtn = findViewById(R.id.searchBtn);

            if (tabLayout == null || viewPager == null || searchBtn == null) {
                throw new IllegalStateException("Required views not found in layout");
            }

            // Initialize apps if not already done
            if (!viewModel.isInitialized()) {
                loadAppsAsync();
            }

            // Setup ViewPager with fragments
            pagerAdapter = new LauncherPagerAdapter(this);
            viewPager.setAdapter(pagerAdapter);

            // Connect TabLayout with ViewPager2
            tabMediator = new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
                switch (position) {
                    case 0:
                        tab.setText("Pinned");
                        break;
                    case 1:
                        tab.setText("All");
                        break;
                }
            });
            tabMediator.attach();

            // Setup search button
            searchBtn.setOnClickListener(v -> showSearchDialog());

            // Observe search query changes with debouncing
            viewModel.getSearchQuery().observe(this, query -> {
                if (query != null) {
                    searchQuery = query;
                    // Add a small delay to prevent rapid fragment updates
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            refreshFragments();
                        }
                    }, 100);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            // Show error and finish gracefully
            Toast.makeText(this, "Error initializing launcher: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        wasInBackground = true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Only animate if we were actually in the background
        if (wasInBackground) {
            wasInBackground = false;

            // Add a small delay to ensure fragments are ready
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    notifyAllFragments();
                }
            }, 100);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SEARCH_QUERY, searchQuery);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up resources
        if (tabMediator != null) {
            tabMediator.detach();
        }

        if (currentSearchDialog != null && currentSearchDialog.isShowing()) {
            currentSearchDialog.dismiss();
        }

        if (searchHandler != null) {
            searchHandler.removeCallbacksAndMessages(null);
        }

        if (executorService != null) {
            executorService.shutdown();
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
            for (AppInfo app : apps) {
                app.isPinned = pinnedPackages.contains(app.packageName);
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

    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private static final long SEARCH_DELAY_MS = 300; // Debounce delay

    private void showSearchDialog() {
        if (isFinishing() || isDestroyed()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            final EditText input = new EditText(this);
            input.setHint("Search apps...");
            input.setText(searchQuery);
            input.setPadding(48, 32, 48, 32);
            input.setTextSize(18);
            input.setBackgroundResource(0);

            builder.setView(input);

            // Use a custom TextWatcher with debouncing
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // Cancel previous search
                    if (searchRunnable != null) {
                        searchHandler.removeCallbacks(searchRunnable);
                    }

                    final String query = s != null ? s.toString() : "";

                    // Debounce search to avoid rapid updates
                    searchRunnable = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!isFinishing() && !isDestroyed() && viewModel != null) {
                                    viewModel.setSearchQuery(query);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    };

                    searchHandler.postDelayed(searchRunnable, SEARCH_DELAY_MS);
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });

            builder.setPositiveButton("Clear", (dialog, which) -> {
                try {
                    if (!isFinishing() && !isDestroyed() && viewModel != null) {
                        if (searchRunnable != null) {
                            searchHandler.removeCallbacks(searchRunnable);
                        }
                        viewModel.setSearchQuery("");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            builder.setNegativeButton("Done", (dialog, which) -> {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
                dialog.dismiss();
            });

            builder.setOnDismissListener(dialog -> {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
            });

            // Dismiss any existing dialog
            if (currentSearchDialog != null && currentSearchDialog.isShowing()) {
                try {
                    currentSearchDialog.dismiss();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            currentSearchDialog = builder.create();
            currentSearchDialog.show();

            input.post(() -> {
                try {
                    input.requestFocus();
                    if (input.getText() != null) {
                        input.setSelection(input.getText().length());
                    }
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error showing search dialog", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshFragments() {
        if (pagerAdapter != null && !isFinishing() && !isDestroyed()) {
            try {
                pagerAdapter.notifyDataSetChanged();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void notifyAllFragments() {
        refreshFragments();
        // Notify visible fragments
        try {
            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof AppsFragment && fragment.isVisible() && fragment.isAdded()) {
                    ((AppsFragment) fragment).refreshAppList();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                    // Continue with next app if one fails
                }
            }

            // Sort alphabetically
            apps.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return apps;
    }

    // Fragment for displaying apps
    public static class AppsFragment extends Fragment {
        private static final String ARG_SHOW_PINNED = "show_pinned";
        private static final int ANIMATION_DURATION = 350;
        private static final int STAGGER_DELAY = 50;
        private boolean showPinned;
        private LinearLayout appsContainer;
        private LauncherViewModel viewModel;
        private Handler animationHandler;
        private List<AnimatorSet> runningAnimations = new ArrayList<>();
        private boolean isAnimating = false;

        public static AppsFragment newInstance(boolean showPinned) {
            AppsFragment fragment = new AppsFragment();
            Bundle args = new Bundle();
            args.putBoolean(ARG_SHOW_PINNED, showPinned);
            fragment.setArguments(args);
            return fragment;
        }

        private Handler refreshHandler;
        private Runnable refreshRunnable;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            animationHandler = new Handler(Looper.getMainLooper());
            refreshHandler = new Handler(Looper.getMainLooper());
            if (getActivity() != null) {
                viewModel = new ViewModelProvider(getActivity()).get(LauncherViewModel.class);
            }
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_apps, container, false);

            appsContainer = view.findViewById(R.id.appsContainer);
            showPinned = getArguments() != null && getArguments().getBoolean(ARG_SHOW_PINNED);

            // Observe data changes with debouncing
            if (viewModel != null) {
                viewModel.getAllApps().observe(getViewLifecycleOwner(), apps -> {
                    if (apps != null) {
                        debounceRefresh();
                    }
                });
                viewModel.getSearchQuery().observe(getViewLifecycleOwner(), query -> {
                    if (query != null) {
                        debounceRefresh();
                    }
                });
            }

            refreshAppList();

            return view;
        }

        private void debounceRefresh() {
            if (refreshRunnable != null) {
                refreshHandler.removeCallbacks(refreshRunnable);
            }

            refreshRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isAdded() && !isDetached()) {
                        refreshAppList();
                    }
                }
            };

            refreshHandler.postDelayed(refreshRunnable, 150);
        }

        @Override
        public void onDestroyView() {
            super.onDestroyView();
            // Cancel all running animations and clean up
            cancelAllAnimations();
            if (animationHandler != null) {
                animationHandler.removeCallbacksAndMessages(null);
            }
            if (refreshHandler != null) {
                refreshHandler.removeCallbacksAndMessages(null);
            }
        }

        private void cancelAllAnimations() {
            isAnimating = false;
            for (AnimatorSet animatorSet : runningAnimations) {
                animatorSet.cancel();
            }
            runningAnimations.clear();
            if (animationHandler != null) {
                animationHandler.removeCallbacksAndMessages(null);
            }
        }

        public void refreshAppList() {
            if (appsContainer == null || viewModel == null || !isAdded() || isDetached()) return;

            // Skip refresh if we're already animating
            if (isAnimating) {
                // Queue another refresh after animations complete
                refreshHandler.postDelayed(() -> {
                    if (isAdded() && !isDetached()) {
                        refreshAppList();
                    }
                }, ANIMATION_DURATION + 100);
                return;
            }

            try {
                // Cancel any running animations
                cancelAllAnimations();

                // Run on UI thread to ensure thread safety
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (!isAdded() || isDetached() || appsContainer == null) return;

                        appsContainer.removeAllViews();
                        List<AppInfo> appsToShow = getFilteredApps();

                        if (appsToShow.isEmpty()) {
                            // Show empty state
                            TextView emptyView = new TextView(getContext());
                            emptyView.setText(showPinned ? "No pinned apps" : "No apps found");
                            emptyView.setPadding(32, 32, 32, 32);
                            emptyView.setTextSize(18);
                            appsContainer.addView(emptyView);
                            return;
                        }

                        // Add all views first but keep them invisible
                        List<View> appViews = new ArrayList<>();
                        for (AppInfo app : appsToShow) {
                            View appView = createAppView(app);
                            if (appView != null) {
                                appView.setAlpha(0f);
                                appView.setTranslationX(300f);
                                appsContainer.addView(appView);
                                appViews.add(appView);
                            }
                        }

                        // Animate views with stagger effect
                        if (!appViews.isEmpty() && isVisible()) {
                            animateViewsIn(appViews);
                        } else {
                            // If not visible, just show without animation
                            for (View view : appViews) {
                                view.setAlpha(1f);
                                view.setTranslationX(0f);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void animateViewsIn(List<View> views) {
            if (!isAdded() || isDetached() || isAnimating) return;

            isAnimating = true;

            for (int i = 0; i < views.size(); i++) {
                final View view = views.get(i);
                final int delay = i * STAGGER_DELAY;

                animationHandler.postDelayed(() -> {
                    if (!isAdded() || isDetached() || view.getParent() == null) return;

                    try {
                        // Create fly-in animation
                        ObjectAnimator translateX = ObjectAnimator.ofFloat(view, "translationX", 300f, 0f);
                        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);

                        // Add slight rotation for more dynamic effect
                        ObjectAnimator rotationY = ObjectAnimator.ofFloat(view, "rotationY", 15f, 0f);

                        AnimatorSet animatorSet = new AnimatorSet();
                        animatorSet.playTogether(translateX, alpha, rotationY);
                        animatorSet.setDuration(ANIMATION_DURATION);
                        animatorSet.setInterpolator(new DecelerateInterpolator(1.5f));

                        animatorSet.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                runningAnimations.remove(animatorSet);
                                if (runningAnimations.isEmpty()) {
                                    isAnimating = false;
                                }
                            }

                            @Override
                            public void onAnimationCancel(Animator animation) {
                                // Ensure view is in final state if animation is cancelled
                                view.setAlpha(1f);
                                view.setTranslationX(0f);
                                view.setRotationY(0f);
                            }
                        });

                        runningAnimations.add(animatorSet);
                        animatorSet.start();
                    } catch (Exception e) {
                        e.printStackTrace();
                        // If animation fails, just show the view
                        view.setAlpha(1f);
                        view.setTranslationX(0f);
                        view.setRotationY(0f);
                    }
                }, delay);
            }
        }

        private List<AppInfo> getFilteredApps() {
            List<AppInfo> filteredApps = new ArrayList<>();

            try {
                List<AppInfo> allApps = viewModel.getAllApps().getValue();
                String searchQuery = viewModel.getSearchQuery().getValue();

                if (allApps == null) return filteredApps;
                if (searchQuery == null) searchQuery = "";

                // Create a defensive copy to avoid concurrent modification
                List<AppInfo> appsCopy = new ArrayList<>(allApps);
                String queryLower = searchQuery.toLowerCase();

                for (AppInfo app : appsCopy) {
                    if (app == null || app.name == null) continue;

                    if (showPinned && !app.isPinned) {
                        continue;
                    }

                    if (!searchQuery.isEmpty() &&
                            !app.name.toLowerCase().contains(queryLower)) {
                        continue;
                    }

                    filteredApps.add(app);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            return filteredApps;
        }

        @NonNull
        private View createAppView(AppInfo app) {
            Context context = getContext();
            if (context == null) return new View(requireContext());

            try {
                LinearLayout itemLayout = new LinearLayout(context);
                itemLayout.setOrientation(LinearLayout.HORIZONTAL);
                itemLayout.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                itemLayout.setPadding(32, 32, 32, 32);
                itemLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
                itemLayout.setClickable(true);
                itemLayout.setFocusable(true);

                android.util.TypedValue outValue = new android.util.TypedValue();
                context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                itemLayout.setBackgroundResource(outValue.resourceId);

                ImageView iconView = new ImageView(context);
                iconView.setImageDrawable(app.icon);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(128, 128);
                iconParams.setMargins(0, 0, 32, 0);
                iconView.setLayoutParams(iconParams);

                TextView nameView = new TextView(context);
                nameView.setText(app.name);
                nameView.setTextSize(24);
                nameView.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                itemLayout.addView(iconView);
                itemLayout.addView(nameView);

                // Use weak reference to avoid memory leaks
                final WeakReference<AppsFragment> fragmentRef = new WeakReference<>(this);

                itemLayout.setOnClickListener(v -> {
                    AppsFragment fragment = fragmentRef.get();
                    if (fragment != null && fragment.isAdded()) {
                        // Add a subtle press animation
                        v.animate()
                                .scaleX(0.95f)
                                .scaleY(0.95f)
                                .setDuration(100)
                                .withEndAction(() -> {
                                    v.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(100)
                                            .start();
                                    fragment.launchApp(app.packageName);
                                })
                                .start();
                    }
                });

                itemLayout.setOnLongClickListener(v -> {
                    AppsFragment fragment = fragmentRef.get();
                    if (fragment != null && fragment.isAdded()) {
                        // Add haptic feedback style animation
                        v.animate()
                                .scaleX(1.05f)
                                .scaleY(1.05f)
                                .setDuration(100)
                                .withEndAction(() -> {
                                    v.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(100)
                                            .start();
                                })
                                .start();
                        fragment.showPinDialog(app);
                        return true;
                    }
                    return false;
                });

                return itemLayout;
            } catch (Exception e) {
                e.printStackTrace();
                return new View(context);
            }
        }

        private void showPinDialog(AppInfo app) {
            Context context = getContext();
            if (context == null || !isAdded() || isDetached()) return;

            try {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle(app.name);

                String action = app.isPinned ? "Remove from Pinned" : "Add to Pinned";
                builder.setItems(new String[]{action}, (dialog, which) -> {
                    if (isAdded() && !isDetached()) {
                        togglePin(app);
                    }
                });

                builder.show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void togglePin(AppInfo app) {
            try {
                app.isPinned = !app.isPinned;

                MainActivity activity = (MainActivity) getActivity();
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    activity.savePinnedApps();

                    String message = app.isPinned ? "Added to Pinned" : "Removed from Pinned";
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

                    viewModel.notifyDataChanged();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void launchApp(String packageName) {
            Context context = getContext();
            if (context == null) return;

            try {
                PackageManager pm = context.getPackageManager();
                Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    Toast.makeText(context, "Cannot launch this app", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(context, "Error launching app", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ViewPager adapter for tabs
    private static class LauncherPagerAdapter extends FragmentStateAdapter {
        public LauncherPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == 0) {
                return AppsFragment.newInstance(true);
            }
            return AppsFragment.newInstance(false);
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }
}