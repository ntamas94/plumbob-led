package hu.ntamas.plumbob;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;

import java.util.*;

/**
 * Drives every "LED…" lamp in range (HOME ML 100 RGB SMART = "LED LAMP" app family) over BLE.
 * Protocol: 9-byte frames written to FFE1, see Proto and ../ledble_protokoll.md.
 * One GATT per lamp, one write in flight per lamp, direct reconnect by MAC after a drop (no rescan),
 * and the current mood is re-sent after every reconnect. All state lives on the UI thread.
 * The "Kinek" dropdown picks everyone or one lamp; the settings panel (gear) names lamps after their wearer
 * (stored by MAC in SharedPreferences), long-press a lamp there to make it blink.
 */
public class MainActivity extends Activity {
    static final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR    = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    static final int SCAN_MS = 10000, RECONNECT_MS = 1500, WRITE_TIMEOUT_MS = 1000;
    static final String[] PEOPLE = {"Dani", "Tamás", "Bianka", "Vivi"};

    // Plumbob colours: classic green -> yellow -> orange -> red, plus the Sims 3/4 extras (flirty pink, focused white, social turquoise).
    static final String[][] MOODS = {
        {"Kiváló", "green", "#33EE33"}, {"Semleges", "yellow", "#EECC33"},
        {"Feszült", "orange", "#FF7733"}, {"Kritikus", "red", "#EE3333"},
        {"Flörtölős", "pink", "#FF77C8"}, {"Fókuszált", "white", "#F4F4F4"},
        {"Társasági", "turquoise", "#33CCCC"}, {"Szivárvány", "rainbow", "#CC66FF"},
        {"Szintlépés", "levelup", "#99CCFF"}, {"Ki", "off", "#666666"},
    };
    // The controller's 23 built-in effects (official app's ble_mode list), mode number = 135 + index.
    static final String[] EFFECTS = {
        "Háromszín ugrás", "Hétszín ugrás", "Háromszín átmenet", "Hétszín átmenet",
        "Piros lélegzés", "Zöld lélegzés", "Kék lélegzés", "Sárga lélegzés", "Cián lélegzés", "Lila lélegzés", "Fehér lélegzés",
        "Piros-zöld átmenet", "Piros-kék átmenet", "Zöld-kék átmenet",
        "Hétszín villogás", "Piros villogás", "Zöld villogás", "Kék villogás", "Sárga villogás", "Cián villogás", "Lila villogás", "Fehér villogás",
        "Hétszín lélegzés",
    };
    static final Map<String, int[]> RGB = new HashMap<>();
    static {
        RGB.put("green", new int[]{0, 255, 0});   // strongest green the lamp can do (the Sims 4 playeractivecolor 110,199,31 looked washed out)
        RGB.put("yellow", new int[]{255, 210, 0});
        RGB.put("orange", new int[]{255, 80, 0});
        RGB.put("red", new int[]{255, 0, 0});
        RGB.put("pink", new int[]{255, 40, 120});
        RGB.put("white", new int[]{255, 255, 255});
        RGB.put("turquoise", new int[]{0, 200, 200});
        RGB.put("blue", new int[]{0, 0, 255});
    }

    static class Bob {
        String name, person;
        int fam;
        BluetoothDevice dev;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic ch;
        TextView lbl;
        LinearLayout row;
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        byte[] inFlight;
        int[] cur = {0, 0, 0};   // last colour sent, fades start from here
        int fadeId;              // bumping it cancels a running fade
    }

    BluetoothAdapter adapter;
    SharedPreferences names;   // MAC -> person
    final Map<String, Bob> bobs = new LinkedHashMap<>();   // by MAC
    final ArrayDeque<Bob> toConnect = new ArrayDeque<>();
    final Handler ui = new Handler();
    Bob connecting;
    boolean scanning, visible;
    LinearLayout list, settings;
    Spinner who;                                   // "Mindenkinek" + one entry per known lamp
    final List<String> whoMacs = new ArrayList<>(); // dropdown position - 1 -> MAC
    String whoSel;                                 // chosen lamp's MAC, null = everyone
    TextView status, seenView;
    final Set<String> seen = new TreeSet<>();   // every advertised name during this scan, for diagnosis
    String mood = "green";   // colour key, "rainbow", "off", or "fx:<mode number>"
    int brightness = 100, speed = 50;
    Spinner fx;
    Runnable levelup, pulser;
    boolean pulse;
    long pulseT0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        names = getSharedPreferences("names", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // Sims-style: sky-blue gradient, plumbob next to the title.
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER);
        ImageView gem = new ImageView(this);
        gem.setImageResource(getResources().getIdentifier("plumbob", "drawable", getPackageName()));
        gem.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        titleRow.addView(gem);
        TextView title = new TextView(this);
        title.setText("  Plumbob");
        title.setTextSize(34);
        title.setTextColor(Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setShadowLayer(6f, 0f, 3f, 0x99000000);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(title);
        Button gear = new Button(this);
        gear.setText("⚙");
        gear.setTextSize(24);
        gear.setOnClickListener(v -> settings.setVisibility(settings.getVisibility() == android.view.View.VISIBLE
            ? android.view.View.GONE : android.view.View.VISIBLE));
        titleRow.addView(gear);
        root.addView(titleRow);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), 0, 0, dp(8));

        // Dropdown: everyone, or one lamp.
        LinearLayout whoRow = new LinearLayout(this);
        whoRow.setOrientation(LinearLayout.HORIZONTAL);
        whoRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView whoLabel = new TextView(this);
        whoLabel.setText("Kinek:  ");
        whoLabel.setTextSize(18);
        whoLabel.setTextColor(Color.WHITE);
        whoRow.addView(whoLabel);
        who = new Spinner(this);
        who.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        who.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, android.view.View v, int pos, long id) {
                whoSel = pos <= 0 || pos > whoMacs.size() ? null : whoMacs.get(pos - 1);
            }
            public void onNothingSelected(AdapterView<?> a) { }
        });
        whoRow.addView(who);
        root.addView(whoRow);
        rebuildWho();

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (String[] m : MOODS) {
            Button btn = new Button(this);
            btn.setText(m[0]);
            btn.setTextSize(20);
            btn.setTextColor(Color.BLACK);
            btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            btn.setAllCaps(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(m[2]));
            bg.setCornerRadius(dp(26));
            bg.setStroke(dp(3), Color.WHITE);
            btn.setBackground(bg);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(72);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> { if ("levelup".equals(m[1])) levelUp(); else applyMood(m[1]); });
            grid.addView(btn);
        }
        root.addView(grid);

        // Built-in effects of the lamp + their speed.
        LinearLayout fxRow = new LinearLayout(this);
        fxRow.setOrientation(LinearLayout.HORIZONTAL);
        fxRow.setGravity(Gravity.CENTER_VERTICAL);
        fxRow.setPadding(0, dp(10), 0, 0);
        TextView fxLabel = new TextView(this);
        fxLabel.setText("Effekt:  ");
        fxLabel.setTextSize(18);
        fxLabel.setTextColor(Color.WHITE);
        fxRow.addView(fxLabel);
        String[] fxItems = new String[EFFECTS.length + 1];
        fxItems[0] = "– nincs (hangulat-szín) –";
        System.arraycopy(EFFECTS, 0, fxItems, 1, EFFECTS.length);
        ArrayAdapter<String> fxAd = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fxItems);
        fxAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fx = new Spinner(this);
        fx.setAdapter(fxAd);
        fx.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        fx.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            public void onItemSelected(AdapterView<?> a, android.view.View v, int pos, long id) {
                if (first) { first = false; return; }   // the initial selection is not a tap
                if (pos > 0) applyMood("fx:" + (135 + pos - 1));
            }
            public void onNothingSelected(AdapterView<?> a) { }
        });
        fxRow.addView(fx);
        root.addView(fxRow);

        TextView sl = new TextView(this);
        sl.setText("Effekt sebessége");
        sl.setTextColor(Color.WHITE);
        sl.setPadding(0, dp(10), 0, dp(4));
        root.addView(sl);
        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(100);
        speedBar.setProgress(speed);
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) {
                speed = s.getProgress();
                for (Bob x : selected()) enqueue(x, Proto.speed(x.fam, speed));
            }
        });
        root.addView(speedBar);

        LinearLayout blRow = new LinearLayout(this);
        blRow.setOrientation(LinearLayout.HORIZONTAL);
        blRow.setGravity(Gravity.CENTER_VERTICAL);
        blRow.setPadding(0, dp(16), 0, dp(4));
        TextView bl = new TextView(this);
        bl.setText("Fényerő");
        bl.setTextColor(Color.WHITE);
        bl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        blRow.addView(bl);
        Switch pulseSw = new Switch(this);   // Sims-style slow breathing of the brightness
        pulseSw.setText("Lüktetés  ");
        pulseSw.setTextColor(Color.WHITE);
        pulseSw.setOnCheckedChangeListener((sw, on) -> setPulse(on));
        blRow.addView(pulseSw);
        root.addView(blRow);

        SeekBar bright = new SeekBar(this);
        bright.setMax(100);
        bright.setProgress(brightness);
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) {
                brightness = Math.max(1, s.getProgress());
                for (Bob x : selected()) enqueue(x, Proto.brightness(x.fam, brightness));
            }
        });
        root.addView(bright);

        // Settings panel (gear): which lamp belongs to whom, rescan, diagnostics. Hidden by default.
        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setVisibility(android.view.View.GONE);
        settings.setPadding(0, dp(16), 0, 0);
        TextView sTitle = new TextView(this);
        sTitle.setText("Lámpák – kié melyik?");
        sTitle.setTextSize(20);
        sTitle.setTextColor(Color.WHITE);
        sTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sTitle.setPadding(0, 0, 0, dp(6));
        settings.addView(sTitle);
        settings.addView(list);

        Button rescan = new Button(this);
        rescan.setText("Keresés újra");
        rescan.setAllCaps(false);
        rescan.setOnClickListener(v -> scan());
        settings.addView(rescan);

        Button upd = new Button(this);
        upd.setText("Frissítés keresése (v" + versionCode() + ")");
        upd.setAllCaps(false);
        upd.setOnClickListener(v -> checkUpdate());
        settings.addView(upd);

        TextView hint = new TextView(this);
        hint.setText("Hosszú nyomás a lámpa nevén: villan, hogy lásd, melyik az. „Kié?”: név a lámpához, a telefon megjegyzi.");
        hint.setTextColor(0xCCFFFFFF);
        hint.setPadding(0, dp(8), 0, 0);
        settings.addView(hint);

        seenView = new TextView(this);
        seenView.setTextColor(0x99FFFFFF);
        seenView.setTextSize(12);
        seenView.setPadding(0, dp(8), 0, 0);
        settings.addView(seenView);
        root.addView(settings);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF5CC8FF, 0xFF1E78CC, 0xFF0C3F7A}));
        scroll.addView(root);
        setContentView(scroll);
    }

    // Connect only while on screen: the lamps keep their last colour after we let go.
    @Override protected void onStart() { super.onStart(); visible = true; start(); if (pulse) setPulse(true); }
    @Override protected void onStop()  { super.onStop(); visible = false; disconnectAll(); }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    List<Bob> selected() {
        List<Bob> r = new ArrayList<>();
        for (Bob b : bobs.values()) if (b.ch != null && wanted(b)) r.add(b);
        return r;
    }

    void refreshStatus() {
        int on = 0;
        for (Bob b : bobs.values()) if (b.ch != null) on++;
        String s = on + "/" + bobs.size() + " lámpa csatlakozva";
        if (scanning) s += " – keresés…";
        else if (connecting != null) s += " – csatlakozás: " + connecting.name;
        status.setText(bobs.isEmpty() && scanning ? "Lámpák keresése…" : s);
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

    // Scan in 10 s bursts only: scanning next to live connections is what makes these cheap controllers drop.
    @SuppressWarnings("MissingPermission")
    void scan() {
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null || scanning || !visible) return;
        scanning = true;
        seen.clear();
        seenView.setText("");
        refreshStatus();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(null, s, scanCb);   // no UUID filter: the lamp does not advertise FFE0, we filter on the "LED" name like the official app
        ui.postDelayed(this::stopScan, SCAN_MS);
    }

    @SuppressWarnings("MissingPermission")
    void stopScan() {
        if (!scanning) return;
        scanning = false;
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc != null) sc.stopScan(scanCb);
        refreshStatus();
        if (bobs.isEmpty()) seenView.setText(seen.isEmpty()
            ? "A keresés semmit nem látott: Bluetooth/helymeghatározás be? „Közeli eszközök” engedély?"
            : "Nincs LED… nevű eszköz. Látott nevek: " + seen);
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            runOnUiThread(() -> {
                BluetoothDevice d = r.getDevice();
                String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (n == null) n = d.getName();   // cached/bonded name as fallback
                seen.add(n == null ? "(névtelen " + d.getAddress().substring(12) + ")" : n);
                seenView.setText("Látott: " + seen);
                if (n == null || !n.toUpperCase().startsWith("LED") || !visible || bobs.containsKey(d.getAddress())) return;
                Bob b = new Bob();
                b.name = n;
                b.dev = d;
                b.fam = Proto.family(n);
                b.person = names.getString(d.getAddress(), null);
                bobs.put(d.getAddress(), b);
                toConnect.add(b);
                connectNext();
            });
        }

        @Override
        public void onScanFailed(int err) { runOnUiThread(() -> { scanning = false; status.setText("Keresési hiba: " + err); }); }
    };

    // Android handles parallel connection attempts badly, so connect one at a time.
    @SuppressWarnings("MissingPermission")
    void connectNext() {
        if (connecting != null || toConnect.isEmpty() || !visible) return;
        connecting = toConnect.poll();
        connecting.gatt = connecting.dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        refreshStatus();
    }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int st, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { g.discoverServices(); return; }
            if (state != BluetoothProfile.STATE_DISCONNECTED) return;
            g.close();
            runOnUiThread(() -> {
                String mac = g.getDevice().getAddress();
                Bob b = bobs.get(mac);
                if (b == null) return;
                b.gatt = null;
                b.ch = null;
                b.inFlight = null;
                b.queue.clear();
                if (b.lbl != null) b.lbl.setTextColor(0x88FFFFFF);
                if (connecting == b) connecting = null;
                refreshStatus();
                connectNext();
                // Reconnect straight to the MAC we already know; no rescan needed.
                ui.postDelayed(() -> {
                    if (visible && b.gatt == null && !toConnect.contains(b) && bobs.get(mac) == b) {
                        toConnect.add(b);
                        connectNext();
                    }
                }, RECONNECT_MS);
            });
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService s = g.getService(SERVICE);
            BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(CHAR);
            runOnUiThread(() -> {
                Bob b = bobs.get(g.getDevice().getAddress());
                if (connecting == b) connecting = null;
                if (b == null) { g.close(); connectNext(); return; }
                if (c == null) {   // some other "LED…" gadget: forget it
                    bobs.remove(g.getDevice().getAddress());
                    g.close();
                    if (b.row != null) list.removeView(b.row);
                    rebuildWho();
                    refreshStatus();
                    connectNext();
                    return;
                }
                b.ch = c;
                if (b.lbl == null) addRow(b);
                b.lbl.setTextColor(Color.WHITE);
                refreshStatus();
                connectNext();
                // Same handshake the official app does 300 ms after connecting, then bring the lamp to the current mood.
                ui.postDelayed(() -> {
                    if (b.ch == null) return;
                    if (b.fam != Proto.SMART) {
                        Calendar cal = Calendar.getInstance();
                        int day = new int[]{7, 1, 2, 3, 4, 5, 6}[cal.get(Calendar.DAY_OF_WEEK) - 1];
                        enqueue(b, Proto.password(day, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)));
                    }
                    if (levelup == null) moodFrames(b, mood);
                }, 300);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            runOnUiThread(() -> {
                Bob b = bobs.get(g.getDevice().getAddress());
                if (b != null) { b.inFlight = null; pump(b); }
            });
        }
    };

    String label(Bob b) { return b.person == null ? b.name : b.person + "  (" + b.name + ")"; }

    int versionCode() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    // GitHub release tag "v<versionCode>": newer than ours -> download and hand to the system installer.
    void checkUpdate() {
        Toast.makeText(this, "Frissítés keresése…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                int latest = Updater.latestCode();
                if (latest < 0) msg = "Nincs még kiadás a GitHubon.";
                else if (latest <= versionCode()) msg = "Ez a legfrissebb (v" + latest + ").";
                else {
                    runOnUiThread(() -> Toast.makeText(this, "Új verzió: v" + latest + " – letöltés…", Toast.LENGTH_LONG).show());
                    Updater.install(this);
                    msg = "Letöltve, a telepítést a telefon kérdezi.";
                }
            } catch (Exception e) {
                msg = "Frissítés hiba: " + e.getMessage();
            }
            String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** Does the dropdown choice cover this lamp? */
    boolean wanted(Bob b) { return whoSel == null || whoSel.equals(b.dev.getAddress()); }

    // Refill the dropdown from the known lamps, keeping the current choice if that lamp is still around.
    void rebuildWho() {
        whoMacs.clear();
        List<String> items = new ArrayList<>();
        items.add("Mindenkinek");
        for (Bob b : bobs.values()) {
            if (b.lbl == null) continue;
            whoMacs.add(b.dev.getAddress());
            items.add(label(b));
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        int keep = whoSel == null ? -1 : whoMacs.indexOf(whoSel);
        if (keep < 0) whoSel = null;
        who.setAdapter(ad);
        who.setSelection(keep < 0 ? 0 : keep + 1, false);
    }

    // Settings row: lamp label (long-press = blink) + "Kié?" button that names the lamp after its wearer.
    void addRow(Bob b) {
        b.row = new LinearLayout(this);
        b.row.setOrientation(LinearLayout.HORIZONTAL);
        b.row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable panel = new GradientDrawable();   // translucent white card, Sims-panel style
        panel.setColor(0x33FFFFFF);
        panel.setCornerRadius(dp(14));
        b.row.setBackground(panel);
        b.row.setPadding(dp(8), dp(2), dp(4), dp(2));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(3), 0, dp(3));
        b.row.setLayoutParams(rlp);
        b.lbl = new TextView(this);
        b.lbl.setText(label(b));
        b.lbl.setTextSize(18);
        b.lbl.setPadding(dp(4), dp(10), 0, dp(10));
        b.lbl.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        b.lbl.setOnLongClickListener(v -> { identify(b); return true; });
        b.row.addView(b.lbl);
        Button whoBtn = new Button(this);
        whoBtn.setText("Kié?");
        whoBtn.setAllCaps(false);
        whoBtn.setOnClickListener(v -> {
            String[] items = Arrays.copyOf(PEOPLE, PEOPLE.length + 1);
            items[PEOPLE.length] = "(senkié)";
            new AlertDialog.Builder(this).setTitle("Kié ez a lámpa? " + b.name).setItems(items, (d, i) -> {
                b.person = i < PEOPLE.length ? PEOPLE[i] : null;
                SharedPreferences.Editor e = names.edit();
                if (b.person == null) e.remove(b.dev.getAddress()); else e.putString(b.dev.getAddress(), b.person);
                e.apply();
                b.lbl.setText(label(b));
                rebuildWho();
            }).show();
        });
        b.row.addView(whoBtn);
        list.addView(b.row);
        rebuildWho();
    }

    // One write in flight per lamp; the next frame goes out when the lamp has acknowledged the previous one.
    void enqueue(Bob b, byte[] frame) {
        b.queue.add(frame);
        pump(b);
    }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    void pump(Bob b) {
        if (b.inFlight != null || b.ch == null || b.gatt == null || b.queue.isEmpty()) return;
        byte[] v = b.queue.poll();
        b.inFlight = v;
        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            ok = b.gatt.writeCharacteristic(b.ch, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
        } else {
            b.ch.setValue(v);
            b.ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            ok = b.gatt.writeCharacteristic(b.ch);
        }
        if (!ok) { b.inFlight = null; return; }
        ui.postDelayed(() -> { if (b.inFlight == v) { b.inFlight = null; pump(b); } }, WRITE_TIMEOUT_MS);  // lost ack: move on
    }

    void moodFrames(Bob b, String m) {
        b.fadeId++;
        if ("off".equals(m)) { enqueue(b, Proto.off(b.fam)); b.cur = new int[]{0, 0, 0}; return; }
        enqueue(b, Proto.on(b.fam));
        if (!pulse) enqueue(b, Proto.brightness(b.fam, brightness));
        if ("rainbow".equals(m)) enqueue(b, Proto.mode(b.fam, b.fam == Proto.SMART ? 1 : 138));   // 138 = seven-colour gradient
        else if (m.startsWith("fx:")) { enqueue(b, Proto.mode(b.fam, Integer.parseInt(m.substring(3)))); enqueue(b, Proto.speed(b.fam, speed)); }
        else fadeTo(b, RGB.get(m));
    }

    static int lerp(int a, int b, float t) { return Math.round(a + (b - a) * t); }

    // Sims-style blend: slide from the lamp's current colour to the target over ~1 s instead of snapping.
    void fadeTo(Bob b, int[] to) {
        int[] from = b.cur.clone();
        final int id = ++b.fadeId;
        for (int i = 1; i <= 10; i++) {
            float t = i / 10f;
            int[] c = {lerp(from[0], to[0], t), lerp(from[1], to[1], t), lerp(from[2], to[2], t)};
            ui.postDelayed(() -> {
                if (b.fadeId != id || b.ch == null) return;
                b.cur = c;
                enqueue(b, Proto.rgb(b.fam, c[0], c[1], c[2]));
            }, i * 100L);
        }
    }

    // Slow breathing: brightness swings between 40 % and 100 % of the slider over 3 s, on every connected lamp.
    void setPulse(boolean on) {
        pulse = on;
        if (pulser != null) ui.removeCallbacks(pulser);
        if (!on) { for (Bob b : bobs.values()) if (b.ch != null) enqueue(b, Proto.brightness(b.fam, brightness)); return; }
        pulseT0 = System.currentTimeMillis();
        pulser = new Runnable() {
            public void run() {
                double ph = (System.currentTimeMillis() - pulseT0) / 3000.0 * 2 * Math.PI;
                int pct = Math.max(1, (int) Math.round(brightness * (0.7 + 0.3 * Math.sin(ph))));
                for (Bob b : bobs.values()) if (b.ch != null && b.queue.isEmpty()) enqueue(b, Proto.brightness(b.fam, pct));
                ui.postDelayed(this, 150);
            }
        };
        pulser.run();
    }

    void applyMood(String m) {
        mood = m;
        if (!m.startsWith("fx:") && fx.getSelectedItemPosition() != 0) fx.setSelection(0, false);   // a mood colour ends the effect
        if (levelup != null) { ui.removeCallbacks(levelup); levelup = null; }
        List<Bob> sel = selected();
        if (sel.isEmpty()) Toast.makeText(this, "Nincs kiválasztott, csatlakozott lámpa", Toast.LENGTH_SHORT).show();
        for (Bob b : sel) moodFrames(b, m);
    }

    // White/green flashing for 3 s, then back to happy green. Done app-side so the lamp ends in a known colour.
    void levelUp() {
        if (levelup != null) ui.removeCallbacks(levelup);
        mood = "green";
        for (Bob b : selected()) { b.fadeId++; enqueue(b, Proto.on(b.fam)); if (!pulse) enqueue(b, Proto.brightness(b.fam, brightness)); }
        levelup = new Runnable() {
            int step = 0;
            public void run() {
                int[] c = RGB.get(step % 2 == 0 ? "white" : "green");
                for (Bob b : selected()) { b.cur = c; enqueue(b, Proto.rgb(b.fam, c[0], c[1], c[2])); }
                if (++step < 12) ui.postDelayed(this, 250);
                else { levelup = null; applyMood("green"); }
            }
        };
        levelup.run();
    }

    // Blink one lamp white/blue so you can tell which checkbox is which costume, then put its mood back.
    void identify(Bob b) {
        if (b.ch == null) return;
        b.fadeId++;
        enqueue(b, Proto.on(b.fam));
        for (int i = 0; i < 6; i++) {
            int[] c = RGB.get(i % 2 == 0 ? "white" : "blue");
            ui.postDelayed(() -> { b.cur = c; enqueue(b, Proto.rgb(b.fam, c[0], c[1], c[2])); }, i * 300L);
        }
        ui.postDelayed(() -> moodFrames(b, mood), 1800);
    }

    @SuppressWarnings("MissingPermission")
    void disconnectAll() {
        stopScan();
        ui.removeCallbacksAndMessages(null);
        levelup = null;
        connecting = null;
        toConnect.clear();
        for (Bob b : bobs.values()) if (b.gatt != null) b.gatt.close();
        bobs.clear();
        list.removeAllViews();
        rebuildWho();
    }
}
