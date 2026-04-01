package com.LingoCloudTranslate.lingocloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectionActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private AppListAdapter adapter;
    private SharedPreferences prefs;
    private Set<String> selectedApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_selection);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.app_whitelist_title);
        }

        recyclerView = findViewById(R.id.recycler_view);
        progressBar = findViewById(R.id.progress_bar);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        selectedApps = new HashSet<>(prefs.getStringSet("app_whitelist", new HashSet<>()));

        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppItem> appItems = new ArrayList<>();

            for (ApplicationInfo appInfo : installedApps) {
                // Include all apps including system apps, as requested by the user
                // who wants it to "include all apps and or apps selected thru the lsposed xposed"
                appItems.add(new AppItem(
                    appInfo.loadLabel(pm).toString(),
                    appInfo.packageName,
                    appInfo.loadIcon(pm),
                    selectedApps.contains(appInfo.packageName)
                ));
            }

            Collections.sort(appItems, (a, b) -> a.name.compareToIgnoreCase(b.name));

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                adapter = new AppListAdapter(appItems, this::onAppToggled);
                recyclerView.setAdapter(adapter);
            });
        }).start();
    }

    private void onAppToggled(AppItem appItem, boolean isChecked) {
        appItem.isSelected = isChecked;
        if (isChecked) {
            selectedApps.add(appItem.packageName);
        } else {
            selectedApps.remove(appItem.packageName);
        }

        if (prefs.edit().putStringSet("app_whitelist", selectedApps).commit()) {
            setPrefsWorldReadable();
        }
    }

    @android.annotation.SuppressLint("SetWorldReadable")
    private void setPrefsWorldReadable() {
        try {
            java.io.File prefsDir = new java.io.File(getApplicationInfo().dataDir, "shared_prefs");
            java.io.File prefsFile = new java.io.File(prefsDir, "settings.xml");
            if (prefsDir.exists()) {
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
                prefsFile.setExecutable(true, false);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static class AppItem {
        String name;
        String packageName;
        android.graphics.drawable.Drawable icon;
        boolean isSelected;

        AppItem(String name, String packageName, android.graphics.drawable.Drawable icon, boolean isSelected) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isSelected = isSelected;
        }
    }

    private static class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
        private final List<AppItem> apps;
        private final OnAppToggledListener listener;

        interface OnAppToggledListener {
            void onAppToggled(AppItem app, boolean isChecked);
        }

        AppListAdapter(List<AppItem> apps, OnAppToggledListener listener) {
            this.apps = apps;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_list, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem app = apps.get(position);
            holder.appName.setText(app.name);
            holder.appPackage.setText(app.packageName);
            holder.appIcon.setImageDrawable(app.icon);
            holder.checkBox.setChecked(app.isSelected);

            holder.itemView.setOnClickListener(v -> {
                boolean newState = !app.isSelected;
                holder.checkBox.setChecked(newState);
                listener.onAppToggled(app, newState);
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView appIcon;
            TextView appName;
            TextView appPackage;
            CheckBox checkBox;

            ViewHolder(View itemView) {
                super(itemView);
                appIcon = itemView.findViewById(R.id.app_icon);
                appName = itemView.findViewById(R.id.app_name);
                appPackage = itemView.findViewById(R.id.app_package);
                checkBox = itemView.findViewById(R.id.app_checkbox);
            }
        }
    }
}
