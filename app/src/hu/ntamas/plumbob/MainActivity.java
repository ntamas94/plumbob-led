package hu.ntamas.plumbob;

import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.widget.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Controls every plumbob in range over BLE, only while the app is on screen.
 * Protocol: UTF-8 text written to CHAR, "green" / "rainbow" / "b:120" ...
 * All state below is touched on the UI thread only; GATT callbacks post back to it.
 */
public class MainActivity extends Activity {
    static final UUID SERVICE = UUID.fromString("b5a1e000-7c3b-4f2a-9a6e-5051ab0b0b01");
    static final UUID CHAR    = UUID.fromString("b5a1e000-7c3b-4f2a-9a6e-5051ab0b0b02");

    static class Bob {
        String name;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic ch;
        CheckBox box;
    }

    BluetoothAdapter adapter;
    final Map<String, Bob> bobs = new LinkedHashMap<>();       // by MAC address
    final ArrayDeque<BluetoothDevice> toConnect = new ArrayDeque<>();
    boolean connecting, scanning, visible;
    LinearLayout list;
    TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Plumbob");
        title.setTextSize(30);
        title.setTextColor(0xFF2ECC40);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), 0, 0, dp(8));
        root.addView(list);

        String[][] moods = {
            {"Boldog", "green", "#33CC33"}, {"Meh", "yellow", "#EECC33"},
            {"Fáradt", "orange", "#FF7733"}, {"Dühös", "red", "#EE3333"},
            {"Szivárvány", "rainbow", "#CC66FF"}, {"Szintlépés", "levelup", "#99CCFF"},
            {"Ki", "off", "#666666"},
        };
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (String[] m : moods) {
            Button btn = new Button(this);
            btn.setText(m[0]);
            btn.setTextSize(20);
            btn.setTextColor(Color.BLACK);
            btn.setAllCaps(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(m[2]));
            bg.setCornerRadius(dp(14));
            btn.setBackground(bg);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(72);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> send(m[1]));
            grid.addView(btn);
        }
        root.addView(grid);

        TextView bl = new TextView(this);
        bl.setText("Fényerő");
        bl.setTextColor(Color.LTGRAY);
        bl.setPadding(0, dp(16), 0, dp(4));
        root.addView(bl);

        SeekBar bright = new SeekBar(this);
        bright.setMax(255);
        bright.setProgress(120);
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { send("b:" + Math.max(10, s.getProgress())); }
        });
        root.addView(bright);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111111);
        scroll.addView(root);
        setContentView(scroll);
    }

    // Connect only while on screen: plumbobs keep their last mood after we let go.
    @Override protected void onStart() { super.onStart(); visible = true; start(); }
    @Override protected void onStop()  { super.onStop(); visible = false; disconnectAll(); }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void refreshStatus() {
        int n = 0;
        for (Bob b : bobs.values()) if (b.ch != null) n++;
        status.setText(n == 0 ? "Plumbobok keresése…" : n + " plumbob csatlakozva" + (scanning ? " – keresés tovább…" : ""));
    }

    String[] perms() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}
            : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    void start() {
        if (!visible) return;
        if (adapter == null) { status.setText("Nincs Bluetooth a telefonon"); return; }
        for (String p : perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { requestPermissions(perms(), 1); return; }
        }
        if (!adapter.isEnabled()) {
            status.setText("Kapcsold be a Bluetooth-t");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
            return;
        }
        scan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) { status.setText("Bluetooth engedély kell"); return; }
        start();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == 2) start();
    }

    @SuppressWarnings("MissingPermission")
    void scan() {
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null || scanning) return;
        scanning = true;
        refreshStatus();
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(Collections.singletonList(f), s, scanCb);
    }

    // Keeps scanning while the app is open, so a plumbob switched on later still shows up.
    final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            runOnUiThread(() -> {
                BluetoothDevice d = r.getDevice();
                if (!visible || bobs.containsKey(d.getAddress()) || toConnect.contains(d)) return;
                Bob b = new Bob();
                String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                b.name = n != null ? n : d.getAddress();
                bobs.put(d.getAddress(), b);
                toConnect.add(d);
                connectNext();
            });
        }

        @Override
        public void onScanFailed(int err) { runOnUiThread(() -> { scanning = false; status.setText("Keresési hiba: " + err); }); }
    };

    // Android handles parallel connection attempts badly, so connect one at a time.
    @SuppressWarnings("MissingPermission")
    void connectNext() {
        if (connecting || toConnect.isEmpty() || !visible) return;
        BluetoothDevice d = toConnect.poll();
        connecting = true;
        bobs.get(d.getAddress()).gatt = d.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int st, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { g.discoverServices(); return; }
            if (state != BluetoothProfile.STATE_DISCONNECTED) return;
            g.close();
            runOnUiThread(() -> {
                Bob b = bobs.remove(g.getDevice().getAddress());
                if (b != null && b.box != null) list.removeView(b.box);
                if (b != null && b.ch == null) connecting = false;  // failed while connecting: move on
                refreshStatus();
                connectNext();
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService s = g.getService(SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(CHAR);
            runOnUiThread(() -> {
                connecting = false;
                Bob b = bobs.get(g.getDevice().getAddress());
                if (b != null && c != null) {
                    b.ch = c;
                    b.box = new CheckBox(MainActivity.this);
                    b.box.setText(b.name);
                    b.box.setTextSize(18);
                    b.box.setTextColor(0xFF2ECC40);
                    b.box.setChecked(true);
                    list.addView(b.box);
                }
                refreshStatus();
                connectNext();
            });
        }
    };

    @SuppressWarnings({"MissingPermission", "deprecation"})
    void send(String cmd) {
        byte[] v = cmd.getBytes(StandardCharsets.UTF_8);
        int type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;  // no ack to wait for, so taps never queue up
        int sent = 0;
        for (Bob b : bobs.values()) {
            if (b.ch == null || !b.box.isChecked()) continue;
            if (Build.VERSION.SDK_INT >= 33) {
                b.gatt.writeCharacteristic(b.ch, v, type);
            } else {
                b.ch.setValue(v);
                b.ch.setWriteType(type);
                b.gatt.writeCharacteristic(b.ch);
            }
            sent++;
        }
        if (sent == 0) Toast.makeText(this, "Nincs kiválasztott, csatlakozott plumbob", Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("MissingPermission")
    void disconnectAll() {
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null)
            adapter.getBluetoothLeScanner().stopScan(scanCb);
        scanning = false;
        connecting = false;
        toConnect.clear();
        for (Bob b : bobs.values()) if (b.gatt != null) b.gatt.close();
        bobs.clear();
        list.removeAllViews();
    }
}
