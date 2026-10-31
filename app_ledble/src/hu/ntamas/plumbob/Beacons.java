package hu.ntamas.plumbob;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Phone-to-phone messages as short BLE advertisements (no network needed):
 * invitations to a group animation, accepts, life-point attacks and their acknowledgements.
 * Payload: {kind, idx, nonceHi, nonceLo, wearerMask, r, g, b, senderName…} under manufacturer id COMPANY.
 */
final class Beacons {
    static final int COMPANY = 0xFFFF;
    static final int INVITE = 1, ACCEPT = 2, ATTACK = 3, ACK = 4;

    static final class Msg {
        final int kind, idx, nonce, mask;
        final int[] colour;
        final String from;
        Msg(int kind, int idx, int nonce, int mask, int[] colour, String from) {
            this.kind = kind; this.idx = idx; this.nonce = nonce; this.mask = mask; this.colour = colour; this.from = from;
        }
    }

    interface Listener {
        void onBeacon(Msg m);          // on the UI thread
        void onSendFailed(int error);  // on the UI thread
    }

    private final BluetoothAdapter adapter;
    private final Handler ui;
    private final Listener listener;
    private final AdvertiseCallback[] advertisers = new AdvertiseCallback[ACK + 1];   // one per kind, so kinds do not cancel each other
    private boolean scanning;

    Beacons(BluetoothAdapter adapter, Handler ui, Listener listener) {
        this.adapter = adapter;
        this.ui = ui;
        this.listener = listener;
        for (int k = INVITE; k <= ACK; k++) {
            advertisers[k] = new AdvertiseCallback() {
                @Override public void onStartFailure(int err) { ui.post(() -> listener.onSendFailed(err)); }
            };
        }
    }

    static byte[] encode(int kind, int idx, int nonce, int mask, int[] colour, String from) {
        byte[] name = from.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[8 + Math.min(name.length, 12)];
        p[0] = (byte) kind; p[1] = (byte) idx; p[2] = (byte) (nonce >> 8); p[3] = (byte) nonce; p[4] = (byte) mask;
        p[5] = (byte) colour[0]; p[6] = (byte) colour[1]; p[7] = (byte) colour[2];
        System.arraycopy(name, 0, p, 8, p.length - 8);
        return p;
    }

    /** Null when the payload is not one of ours. */
    static Msg decode(byte[] p) {
        if (p == null || p.length < 8 || p[0] < INVITE || p[0] > ACK) return null;
        int nonce = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        int[] colour = {p[5] & 0xFF, p[6] & 0xFF, p[7] & 0xFF};
        return new Msg(p[0], p[1] & 0xFF, nonce, p[4] & 0xFF, colour, new String(p, 8, p.length - 8, StandardCharsets.UTF_8));
    }

    /** Advertise one message for ms milliseconds. False when this phone cannot advertise. */
    @SuppressWarnings("MissingPermission")
    boolean send(int kind, int idx, int nonce, int mask, int[] colour, String from, int ms) {
        BluetoothLeAdvertiser adv = adapter.getBluetoothLeAdvertiser();
        if (adv == null) return false;
        AdvertiseSettings s = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).setTimeout(ms).build();
        AdvertiseData d = new AdvertiseData.Builder().setIncludeDeviceName(false)
            .addManufacturerData(COMPANY, encode(kind, idx, nonce, mask, colour, from)).build();
        try { adv.stopAdvertising(advertisers[kind]); } catch (Exception ignored) { }
        adv.startAdvertising(s, d, advertisers[kind]);
        return true;
    }

    // Low latency while the app is up: beacons last seconds and a low-power scan listens only 0.5 s in 5.
    @SuppressWarnings("MissingPermission")
    void startScan() {
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null || scanning) return;
        ScanFilter f = new ScanFilter.Builder().setManufacturerData(COMPANY, new byte[0]).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(Collections.singletonList(f), s, scanCb);
        scanning = true;
    }

    @SuppressWarnings("MissingPermission")
    void stopScan() {
        if (!scanning) return;
        scanning = false;
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc != null) sc.stopScan(scanCb);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            if (r.getScanRecord() == null) return;
            Msg m = decode(r.getScanRecord().getManufacturerSpecificData(COMPANY));
            if (m != null) ui.post(() -> listener.onBeacon(m));
        }
    };

    /** Self-check: encode/decode round trip. Run: java -cp build/classes hu.ntamas.plumbob.Beacons */
    public static void main(String[] a) {
        Msg m = decode(encode(ATTACK, 2, 0xBEEF, 0b0110, new int[]{255, 40, 120}, "Tamás"));
        if (m == null || m.kind != ATTACK || m.idx != 2 || m.nonce != 0xBEEF || m.mask != 6 || m.colour[2] != 120 || !"Tamás".equals(m.from))
            throw new AssertionError("round trip");
        if (decode(new byte[]{9, 0, 0, 0, 0, 0, 0, 0}) != null || decode(null) != null) throw new AssertionError("foreign payloads are ignored");
        if (encode(INVITE, 0, 0, 0, new int[]{0, 0, 0}, "Hosszúnévvalakinek").length > 20) throw new AssertionError("fits a legacy advertisement");
        System.out.println("Beacons OK");
    }
}
