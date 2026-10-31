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
    // App-side animations (colour frames pushed from the phone), {name, button colour}; order = case index in animFrame().
    static final String[][] ANIMS = {
        {"Szívverés", "#EE3333"}, {"Tűz", "#FF8800"}, {"Sziréna", "#6688FF"}, {"Villám", "#DDDDEE"}, {"Disco", "#CC66FF"},
        {"Szellem", "#BBEEFF"}, {"Halloween", "#FF9933"}, {"WooHoo", "#FF77C8"}, {"Zombi", "#66AA33"},
        {"Szivárvány-hullám", "#FFCC66"}, {"Gyertya", "#FFD27F"}, {"Boszorkány", "#99FF66"}, {"Vámpír", "#AA2222"},
        {"Alien", "#66FFCC"}, {"Simoleon", "#FFD700"}, {"Strobo", "#FFFFFF"}, {"Karácsony", "#FF6666"},
        {"Magyar zászló", "#DDFFDD"}, {"Tévé", "#AACCFF"}, {"Éhező Sim", "#CCDD55"}, {"Kaszás", "#999999"},
        // From MULTI on: per-lamp frames in wearer order (Dani, Tamás, Bianka, Vivi), see multiFrame(). With the lamps
        // spread over several phones the step comes from the wall clock, so everyone pressing the same button stays in sync.
        {"Futófény (csoport)", "#FFFFAA"}, {"Pingpong (csoport)", "#AAFFFF"}, {"Hullám (csoport)", "#FFAAFF"},
        {"Forró krumpli (játék)", "#FFB347"}, {"Szerencsekerék (játék)", "#FFD700"}, {"Tűzijáték (csoport)", "#FF99CC"},
        {"Zene (mikrofon)", "#FFEE88"},
    };
    static final int MULTI = 21, MIC = 27;
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
        int hp = 100;            // life points of this lamp's wearer
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
    String mood = "green";   // colour key, "rainbow", "off", "fx:<mode number>" or "anim:<index>"
    int brightness = 100, speed = 50;
    LinearLayout[] pages;
    Button[] tabs;
    Runnable anim;
    int animStep, animWait;
    int epoch;   // bumped on every mood/animation switch; delayed lambdas from the previous one check it and bail
    int hpMax = 100;
    TextView hpView;
    final ArrayDeque<Integer> seenAttacks = new ArrayDeque<>(), seenAcks = new ArrayDeque<>();
    int[] base = {0, 255, 0};   // last static colour, used by the chase animations
    final Random rnd = new Random();
    SeekBar moodBar;
    Runnable decay;
    boolean shake = true;
    long lastShake;
    volatile boolean micOn;
    Thread micThread;
    Runnable levelup, pulser;
    boolean pulse;
    long pulseT0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        names = getSharedPreferences("names", MODE_PRIVATE);
        ignore.addAll(names.getStringSet("ignore", Collections.emptySet()));
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

        // Tabs: one page visible at a time; "Kinek", brightness and pulse stay outside the pages.
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, dp(8), 0, dp(4));
        String[] tabNames = {"Hangulat", "Effekt", "Anim", "Életerő", "Beáll."};
        pages = new LinearLayout[tabNames.length];
        tabs = new Button[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            Button t = new Button(this);
            t.setText(tabNames[i]);
            t.setAllCaps(false);
            t.setTextSize(13);
            t.setSingleLine(true);
            t.setPadding(0, 0, 0, 0);
            t.setMinWidth(0);
            t.setMinimumWidth(0);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tlp.setMargins(dp(2), 0, dp(2), 0);
            t.setLayoutParams(tlp);
            t.setOnClickListener(v -> showPage(idx));
            tabs[i] = t;
            tabBar.addView(t);
        }
        root.addView(tabBar);

        // Page 0: plumbob scale slider (green -> yellow -> orange -> red) + mood buttons.
        TextView ml = new TextView(this);
        ml.setText("Plumbob-skála:  Kiváló  ◀━━━▶  Kritikus");
        ml.setTextColor(Color.WHITE);
        ml.setGravity(Gravity.CENTER);
        ml.setPadding(0, dp(6), 0, 0);
        pages[0].addView(ml);
        android.widget.FrameLayout slideBox = new android.widget.FrameLayout(this);
        android.view.View bar = new android.view.View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF00FF00, 0xFFFFD200, 0xFFFF5000, 0xFFFF0000});
        g.setCornerRadius(dp(10));
        bar.setBackground(g);
        android.widget.FrameLayout.LayoutParams blp2 = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(18), Gravity.CENTER_VERTICAL);
        blp2.setMargins(dp(16), 0, dp(16), 0);
        bar.setLayoutParams(blp2);
        slideBox.addView(bar);
        moodBar = new SeekBar(this);
        moodBar.setMax(100);
        moodBar.setProgress(0);
        moodBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        moodBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        moodBar.setPadding(dp(16), dp(14), dp(16), dp(14));
        moodBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) slideMood(p, false); }
            public void onStartTrackingTouch(SeekBar s) { slideStart(); }
            public void onStopTrackingTouch(SeekBar s) { slideMood(s.getProgress(), true); }
        });
        slideBox.addView(moodBar);
        pages[0].addView(slideBox);

        Switch decaySw = new Switch(this);   // Sims needs: the scale slides towards red by itself, "Kiváló" feeds the Sim
        decaySw.setText("Lecsengés (10 perc alatt zöld → piros)  ");
        decaySw.setTextColor(Color.WHITE);
        decaySw.setPadding(0, dp(4), 0, dp(4));
        decaySw.setOnCheckedChangeListener((sw, on) -> setDecay(on));
        pages[0].addView(decaySw);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (String[] m : MOODS) grid.addView(pill(m[0], m[2], 20, dp(72), () -> {
            if ("levelup".equals(m[1])) levelUp(); else applyMood(m[1]);
            int pos = "green".equals(m[1]) ? 0 : "yellow".equals(m[1]) ? 33 : "orange".equals(m[1]) ? 66 : "red".equals(m[1]) ? 100 : -1;
            if (pos >= 0) moodBar.setProgress(pos);   // keep the scale (and the decay) in step with the classic moods
        }));
        pages[0].addView(grid);

        // Page 1: the lamp's built-in effects + their speed.
        GridLayout fxGrid = new GridLayout(this);
        fxGrid.setColumnCount(2);
        for (int i = 0; i < EFFECTS.length; i++) {
            final int mode = 135 + i;
            fxGrid.addView(pill(EFFECTS[i], "#BBDDFF", 15, dp(56), () -> applyMood("fx:" + mode)));
        }
        pages[1].addView(fxGrid);
        TextView sl = new TextView(this);
        sl.setText("Effekt sebessége");
        sl.setTextColor(Color.WHITE);
        sl.setPadding(0, dp(10), 0, dp(4));
        pages[1].addView(sl);
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
        pages[1].addView(speedBar);

        // Page 2: animations driven by the phone.
        GridLayout anGrid = new GridLayout(this);
        anGrid.setColumnCount(2);
        for (int i = 0; i < ANIMS.length; i++) {
            final int a = i;
            Button ab = pill(ANIMS[i][0], ANIMS[i][1], 15, dp(56), () -> startAnim(a));
            if (a >= MULTI && a < MIC) ab.setOnLongClickListener(v -> { sendInvite(a); return true; });
            anGrid.addView(ab);
        }
        pages[2].addView(anGrid);
        TextView anHint = new TextView(this);
        anHint.setText("Sima nyomás: a csoport-animáció a saját lámpáid közt fut (2 lámpa = oda-vissza). Hosszú nyomás: meghívást küld a közeli telefonoknak (Bluetooth); akik elfogadják, azoknál is elindul, óra-szinkronban, a Kié?-nevek sorrendjében.");
        anHint.setTextColor(0xCCFFFFFF);
        anHint.setTextSize(13);
        anHint.setPadding(0, dp(6), 0, 0);
        pages[2].addView(anHint);

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

        // Page 3: life points for board-game nights. The lamp shows HP as plumbob colour; attacks arrive over Bluetooth.
        hpView = new TextView(this);
        hpView.setTextSize(40);
        hpView.setTextColor(Color.WHITE);
        hpView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hpView.setGravity(Gravity.CENTER);
        hpView.setShadowLayer(6f, 0f, 3f, 0x99000000);
        hpView.setPadding(0, dp(8), 0, dp(4));
        pages[3].addView(hpView);
        GridLayout hpGrid = new GridLayout(this);
        hpGrid.setColumnCount(2);
        hpGrid.addView(pill("−1", "#FF7766", 24, dp(72), () -> addHp(-1)));
        hpGrid.addView(pill("+1", "#77EE77", 24, dp(72), () -> addHp(1)));
        hpGrid.addView(pill("−5", "#EE4444", 20, dp(56), () -> addHp(-5)));
        hpGrid.addView(pill("+5", "#44CC44", 20, dp(56), () -> addHp(5)));
        hpGrid.addView(pill("−10", "#CC2222", 20, dp(56), () -> addHp(-10)));
        hpGrid.addView(pill("+10", "#22AA22", 20, dp(56), () -> addHp(10)));
        hpGrid.addView(pill("Halott (0)", "#999999", 16, dp(56), () -> addHp(-100000)));
        hpGrid.addView(pill("Teli", "#BBDDFF", 16, dp(56), () -> addHp(100000)));
        pages[3].addView(hpGrid);
        TextView maxL = new TextView(this);
        maxL.setText("Maximum (mindenkinek, és feltölt)");
        maxL.setTextColor(Color.WHITE);
        maxL.setPadding(0, dp(8), 0, 0);
        pages[3].addView(maxL);
        GridLayout maxGrid = new GridLayout(this);
        maxGrid.setColumnCount(5);
        for (int m : new int[]{10, 20, 30, 50, 100}) maxGrid.addView(pill("" + m, "#DDDDDD", 14, dp(44), () -> { hpMax = m; for (Bob x : bobs.values()) x.hp = m; showHp(true); }));
        pages[3].addView(maxGrid);
        TextView hpHint = new TextView(this);
        hpHint.setText("A gombok a „Kinek” szerint kiválasztott lámpá(k)ra hatnak. Életerő lámpánként (személyenként) számolódik.");
        hpHint.setTextColor(0xCCFFFFFF);
        hpHint.setTextSize(13);
        hpHint.setPadding(0, dp(6), 0, 0);
        pages[3].addView(hpHint);
        TextView atkL = new TextView(this);
        atkL.setText("Támadás (−1 HP a másik telefonján, Bluetooth-on)");
        atkL.setTextColor(Color.WHITE);
        atkL.setPadding(0, dp(10), 0, 0);
        pages[3].addView(atkL);
        GridLayout atkGrid = new GridLayout(this);
        atkGrid.setColumnCount(4);
        for (int i = 0; i < PEOPLE.length; i++) { final int t = i; atkGrid.addView(pill(PEOPLE[i], "#FFAA55", 14, dp(48), () -> sendAttack(t))); }
        pages[3].addView(atkGrid);
        showHp(false);

        // Page 4: settings – which lamp belongs to whom, rescan, update, diagnostics.
        settings = pages[4];
        settings.setPadding(0, dp(8), 0, 0);
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

        Button unIgnore = new Button(this);
        unIgnore.setText("Elengedettek vissza");
        unIgnore.setAllCaps(false);
        unIgnore.setOnClickListener(v -> { ignore.clear(); names.edit().remove("ignore").apply(); scan(); Toast.makeText(this, "Minden lámpa újra felvehető", Toast.LENGTH_SHORT).show(); });
        settings.addView(unIgnore);

        Button upd = new Button(this);
        upd.setText("Frissítés keresése (v" + versionCode() + ")");
        upd.setAllCaps(false);
        upd.setOnClickListener(v -> checkUpdate());
        settings.addView(upd);

        // White balance: RGB strings mix white differently, let the wearer pick the one that looks snow-white on theirs.
        TextView wl = new TextView(this);
        wl.setText("Fehér (Fókuszált, fehér villanások)");
        wl.setTextColor(Color.WHITE);
        wl.setPadding(0, dp(12), 0, dp(4));
        settings.addView(wl);
        GridLayout wGrid = new GridLayout(this);
        wGrid.setColumnCount(3);
        String[][] whites = {{"Hófehér", "200,230,255"}, {"Tiszta", "255,255,255"}, {"Meleg", "255,225,180"}};
        for (String[] w : whites) {
            Button wb = pill(w[0], "#F4F4F4", 14, dp(48), () -> {
                names.edit().putString("white", w[1]).apply();
                loadWhite();
                if ("white".equals(mood)) applyMood("white");
                Toast.makeText(this, "Fehér: " + w[0], Toast.LENGTH_SHORT).show();
            });
            wGrid.addView(wb);
        }
        settings.addView(wGrid);
        loadWhite();

        Switch shakeSw = new Switch(this);
        shakeSw.setText("Rázásra véletlen hangulat  ");
        shakeSw.setTextColor(Color.WHITE);
        shakeSw.setChecked(shake);
        shakeSw.setPadding(0, dp(10), 0, 0);
        shakeSw.setOnCheckedChangeListener((sw, on) -> shake = on);
        settings.addView(shakeSw);

        TextView hint = new TextView(this);
        hint.setText("Hosszú nyomás a lámpa nevén: villan, hogy lásd, melyik az. „Kié?”: név a lámpához, a telefon megjegyzi. Animáció vagy Lüktetés közben az app háttérben is megy (értesítés a sávban), statikus színnél kilépéskor elenged, a lámpa megjegyzi a színt. Csoport-animációk több telefonról: mindenki adjon nevet a lámpájának és nyomja ugyanazt, a telefonok órája tartja a ritmust.");
        hint.setTextColor(0xCCFFFFFF);
        hint.setPadding(0, dp(8), 0, 0);
        settings.addView(hint);

        seenView = new TextView(this);
        seenView.setTextColor(0x99FFFFFF);
        seenView.setTextSize(12);
        seenView.setPadding(0, dp(8), 0, 0);
        settings.addView(seenView);

        // Pages go right under the tab bar (before brightness), which sits lower in root.
        for (int i = 0; i < pages.length; i++) root.addView(pages[i], root.indexOfChild(tabBar) + 1 + i);
        showPage(0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF5CC8FF, 0xFF1E78CC, 0xFF0C3F7A}));
        scroll.addView(root);
        setContentView(scroll);
    }

    // Static colour: connect only while on screen, the lamps keep it. Animation or pulse running: stay connected in the
    // background behind a foreground-service notification so the phone keeps driving the lamps with the screen off.
    @Override protected void onStart() {
        super.onStart();
        visible = true;
        stopService(new Intent(this, KeepAliveService.class));
        start();
        if (pulse && pulser == null) setPulse(true);
        if (decay != null) { ui.removeCallbacks(decay); ui.postDelayed(decay, 30000); }   // disconnectAll() dropped its timer
        if (!autoChecked) { autoChecked = true; ui.postDelayed(() -> checkUpdate(true), 3000); }   // once per launch, quietly
    }

    boolean autoChecked;

    @Override protected void onStop() {
        super.onStop();
        if (anim != null || pulse || levelup != null) {
            startForegroundService(new Intent(this, KeepAliveService.class));
            return;   // visible stays true: reconnects and scans keep working in the background
        }
        visible = false;
        disconnectAll();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        visible = false;
        stopService(new Intent(this, KeepAliveService.class));
        disconnectAll();
    }

    // Shake detection only while on screen.
    @Override protected void onResume() {
        super.onResume();
        android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        android.hardware.Sensor acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sm.registerListener(shakeCb, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME);
    }

    @Override protected void onPause() {
        super.onPause();
        ((android.hardware.SensorManager) getSystemService(SENSOR_SERVICE)).unregisterListener(shakeCb);
    }

    final android.hardware.SensorEventListener shakeCb = new android.hardware.SensorEventListener() {
        public void onSensorChanged(android.hardware.SensorEvent e) {
            if (!shake) return;
            double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81;
            long now = System.currentTimeMillis();
            if (g > 2.4 && now - lastShake > 1200) {   // a real shake, not a pocket wobble; one mood per shake
                lastShake = now;
                String[] moods = {"green", "yellow", "orange", "red", "pink", "white", "turquoise"};
                String pick = moods[rnd.nextInt(moods.length)];
                applyMood(pick);
                Toast.makeText(MainActivity.this, "Rázás: " + pick, Toast.LENGTH_SHORT).show();
            }
        }
        public void onAccuracyChanged(android.hardware.Sensor s, int a) { }
    };

    // Needs decay: every 30 s the scale moves 5 % towards red (10 min from green to red), then stays red.
    void setDecay(boolean on) {
        if (decay != null) { ui.removeCallbacks(decay); decay = null; }
        if (!on) return;
        decay = new Runnable() {
            public void run() {
                int pos = Math.min(100, moodBar.getProgress() + 5);
                moodBar.setProgress(pos);
                if (anim == null && levelup == null) { slideStart(); slideMood(pos, true); }
                ui.postDelayed(this, 30000);
            }
        };
        ui.postDelayed(decay, 30000);
    }

    // Microphone-reactive: the base colour scaled by the loudness of what the phone hears, ~10 frames a second.
    void startMic() {
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 3);
            return;
        }
        micOn = true;
        micThread = new Thread(() -> {
            int rate = 16000, n = 1600;
            android.media.AudioRecord rec = null;
            try {
                rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(n * 2, android.media.AudioRecord.getMinBufferSize(rate, 16, 2)));
                rec.startRecording();
                short[] buf = new short[n];
                double peak = 500;
                while (micOn) {
                    int got = rec.read(buf, 0, n);
                    if (got <= 0) continue;
                    double sum = 0;
                    for (int i = 0; i < got; i++) sum += (double) buf[i] * buf[i];
                    double rms = Math.sqrt(sum / got);
                    peak = Math.max(rms, peak * 0.97);              // adaptive ceiling: quiet rooms still move
                    double level = Math.max(0.06, Math.min(1.0, rms / Math.max(peak, 300)));
                    int[] c = {(int) (base[0] * level), (int) (base[1] * level), (int) (base[2] * level)};
                    ui.post(() -> { if (!micOn) return; for (Bob b : selected()) if (b.queue.size() < 2) { b.cur = c; enqueue(b, Proto.rgb(b.fam, c[0], c[1], c[2])); } });
                }
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Mikrofon hiba: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
            }
        });
        micThread.start();
    }

    void stopMic() { micOn = false; micThread = null; }

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
        if (Build.VERSION.SDK_INT >= 33) return new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.POST_NOTIFICATIONS"};
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"}
            : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    // ---- Invitations between phones: a 20 s BLE advertisement carrying {version, animation, nonce, sender}. ----
    static final int COMPANY = 0xFFFF;   // manufacturer id used for our invite payload
    int myNonce;
    int lastInviteNonce = -1;
    boolean inviteScanning;
    boolean groupMode;   // set by sending/accepting an invitation: lamps take their slot in the roster and the clock's step
    int roster;          // bitmask over PEOPLE of who is in the group animation (merged from invite + accept beacons)
    int curNonce;        // the group session we are in (sender's nonce)

    String myName() {
        for (Bob b : bobs.values()) if (b.person != null) return b.person;
        return "Valaki";
    }

    /** Index of a wearer in PEOPLE, -1 for none; no allocation (called from the animation tick's sort). */
    static int personIdx(String p) {
        if (p != null) for (int i = 0; i < PEOPLE.length; i++) if (PEOPLE[i].equals(p)) return i;
        return -1;
    }

    /** Bit i set when PEOPLE[i] has a lamp on this phone. */
    int myMask() {
        int m = 0;
        for (Bob b : bobs.values()) { int i = personIdx(b.person); if (i >= 0) m |= 1 << i; }
        return m;
    }

    /** Power on and set brightness (unless the pulse is driving it): the start of every colour change. */
    void wake(Bob b) {
        enqueue(b, Proto.on(b.fam));
        if (!pulse) enqueue(b, Proto.brightness(b.fam, brightness));
    }

    @SuppressWarnings("MissingPermission")
    void sendInvite(int idx) {
        startAnim(idx);
        groupMode = true;
        myNonce = curNonce = rnd.nextInt(65536);
        roster = myMask();
        if (advertise(1, idx, curNonce, 20000, inviteAdvCb)) Toast.makeText(this, "Meghívás küldve: " + ANIMS[idx][0] + " (20 s)", Toast.LENGTH_SHORT).show();
    }

    /** Beacon {type, anim, nonce, mask of my wearers, sender name}: type 1 = invite, 2 = accept. */
    @SuppressWarnings("MissingPermission")
    boolean advertise(int type, int idx, int nonce, int ms, AdvertiseCallback cb) {
        BluetoothLeAdvertiser adv = adapter.getBluetoothLeAdvertiser();
        if (adv == null) { Toast.makeText(this, "Ez a telefon nem tud BLE-hirdetést küldeni", Toast.LENGTH_LONG).show(); return false; }
        byte[] name = myName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] p = new byte[8 + Math.min(name.length, 12)];
        p[0] = (byte) type; p[1] = (byte) idx; p[2] = (byte) (nonce >> 8); p[3] = (byte) nonce; p[4] = (byte) myMask();
        p[5] = (byte) base[0]; p[6] = (byte) base[1]; p[7] = (byte) base[2];   // the colour the chase runs in
        System.arraycopy(name, 0, p, 8, p.length - 8);
        AdvertiseSettings s = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).setTimeout(ms).build();
        AdvertiseData d = new AdvertiseData.Builder().setIncludeDeviceName(false).addManufacturerData(COMPANY, p).build();
        try { adv.stopAdvertising(cb); } catch (Exception ignored) { }
        adv.startAdvertising(s, d, cb);
        return true;
    }

    final AdvertiseCallback inviteAdvCb = new AdvertiseCallback() {
        @Override public void onStartFailure(int err) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Meghívás hiba: " + err, Toast.LENGTH_LONG).show()); }
    };
    final AdvertiseCallback acceptAdvCb = new AdvertiseCallback() { };

    // Beacon scan with a hardware filter on our manufacturer id, low latency while the app is up: beacons last
    // seconds and a low-power scan listens only 0.5 s in 5. Started once per start(), stopped in disconnectAll().
    @SuppressWarnings("MissingPermission")
    void startInviteScan() {
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null || inviteScanning) return;
        ScanFilter f = new ScanFilter.Builder().setManufacturerData(COMPANY, new byte[0]).build();   // any of our beacons
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        sc.startScan(Collections.singletonList(f), s, inviteCb);
        inviteScanning = true;
    }

    @SuppressWarnings("MissingPermission")
    void stopInviteScan() {
        if (!inviteScanning) return;
        inviteScanning = false;
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc != null) sc.stopScan(inviteCb);
    }

    final ScanCallback inviteCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            if (r.getScanRecord() == null) return;
            byte[] p = r.getScanRecord().getManufacturerSpecificData(COMPANY);
            if (p == null || p.length < 8 || p[0] < 1 || p[0] > 4) return;
            int kind = p[0], idx = p[1] & 0xFF, nonce = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF), mask = p[4] & 0xFF;
            int[] col = {p[5] & 0xFF, p[6] & 0xFF, p[7] & 0xFF};
            String from = new String(p, 8, p.length - 8, java.nio.charset.StandardCharsets.UTF_8);
            if (kind == 3) {   // attack on PEOPLE[idx]: applies on the phone that owns that lamp, once per nonce
                runOnUiThread(() -> {
                    if (seenAttacks.contains(nonce) || idx >= PEOPLE.length || (myMask() & (1 << idx)) == 0) return;
                    seenAttacks.add(nonce);
                    while (seenAttacks.size() > 32) seenAttacks.poll();
                    showPage(3);
                    for (Bob b : bobs.values()) if (b.ch != null && PEOPLE[idx].equals(b.person)) hit(b, from);
                    advertise(4, idx, nonce, 6000, ackAdvCb);   // tell the attacker it landed
                });
                return;
            }
            if (kind == 4) {   // acknowledgement of our attack
                runOnUiThread(() -> {
                    if (!seenAttacks.contains(nonce) || seenAcks.contains(nonce)) return;
                    seenAcks.add(nonce);
                    while (seenAcks.size() > 32) seenAcks.poll();
                    Toast.makeText(MainActivity.this, "Talált: " + from, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            if (idx >= ANIMS.length) return;
            runOnUiThread(() -> {
                if (groupMode && nonce == curNonce) {   // our session: anyone's beacon grows the roster on every phone
                    if ((roster | mask) != roster) { roster |= mask; Toast.makeText(MainActivity.this, from + " benne van", Toast.LENGTH_SHORT).show(); }
                    return;
                }
                if (kind != 1 || nonce == myNonce || nonce == lastInviteNonce) return;
                lastInviteNonce = nonce;
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Meghívás")
                    .setMessage(from + " meghív: " + ANIMS[idx][0] + ". Csatlakozol?")
                    .setPositiveButton("Igen", (d, w) -> {
                        showPage(2);
                        base = col;          // run the chase in the inviter's colour so all lamps match
                        startAnim(idx);
                        groupMode = true;
                        curNonce = nonce;
                        roster = mask | myMask();
                        advertise(2, idx, nonce, 30000, acceptAdvCb);   // tell the others (and the sender) we are in
                    })
                    .setNegativeButton("Nem", null)
                    .show();
            });
        }
    };

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
        startInviteScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        if (code == 3) { if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startMic(); else Toast.makeText(this, "Mikrofon engedély kell a Zene animációhoz", Toast.LENGTH_LONG).show(); return; }
        for (int i = 0; i < res.length; i++) {
            if (res[i] != PackageManager.PERMISSION_GRANTED && !p[i].endsWith("POST_NOTIFICATIONS") && !p[i].endsWith("BLUETOOTH_ADVERTISE")) { status.setText("Bluetooth engedély kell"); return; }
        }
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
                if (n == null || !n.toUpperCase().startsWith("LED") || !visible || bobs.containsKey(d.getAddress()) || ignore.contains(d.getAddress())) return;
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
                showHp(false);
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

    void loadWhite() {
        String[] p = names.getString("white", "255,255,255").split(",");
        RGB.put("white", new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
    }

    // Rounded, white-bordered Sims-style button for the 2-column grids.
    Button pill(String text, String color, int textSp, int heightPx, Runnable onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(textSp);
        btn.setTextColor(Color.BLACK);
        btn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(3), Color.WHITE);
        btn.setBackground(bg);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = heightPx;
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    void showPage(int i) {
        for (int k = 0; k < pages.length; k++) {
            pages[k].setVisibility(k == i ? android.view.View.VISIBLE : android.view.View.GONE);
            tabs[k].setAlpha(k == i ? 1f : 0.55f);
            tabs[k].setTypeface(k == i ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    int versionCode() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    // GitHub release tag "v<versionCode>": newer than ours -> download and install (silently where Android allows it).
    void checkUpdate() { checkUpdate(false); }

    void checkUpdate(boolean quiet) {
        if (!quiet) Toast.makeText(this, "Frissítés keresése…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                int latest = Updater.latestCode();
                if (latest < 0) msg = quiet ? null : "Nincs még kiadás a GitHubon.";
                else if (latest <= versionCode()) msg = quiet ? null : "Ez a legfrissebb (v" + latest + ").";
                else {
                    runOnUiThread(() -> Toast.makeText(this, "Új verzió: v" + latest + " – települ…", Toast.LENGTH_LONG).show());
                    Updater.install(this);
                    msg = "v" + latest + " letöltve; ha a telefon kérdez, Telepítés, különben magától frissül.";
                }
            } catch (Exception e) {
                msg = quiet ? null : "Frissítés hiba: " + e.getMessage();
            }
            String m = msg;
            if (m != null) runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); status.setText(m); });
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
                showHp(false);
            }).show();
        });
        b.row.addView(whoBtn);
        Button drop = new Button(this);   // "everyone drives one lamp": hand this lamp over to another phone
        drop.setText("Elenged");
        drop.setAllCaps(false);
        drop.setOnClickListener(v -> release(b));
        b.row.addView(drop);
        list.addView(b.row);
        rebuildWho();
    }

    /** Disconnect the lamp and ignore it on this phone until "Elengedettek vissza"; another phone can take it. */
    @SuppressWarnings("MissingPermission")
    void release(Bob b) {
        ignore.add(b.dev.getAddress());
        names.edit().putStringSet("ignore", new HashSet<>(ignore)).apply();
        if (b.gatt != null) b.gatt.close();
        bobs.remove(b.dev.getAddress());
        toConnect.remove(b);
        if (connecting == b) connecting = null;
        if (b.row != null) list.removeView(b.row);
        rebuildWho();
        refreshStatus();
        showHp(false);
        Toast.makeText(this, label(b) + " elengedve, másik telefon felveheti", Toast.LENGTH_SHORT).show();
        connectNext();
    }

    final Set<String> ignore = new HashSet<>();   // MACs released to other phones (persisted under "ignore")

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
        wake(b);
        if ("rainbow".equals(m)) enqueue(b, Proto.mode(b.fam, b.fam == Proto.SMART ? 1 : 138));   // 138 = seven-colour gradient
        else if (m.startsWith("fx:")) { enqueue(b, Proto.mode(b.fam, Integer.parseInt(m.substring(3)))); enqueue(b, Proto.speed(b.fam, speed)); }
        else if (m.startsWith("anim:")) { /* the running animation feeds the colours */ }
        else if (m.startsWith("rgb:")) { String[] p = m.substring(4).split(","); fadeTo(b, new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])}); }
        else if ("hp".equals(m)) { if (b.hp <= 0) enqueue(b, Proto.mode(b.fam, 150)); else fadeTo(b, hpColor(b)); }   // 150 = red flash: dead
        else fadeTo(b, RGB.get(m));
    }

    // ---- Life points, one counter per lamp (= per wearer) ----
    int[] hpColor(Bob b) { return b.hp <= 0 ? new int[]{255, 0, 0} : plumbobColor(1f - (b.hp - 1f) / Math.max(1, hpMax - 1)); }

    /** Text like "Tamás 87/100   Bianka 92/100" for the lamps the "Kinek" choice covers. */
    void showHp(boolean apply) {
        StringBuilder s = new StringBuilder();
        for (Bob b : ordered()) s.append(s.length() > 0 ? "   " : "").append(b.person == null ? b.name : b.person).append(' ').append(b.hp).append('/').append(hpMax);
        hpView.setText(s.length() == 0 ? "Életerő: nincs csatlakozott lámpa" : s.toString());
        if (apply) applyMood("hp");
    }

    void addHp(int d) {
        for (Bob b : selected()) b.hp = Math.max(0, Math.min(hpMax, b.hp + d));
        showHp(true);
    }

    /** One lamp takes a hit: white flash, then its HP colour (or the dead red flash). */
    void hit(Bob b, String from) {
        b.hp = Math.max(0, b.hp - 1);
        Toast.makeText(this, from + " megtámadta: " + (b.person == null ? b.name : b.person) + " (−1)", Toast.LENGTH_SHORT).show();
        mood = "hp";
        b.fadeId++;
        b.queue.clear();
        final int ep = ++epoch;
        if (anim != null) stopAnim();
        wake(b);
        enqueue(b, Proto.rgb(b.fam, 255, 255, 255));
        ui.postDelayed(() -> { if (ep != epoch || b.ch == null) return; b.cur = new int[]{255, 255, 255}; moodFrames(b, "hp"); }, 200);
        showHp(false);
    }

    void sendAttack(int target) {
        int nonce = rnd.nextInt(65536);
        seenAttacks.add(nonce);
        for (Bob b : bobs.values()) if (b.ch != null && PEOPLE[target].equals(b.person)) hit(b, myName());   // target's lamp is on this phone
        if (advertise(3, target, nonce, 10000, attackAdvCb)) Toast.makeText(this, "Támadás: " + PEOPLE[target] + "…", Toast.LENGTH_SHORT).show();
    }

    final AdvertiseCallback attackAdvCb = new AdvertiseCallback() { };
    final AdvertiseCallback ackAdvCb = new AdvertiseCallback() { };

    /** Colour at position t (0 = happy green, 1 = critical red) of the plumbob scale. */
    static int[] plumbobColor(float t) {
        int[][] stops = {{0, 255, 0}, {255, 210, 0}, {255, 80, 0}, {255, 0, 0}};
        float u = Math.max(0f, Math.min(1f, t)) * (stops.length - 1);
        int i = Math.min((int) u, stops.length - 2);
        float f = u - i;
        return new int[]{lerp(stops[i][0], stops[i + 1][0], f), lerp(stops[i][1], stops[i + 1][1], f), lerp(stops[i][2], stops[i + 1][2], f)};
    }

    long lastSlide;

    // Finger lands on the scale: stop whatever runs and make sure the lamps are on.
    void slideStart() {
        fresh();
        for (Bob b : selected()) wake(b);
    }

    // Dragging: at most ~8 colour frames a second, the final position always goes out.
    void slideMood(int pct, boolean fin) {
        long now = System.currentTimeMillis();
        if (!fin && now - lastSlide < 120) return;
        lastSlide = now;
        int[] c = plumbobColor(pct / 100f);
        base = c;
        mood = "rgb:" + c[0] + "," + c[1] + "," + c[2];
        for (Bob b : selected()) {
            b.fadeId++;
            b.cur = c;
            if (fin || b.queue.size() < 2) enqueue(b, Proto.rgb(b.fam, c[0], c[1], c[2]));
        }
    }

    void stopAnim() { if (anim != null) { ui.removeCallbacks(anim); anim = null; } stopMic(); }

    // Every switch: new epoch, no leftover frames in the targeted lamps' queues, no running fades on them.
    void fresh() {
        epoch++;
        stopAnim();
        if (levelup != null) { ui.removeCallbacks(levelup); levelup = null; }
        for (Bob b : selected()) { b.fadeId++; b.queue.clear(); }
    }

    // One animation at a time; each tick sends one colour frame to every selected lamp (skipped if its queue is backed up).
    void startAnim(int idx) {
        fresh();
        groupMode = false;   // a plain tap runs the chase over this phone's own lamps; invitations switch group mode on
        curNonce = 0;        // games use it as their random seed; a group session sets it from the invitation
        mood = "anim:" + idx;
        for (Bob b : selected()) wake(b);
        animStep = 0;
        animWait = idx < MULTI ? animFrame(idx, 0)[3] : multiFrame(idx, 0, 0, 1)[3];   // the tick period (fixed for most)
        if (idx == MIC) {
            anim = () -> { };   // marker so onStop keeps the connections alive; the mic thread does the work
            startMic();
            return;
        }
        anim = new Runnable() {
            public void run() {
                List<Bob> sel = ordered();
                int total = Math.max(1, sel.size());
                // Shared animations: in a group session the step comes from the clock so phones flash together.
                int sharedStep = groupMode ? (int) ((System.currentTimeMillis() / animWait) % 1000000) : animStep;
                int[] shared = idx < MULTI ? animFrame(idx, sharedStep) : null;   // {r, g, b, wait ms}, same for every lamp
                int wait = shared != null ? shared[3] : animWait;
                // Group mode (after an invitation): named lamps take their wearer's slot of 4 and the clock's step,
                // so several phones run one chase; slots of people who are not in stay dark.
                boolean group = shared == null && groupMode;
                int clockStep = (int) ((System.currentTimeMillis() / wait) % 1000000);
                int rosterSize = Integer.bitCount(roster);
                for (int k = 0; k < sel.size(); k++) {
                    Bob b = sel.get(k);
                    int[] f;
                    int slot = group && b.person != null ? slotOf(rank(b)) : -1;
                    if (shared != null) f = shared;
                    else if (slot >= 0) f = multiFrame(idx, clockStep, slot, Math.max(1, rosterSize));
                    else f = multiFrame(idx, animStep, k, total);
                    if (b.queue.size() < 3) { b.cur = new int[]{f[0], f[1], f[2]}; enqueue(b, Proto.rgb(b.fam, f[0], f[1], f[2])); }
                }
                animStep++;
                // Group mode: wake exactly at the next clock boundary so phones tick together.
                ui.postDelayed(this, groupMode ? wait - (System.currentTimeMillis() % wait) : wait);
            }
        };
        anim.run();
    }

    /** Selected lamps in wearer order (PEOPLE), unnamed ones last in discovery order. */
    List<Bob> ordered() {
        List<Bob> r = selected();
        r.sort((x, y) -> Integer.compare(rank(x), rank(y)));
        return r;
    }

    int rank(Bob b) {
        int i = personIdx(b.person);
        return i < 0 ? 99 : i;
    }

    /** Position of PEOPLE[i] inside the roster (people in the group, PEOPLE order), -1 when not in it. */
    int slotOf(int i) {
        if (i < 0 || i >= PEOPLE.length || (roster & (1 << i)) == 0) return -1;
        return Integer.bitCount(roster & ((1 << i) - 1));
    }

    /** Frame for lamp k of total in a multi-lamp animation. */
    int[] multiFrame(int idx, int n, int k, int total) {
        switch (idx) {
            case MULTI: {       // Futófény: one lamp lit in the base colour, the rest dark, running round
                int lit = n % total;
                return k == lit ? new int[]{base[0], base[1], base[2], 300} : new int[]{0, 0, 0, 300};
            }
            case MULTI + 1: {   // Pingpong: same, bouncing back and forth
                int span = Math.max(1, 2 * total - 2);
                int p = n % span;
                int lit = p < total ? p : span - p;
                return k == lit ? new int[]{base[0], base[1], base[2], 250} : new int[]{0, 0, 0, 250};
            }
            case MULTI + 2:     // Hullám: rainbow, each lamp a share of the turn behind the previous one
                return hsv((n * 8 + k * (360 / total)) % 360, 120);
            case MULTI + 3: {   // Forró krumpli: 250 ms hops to random players, round of 10..21 hops, loser flashes red for 12 ticks
                int step = n, round = 0;
                while (true) { int len = 10 + seeded(round, 0) % 12; if (step < len + 12) return potato(step, len, round, k, total); step -= len + 12; round++; }
            }
            case MULTI + 4: {   // Szerencsekerék: 100 ms ticks, 6 s decelerating spin + 3 s hold on the winner
                int per = 90, t = n % per, round = n / per;
                float turns = 3 + (seeded(round, 1) % 300) / 100f;              // 3.00 .. 5.99 turns, the fraction picks the winner
                float u = Math.min(1f, t / 60f);
                int lit = (int) Math.floor(turns * (1 - (1 - u) * (1 - u)) * total) % total;
                if (t >= 60) return k == lit ? (t % 4 < 2 ? new int[]{255, 215, 0, 100} : new int[]{255, 255, 255, 100}) : new int[]{6, 6, 10, 100};
                return k == lit ? new int[]{255, 215, 0, 100} : new int[]{20, 15, 0, 100};
            }
            case MULTI + 5: {   // Tűzijáték: 150 ms ticks, random bursts (white, then a colour fading over 6 ticks)
                for (int d = 0; d < 7; d++) {
                    int s = n - d;
                    if (s >= 0 && seeded(s, 100 + k) % 100 < 7) {
                        if (d == 0) return new int[]{255, 255, 255, 150};
                        int[] c = hsv(seeded(s, 200 + k) % 360, 150);
                        float lv = 1f - d / 7f;
                        return new int[]{(int) (c[0] * lv), (int) (c[1] * lv), (int) (c[2] * lv), 150};
                    }
                }
                return new int[]{3, 3, 6, 150};
            }
            default:
                return new int[]{0, 0, 0, 300};
        }
    }

    /** Deterministic pseudo-random for games: every phone in the session (same curNonce) gets the same numbers. */
    int seeded(int a, int b) {
        long x = curNonce * 0x9E3779B97F4A7C15L + a * 0xBF58476D1CE4E5B9L + b * 0x94D049BB133111EBL;
        x ^= x >>> 31; x *= 0x7FB5D329728EA185L; x ^= x >>> 27; x *= 0x81DADEF4BC2DD44DL; x ^= x >>> 33;
        return (int) (x & 0x7FFFFFFF);
    }

    int[] potato(int step, int len, int round, int k, int total) {
        if (step < len) {   // hopping: holder of this hop, never the same lamp twice in a row
            int holder = seeded(round, 10 + step) % total;
            if (step > 0 && total > 1 && holder == seeded(round, 10 + step - 1) % total) holder = (holder + 1) % total;
            return k == holder ? new int[]{255, 140, 0, 250} : new int[]{10, 6, 0, 250};
        }
        int loser = seeded(round, 10 + len - 1) % total;   // whoever held it on the last hop
        if (len > 1 && total > 1 && loser == seeded(round, 10 + len - 2) % total) loser = (loser + 1) % total;
        int t = step - len;
        return k == loser ? (t % 2 == 0 ? new int[]{255, 0, 0, 250} : new int[]{40, 0, 0, 250}) : new int[]{0, 30, 0, 250};
    }

    /** Colour and hold time of animation step n. */
    int[] animFrame(int idx, int n) {
        switch (idx) {
            case 0: { // Szívverés: lub-dub, then rest
                int k = n % 4;
                return k == 0 || k == 2 ? new int[]{255, 0, 0, 120} : new int[]{35, 0, 0, k == 1 ? 140 : 700};
            }
            case 1: // Tűz: flickering orange
                return new int[]{255, 30 + rnd.nextInt(110), 0, 70 + rnd.nextInt(90)};
            case 2: // Sziréna: red / blue
                return n % 2 == 0 ? new int[]{255, 0, 0, 250} : new int[]{0, 0, 255, 250};
            case 3: // Villám: dark sky with random white strikes
                return rnd.nextInt(100) < 15 ? new int[]{255, 255, 255, 60} : new int[]{8, 8, 40, 150};
            case 4: { // Disco: random pure colours
                int[][] p = {{255,0,0},{0,255,0},{0,0,255},{255,255,0},{0,255,255},{255,0,255}};
                int[] c = p[rnd.nextInt(p.length)];
                return new int[]{c[0], c[1], c[2], 200};
            }
            case 5: { // Szellem: pale blue slow breathing
                double s = 0.2 + 0.8 * (0.5 + 0.5 * Math.sin(n / 10.0));
                return new int[]{(int) (120 * s), (int) (200 * s), (int) (255 * s), 100};
            }
            case 6: // Halloween: orange / purple
                return n % 2 == 0 ? new int[]{255, 80, 0, 500} : new int[]{120, 0, 200, 500};
            case 7: // WooHoo: pinks with a white sparkle
                if (n % 6 == 5) return new int[]{255, 255, 255, 80};
                return n % 2 == 0 ? new int[]{255, 40, 120, 300} : new int[]{255, 0, 60, 300};
            case 8: // Zombi: sickly greens, slow
                return n % 2 == 0 ? new int[]{0, 80, 0, 800} : new int[]{50, 140, 0, 800};
            case 9: // Szivárvány-hullám: smooth hue sweep
                return hsv((n * 6) % 360, 100);
            case 10: // Gyertya: warm, lazy flicker
                return new int[]{255, 120 + rnd.nextInt(60), 10 + rnd.nextInt(20), 120 + rnd.nextInt(160)};
            case 11: // Boszorkány: purple / poison green
                return n % 2 == 0 ? new int[]{140, 0, 220, 600} : new int[]{60, 255, 0, 600};
            case 12: { // Vámpír: deep red breathing
                double s = 0.15 + 0.85 * (0.5 + 0.5 * Math.sin(n / 8.0));
                return new int[]{(int) (255 * s), 0, 0, 100};
            }
            case 13: // Alien: green / purple strobes, white blip
                if (n % 8 == 7) return new int[]{255, 255, 255, 60};
                return n % 2 == 0 ? new int[]{0, 255, 80, 150} : new int[]{160, 0, 255, 150};
            case 14: // Simoleon: gold with sparkles
                return rnd.nextInt(100) < 20 ? new int[]{255, 255, 200, 60} : new int[]{255, 180, 0, 150};
            case 15: // Strobo
                return n % 2 == 0 ? new int[]{255, 255, 255, 50} : new int[]{0, 0, 0, 50};
            case 16: { // Karácsony: red / green / white jumps
                int[][] p = {{255, 0, 0}, {0, 255, 0}, {255, 255, 255}};
                int[] c = p[n % 3];
                return new int[]{c[0], c[1], c[2], 500};
            }
            case 17: { // Magyar zászló: piros, fehér, zöld
                int[][] p = {{255, 0, 0}, {255, 255, 255}, {0, 255, 0}};
                int[] c = p[n % 3];
                return new int[]{c[0], c[1], c[2], 600};
            }
            case 18: { // Tévé: bluish flicker
                int v = 60 + rnd.nextInt(196);
                return new int[]{(int) (v * 0.7), (int) (v * 0.8), v, 60 + rnd.nextInt(120)};
            }
            case 19: { // Éhező Sim: plumbob decays green -> yellow -> orange -> red over ~20 s, then starts over
                int[] c = plumbobColor((n % 100) / 100f);
                return new int[]{c[0], c[1], c[2], 200};
            }
            default: // Kaszás: near-dark with ghostly flickers
                return rnd.nextInt(100) < 10 ? new int[]{90, 90, 110, 80} : new int[]{2, 2, 4, 200};
        }
    }

    static int[] hsv(float hue, int waitMs) {
        int c = Color.HSVToColor(new float[]{hue, 1f, 1f});
        return new int[]{Color.red(c), Color.green(c), Color.blue(c), waitMs};
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
        if (RGB.containsKey(m)) base = RGB.get(m);
        fresh();
        List<Bob> sel = selected();
        if (sel.isEmpty()) Toast.makeText(this, "Nincs kiválasztott, csatlakozott lámpa", Toast.LENGTH_SHORT).show();
        for (Bob b : sel) moodFrames(b, m);
    }

    // White/green flashing for 3 s, then back to happy green. Done app-side so the lamp ends in a known colour.
    void levelUp() {
        fresh();
        mood = "green";
        for (Bob b : selected()) wake(b);
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
        final int ep = epoch;
        b.fadeId++;
        enqueue(b, Proto.on(b.fam));
        for (int i = 0; i < 6; i++) {
            int[] c = RGB.get(i % 2 == 0 ? "white" : "blue");
            ui.postDelayed(() -> { if (ep != epoch) return; b.cur = c; enqueue(b, Proto.rgb(b.fam, c[0], c[1], c[2])); }, i * 300L);
        }
        ui.postDelayed(() -> { if (ep == epoch) moodFrames(b, mood); }, 1800);
    }

    @SuppressWarnings("MissingPermission")
    void disconnectAll() {
        stopScan();
        stopInviteScan();
        stopMic();
        ui.removeCallbacksAndMessages(null);
        levelup = null;
        anim = null;
        pulser = null;
        connecting = null;
        toConnect.clear();
        for (Bob b : bobs.values()) if (b.gatt != null) b.gatt.close();
        bobs.clear();
        list.removeAllViews();
        rebuildWho();
    }
}
