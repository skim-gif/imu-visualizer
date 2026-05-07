# IMU 3D Visualization - Serial Version

This project creates a real-time 3D visualization of IMU (Inertial Measurement Unit) data read from a serial port (USB).

## Java Version

The Java visualizer is `IMU3DVisualization.java`. It uses Swing for the 3D-style display and `jSerialComm` for serial port access.

### Java Requirements

- JDK 11 or newer
- jSerialComm jar from https://fazecast.github.io/jSerialComm/

### Java Setup

1. Put `jSerialComm.jar` in this project folder.

2. Compile:
   ```powershell
   javac .\IMU3DVisualization.java
   ```

3. List serial ports:
   ```powershell
   java -cp ".;jSerialComm.jar" IMU3DVisualization --list-ports
   ```

4. Run:
   ```powershell
   java -cp ".;jSerialComm.jar" IMU3DVisualization --port COM3
   ```

If only one likely Arduino/USB serial device is connected, the Java app can auto-detect the port:

```powershell
java -cp ".;jSerialComm.jar" IMU3DVisualization
```

## Python Version

The original Python visualizer is still available as `3DVisualization.py`.

### Python Requirements

- Python 3.x
- PyQt5
- pyqtgraph
- PyOpenGL
- numpy
- scipy
- pyserial

### Python Setup

1. Install dependencies:
   ```
   pip install pyqtgraph PyQt5 PyOpenGL numpy scipy pyserial
   ```

2. Connect your IMU device to a USB port.

3. Install and upload `MPU9250_SerialPlotter.ino` to your Arduino-compatible board.

4. Check the COM port and adjust `SERIAL_PORT` in `3DVisualization.py` if needed. If `SERIAL_PORT` is `None`, the script will auto-select the first available port.

## Data Format

The Arduino sketch sends each sample as an ASCII line in this format:

```
roll,pitch,yaw\n
```

Each angle is in degrees.

### Python Running

Run the script:
```
python 3DVisualization.py
```

A 3D window will open showing a rotating cube that represents the IMU orientation.

## Notes

- Both visualizers expect roll, pitch, and yaw angles in degrees.
- The Java visualizer currently uses no smoothing (`alpha=1.0`), matching the current Python file.
- Press Ctrl+C in the terminal to stop the script.
- The serial port is automatically closed when the application exits.
