// IPermissionLogger.aidl
package com.example.permissionlogger;

/**
 * Interface for logging permission grant events for study purposes.
 */
interface IPermissionLogger {
    /**
     * Logs a permission decision event. Use oneway for fire-and-forget logging.
     * @param timestampMillis Epoch timestamp of the event.
     * @param sourceComponent Identifier for where the log originated (e.g., "GrantPermissionsActivity", "Settings").
     * @param packageName The package name of the app requesting permission.
     * @param permission The name of the permission being decided.
     * @param outcome A string indicating the outcome (e.g., "GRANTED", "DENIED", "IGNORED").
     * @param details Optional extra details (e.g., user ID, flags). Can be null or empty string.
     */
    oneway void logPermissionEvent(long timestampMillis, String sourceComponent, String packageName, String permission, String outcome, String details);
}
