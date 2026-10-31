package hu.ntamas.plumbob;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;

import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Finds "LED…" lamps, keeps one GATT per lamp with one write in flight, reconnects dropped lamps by MAC
 * while active, and sends the official app's password handshake after connecting. Listener calls arrive
 * on the UI thread. Lamp names (wearers) and released lamps are persisted in the given preferences.
 */
final class LampLink {
    interface Listener {
        void onScanStarted();                 // a 10 s scan burst begins
        void onScanStopped();                 // it ended (timeout or failure, see scanError)
        void onSeen(String advertisedName);   // every name seen during a scan, for diagnostics
        void onFound(Lamp lamp);              // registered, connection pending
        void onReady(Lamp lamp);              // connected, handshake sent: colours can go
        void onDropped(Lamp lamp);            // link lost; it reconnects by itself while active
        void onForgotten(Lamp lamp);          // no FFE0 service: not one of ours after all
        void onStateChanged();                // scanning / connecting / counts
    }

    static final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    static final UUID COMMAND = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    static final int SCAN_MS = 10000, RECONNECT_MS = 1500, WRITE_TIMEOUT_MS = 1000, HANDSHAKE_DELAY_MS = 300;
    static final String IGNORE_KEY = "ignore";

    final Map<String, Lamp> lamps = new LinkedHashMap<>();   // by MAC, discovery order
    final Set<String> seen = new TreeSet<>();                // advertised names of the current scan
    boolean scanning, active;
    int scanError;                                           // last ScanCallback error code, 0 when fine
    Lamp connecting;

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler ui;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Set<String> ignored = new HashSet<>();
    private final ArrayDeque<Lamp> toConnect = new ArrayDeque<>();

    LampLink(Context context, BluetoothAdapter adapter, Handler ui, SharedPreferences prefs, Listener listener) {
        this.context = context;
        this.adapter = adapter;
        this.ui = ui;
        this.prefs = prefs;
        this.listener = listener;
        ignored.addAll(prefs.getStringSet(IGNORE_KEY, Collections.emptySet()));
    }

    int connectedCount() {
        int n = 0;
        for (Lamp l : lamps.values()) if (l.connected()) n++;
        return n;
    }

    // Scan in 10 s bursts only: scanning next to live connections is what makes these cheap controllers drop.
    @SuppressWarnings("MissingPermission")
    void scan() {
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null || scanning || !active) return;
        scanning = true;
        scanError = 0;
        seen.clear();
        listener.onScanStarted();
        listener.onStateChanged();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            sc.startScan(null, s, scanCb);   // no UUID filter: the lamp does not advertise FFE0, we filter on the "LED" name
        } catch (SecurityException e) {      // BLUETOOTH_SCAN denied: the rescan buttons bypass the permission flow
            scanning = false;
            scanError = -1;
            listener.onStateChanged();
            listener.onScanStopped();
            return;
        }
        ui.postDelayed(this::stopScan, SCAN_MS);
    }

    @SuppressWarnings("MissingPermission")
    void stopScan() {
        if (!scanning) return;
        scanning = false;
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc != null) sc.stopScan(scanCb);
        listener.onStateChanged();
        listener.onScanStopped();
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            ui.post(() -> {
                BluetoothDevice d = r.getDevice();
                String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (n == null) n = d.getName();   // cached/bonded name as fallback
                seen.add(n == null ? "(névtelen " + d.getAddress().substring(12) + ")" : n);
                listener.onSeen(n);
                if (n == null || !n.toUpperCase().startsWith("LED") || !active || lamps.containsKey(d.getAddress()) || ignored.contains(d.getAddress())) return;
                Lamp lamp = new Lamp(d, n, prefs.getString(d.getAddress(), null));
                lamps.put(d.getAddress(), lamp);
                toConnect.add(lamp);
                listener.onFound(lamp);
                connectNext();
            });
        }

        @Override
        public void onScanFailed(int err) {
            ui.post(() -> { scanning = false; scanError = err; listener.onStateChanged(); listener.onScanStopped(); });
        }
    };

    // Android handles parallel connection attempts badly, so connect one at a time.
    @SuppressWarnings("MissingPermission")
    void connectNext() {
        if (connecting != null || toConnect.isEmpty() || !active) return;
        Lamp lamp = toConnect.poll();
        lamp.gatt = lamp.device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        if (lamp.gatt == null) {   // adapter off / no GATT client: no callback will ever come, retry like a drop
            retryLater(lamp);
            listener.onStateChanged();
            connectNext();
            return;
        }
        connecting = lamp;
        listener.onStateChanged();
    }

    /** Reconnect straight to the MAC we already know after RECONNECT_MS; no rescan needed. */
    private void retryLater(Lamp lamp) {
        ui.postDelayed(() -> {
            if (active && lamp.gatt == null && !toConnect.contains(lamp) && lamps.get(lamp.mac()) == lamp) {
                toConnect.add(lamp);
                connectNext();
            }
        }, RECONNECT_MS);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int st, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { g.discoverServices(); return; }
            if (state != BluetoothProfile.STATE_DISCONNECTED) return;
            g.close();
            ui.post(() -> {
                Lamp lamp = lamps.get(g.getDevice().getAddress());
                if (lamp == null) return;
                detach(lamp);
                if (connecting == lamp) connecting = null;
                listener.onDropped(lamp);
                listener.onStateChanged();
                connectNext();
                retryLater(lamp);
            });
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            if (st != BluetoothGatt.GATT_SUCCESS) {   // transient discovery error (129/133 right after a reconnect): treat as a drop, not as "not a lamp"
                onConnectionStateChange(g, st, BluetoothProfile.STATE_DISCONNECTED);
                return;
            }
            BluetoothGattService s = g.getService(SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(COMMAND);
            ui.post(() -> {
                Lamp lamp = lamps.get(g.getDevice().getAddress());
                if (connecting == lamp) connecting = null;
                if (lamp == null) { g.close(); connectNext(); return; }
                if (c == null) {   // some other "LED…" gadget
                    lamps.remove(lamp.mac());
                    g.close();
                    listener.onForgotten(lamp);
                    listener.onStateChanged();
                    connectNext();
                    return;
                }
                lamp.command = c;
                listener.onStateChanged();
                connectNext();
                ui.postDelayed(() -> {
                    if (!lamp.connected() || lamps.get(lamp.mac()) != lamp) return;   // dropped or released meanwhile
                    if (lamp.family != Proto.SMART) enqueue(lamp, passwordFrame());
                    listener.onReady(lamp);
                }, HANDSHAKE_DELAY_MS);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            ui.post(() -> {
                Lamp lamp = lamps.get(g.getDevice().getAddress());
                if (lamp != null) { lamp.inFlight = null; pump(lamp); }
            });
        }
    };

    /** The password check the official app sends after connecting (Mon=1 … Sun=7). */
    private static byte[] passwordFrame() {
        Calendar cal = Calendar.getInstance();
        int day = new int[]{7, 1, 2, 3, 4, 5, 6}[cal.get(Calendar.DAY_OF_WEEK) - 1];
        return Proto.password(day, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    // One write in flight per lamp; the next frame goes out when the lamp has acknowledged the previous one.
    void enqueue(Lamp lamp, byte[] frame) {
        lamp.queue.add(frame);
        pump(lamp);
    }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void pump(Lamp lamp) {
        if (lamp.inFlight != null || !lamp.connected() || lamp.gatt == null || lamp.queue.isEmpty()) return;
        byte[] v = lamp.queue.poll();
        lamp.inFlight = v;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = lamp.gatt.writeCharacteristic(lamp.command, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
        } else {
            lamp.command.setValue(v);
            lamp.command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            ok = lamp.gatt.writeCharacteristic(lamp.command);
        }
        if (!ok) { lamp.inFlight = null; return; }
        ui.postDelayed(() -> { if (lamp.inFlight == v) { lamp.inFlight = null; pump(lamp); } }, WRITE_TIMEOUT_MS);   // lost ack: move on
    }

    void rename(Lamp lamp, String person) {
        lamp.person = person;
        SharedPreferences.Editor e = prefs.edit();
        if (person == null) e.remove(lamp.mac()); else e.putString(lamp.mac(), person);
        e.apply();
    }

    /** Forget the BLE handles of a lamp whose connection is gone (closed or dropped). */
    private static void detach(Lamp lamp) {
        lamp.gatt = null;
        lamp.command = null;
        lamp.inFlight = null;
        lamp.queue.clear();
    }

    /** Disconnect and ignore the lamp on this phone until forgetReleased(); another phone can take it. */
    @SuppressWarnings("MissingPermission")
    void release(Lamp lamp) {
        ignored.add(lamp.mac());
        prefs.edit().putStringSet(IGNORE_KEY, new HashSet<>(ignored)).apply();
        if (lamp.gatt != null) lamp.gatt.close();
        detach(lamp);
        lamps.remove(lamp.mac());
        toConnect.remove(lamp);
        if (connecting == lamp) connecting = null;
        listener.onStateChanged();
        connectNext();
    }

    void forgetReleased() {
        ignored.clear();
        prefs.edit().remove(IGNORE_KEY).apply();
    }

    /** Close every connection and forget every lamp (the lamps keep their last colour). */
    @SuppressWarnings("MissingPermission")
    void closeAll() {
        stopScan();
        connecting = null;
        toConnect.clear();
        for (Lamp l : lamps.values()) { if (l.gatt != null) l.gatt.close(); detach(l); }
        lamps.clear();
    }
}
