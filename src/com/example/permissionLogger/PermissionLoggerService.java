package com.example.permissionlogger;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.ServiceManager; // Needed to add the service
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PermissionLoggerService extends Service {
    private static final String TAG = "PermissionLogSvc"; // Log tag for this service's own logs
    // Service name used for ServiceManager registration/lookup and SELinux service context
    private static final String SERVICE_NAME = "permission_logger_service";
    // Log file location (ensure init.rc creates this dir with correct perms/context)
    private static final String LOG_DIR_PATH = "/data/misc/permission_log"; // Changed name slightly for consistency
    private static final String LOG_FILE_NAME = "permission_events.log";

    private File logFile;
    // Use a single-threaded executor to serialize file writes and avoid blocking binder threads
    private ExecutorService fileWriterExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service creating, UID: " + Process.myUid());
        fileWriterExecutor = Executors.newSingleThreadExecutor();

        File logDir = new File(LOG_DIR_PATH);
        // Directory creation, ownership, and SELinux context MUST be handled by init.rc.
        // We just check if it exists here.
        if (!logDir.exists() || !logDir.isDirectory()) {
            Log.e(TAG, "Log directory '" + LOG_DIR_PATH + "' is missing or not a directory! " +
                       "Check init.rc configuration and SELinux policy.");
        } else {
             Log.i(TAG, "Log directory exists: " + LOG_DIR_PATH);
        }

        logFile = new File(logDir, LOG_FILE_NAME);
        Log.i(TAG, "Logging events to: " + logFile.getAbsolutePath());

        // Register this service with the Service Manager
        try {
            // Note: Requires sharedUserId="android.uid.system" and appropriate SELinux permissions
            ServiceManager.addService(SERVICE_NAME, binder);
            Log.i(TAG, "Service '" + SERVICE_NAME + "' successfully added to ServiceManager.");
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Failed to add service '" + SERVICE_NAME + "' to ServiceManager." +
                       " Check sharedUserId/certificate and SELinux permissions (add_service rule).", e);
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error adding service '" + SERVICE_NAME + "' to ServiceManager.", t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Clients typically use ServiceManager.getService(SERVICE_NAME) rather than bindService()
        // for system services like this, but returning the binder is still necessary.
        return binder;
    }

    // Implementation of the AIDL interface
    private final IPermissionLogger.Stub binder = new IPermissionLogger.Stub() {
        @Override
        public void logPermissionEvent(long timestampMillis, String sourceComponent, String packageName,
                                       String permission, String outcome, String details) {
            // Primary access control should be SELinux rules allowing specific domains
            // (like system_app, platform_app) to call this service's binder interface.
            // Log the caller UID for debugging purposes.
            Log.v(TAG, "logPermissionEvent received from UID: " + getCallingUid());

            // Submit the actual file writing to the background thread
            fileWriterExecutor.submit(() -> writeLogEntryToFile(timestampMillis, sourceComponent, packageName, permission, outcome, details));
        }
    };

    // This method runs on the fileWriterExecutor thread
    private void writeLogEntryToFile(long timestampMillis, String source, String pkg, String perm, String outcome, String details) {
        // Use standard ISO 8601 format for timestamp for better parsing later
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        String formattedTimestamp = sdf.format(new Date(timestampMillis));

        // Ensure details isn't null for formatting
        String safeDetails = (details != null) ? details : "";

        // Simple CSV format - quote fields that might contain delimiters or quotes
        String logLine = String.format("\"%s\",%d,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                formattedTimestamp,
                timestampMillis, // Include epoch millis as well
                sanitizeCsvField(source),
                sanitizeCsvField(pkg),
                sanitizeCsvField(perm),
                sanitizeCsvField(outcome),
                sanitizeCsvField(safeDetails)
        );

        // Check file writability defensively, though SELinux is the main gatekeeper.
        // The directory check happens in onCreate.
        if (!logFile.getParentFile().exists()) {
             Log.e(TAG, "Log directory seems to have disappeared! Skipping write.");
             return;
        }

        try (FileOutputStream fos = new FileOutputStream(logFile, true); // Use append mode
             PrintWriter writer = new PrintWriter(fos)) {
            writer.print(logLine);
            writer.flush(); // Ensure data is pushed to the OS buffer
        } catch (IOException e) {
            Log.e(TAG, "IOException writing to log file: " + logFile.getAbsolutePath(), e);
        } catch (SecurityException se) {
            // This would likely indicate an SELinux denial on file write/append.
            Log.e(TAG, "SecurityException writing to log file. Check SELinux policy (allow rules for " +
                       "'permission_logger_app' domain on 'permission_log_data_file' type).", se);
        } catch (Exception e) {
            // Catch any other unexpected file writing errors
             Log.e(TAG, "Unexpected exception writing to log file: " + logFile.getAbsolutePath(), e);
        }
    }

    // Basic CSV field sanitization: replace double quotes with two double quotes
    // and enclose the whole field in double quotes if it contains a comma, double quote, or newline.
    private String sanitizeCsvField(String input) {
        if (input == null) return "";
        // Check if sanitization/quoting is needed
        if (input.contains("\"") || input.contains(",") || input.contains("\n") || input.contains("\r")) {
            // Replace existing double quotes with two double quotes, then enclose the whole thing
            return "\"" + input.replace("\"", "\"\"") + "\"";
        }
        // No special characters found, return as is
        return input;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service received start command (flags=" + flags + ", startId=" + startId + ")");
        // Use START_STICKY to request the system restart the service if it's killed.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "Service destroying! Shutting down file writer executor."); // Log as warning as this shouldn't normally happen
        if (fileWriterExecutor != null) {
            fileWriterExecutor.shutdown(); // Attempt graceful shutdown
        }
        // Consider logging an event indicating the service stopped unexpectedly if needed.
        super.onDestroy();
    }
}
