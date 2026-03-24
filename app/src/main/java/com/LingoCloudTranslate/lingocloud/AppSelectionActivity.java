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

    /**
     * Initializes the activity UI, restores the persisted app whitelist, and starts loading installed apps.
     *
     * Sets the activity layout, configures the toolbar (title and up navigation), initializes the RecyclerView
     * and ProgressBar, loads the saved package-name set from the "settings" SharedPreferences into
     * `selectedApps`, and invokes `loadApps()` to populate the list.
     */
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

    /**
     * Load installed applications, build AppItem entries (using current selection state), sort them by name, and bind the list to the RecyclerView.
     *
     * Queries PackageManager on a background thread to obtain installed applications, creates an AppItem for each app with its label, package name, icon, and initial selection determined from `selectedApps`, sorts the resulting list case-insensitively by app name, then on the UI thread hides the progress bar and sets the adapter on the RecyclerView to display the apps.
     */
    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                installedApps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of((long) PackageManager.GET_META_DATA));
            } else {
                installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            }
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

    /**
     * Update the given app item's selection state and persist the updated whitelist.
     *
     * @param appItem  the app entry whose selection was changed
     * @param isChecked `true` to include the app's package name in the persisted whitelist, `false` to remove it
     */
    private void onAppToggled(AppItem appItem, boolean isChecked) {
        appItem.isSelected = isChecked;
        if (isChecked) {
            selectedApps.add(appItem.packageName);
        } else {
            selectedApps.remove(appItem.packageName);
        }

        prefs.edit().putStringSet("app_whitelist", selectedApps).apply();
    }

    /**
     * Handle toolbar menu item selection and finish the activity when the home/up button is pressed.
     *
     * @param item the selected menu item
     * @return `true` if the selection was handled here (home pressed), `false` otherwise
     */
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

        /**
         * Creates an AppItem representing an installed application with its display name, package name, icon, and selection state.
         *
         * @param name the app's display label
         * @param packageName the app's package identifier
         * @param icon the app's icon drawable
         * @param isSelected true if the app is initially selected, false otherwise
         */
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
            /**
 * Update an app's selection state and persist the change to the activity's whitelist.
 *
 * Updates the given AppItem's `isSelected` flag, adds or removes its package name from
 * the in-memory selection set, and writes the updated set to SharedPreferences.
 *
 * @param app the app item whose selection changed
 * @param isChecked `true` if the app is now selected, `false` if it was deselected
 */
void onAppToggled(AppItem app, boolean isChecked);
        }

        /**
         * Creates an adapter that binds a list of apps to list rows and notifies when an app's selection changes.
         *
         * @param apps     the list of AppItem objects to display
         * @param listener callback invoked when a row's selection state changes
         */
        AppListAdapter(List<AppItem> apps, OnAppToggledListener listener) {
            this.apps = apps;
            this.listener = listener;
        }

        /**
         * Creates a new ViewHolder for a single app list row.
         *
         * @return a ViewHolder whose item view is the inflated R.layout.item_app_list
         */
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_list, parent, false);
            return new ViewHolder(view);
        }

        /**
         * Binds the AppItem at the given adapter position to the provided ViewHolder and wires the row click
         * to toggle the app's selection state.
         *
         * <p>Updates the holder's name, package, icon, and checkbox to reflect the AppItem, and registers a
         * click listener that flips the selection and notifies the adapter's OnAppToggledListener.</p>
         *
         * @param holder   the ViewHolder whose views will be updated
         * @param position the adapter position of the AppItem to bind
         */
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

        /**
         * Number of apps currently held by the adapter.
         *
         * @return the number of apps in the adapter
         */
        @Override
        public int getItemCount() {
            return apps.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView appIcon;
            TextView appName;
            TextView appPackage;
            CheckBox checkBox;

            /**
             * Initializes the ViewHolder and locates the child views for a single app list row.
             *
             * @param itemView the root view of the item layout used to find the icon, name, package, and checkbox views
             */
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
