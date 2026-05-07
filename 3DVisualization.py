import argparse
import re
import sys

import numpy as np
import pyqtgraph.opengl as gl
import serial
from serial.tools import list_ports
from pyqtgraph.Qt import QtCore, QtWidgets, QtGui

SERIAL_PORT = None
BAUD_RATE = 115200
DEBUG = False

# If the numeric values match Arduino but the 3D board moves the wrong way,
# adjust only these signs/offsets. The serial data itself stays roll,pitch,yaw.
ROLL_SIGN = 1
PITCH_SIGN = 1
YAW_SIGN = 1
ROLL_OFFSET = 0
PITCH_OFFSET = 0
YAW_OFFSET = 0

def available_ports():
    return list(list_ports.comports())

def print_available_ports():
    ports = available_ports()
    if not ports:
        print("No serial ports found.")
        return

    print("Available serial ports:")
    for port in ports:
        details = f" - {port.device}"
        if port.description:
            details += f": {port.description}"
        print(details)

def auto_detect_port():
    ports = available_ports()
    if not ports:
        return None

    likely_device_ports = [
        port.device for port in ports
        if any(
            token in f"{port.description} {port.manufacturer}".lower()
            for token in ("arduino", "usb serial", "ch340", "cp210", "ftdi")
        )
    ]
    if len(likely_device_ports) == 1:
        return likely_device_ports[0]

    non_builtin_ports = [
        port.device for port in ports
        if port.device.upper() != "COM1"
    ]
    if len(non_builtin_ports) == 1:
        return non_builtin_ports[0]

    if len(ports) == 1:
        return ports[0].device

    return None

def parse_args():
    parser = argparse.ArgumentParser(description="MPU9250 3D serial visualizer")
    parser.add_argument(
        "--port",
        default=SERIAL_PORT,
        help="Serial port to open, for example COM3. Auto-detects when omitted.",
    )
    parser.add_argument(
        "--baud",
        type=int,
        default=BAUD_RATE,
        help=f"Serial baud rate. Default: {BAUD_RATE}",
    )
    parser.add_argument(
        "--list-ports",
        action="store_true",
        help="Print available serial ports and exit.",
    )
    return parser.parse_args()

args = parse_args()

if args.list_ports:
    print_available_ports()
    sys.exit(0)

selected_port = args.port or auto_detect_port()
if selected_port is None:
    print("Could not auto-detect a serial port.")
    print_available_ports()
    print("\nRun again with a port, for example: python .\\3DVisualization.py --port COM3")
    sys.exit(1)

try:
    ser = serial.Serial(selected_port, args.baud, timeout=0.1)
    ser.reset_input_buffer()
except serial.SerialException as exc:
    print(f"Could not open serial port {selected_port!r}: {exc}")
    print_available_ports()
    print("\nCheck the Arduino IDE Serial Monitor is closed, then run with the correct port:")
    print("python .\\3DVisualization.py --port COM3")
    sys.exit(1)

app = QtWidgets.QApplication([])
w = gl.GLViewWidget()
w.setWindowTitle("IMU 3D Viewer")
w.setCameraPosition(distance=10)
w.show()

grid = gl.GLGridItem()
grid.scale(2, 2, 1)
w.addItem(grid)

axis = gl.GLAxisItem()
axis.setSize(5, 5, 5)
w.addItem(axis)

# -------- BOARD --------
verts = np.array([
    [-2, -1, 0], [2, -1, 0], [2, 1, 0], [-2, 1, 0],
    [-2, -1, 0.2], [2, -1, 0.2], [2, 1, 0.2], [-2, 1, 0.2],
])

faces = np.array([
    [0,1,2],[0,2,3],
    [4,5,6],[4,6,7],
    [0,1,5],[0,5,4],
    [2,3,7],[2,7,6],
    [1,2,6],[1,6,5],
    [0,3,7],[0,7,4],
])

colors = np.array([[0.2,0.7,1,0.6]] * len(faces))
mesh = gl.GLMeshItem(vertexes=verts, faces=faces, faceColors=colors)
w.addItem(mesh)

# -------- TEXT --------
text = gl.GLTextItem(color=(255,255,255))
w.addItem(text)

# -------- EMA --------
ema = None
alpha = 1.0  # Changed to 1.0 for no smoothing

def parse(line):
    csv_match = re.match(
        r"^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$",
        line,
    )
    if csv_match:
        return np.array([float(x) for x in csv_match.groups()])

    labeled_match = re.search(
        r"Roll:\s*(-?\d+(?:\.\d+)?).*Pitch:\s*(-?\d+(?:\.\d+)?).*Yaw:\s*(-?\d+(?:\.\d+)?)",
        line,
    )
    if labeled_match:
        return np.array([float(x) for x in labeled_match.groups()])

    return None

def latest_serial_line():
    line = ser.readline()
    while ser.in_waiting:
        line = ser.readline()

    try:
        return line.decode(errors='ignore').strip()
    except UnicodeDecodeError:
        return ""

def apply_visual_mapping(roll, pitch, yaw):
    return (
        ROLL_SIGN * roll + ROLL_OFFSET,
        PITCH_SIGN * pitch + PITCH_OFFSET,
        YAW_SIGN * yaw + YAW_OFFSET,
    )

def transform(roll, pitch, yaw):
    roll, pitch, yaw = apply_visual_mapping(roll, pitch, yaw)

    # Keep the visual Euler convention in one place. If the displayed serial
    # values match Arduino but the board moves backward, change the signs above.
    tr = QtGui.QMatrix4x4()
    tr.rotate(roll, 1, 0, 0)
    tr.rotate(pitch, 0, 1, 0)
    tr.rotate(yaw, 0, 0, 1)
    return tr

def update():
    global ema

    line = latest_serial_line()
    if not line:
        return

    data = parse(line)
    if data is None:
        if DEBUG:
            print(f"ignored line={line!r}")
        return

    if ema is None:
        ema = data
    else:
        ema = alpha * data + (1 - alpha) * ema

    roll, pitch, yaw = ema
    visual_roll, visual_pitch, visual_yaw = apply_visual_mapping(roll, pitch, yaw)

    if DEBUG:
        print(
            f"serial line={line!r} -> "
            f"roll={roll:.2f}, pitch={pitch:.2f}, yaw={yaw:.2f}"
        )

    mesh.setTransform(transform(roll, pitch, yaw))

    text.setData(
        text=(
            f"Serial Roll: {roll:.1f}\n"
            f"Serial Pitch: {pitch:.1f}\n"
            f"Serial Yaw: {yaw:.1f}\n"
            f"Visual Roll: {visual_roll:.1f}\n"
            f"Visual Pitch: {visual_pitch:.1f}\n"
            f"Visual Yaw: {visual_yaw:.1f}"
        ),
        pos=(0, 0, 0),
    )

timer = QtCore.QTimer()
timer.timeout.connect(update)
timer.start(20)

app.exec()
