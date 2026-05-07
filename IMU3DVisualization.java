import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IMU3DVisualization {
    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final double ALPHA = 1.0;
    private static final boolean DEBUG = false;

    private static final double ROLL_SIGN = 1.0;
    private static final double PITCH_SIGN = 1.0;
    private static final double YAW_SIGN = 1.0;
    private static final double ROLL_OFFSET = 0.0;
    private static final double PITCH_OFFSET = 0.0;
    private static final double YAW_OFFSET = 0.0;

    private static final Pattern CSV_PATTERN = Pattern.compile(
            "^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$"
    );
    private static final Pattern LABELED_PATTERN = Pattern.compile(
            "Roll:\\s*(-?\\d+(?:\\.\\d+)?).*Pitch:\\s*(-?\\d+(?:\\.\\d+)?).*Yaw:\\s*(-?\\d+(?:\\.\\d+)?)"
    );

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);

        Options options = Options.parse(args);
        if (options.showHelp) {
            printUsage();
            return;
        }

        if (options.listPorts) {
            printAvailablePorts();
            return;
        }

        String selectedPort = options.portName == null ? autoDetectPort() : options.portName;
        if (selectedPort == null) {
            System.err.println("Could not auto-detect a serial port.");
            printAvailablePorts();
            System.err.println();
            System.err.println("Run again with a port, for example:");
            System.err.println("java -cp \".;jSerialComm.jar\" IMU3DVisualization --port COM3");
            return;
        }

        OrientationModel model = new OrientationModel();
        BoardPanel panel = new BoardPanel(model);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("IMU 3D Viewer");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    model.running = false;
                }
            });

            Timer timer = new Timer(20, event -> panel.repaint());
            timer.start();
        });

        Thread serialThread = new Thread(
                () -> readSerial(selectedPort, options.baudRate, model),
                "imu-serial-reader"
        );
        serialThread.setDaemon(false);
        serialThread.start();
    }

    private static void printUsage() {
        System.out.println("MPU9250 3D serial visualizer");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -cp \".;jSerialComm.jar\" IMU3DVisualization [--port COM3] [--baud 115200]");
        System.out.println("  java -cp \".;jSerialComm.jar\" IMU3DVisualization --list-ports");
    }

    private static void printAvailablePorts() {
        List<SerialPortInfo> ports = availablePorts();
        if (ports.isEmpty()) {
            System.out.println("No serial ports found.");
            return;
        }

        System.out.println("Available serial ports:");
        for (SerialPortInfo port : ports) {
            System.out.println(" - " + port.name + port.describe());
        }
    }

    private static String autoDetectPort() {
        List<SerialPortInfo> ports = availablePorts();
        if (ports.isEmpty()) {
            return null;
        }

        List<SerialPortInfo> likely = new ArrayList<>();
        for (SerialPortInfo port : ports) {
            String details = (port.description + " " + port.manufacturer).toLowerCase(Locale.US);
            if (details.contains("arduino")
                    || details.contains("usb serial")
                    || details.contains("ch340")
                    || details.contains("cp210")
                    || details.contains("ftdi")) {
                likely.add(port);
            }
        }
        if (likely.size() == 1) {
            return likely.get(0).name;
        }

        List<SerialPortInfo> nonBuiltin = new ArrayList<>();
        for (SerialPortInfo port : ports) {
            if (!"COM1".equalsIgnoreCase(port.name)) {
                nonBuiltin.add(port);
            }
        }
        if (nonBuiltin.size() == 1) {
            return nonBuiltin.get(0).name;
        }

        if (ports.size() == 1) {
            return ports.get(0).name;
        }

        return null;
    }

    private static List<SerialPortInfo> availablePorts() {
        List<SerialPortInfo> ports = new ArrayList<>();
        try {
            Class<?> serialPortClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
            Method getCommPorts = serialPortClass.getMethod("getCommPorts");
            Object[] commPorts = (Object[]) getCommPorts.invoke(null);

            for (Object port : commPorts) {
                String device = invokeString(serialPortClass, port, "getSystemPortName");
                String description = firstNonBlank(
                        invokeString(serialPortClass, port, "getDescriptivePortName"),
                        invokeString(serialPortClass, port, "getPortDescription")
                );
                String manufacturer = invokeString(serialPortClass, port, "getManufacturer");
                ports.add(new SerialPortInfo(device, description, manufacturer));
            }
        } catch (ClassNotFoundException ex) {
            System.err.println("jSerialComm was not found.");
            System.err.println("Download jSerialComm and add it to the classpath.");
        } catch (ReflectiveOperationException ex) {
            System.err.println("Could not list serial ports: " + ex.getMessage());
        }
        ports.sort(Comparator.comparing(port -> port.name));
        return ports;
    }

    private static String invokeString(Class<?> type, Object target, String methodName) {
        try {
            Object value = type.getMethod(methodName).invoke(target);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException ex) {
            return "";
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static void readSerial(String portName, int baudRate, OrientationModel model) {
        Object port = null;
        try {
            Class<?> serialPortClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
            port = serialPortClass.getMethod("getCommPort", String.class).invoke(null, portName);

            serialPortClass.getMethod("setBaudRate", int.class).invoke(port, baudRate);
            serialPortClass.getMethod("setComPortTimeouts", int.class, int.class, int.class)
                    .invoke(port, 1, 100, 0);

            boolean opened = (boolean) serialPortClass.getMethod("openPort").invoke(port);
            if (!opened) {
                System.err.println("Could not open serial port " + portName + ".");
                printAvailablePorts();
                return;
            }

            System.out.println("Reading IMU data from " + portName + " at " + baudRate + " baud.");
            InputStream input = (InputStream) serialPortClass.getMethod("getInputStream").invoke(port);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while (model.running && (line = reader.readLine()) != null) {
                    Orientation orientation = parse(line);
                    if (orientation == null) {
                        if (DEBUG) {
                            System.out.println("ignored line=" + line);
                        }
                        continue;
                    }

                    model.update(orientation, ALPHA);
                    if (DEBUG) {
                        System.out.printf(
                                "serial line=%s -> roll=%.2f, pitch=%.2f, yaw=%.2f%n",
                                line,
                                orientation.roll,
                                orientation.pitch,
                                orientation.yaw
                        );
                    }
                }
            }
        } catch (ClassNotFoundException ex) {
            System.err.println("jSerialComm was not found.");
            System.err.println("Run with the jar on the classpath, for example:");
            System.err.println("java -cp \".;jSerialComm.jar\" IMU3DVisualization --port COM3 --baud 115200");
        } catch (ReflectiveOperationException ex) {
            System.err.println("Serial setup failed: " + ex.getMessage());
        } catch (Exception ex) {
            System.err.println("Serial read failed: " + ex.getMessage());
        } finally {
            if (port != null) {
                try {
                    port.getClass().getMethod("closePort").invoke(port);
                } catch (ReflectiveOperationException ignored) {
                    // Nothing useful to do during shutdown.
                }
            }
        }
    }

    private static Orientation parse(String line) {
        Matcher csv = CSV_PATTERN.matcher(line);
        if (csv.matches()) {
            return new Orientation(
                    Double.parseDouble(csv.group(1)),
                    Double.parseDouble(csv.group(2)),
                    Double.parseDouble(csv.group(3))
            );
        }

        Matcher labeled = LABELED_PATTERN.matcher(line);
        if (labeled.find()) {
            return new Orientation(
                    Double.parseDouble(labeled.group(1)),
                    Double.parseDouble(labeled.group(2)),
                    Double.parseDouble(labeled.group(3))
            );
        }

        return null;
    }

    private static final class Options {
        final String portName;
        final int baudRate;
        final boolean listPorts;
        final boolean showHelp;

        private Options(String portName, int baudRate, boolean listPorts, boolean showHelp) {
            this.portName = portName;
            this.baudRate = baudRate;
            this.listPorts = listPorts;
            this.showHelp = showHelp;
        }

        static Options parse(String[] args) {
            String portName = null;
            int baudRate = DEFAULT_BAUD_RATE;
            boolean listPorts = false;
            boolean showHelp = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--port requires a value.");
                        }
                        portName = args[++i];
                        break;
                    case "--baud":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("--baud requires a value.");
                        }
                        baudRate = Integer.parseInt(args[++i]);
                        break;
                    case "--list-ports":
                        listPorts = true;
                        break;
                    case "-h":
                    case "--help":
                        showHelp = true;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }

            return new Options(portName, baudRate, listPorts, showHelp);
        }
    }

    private static final class SerialPortInfo {
        final String name;
        final String description;
        final String manufacturer;

        SerialPortInfo(String name, String description, String manufacturer) {
            this.name = name == null ? "" : name;
            this.description = description == null ? "" : description;
            this.manufacturer = manufacturer == null ? "" : manufacturer;
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (!description.isBlank()) {
                parts.add(description);
            }
            if (!manufacturer.isBlank()) {
                parts.add(manufacturer);
            }
            return parts.isEmpty() ? "" : ": " + String.join(", ", parts);
        }
    }

    private static final class Orientation {
        final double roll;
        final double pitch;
        final double yaw;

        Orientation(double roll, double pitch, double yaw) {
            this.roll = roll;
            this.pitch = pitch;
            this.yaw = yaw;
        }

        Orientation visual() {
            return new Orientation(
                    ROLL_SIGN * roll + ROLL_OFFSET,
                    PITCH_SIGN * pitch + PITCH_OFFSET,
                    YAW_SIGN * yaw + YAW_OFFSET
            );
        }
    }

    private static final class OrientationModel {
        volatile boolean running = true;
        private boolean hasValue = false;
        private Orientation serial = new Orientation(0.0, 0.0, 0.0);

        synchronized void update(Orientation orientation, double alpha) {
            if (!hasValue) {
                serial = orientation;
                hasValue = true;
                return;
            }

            serial = new Orientation(
                    alpha * orientation.roll + (1.0 - alpha) * serial.roll,
                    alpha * orientation.pitch + (1.0 - alpha) * serial.pitch,
                    alpha * orientation.yaw + (1.0 - alpha) * serial.yaw
            );
        }

        synchronized Orientation serial() {
            return serial;
        }
    }

    private static final class BoardPanel extends JPanel {
        private static final double[][] VERTICES = {
                {-2.0, -1.0, -0.1},
                {2.0, -1.0, -0.1},
                {2.0, 1.0, -0.1},
                {-2.0, 1.0, -0.1},
                {-2.0, -1.0, 0.1},
                {2.0, -1.0, 0.1},
                {2.0, 1.0, 0.1},
                {-2.0, 1.0, 0.1}
        };
        private static final int[][] FACES = {
                {0, 1, 2, 3},
                {4, 5, 6, 7},
                {0, 1, 5, 4},
                {2, 3, 7, 6},
                {1, 2, 6, 5},
                {0, 3, 7, 4}
        };

        private final OrientationModel model;

        BoardPanel(OrientationModel model) {
            this.model = model;
            setPreferredSize(new Dimension(900, 650));
            setBackground(new Color(14, 18, 24));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawGrid(g);
            drawAxes(g);
            drawBoard(g);
            drawBoardAxes(g);
            drawReadout(g);

            g.dispose();
        }

        private void drawGrid(Graphics2D g) {
            int width = getWidth();
            int height = getHeight();
            int centerX = width / 2;
            int centerY = height / 2 + 130;

            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(49, 59, 70));
            for (int i = -8; i <= 8; i++) {
                int x1 = centerX - 360 + i * 28;
                int y1 = centerY + 120;
                int x2 = centerX + 360 + i * 28;
                int y2 = centerY - 120;
                g.drawLine(x1, y1, x2, y2);

                int x3 = centerX - 360 + i * 28;
                int y3 = centerY - 120;
                int x4 = centerX + 360 + i * 28;
                int y4 = centerY + 120;
                g.drawLine(x3, y3, x4, y4);
            }
        }

        private void drawAxes(Graphics2D g) {
            int originX = getWidth() - 150;
            int originY = getHeight() - 120;
            drawAxis(g, originX, originY, 70, 0, new Color(255, 89, 94), "X");
            drawAxis(g, originX, originY, 0, -70, new Color(138, 201, 38), "Y");
            drawAxis(g, originX, originY, -45, 45, new Color(25, 130, 196), "Z");
        }

        private void drawAxis(Graphics2D g, int x, int y, int dx, int dy, Color color, String label) {
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(color);
            g.drawLine(x, y, x + dx, y + dy);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.drawString(label, x + dx + 6, y + dy + 5);
        }

        private void drawBoard(Graphics2D g) {
            Orientation serial = model.serial();
            Orientation visual = serial.visual();
            ProjectedPoint[] projected = new ProjectedPoint[VERTICES.length];

            for (int i = 0; i < VERTICES.length; i++) {
                double[] rotated = rotate(VERTICES[i], visual.roll, visual.pitch, visual.yaw);
                projected[i] = project(rotated);
            }

            List<Face> faces = new ArrayList<>();
            for (int i = 0; i < FACES.length; i++) {
                int[] face = FACES[i];
                double depth = 0.0;
                for (int vertex : face) {
                    depth += projected[vertex].z;
                }
                faces.add(new Face(i, depth / face.length));
            }
            faces.sort(Comparator.comparingDouble(face -> face.depth));

            Color[] colors = {
                    new Color(23, 87, 126, 210),
                    new Color(62, 178, 219, 225),
                    new Color(32, 122, 168, 215),
                    new Color(28, 104, 148, 215),
                    new Color(45, 151, 196, 220),
                    new Color(18, 73, 109, 210)
            };

            for (Face drawFace : faces) {
                int[] face = FACES[drawFace.index];
                Polygon polygon = new Polygon();
                for (int vertex : face) {
                    polygon.addPoint(projected[vertex].x, projected[vertex].y);
                }
                g.setColor(colors[drawFace.index]);
                g.fillPolygon(polygon);
                g.setStroke(new BasicStroke(1.5f));
                g.setColor(new Color(180, 235, 255));
                g.drawPolygon(polygon);
            }
        }

        private void drawBoardAxes(Graphics2D g) {
            Orientation serial = model.serial();
            Orientation visual = serial.visual();

            // Define axis endpoints from origin
            double[][] axisPoints = {
                    {0.0, 0.0, 0.0},  // Origin
                    {3.5, 0.0, 0.0},  // X-axis endpoint (red)
                    {0.0, 3.5, 0.0},  // Y-axis endpoint (green)
                    {0.0, 0.0, 3.5}   // Z-axis endpoint (blue)
            };

            ProjectedPoint[] projected = new ProjectedPoint[axisPoints.length];
            for (int i = 0; i < axisPoints.length; i++) {
                double[] rotated = rotate(axisPoints[i], visual.roll, visual.pitch, visual.yaw);
                projected[i] = project(rotated);
            }

            g.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            // Draw X-axis (Red)
            g.setColor(new Color(255, 89, 94));
            g.drawLine(projected[0].x, projected[0].y, projected[1].x, projected[1].y);

            // Draw Y-axis (Green)
            g.setColor(new Color(138, 201, 38));
            g.drawLine(projected[0].x, projected[0].y, projected[2].x, projected[2].y);

            // Draw Z-axis (Blue)
            g.setColor(new Color(25, 130, 196));
            g.drawLine(projected[0].x, projected[0].y, projected[3].x, projected[3].y);

            // Draw axis labels
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

            g.setColor(new Color(255, 89, 94));
            g.drawString("X", projected[1].x + 5, projected[1].y - 5);

            g.setColor(new Color(138, 201, 38));
            g.drawString("Y", projected[2].x + 5, projected[2].y - 5);

            g.setColor(new Color(25, 130, 196));
            g.drawString("Z", projected[3].x + 5, projected[3].y - 5);
        }

        private double[] rotate(double[] point, double rollDeg, double pitchDeg, double yawDeg) {
            double x = point[0];
            double y = point[1];
            double z = point[2];

            double roll = Math.toRadians(rollDeg);
            double cosRoll = Math.cos(roll);
            double sinRoll = Math.sin(roll);
            double y1 = y * cosRoll - z * sinRoll;
            double z1 = y * sinRoll + z * cosRoll;
            y = y1;
            z = z1;

            double pitch = Math.toRadians(pitchDeg);
            double cosPitch = Math.cos(pitch);
            double sinPitch = Math.sin(pitch);
            double x1 = x * cosPitch + z * sinPitch;
            double z2 = -x * sinPitch + z * cosPitch;
            x = x1;
            z = z2;

            double yaw = Math.toRadians(yawDeg);
            double cosYaw = Math.cos(yaw);
            double sinYaw = Math.sin(yaw);
            double x2 = x * cosYaw - y * sinYaw;
            double y2 = x * sinYaw + y * cosYaw;

            return new double[]{x2, y2, z};
        }

        private ProjectedPoint project(double[] point) {
            double scale = Math.min(getWidth(), getHeight()) * 0.16;
            double cameraDistance = 8.0;
            double perspective = cameraDistance / (cameraDistance - point[2]);
            int x = (int) Math.round(getWidth() / 2.0 + point[0] * scale * perspective);
            int y = (int) Math.round(getHeight() / 2.0 - point[1] * scale * perspective);
            return new ProjectedPoint(x, y, point[2]);
        }

        private void drawReadout(Graphics2D g) {
            Orientation serial = model.serial();
            Orientation visual = serial.visual();

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.setColor(new Color(235, 241, 246));
            g.drawString("Serial Roll: " + format(serial.roll), 28, 42);
            g.drawString("Serial Pitch: " + format(serial.pitch), 28, 70);
            g.drawString("Serial Yaw: " + format(serial.yaw), 28, 98);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(new Color(181, 196, 209));
            g.drawString("Visual Roll: " + format(visual.roll), 28, 132);
            g.drawString("Visual Pitch: " + format(visual.pitch), 28, 158);
            g.drawString("Visual Yaw: " + format(visual.yaw), 28, 184);
        }

        private String format(double value) {
            return String.format(Locale.US, "%.1f", value);
        }
    }

    private static final class Face {
        final int index;
        final double depth;

        Face(int index, double depth) {
            this.index = index;
            this.depth = depth;
        }
    }

    private static final class ProjectedPoint {
        final int x;
        final int y;
        final double z;

        ProjectedPoint(int x, int y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
