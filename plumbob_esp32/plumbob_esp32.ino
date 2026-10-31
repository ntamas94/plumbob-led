// Sims plumbob – ESP32-C3 + WS2812B, controlled over Bluetooth LE by Plumbob.apk.
// The app connects only while it is open; the plumbob keeps its last mood on its own.
// Library: Adafruit NeoPixel (Arduino IDE > Library Manager). Board: ESP32C3 Dev Module (or any ESP32 with BLE).
#include <Adafruit_NeoPixel.h>
#include <BLEDevice.h>
#include <BLEServer.h>

const char* NAME      = "Plumbob-1";  // give each costume its own: Plumbob-1 ... Plumbob-4
const int   LED_PIN   = 5;            // data pin -> 330 ohm -> strip DIN
const int   LED_COUNT = 8;            // LEDs inside the plumbob
#define BLE_SERVICE "b5a1e000-7c3b-4f2a-9a6e-5051ab0b0b01"  // must match the app
#define BLE_CHAR    "b5a1e000-7c3b-4f2a-9a6e-5051ab0b0b02"

Adafruit_NeoPixel strip(LED_COUNT, LED_PIN, NEO_GRB + NEO_KHZ800);

String mode = "green";
unsigned long levelStart = 0;
uint8_t maxBright = 120;  // ponytail: cap keeps LEDs well under USB 500 mA; raise if your powerbank copes

// BLE callbacks run on another task: they only drop the text here, loop() applies it
char bleCmd[16];
volatile bool bleNew = false;

// "green" / "rainbow" / ... or "b:120" for brightness
void applyCmd(const String& c) {
  if (c.startsWith("b:")) maxBright = constrain(c.substring(2).toInt(), 10, 255);
  else { mode = c; levelStart = millis(); }
}

class CmdCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    if (bleNew) return;  // previous command not consumed yet
    String v = c->getValue().c_str();  // getValue() is std::string on core 2.x, String on 3.x
    v.toCharArray(bleCmd, sizeof bleCmd);
    bleNew = true;
  }
};

class ConnCb : public BLEServerCallbacks {
  void onDisconnect(BLEServer*) override { BLEDevice::startAdvertising(); }
};

uint32_t moodColor(const String& m) {
  if (m == "yellow") return strip.Color(230, 200, 0);
  if (m == "orange") return strip.Color(255, 90, 0);
  if (m == "red")    return strip.Color(255, 0, 0);
  return strip.Color(0, 255, 40);  // green
}

void setup() {
  strip.begin();
  strip.show();

  BLEDevice::init(NAME);
  BLEServer* srv = BLEDevice::createServer();
  srv->setCallbacks(new ConnCb());
  BLEService* svc = srv->createService(BLE_SERVICE);
  BLECharacteristic* ch = svc->createCharacteristic(BLE_CHAR,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  ch->setCallbacks(new CmdCb());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(BLE_SERVICE);
  adv->setScanResponse(true);  // name goes in the scan response, the 128-bit UUID fills the advert
  BLEDevice::startAdvertising();
}

void loop() {
  if (bleNew) { applyCmd(String(bleCmd)); bleNew = false; }
  unsigned long t = millis();

  if (mode == "off") {
    strip.clear();
  } else if (mode == "rainbow") {
    strip.setBrightness(maxBright);  // set before writing pixels: NeoPixel brightness is applied on write
    for (int i = 0; i < LED_COUNT; i++)
      strip.setPixelColor(i, strip.gamma32(strip.ColorHSV((t * 20) + i * 65536L / LED_COUNT)));
  } else if (mode == "levelup") {
    // fast white-green flashing for 3 s, then back to happy green
    strip.setBrightness(maxBright);
    strip.fill((t / 120) % 2 ? strip.Color(255, 255, 255) : strip.Color(0, 255, 40));
    if (t - levelStart > 3000) mode = "green";
  } else {
    // slow "breathing" like the in-game plumbob
    float k = 0.55 + 0.45 * sin(t / 600.0);
    strip.setBrightness(maxBright * k);
    strip.fill(moodColor(mode));
  }
  strip.show();
  delay(15);
}
