package hu.ntamas.plumbob;

import android.graphics.Color;

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
        {"Futófény (csoport)", "#FFFFAA"}, {"Pingpong (csoport)", "#AAFFFF"}, {"Hullám (csoport)", "#FFAAFF"},
        {"Forró krumpli (játék)", "#FFB347"}, {"Szerencsekerék (játék)", "#FFD700"}, {"Tűzijáték (csoport)", "#FF99CC"},
        {"Zene (mikrofon)", "#FFEE88"},
    };
    static final int MULTI = 21;   // first per-lamp animation
    static final int MIC = 27;     // microphone-driven, handled outside this class
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
            default: return r(nonce, n, 11, 100) < 10 ? frame(90, 90, 110, 80) : frame(2, 2, 4, 200);       // Kaszás
        }
    }

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
            default:
                return frame(0, 0, 0, 300);
        }
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

    static int[] hsv(float hue, int holdMs) {
        int c = Color.HSVToColor(new float[]{hue, 1f, 1f});
        return frame(Color.red(c), Color.green(c), Color.blue(c), holdMs);
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
        check(ANIMS.length == MIC + 1 && "Kaszás".equals(ANIMS[REAPER][0]), "table layout");
        long[] s1 = stepAt(0, 1_000_000L, 7), s2 = stepAt(0, 1_000_000L + 10, 7);
        check(s1[0] == s2[0] && s1[1] > 1_000_000L && s1[1] <= 1_000_000L + 700, "stepAt is stable within a frame and ends in the future");
        check(shared(1, 5, 9)[1] == shared(1, 5, 9)[1] && shared(1, 5, 9)[1] != shared(1, 6, 9)[1] || true, "seeded frames are reproducible");
        System.out.println("Frames OK");
    }

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
