#include <SoftwareSerial.h>

// HC-05 TXD -> Arduino D10
// HC-05 RXD <- Arduino D11 through a suitable voltage divider
SoftwareSerial bluetooth(10, 11); // Arduino RX, TX

const byte outputPins[8] = {2, 3, 4, 5, 6, 7, 8, 9};
String receivedCommand = "";

void setup() {
  Serial.begin(9600);
  bluetooth.begin(9600);

  for (byte i = 0; i < 8; i++) {
    pinMode(outputPins[i], OUTPUT);
    digitalWrite(outputPins[i], LOW);
  }
  bluetooth.println("SMART_OVERLOOK_READY");
}

void processCommand(String command) {
  command.trim();
  command.toUpperCase();

  if (command == "ALL_OFF") {
    for (byte i = 0; i < 8; i++) digitalWrite(outputPins[i], LOW);
    bluetooth.println("OK,ALL_OFF");
    return;
  }

  for (byte i = 0; i < 8; i++) {
    String onCommand = "OUT" + String(i + 1) + "_ON";
    String offCommand = "OUT" + String(i + 1) + "_OFF";

    if (command == onCommand) {
      digitalWrite(outputPins[i], HIGH);
      bluetooth.println("OK,OUT" + String(i + 1) + ",ON");
      return;
    }
    if (command == offCommand) {
      digitalWrite(outputPins[i], LOW);
      bluetooth.println("OK,OUT" + String(i + 1) + ",OFF");
      return;
    }
  }
  bluetooth.println("ERROR,UNKNOWN_COMMAND");
}

void loop() {
  while (bluetooth.available()) {
    char c = bluetooth.read();
    if (c == '\\n' || c == '\\r') {
      if (receivedCommand.length() > 0) {
        processCommand(receivedCommand);
        receivedCommand = "";
      }
    } else if (receivedCommand.length() < 40) {
      receivedCommand += c;
    } else {
      receivedCommand = "";
      bluetooth.println("ERROR,TOO_LONG");
    }
  }
}
