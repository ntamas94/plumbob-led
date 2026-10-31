# LED BLE protokoll – HOME ML 100 RGB SMART (Somogyi) / „LED LAMP” app

Forrás: com.ledlamp 4.3.8 APK (`com.home.net.NetConnectBle`, `ServicesFragment`, `res/array/ble_mode`), jadx. 2026-10-30.

## GATT
- Hirdetett név: `LED…` prefix (az app csak `startsWith("LED")` eszközt mutat). Család a névből: `LEDBLE-00-` … `LEDBLE-05-` (sima RGB), `LEDSMART`, `LEDDMX-xx-`, `LEDCAR-xx-` stb.
- Service `0000ffe0-0000-1000-8000-00805f9b34fb`
- Parancs-karakterisztika `0000ffe1-…` – **write with response** (az app `writeCharacteristic` alapértelmezett típussal ír), ugyanezen jön notify is (jelszó-válasz).
- `0000ffe2-…` definiálva, az app nem használja.
- Nincs MTU-kérés, nincs connection priority. Egy parancs = pontosan 9 bájt.

## Keret (LEDBLE család)
`7E FF <cmd> <a> <b> <c> <d> <e> EF` – 9 bájt. Az `LEDBLE-03/04/05` altípusnál néhány parancs rövid formájú: `7E <cmd> <a> <b> FF FF FF FF EF`.

| Művelet | Bájtok (hex) | Megjegyzés |
|---|---|---|
| Be | `7E FF 04 01 00 FF FF 00 EF` | |
| Ki | `7E FF 04 00 00 FF FF 00 EF` | |
| Szín | `7E FF 05 03 RR GG BB FF EF` | app 50 ms-enként max. egyet küld |
| Fényerő 0–100 | `7E FF 01 BB 00 FF FF FF EF` | 03/04/05: `7E 01 BB 00 FF FF FF FF EF`; app 30 ms-es torlasztás |
| Effekt mód | `7E FF 03 MM 03 FF FF FF EF` | 03/04/05: `7E 03 MM 00 FF FF FF FF EF` |
| Sebesség | `7E FF 02 SS 00 FF FF FF EF` | 03/04/05: `7E 02 SS 00 FF FF FF FF EF` |
| Színsorrend | `7E FF 08 … ` (SetRgbSort) | RGB=1 RBG=2 GRB=3 GBR=4 BRG=5 BGR=6 |
| Zene/mikrofon | `7E FF 06 …` (setMusicMicroMode) | nem kell |
| Idő-beállítás | `8E …` (sendTime) | időzítő, nem kell |

Effekt módszámok (`ble_mode`): 135 tricolor jump, 136 seven-color jump, 137 tricolor gradient, **138 seven-color gradient (szivárvány)**, 139 red gradient, 140 green, 141 blue, 142 yellow, 143 cyan, 144 purple, 145 white gradient, 146 red-green, 147 red-blue, 148 green-blue, 149 seven-color flash, 150 red flash, **151 green flash**, 152 blue flash, 153 yellow flash, 154 cyan flash, 155 purple flash, 156 white flash, 157 seven-color breathe.

## Jelszó-kézfogás (csatlakozás után 300 ms-mal az app elküldi)
`2A 02 A1 23 45 67 <nap<<5 | óra> <perc> AF`
- `02` = ellenőrzés; alapjelszó `A1 23 45 67` (ha a felhasználó nem állított mást).
- nap: hétfő=1 … vasárnap=7 (app: `{7,1,2,3,4,5,6}[Calendar.DAY_OF_WEEK-1]`), óra 0–23.
- Válasz notify FFE1-en: `2A 05 <st> …`; st=1 rossz jelszó, 2 tiltott jelszó, 3 → app `2A 05 FF FF FF FF FF FF AF`-ot küld vissza. Jelszó nélküli lámpa nem válaszol, a parancsok ettől függetlenül mennek.
- Jelszó beállítás: `2A 01 …`? (setPassword(i=…) az UI-ból; nem kell.)

## LED SMART család (ha a lámpa `LEDSMART`-ként hirdet)
`7D <grp> <cmd> … DF`: be `7D 01 01 01 FF FF FF FF DF`, ki `7D 01 01 00 …`, szín `7D 02 01 FF RR GG BB FF DF`, fényerő `7D 02 02 BB FF FF FF FF DF`, mód `7D 02 05 MM …`, sebesség `7D 02 04 SS …`.

## Miért szakad a gyári app
- fastble fork: `setReConnectCount(50)`, szakadás után 8 s-enként **újra scan-el** csatlakozott eszközök mellett; `refreshDeviceCache` reflexióval minden hibánál.
- minden write válaszos, torlasztás csak UI-oldalon (30–100 ms), sorba állítás nincs → egymásra futó write-ok.
- Saját app: egy GATT/lámpa, write-sor (következő csak `onCharacteristicWrite` után), szakadáskor közvetlen újracsatlakozás MAC-re scan nélkül, visszacsatlakozás után az aktuális hangulat újraküldése.
