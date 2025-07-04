// LauncherViewModel.java

package com.cinemint.rosterlauncher;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LauncherViewModel extends ViewModel {
    private final MutableLiveData<List<MainActivity.AppInfo>> allApps = new MutableLiveData<>();
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");
    private volatile boolean initialized = false;

    // Thread-safe app list
    private final CopyOnWriteArrayList<MainActivity.AppInfo> appList = new CopyOnWriteArrayList<>();

    public LauncherViewModel() {
        // Initialize with empty list
        allApps.setValue(new ArrayList<>());
    }

    public LiveData<List<MainActivity.AppInfo>> getAllApps() {
        return allApps;
    }

    public synchronized void setAllApps(List<MainActivity.AppInfo> apps) {
        if (apps == null) {
            return;
        }

        // Clear and update thread-safe list
        appList.clear();
        appList.addAll(apps);

        // Post to LiveData
        allApps.postValue(new ArrayList<>(appList));
        initialized = true;
    }

    public LiveData<String> getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String query) {
        searchQuery.postValue(query != null ? query : "");
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /**
     * Update a single app's pin status
     */
    public synchronized void updateAppPinStatus(String packageName, boolean isPinned) {
        boolean updated = false;

        for (MainActivity.AppInfo app : appList) {
            if (app.packageName.equals(packageName)) {
                app.isPinned = isPinned;
                updated = true;
                break;
            }
        }

        if (updated) {
            // Trigger observers with new list
            allApps.postValue(new ArrayList<>(appList));
        }
    }

    /**
     * Remove an app from the list (e.g., when uninstalled)
     */
    public synchronized void removeApp(String packageName) {
        boolean removed = appList.removeIf(app -> app.packageName.equals(packageName));

        if (removed) {
            // Trigger observers with new list
            allApps.postValue(new ArrayList<>(appList));
        }
    }

    /**
     * Add or update an app in the list
     */
    public synchronized void addOrUpdateApp(MainActivity.AppInfo newApp) {
        if (newApp == null || newApp.packageName == null) {
            return;
        }

        // Remove existing if present
        appList.removeIf(app -> app.packageName.equals(newApp.packageName));

        // Add new/updated app
        appList.add(newApp);

        // Sort alphabetically
        List<MainActivity.AppInfo> sortedList = new ArrayList<>(appList);
        Collections.sort(sortedList, (a, b) -> a.name.compareToIgnoreCase(b.name));

        // Update list
        appList.clear();
        appList.addAll(sortedList);

        // Trigger observers
        allApps.postValue(new ArrayList<>(appList));
    }

    /**
     * Force refresh of observers without changing data
     */
    public void notifyDataChanged() {
        if (!appList.isEmpty()) {
            allApps.postValue(new ArrayList<>(appList));
        }
    }

    /**
     * Get a defensive copy of the current app list
     */
    public List<MainActivity.AppInfo> getCurrentApps() {
        return new ArrayList<>(appList);
    }

    /**
     * Clear all data
     */
    public synchronized void clear() {
        appList.clear();
        allApps.postValue(new ArrayList<>());
        searchQuery.postValue("");
        initialized = false;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        clear();
    }
}