package hu.ntamas.plumbob;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plumbob LED: the screen and the orchestration. Lamps come from LampLink, animation colours from Frames,
 * phone-to-phone messages from Beacons, updates from Updater. Everything here runs on the UI thread.
 */
public class MainActivity extends Activity implements LampLink.Listener, Beacons.Listener {
    static final String[] PEOPLE = {"Dani", "Tamás", "Bianka", "Vivi"};

    // Plumbob colours: classic green -> yellow -> orange -> red, plus the Sims 3/4 extras.
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
    static final int FIRST_EFFECT = 135, RAINBOW_EFFECT = 138, RED_FLASH_EFFECT = 150;
    static final Map<String, int[]> RGB = new HashMap<>();
    static {
        RGB.put("green", new int[]{0, 255, 0});
        RGB.put("yellow", new int[]{255, 210, 0});
        RGB.put("orange", new int[]{255, 80, 0});
        RGB.put("red", new int[]{255, 0, 0});
        RGB.put("pink", new int[]{255, 40, 120});
        RGB.put("white", new int[]{255, 255, 255});
        RGB.put("turquoise", new int[]{0, 200, 200});
        RGB.put("blue", new int[]{0, 0, 255});
    }
    static final int DECAY_STEP_MS = 30000, PULSE_PERIOD_MS = 3000;

    /** A lamp's row on the settings page. */
    static final class Row { LinearLayout row; TextView label; }

    final Handler ui = new Handler();
    final Random rnd = new Random();
    SharedPreferences prefs;
    BluetoothAdapter adapter;
    LampLink link;
    Beacons beacons;
    final Map<Lamp, Row> rows = new LinkedHashMap<>();   // discovery order, drives the "Kinek" list

    // Screen
    LinearLayout lampList;
    LinearLayout[] pages;
    Button[] tabs;
    Spinner who;
    final List<String> whoMacs = new ArrayList<>();   // dropdown position - 1 -> MAC (position 0 = everyone)
    String whoSel;                                   // the chosen lamp's MAC, null only while no lamp is known
    boolean whoAll;                                  // "Mindenkinek": every connected lamp at once
    TextView status, seenView, hpView;
    SeekBar moodBar;

    // What the lamps show
    String mood = "green";   // colour key, "rainbow", "off", "fx:<mode>", "anim:<idx>", "rgb:r,g,b" or "hp"
    int[] base = {0, 255, 0};   // last static colour; chase animations and the microphone run in it
    int brightness = 100, speed = 50;
    boolean pulse;
    Runnable pulser, levelup, decay, anim;
    long pulseT0, lastSlide, lastShake;
    int animStep, animWait;
    int epoch;   // bumped on every switch; delayed lambdas of the previous one check it and bail
    boolean shake = true;
    volatile int micGen;   // capture generation; bumping it ends the running capture thread
    boolean micRunning;

    // Group sessions and board-game attacks over Beacons
    boolean groupMode;   // lamps take their roster slot and the clock's step, so phones run one chase
    int roster;          // bitmask over PEOPLE of who is in
    int curNonce;        // the session's seed; 0 outside a session
    int myNonce, lastInviteNonce = -1;
    final ArrayDeque<Integer> seenAttacks = new ArrayDeque<>(), seenAcks = new ArrayDeque<>();

    // Life points
    int hpMax = 100;
    final Map<String, Integer> hpByMac = new HashMap<>();   // survives onStop/onStart (lamps are re-discovered as new objects)
    boolean autoChecked;

    // Colour correction for this string's strong green/blue LEDs (percent of the requested value), applied in setColour()
    int gainG = 100, gainB = 100;
    final int[] custom = {0, 255, 0};   // the "Saját szín" sliders

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        prefs = getSharedPreferences("names", MODE_PRIVATE);
        gainG = prefs.getInt("gainG", 100);
        gainB = prefs.getInt("gainB", 100);
        link = new LampLink(this, adapter, ui, prefs, this);
        beacons = new Beacons(adapter, ui, this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildScreen());
        loadWhite();
        rebuildWho();
        showHp(false);
        showPage(0);
    }

    // Static colour: connect only while on screen, the lamps keep it. Animation or pulse running: stay connected in the
    // background behind a foreground-service notification so the phone keeps driving the lamps with the screen off.
    @Override protected void onStart() {
        super.onStart();
        link.active = true;
        if (!micRunning) stopService(new Intent(this, KeepAliveService.class));   // the mic service was started in the foreground on purpose
        start();
        if (pulse && pulser == null) setPulse(true);
        if (decay != null) { ui.removeCallbacks(decay); ui.postDelayed(decay, DECAY_STEP_MS); }
        if (!autoChecked) { autoChecked = true; ui.postDelayed(() -> checkUpdate(true), 3000); }
    }

    @Override protected void onStop() {
        super.onStop();
        if (anim != null || pulse || levelup != null || show != null) {
            startForegroundService(new Intent(this, KeepAliveService.class).putExtra("mic", micRunning));
            return;   // link stays active: reconnects and scans keep working in the background
        }
        shutDown();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopService(new Intent(this, KeepAliveService.class));
        shutDown();
    }

    @Override protected void onResume() {
        super.onResume();
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sm.registerListener(shakeCb, acc, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override protected void onPause() {
        super.onPause();
        ((SensorManager) getSystemService(SENSOR_SERVICE)).unregisterListener(shakeCb);
    }

    void shutDown() {
        link.active = false;
        beacons.stopScan();
        stopMic();
        ui.removeCallbacksAndMessages(null);
        levelup = null;
        anim = null;
        pulser = null;
        show = null;
        for (Lamp l : link.lamps.values()) hpByMac.put(l.mac(), l.hp);   // the game must survive a trip to the home screen
        link.closeAll();
        rows.clear();
        lampList.removeAllViews();
        rebuildWho();
    }

    void start() {
        if (!link.active) return;
        if (adapter == null) { status.setText("Nincs Bluetooth a telefonon"); return; }
        for (String p : perms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) { requestPermissions(perms(), 1); return; }
        }
        if (!adapter.isEnabled()) {
            status.setText("Kapcsold be a Bluetooth-t");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
            return;
        }
        link.scan();
        beacons.startScan();
        ui.removeCallbacks(presence);
        ui.postDelayed(presence, 2000);
    }

    String[] perms() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.POST_NOTIFICATIONS"};
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"}
            : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] res) {
        if (code == 3) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startAnim(Frames.MIC);
            else toast("Mikrofon engedély kell a Zene animációhoz");
            return;
        }
        for (int i = 0; i < res.length; i++) {
            boolean optional = p[i].endsWith("POST_NOTIFICATIONS") || p[i].endsWith("BLUETOOTH_ADVERTISE");
            if (res[i] != PackageManager.PERMISSION_GRANTED && !optional) { status.setText("Bluetooth engedély kell"); return; }
        }
        start();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == 2 && result == RESULT_OK) start();   // declined "turn on Bluetooth" must not re-open the dialog forever
    }

    // ---------------------------------------------------------------- screen

    View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(buildTitle());
        status = text("", Color.WHITE, 14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, dp(8));
        root.addView(status);
        root.addView(buildWhoRow());

        String[] tabNames = {"Hangulat", "Effekt", "Anim", "Társas", "Beáll."};
        pages = new LinearLayout[tabNames.length];
        tabs = new Button[tabNames.length];
        root.addView(buildTabBar(tabNames));
        buildMoodPage(pages[0]);
        buildEffectPage(pages[1]);
        buildAnimPage(pages[2]);
        buildLifePage(pages[3]);
        buildSettingsPage(pages[4]);
        for (LinearLayout page : pages) root.addView(page);
        root.addView(buildBrightnessRow());
        root.addView(buildBrightnessBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF5CC8FF, 0xFF1E78CC, 0xFF0C3F7A}));
        scroll.addView(root);
        return scroll;
    }

    View buildTitle() {
        LinearLayout row = hrow();
        row.setGravity(Gravity.CENTER);
        ImageView gem = new ImageView(this);
        gem.setImageResource(getResources().getIdentifier("plumbob", "drawable", getPackageName()));
        gem.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        row.addView(gem);
        TextView title = text("  Plumbob", Color.WHITE, 34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(6f, 0f, 3f, 0x99000000);
        title.setLayoutParams(weighted());
        row.addView(title);
        return row;
    }

    View buildWhoRow() {
        LinearLayout row = hrow();
        row.addView(text("Kinek:  ", Color.WHITE, 18));
        who = new Spinner(this);
        who.setLayoutParams(weighted());
        who.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (whoMacs.isEmpty()) { whoAll = false; whoSel = null; }
                else if (pos == 0) whoAll = true;
                else if (pos <= whoMacs.size()) { whoAll = false; whoSel = whoMacs.get(pos - 1); }
                showHp(false);
            }
            public void onNothingSelected(AdapterView<?> a) { }
        });
        row.addView(who);
        return row;
    }

    View buildTabBar(String[] names) {
        LinearLayout bar = hrow();
        bar.setPadding(0, dp(8), 0, dp(4));
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            Button t = new Button(this);
            t.setText(names[i]);
            t.setAllCaps(false);
            t.setTextSize(13);
            t.setSingleLine(true);
            t.setPadding(0, 0, 0, 0);
            t.setMinWidth(0);
            t.setMinimumWidth(0);
            LinearLayout.LayoutParams lp = weighted();
            lp.setMargins(dp(2), 0, dp(2), 0);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> showPage(idx));
            tabs[i] = t;
            bar.addView(t);
        }
        return bar;
    }

    void buildMoodPage(LinearLayout page) {
        TextView label = text("Plumbob-skála:  Kiváló  ◀━━━▶  Kritikus", Color.WHITE, 14);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);
        page.addView(label);
        page.addView(buildMoodSlider());

        Switch decaySw = new Switch(this);   // Sims needs: the scale slides towards red by itself, "Kiváló" feeds the Sim
        decaySw.setText("Lecsengés (10 perc alatt zöld → piros)  ");
        decaySw.setTextColor(Color.WHITE);
        decaySw.setPadding(0, dp(4), 0, dp(4));
        decaySw.setOnCheckedChangeListener((sw, on) -> setDecay(on));
        page.addView(decaySw);

        GridLayout grid = grid(2);
        for (String[] m : MOODS) grid.addView(pill(m[0], m[2], 20, dp(72), () -> {
            if ("levelup".equals(m[1])) levelUp(); else applyMood(m[1]);
            int pos = "green".equals(m[1]) ? 0 : "yellow".equals(m[1]) ? 33 : "orange".equals(m[1]) ? 66 : "red".equals(m[1]) ? 100 : -1;
            if (pos >= 0) moodBar.setProgress(pos);   // keep the scale (and the decay) in step with the classic moods
        }));
        page.addView(grid);

        page.addView(section("Saját szín"));
        String[] ch = {"Piros", "Zöld", "Kék"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            page.addView(text(ch[i], 0xCCFFFFFF, 12));
            page.addView(seekBar(255, custom[i], v -> { custom[k] = v; base = new int[]{custom[0], custom[1], custom[2]}; applyMood("rgb:" + custom[0] + "," + custom[1] + "," + custom[2]); }));
        }

        page.addView(section("Hangulat küldése másnak (az aktuális szín megy)"));
        sendGrid = grid(4);
        page.addView(sendGrid);

        page.addView(button("Műsor… (időzített sorrend)", v -> showDialog()));
    }

    TextView section(String s) {
        TextView t = text(s, Color.WHITE, 14);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    View buildMoodSlider() {
        FrameLayout box = new FrameLayout(this);
        View bar = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF00FF00, 0xFFFFD200, 0xFFFF5000, 0xFFFF0000});
        g.setCornerRadius(dp(10));
        bar.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(18), Gravity.CENTER_VERTICAL);
        lp.setMargins(dp(16), 0, dp(16), 0);
        bar.setLayoutParams(lp);
        box.addView(bar);
        moodBar = new SeekBar(this);
        moodBar.setMax(100);
        moodBar.setProgress(0);
        moodBar.setProgressTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        moodBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        moodBar.setPadding(dp(16), dp(14), dp(16), dp(14));
        moodBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) slideMood(p, false); }
            public void onStartTrackingTouch(SeekBar s) { slideStart(); }
            public void onStopTrackingTouch(SeekBar s) { slideMood(s.getProgress(), true); }
        });
        box.addView(moodBar);
        return box;
    }

    void buildEffectPage(LinearLayout page) {
        GridLayout grid = grid(2);
        for (int i = 0; i < EFFECTS.length; i++) {
            final int mode = FIRST_EFFECT + i;
            grid.addView(pill(EFFECTS[i], "#BBDDFF", 15, dp(56), () -> applyMood("fx:" + mode)));
        }
        page.addView(grid);
        TextView label = text("Effekt sebessége", Color.WHITE, 14);
        label.setPadding(0, dp(10), 0, dp(4));
        page.addView(label);
        page.addView(seekBar(100, speed, v -> { speed = v; for (Lamp l : selected()) link.enqueue(l, Proto.speed(l.family, speed)); }));
    }

    void buildAnimPage(LinearLayout page) {
        GridLayout grid = grid(2);
        for (int i = 0; i < Frames.ANIMS.length; i++) {
            final int a = i;
            Button b = pill(Frames.ANIMS[i][0], Frames.ANIMS[i][1], 15, dp(56), () -> startAnim(a));
            if (a != Frames.MIC) b.setOnLongClickListener(v -> { sendInvite(a); return true; });
            grid.addView(b);
        }
        page.addView(grid);
        page.addView(hint("Hosszú nyomás bármelyik animáción: meghívást küld a közeli telefonoknak (Bluetooth); akik elfogadják, azoknál is elindul, óra-szinkronban, a csoportosak a Kié?-nevek sorrendjében."));
    }

    void buildLifePage(LinearLayout page) {
        hpView = text("", Color.WHITE, 40);
        hpView.setTypeface(Typeface.DEFAULT_BOLD);
        hpView.setGravity(Gravity.CENTER);
        hpView.setShadowLayer(6f, 0f, 3f, 0x99000000);
        hpView.setPadding(0, dp(8), 0, dp(4));
        page.addView(hpView);
        GridLayout grid = grid(2);
        grid.addView(pill("−1", "#FF7766", 24, dp(72), () -> addHp(-1)));
        grid.addView(pill("+1", "#77EE77", 24, dp(72), () -> addHp(1)));
        grid.addView(pill("−5", "#EE4444", 20, dp(56), () -> addHp(-5)));
        grid.addView(pill("+5", "#44CC44", 20, dp(56), () -> addHp(5)));
        grid.addView(pill("−10", "#CC2222", 20, dp(56), () -> addHp(-10)));
        grid.addView(pill("+10", "#22AA22", 20, dp(56), () -> addHp(10)));
        grid.addView(pill("Halott (0)", "#999999", 16, dp(56), () -> addHp(-100000)));
        grid.addView(pill("Teli", "#BBDDFF", 16, dp(56), () -> addHp(100000)));
        page.addView(grid);
        TextView maxLabel = text("Maximum (mindenkinek, és feltölt)", Color.WHITE, 14);
        maxLabel.setPadding(0, dp(8), 0, 0);
        page.addView(maxLabel);
        GridLayout maxGrid = grid(5);
        for (int m : new int[]{10, 20, 30, 50, 100}) maxGrid.addView(pill("" + m, "#DDDDDD", 14, dp(44), () -> setHpMax(m)));
        page.addView(maxGrid);
        page.addView(hint("A gombok a „Kinek” szerint kiválasztott lámpára (vagy Mindenkinek: az összesre) hatnak. Életerő lámpánként (személyenként) számolódik."));
        TextView atkLabel = text("Támadás (−1 HP a másik telefonján, Bluetooth-on) – csak aki itt van", Color.WHITE, 14);
        atkLabel.setPadding(0, dp(10), 0, 0);
        page.addView(atkLabel);
        atkGrid = grid(4);
        page.addView(atkGrid);

        page.addView(section("Szavazás – a lámpád a válaszod színe"));
        GridLayout voteGrid = grid(4);
        for (int i = 0; i < VOTE_NAMES.length; i++) { final int c = i; voteGrid.addView(pill(VOTE_NAMES[i], VOTE_HEX[i], 18, dp(56), () -> castVote(c))); }
        page.addView(voteGrid);
        voteView = text("", Color.WHITE, 15);
        voteView.setPadding(0, dp(4), 0, 0);
        page.addView(voteView);
        page.addView(button("Új szavazás (mindenkinél töröl)", v -> resetVotes(true)));

        page.addView(section("Reakció-játék – sötét, aztán villan: ki koppint először?"));
        page.addView(button("Reakció-játék indítása (mindenkinél)", v -> startReaction()));
        reactBtn = pill("—", "#DDDDDD", 24, dp(96), this::reactTap);
        GridLayout reactGrid = grid(1);
        reactGrid.addView(reactBtn);
        page.addView(reactGrid);
        reactView = text("", Color.WHITE, 15);
        page.addView(reactView);

        rebuildPeopleButtons();
        showVotes();
    }

    // ---- voting: everybody's lamp shows their answer, every phone tallies what it hears ----
    static final String[] VOTE_NAMES = {"A", "B", "C", "D"};
    static final String[] VOTE_HEX = {"#33EE33", "#EE3333", "#6688FF", "#EECC33"};
    static final int[][] VOTE_RGB = {{0, 255, 0}, {255, 0, 0}, {0, 0, 255}, {255, 210, 0}};
    final int[] votes = {-1, -1, -1, -1};
    int pollNonce;   // the current poll; votes of another poll (still in the air after a reset) are ignored
    final ArrayDeque<Integer> seenResets = new ArrayDeque<>();
    TextView voteView;

    void castVote(int choice) {
        int me = personIdx(myName());
        if (me >= 0) votes[me] = choice;
        applyMood("rgb:" + VOTE_RGB[choice][0] + "," + VOTE_RGB[choice][1] + "," + VOTE_RGB[choice][2]);
        beacons.send(Beacons.VOTE, choice, pollNonce, myMask(), VOTE_RGB[choice], myName(), 6000);
        showVotes();
    }

    void resetVotes(boolean broadcast) {
        Arrays.fill(votes, -1);
        showVotes();
        if (!broadcast) return;
        int nonce = rnd.nextInt(65536);
        pollNonce = nonce;
        remember(seenResets, nonce);
        beacons.send(Beacons.VOTE_RESET, 0, nonce, myMask(), base, myName(), 6000);
    }

    void showVotes() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < PEOPLE.length; i++) {
            if (!present(i) && votes[i] < 0) continue;
            s.append(s.length() > 0 ? "   " : "").append(PEOPLE[i]).append(": ").append(votes[i] < 0 ? "–" : VOTE_NAMES[votes[i]]);
        }
        voteView.setText(s.length() == 0 ? "Még senki nem szavazott." : s.toString());
    }

    void onVote(Beacons.Msg m) {
        if (m.nonce != pollNonce) return;
        int p = personIdx(m.from);
        if (p >= 0 && m.idx < VOTE_NAMES.length) { votes[p] = m.idx; showVotes(); }
    }

    void onVoteReset(Beacons.Msg m) {
        if (seenResets.contains(m.nonce)) return;
        remember(seenResets, m.nonce);
        pollNonce = m.nonce;
        Arrays.fill(votes, -1);
        showVotes();
        toast(m.from + ": új szavazás");
    }

    // ---- reaction game: lamps go dark, flash white at one wall-clock instant on every phone, first tap wins ----
    long reactAt;        // flash time, 0 = no game armed
    int reactNonce;
    boolean reactDone;
    String reactBefore = "green";   // what ran before the game, put back afterwards
    boolean reactGroup;
    int reactSeed, reactRoster;
    Button reactBtn;
    TextView reactView;
    final Map<String, Integer> reactResults = new LinkedHashMap<>();   // wearer -> ms, -1 = tapped early
    final ArrayDeque<Integer> seenReacts = new ArrayDeque<>();

    void startReaction() {
        long at = System.currentTimeMillis() + 5000 + rnd.nextInt(5000);
        int nonce = rnd.nextInt(65536);
        remember(seenReacts, nonce);
        beacons.send(Beacons.REACT_START, 0, nonce, myMask(), clock24(at), myName(), 6000);
        armReaction(at, nonce);
    }

    static int[] clock24(long t) { return new int[]{(int) ((t >> 16) & 0xFF), (int) ((t >> 8) & 0xFF), (int) (t & 0xFF)}; }

    /** The flash instant from its low 24 bits, taken as the one closest to now (the beacon is only seconds old). */
    static long unclock24(int[] c, long now) {
        long t = (now & ~0xFFFFFFL) | ((long) c[0] << 16) | ((long) c[1] << 8) | c[2];
        if (t - now > (1 << 23)) t -= 1 << 24;
        else if (now - t > (1 << 23)) t += 1 << 24;
        return t;
    }

    void armReaction(long at, int nonce) {
        reactAt = at;
        reactNonce = nonce;
        reactDone = false;
        reactResults.clear();
        reactView.setText("");
        showPage(3);
        reactBefore = mood; reactGroup = groupMode; reactSeed = curNonce; reactRoster = roster;
        fresh();
        final int ep = epoch;
        for (Lamp l : selected()) { wake(l); setColour(l, new int[]{0, 0, 0}); }
        reactBtn.setText("VÁRJ…");
        long wait = Math.max(0, at - System.currentTimeMillis());
        ui.postDelayed(() -> {
            if (reactNonce != nonce || reactDone || ep != epoch) return;   // a mood chosen meanwhile wins
            for (Lamp l : selected()) setColour(l, RGB.get("white"));
            reactBtn.setText("KOPPINTS!");
        }, wait);
        ui.postDelayed(() -> { if (reactNonce == nonce && !reactDone && ep == epoch) { reactDone = true; reactBtn.setText("—"); reactRestore(); } }, wait + 5000);   // nobody tapped
    }

    /** Put back what ran before the game: an animation restarts (with its session), a colour is re-sent. */
    void reactRestore() {
        if (reactBefore.startsWith("anim:")) {
            startAnim(Integer.parseInt(reactBefore.substring(5)));
            groupMode = reactGroup; curNonce = reactSeed; roster = reactRoster;   // same pattern as acceptInvite()
        } else for (Lamp l : selected()) moodFrames(l, l.mood != null && !l.mood.startsWith("anim:") ? l.mood : reactBefore);
    }

    void reactTap() {
        if (reactAt == 0 || reactDone) return;
        reactDone = true;
        long now = System.currentTimeMillis();
        int ms = now < reactAt ? -1 : (int) Math.min(0xFFFFFE, now - reactAt);
        reactBtn.setText(ms < 0 ? "Korai!" : ms + " ms");
        reactResults.put(myName(), ms);
        showReact();
        int code = ms < 0 ? 0xFFFFFF : ms;
        beacons.send(Beacons.REACT, 0, reactNonce, myMask(), new int[]{(code >> 16) & 0xFF, (code >> 8) & 0xFF, code & 0xFF}, myName(), 8000);
        final int nonce = reactNonce;
        ui.postDelayed(() -> { if (reactNonce == nonce) reactRestore(); }, 1500);
    }

    void onReactStart(Beacons.Msg m) {
        if (seenReacts.contains(m.nonce)) return;
        remember(seenReacts, m.nonce);
        armReaction(unclock24(m.colour, System.currentTimeMillis()), m.nonce);
    }

    void onReact(Beacons.Msg m) {
        if (m.nonce != reactNonce) return;
        int code = (m.colour[0] << 16) | (m.colour[1] << 8) | m.colour[2];
        reactResults.put(m.from, code == 0xFFFFFF ? -1 : code);
        showReact();
    }

    void showReact() {
        List<Map.Entry<String, Integer>> r = new ArrayList<>(reactResults.entrySet());
        r.sort((a, b) -> Integer.compare(a.getValue() < 0 ? Integer.MAX_VALUE : a.getValue(), b.getValue() < 0 ? Integer.MAX_VALUE : b.getValue()));
        StringBuilder s = new StringBuilder();
        int place = 1;
        for (Map.Entry<String, Integer> e : r) s.append(place++).append(". ").append(e.getKey()).append(e.getValue() < 0 ? "  korai" : "  " + e.getValue() + " ms").append('\n');
        reactView.setText(s.toString().trim());
    }

    // ---- give someone a colour ----
    final ArrayDeque<Integer> seenMoods = new ArrayDeque<>();

    void sendMood(int target) {
        int nonce = rnd.nextInt(65536);
        remember(seenMoods, nonce);
        for (Lamp l : lampsOf(target)) giveMood(l, myName(), base);
        beacons.send(Beacons.MOOD, target, nonce, myMask(), base, myName(), 8000);
        toast("Szín elküldve: " + PEOPLE[target]);
    }

    void onMood(Beacons.Msg m) {
        if (seenMoods.contains(m.nonce) || m.idx >= PEOPLE.length || (myMask() & (1 << m.idx)) == 0) return;
        remember(seenMoods, m.nonce);
        for (Lamp l : lampsOf(m.idx)) giveMood(l, m.from, m.colour);
    }

    void giveMood(Lamp l, String from, int[] c) {
        String m = "rgb:" + c[0] + "," + c[1] + "," + c[2];
        if (anim != null || levelup != null) { fresh(); mood = m; }   // otherwise the next tick overwrites the gift
        l.queue.clear();
        l.fadeId++;
        wake(l);
        l.mood = m;
        fadeTo(l, c);
        toast(from + " színt küldött: " + l.shortName());
    }

    // ---- timed show: "Kiváló 5" lines, each a mood / effect / animation name and minutes ----
    Runnable show;
    List<String[]> showSteps;
    boolean inShow;

    void showDialog() {
        android.widget.EditText box = new android.widget.EditText(this);
        box.setText(prefs.getString("show", "Kiváló 5\nSzivárvány 2\nSemleges 5\nFeszült 2\nKritikus 1"));
        box.setMinLines(5);
        new AlertDialog.Builder(this)
            .setTitle("Műsor: név és perc soronként")
            .setView(box)
            .setPositiveButton(show == null ? "Indít" : "Újraindít", (d, w) -> { prefs.edit().putString("show", box.getText().toString()).apply(); startShow(box.getText().toString()); })
            .setNegativeButton("Leállít", (d, w) -> stopShow())
            .setNeutralButton("Mégse", null)
            .show();
    }

    /** Line "Szivárvány 2" -> {"anim:…"|"mood:…"|"fx:…", minutes}; unknown names are reported and skipped. */
    List<String[]> parseShow(String text) {
        List<String[]> steps = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            int sp = line.lastIndexOf(' ');
            if (sp < 1) continue;
            String name = line.substring(0, sp).trim(), mins = line.substring(sp + 1).replace(',', '.');
            String what = resolveName(name);
            if (what == null) { toast("Ismeretlen: " + name); continue; }
            try { double m = Double.parseDouble(mins); if (!(m > 0)) throw new NumberFormatException(); steps.add(new String[]{what, String.valueOf(m)}); }
            catch (NumberFormatException e) { toast("Nem perc: " + line); }
        }
        return steps;
    }

    static String resolveName(String name) {
        for (String[] m : MOODS) if (m[0].equalsIgnoreCase(name)) return "mood:" + m[1];
        for (int i = 0; i < EFFECTS.length; i++) if (EFFECTS[i].equalsIgnoreCase(name)) return "fx:" + (FIRST_EFFECT + i);
        for (int i = 0; i < Frames.ANIMS.length; i++) if (Frames.ANIMS[i][0].equalsIgnoreCase(name) || Frames.ANIMS[i][0].toLowerCase().startsWith(name.toLowerCase() + " (")) return "anim:" + i;
        return null;
    }

    void startShow(String text) {
        stopShow();
        showSteps = parseShow(text);
        if (showSteps.isEmpty()) { toast("Üres műsor"); return; }
        show = new Runnable() {
            int i = 0;
            public void run() {
                String[] step = showSteps.get(i % showSteps.size());
                String what = step[0];
                inShow = true;   // fresh() must not stop the show that is applying this step
                try {
                    if (what.startsWith("mood:")) { String m = what.substring(5); if ("levelup".equals(m)) levelUp(); else applyMood(m); }
                    else if (what.startsWith("fx:")) applyMood(what);
                    else startAnim(Integer.parseInt(what.substring(5)));
                } finally { inShow = false; }
                toast("Műsor " + (i % showSteps.size() + 1) + "/" + showSteps.size());
                i++;
                ui.postDelayed(this, (long) (Double.parseDouble(step[1]) * 60000));
            }
        };
        show.run();
    }

    void stopShow() {
        if (show != null) { ui.removeCallbacks(show); show = null; toast("Műsor leállítva"); }
    }

    // ---- who is around: our own named lamps + wearers whose phones we heard in the last 45 s ----
    static final int PRESENCE_EVERY_MS = 15000, PRESENCE_ADVERT_MS = 3000, PRESENCE_TTL_MS = 45000;
    final long[] lastHeard = new long[PEOPLE.length];   // uptime ms per wearer, 0 = never
    GridLayout atkGrid, sendGrid;

    void notePresence(int mask) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean changed = false;
        for (int i = 0; i < PEOPLE.length; i++) if ((mask & (1 << i)) != 0) { changed |= !present(i); lastHeard[i] = now; }
        if (changed) rebuildPeopleButtons();
    }

    boolean present(int i) {
        return (myMask() & (1 << i)) != 0 || (lastHeard[i] != 0 && android.os.SystemClock.uptimeMillis() - lastHeard[i] < PRESENCE_TTL_MS);
    }

    interface Target { void pick(int personIndex); }

    /** One button per wearer who is around; a hint when nobody is. */
    void fillPeople(GridLayout g, String colour, Target action) {
        g.removeAllViews();
        int n = 0;
        for (int i = 0; i < PEOPLE.length; i++) {
            if (!present(i)) continue;
            final int t = i;
            g.addView(pill(PEOPLE[i], colour, 14, dp(48), () -> action.pick(t)));
            n++;
        }
        if (n == 0) g.addView(text("Senkit nem látunk még (Kié?-név kell mindenkinél, app nyitva)", 0xCCFFFFFF, 13));
    }

    void rebuildPeopleButtons() {
        if (atkGrid != null) fillPeople(atkGrid, "#FFAA55", this::sendAttack);
        if (sendGrid != null) fillPeople(sendGrid, "#BBDDFF", this::sendMood);
        if (voteView != null) showVotes();
    }

    /** Every 15 s: tell the others who is on this phone, forget wearers not heard of for 45 s. */
    final Runnable presence = new Runnable() {
        public void run() {
            if (myMask() != 0) beacons.send(Beacons.PRESENCE, 0, rnd.nextInt(65536), myMask(), base, myName(), PRESENCE_ADVERT_MS);
            rebuildPeopleButtons();   // drops the expired ones
            ui.postDelayed(this, PRESENCE_EVERY_MS);
        }
    };

    void buildSettingsPage(LinearLayout page) {
        page.setPadding(0, dp(8), 0, 0);
        TextView title = text("Lámpák – kié melyik?", Color.WHITE, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(6));
        page.addView(title);
        lampList = new LinearLayout(this);
        lampList.setOrientation(LinearLayout.VERTICAL);
        lampList.setPadding(dp(8), 0, 0, dp(8));
        page.addView(lampList);
        page.addView(button("Keresés újra", v -> start()));   // through start(): permissions / Bluetooth-on checks first
        page.addView(button("Elengedettek vissza", v -> { link.forgetReleased(); start(); toast("Minden lámpa újra felvehető"); }));
        page.addView(button("Frissítés keresése (v" + versionCode() + ")", v -> checkUpdate(false)));

        TextView whiteLabel = text("Fehér (Fókuszált, fehér villanások)", Color.WHITE, 14);
        whiteLabel.setPadding(0, dp(12), 0, dp(4));
        page.addView(whiteLabel);
        GridLayout whites = grid(3);
        for (String[] w : new String[][]{{"Hófehér", "200,230,255"}, {"Tiszta", "255,255,255"}, {"Meleg", "255,225,180"}}) {
            whites.addView(pill(w[0], "#F4F4F4", 14, dp(48), () -> {
                prefs.edit().putString("white", w[1]).apply();
                loadWhite();
                if ("white".equals(mood)) applyMood("white");
                toast("Fehér: " + w[0]);
            }));
        }
        page.addView(whites);

        page.addView(section("Színkorrekció – ha a sárga/narancs zöldes, vedd lejjebb a zöldet"));
        TextView gL = text("Zöld LED erőssége: " + gainG + " %", 0xCCFFFFFF, 12);
        page.addView(gL);
        SeekBar gS = seekBar(100, gainG, v -> { gainG = v; prefs.edit().putInt("gainG", gainG).apply(); gL.setText("Zöld LED erőssége: " + gainG + " %"); reapply(); });
        gS.setMin(10);
        page.addView(gS);
        TextView bL = text("Kék LED erőssége: " + gainB + " %", 0xCCFFFFFF, 12);
        page.addView(bL);
        SeekBar bS = seekBar(100, gainB, v -> { gainB = v; prefs.edit().putInt("gainB", gainB).apply(); bL.setText("Kék LED erőssége: " + gainB + " %"); reapply(); });
        bS.setMin(10);
        page.addView(bS);

        Switch shakeSw = new Switch(this);
        shakeSw.setText("Rázásra véletlen hangulat  ");
        shakeSw.setTextColor(Color.WHITE);
        shakeSw.setChecked(shake);
        shakeSw.setPadding(0, dp(10), 0, 0);
        shakeSw.setOnCheckedChangeListener((sw, on) -> shake = on);
        page.addView(shakeSw);

        page.addView(hint("Hosszú nyomás a lámpa nevén: villan, hogy lásd, melyik az. „Kié?”: név a lámpához, a telefon megjegyzi. Animáció vagy Lüktetés közben az app háttérben is megy (értesítés a sávban), statikus színnél kilépéskor elenged, a lámpa megjegyzi a színt. Csoport-animációk több telefonról: mindenki adjon nevet a lámpájának és nyomja ugyanazt, a telefonok órája tartja a ritmust."));
        seenView = text("", 0x99FFFFFF, 12);
        seenView.setPadding(0, dp(8), 0, 0);
        page.addView(seenView);
    }

    View buildBrightnessRow() {
        LinearLayout row = hrow();
        row.setPadding(0, dp(16), 0, dp(4));
        TextView label = text("Fényerő", Color.WHITE, 14);
        label.setLayoutParams(weighted());
        row.addView(label);
        Switch pulseSw = new Switch(this);   // Sims-style slow breathing of the brightness
        pulseSw.setText("Lüktetés  ");
        pulseSw.setTextColor(Color.WHITE);
        pulseSw.setOnCheckedChangeListener((sw, on) -> setPulse(on));
        row.addView(pulseSw);
        return row;
    }

    View buildBrightnessBar() {
        return seekBar(100, brightness, v -> { brightness = Math.max(1, v); for (Lamp l : selected()) link.enqueue(l, Proto.brightness(l.family, brightness)); });
    }

    // Settings row of one lamp: label (long-press = blink), "Kié?" (name the wearer) and "Elenged" (hand over to another phone).
    void addRow(Lamp lamp) {
        Row r = new Row();
        r.row = hrow();
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0x33FFFFFF);
        panel.setCornerRadius(dp(14));
        r.row.setBackground(panel);
        r.row.setPadding(dp(8), dp(2), dp(4), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(3), 0, dp(3));
        r.row.setLayoutParams(lp);
        r.label = text(lamp.label(), Color.WHITE, 18);
        r.label.setPadding(dp(4), dp(10), 0, dp(10));
        r.label.setLayoutParams(weighted());
        r.label.setOnLongClickListener(v -> { identify(lamp); return true; });
        r.row.addView(r.label);
        r.row.addView(button("Kié?", v -> askOwner(lamp)));
        r.row.addView(button("Elenged", v -> { link.release(lamp); removeRow(lamp); toast(lamp.label() + " elengedve, másik telefon felveheti"); }));
        lampList.addView(r.row);
        rows.put(lamp, r);
        rebuildWho();
    }

    void removeRow(Lamp lamp) {
        Row r = rows.remove(lamp);
        if (r != null) lampList.removeView(r.row);
        rebuildWho();
        showHp(false);
    }

    void askOwner(Lamp lamp) {
        String[] items = Arrays.copyOf(PEOPLE, PEOPLE.length + 1);
        items[PEOPLE.length] = "(senkié)";
        new AlertDialog.Builder(this).setTitle("Kié ez a lámpa? " + lamp.name).setItems(items, (d, i) -> {
            link.rename(lamp, i < PEOPLE.length ? PEOPLE[i] : null);
            Row r = rows.get(lamp);
            if (r != null) r.label.setText(lamp.label());
            rebuildWho();
            showHp(false);
            rebuildPeopleButtons();
        }).show();
    }

    // Refill the "Kinek" dropdown from the known lamps: "Mindenkinek" (all lamps at once) or exactly one lamp
    // (the first one by default), keeping the current choice if it is still around.
    void rebuildWho() {
        whoMacs.clear();
        List<String> items = new ArrayList<>();
        for (Lamp l : rows.keySet()) { whoMacs.add(l.mac()); items.add(l.label()); }
        if (items.isEmpty()) { items.add("– nincs lámpa –"); whoAll = false; }
        else items.add(0, "Mindenkinek");
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        int keep = whoSel == null ? -1 : whoMacs.indexOf(whoSel);
        if (keep < 0) keep = 0;
        whoSel = whoMacs.isEmpty() ? null : whoMacs.get(keep);
        who.setAdapter(ad);
        who.setSelection(whoMacs.isEmpty() ? 0 : whoAll ? 0 : keep + 1, false);
    }

    void showPage(int i) {
        for (int k = 0; k < pages.length; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setAlpha(k == i ? 1f : 0.55f);
            tabs[k].setTypeface(k == i ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    void refreshStatus() {
        int on = link.connectedCount();
        String s = on + "/" + link.lamps.size() + " lámpa csatlakozva";
        if (link.scanning) s += " – keresés…";
        else if (link.connecting != null) s += " – csatlakozás: " + link.connecting.name;
        if (link.scanError != 0) s += " – keresési hiba: " + (link.scanError < 0 ? "nincs Bluetooth-engedély" : link.scanError);
        status.setText(link.lamps.isEmpty() && link.scanning ? "Lámpák keresése…" : s);
    }

    // ---------------------------------------------------------------- LampLink.Listener

    @Override public void onScanStarted() { seenView.setText(""); }

    @Override public void onScanStopped() {
        if (!link.lamps.isEmpty()) return;
        seenView.setText(link.seen.isEmpty()
            ? "A keresés semmit nem látott: Bluetooth/helymeghatározás be? „Közeli eszközök” engedély?"
            : "Nincs LED… nevű eszköz. Látott nevek: " + link.seen);
    }

    @Override public void onSeen(String name) { seenView.setText("Látott: " + link.seen); }

    @Override public void onFound(Lamp lamp) { }

    @Override public void onReady(Lamp lamp) {
        if (!rows.containsKey(lamp)) addRow(lamp);
        rows.get(lamp).label.setTextColor(Color.WHITE);
        Integer kept = hpByMac.get(lamp.mac());
        if (kept != null) lamp.hp = Math.min(kept, hpMax);
        showHp(false);
        rebuildPeopleButtons();
        // The lamp kept its colour across the drop; re-apply what THIS lamp last showed, not the phone-wide mood.
        if (levelup == null) moodFrames(lamp, lamp.mood != null ? lamp.mood : mood);
    }

    @Override public void onDropped(Lamp lamp) {
        Row r = rows.get(lamp);
        if (r != null) r.label.setTextColor(0x88FFFFFF);
    }

    @Override public void onForgotten(Lamp lamp) { removeRow(lamp); }

    @Override public void onStateChanged() { refreshStatus(); }

    // ---------------------------------------------------------------- lamp selection

    boolean wanted(Lamp l) { return whoAll || whoSel != null && whoSel.equals(l.mac()); }

    List<Lamp> selected() {
        List<Lamp> r = new ArrayList<>();
        for (Lamp l : link.lamps.values()) if (l.connected() && wanted(l)) r.add(l);
        return r;
    }

    /** Selected lamps in wearer order (PEOPLE), unnamed ones last in discovery order. */
    List<Lamp> ordered() {
        List<Lamp> r = selected();
        r.sort((x, y) -> Integer.compare(rank(x), rank(y)));
        return r;
    }

    static int personIdx(String p) {
        if (p != null) for (int i = 0; i < PEOPLE.length; i++) if (PEOPLE[i].equals(p)) return i;
        return -1;
    }

    static int rank(Lamp l) { int i = personIdx(l.person); return i < 0 ? 99 : i; }

    /** Bit i set when PEOPLE[i] has a lamp on this phone. */
    int myMask() {
        int m = 0;
        for (Lamp l : link.lamps.values()) { int i = personIdx(l.person); if (i >= 0) m |= 1 << i; }
        return m;
    }

    String myName() {
        for (Lamp l : link.lamps.values()) if (l.person != null) return l.person;
        return "Valaki";
    }

    // ---------------------------------------------------------------- moods

    void loadWhite() {
        String[] p = prefs.getString("white", "255,255,255").split(",");
        RGB.put("white", new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
    }

    /** Re-send what the selected lamps show, e.g. after a colour-correction change. */
    void reapply() {
        for (Lamp l : selected()) if (l.mood != null && !l.mood.startsWith("anim:")) moodFrames(l, l.mood);
    }

    /** Power on and set brightness (unless the pulse drives it): the start of every colour change. */
    void wake(Lamp l) {
        link.enqueue(l, Proto.on(l.family));
        if (!pulse) link.enqueue(l, Proto.brightness(l.family, brightness));
    }

    /** Every switch: new epoch, no leftover frames in the targeted lamps' queues, no running fades on them. */
    void fresh() {
        if (!inShow) stopShow();   // any explicit action (tap, slider, vote, accept, attack) ends the show
        epoch++;
        groupMode = false;         // and whatever session we were in; invitations switch it back on afterwards
        stopAnim();
        if (levelup != null) { ui.removeCallbacks(levelup); levelup = null; }
        for (Lamp l : selected()) { l.fadeId++; l.queue.clear(); }
    }

    void applyMood(String m) {
        mood = m;
        if (RGB.containsKey(m)) base = RGB.get(m);
        fresh();
        List<Lamp> sel = selected();
        if (sel.isEmpty()) toast("Nincs kiválasztott, csatlakozott lámpa");
        for (Lamp l : sel) moodFrames(l, m);
    }

    /** Frames that put one lamp into mood m. */
    void moodFrames(Lamp l, String m) {
        l.mood = m;
        l.fadeId++;
        if ("off".equals(m)) { link.enqueue(l, Proto.off(l.family)); l.colour = new int[]{0, 0, 0}; return; }
        wake(l);
        if ("rainbow".equals(m)) link.enqueue(l, Proto.mode(l.family, l.family == Proto.SMART ? 1 : RAINBOW_EFFECT));
        else if (m.startsWith("fx:")) { link.enqueue(l, Proto.mode(l.family, Integer.parseInt(m.substring(3)))); link.enqueue(l, Proto.speed(l.family, speed)); }
        else if (m.startsWith("anim:")) { /* the running animation feeds the colours */ }
        else if (m.startsWith("rgb:")) fadeTo(l, parseRgb(m.substring(4)));
        else if ("hp".equals(m)) { if (l.hp <= 0) link.enqueue(l, Proto.mode(l.family, RED_FLASH_EFFECT)); else fadeTo(l, hpColour(l)); }
        else fadeTo(l, RGB.get(m));
    }

    static int[] parseRgb(String s) {
        String[] p = s.split(",");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
    }

    /** Sims-style blend: slide from the lamp's current colour to the target over ~1 s instead of snapping. */
    void fadeTo(Lamp l, int[] to) {
        int[] from = l.colour.clone();
        final int id = ++l.fadeId;
        for (int i = 1; i <= 10; i++) {
            float t = i / 10f;
            int[] c = {Frames.lerp(from[0], to[0], t), Frames.lerp(from[1], to[1], t), Frames.lerp(from[2], to[2], t)};
            ui.postDelayed(() -> { if (l.fadeId == id && l.connected()) setColour(l, c); }, i * 100L);
        }
    }

    /** The one place colours leave the phone: the green/blue correction for this string is applied here. */
    void setColour(Lamp l, int[] c) {
        l.colour = c;
        link.enqueue(l, Proto.rgb(l.family, c[0], c[1] * gainG / 100, c[2] * gainB / 100));
    }

    /** White/green flashing for 3 s, then back to happy green. */
    void levelUp() {
        fresh();
        mood = "green";
        for (Lamp l : selected()) wake(l);
        levelup = new Runnable() {
            int step = 0;
            public void run() {
                int[] c = RGB.get(step % 2 == 0 ? "white" : "green");
                for (Lamp l : selected()) setColour(l, c);
                if (++step < 12) ui.postDelayed(this, 250);
                else { levelup = null; applyMood("green"); }
            }
        };
        levelup.run();
    }

    /** Blink one lamp white/blue so the wearer can tell which row is theirs, then put its mood back. */
    void identify(Lamp l) {
        if (!l.connected()) return;
        final int ep = epoch;
        l.fadeId++;
        link.enqueue(l, Proto.on(l.family));
        for (int i = 0; i < 6; i++) {
            int[] c = RGB.get(i % 2 == 0 ? "white" : "blue");
            ui.postDelayed(() -> { if (ep == epoch) setColour(l, c); }, i * 300L);
        }
        ui.postDelayed(() -> { if (ep == epoch) moodFrames(l, mood); }, 1800);
    }

    // Finger lands on the scale: stop whatever runs and make sure the lamps are on.
    void slideStart() {
        fresh();
        for (Lamp l : selected()) wake(l);
    }

    // Dragging: at most ~8 colour frames a second, the final position always goes out.
    void slideMood(int pct, boolean fin) {
        long now = System.currentTimeMillis();
        if (!fin && now - lastSlide < 120) return;
        lastSlide = now;
        int[] c = Frames.plumbobColor(pct / 100f);
        base = c;
        mood = "rgb:" + c[0] + "," + c[1] + "," + c[2];
        for (Lamp l : selected()) {
            l.mood = mood;
            l.fadeId++;
            if (fin || l.queue.size() < 2) setColour(l, c);
        }
    }

    // Needs decay: every 30 s the scale moves 5 % towards red (10 min from green to red), then stays red.
    void setDecay(boolean on) {
        if (decay != null) { ui.removeCallbacks(decay); decay = null; }
        if (!on) return;
        decay = new Runnable() {
            public void run() {
                int pos = Math.min(100, moodBar.getProgress() + 5);
                moodBar.setProgress(pos);
                if (anim == null && levelup == null) { slideStart(); slideMood(pos, true); }
                ui.postDelayed(this, DECAY_STEP_MS);
            }
        };
        ui.postDelayed(decay, DECAY_STEP_MS);
    }

    // Slow breathing: brightness swings between 40 % and 100 % of the slider over 3 s, on every connected lamp.
    void setPulse(boolean on) {
        pulse = on;
        if (pulser != null) ui.removeCallbacks(pulser);
        if (!on) { for (Lamp l : link.lamps.values()) if (l.connected()) link.enqueue(l, Proto.brightness(l.family, brightness)); return; }
        pulseT0 = System.currentTimeMillis();
        pulser = new Runnable() {
            public void run() {
                double ph = (System.currentTimeMillis() - pulseT0) / (double) PULSE_PERIOD_MS * 2 * Math.PI;
                int pct = Math.max(1, (int) Math.round(brightness * (0.7 + 0.3 * Math.sin(ph))));
                for (Lamp l : link.lamps.values()) if (l.connected() && l.queue.isEmpty()) link.enqueue(l, Proto.brightness(l.family, pct));
                ui.postDelayed(this, 150);
            }
        };
        pulser.run();
    }

    final SensorEventListener shakeCb = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) {
            if (!shake) return;
            double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81;
            long now = System.currentTimeMillis();
            if (g > 2.4 && now - lastShake > 1200) {   // a real shake, not a pocket wobble; one mood per shake
                lastShake = now;
                String[] moods = {"green", "yellow", "orange", "red", "pink", "white", "turquoise"};
                String pick = moods[rnd.nextInt(moods.length)];
                applyMood(pick);
                toast("Rázás: " + pick);
            }
        }
        public void onAccuracyChanged(Sensor s, int a) { }
    };

    // ---------------------------------------------------------------- animations

    void stopAnim() {
        if (anim != null) { ui.removeCallbacks(anim); anim = null; }
        stopMic();
    }

    /** One animation at a time; each tick sends one frame to every selected lamp (skipped when its queue is backed up). */
    void startAnim(int idx) {
        fresh();
        groupMode = false;   // a plain tap runs over this phone's own lamps; invitations switch group mode on
        curNonce = rnd.nextInt(65536);   // fresh seed, so a local game is not the same every time; a session overwrites it
        mood = "anim:" + idx;
        for (Lamp l : selected()) { l.mood = mood; wake(l); }
        animStep = 0;
        if (idx == Frames.MIC) { startMic(); return; }   // startMic sets the 'anim' marker only once capture really runs
        animWait = Frames.isPerLamp(idx) ? Frames.period(idx) : Frames.shared(idx, 0, 0)[3];   // nonce 0: every phone agrees on the tick
        anim = new Runnable() {
            public void run() {
                List<Lamp> sel = ordered();
                int total = Math.max(1, sel.size());
                long now = System.currentTimeMillis();
                int clockStep = (int) ((now / animWait) % 1000000);   // per-lamp animations: same on every phone whose clock is right
                long[] at = groupMode && !Frames.isPerLamp(idx) ? Frames.stepAt(idx, now, curNonce) : null;   // shared ones keep their own holds
                int[] shared = Frames.isPerLamp(idx) ? null : Frames.shared(idx, at != null ? (int) at[0] : animStep, curNonce);
                int wait = shared != null ? shared[3] : animWait;
                int rosterSize = Math.max(1, Integer.bitCount(roster));
                for (int k = 0; k < sel.size(); k++) {
                    Lamp l = sel.get(k);
                    int slot = groupMode && shared == null ? slotOf(rank(l)) : -1;
                    int[] f = shared != null ? shared
                        : slot >= 0 ? Frames.perLamp(idx, clockStep, slot, rosterSize, base, curNonce)
                        : Frames.perLamp(idx, animStep, k, total, base, curNonce);
                    if (l.queue.size() < 3) setColour(l, new int[]{f[0], f[1], f[2]});
                }
                animStep++;
                long next = at != null ? at[1] - now : groupMode ? wait - (now % wait) : wait;   // group: wake on the clock boundary
                ui.postDelayed(this, Math.max(1, next));
            }
        };
        ui.post(anim);   // first tick after the caller returned, so an invitation's groupMode/nonce/roster are already set
    }

    /** Position of PEOPLE[i] inside the roster (people in the group, PEOPLE order), -1 when not in it. */
    int slotOf(int i) {
        if (i < 0 || i >= PEOPLE.length || (roster & (1 << i)) == 0) return -1;
        return Integer.bitCount(roster & ((1 << i) - 1));
    }

    // Microphone-reactive: the base colour scaled by the loudness of what the phone hears, ~10 frames a second.
    // A generation counter (not a flag) stops the previous capture thread: it is blocked in read() and would miss a
    // momentary false; posted frames of an old generation are dropped too.
    void startMic() {
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 3);
            return;
        }
        final int g = ++micGen;
        micRunning = true;
        anim = () -> { };   // marker: onStop keeps the connections alive while the mic drives the lamps
        // Started here, in the foreground, so the microphone type is allowed and keeps working with the screen off.
        startForegroundService(new Intent(this, KeepAliveService.class).putExtra("mic", true));
        new Thread(() -> micLoop(g)).start();
    }

    void stopMic() { micGen++; micRunning = false; }

    void micLoop(int g) {
        int rate = 16000, n = 1600;
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(n * 2, AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)));
            rec.startRecording();
            short[] buf = new short[n];
            double peak = 500;
            while (g == micGen) {
                int got = rec.read(buf, 0, n);
                if (got <= 0) continue;
                double sum = 0;
                for (int i = 0; i < got; i++) sum += (double) buf[i] * buf[i];
                double rms = Math.sqrt(sum / got);
                peak = Math.max(rms, peak * 0.97);   // adaptive ceiling: quiet rooms still move
                double level = Math.max(0.06, Math.min(1.0, rms / Math.max(peak, 300)));
                int[] c = {(int) (base[0] * level), (int) (base[1] * level), (int) (base[2] * level)};
                ui.post(() -> { if (g != micGen) return; for (Lamp l : selected()) if (l.queue.size() < 2) setColour(l, c); });
            }
        } catch (Exception e) {
            ui.post(() -> toast("Mikrofon hiba: " + e.getMessage()));
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
        }
    }

    // ---------------------------------------------------------------- group sessions over Beacons

    void sendInvite(int idx) {
        stopShow();
        startAnim(idx);
        groupMode = true;
        myNonce = curNonce = rnd.nextInt(65536);
        roster = myMask();
        if (send(Beacons.INVITE, idx, curNonce, 20000)) toast("Meghívás küldve: " + Frames.ANIMS[idx][0] + " (20 s)");
    }

    int lastSentRoster;   // what our last session beacon carried; re-advertise only when we know more than that

    /** Session beacons (invite/accept) carry everyone we know of, so late joiners and the host converge on one roster. */
    boolean send(int kind, int idx, int nonce, int ms) {
        boolean session = kind == Beacons.INVITE || kind == Beacons.ACCEPT;
        if (session) lastSentRoster = roster;
        boolean ok = beacons.send(kind, idx, nonce, session ? roster : myMask(), base, myName(), ms);
        if (!ok) toast("Ez a telefon nem tud BLE-hirdetést küldeni");
        return ok;
    }

    @Override public void onSendFailed(int err) { toast("Meghívás hiba: " + err); }

    @Override public void onBeacon(Beacons.Msg m) {
        notePresence(m.mask);   // every beacon says who is on the sending phone
        switch (m.kind) {
            case Beacons.PRESENCE: break;
            case Beacons.ATTACK: onAttack(m); break;
            case Beacons.ACK: onAck(m); break;
            case Beacons.MOOD: onMood(m); break;
            case Beacons.VOTE_RESET: onVoteReset(m); break;
            case Beacons.VOTE: onVote(m); break;
            case Beacons.REACT_START: onReactStart(m); break;
            case Beacons.REACT: onReact(m); break;
            default: onInviteOrAccept(m);
        }
    }

    void onInviteOrAccept(Beacons.Msg m) {
        if (m.idx >= Frames.ANIMS.length) return;
        if (groupMode && m.nonce == curNonce) {   // our session: anyone's beacon grows the roster on every phone
            if ((roster | m.mask) != roster) { roster |= m.mask; toast(m.from + " benne van"); }
            // The sender knows fewer people than we do: tell everyone once per roster change, then stay quiet.
            if ((m.mask | roster) != m.mask && roster != lastSentRoster) send(Beacons.ACCEPT, m.idx, curNonce, 30000);
            return;
        }
        if (groupMode && curNonce == myNonce && m.kind == Beacons.INVITE) {   // two hosts invited at once: the lower nonce wins, nobody is asked
            if (m.nonce < curNonce) acceptInvite(m);
            return;
        }
        if (m.kind != Beacons.INVITE || m.nonce == myNonce || m.nonce == lastInviteNonce) return;
        lastInviteNonce = m.nonce;
        new AlertDialog.Builder(this)
            .setTitle("Meghívás")
            .setMessage(m.from + " meghív: " + Frames.ANIMS[m.idx][0] + ". Csatlakozol?")
            .setPositiveButton("Igen", (d, w) -> acceptInvite(m))
            .setNegativeButton("Nem", null)
            .show();
    }

    void acceptInvite(Beacons.Msg m) {
        stopShow();
        int known = groupMode ? roster : 0;   // a host switching sessions keeps the people it already knows
        showPage(2);
        base = m.colour;   // run the chase in the inviter's colour so all lamps match
        startAnim(m.idx);
        groupMode = true;
        curNonce = m.nonce;
        roster = known | m.mask | myMask();
        send(Beacons.ACCEPT, m.idx, m.nonce, 30000);   // tell the others (and the sender) we are in
    }

    // ---------------------------------------------------------------- life points, one counter per lamp

    int[] hpColour(Lamp l) { return l.hp <= 0 ? new int[]{255, 0, 0} : Frames.plumbobColor(1f - (l.hp - 1f) / Math.max(1, hpMax - 1)); }

    /** Text like "Tamás 87/100   Bianka 92/100" for the lamps the "Kinek" choice covers. */
    void showHp(boolean apply) {
        StringBuilder s = new StringBuilder();
        for (Lamp l : ordered()) s.append(s.length() > 0 ? "   " : "").append(l.shortName()).append(' ').append(l.hp).append('/').append(hpMax);
        hpView.setText(s.length() == 0 ? "Életerő: nincs csatlakozott lámpa" : s.toString());
        if (apply) applyMood("hp");
    }

    void addHp(int d) {
        for (Lamp l : selected()) l.hp = Math.max(0, Math.min(hpMax, l.hp + d));
        showHp(true);
    }

    /** New maximum for everyone, refilled: every connected lamp gets its colour, not only the "Kinek" ones. */
    void setHpMax(int m) {
        hpMax = m;
        mood = "hp";
        fresh();
        for (Lamp l : link.lamps.values()) {
            l.hp = m;
            if (l.connected()) { l.queue.clear(); moodFrames(l, "hp"); }
        }
        showHp(false);
    }

    /** One lamp takes a hit: white flash, then its HP colour (or the dead red flash). */
    void hit(Lamp l, String from) {
        l.hp = Math.max(0, l.hp - 1);
        toast(from + " megtámadta: " + l.shortName() + " (−1)");
        boolean interrupted = anim != null || levelup != null;
        fresh();   // stops any animation (and the mic) phone-wide, new epoch
        mood = "hp";
        if (interrupted) for (Lamp x : link.lamps.values()) if (x != l && x.connected()) moodFrames(x, "hp");   // siblings must not freeze on their last animation frame
        l.fadeId++;
        l.queue.clear();
        final int ep = epoch;
        wake(l);
        setColour(l, RGB.get("white"));
        ui.postDelayed(() -> { if (ep == epoch && l.connected()) moodFrames(l, "hp"); }, 200);
        showHp(false);
    }

    void sendAttack(int target) {
        int nonce = rnd.nextInt(65536);
        remember(seenAttacks, nonce);
        for (Lamp l : lampsOf(target)) hit(l, myName());   // the target's lamp is on this phone
        if (send(Beacons.ATTACK, target, nonce, 10000)) toast("Támadás: " + PEOPLE[target] + "…");
    }

    void onAttack(Beacons.Msg m) {
        if (seenAttacks.contains(m.nonce) || m.idx >= PEOPLE.length || (myMask() & (1 << m.idx)) == 0) return;
        remember(seenAttacks, m.nonce);
        showPage(3);
        for (Lamp l : lampsOf(m.idx)) hit(l, m.from);
        send(Beacons.ACK, m.idx, m.nonce, 6000);   // tell the attacker it landed
    }

    void onAck(Beacons.Msg m) {
        if (!seenAttacks.contains(m.nonce) || seenAcks.contains(m.nonce)) return;
        remember(seenAcks, m.nonce);
        toast("Talált: " + m.from);
    }

    List<Lamp> lampsOf(int personIndex) {
        List<Lamp> r = new ArrayList<>();
        for (Lamp l : link.lamps.values()) if (l.connected() && PEOPLE[personIndex].equals(l.person)) r.add(l);
        return r;
    }

    static void remember(ArrayDeque<Integer> seen, int nonce) {
        seen.add(nonce);
        while (seen.size() > 32) seen.poll();
    }

    // ---------------------------------------------------------------- updates

    int versionCode() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    /** GitHub release tag "v<versionCode>": newer than ours -> download and install (silently where Android allows it). */
    void checkUpdate(boolean quiet) {
        if (!quiet) toast("Frissítés keresése…");
        new Thread(() -> {
            String msg;
            try {
                int latest = Updater.latestCode();
                if (latest < 0) msg = quiet ? null : "Nincs még kiadás a GitHubon.";
                else if (latest <= versionCode()) msg = quiet ? null : "Ez a legfrissebb (v" + latest + ").";
                else {
                    ui.post(() -> toast("Új verzió: v" + latest + " – települ…"));
                    Updater.install(this);
                    msg = "v" + latest + " letöltve; ha a telefon kérdez, Telepítés, különben magától frissül.";
                }
            } catch (Exception e) {
                msg = quiet ? null : "Frissítés hiba: " + e.getMessage();
            }
            String m = msg;
            if (m != null) ui.post(() -> { toast(m); status.setText(m); });
        }).start();
    }

    // ---------------------------------------------------------------- small view helpers

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    LinearLayout hrow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); }

    TextView text(String s, int colour, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(sp);
        return t;
    }

    TextView hint(String s) {
        TextView t = text(s, 0xCCFFFFFF, 13);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    GridLayout grid(int columns) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(columns);
        return g;
    }

    interface IntConsumer { void accept(int v); }

    /** 0..max slider that reports the value when the finger lifts. */
    SeekBar seekBar(int max, int initial, IntConsumer onStop) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(initial);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean user) { }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { onStop.accept(b.getProgress()); }
        });
        return s;
    }

    /** Rounded, white-bordered Sims-style button for the grids. */
    Button pill(String label, String colour, int textSp, int heightPx, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(textSp);
        b.setTextColor(Color.BLACK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colour));
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(3), Color.WHITE);
        b.setBackground(bg);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = heightPx;
        lp.setMargins(dp(5), dp(5), dp(5), dp(5));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }
}
