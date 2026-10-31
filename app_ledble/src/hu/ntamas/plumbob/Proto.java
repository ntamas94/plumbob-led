package hu.ntamas.plumbob;

/** 9-byte frames of the "LED LAMP" app (see ../ledble_protokoll.md). Plain Java, no Android. */
final class Proto {
    private Proto() { }

    /** Frame family, picked from the advertised name. */
    static final int BLE = 0, BLE_SHORT = 1, SMART = 2;

    static int family(String name) {
        if (name == null) return BLE;
        if (name.contains("LEDSMART")) return SMART;
        if (name.contains("LEDBLE-03-") || name.contains("LEDBLE-04-") || name.contains("LEDBLE-05-")) return BLE_SHORT;
        return BLE;
    }

    static byte[] f(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static byte[] on(int fam) {
        return fam == SMART ? f(0x7D, 1, 1, 1, 0xFF, 0xFF, 0xFF, 0xFF, 0xDF) : f(0x7E, 0xFF, 4, 1, 0, 0xFF, 0xFF, 0, 0xEF);
    }

    static byte[] off(int fam) {
        return fam == SMART ? f(0x7D, 1, 1, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xDF) : f(0x7E, 0xFF, 4, 0, 0, 0xFF, 0xFF, 0, 0xEF);
    }

    static byte[] rgb(int fam, int r, int g, int b) {
        return fam == SMART ? f(0x7D, 2, 1, 0xFF, r, g, b, 0xFF, 0xDF) : f(0x7E, 0xFF, 5, 3, r, g, b, 0xFF, 0xEF);
    }

    static byte[] brightness(int fam, int pct) {
        pct = Math.max(0, Math.min(100, pct));
        if (fam == SMART)     return f(0x7D, 2, 2, pct, 0xFF, 0xFF, 0xFF, 0xFF, 0xDF);
        if (fam == BLE_SHORT) return f(0x7E, 1, pct, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF);
        return f(0x7E, 0xFF, 1, pct, 0, 0xFF, 0xFF, 0xFF, 0xEF);
    }

    /** Built-in effect. LEDBLE: 135..157 (138 = seven-colour gradient, 151 = green flash). SMART numbering unverified. */
    static byte[] mode(int fam, int m) {
        if (fam == SMART)     return f(0x7D, 2, 5, m, 0xFF, 0xFF, 0xFF, 0xFF, 0xDF);
        if (fam == BLE_SHORT) return f(0x7E, 3, m, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF);
        return f(0x7E, 0xFF, 3, m, 3, 0xFF, 0xFF, 0xFF, 0xEF);
    }

    /** Effect speed 0..100. */
    static byte[] speed(int fam, int s) {
        s = Math.max(0, Math.min(100, s));
        if (fam == SMART)     return f(0x7D, 2, 4, s, 0xFF, 0xFF, 0xFF, 0xFF, 0xDF);
        if (fam == BLE_SHORT) return f(0x7E, 2, s, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF);
        return f(0x7E, 0xFF, 2, s, 0, 0xFF, 0xFF, 0xFF, 0xEF);
    }

    /** Password check the official app sends 300 ms after connecting to LEDBLE lamps. day: Monday=1 .. Sunday=7. Default password A1 23 45 67. */
    static byte[] password(int day, int hour, int minute) {
        return f(0x2A, 2, 0xA1, 0x23, 0x45, 0x67, (day << 5) | hour, minute, 0xAF);
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02X ", x));
        return s.toString().trim();
    }

    /** Self-check against frames lifted from the decompiled app. Run: java -cp build/classes hu.ntamas.plumbob.Proto */
    public static void main(String[] a) {
        check("7E FF 04 01 00 FF FF 00 EF", on(BLE));
        check("7E FF 04 00 00 FF FF 00 EF", off(BLE));
        check("7E FF 05 03 FF 5A 00 FF EF", rgb(BLE, 255, 90, 0));
        check("7E FF 01 64 00 FF FF FF EF", brightness(BLE, 150));
        check("7E 01 32 00 FF FF FF FF EF", brightness(BLE_SHORT, 50));
        check("7E FF 03 8A 03 FF FF FF EF", mode(BLE, 138));
        check("7E 03 8A 00 FF FF FF FF EF", mode(BLE_SHORT, 138));
        check("7D 01 01 01 FF FF FF FF DF", on(SMART));
        check("7D 02 01 FF 00 FF 28 FF DF", rgb(SMART, 0, 255, 40));
        check("7D 02 02 64 FF FF FF FF DF", brightness(SMART, 100));
        check("7E FF 02 32 00 FF FF FF EF", speed(BLE, 50));
        check("7E 02 32 00 FF FF FF FF EF", speed(BLE_SHORT, 50));
        check("2A 02 A1 23 45 67 B2 1E AF", password(5, 18, 30));  // Friday 18:30 -> (5<<5)|18 = 0xB2
        if (family("LEDBLE-03-ABCD") != BLE_SHORT || family("LEDBLE-01-ABCD") != BLE || family("LEDSMART-1") != SMART) throw new AssertionError("family");
        System.out.println("Proto OK");
    }

    private static void check(String want, byte[] got) {
        if (got.length != 9 || !want.equals(hex(got))) throw new AssertionError("want " + want + " got " + hex(got));
    }
}
