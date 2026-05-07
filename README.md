# IMU 3D Visualization — MPU9250 Serial Plotter

A real-time 3D visualization tool for IMU (Inertial Measurement Unit) orientation data streamed from an **MPU-9250** sensor via USB/Serial. The display shows a rotating 3D board with labeled X/Y/Z axes that mirrors the physical orientation of your sensor in real time.

![IMU 3D Visualization]
> *Live 3D view showing Roll: -30.1°, Pitch: -62.3°, Yaw: 5.3° with color-coded axes (X = red, Y = green, Z = blue)*

---<img width="1202" height="871" alt="image" src="https://github.com/user-attachments/assets/81435ea6-4ec5-47a5-a8b2-b375a01953c6" />


## How It Works

1. The **MPU9250 SerialPlotter** Arduino sketch reads roll, pitch, and yaw angles from the MPU-9250 IMU and sends them over USB serial in CSV format.
2. The PC-side visualizer (Java or Python) reads that serial data and renders a live 3D view of the sensor orientation.

---

## Arduino — MPU9250 SerialPlotter

### Hardware Required

- Arduino-compatible board (Uno, Nano, ESP32, etc.)
- MPU-9250 IMU module (I²C connection)

### Wiring (I²C)

| MPU-9250 Pin | Arduino Pin |
|---|---|
| VCC | 3.3V or 5V |
| GND | GND |
| SDA | A4 (Uno) / SDA |
| SCL | A5 (Uno) / SCL |

### Upload

1. Open `MPU9250_SerialPlotter.ino` in the Arduino IDE.
2. Select your board and COM port.
3. Upload the sketch.
4. Open the Serial Monitor at **115200 baud** to confirm data is streaming.

### Serial Output Format

Each line sent over serial is:

```
roll,pitch,yaw\n
```

All angles are in **degrees**. Example:

```
-30.1,-62.3,5.3
```

---

## PC Visualizer — Java Version

`IMU3DVisualization.java` uses Swing for the 3D-style display and `jSerialComm` for serial port access.

### Requirements

- JDK 11 or newer
- [jSerialComm](https://fazecast.github.io/jSerialComm/) `.jar`

### Setup & Run

1. Place `jSerialComm.jar` in the project folder.

2. Compile:
   ```powershell
   javac .\IMU3DVisualization.java
   ```

3. List available serial ports:
   ```powershell
   java -cp ".;jSerialComm.jar" IMU3DVisualization --list-ports
   ```

4. Run on a specific port:
   ```powershell
   java -cp ".;jSerialComm.jar" IMU3DVisualization --port COM3
   ```

5. Auto-detect (if only one USB/Arduino device is connected):
   ```powershell
   java -cp ".;jSerialComm.jar" IMU3DVisualization
   ```

---

## PC Visualizer — Python Version

`3DVisualization.py` is the original Python-based visualizer.

### Requirements

- Python 3.x
- PyQt5
- pyqtgraph
- PyOpenGL
- numpy
- scipy
- pyserial

### Setup & Run

1. Install dependencies:
   ```bash
   pip install pyqtgraph PyQt5 PyOpenGL numpy scipy pyserial
   ```

2. Connect your Arduino with the MPU9250 SerialPlotter sketch uploaded.

3. Check the COM port and adjust `SERIAL_PORT` in `3DVisualization.py` if needed.  
   Set `SERIAL_PORT = None` to auto-select the first available port.

4. Run the visualizer:
   ```bash
   python 3DVisualization.py
   ```

A 3D window opens showing a board that rotates to match your sensor's orientation in real time.

---

## Display

| Element | Color | Description |
|---|---|---|
| X axis | 🔴 Red | Points right |
| Y axis | 🟢 Green | Points up |
| Z axis | 🔵 Blue | Points forward |
| Board | Cyan | Represents the physical IMU |

The top-left corner displays live **Serial** (raw) and **Visual** (smoothed) roll, pitch, and yaw values in degrees.

---

## Notes

- Both visualizers expect angles in **degrees** (roll, pitch, yaw).
- The Java visualizer uses no smoothing (`alpha = 1.0`), matching the current Python defaults.
- Press **Ctrl+C** in the terminal to stop either visualizer.
- The serial port is automatically closed on exit.
