/*
 * CanonPrint Bridge for ESP32 (EXPERIMENTAL, untested on hardware)
 *
 * A pocket-sized alternative to the Raspberry Pi / Android bridges:
 * the ESP32 opens its own Wi-Fi access point for the camera and relays
 * TCP port 9100 to the Canon Ivy 2 over Bluetooth Classic (SPP/RFCOMM).
 * It also answers the camera's UDP discovery broadcast on port 9101.
 *
 * Requirements:
 *  - An original ESP32 (ESP32-WROOM/WROVER). The S2, S3, C3, C6 and H2
 *    variants do NOT support Bluetooth Classic and will not work.
 *  - Arduino IDE with the "esp32" boards package (2.x or 3.x),
 *    Partition scheme "Huge APP" (Bluetooth Classic + Wi-Fi is large).
 *
 * Configure PRINTER_ADDRESS below (find it with a phone or `bluetoothctl`,
 * the printer shows up as "Canon (xx:xx) Mini Printer").
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <BluetoothSerial.h>

// ---- configuration ---------------------------------------------------------
static const char *AP_SSID = "CANONPRINT-BRIDGE";
static const char *AP_PASSWORD = "canonprint";  // at least 8 characters
static uint8_t PRINTER_ADDRESS[6] = {0x04, 0x7F, 0x0E, 0x00, 0x00, 0x00};
// ---------------------------------------------------------------------------

static const uint16_t TCP_PORT = 9100;
static const uint16_t DISCOVERY_PORT = 9101;
static const size_t RFCOMM_CHUNK = 990;  // same as the reference client
static const unsigned long IDLE_TIMEOUT_MS = 180000;

WiFiServer server(TCP_PORT);
WiFiUDP udp;
BluetoothSerial bt;

static void answerDiscovery() {
  int size = udp.parsePacket();
  if (size <= 0) return;
  char buf[64];
  int n = udp.read(buf, sizeof(buf) - 1);
  if (n <= 0) return;
  buf[n] = 0;
  if (strncmp(buf, "CANONPRINT_BRIDGE_DISCOVER", 19) != 0) return;
  udp.beginPacket(udp.remoteIP(), udp.remotePort());
  udp.print("CANONPRINT_BRIDGE port=9100 name=ESP32 bridge");
  udp.endPacket();
  Serial.printf("discovery request from %s\n", udp.remoteIP().toString().c_str());
}

static bool connectPrinter() {
  if (bt.connected()) return true;
  Serial.println("connecting to printer...");
  if (bt.connect(PRINTER_ADDRESS)) {
    Serial.println("printer connected");
    return true;
  }
  Serial.println("printer connection failed");
  return false;
}

static void relay(WiFiClient &client) {
  uint8_t buf[RFCOMM_CHUNK];
  size_t toPrinter = 0, toCamera = 0;
  unsigned long lastActivity = millis();
  while (client.connected() && bt.connected()) {
    bool active = false;
    int n = client.available();
    if (n > 0) {
      n = client.read(buf, min((size_t)n, sizeof(buf)));
      if (n > 0) {
        bt.write(buf, n);
        toPrinter += n;
        active = true;
      }
    }
    n = bt.available();
    if (n > 0) {
      n = bt.readBytes(buf, min((size_t)n, sizeof(buf)));
      if (n > 0) {
        client.write(buf, n);
        toCamera += n;
        active = true;
      }
    }
    if (active) {
      lastActivity = millis();
    } else {
      if (millis() - lastActivity > IDLE_TIMEOUT_MS) break;
      answerDiscovery();
      delay(1);
    }
  }
  Serial.printf("done: %u bytes to printer, %u bytes to camera\n", (unsigned)toPrinter, (unsigned)toCamera);
}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASSWORD);
  Serial.printf("Wi-Fi AP \"%s\" at %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  server.begin();
  server.setNoDelay(true);
  udp.begin(DISCOVERY_PORT);
  bt.begin("canonprint-bridge", true);  // Bluetooth master mode
}

void loop() {
  answerDiscovery();
  WiFiClient client = server.available();
  if (!client) {
    delay(10);
    return;
  }
  Serial.printf("camera connected from %s\n", client.remoteIP().toString().c_str());
  client.setNoDelay(true);
  if (connectPrinter()) {
    relay(client);
    // Drop the Bluetooth link so the printer can power off / accept its phone app.
    bt.disconnect();
  }
  // Closing without data tells the camera the printer was unreachable.
  client.stop();
}
