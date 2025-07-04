// AppsFragment.java

package com.cinemint.rosterlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class AppsFragment extends Fragment {
    private static final String ARG_SHOW_PINNED = "show_pinned";
    private static final int ANIMATION_DURATION = 350;
    private static final int STAGGER_DELAY = 50;

    private boolean showPinned;
    private LinearLayout appsContainer;
    private ScrollView scrollView;
    private FrameLayout rootLayout;
    private LauncherViewModel viewModel;
    private Handler animationHandler;
    private Handler refreshHandler;

    // Search components
    private EditText searchBar;
    private LinearLayout searchContainer;
    private String currentSearchQuery = "";

    // Thread-safe list for animations
    private final CopyOnWriteArrayList<AnimatorSet> runningAnimations = new CopyOnWriteArrayList<>();
    private volatile boolean isAnimating = false;

    // Alphabet navigation
    private Map<String, Integer> letterPositions = new HashMap<>();
    private FrameLayout alphabetOverlay;

    // Debounce refresh
    private Runnable refreshRunnable;
    private static final long REFRESH_DEBOUNCE_MS = 150;

    public static AppsFragment newInstance(boolean showPinned) {
        AppsFragment fragment = new AppsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_SHOW_PINNED, showPinned);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        animationHandler = new Handler(Looper.getMainLooper());
        refreshHandler = new Handler(Looper.getMainLooper());

        if (getActivity() != null) {
            viewModel = new ViewModelProvider(getActivity()).get(LauncherViewModel.class);
        }

        if (getArguments() != null) {
            showPinned = getArguments().getBoolean(ARG_SHOW_PINNED);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Create root layout
        rootLayout = new FrameLayout(requireContext());
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Create ScrollView
        scrollView = new ScrollView(requireContext());
        FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);

        // Add margin bottom for search bar if this is the All apps tab
        if (!showPinned) {
            scrollParams.bottomMargin = 120; // Space for search bar
        }

        scrollView.setLayoutParams(scrollParams);
        scrollView.setFillViewport(true);

        // Create apps container
        appsContainer = new LinearLayout(requireContext());
        appsContainer.setOrientation(LinearLayout.VERTICAL);
        appsContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView.addView(appsContainer);
        rootLayout.addView(scrollView);

        return rootLayout;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Observe data changes
        if (viewModel != null) {
            viewModel.getAllApps().observe(getViewLifecycleOwner(), apps -> {
                if (apps != null) {
                    debounceRefresh();
                }
            });
        }

        refreshAppList();
    }

    @Override
    public void onDestroyView() {
        // Clean up animations and handlers
        cancelAllAnimations();

        if (animationHandler != null) {
            animationHandler.removeCallbacksAndMessages(null);
        }

        if (refreshHandler != null) {
            refreshHandler.removeCallbacksAndMessages(null);
        }

        super.onDestroyView();
    }

    private void debounceRefresh() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }

        refreshRunnable = () -> {
            if (isAdded() && !isDetached() && getView() != null) {
                refreshAppList();
            }
        };

        refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
    }

    private void cancelAllAnimations() {
        isAnimating = false;

        // Cancel all running animations
        for (AnimatorSet animatorSet : runningAnimations) {
            animatorSet.cancel();
        }
        runningAnimations.clear();

        // Clear animation handler
        if (animationHandler != null) {
            animationHandler.removeCallbacksAndMessages(null);
        }

        // Ensure all views in the container are visible
        if (appsContainer != null) {
            for (int i = 0; i < appsContainer.getChildCount(); i++) {
                View child = appsContainer.getChildAt(i);
                if (child != null) {
                    child.setAlpha(1f);
                    child.setTranslationX(0f);
                    child.setRotationY(0f);
                    child.setScaleX(1f);
                    child.setScaleY(1f);
                }
            }
        }
    }

    public void refreshAppList() {
        if (!isAdded() || isDetached() || getView() == null || appsContainer == null || viewModel == null) {
            return;
        }

        // Skip if animating on pinned tab
        if (showPinned && isAnimating) {
            refreshHandler.postDelayed(this::refreshAppList, ANIMATION_DURATION + 100);
            return;
        }

        try {
            // Cancel any running animations first
            cancelAllAnimations();

            // Store scroll position
            final int scrollY = scrollView.getScrollY();

            // Update UI on main thread
            requireActivity().runOnUiThread(() -> {
                if (!isAdded() || isDetached() || appsContainer == null) return;

                appsContainer.removeAllViews();
                letterPositions.clear();

                List<MainActivity.AppInfo> appsToShow = getFilteredApps();

                if (appsToShow.isEmpty()) {
                    showEmptyState();
                    return;
                }

                if (!showPinned) {
                    // Group by letter for "All" tab
                    displayGroupedApps(appsToShow);
                } else {
                    // Display pinned apps - with fix for visibility
                    displayPinnedApps(appsToShow);
                }

                // Restore scroll position
                scrollView.post(() -> {
                    if (scrollView != null) {
                        scrollView.setScrollY(scrollY);
                    }
                });
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showEmptyState() {
        TextView emptyView = new TextView(getContext());

        if (showPinned) {
            emptyView.setText("No pinned apps yet\n\nLong press any app to pin it");
        } else {
            if (!currentSearchQuery.isEmpty()) {
                emptyView.setText("No apps found matching \"" + currentSearchQuery + "\"");
            } else {
                emptyView.setText("No apps found");
            }
        }

        emptyView.setPadding(32, 64, 32, 64);
        emptyView.setTextSize(18);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Color.GRAY);
        appsContainer.addView(emptyView);
    }

    private void displayGroupedApps(List<MainActivity.AppInfo> apps) {
        TreeMap<String, List<MainActivity.AppInfo>> groupedApps = new TreeMap<>();

        for (MainActivity.AppInfo app : apps) {
            String firstLetter = app.name.substring(0, 1).toUpperCase();
            if (!Character.isLetter(firstLetter.charAt(0))) {
                firstLetter = "#";
            }
            groupedApps.computeIfAbsent(firstLetter, k -> new ArrayList<>()).add(app);
        }

        for (Map.Entry<String, List<MainActivity.AppInfo>> entry : groupedApps.entrySet()) {
            // Add letter header
            View letterHeader = createLetterHeader(entry.getKey());
            appsContainer.addView(letterHeader);
            letterPositions.put(entry.getKey(), appsContainer.getChildCount() - 1);

            // Add apps
            for (MainActivity.AppInfo app : entry.getValue()) {
                View appView = createAppView(app);
                if (appView != null) {
                    appView.setAlpha(1f);
                    appView.setTranslationX(0f);
                    appsContainer.addView(appView);
                }
            }
        }
    }

    private void displayPinnedApps(List<MainActivity.AppInfo> apps) {
        List<View> appViews = new ArrayList<>();

        for (MainActivity.AppInfo app : apps) {
            View appView = createAppView(app);
            if (appView != null) {
                // Check if we should animate
                boolean shouldAnimate = !isAnimating && isVisible() && getUserVisibleHint();

                if (shouldAnimate) {
                    appView.setAlpha(0f);
                    appView.setTranslationX(300f);
                } else {
                    // If not animating, ensure views are visible
                    appView.setAlpha(1f);
                    appView.setTranslationX(0f);
                    appView.setRotationY(0f);
                }

                appsContainer.addView(appView);
                appViews.add(appView);
            }
        }

        // Only animate if we have views and conditions are right
        if (!appViews.isEmpty() && isVisible() && !isAnimating) {
            // Check if this is the first load or a refresh after changes
            boolean isFirstLoad = appsContainer.getChildCount() == appViews.size();

            if (isFirstLoad && getUserVisibleHint()) {
                animateViewsIn(appViews);
            } else {
                // For refreshes, just ensure views are visible
                for (View view : appViews) {
                    view.setAlpha(1f);
                    view.setTranslationX(0f);
                    view.setRotationY(0f);
                }
            }
        }
    }

    private List<MainActivity.AppInfo> getFilteredApps() {
        List<MainActivity.AppInfo> filteredApps = new ArrayList<>();

        try {
            List<MainActivity.AppInfo> allApps = viewModel.getAllApps().getValue();
            if (allApps == null) return filteredApps;

            // Create defensive copy
            List<MainActivity.AppInfo> appsCopy = new ArrayList<>(allApps);

            for (MainActivity.AppInfo app : appsCopy) {
                if (app == null || app.name == null) continue;

                // For pinned tab, only show pinned apps
                if (showPinned) {
                    if (app.isPinned) {
                        filteredApps.add(app);
                    }
                } else {
                    // For All apps tab, apply search filter
                    if (currentSearchQuery.isEmpty() ||
                            app.name.toLowerCase().contains(currentSearchQuery.toLowerCase())) {
                        filteredApps.add(app);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return filteredApps;
    }

    private View createLetterHeader(String letter) {
        Context context = getContext();
        if (context == null) return new View(requireContext());

        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        headerLayout.setPadding(32, 16, 32, 16);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);

        // Make clickable with ripple
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        headerLayout.setBackgroundResource(outValue.resourceId);
        headerLayout.setClickable(true);
        headerLayout.setFocusable(true);
        headerLayout.setOnClickListener(v -> showAlphabetGrid());

        // Letter text
        TextView letterText = new TextView(context);
        letterText.setText(letter);
        letterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        letterText.setTypeface(null, Typeface.BOLD);
        letterText.setTextColor(getAccentColor());
        letterText.setPadding(0, 0, 16, 0);
        headerLayout.addView(letterText);

        // Separator line
        View separator = new View(context);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                0, 2, 1f);
        separator.setLayoutParams(separatorParams);
        separator.setBackgroundColor(Color.parseColor("#33000000"));
        headerLayout.addView(separator);

        return headerLayout;
    }

    @NonNull
    private View createAppView(MainActivity.AppInfo app) {
        Context context = getContext();
        if (context == null) return new View(requireContext());

        try {
            LinearLayout itemLayout = new LinearLayout(context);
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            itemLayout.setPadding(32, 32, 32, 32);
            itemLayout.setGravity(Gravity.CENTER_VERTICAL);
            itemLayout.setClickable(true);
            itemLayout.setFocusable(true);

            // Ripple effect
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            itemLayout.setBackgroundResource(outValue.resourceId);

            // App icon
            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(app.icon);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(128, 128);
            iconParams.setMargins(0, 0, 32, 0);
            iconView.setLayoutParams(iconParams);

            // App name
            TextView nameView = new TextView(context);
            nameView.setText(app.name);
            nameView.setTextSize(24);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            itemLayout.addView(iconView);
            itemLayout.addView(nameView);

            // Use weak reference for click handlers
            final WeakReference<AppsFragment> fragmentRef = new WeakReference<>(this);

            itemLayout.setOnClickListener(v -> {
                AppsFragment fragment = fragmentRef.get();
                if (fragment != null && fragment.isAdded()) {
                    // Simple scale animation
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
                    fragment.showAppOptionsDialog(app);
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

    private void animateViewsIn(List<View> views) {
        if (!isAdded() || isDetached() || isAnimating || !showPinned) return;

        isAnimating = true;

        for (int i = 0; i < views.size(); i++) {
            final View view = views.get(i);
            final int delay = i * STAGGER_DELAY;

            animationHandler.postDelayed(() -> {
                if (!isAdded() || isDetached() || view.getParent() == null) {
                    // If fragment is gone, ensure view is visible
                    view.setAlpha(1f);
                    view.setTranslationX(0f);
                    view.setRotationY(0f);
                    return;
                }

                try {
                    ObjectAnimator translateX = ObjectAnimator.ofFloat(view, "translationX", 300f, 0f);
                    ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
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
                            // Ensure final state
                            view.setAlpha(1f);
                            view.setTranslationX(0f);
                            view.setRotationY(0f);
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            view.setAlpha(1f);
                            view.setTranslationX(0f);
                            view.setRotationY(0f);
                        }
                    });

                    runningAnimations.add(animatorSet);
                    animatorSet.start();
                } catch (Exception e) {
                    e.printStackTrace();
                    view.setAlpha(1f);
                    view.setTranslationX(0f);
                    view.setRotationY(0f);
                    isAnimating = false;
                }
            }, delay);
        }

        // Failsafe: ensure animation flag is cleared after max time
        animationHandler.postDelayed(() -> {
            if (isAnimating && runningAnimations.isEmpty()) {
                isAnimating = false;
            }
        }, (views.size() * STAGGER_DELAY) + ANIMATION_DURATION + 100);
    }

    private void showAlphabetGrid() {
        Context context = getContext();
        if (context == null || rootLayout == null) return;

        // Create overlay
        alphabetOverlay = new FrameLayout(context);
        alphabetOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        alphabetOverlay.setBackgroundColor(Color.parseColor("#E6000000"));
        alphabetOverlay.setClickable(true);

        // Grid container
        LinearLayout gridContainer = new LinearLayout(context);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        containerParams.gravity = Gravity.CENTER;
        gridContainer.setLayoutParams(containerParams);
        gridContainer.setPadding(48, 48, 48, 48);

        // Create grid
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(5);
        grid.setRowCount(6);

        String[] letters = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
                "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
                "U", "V", "W", "X", "Y", "Z", "#"};

        for (String letter : letters) {
            TextView letterTile = createLetterTile(letter);
            grid.addView(letterTile);
        }

        gridContainer.addView(grid);
        alphabetOverlay.addView(gridContainer);

        // Dismiss on outside click
        alphabetOverlay.setOnClickListener(v -> hideAlphabetGrid());

        // Add with fade animation
        alphabetOverlay.setAlpha(0f);
        rootLayout.addView(alphabetOverlay);
        alphabetOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .start();
    }

    private TextView createLetterTile(String letter) {
        Context context = requireContext();
        TextView letterTile = new TextView(context);
        letterTile.setText(letter);
        letterTile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        letterTile.setTextColor(Color.WHITE);
        letterTile.setGravity(Gravity.CENTER);
        letterTile.setTypeface(null, Typeface.BOLD);

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 120;
        params.height = 120;
        params.setMargins(8, 8, 8, 8);
        letterTile.setLayoutParams(params);

        boolean hasApps = letterPositions.containsKey(letter);
        if (hasApps) {
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(getAccentColor());
            background.setCornerRadius(8);
            letterTile.setBackground(background);

            letterTile.setClickable(true);
            letterTile.setFocusable(true);

            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
            letterTile.setForeground(ContextCompat.getDrawable(context, outValue.resourceId));

            letterTile.setOnClickListener(v -> {
                jumpToLetter(letter);
                hideAlphabetGrid();
            });
        } else {
            letterTile.setBackgroundColor(Color.parseColor("#33FFFFFF"));
            letterTile.setAlpha(0.3f);
        }

        return letterTile;
    }

    private void hideAlphabetGrid() {
        if (alphabetOverlay != null) {
            alphabetOverlay.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (rootLayout != null && alphabetOverlay != null) {
                            rootLayout.removeView(alphabetOverlay);
                            alphabetOverlay = null;
                        }
                    })
                    .start();
        }
    }

    private void jumpToLetter(String letter) {
        Integer position = letterPositions.get(letter);
        if (position != null && scrollView != null && appsContainer != null &&
                position < appsContainer.getChildCount()) {
            View targetView = appsContainer.getChildAt(position);
            if (targetView != null) {
                scrollView.smoothScrollTo(0, targetView.getTop());
            }
        }
    }

    private int getAccentColor() {
        Context context = getContext();
        if (context == null) return Color.BLUE;

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true);
        return typedValue.data;
    }

    private void showAppOptionsDialog(MainActivity.AppInfo app) {
        Context context = getContext();
        if (context == null || !isAdded() || isDetached()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(app.name);

            String pinAction = app.isPinned ? "Remove from Pinned" : "Add to Pinned";
            String[] menuItems = {pinAction, "Uninstall"};

            builder.setItems(menuItems, (dialog, which) -> {
                if (isAdded() && !isDetached()) {
                    switch (which) {
                        case 0:
                            togglePin(app);
                            break;
                        case 1:
                            confirmUninstall(app);
                            break;
                    }
                }
            });

            builder.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void confirmUninstall(MainActivity.AppInfo app) {
        Context context = getContext();
        if (context == null || !isAdded() || isDetached()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Uninstall " + app.name + "?");
            builder.setMessage("This will remove the app and all its data.");

            builder.setPositiveButton("Uninstall", (dialog, which) -> {
                Toast.makeText(context, "Uninstalling " + app.name + "...", Toast.LENGTH_SHORT).show();
                uninstallApp(app);
            });

            builder.setNegativeButton("Cancel", null);
            builder.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uninstallApp(MainActivity.AppInfo app) {
        MainActivity activity = (MainActivity) getActivity();
        if (activity != null && !activity.isFinishing()) {
            activity.launchUninstall(app.packageName);
        }
    }

    private void togglePin(MainActivity.AppInfo app) {
        try {
            app.isPinned = !app.isPinned;

            MainActivity activity = (MainActivity) getActivity();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.savePinnedApps();

                String message = app.isPinned ? "Added to Pinned" : "Removed from Pinned";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

                viewModel.updateAppPinStatus(app.packageName, app.isPinned);
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
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
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