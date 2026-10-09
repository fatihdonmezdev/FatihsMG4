package com.evsuite.abrp;

/**
 * Placeholder — the service is always-on and samples on a fixed 15-second scheduler, so
 * there is no user-configurable upload policy left. Kept as an empty shell so any stray
 * references compile during the ABRP removal; will be deleted once all callers are gone.
 */
final class UploadSettings {

    static final String KEY_AUTOSTART = "autostart";
    static final boolean DEFAULT_AUTOSTART = true;

    private UploadSettings() { }
}
