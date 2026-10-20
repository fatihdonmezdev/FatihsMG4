package com.evsuite.abrp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * Access to vehicle GPS coordinates.
 *
 * The MG4 head unit provides vehicle position through standard Android LocationManager
 * with fused and gps providers.
 */
public final class VehicleLocationProvider {
    private static final String TAG = "VehicleLocation";
    private static VehicleLocationProvider instance;

    public static synchronized VehicleLocationProvider get(Context context) {
        if (instance == null) {
            instance = new VehicleLocationProvider(context.getApplicationContext());
        }
        return instance;
    }

    private final LocationManager locationManager;
    private volatile Location lastLocation;

    @SuppressLint("MissingPermission")
    private VehicleLocationProvider(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        // Try getting last known location from all providers
        updateFromLastKnown();

        // Register updates on available providers
        try {
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null && isValid(location)) {
                        lastLocation = location;
                    }
                }

                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            List<String> providers = locationManager.getAllProviders();
            if (providers != null) {
                for (String provider : providers) {
                    if (LocationManager.PASSIVE_PROVIDER.equals(provider)) continue;
                    try {
                        locationManager.requestLocationUpdates(
                                provider, 5000L, 5f, listener, Looper.getMainLooper());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register location updates: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void updateFromLastKnown() {
        if (locationManager == null) return;
        try {
            Location best = null;
            List<String> providers = locationManager.getAllProviders();
            if (providers != null) {
                for (String provider : providers) {
                    try {
                        Location loc = locationManager.getLastKnownLocation(provider);
                        if (loc != null && isValid(loc)) {
                            if (best == null || loc.getTime() > best.getTime()) {
                                best = loc;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (best != null) {
                lastLocation = best;
            }
        } catch (Exception ignored) {}
    }

    private static boolean isValid(Location loc) {
        return loc != null && (Math.abs(loc.getLatitude()) > 0.0001 || Math.abs(loc.getLongitude()) > 0.0001);
    }

    @SuppressLint("MissingPermission")
    public Location getLastLocation() {
        if (lastLocation == null) {
            updateFromLastKnown();
        }
        return lastLocation;
    }

    public Double getLatitude() {
        Location loc = getLastLocation();
        return (loc != null && isValid(loc)) ? loc.getLatitude() : null;
    }

    public Double getLongitude() {
        Location loc = getLastLocation();
        return (loc != null && isValid(loc)) ? loc.getLongitude() : null;
    }
}
