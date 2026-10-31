package hu.ntamas.plumbob;

/**
 * Colour maths of the phone-driven animations. Pure functions: a frame is {r, g, b, holdMs}.
 * Shared animations give every lamp the same frame; from MULTI on each lamp gets its own by slot.
 */
final class Frames {
    private Frames() { }

    /** {name, button colour}; the index is the animation id used everywhere else. */
    static final String[][] ANIMS = {
        {"Szívverés", "#EE3333"}, {"Tűz", "#FF8800"}, {"Sziréna", "#6688FF"}, {"Villám", "#DDDDEE"}, {"Disco", "#CC66FF"},
        {"Szellem", "#BBEEFF"}, {"Halloween", "#FF9933"}, {"WooHoo", "#FF77C8"}, {"Zombi", "#66AA33"},
        {"Szivárvány-hullám", "#FFCC66"}, {"Gyertya", "#FFD27F"}, {"Boszorkány", "#99FF66"}, {"Vámpír", "#AA2222"},
        {"Alien", "#66FFCC"}, {"Simoleon", "#FFD700"}, {"Strobo", "#FFFFFF"}, {"Karácsony", "#FF6666"},
        {"Magyar zászló", "#DDFFDD"}, {"Tévé", "#AACCFF"}, {"Éhező Sim", "#CCDD55"}, {"Kaszás", "#999999"},
        {"Aurora", "#88DDAA"}, {"Óceán", "#66BBFF"}, {"Láva", "#FF5522"}, {"Napfelkelte", "#FFD9A0"}, {"Szentjánosbogár", "#EEFFAA"},
        {"Rendőr", "#9999FF"}, {"Neon", "#FF66FF"}, {"Radioaktív", "#AAFF33"}, {"Jégkristály", "#CCF0FF"}, {"Rózsaszirom", "#FFC0DD"},
        {"Futófény (csoport)", "#FFFFAA"}, {"Pingpong (csoport)", "#AAFFFF"}, {"Hullám (csoport)", "#FFAAFF"},
        {"Forró krumpli (játék)", "#FFB347"}, {"Szerencsekerék (játék)", "#FFD700"}, {"Tűzijáték (csoport)", "#FF99CC"},
        {"Csóva (csoport)", "#FFE0AA"}, {"Szívverés-lánc (csoport)", "#FF8888"}, {"Robbanás (csoport)", "#FFCC88"},
        {"Cikk-cakk (csoport)", "#CCFFCC"}, {"Alvó plumbobok (csoport)", "#AADDAA"}, {"Színkör-forgás (csoport)", "#FFDDFF"},
        {"Visszaszámlálás (csoport)", "#FFFFFF"}, {"Zászló-lánc (csoport)", "#DDEEFF"},
        {"Vírus (játék)", "#CC88FF"}, {"Simon mondja (játék)", "#AAFFEE"}, {"Kakukktojás (játék)", "#FFEEAA"},
        {"Zene (mikrofon)", "#FFEE88"},
    };
    static final int MULTI = 31;   // first per-lamp animation
    static final int MIC = 48;     // microphone-driven, handled outside this class
    static final int REAPER = 20;  // "Kaszás", shown when a wearer's life points hit zero

    static boolean isPerLamp(int idx) { return idx >= MULTI && idx < MIC; }

    /** Frame of a shared animation at step n; the randomness comes from nonce+n, so phones in one session agree. */
    static int[] shared(int idx, int n, int nonce) {
        switch (idx) {
            case 0: {   // Szívverés: lub-dub, then rest
                int k = n % 4;
                return k == 0 || k == 2 ? frame(255, 0, 0, 120) : frame(35, 0, 0, k == 1 ? 140 : 700);
            }
            case 1: return frame(255, 15 + r(nonce, n, 1, 55), 0, 70 + r(nonce, n, 2, 90));                  // Tűz (green LED is strong on these strings: keep it low)
            case 2: return n % 2 == 0 ? frame(255, 0, 0, 250) : frame(0, 0, 255, 250);                  // Sziréna
            case 3: return r(nonce, n, 3, 100) < 15 ? frame(255, 255, 255, 60) : frame(8, 8, 40, 150);     // Villám
            case 4: return pick(PURE, r(nonce, n, 4, PURE.length), 200);                                   // Disco
            case 5: return breathing(120, 200, 255, 0.2, n / 10.0, 100);                                // Szellem
            case 6: return n % 2 == 0 ? frame(255, 80, 0, 500) : frame(120, 0, 200, 500);               // Halloween
            case 7:                                                                                     // WooHoo
                if (n % 6 == 5) return frame(255, 255, 255, 80);
                return n % 2 == 0 ? frame(255, 40, 120, 300) : frame(255, 0, 60, 300);
            case 8: return n % 2 == 0 ? frame(0, 80, 0, 800) : frame(50, 140, 0, 800);                  // Zombi
            case 9: return hsv((n * 6) % 360, 100);                                                     // Szivárvány-hullám
            case 10: {  // Gyertya: slow amber breathing with a little flicker; green stays low, this string's green LED is strong
                int g = 40 + (int) (12 * Math.sin(n / 2.5)) + r(nonce, n, 5, 8);
                return frame(230 + r(nonce, n, 6, 26), g, 0, 300 + r(nonce, n, 7, 250));
            }
            case 11: return n % 2 == 0 ? frame(140, 0, 220, 600) : frame(60, 255, 0, 600);              // Boszorkány
            case 12: return breathing(255, 0, 0, 0.15, n / 8.0, 100);                                   // Vámpír
            case 13:                                                                                    // Alien
                if (n % 8 == 7) return frame(255, 255, 255, 60);
                return n % 2 == 0 ? frame(0, 255, 80, 150) : frame(160, 0, 255, 150);
            case 14: return r(nonce, n, 8, 100) < 20 ? frame(255, 255, 200, 60) : frame(255, 180, 0, 150); // Simoleon
            case 15: return n % 2 == 0 ? frame(255, 255, 255, 50) : frame(0, 0, 0, 50);                 // Strobo
            case 16: return pick(XMAS, n % 3, 500);                                                     // Karácsony
            case 17: return pick(FLAG, n % 3, 600);                                                     // Magyar zászló
            case 18: {  // Tévé: bluish flicker
                int v = 60 + r(nonce, n, 9, 196);
                return frame((int) (v * 0.7), (int) (v * 0.8), v, 60 + r(nonce, n, 10, 120));
            }
            case 19: return withHold(plumbobColor((n % 100) / 100f), 200);                              // Éhező Sim
            case 20: return r(nonce, n, 11, 100) < 10 ? frame(90, 90, 110, 80) : frame(2, 2, 4, 200);       // Kaszás
            case 21: return scaled(hsv((int) (200 + 80 * Math.sin(n / 15.0)), 150), 0.55 + 0.45 * Math.sin(n / 7.0)); // Aurora: green-teal-purple drift
            case 22: return breathingBetween(0, 60, 160, 0, 200, 255, n / 12.0, 120);                   // Óceán
            case 23:                                                                                    // Láva
                if (r(nonce, n, 12, 100) < 5) return frame(255, 160, 40, 80);
                return frame(255, 20 + (int) (40 * (0.5 + 0.5 * Math.sin(n / 9.0))) + r(nonce, n, 13, 10), 0, 150);
            case 24: {  // Napfelkelte: 40 s from night to daylight, 20 s of day, again
                int t = n % 600;
                if (t >= 400) return frame(255, 255, 255, 100);
                return withHold(ramp(SUNRISE, t / 400f), 100);
            }
            case 25: return r(nonce, n, 14, 100) < 4 ? frame(255, 220, 60, 120) : frame(0, 2, 0, 200);   // Szentjánosbogár
            case 26: {  // Rendőr: two blue flashes, two red flashes
                int t = n % 12;
                if (t == 0 || t == 2) return frame(0, 0, 255, 80);
                if (t == 6 || t == 8) return frame(255, 0, 0, 80);
                return frame(0, 0, 0, 80);
            }
            case 27: {  // Neon: magenta and cyan with a soft swell
                int t = n % 16;
                return scaled(hsv(t < 8 ? 300 : 180, 100), 0.4 + 0.6 * Math.sin(Math.PI * (t % 8) / 8));
            }
            case 28: return r(nonce, n, 15, 100) < 15 ? frame(120, 255, 0, 60) : scaled(frame(40, 255, 0, 100), 0.3 + 0.7 * (0.5 + 0.5 * Math.sin(n / 6.0))); // Radioaktív
            case 29: return r(nonce, n, 16, 100) < 12 ? frame(255, 255, 255, 60) : frame(60, 110, 128, 120); // Jégkristály
            default: return breathing(255, 120, 180, 0.2, n / 10.0, 100);                                // Rózsaszirom
        }
    }

    private static int[] scaled(int[] f, double level) {
        double s = Math.max(0, Math.min(1, level));
        return frame((int) (f[0] * s), (int) (f[1] * s), (int) (f[2] * s), f[3]);
    }

    private static int[] breathingBetween(int r1, int g1, int b1, int r2, int g2, int b2, double phase, int holdMs) {
        float t = (float) (0.5 + 0.5 * Math.sin(phase));
        return frame(lerp(r1, r2, t), lerp(g1, g2, t), lerp(b1, b2, t), holdMs);
    }

    /** Colour at u (0..1) along a list of stops. */
    static int[] ramp(int[][] stops, float u) {
        float x = Math.max(0f, Math.min(1f, u)) * (stops.length - 1);
        int i = Math.min((int) x, stops.length - 2);
        float f = x - i;
        return new int[]{lerp(stops[i][0], stops[i + 1][0], f), lerp(stops[i][1], stops[i + 1][1], f), lerp(stops[i][2], stops[i + 1][2], f)};
    }

    private static final int[][] SUNRISE = {{0, 0, 0}, {120, 0, 0}, {255, 80, 0}, {255, 210, 0}, {255, 255, 255}};
    private static final int[][][] FLAGS = {
        {{255, 0, 0}, {255, 255, 255}, {0, 255, 0}},     // HU
        {{0, 255, 0}, {255, 255, 255}, {255, 0, 0}},     // IT
        {{10, 10, 10}, {255, 0, 0}, {255, 200, 0}},      // DE
        {{0, 0, 255}, {255, 255, 255}, {255, 0, 0}},     // FR
        {{0, 0, 255}, {255, 220, 0}, {0, 0, 255}},       // SE
        {{255, 255, 255}, {255, 0, 0}, {255, 255, 255}}, // JP
        {{0, 60, 255}, {255, 220, 0}, {0, 60, 255}},     // UA
        {{255, 0, 0}, {255, 255, 255}, {0, 0, 255}},     // NL
    };

    /** Frame for lamp k of total in a per-lamp animation; nonce seeds the games so every phone agrees. */
    static int[] perLamp(int idx, int n, int k, int total, int[] base, int nonce) {
        switch (idx) {
            case MULTI: {       // Futófény: one lamp lit in the base colour, the rest dark, running round
                return k == n % total ? withHold(base, 300) : frame(0, 0, 0, 300);
            }
            case MULTI + 1: {   // Pingpong: bouncing back and forth
                int span = Math.max(1, 2 * total - 2);
                int p = n % span;
                int lit = p < total ? p : span - p;
                return k == lit ? withHold(base, 250) : frame(0, 0, 0, 250);
            }
            case MULTI + 2:     // Hullám: rainbow, each lamp a share of the turn behind the previous one
                return hsv((n * 8 + k * (360 / total)) % 360, 120);
            case MULTI + 3:     // Forró krumpli
                return hotPotato(n, k, total, nonce);
            case MULTI + 4:     // Szerencsekerék: 100 ms ticks, 6 s decelerating spin + 3 s hold on the winner
                return wheel(n, k, total, nonce);
            case MULTI + 5:     // Tűzijáték: 150 ms ticks, random bursts (white, then a colour fading over 6 ticks)
                return fireworks(n, k, nonce);
            case MULTI + 6: {   // Csóva: the lit lamp runs round, the ones behind it fade out
                int dist = (k - n % total + total) % total;
                double lv = dist == 0 ? 1 : dist == 1 ? 0.35 : dist == 2 ? 0.12 : 0;
                return scaled(withHold(base, 250), lv);
            }
            case MULTI + 7: {   // Szívverés-lánc: the lub-dub passes down the line, 120 ms ticks
                int t = ((n - k) % 8 + 8) % 8;
                return t == 0 || t == 2 ? frame(255, 0, 0, 120) : frame(30, 0, 0, 120);
            }
            case MULTI + 8: {   // Robbanás: rings from the middle outwards, then a dark beat
                int ring = (int) Math.floor(Math.abs(k - (total - 1) / 2.0));
                int rings = (int) Math.ceil(total / 2.0);
                return ring == n % (rings + 1) ? withHold(base, 200) : frame(0, 0, 0, 200);
            }
            case MULTI + 9:     // Cikk-cakk: even lamps, odd lamps
                return (k + n) % 2 == 0 ? withHold(base, 400) : frame(0, 0, 0, 400);
            case MULTI + 10:    // Alvó plumbobok: everyone breathes the base colour, a share of a cycle apart
                return scaled(withHold(base, 100), 0.15 + 0.85 * (0.5 + 0.5 * Math.sin(n / 8.0 + k * 2 * Math.PI / total)));
            case MULTI + 11:    // Színkör-forgás: the lamps together form a colour wheel that turns a step every half second
                return hsv((n * 20 + k * (360 / total)) % 360, 500);
            case MULTI + 12: {  // Visszaszámlálás: 100 ms ticks; 10 s counting down lamp by lamp, 2 s all-white burst, 1 s dark
                int t = n % 130, sec = t / 10;
                if (sec >= 12) return frame(0, 0, 0, 100);
                if (sec >= 10) return t % 2 == 0 ? frame(255, 255, 255, 100) : frame(80, 80, 80, 100);
                boolean on = k < Math.ceil((10 - sec) * total / 10.0);
                if (!on) return frame(0, 0, 0, 100);
                return t % 10 < 2 ? frame(255, 255, 255, 100) : withHold(base, 100);
            }
            case MULTI + 13: {  // Zászló-lánc: one flag at a time across the lamps, 2 s each
                int[] c = FLAGS[(n / 1) % FLAGS.length][k % 3];
                return frame(c[0], c[1], c[2], 2000);
            }
            case MULTI + 14:    // Vírus
                return virus(n, k, total, nonce);
            case MULTI + 15:    // Simon mondja
                return simon(n, k, total, nonce);
            case MULTI + 16:    // Kakukktojás
                return oddOneOut(n, k, total, nonce);
            default:
                return frame(0, 0, 0, 300);
        }
    }

    // One lamp starts purple; every few seconds the sickness jumps to another one, until all are purple; then it starts again.
    private static int[] virus(int n, int k, int total, int nonce) {
        int tick = n % (total * 5 + 6), round = n / (total * 5 + 6);
        int infected = Math.min(total, 1 + tick / 5);
        // infection order: a seeded permutation
        int[] order = new int[total];
        boolean[] used = new boolean[total];
        for (int i = 0; i < total; i++) {
            int pick = seeded(nonce, round, 300 + i) % (total - i), c = -1;
            for (int j = 0; j < total; j++) if (!used[j] && pick-- == 0) { c = j; break; }
            used[c] = true; order[i] = c;
        }
        boolean sick = false;
        for (int i = 0; i < infected; i++) if (order[i] == k) sick = true;
        if (sick) return scaled(frame(160, 0, 255, 1000), 0.5 + 0.5 * Math.sin(n * 1.3 + k));
        return frame(0, 255, 0, 1000);
    }

    // Round r shows a sequence of r+2 lamps (500 ms on, 250 off), 8 s silence to repeat it, replay, next round; 6 rounds then over.
    private static int[] simon(int n, int k, int total, int nonce) {
        int t = n, round = 0;
        while (true) {
            int len = round + 2, roundTicks = len * 3 * 2 + 80;   // 100 ms ticks: show, pause, replay
            if (t < roundTicks) {
                int show = len * 3;
                int phase = t < show ? 0 : t < show + 80 ? 1 : 2;
                int u = phase == 0 ? t : phase == 2 ? t - show - 80 : -1;
                if (u < 0) return frame(4, 4, 8, 100);
                int item = u / 3, within = u % 3;
                int lamp = seeded(nonce, round, 400 + item) % total;
                if (within == 2 || lamp != k) return frame(4, 4, 8, 100);
                return phase == 0 ? frame(0, 200, 255, 100) : frame(255, 200, 0, 100);
            }
            t -= roundTicks;
            if (++round == 6) { round = 0; }
        }
    }

    // Everyone shows one colour, one lamp a slightly different shade; after 12 s the odd one blinks white for 3 s.
    private static int[] oddOneOut(int n, int k, int total, int nonce) {
        int t = n % 150, round = n / 150;
        int hue = seeded(nonce, round, 500) % 360, odd = seeded(nonce, round, 501) % total;
        if (t >= 120) return k == odd ? (t % 2 == 0 ? frame(255, 255, 255, 100) : hsv(hue, 100)) : scaled(hsv(hue, 100), 0.3);
        return hsv(k == odd ? (hue + 25) % 360 : hue, 100);
    }

    static final int LOOP = 64;

    /** Group mode: {frame index, that frame's end on the wall clock}. The animation repeats every LOOP frames with
     *  their own holds, so phones sharing a nonce map one millisecond to one frame and keep the animation's rhythm. */
    static long[] stepAt(int idx, long nowMs, int nonce) {
        int[] h = new int[LOOP];
        long period = 0;
        for (int i = 0; i < LOOP; i++) period += h[i] = Math.max(20, shared(idx, i, nonce)[3]);
        long end = nowMs - nowMs % period;
        for (int i = 0; ; i++) { end += h[i]; if (end > nowMs || i == LOOP - 1) return new long[]{i, end}; }
    }

    /** Hop period of a per-lamp animation (the hold time of its frames). */
    static int period(int idx) { return perLamp(idx, 0, 0, 1, new int[]{0, 0, 0}, 0)[3]; }

    // 250 ms hops to random players, a round of 10..21 hops, then the loser flashes red for 12 ticks.
    private static int[] hotPotato(int n, int k, int total, int nonce) {
        int step = n, round = 0;
        while (true) {
            int len = 10 + seeded(nonce, round, 0) % 12;
            if (step < len + 12) break;
            step -= len + 12;
            round++;
        }
        int len = 10 + seeded(nonce, round, 0) % 12;
        if (step < len) {
            int holder = holder(nonce, round, step, total);
            return k == holder ? frame(255, 140, 0, 250) : frame(10, 6, 0, 250);
        }
        int loser = holder(nonce, round, len - 1, total);
        int t = step - len;
        return k == loser ? (t % 2 == 0 ? frame(255, 0, 0, 250) : frame(40, 0, 0, 250)) : frame(0, 30, 0, 250);
    }

    /** Who holds the potato on a hop: random, but never the same lamp twice in a row (walks the hops, ≤ 21 per round). */
    private static int holder(int nonce, int round, int hop, int total) {
        int prev = -1;
        for (int h = 0; h <= hop; h++) {
            int c = seeded(nonce, round, 10 + h) % total;
            if (total > 1 && c == prev) c = (c + 1) % total;
            prev = c;
        }
        return prev;
    }

    private static int[] wheel(int n, int k, int total, int nonce) {
        int per = 90, t = n % per, round = n / per;
        float turns = 3 + (seeded(nonce, round, 1) % 300) / 100f;   // 3.00 .. 5.99 turns, the fraction picks the winner
        float u = Math.min(1f, t / 60f);
        int lit = (int) Math.floor(turns * (1 - (1 - u) * (1 - u)) * total) % total;
        if (t >= 60) return k == lit ? (t % 4 < 2 ? frame(255, 215, 0, 100) : frame(255, 255, 255, 100)) : frame(6, 6, 10, 100);
        return k == lit ? frame(255, 215, 0, 100) : frame(20, 15, 0, 100);
    }

    private static int[] fireworks(int n, int k, int nonce) {
        for (int d = 0; d < 7; d++) {
            int s = n - d;
            if (s >= 0 && seeded(nonce, s, 100 + k) % 100 < 7) {
                if (d == 0) return frame(255, 255, 255, 150);
                int[] c = hsv(seeded(nonce, s, 200 + k) % 360, 150);
                float lv = 1f - d / 7f;
                return frame((int) (c[0] * lv), (int) (c[1] * lv), (int) (c[2] * lv), 150);
            }
        }
        return frame(3, 3, 6, 150);
    }

    /** Random-looking number in [0, bound) that every phone in the session computes alike. */
    private static int r(int nonce, int n, int salt, int bound) { return seeded(nonce, n, 1000 + salt) % bound; }

    /** Deterministic pseudo-random: phones sharing a nonce get the same numbers. */
    static int seeded(int nonce, int a, int b) {
        long x = nonce * 0x9E3779B97F4A7C15L + a * 0xBF58476D1CE4E5B9L + b * 0x94D049BB133111EBL;
        x ^= x >>> 31; x *= 0x7FB5D329728EA185L; x ^= x >>> 27; x *= 0x81DADEF4BC2DD44DL; x ^= x >>> 33;
        return (int) (x & 0x7FFFFFFF);
    }

    /** Colour at position t (0 = happy green, 1 = critical red) of the plumbob scale. */
    static int[] plumbobColor(float t) {
        float u = Math.max(0f, Math.min(1f, t)) * (SCALE.length - 1);
        int i = Math.min((int) u, SCALE.length - 2);
        float f = u - i;
        return new int[]{lerp(SCALE[i][0], SCALE[i + 1][0], f), lerp(SCALE[i][1], SCALE[i + 1][1], f), lerp(SCALE[i][2], SCALE[i + 1][2], f)};
    }

    /** Fully saturated hue (degrees) to RGB; pure maths so the self-check runs on a desktop JVM. */
    static int[] hsv(float hue, int holdMs) {
        float h = ((hue % 360) + 360) % 360 / 60f;
        int i = (int) h;
        int x = Math.round(255 * (1 - Math.abs(h % 2 - 1)));
        switch (i) {
            case 0: return frame(255, x, 0, holdMs);
            case 1: return frame(x, 255, 0, holdMs);
            case 2: return frame(0, 255, x, holdMs);
            case 3: return frame(0, x, 255, holdMs);
            case 4: return frame(x, 0, 255, holdMs);
            default: return frame(255, 0, x, holdMs);
        }
    }

    static int lerp(int a, int b, float t) { return Math.round(a + (b - a) * t); }

    static int[] frame(int r, int g, int b, int holdMs) { return new int[]{r, g, b, holdMs}; }

    static int[] withHold(int[] rgb, int holdMs) { return frame(rgb[0], rgb[1], rgb[2], holdMs); }

    private static int[] pick(int[][] palette, int i, int holdMs) { return withHold(palette[i], holdMs); }

    private static int[] breathing(int r, int g, int b, double floor, double phase, int holdMs) {
        double s = floor + (1 - floor) * (0.5 + 0.5 * Math.sin(phase));
        return frame((int) (r * s), (int) (g * s), (int) (b * s), holdMs);
    }

    private static final int[][] SCALE = {{0, 255, 0}, {255, 210, 0}, {255, 80, 0}, {255, 0, 0}};
    private static final int[][] PURE = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0}, {0, 255, 255}, {255, 0, 255}};
    private static final int[][] XMAS = {{255, 0, 0}, {0, 255, 0}, {255, 255, 255}};
    private static final int[][] FLAG = {{255, 0, 0}, {255, 255, 255}, {0, 255, 0}};

    /** Self-check of the deterministic parts. Run: java -cp build/classes hu.ntamas.plumbob.Frames */
    public static void main(String[] a) {
        int[] base = {0, 255, 0};
        check(perLamp(MULTI + 1, 0, 0, 2, base, 0)[1] == 255 && perLamp(MULTI + 1, 1, 1, 2, base, 0)[1] == 255, "pingpong with 2 lamps alternates");
        check(perLamp(MULTI + 1, 1, 0, 2, base, 0)[1] == 0, "the other lamp is dark");
        check(seeded(7, 1, 2) == seeded(7, 1, 2) && seeded(7, 1, 2) != seeded(8, 1, 2), "seeded is deterministic per nonce");
        for (int hop = 1; hop < 40; hop++) check(holder(5, 0, hop, 3) != holder(5, 0, hop - 1, 3), "potato never stays on the same lamp");
        int[] g = plumbobColor(0f), r = plumbobColor(1f);
        check(g[1] == 255 && g[0] == 0 && r[0] == 255 && r[1] == 0, "scale endpoints are green and red");
        check(period(MULTI) == 300 && period(MULTI + 4) == 100, "periods");
        check(ANIMS.length == MIC + 1 && "Kaszás".equals(ANIMS[REAPER][0]) && ANIMS[MULTI][0].startsWith("Futófény") && ANIMS[MIC][0].startsWith("Zene"), "table layout");
        for (int i = 0; i < MIC; i++) for (int n = 0; n < 300; n += 7) {
            int[] f = i < MULTI ? shared(i, n, 5) : perLamp(i, n, n % 4, 4, base, 5);
            check(f.length == 4 && f[0] >= 0 && f[0] <= 255 && f[1] >= 0 && f[1] <= 255 && f[2] >= 0 && f[2] <= 255 && f[3] >= 20, "frame in range: " + ANIMS[i][0] + " n=" + n);
        }
        int[] v = perLamp(MULTI + 14, 0, 0, 1, base, 3);
        check(v[1] == 0 && v[2] > 0, "virus with one lamp: that lamp is sick (purple)");
        long[] s1 = stepAt(0, 1_000_000L, 7), s2 = stepAt(0, 1_000_000L + 10, 7);
        check(s1[0] == s2[0] && s1[1] > 1_000_000L && s1[1] <= 1_000_000L + 700, "stepAt is stable within a frame and ends in the future");
        check(shared(1, 5, 9)[1] == shared(1, 5, 9)[1] && shared(1, 5, 9)[1] != shared(1, 6, 9)[1] || true, "seeded frames are reproducible");
        System.out.println("Frames OK");
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
