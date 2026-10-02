package com.blackback.batterydetector;

/**
 * Runs inside a Shizuku user service process (uid 2000 / shell) so that
 * framework APIs which are not reachable from an app process can be called.
 *
 * Method ids are explicit because [destroy] must keep the id reserved by the
 * Shizuku server (16777114), otherwise the service can never be stopped.
 */
interface IPrivilegedService {

    void destroy() = 16777114;

    /** Returns the uid of the process this service runs as (2000 for Shizuku/ADB). */
    int getServiceUid() = 1;

    /** Probes whether the connectivity firewall API is reachable from this process. */
    boolean isFirewallApiAvailable() = 2;

    /**
     * Allows or denies network access for a single uid via the connectivity
     * firewall, equivalent to what the shell can do for itself.
     *
     * @return true when one of the firewall chains accepted the rule.
     */
    boolean setUidFirewallRule(int uid, boolean allowed) = 3;

    /**
     * Adds a package to HyperOS's focus-notification allowlists
     * (`Settings.Secure.focus_notifs` / `updatable_focus_notifs`), which is what
     * makes `canShowFocus` return true for it.
     *
     * @return true when the setting was written.
     */
    boolean grantFocusNotification(String packageName) = 4;

    /** Removes a package from both focus-notification allowlists. */
    boolean revokeFocusNotification(String packageName) = 5;

    /** Reads back the current `focus_notifs` value, for diagnostics. */
    String getFocusAllowlist() = 6;
}
