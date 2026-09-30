package com.evsuite.abrp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.evsuite.hardware.EVHardware;
import com.evsuite.hardware.FirmwareInfo;
import com.evsuite.hardware.telemetry.EnergySnapshot;
import com.evsuite.hardware.telemetry.EnergyTelemetryReader;


public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST = 100;
    /** Shared with {@link AbrpUploadService}, which reads it on every tick. */
    static final String WIFI_AUTO_KEY = "wifi_auto_connect";
    private static final String REPOSITORY_URL = "https://github.com/fatihdonmezdev/MG4ABRP";

    /** Navigation destinations in page order. Parallel to {@link #panes}. */
    private static final int[] TAB_IDS =
            { R.id.tabVehicle, R.id.tabConsumption, R.id.tabCharging, R.id.tabAbrp, R.id.tabService, R.id.tabWifi,
              R.id.tabLog };

    private static final int[] PAGE_TITLES = {
            R.string.design_overview_title, R.string.consumption_title, R.string.charging_title, R.string.design_abrp_title,
            R.string.design_service_title, R.string.design_wifi_title, R.string.design_log_title
    };
    private static final int[] PAGE_SUBTITLES = {
            R.string.design_overview_subtitle, R.string.consumption_subtitle, R.string.charging_subtitle, R.string.design_abrp_subtitle,
            R.string.design_service_subtitle, R.string.design_wifi_subtitle, R.string.design_log_subtitle
    };

    // Status colours come from the palette, not from the Material swatches: #4CAF50 and
    // #F44336 sit around 4:1 on this background, which disappears behind a sunlit
    // reflection. The palette entries are the lightened variants (7:1 and above).
    private static final int COLOR_OK      = 0xFFA4DCC1;
    private static final int COLOR_ERROR   = 0xFFFFB4AB;
    private static final int COLOR_PENDING = 0xFFBDC4C6;

    private TextInputLayout   apiKeyLayout;
    private TextInputEditText apiKeyInput;
    private TextInputLayout   tokenLayout;
    private TextInputEditText tokenInput;
    private SwitchMaterial    serviceSwitch;
    private SwitchMaterial    autostartSwitch;
    private TextView          statusText;
    private Button            testButton;
    private View              connectionStatusRow;
    private View              connectionIndicator;
    private TextView          connectionStatusText;
    private Spinner intervalSpinner;
    private SwitchMaterial boostSwitch;
    private TextInputEditText lowSocInput;
    private TextInputLayout lowSocLayout;
    private TextView callLogText;
    private View abrpPane;
    private View servicePane;
    private View logPane;
    private View vehiclePane;
    private View consumptionPane;
    private View chargingPane;
    private View wifiPane;

    /** The five pages in navigation order. Parallel to {@link #TAB_IDS}. */
    private View[] panes;

    /** Wi-Fi page. The helper is the same one the upload service uses on its own tick. */
    private WifiAutoConnect wifi;
    private TextView wifiSsid, wifiState, wifiSavedList;

    /**
     * Value cells of the Vehicle page, looked up once and refreshed on the UI tick.
     *
     * Read straight from {@link EnergyTelemetryReader} rather than from the uploader: the
     * page shows what the car reports, whether or not the upload service is running, and a
     * signal the vehicle does not publish must show a dash rather than a zero.
     */
    private EnergyTelemetryReader vehicleReader;
    private TextView vehSoc, vehRange, vehPower, vehSpeed, vehOdometer,
            vehCharging, vehHvac, vehSoe,
            vehTireFl, vehTireFr, vehTireRl, vehTireRr;
    private ConsumptionTracker consumptionTracker;
    private Spinner consumptionPeriod;
    private TextView consumptionDistance, consumptionEnergy, consumptionAverage,
            consumptionSpeed, consumptionTime, consumptionSoc;
    private Button consumptionReset;
    private TextView chargeStatus, chargeSoc, chargeVoltage, chargeCurrent, chargePower,
            chargeEnergy, chargeDuration;
    private ChargingGraphView chargeGraph;
    private long chargeStartMs, lastChargeMs;
    private double chargedKwh;

    /**
     * Inflates a page with no parent, then gives it the MATCH_PARENT/MATCH_PARENT layout
     * params ViewPager2 requires of its items — inflating detached leaves them null, and
     * the pager throws rather than guessing.
     */
    private View inflatePane(int layoutRes) {
        View pane = getLayoutInflater().inflate(layoutRes, null, false);
        pane.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        return pane;
    }

    /**
     * Wires the navigation to the pager: every tab is a page, and every page is a tab.
     *
     * Each page has its own view type, so the pager asks for it once and keeps it: these
     * views are the activity's own, held in {@link #panes}, not rows to be recycled.
     */
    private void setUpPager() {
        ViewPager2 pager = findViewById(R.id.content);
        pager.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemCount() { return panes.length; }
            @Override public int getItemViewType(int position) { return position; }

            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent,
                                                              int viewType) {
                return new RecyclerView.ViewHolder(panes[viewType]) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                // Nothing to bind: the pages are built once in onCreate and keep their own
                // state, exactly as they did when they were siblings in the layout.
            }
        });
        // Keep all pages alive: the service switch, log and vehicle readings are refreshed
        // by the tick even when their page is off screen.
        pager.setOffscreenPageLimit(panes.length - 1);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { markCurrentPage(position); }
        });

        for (int i = 0; i < TAB_IDS.length; i++) {
            final int index = i;
            // Animated, so the button does the same thing the swipe does: the direction of
            // travel is what tells the driver where they are in the row.
            findViewById(TAB_IDS[i]).setOnClickListener(v -> pager.setCurrentItem(index, false));
        }
        markCurrentPage(pager.getCurrentItem());
    }

    /**
     * Marks the destination and labels the page on screen.
     *
     * Only isSelected is set: fill, text, icon and stroke come from the
     * res/color/nav_tab_*.xml selectors applied by the Widget.EV.NavTab style. isSelected
     * also serves TalkBack, which announces the current destination.
     */
    private void markCurrentPage(int position) {
        for (int i = 0; i < TAB_IDS.length; i++) {
            findViewById(TAB_IDS[i]).setSelected(i == position);
        }
        ((TextView) findViewById(R.id.header_title)).setText(PAGE_TITLES[position]);
        ((TextView) findViewById(R.id.header_subtitle)).setText(PAGE_SUBTITLES[position]);
    }

    /** Refreshes state + call log while the screen is visible. */
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable uiRefresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            refreshCallLog();
            refreshVehicle();
            refreshConsumption();
            refreshWifi();
            uiHandler.postDelayed(this, 2_000L);
        }
    };

    private SharedPreferences prefs;
    /** Credentials only — encrypted at rest, see {@link SecurePrefs}. */
    private SharedPreferences securePrefs;

    /**
     * Picks an ABRP config text file. Only reachable on devices that ship a document picker —
     * the MG4 head unit does not, which is why {@link #startConfigImport()} scans the
     * app-specific folders first and only falls back to this.
     */
    private final ActivityResultLauncher<String[]> configPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importConfig(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ((TextView) findViewById(R.id.version_badge)).setText("v" + BuildConfig.VERSION_NAME);

        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);
        securePrefs = SecurePrefs.get(this);

        // The pages are inflated here, once, and handed to the pager as fixed,
        // non-recycled items. That is what lets every widget below be looked up now and
        // held for the life of the activity, the way it was when the panes were siblings
        // in activity_main.xml — a recycled page would invalidate these references as
        // soon as the user swiped away from it.
        abrpPane    = inflatePane(R.layout.pane_abrp);
        servicePane = inflatePane(R.layout.pane_service);
        vehiclePane   = inflatePane(R.layout.pane_vehicle);
        consumptionPane = inflatePane(R.layout.pane_consumption);
        chargingPane = inflatePane(R.layout.pane_charging);
        wifiPane    = inflatePane(R.layout.pane_wifi);
        logPane     = inflatePane(R.layout.pane_log);
        panes = new View[] { vehiclePane, consumptionPane, chargingPane, abrpPane, servicePane, wifiPane, logPane };

        bindVehiclePane();
        bindConsumptionPane();
        bindChargingPane();
        bindWifiPane();

        apiKeyLayout        = abrpPane.findViewById(R.id.api_key_layout);
        apiKeyInput         = abrpPane.findViewById(R.id.api_key_input);
        tokenLayout         = abrpPane.findViewById(R.id.token_layout);
        tokenInput          = abrpPane.findViewById(R.id.token_input);
        serviceSwitch       = servicePane.findViewById(R.id.service_switch);
        autostartSwitch     = servicePane.findViewById(R.id.autostart_switch);
        statusText          = servicePane.findViewById(R.id.status_text);
        testButton          = abrpPane.findViewById(R.id.test_button);
        connectionStatusRow = abrpPane.findViewById(R.id.connection_status_row);
        connectionIndicator = abrpPane.findViewById(R.id.connection_indicator);
        connectionStatusText = abrpPane.findViewById(R.id.connection_status_text);
        intervalSpinner = servicePane.findViewById(R.id.interval_spinner);
        boostSwitch = servicePane.findViewById(R.id.boost_switch);
        lowSocInput = servicePane.findViewById(R.id.low_soc_input);
        lowSocLayout = servicePane.findViewById(R.id.low_soc_layout);
        callLogText = logPane.findViewById(R.id.call_log_text);

        setUpPager();
        findViewById(R.id.about_button).setOnClickListener(v -> showAbout());
        findViewById(R.id.update_button).setOnClickListener(v -> checkForUpdate(v));

        bindCadenceControls();

        // Unstable builds check for a newer pre-release; the stable flavor's UpdateHook is
        // a no-op and does not even contain the updater.
        UpdateHook.checkInBackground(this);

        apiKeyInput.setText(savedApiKeyOrDefault());
        tokenInput.setText(securePrefs.getString(SecurePrefs.KEY_TOKEN, ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));

        autostartSwitch.setChecked(
                prefs.getBoolean(UploadSettings.KEY_AUTOSTART, UploadSettings.DEFAULT_AUTOSTART));
        autostartSwitch.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(UploadSettings.KEY_AUTOSTART, checked).apply());

        // If the user wants the service running, kick it on every activity launch.
        // startForegroundService is idempotent — if the service is already up this
        // is a no-op aside from delivering a new intent. We don't trust
        // service_running as a sole indicator because it can be stale-true if a
        // previous process was force-killed (e.g. by `adb install -r`) without
        // onDestroy running.
        boolean enabled = prefs.getBoolean("service_enabled", false);
        boolean haveCreds = !securePrefs.getString(SecurePrefs.KEY_TOKEN, "").trim().isEmpty()
                         && !securePrefs.getString(SecurePrefs.KEY_API_KEY, "").trim().isEmpty();
        if (enabled && haveCreds) {
            // Asked for first, and deliberately: the service declares the `location`
            // foreground type, and since API 34 starting it without the permission behind
            // that type is a SecurityException in its own onCreate. Started this way round,
            // the very first launch after an install could never bring the uploader up.
            requestLocationPermissionIfNeeded();
            startForegroundService(new Intent(this, AbrpUploadService.class));
        }

        abrpPane.findViewById(R.id.save_button).setOnClickListener(v -> saveCredentials());
        testButton.setOnClickListener(v -> testConnection());
        abrpPane.findViewById(R.id.import_button).setOnClickListener(v -> startConfigImport());
        wireProbeIfDebug();

        serviceSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                String apiKey = textOf(apiKeyInput);
                String token  = textOf(tokenInput);
                if (apiKey.isEmpty() || token.isEmpty()) {
                    serviceSwitch.setChecked(false);
                    if (apiKey.isEmpty()) apiKeyLayout.setError(getString(R.string.api_key_required));
                    if (token.isEmpty())  tokenLayout.setError(getString(R.string.token_required));
                    return;
                }
                apiKeyLayout.setError(null);
                tokenLayout.setError(null);
                prefs.edit().putBoolean("service_enabled", true).apply();
                startForegroundService(new Intent(this, AbrpUploadService.class));
                requestLocationPermissionIfNeeded();
            } else {
                prefs.edit().putBoolean("service_enabled", false).apply();
                stopService(new Intent(this, AbrpUploadService.class));
            }
            refreshStatus();
        });
    }

    private void showAbout() {
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = getString(R.string.about_version_unknown);
        }
        View content = getLayoutInflater().inflate(R.layout.dialog_about, null);
        content.<TextView>findViewById(R.id.about_version)
                .setText(getString(R.string.about_version, version));
        ImageView qr = content.findViewById(R.id.about_qr_code);
        android.graphics.Bitmap bitmap = QrCode.generate(REPOSITORY_URL, 416);
        if (bitmap != null) qr.setImageBitmap(bitmap);
        content.findViewById(R.id.about_repository).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        content.<MaterialButton>findViewById(R.id.about_close).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int width = Math.min((int) (760 * metrics.density),
                    metrics.widthPixels - (int) (32 * metrics.density));
            dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        apiKeyInput.setText(savedApiKeyOrDefault());
        tokenInput.setText(securePrefs.getString(SecurePrefs.KEY_TOKEN, ""));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));
        autostartSwitch.setChecked(
                prefs.getBoolean(UploadSettings.KEY_AUTOSTART, UploadSettings.DEFAULT_AUTOSTART));
        // Poll state and log only while the screen is up; onPause cancels it.
        uiHandler.post(uiRefresh);
    }

    // ---------- Credentials ----------

    /**
     * The SAIC open-source gateway publishes this shared ABRP key for compatible clients.
     * A user-supplied key saved in encrypted preferences always takes precedence.
     */
    private String savedApiKeyOrDefault() {
        String savedApiKey = securePrefs.getString(SecurePrefs.KEY_API_KEY, "");
        return savedApiKey == null || savedApiKey.trim().isEmpty()
                ? getString(R.string.default_abrp_api_key)
                : savedApiKey;
    }

    private void saveCredentials() {
        String apiKey = textOf(apiKeyInput);
        String token  = textOf(tokenInput);
        boolean valid = true;

        if (apiKey.isEmpty()) {
            apiKeyLayout.setError(getString(R.string.api_key_required));
            valid = false;
        } else {
            apiKeyLayout.setError(null);
        }
        if (token.isEmpty()) {
            tokenLayout.setError(getString(R.string.token_required));
            valid = false;
        } else {
            tokenLayout.setError(null);
        }
        if (!valid) return;

        securePrefs.edit()
                .putString(SecurePrefs.KEY_API_KEY, apiKey)
                .putString(SecurePrefs.KEY_TOKEN,   token)
                .apply();

        if (serviceSwitch.isChecked()) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
        }
        refreshStatus();
    }

    // ---------- Config import ----------

    /**
     * The MG4 head unit ships no document picker — SAF answers "FileManagement is no supported
     * on this device" — so the file is found by scanning instead. getExternalFilesDirs() returns
     * this app's own folder on internal storage and on every mounted volume including a USB
     * stick, and those need no storage permission at any API level. The picker stays as the
     * fallback for phones and tablets, where the user may keep the file anywhere.
     */
    private void startConfigImport() {
        java.io.File found = findConfigFile();
        if (found != null) {
            importConfig(found);
            return;
        }
        try {
            // Any MIME: the MG4 Files app tags .txt inconsistently, so filtering by type hides
            // the very file the user is trying to pick. They select it by name instead.
            configPicker.launch(new String[]{"*/*"});
        } catch (android.content.ActivityNotFoundException e) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.import_err_not_found, configDirHint()));
        }
    }

    /**
     * First readable file in an app-specific folder that parses as a config. Names are not
     * filtered: the user copies one file to the folder, whatever they called it.
     */
    private java.io.File findConfigFile() {
        for (java.io.File dir : getExternalFilesDirs(null)) {
            if (dir == null) continue;
            java.io.File[] files = dir.listFiles();
            if (files == null) continue;
            for (java.io.File f : files) {
                if (f.isFile() && f.canRead() && f.length() > 0 && f.length() <= 64 * 1024
                        && !ConfigImport.parse(readText(f)).isEmpty()) {
                    return f;
                }
            }
        }
        return null;
    }

    /** File text, or "" when unreadable — callers treat that as "not a config". */
    private String readText(java.io.File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            return readCapped(in);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Cap the read so a wrong file (a huge binary picked by mistake) can't be slurped whole
     * into memory before we find out it isn't a config.
     */
    private String readCapped(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1 && buf.size() < 64 * 1024) buf.write(chunk, 0, n);
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8.name());
    }

    /** Where the user should drop the file, shown when nothing was found. */
    private String configDirHint() {
        java.io.File[] dirs = getExternalFilesDirs(null);
        return dirs.length > 0 && dirs[0] != null ? dirs[0].getAbsolutePath() : "";
    }

    private void importConfig(java.io.File file) {
        String text = readText(file);
        if (text.isEmpty()) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.import_err_read));
            return;
        }
        applyConfig(text);
    }

    private void importConfig(android.net.Uri uri) {
        String text;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("no stream");
            text = readCapped(in);
        } catch (Exception e) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.import_err_read));
            return;
        }
        applyConfig(text);
    }

    /**
     * Applies whatever the config text sets. Credentials go straight to the encrypted store;
     * cadence keys go to the plain prefs the same way the manual controls write them. Absent
     * keys are left untouched, so a file carrying only the credentials does not wipe a cadence
     * the user already tuned.
     */
    private void applyConfig(String text) {
        ConfigImport config = ConfigImport.parse(text);
        if (config.isEmpty()) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.import_err_empty));
            return;
        }

        if (config.apiKey != null) {
            securePrefs.edit().putString(SecurePrefs.KEY_API_KEY, config.apiKey).apply();
        }
        if (config.token != null) {
            securePrefs.edit().putString(SecurePrefs.KEY_TOKEN, config.token).apply();
        }
        SharedPreferences.Editor edit = prefs.edit();
        if (config.intervalSec != null) {
            edit.putInt(UploadSettings.KEY_INTERVAL_SEC, config.intervalSec);
        }
        if (config.boostLowSoc != null) {
            edit.putBoolean(UploadSettings.KEY_BOOST_LOW_SOC, config.boostLowSoc);
        }
        if (config.lowSocPercent != null) {
            edit.putInt(UploadSettings.KEY_LOW_SOC_PERCENT, config.lowSocPercent);
        }
        edit.apply();

        // Re-read every control from prefs so the screen shows what was imported.
        apiKeyInput.setText(savedApiKeyOrDefault());
        tokenInput.setText(securePrefs.getString(SecurePrefs.KEY_TOKEN, ""));
        apiKeyLayout.setError(null);
        tokenLayout.setError(null);
        bindCadenceControls();
        AbrpUploadService.reloadSettings();

        if (serviceSwitch.isChecked()) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
        }
        setConnectionStatus(COLOR_OK, getString(R.string.import_ok));
        refreshStatus();
    }

    // ---------- Connection test ----------

    private void testConnection() {
        String apiKey = textOf(apiKeyInput);
        String token  = textOf(tokenInput);

        if (apiKey.isEmpty() || token.isEmpty()) {
            if (apiKey.isEmpty()) apiKeyLayout.setError(getString(R.string.api_key_required));
            if (token.isEmpty())  tokenLayout.setError(getString(R.string.token_required));
            return;
        }

        setConnectionStatus(COLOR_PENDING, getString(R.string.conn_testing));
        testButton.setEnabled(false);

        new Thread(() -> {
            String result = pingAbrp(apiKey, token);
            runOnUiThread(() -> {
                testButton.setEnabled(true);
                if (result == null) {
                    setConnectionStatus(COLOR_OK, getString(R.string.conn_ok));
                } else {
                    setConnectionStatus(COLOR_ERROR, result);
                }
            });
        }).start();
    }

    /**
     * Sends a minimal test request to the ABRP API.
     * Returns null on success, or a short error string on failure.
     */
    private String pingAbrp(String apiKey, String token) {
        try {
            // Read-only check. The previous version POSTed {"utc":...,"soc":0} to the LIVE
            // /tlm/send endpoint, i.e. told ABRP the car was at 0% every time the user
            // pressed Test, wrecking the route plan it had computed.
            AbrpApi.Response response = AbrpApi.verifyCredentials(apiKey, token);

            if (response.code == 200) return null;
            if (response.code == 401) return getString(R.string.conn_err_auth);
            return getString(R.string.conn_err_http, response.code);

        } catch (java.net.UnknownHostException e) {
            return getString(R.string.conn_err_no_internet);
        } catch (java.net.SocketTimeoutException e) {
            return getString(R.string.conn_err_timeout);
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop polling off-screen: this activity has no reason to spend cycles then.
        uiHandler.removeCallbacks(uiRefresh);
    }

    // ---------- Cadence configuration ----------

    private void bindCadenceControls() {
        UploadSettings current = UploadSettings.from(prefs);

        String[] labels = new String[UploadSettings.INTERVAL_CHOICES_SEC.length];
        int selected = 0;
        for (int i = 0; i < UploadSettings.INTERVAL_CHOICES_SEC.length; i++) {
            int sec = UploadSettings.INTERVAL_CHOICES_SEC[i];
            labels[i] = sec >= 60
                    ? getResources().getQuantityString(R.plurals.interval_minutes, sec / 60, sec / 60)
                    : getResources().getQuantityString(R.plurals.interval_seconds, sec, sec);
            if (sec == current.intervalSec) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_value, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown);
        intervalSpinner.setAdapter(adapter);
        intervalSpinner.setSelection(selected);
        intervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt(UploadSettings.KEY_INTERVAL_SEC,
                        UploadSettings.INTERVAL_CHOICES_SEC[position]).apply();
                // Takes effect on the next cycle — no service restart needed.
                AbrpUploadService.reloadSettings();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        boostSwitch.setChecked(current.boostLowSoc);
        lowSocLayout.setEnabled(current.boostLowSoc);
        lowSocInput.setEnabled(current.boostLowSoc);
        boostSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(UploadSettings.KEY_BOOST_LOW_SOC, checked).apply();
            lowSocLayout.setEnabled(checked);
            lowSocInput.setEnabled(checked);
            AbrpUploadService.reloadSettings();
        });

        lowSocInput.setText(String.valueOf(current.lowSocPercent));
        lowSocInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveLowSocThreshold();
        });
    }

    /** Clamped to 1-99: 0 would never trigger and 100 would boost permanently. */
    private void saveLowSocThreshold() {
        CharSequence raw = lowSocInput.getText();
        int value;
        try {
            value = Integer.parseInt(raw == null ? "" : raw.toString().trim());
        } catch (NumberFormatException e) {
            value = UploadSettings.DEFAULT_LOW_SOC_PERCENT;
        }
        value = Math.max(1, Math.min(99, value));
        lowSocInput.setText(String.valueOf(value));
        prefs.edit().putInt(UploadSettings.KEY_LOW_SOC_PERCENT, value).apply();
        AbrpUploadService.reloadSettings();
    }

    // ---------- Update check ----------

    /**
     * Checks for a newer build, and installs one if it is there.
     *
     * The button is disabled for the duration rather than guarded by a flag: the whole
     * sequence — API call, download, installer staging — runs on a background thread, and
     * a second press would start a second download of the same APK. It comes back on when
     * the result lands, whatever that result is.
     *
     * A modal progress surface keeps the driver informed while the APK is downloaded and
     * SHA-256 verified; the final result remains visible until dismissed.
     */
    private void checkForUpdate(View button) {
        button.setEnabled(false);
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        TextView status = new TextView(this);
        status.setText(R.string.update_checking);
        status.setTextAppearance(R.style.Text_EV_Body);
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        android.widget.LinearLayout.LayoutParams progressParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = padding;
        panel.addView(status);
        panel.addView(progress, progressParams);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_dialog_title)
                .setView(panel)
                .setCancelable(false)
                .create();
        dialog.show();
        UpdateHook.checkInBackground(this, new UpdateHook.Listener() {
            @Override public void onProgress(int percent) {
                if (percent < 0) {
                    progress.setIndeterminate(true);
                    status.setText(R.string.update_downloading);
                } else {
                    progress.setIndeterminate(false);
                    progress.setMax(100);
                    progress.setProgress(percent);
                    status.setText(getString(R.string.update_downloading_percent, percent));
                }
            }

            @Override public void onResult(String message) {
                button.setEnabled(true);
                dialog.dismiss();
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(R.string.update_dialog_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    // ---------- Wi-Fi page ----------

    private void bindWifiPane() {
        wifi = new WifiAutoConnect(getApplicationContext());
        wifiSsid      = wifiPane.findViewById(R.id.wifi_ssid);
        wifiState     = wifiPane.findViewById(R.id.wifi_state);
        wifiSavedList = wifiPane.findViewById(R.id.wifi_saved_list);

        SwitchMaterial auto = wifiPane.findViewById(R.id.wifi_auto_switch);
        auto.setChecked(prefs.getBoolean(WIFI_AUTO_KEY, true));
        auto.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(WIFI_AUTO_KEY, checked).apply());

        wifiPane.findViewById(R.id.wifi_connect_button).setOnClickListener(v -> {
            // force: the driver is asking now, so the rate limit does not apply.
            WifiAutoConnect.Result r = wifi.ensureConnected(true);
            wifiState.setText(r.message);
            refreshWifi();
        });
    }

    private void refreshWifi() {
        if (wifi == null) return;
        String ssid = wifi.currentSsid();
        wifiSsid.setText(ssid == null ? getString(R.string.wifi_not_connected) : ssid);
        if (ssid != null) {
            wifiState.setText("Bağlı ♥");
        } else if (!wifi.isWifiEnabled()) {
            wifiState.setText("Wi-Fi kapalı");
        }
        // Left alone otherwise: a message the button just set says more about what is
        // happening than "not connected" would.

        java.util.List<String> saved = wifi.savedNetworkNames();
        wifiSavedList.setText(saved.isEmpty()
                ? getString(R.string.wifi_saved_empty)
                : android.text.TextUtils.join("\n", saved));
    }

    // ---------- Vehicle page ----------

    /** Looks up the value cells once; the labels are static and never touched from code. */
    private void bindVehiclePane() {
        vehSoc       = vehiclePane.findViewById(R.id.vehicle_soc);
        vehRange     = vehiclePane.findViewById(R.id.vehicle_range);
        vehPower     = vehiclePane.findViewById(R.id.vehicle_power);
        vehSpeed     = vehiclePane.findViewById(R.id.vehicle_speed);
        vehOdometer  = vehiclePane.findViewById(R.id.vehicle_odometer);
        vehCharging  = vehiclePane.findViewById(R.id.vehicle_charging);
        vehHvac      = vehiclePane.findViewById(R.id.vehicle_hvac);
        vehSoe       = vehiclePane.findViewById(R.id.vehicle_soe);
        vehTireFl    = vehiclePane.findViewById(R.id.vehicle_tire_fl);
        vehTireFr    = vehiclePane.findViewById(R.id.vehicle_tire_fr);
        vehTireRl    = vehiclePane.findViewById(R.id.vehicle_tire_rl);
        vehTireRr    = vehiclePane.findViewById(R.id.vehicle_tire_rr);
    }

    /**
     * Reads one snapshot and writes it to the page.
     *
     * The reader is built lazily and kept: constructing it binds the vendor services, which
     * is not work to repeat every two seconds. A read that throws leaves the cells as they
     * were rather than blanking the page — a transient miss is not news worth showing.
     */
    private void refreshVehicle() {
        try {
            if (vehicleReader == null) {
                vehicleReader = new EnergyTelemetryReader(getApplicationContext());
            }
            // The timestamp is passed explicitly: its Kotlin default is not visible from Java.
            EnergySnapshot s = vehicleReader.read(System.currentTimeMillis());
            ConsumptionTracker.get(this).sample(s);
            refreshCharging(s);

            vehSoc.setText(fmt(s.getSocPercent(), "%.0f %%"));
            vehRange.setText(fmt(s.getRangeKm(), "%.0f km"));
            vehPower.setText(fmt(s.getBatteryPowerKw(), "%.1f kW"));
            vehSpeed.setText(fmt(s.getSpeedKmh(), "%.0f km/h"));
            vehOdometer.setText(fmt(s.getOdometerKm(), "%.0f km"));
            vehHvac.setText(fmt(s.getClimate().getDriverTargetCelsius(), "%.0f °C"));
            vehSoe.setText(fmt(s.getBatteryEnergyKwh(), "%.1f kWh"));

            vehCharging.setText(chargingLabel(s));

            vehTireFl.setText(fmt(s.getTirePressures().getFrontLeftKpa(), "%.0f kPa"));
            vehTireFr.setText(fmt(s.getTirePressures().getFrontRightKpa(), "%.0f kPa"));
            vehTireRl.setText(fmt(s.getTirePressures().getRearLeftKpa(), "%.0f kPa"));
            vehTireRr.setText(fmt(s.getTirePressures().getRearRightKpa(), "%.0f kPa"));
        } catch (Throwable ignored) {
            // Leave the last good reading on screen.
        }
    }

    private void bindConsumptionPane() {
        consumptionTracker = ConsumptionTracker.get(this);
        consumptionPeriod = consumptionPane.findViewById(R.id.consumption_period);
        String[] periods = {"Başlangıçtan itibaren", "Motor çalıştıktan itibaren",
                "Tüm zamanlar", "Trip A", "Trip B"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, periods);
        consumptionPeriod.setAdapter(adapter);
        consumptionDistance = consumptionPane.findViewById(R.id.consumption_distance);
        consumptionEnergy = consumptionPane.findViewById(R.id.consumption_energy);
        consumptionAverage = consumptionPane.findViewById(R.id.consumption_average);
        consumptionSpeed = consumptionPane.findViewById(R.id.consumption_speed);
        consumptionTime = consumptionPane.findViewById(R.id.consumption_time);
        consumptionSoc = consumptionPane.findViewById(R.id.consumption_soc);
        consumptionReset = consumptionPane.findViewById(R.id.consumption_reset);
        consumptionPeriod.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                consumptionReset.setVisibility(position >= 3 ? View.VISIBLE : View.GONE);
                refreshConsumption();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        consumptionReset.setOnClickListener(v -> {
            int selected = consumptionPeriod.getSelectedItemPosition();
            if (selected < 3) return;
            String name = selected == 3 ? "Trip A" : "Trip B";
            new MaterialAlertDialogBuilder(this).setTitle(name + " sıfırlansın mı?")
                    .setMessage("Bu sayacın kayıtlı tüketim geçmişi kalıcı olarak silinir.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Sıfırla", (d, w) -> {
                        consumptionTracker.reset(selected == 3
                                ? ConsumptionTracker.Period.TRIP_A : ConsumptionTracker.Period.TRIP_B);
                        refreshConsumption();
                    }).show();
        });
    }

    private void bindChargingPane() {
        chargeStatus = chargingPane.findViewById(R.id.charge_status);
        chargeSoc = chargingPane.findViewById(R.id.charge_soc);
        chargeVoltage = chargingPane.findViewById(R.id.charge_voltage);
        chargeCurrent = chargingPane.findViewById(R.id.charge_current);
        chargePower = chargingPane.findViewById(R.id.charge_power);
        chargeEnergy = chargingPane.findViewById(R.id.charge_energy);
        chargeDuration = chargingPane.findViewById(R.id.charge_duration);
        chargeGraph = chargingPane.findViewById(R.id.charge_graph);
    }

    private void refreshCharging(EnergySnapshot s) {
        if (chargeStatus == null) return;
        Integer code = s.getChargingStatus();
        boolean charging = (code != null && (code == 1 || code == 10)) ||
                (Boolean.TRUE.equals(s.getChargePortConnected()) && s.getBatteryPowerKw() != null
                        && s.getBatteryPowerKw() < -0.2f);
        long now = s.getTimestampMs();
        if (charging && chargeStartMs == 0) { chargeStartMs = now; chargedKwh = 0; }
        if (!charging) { chargeStartMs = 0; lastChargeMs = 0; chargedKwh = 0; }
        if (charging && lastChargeMs > 0 && s.getBatteryPowerKw() != null)
            chargedKwh += Math.max(0, -s.getBatteryPowerKw()) * (now-lastChargeMs) / 3_600_000d;
        if (charging) lastChargeMs = now;
        chargeStatus.setText(charging ? (code != null && code == 10 ? "DC hızlı şarj" : "AC şarj") : "Şarj yok");
        chargeSoc.setText(fmt(s.getSocPercent(), "%.1f %%"));
        chargeVoltage.setText(fmt(s.getBatteryVoltageV(), "%.1f V"));
        chargeCurrent.setText(fmt(s.getBatteryCurrentA(), "%.1f A"));
        chargePower.setText(s.getBatteryPowerKw() == null ? "—" : String.format(java.util.Locale.getDefault(), "%.1f kW", Math.abs(s.getBatteryPowerKw())));
        chargeEnergy.setText(charging ? String.format(java.util.Locale.getDefault(), "%.2f kWh", chargedKwh) : "—");
        long sec = chargeStartMs == 0 ? 0 : (now-chargeStartMs)/1000;
        chargeDuration.setText(charging ? String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", sec/3600,(sec%3600)/60,sec%60) : "—");
        if (charging) chargeGraph.add(s.getSocPercent(), s.getBatteryPowerKw());
    }

    private void refreshConsumption() {
        if (consumptionTracker == null || consumptionPeriod == null) return;
        ConsumptionTracker.Period period = ConsumptionTracker.Period.values()[
                Math.max(0, consumptionPeriod.getSelectedItemPosition())];
        ConsumptionTracker.Totals t = consumptionTracker.totals(period);
        consumptionDistance.setText(String.format(java.util.Locale.getDefault(), "%.1f km", t.km));
        consumptionEnergy.setText(String.format(java.util.Locale.getDefault(), "%.2f kWh", t.kwh));
        consumptionAverage.setText(t.km < 0.1 ? "—" : String.format(java.util.Locale.getDefault(),
                "%.1f kWh/100 km", t.kwh * 100d / t.km));
        consumptionSpeed.setText(t.hours <= 0 ? "—" : String.format(java.util.Locale.getDefault(),
                "%.1f km/h", t.km / t.hours));
        long minutes = Math.round(t.hours * 60d);
        consumptionTime.setText(String.format(java.util.Locale.getDefault(), "%d sa %02d dk",
                minutes / 60, minutes % 60));
        consumptionSoc.setText(String.format(java.util.Locale.getDefault(), "%.1f %%", t.soc));
    }

    /**
     * The charging row: the session state when the car reports one, the port otherwise.
     *
     * The codes are the vehicle's own — see EVHardware.PROP_VENDOR_CHARGE_STATUS. An
     * unrecognised code is shown as itself rather than guessed at.
     */
    private static String chargingLabel(EnergySnapshot s) {
        Integer status = s.getChargingStatus();
        if (status != null) {
            switch (status) {
                case 1:  return "AC şarj ♥";
                case 10: return "DC hızlı şarj ♥";
                case 5:  return "Bağlanıyor…";
                case 7:  return "Kabloda, şarj yok";
                case 8:  return "Durduruldu";
                case 0:  return "Şarj yok";
                default: return "Durum " + status;
            }
        }
        Boolean plugged = s.getChargePortConnected();
        if (plugged != null) return plugged ? "Bağlı ♥" : "Bağlı değil";
        return "—";
    }

    /** A dash for a signal the car does not publish — never a zero. See pane_vehicle.xml. */
    private static String fmt(Float value, String pattern) {
        if (value == null || value.isNaN() || value.isInfinite()) return "—";
        return String.format(java.util.Locale.US, pattern, value);
    }

    // ---------- Call log ----------

    private void refreshCallLog() {
        java.util.List<UploadLog.Entry> entries = AbrpUploadService.log().recent();
        if (entries.isEmpty()) {
            callLogText.setText(R.string.call_log_empty);
            return;
        }
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        StringBuilder sb = new StringBuilder();
        for (UploadLog.Entry entry : entries) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.success ? "OK  " : "ERR ")
              .append(format.format(new java.util.Date(entry.timestampMs)))
              .append("  ")
              // httpStatus 0 means the request never reached a server.
              .append(entry.httpStatus > 0 ? String.valueOf(entry.httpStatus) : "---")
              .append("  ")
              .append(entry.detail);
            // What the payload held, under the attempt it belongs to. This is the only view
            // of it the driver has: the car runs no adb, so "the field is 0 on ABRP" and
            // "the app never sent the field" look identical from the outside without it.
            if (entry.summary != null && !entry.summary.isEmpty()) {
                sb.append("\n    ").append(entry.summary);
            }
        }
        callLogText.setText(sb.toString());
    }

    // ---------- VHAL probe ----------

    /**
     * Wires the "VHAL Probe" button that dumps the vehicle's own telemetry property
     * surface into the in-app log. The head unit runs no adb, so the screen's existing
     * log view is the only channel a diagnostic can reach the driver through.
     *
     * Present in every build, not just debug: identifying the SWI69 property ids needs to
     * happen on a production APK on the car, and EVHardware's probe is read-only, so it is
     * safe to ship. It deliberately holds no device credentials. Keep it until the
     * per-generation property map for SWI69 is confirmed, then gate it on debug.
     */
    private void wireProbeIfDebug() {
        View probeButton = abrpPane.findViewById(R.id.probe_button);
        if (probeButton == null) return;
        probeButton.setVisibility(View.VISIBLE);
        probeButton.setOnClickListener(v -> runProbe());
    }

    private void runProbe() {
        // Binder reads are slow enough to be worth keeping off the UI thread; the result
        // is recorded through the upload log, which the screen already renders.
        new Thread(() -> {
            try {
                EVHardware.INSTANCE.init(getApplicationContext());
                java.util.List<EVHardware.PropertyReport> reports = EVHardware.INSTANCE.probeTelemetryProperties();
                String firmware = String.valueOf(FirmwareInfo.INSTANCE.getGeneration());
                StringBuilder sb = new StringBuilder("VHAL probe on ").append(firmware).append('\n');
                for (EVHardware.PropertyReport r : reports) sb.append("  ").append(r).append('\n');
                sb.append("TOTAL ").append(reports.size()).append(" properties probed");
                runOnUiThread(() -> AbrpUploadService.log().record(
                        new UploadLog.Entry(System.currentTimeMillis(), 200, true, "VHAL Probe", sb.toString())));
            } catch (Throwable t) {
                String msg = "Probe failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                runOnUiThread(() -> AbrpUploadService.log().record(
                        new UploadLog.Entry(System.currentTimeMillis(), 0, false, "VHAL Probe", msg)));
            }
        }).start();
    }

    private void setConnectionStatus(int color, String message) {
        connectionStatusRow.setVisibility(View.VISIBLE);
        connectionIndicator.getBackground().setTint(color);
        connectionStatusText.setText(message);
        connectionStatusText.setTextColor(color);
    }

    // ---------- Service status ----------

    private void refreshStatus() {
        // Live signal, not the preference: "service_running" stays stale-true after a
        // force-kill because onDestroy never ran.
        UploadLog.State state = AbrpUploadService.state();

        switch (state) {
            case ERROR:
                // Derived from the log, not from the last status alone: a single failed
                // upload is normal (tunnel, dead spot), three in a row is a problem.
                statusText.setText(getString(R.string.state_error,
                        AbrpUploadService.log().consecutiveFailures()));
                statusText.setTextColor(COLOR_ERROR);
                break;
            case RUNNING:
                String lastTime = prefs.getString("last_upload_time", null);
                statusText.setText(lastTime != null
                        ? getString(R.string.status_last_upload, lastTime,
                                getString(R.string.status_ok))
                        : getString(R.string.state_running));
                statusText.setTextColor(COLOR_OK);
                break;
            case STARTING:
                statusText.setText(R.string.state_starting);
                statusText.setTextColor(COLOR_OK);
                break;
            case STOPPED:
            default:
                statusText.setText(R.string.state_stopped);
                statusText.setTextColor(COLOR_PENDING);
                break;
        }
    }

    // ---------- Helpers ----------

    private String textOf(TextInputEditText field) {
        CharSequence text = field.getText();
        return text != null ? text.toString().trim() : "";
    }

    /**
     * Permissions that need a runtime grant (vs being granted at install time
     * via the platform signature). CAR_SPEED and CAR_ENERGY are dangerous-level
     * AAOS permissions — without these, the corresponding property reads throw
     * SecurityException and we have no SOC / speed data.
     */
    private static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_ENERGY_PORTS",
            // Outside temperature and cabin temperature: declared and read since 2.1.0, but
            // never asked for, so both came back unreadable and were dropped from every
            // payload on a car that would have answered them.
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.car.permission.CONTROL_CAR_CLIMATE",
    };

    /**
     * Asked for alongside the telemetry permissions from API 33, and separately because a
     * denial costs something different: the foreground notification is the only status this
     * service has, and without this it is dropped silently while the service runs on.
     */
    private static String[] runtimePermissions() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return RUNTIME_PERMISSIONS;
        }
        String[] all = java.util.Arrays.copyOf(RUNTIME_PERMISSIONS, RUNTIME_PERMISSIONS.length + 1);
        all[RUNTIME_PERMISSIONS.length] = Manifest.permission.POST_NOTIFICATIONS;
        return all;
    }

    private void requestLocationPermissionIfNeeded() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String p : runtimePermissions()) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]),
                    LOCATION_PERMISSION_REQUEST);
        }
    }

    /**
     * A denied permission used to be silent: the service kept running and simply uploaded
     * nothing useful, with the UI claiming everything was fine. Say what was denied and
     * what it costs.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;

        boolean locationDenied = false;
        boolean carDataDenied  = false;
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) continue;
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])) {
                locationDenied = true;
            } else {
                carDataDenied = true;
            }
        }

        // A grant that arrives after the service is already up changes which foreground
        // types it may hold, and which properties it may read. Starting it again is what
        // applies both without waiting for the next boot.
        if (prefs.getBoolean("service_enabled", false)) {
            startForegroundService(new Intent(this, AbrpUploadService.class));
        }

        if (locationDenied && carDataDenied) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.perm_denied_all));
        } else if (locationDenied) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.perm_denied_location));
        } else if (carDataDenied) {
            setConnectionStatus(COLOR_ERROR, getString(R.string.perm_denied_car));
        }
        refreshStatus();
    }
}
