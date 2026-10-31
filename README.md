# Plumbob LED

Android app a Sims-plumbob jelmez lámpáihoz (Somogyi HOME ML 100 RGB SMART, a gyári „LED LAMP” app lámpái). Bluetooth LE, egy telefonról akár négy lámpa, hangulat-színek, effektek, személyenkénti lámpaválasztás, beépített frissítés.

- `app_ledble/` – az app forrása (Gradle nélkül: `build.ps1` = aapt2 → javac → d8 → zipalign → apksigner). `release.ps1` buildel és GitHub release-t tesz közzé `v<versionCode>` taggel; az app ⚙ → „Frissítés keresése” gombja ezt tölti le.
- `ledble_protokoll.md` – a LED LAMP app visszafejtett BLE-protokollja (service FFE0, 9 bájtos `7E FF … EF` keretek).
- `plumbob_esp32/` – korábbi, ESP32 + WS2812B változat (saját protokoll).

Telepítés: a legfrissebb release `PlumbobLED.apk`-ja, „ismeretlen forrás” engedéllyel. A gyári LED LAMP app legyen zárva, egy lámpához egyszerre egy telefon kapcsolódhat.
