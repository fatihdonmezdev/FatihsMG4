package com.evsuite.abrp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.evsuite.hardware.telemetry.EnergySnapshot;
import com.evsuite.hardware.telemetry.EnergyTelemetryReader;


public class MainActivity extends AppCompatActivity {

    /** Shared with {@link AbrpUploadService}, which reads it on every tick. */
    static final String WIFI_AUTO_KEY = "wifi_auto_connect";
    private static final String REPOSITORY_URL = "https://github.com/fatihdonmezdev/FatihsMG4";

    /** Navigation destinations in page order. Parallel to {@link #panes}. */
    private static final int[] TAB_IDS =
            { R.id.tabVehicle, R.id.tabConsumption, R.id.tabCharging, R.id.tabService, R.id.tabWifi,
              R.id.tabLog };

    private static final int[] PAGE_TITLES = {
            R.string.design_overview_title, R.string.consumption_title, R.string.charging_title,
            R.string.design_service_title, R.string.design_wifi_title, R.string.design_log_title
    };
    private static final int[] PAGE_SUBTITLES = {
            R.string.design_overview_subtitle, R.string.consumption_subtitle, R.string.charging_subtitle,
            R.string.design_service_subtitle, R.string.design_wifi_subtitle, R.string.design_log_subtitle
    };

    // Status colours come from the palette, not from the Material swatches: #4CAF50 and
    // #F44336 sit around 4:1 on this background, which disappears behind a sunlit
    // reflection. The palette entries are the lightened variants (7:1 and above).
    private static final int COLOR_OK      = 0xFFA4DCC1;
    private static final int COLOR_ERROR   = 0xFFFFB4AB;
    private static final int COLOR_PENDING = 0xFFBDC4C6;

    private TextView          statusText;
    private TextView callLogText;
    private View servicePane;
    private View logPane;
    private View vehiclePane;
    private View consumptionPane;
    private View chargingPane;
    private View wifiPane;

    /** The six pages in navigation order. Parallel to {@link #TAB_IDS}. */
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
            vehCharging, vehHvac,
            vehTireFl, vehTireFr, vehTireRl, vehTireRr;
    private ConsumptionTracker consumptionTracker;
    private ConsumptionCloudClient consumptionCloudClient;
    private ConsumptionTracker.Period consumptionPeriod = ConsumptionTracker.Period.LIFETIME;
    private TextView consumptionDistance, consumptionEnergy, consumptionAverage,
            consumptionSpeed, consumptionTime, consumptionSoc, consumptionDailyHistory;
    private Button consumptionReset;
    private MaterialButton consumptionDateButton;
    private TextInputLayout consumptionSohLayout;
    private TextInputEditText consumptionSohInput;
    private java.time.LocalDate selectedConsumptionDate = java.time.LocalDate.now();
    private TextView chargeStatus, chargeSoc, chargeStartSoc, chargeVoltage, chargeCurrent, chargePower,
            chargeEnergy, chargeGridEnergy, chargeDuration, chargeTotalCost, chargeCurveTable;
    private TextInputLayout chargePriceLayout;
    private TextInputEditText chargePriceInput;
    private ChargeSessionTracker chargeSessionTracker;
    private ChargingGraphView chargeGraph;

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
        // Keep all pages alive: the log and vehicle readings are refreshed
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ((TextView) findViewById(R.id.version_badge)).setText("v" + BuildConfig.VERSION_NAME);

        prefs = getSharedPreferences("abrp_prefs", MODE_PRIVATE);

        // The pages are inflated here, once, and handed to the pager as fixed,
        // non-recycled items. That is what lets every widget below be looked up now and
        // held for the life of the activity, the way it was when the panes were siblings
        // in activity_main.xml — a recycled page would invalidate these references as
        // soon as the user swiped away from it.
        servicePane = inflatePane(R.layout.pane_service);
        vehiclePane   = inflatePane(R.layout.pane_vehicle);
        consumptionPane = inflatePane(R.layout.pane_consumption);
        chargingPane = inflatePane(R.layout.pane_charging);
        wifiPane    = inflatePane(R.layout.pane_wifi);
        logPane     = inflatePane(R.layout.pane_log);
        panes = new View[] { vehiclePane, consumptionPane, chargingPane, servicePane, wifiPane, logPane };

        bindVehiclePane();
        bindConsumptionPane();
        bindChargingPane();
        bindWifiPane();

        statusText          = servicePane.findViewById(R.id.status_text);
        callLogText = logPane.findViewById(R.id.call_log_text);
        logPane.findViewById(R.id.flush_now_button).setOnClickListener(v -> manualFlush());

        setUpPager();
        findViewById(R.id.about_button).setOnClickListener(v -> showAbout());
        findViewById(R.id.update_button).setOnClickListener(v -> checkForUpdate(v));

        // Unstable builds check for a newer pre-release; the stable flavor's UpdateHook is
        // a no-op and does not even contain the updater.
        UpdateHook.checkInBackground(this);

        // The service is always-on: it tracks consumption and syncs to MongoDB cloud.
        // startForegroundService is idempotent — if the service is already up this
        // is a no-op aside from delivering a new intent.
        startForegroundService(new Intent(this, AbrpUploadService.class));
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
        // Poll state and log only while the screen is up; onPause cancels it.
        uiHandler.post(uiRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop polling off-screen: this activity has no reason to spend cycles then.
        uiHandler.removeCallbacks(uiRefresh);
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
        consumptionCloudClient = new ConsumptionCloudClient(getApplicationContext());
        consumptionDistance = consumptionPane.findViewById(R.id.consumption_distance);
        consumptionEnergy = consumptionPane.findViewById(R.id.consumption_energy);
        consumptionAverage = consumptionPane.findViewById(R.id.consumption_average);
        consumptionSpeed = consumptionPane.findViewById(R.id.consumption_speed);
        consumptionTime = consumptionPane.findViewById(R.id.consumption_time);
        consumptionSoc = consumptionPane.findViewById(R.id.consumption_soc);
        consumptionDailyHistory = consumptionPane.findViewById(R.id.consumption_daily_history);
        consumptionDateButton = consumptionPane.findViewById(R.id.consumption_date_button);
        consumptionDateButton.setText(selectedConsumptionDate.toString());
        consumptionDateButton.setOnClickListener(v -> showConsumptionDatePicker());
        consumptionPane.findViewById(R.id.consumption_refresh).setOnClickListener(v -> refreshFromCloud());
        consumptionReset = consumptionPane.findViewById(R.id.consumption_reset);
        consumptionSohLayout = consumptionPane.findViewById(R.id.consumption_soh_layout);
        consumptionSohInput = consumptionPane.findViewById(R.id.consumption_soh_input);
        Float savedSoh = consumptionTracker.sohPercent();
        if (savedSoh != null) consumptionSohInput.setText(String.format(
                java.util.Locale.US, "%.1f", savedSoh));
        consumptionPane.findViewById(R.id.consumption_soh_save).setOnClickListener(v -> saveSoh());
        bindPeriodButton(R.id.consumption_lifetime, ConsumptionTracker.Period.LIFETIME);
        bindPeriodButton(R.id.consumption_week, ConsumptionTracker.Period.WEEK);
        bindPeriodButton(R.id.consumption_month, ConsumptionTracker.Period.MONTH);
        bindPeriodButton(R.id.consumption_trip_a, ConsumptionTracker.Period.TRIP_A);
        bindPeriodButton(R.id.consumption_trip_b, ConsumptionTracker.Period.TRIP_B);
        selectPeriodButton(R.id.consumption_lifetime);
        consumptionReset.setOnClickListener(v -> {
            if (consumptionPeriod != ConsumptionTracker.Period.TRIP_A && consumptionPeriod != ConsumptionTracker.Period.TRIP_B) return;
            String name = consumptionPeriod == ConsumptionTracker.Period.TRIP_A ? "Trip A" : "Trip B";
            new MaterialAlertDialogBuilder(this).setTitle(name + " sıfırlansın mı?")
                    .setMessage("Bu sayacın kayıtlı tüketim geçmişi kalıcı olarak silinir.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Sıfırla", (d, w) -> {
                        consumptionTracker.reset(consumptionPeriod);
                        refreshConsumption();
                    }).show();
        });
    }

    private void saveSoh() {
        String raw = textOf(consumptionSohInput).replace(',', '.');
        try {
            float value = Float.parseFloat(raw);
            if (!consumptionTracker.setSohPercent(value)) throw new NumberFormatException();
            consumptionSohLayout.setError(null);
            consumptionSohInput.setText(String.format(java.util.Locale.US, "%.1f", value));
            Toast.makeText(this, "SoH kaydedildi", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            consumptionSohLayout.setError("1 ile 100 arasında bir değer girin");
        }
    }

    private void bindPeriodButton(int id, ConsumptionTracker.Period period) {
        View button = consumptionPane.findViewById(id);
        button.setOnClickListener(v -> {
            selectPeriodButton(id);
            consumptionPeriod = period;
            consumptionReset.setVisibility(period == ConsumptionTracker.Period.TRIP_A ||
                    period == ConsumptionTracker.Period.TRIP_B ? View.VISIBLE : View.GONE);
            refreshConsumption();
        });
    }

    private void selectPeriodButton(int selectedId) {
        int[] ids = {R.id.consumption_lifetime, R.id.consumption_week,
                R.id.consumption_month, R.id.consumption_trip_a, R.id.consumption_trip_b};
        for (int id : ids) {
            MaterialButton button = consumptionPane.findViewById(id);
            boolean selected = id == selectedId;
            button.setSelected(selected);
            button.setTextColor(selected ? 0xFFD8C39A : COLOR_PENDING);
            button.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    selected ? 0xFFD8C39A : 0xFF3A4143));
        }
    }

    private void bindChargingPane() {
        chargeSessionTracker = ChargeSessionTracker.get(this);
        chargeStatus = chargingPane.findViewById(R.id.charge_status);
        chargeSoc = chargingPane.findViewById(R.id.charge_soc);
        chargeStartSoc = chargingPane.findViewById(R.id.charge_start_soc);
        chargeVoltage = chargingPane.findViewById(R.id.charge_voltage);
        chargeCurrent = chargingPane.findViewById(R.id.charge_current);
        chargePower = chargingPane.findViewById(R.id.charge_power);
        chargeEnergy = chargingPane.findViewById(R.id.charge_energy);
        chargeGridEnergy = chargingPane.findViewById(R.id.charge_grid_energy);
        chargeDuration = chargingPane.findViewById(R.id.charge_duration);
        chargeTotalCost = chargingPane.findViewById(R.id.charge_total_cost);
        chargeGraph = chargingPane.findViewById(R.id.charge_graph);
        chargeCurveTable = chargingPane.findViewById(R.id.charge_curve_table);
        chargePriceLayout = chargingPane.findViewById(R.id.charge_price_layout);
        chargePriceInput = chargingPane.findViewById(R.id.charge_price_input);
        chargePriceInput.setText(String.format(java.util.Locale.US, "%.2f",
                chargeSessionTracker.pricePerKwh()));
        chargingPane.findViewById(R.id.charge_price_save).setOnClickListener(v -> saveChargePrice());
    }

    private void saveChargePrice() {
        String raw = textOf(chargePriceInput).replace(',', '.');
        try {
            double value = Double.parseDouble(raw);
            if (!chargeSessionTracker.setPricePerKwh(value)) throw new NumberFormatException();
            chargePriceLayout.setError(null);
            chargePriceInput.setText(String.format(java.util.Locale.US, "%.2f", value));
            Toast.makeText(this, "Şarj fiyatı kaydedildi", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            chargePriceLayout.setError("Geçerli bir ₺/kWh değeri girin");
        }
    }

    private void refreshCharging(EnergySnapshot s) {
        if (chargeStatus == null) return;
        chargeSessionTracker.sample(s);
        Integer code = s.getChargingStatus();
        boolean charging = ChargingState.isCharging(
                code, s.getChargePortConnected(), s.getBatteryPowerKw(), s.getSpeedKmh());
        ChargeSessionTracker.Snapshot session = chargeSessionTracker.snapshot();
        // Only DC is metered, so an AC charge must say so rather than let the readouts
        // below — which then describe the previous DC session — be read as this charge.
        boolean dc = ChargingState.isDcCharging(
                code, s.getChargePortConnected(), s.getBatteryPowerKw(), s.getSpeedKmh());
        chargeStatus.setText(dc ? "DC hızlı şarj"
                : charging ? "AC şarj (ölçülmüyor)"
                : (session.startedAtMs > 0 ? "Son şarj" : "Şarj yok"));
        chargeSoc.setText(fmt(s.getSocPercent(), "%.1f %%"));
        chargeStartSoc.setText(fmt(session.startSocPercent, "%.1f %%"));
        chargeVoltage.setText(fmt(s.getBatteryVoltageV(), "%.1f V"));
        chargeCurrent.setText(fmt(s.getBatteryCurrentA(), "%.1f A"));
        chargePower.setText(s.getBatteryPowerKw() == null ? "—" : String.format(java.util.Locale.getDefault(), "%.1f kW", Math.abs(s.getBatteryPowerKw())));
        chargeEnergy.setText(session.startedAtMs > 0 ? String.format(java.util.Locale.getDefault(), "%.2f kWh", session.energyKwh) : "—");
        chargeGridEnergy.setText(session.startedAtMs > 0 ? String.format(
                java.util.Locale.getDefault(), "%.2f kWh", session.gridEnergyKwh) : "—");
        long sec = session.durationSeconds;
        chargeDuration.setText(session.startedAtMs > 0 ? String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", sec/3600,(sec%3600)/60,sec%60) : "—");
        chargeTotalCost.setText(session.startedAtMs > 0 ? String.format(java.util.Locale.getDefault(), "%.2f ₺", session.totalCost) : "—");
        chargeGraph.setPoints(session.points);
        chargeCurveTable.setText(chargeGraph.tableText());
    }

    private void refreshConsumption() {
        if (consumptionTracker == null) return;
        // Trip counters are local-only (cloud has no trip concept); everything else reads
        // back from the cloud so an APK wipe does not blank the display.
        ConsumptionTracker.Totals t;
        if (consumptionPeriod == ConsumptionTracker.Period.TRIP_A
                || consumptionPeriod == ConsumptionTracker.Period.TRIP_B) {
            t = consumptionTracker.totals(consumptionPeriod);
        } else {
            t = cloudTotals(consumptionPeriod);
        }
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
        // Daily history: cloud first, local fallback for today (not yet uploaded).
        ConsumptionTracker.Totals day = cloudDay(selectedConsumptionDate);
        if (day == null) day = consumptionTracker.totalsForDay(selectedConsumptionDate);
        if (day.km == 0 && day.kwh == 0 && day.hours == 0 && day.soc == 0) {
            consumptionDailyHistory.setText("Bu tarih için kayıt yok");
        } else {
            String average = day.km < 0.1 ? "—" : String.format(java.util.Locale.getDefault(),
                    "%.1f kWh/100 km", day.kwh * 100d / day.km);
            long dayMinutes = Math.round(day.hours * 60d);
            consumptionDailyHistory.setText(String.format(java.util.Locale.getDefault(),
                    "Mesafe       %.1f km\nEnerji       %.2f kWh\nOrtalama     %s\nSüre         %d sa %02d dk\nSOC farkı    %.1f %%",
                    day.km, day.kwh, average, dayMinutes / 60, dayMinutes % 60, day.soc));
        }
    }

    /**
     * Period totals from the cloud history. LIFETIME = the latest record's lifetime field;
     * WEEK/MONTH = sum of the last 7/30 days' day-totals. Falls back to the local tracker
     * when the cloud is empty or not yet fetched, so the UI is never blank on a cold start.
     */
    private ConsumptionTracker.Totals cloudTotals(ConsumptionTracker.Period period) {
        List<ConsumptionCloudClient.DayRecord> history = consumptionCloudClient != null
                ? consumptionCloudClient.fetchHistoryIfDue() : java.util.Collections.emptyList();
        if (history.isEmpty()) return consumptionTracker.totals(period);
        if (period == ConsumptionTracker.Period.LIFETIME) {
            // history is sorted date-desc, so index 0 is the latest day carrying lifetime.
            return history.get(0).lifetime;
        }
        int days = period == ConsumptionTracker.Period.WEEK ? 7 : 30;
        double km = 0, kwh = 0, hours = 0, soc = 0;
        LocalDate today = LocalDate.now();
        for (int i = 0; i < days; i++) {
            ConsumptionTracker.Totals d = cloudDay(today.minusDays(i), history);
            if (d != null) { km += d.km; kwh += d.kwh; hours += d.hours; soc += d.soc; }
        }
        return new ConsumptionTracker.Totals(km, kwh, hours, soc);
    }

    /** Looks up one day in the cloud cache; null when that date is not present. */
    private ConsumptionTracker.Totals cloudDay(LocalDate date) {
        return cloudDay(date, consumptionCloudClient != null
                ? consumptionCloudClient.fetchHistoryIfDue() : java.util.Collections.emptyList());
    }

    private static ConsumptionTracker.Totals cloudDay(LocalDate date,
            List<ConsumptionCloudClient.DayRecord> history) {
        for (ConsumptionCloudClient.DayRecord r : history) {
            if (r.date.equals(date)) return r.day;
        }
        return null;
    }

    private void showConsumptionDatePicker() {
        java.time.LocalDate selected = selectedConsumptionDate;
        android.app.DatePickerDialog dialog = new android.app.DatePickerDialog(this,
                (view, year, month, day) -> {
                    selectedConsumptionDate = java.time.LocalDate.of(year, month + 1, day);
                    consumptionDateButton.setText(selectedConsumptionDate.toString());
                    refreshConsumption();
                }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.getDatePicker().setMinDate(java.time.LocalDate.now().minusMonths(4)
                .atStartOfDay(zone).toInstant().toEpochMilli());
        dialog.show();
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

    /**
     * "Şimdi yükle" button — forces a cache drain + fresh chunk on a background thread,
     * ignoring the 2-minute throttle. The result lands in the upload log (Kayıtlar tab).
     */
    private void manualFlush() {
        if (consumptionCloudClient == null || consumptionTracker == null) return;
        AbrpUploadService.log().record(new UploadLog.Entry(
                System.currentTimeMillis(), 0, true, "Manuel sync başlatıldı…"));
        new Thread(() -> {
            String result = consumptionCloudClient.flushNow(
                    consumptionTracker, System.currentTimeMillis());
            runOnUiThread(() -> Toast.makeText(this, result, Toast.LENGTH_SHORT).show());
        }, "manual-flush").start();
    }

    /**
     * Tüketim sekmesi "Yenile" button — sends cached chunks + fresh delta, invalidates the
     * history cache so the next poll fetches fresh data from the cloud, then refreshes the UI.
     */
    private void refreshFromCloud() {
        if (consumptionCloudClient == null || consumptionTracker == null) return;
        AbrpUploadService.log().record(new UploadLog.Entry(
                System.currentTimeMillis(), 0, true, "Tüketim yenileniyor…"));
        new Thread(() -> {
            consumptionCloudClient.flushNow(consumptionTracker, System.currentTimeMillis());
            consumptionCloudClient.invalidateHistory();
            runOnUiThread(() -> {
                refreshConsumption();
                Toast.makeText(this, "Tüketim yenilendi", Toast.LENGTH_SHORT).show();
            });
        }, "cloud-refresh").start();
    }

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

    // ---------- Service status ----------

    private void refreshStatus() {
        // The service is always running. Show the cloud sync state derived from the log.
        UploadLog.State state = AbrpUploadService.state();

        switch (state) {
            case ERROR:
                // Derived from the log: three consecutive cloud sync failures is a problem.
                statusText.setText(getString(R.string.state_error,
                        AbrpUploadService.log().consecutiveFailures()));
                statusText.setTextColor(COLOR_ERROR);
                break;
            case RUNNING:
            case STARTING:
                statusText.setText("Tüketim takibi aktif");
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
}
