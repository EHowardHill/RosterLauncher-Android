// LauncherViewModel.java

package com.cinemint.rosterlauncher;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LauncherViewModel extends ViewModel {
    private final MutableLiveData<List<MainActivity.AppInfo>> allApps = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");
    private boolean initialized = false;
    private final Object lock = new Object();

    public LiveData<List<MainActivity.AppInfo>> getAllApps() {
        return allApps;
    }

    public void setAllApps(List<MainActivity.AppInfo> apps) {
        synchronized (lock) {
            if (apps != null) {
                // Create a defensive copy
                List<MainActivity.AppInfo> safeCopy = Collections.synchronizedList(new ArrayList<>(apps));
                allApps.postValue(safeCopy);
                initialized = true;
            }
        }
    }

    public LiveData<String> getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String query) {
        // Ensure we never set null
        String safeQuery = query != null ? query : "";
        searchQuery.postValue(safeQuery);
    }

    public boolean isInitialized() {
        synchronized (lock) {
            return initialized;
        }
    }

    public void notifyDataChanged() {
        synchronized (lock) {
            List<MainActivity.AppInfo> currentApps = allApps.getValue();
            if (currentApps != null) {
                // Trigger observers by setting a new list instance
                allApps.postValue(new ArrayList<>(currentApps));
            }
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // Clean up resources if needed
    }
}