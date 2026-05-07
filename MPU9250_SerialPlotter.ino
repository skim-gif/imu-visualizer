#include <Wire.h>
#include "MPU9250.h"

MPU9250 mpu;

const int BAUD_RATE = 115200;

void setup() {
  Serial.begin(BAUD_RATE);
  Wire.begin();
  Wire.setClock(400000);

  if (!mpu.setup(0x68)) {
    while (1) {
      Serial.println("MPU9250 not found!");
      delay(1000);
    }
  }

  Serial.println("Calibrating... keep sensor still");
  delay(2000);

  mpu.calibrateAccelGyro();

  Serial.println("Ready!");
}

void loop() {
  if (!mpu.update()) return;

  // Match WIFI.ino: use the MPU9250 library's fused orientation values.
  Serial.print(mpu.getRoll(), 2);
  Serial.print(",");
  Serial.print(mpu.getPitch(), 2);
  Serial.print(",");
  Serial.println(mpu.getYaw(), 2);
}
