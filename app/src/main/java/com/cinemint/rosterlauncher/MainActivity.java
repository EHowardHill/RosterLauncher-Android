// MainActivity.java

package com.cinemint.rosterlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static class AppInfo {
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

    private static List<AppInfo> allApps = new ArrayList<>();
    private static SharedPreferences prefs;
    private static final String PREF_NAME = "RosterLauncherPrefs";
    private static final String PINNED_APPS_KEY = "pinned_apps";
    private static String searchQuery = "";
    private static LauncherPagerAdapter pagerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        com.cinemint.rosterlauncher.databinding.ActivityMainBinding binding =
                com.cinemint.rosterlauncher.databinding.ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize SharedPreferences
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // Setup tabs and ViewPager2
        TabLayout tabLayout = findViewById(R.id.tabs);
        ViewPager2 viewPager = findViewById(R.id.viewPager);
        FloatingActionButton searchBtn = findViewById(R.id.searchBtn);

        // Get all installed apps
        allApps = getInstalledApps();

        // Load pinned apps from preferences
        loadPinnedApps();

        // Sort apps alphabetically
        allApps.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        // Setup ViewPager with fragments
        pagerAdapter = new LauncherPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        // Connect TabLayout with ViewPager2
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Pinned");
                    break;
                case 1:
                    tab.setText("All");
                    break;
            }
        }).attach();

        // Setup search button
        searchBtn.setOnClickListener(v -> showSearchDialog());
    }

    private void loadPinnedApps() {
        Set<String> pinnedPackages = prefs.getStringSet(PINNED_APPS_KEY, new HashSet<>());
        for (AppInfo app : allApps) {
            app.isPinned = pinnedPackages.contains(app.packageName);
        }
    }

    private static void savePinnedApps() {
        Set<String> pinnedPackages = new HashSet<>();
        for (AppInfo app : allApps) {
            if (app.isPinned) {
                pinnedPackages.add(app.packageName);
            }
        }
        prefs.edit().putStringSet(PINNED_APPS_KEY, pinnedPackages).apply();
    }

    private void showSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        // No title for cleaner look

        // Create search input with padding
        final EditText input = new EditText(this);
        input.setHint("Search apps...");
        input.setText(searchQuery);
        input.setPadding(48, 32, 48, 32);
        input.setTextSize(18);
        input.setBackgroundResource(0); // Remove underline

        builder.setView(input);

        // Add text change listener for real-time search
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString();
                refreshFragments();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        builder.setPositiveButton("Clear", (dialog, which) -> {
            searchQuery = "";
            refreshFragments();
        });

        builder.setNegativeButton("Done", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();

        // Force keyboard to show immediately
        input.post(() -> {
            input.requestFocus();
            input.setSelection(input.getText().length());
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void refreshFragments() {
        if (pagerAdapter != null) {
            pagerAdapter.notifyDataSetChanged();
        }
    }

    private void notifyAllFragments() {
        // Force immediate update of all fragments
        if (pagerAdapter != null) {
            pagerAdapter.notifyDataSetChanged();
            // Also notify any currently visible fragments to refresh their views
            for (Fragment fragment : getSupportFragmentManager().getFragments()) {
                if (fragment instanceof AppsFragment) {
                    ((AppsFragment) fragment).refreshAppList();
                }
            }
        }
    }

    private List<AppInfo> getInstalledApps() {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();

        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent,
                PackageManager.MATCH_ALL);

        for (ResolveInfo resolveInfo : resolveInfos) {
            String packageName = resolveInfo.activityInfo.packageName;
            String appName = resolveInfo.loadLabel(pm).toString();
            Drawable appIcon = resolveInfo.loadIcon(pm);

            apps.add(new AppInfo(appName, packageName, appIcon));
        }

        return apps;
    }

    // Fragment for displaying apps
    public static class AppsFragment extends Fragment {
        private static final String ARG_SHOW_PINNED = "show_pinned";
        private boolean showPinned;
        private LinearLayout appsContainer;

        public static AppsFragment newInstance(boolean showPinned) {
            AppsFragment fragment = new AppsFragment();
            Bundle args = new Bundle();
            args.putBoolean(ARG_SHOW_PINNED, showPinned);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_apps, container, false);

            appsContainer = view.findViewById(R.id.appsContainer);
            showPinned = getArguments() != null && getArguments().getBoolean(ARG_SHOW_PINNED);

            refreshAppList();

            return view;
        }

        public void refreshAppList() {
            if (appsContainer == null) return;

            appsContainer.removeAllViews();
            List<AppInfo> appsToShow = getFilteredApps();

            // Create views for each app
            for (AppInfo app : appsToShow) {
                View appView = createAppView(app);
                appsContainer.addView(appView);
            }
        }

        private List<AppInfo> getFilteredApps() {
            List<AppInfo> filteredApps = new ArrayList<>();

            for (AppInfo app : allApps) {
                // Filter by pinned status
                if (showPinned && !app.isPinned) {
                    continue;
                }

                // Filter by search query
                if (!searchQuery.isEmpty() &&
                        !app.name.toLowerCase().contains(searchQuery.toLowerCase())) {
                    continue;
                }

                filteredApps.add(app);
            }

            return filteredApps;
        }

        @NonNull
        private View createAppView(AppInfo app) {
            // Create a horizontal LinearLayout for the list item
            LinearLayout itemLayout = new LinearLayout(getContext());
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            itemLayout.setPadding(32, 24, 32, 24); // Increased padding for easier tapping
            itemLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
            itemLayout.setClickable(true);
            itemLayout.setFocusable(true);

            // Use system's selectableItemBackground for proper theming
            android.util.TypedValue outValue = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            itemLayout.setBackgroundResource(outValue.resourceId);

            // App icon - larger size
            ImageView iconView = new ImageView(getContext());
            iconView.setImageDrawable(app.icon);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(96, 96); // Increased from 64x64
            iconParams.setMargins(0, 0, 24, 0); // More spacing
            iconView.setLayoutParams(iconParams);

            // App name - larger text
            TextView nameView = new TextView(getContext());
            nameView.setText(app.name);
            nameView.setTextSize(18); // Increased from 16
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            // Add views to layout
            itemLayout.addView(iconView);
            itemLayout.addView(nameView);

            // Click listener to launch app
            itemLayout.setOnClickListener(v -> launchApp(app.packageName));

            // Long click listener to show pin/unpin dialog
            itemLayout.setOnLongClickListener(v -> {
                showPinDialog(app);
                return true;
            });

            return itemLayout;
        }

        private void showPinDialog(AppInfo app) {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setTitle(app.name);

            String action = app.isPinned ? "Remove from Pinned" : "Add to Pinned";
            builder.setItems(new String[]{action}, (dialog, which) -> {
                togglePin(app);
            });

            builder.show();
        }

        private void togglePin(AppInfo app) {
            app.isPinned = !app.isPinned;
            savePinnedApps();

            String message = app.isPinned ? "Added to Pinned" : "Removed from Pinned";
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

            // Immediately refresh all fragments
            if (getActivity() instanceof MainActivity) {
                MainActivity activity = (MainActivity) getActivity();
                activity.notifyAllFragments();
            }
        }

        private void launchApp(String packageName) {
            try {
                PackageManager pm = requireContext().getPackageManager();
                Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                } else {
                    Toast.makeText(getContext(), "Cannot launch this app", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(getContext(), "Error launching app: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            switch (position) {
                case 0:
                    return AppsFragment.newInstance(true); // Pinned apps
                case 1:
                    return AppsFragment.newInstance(false); // All apps
                default:
                    return AppsFragment.newInstance(false);
            }
        }

        @Override
        public int getItemCount() {
            return 2; // Pinned and All tabs
        }

        @Override
        public long getItemId(int position) {
            // Return unique IDs to force recreation when data changes
            return position + searchQuery.hashCode();
        }

        @Override
        public boolean containsItem(long itemId) {
            // This ensures fragments are recreated when needed
            return false;
        }
    }
}