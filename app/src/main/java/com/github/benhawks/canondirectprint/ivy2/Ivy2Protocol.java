package com.github.benhawks.canondirectprint.ivy2;

/**
 * Wire format of the Canon Ivy 2 (Mini Photo Printer) Bluetooth protocol.
 *
 * Every command is a fixed 34-byte packet:
 * <pre>
 *   0..1  start code 0x430F
 *   2..3  int16  (1, or -1 for "start session")
 *   4     int8   (32, or -1 for "start session")
 *   5..6  uint16 command id
 *   7     flag   (1 = "write" variant of the command)
 *   8..   command payload (zero padded)
 * </pre>
 * Responses use the same header; bytes 5..6 echo the command id (the "ack"),
 * byte 7 is an error code and the payload starts at byte 8.
 *
 * Protocol as reverse engineered by https://github.com/dtgreene/ivy2 (task.py / utils.py).
 */
public final class Ivy2Protocol {
    private Ivy2Protocol() {}

    public static final int MESSAGE_LENGTH = 34;
    public static final int HEADER_LENGTH = 8;
    public static final int START_CODE = 0x430F;
    /** Replies from a real Ivy 2 start with this instead (seen in the field). */
    public static final int REPLY_START_CODE = 0x43F0;

    public static final int COMMAND_START_SESSION = 0;
    public static final int COMMAND_GET_STATUS = 257;
    public static final int COMMAND_SETTING_ACCESSORY = 259;
    public static final int COMMAND_PRINT_READY = 769;
    public static final int COMMAND_REBOOT = 65535;

    /** Image data is streamed in chunks of this size after PRINT_READY. */
    public static final int PRINT_DATA_CHUNK = 990;

    /**
     * Minimum battery level for printing, as used by the reference implementation.
     * The printer reports battery as a 6-bit value (0..63); its exact scale is not documented.
     */
    public static final int PRINT_BATTERY_MIN = 30;

    /** Supported auto power off values, in minutes. */
    public static final int[] AUTO_POWER_OFF_VALUES = { 3, 5, 10 };

    public static byte[] baseMessage(int command, boolean startSession, boolean write) {
        byte[] m = new byte[MESSAGE_LENGTH];
        putU16(m, 0, START_CODE);
        if (startSession) {
            m[2] = (byte) 0xFF;
            m[3] = (byte) 0xFF;
            m[4] = (byte) 0xFF;
        } else {
            m[2] = 0;
            m[3] = 1;
            m[4] = 32;
        }
        putU16(m, 5, command);
        m[7] = (byte) (write ? 1 : 0);
        return m;
    }

    public static byte[] startSession() {
        return baseMessage(COMMAND_START_SESSION, true, false);
    }

    public static byte[] getStatus() {
        return baseMessage(COMMAND_GET_STATUS, false, false);
    }

    public static byte[] getSetting() {
        return baseMessage(COMMAND_SETTING_ACCESSORY, false, false);
    }

    public static byte[] setAutoPowerOff(int minutes) {
        byte[] m = baseMessage(COMMAND_SETTING_ACCESSORY, false, true);
        m[8] = (byte) minutes;
        return m;
    }

    public static byte[] reboot() {
        byte[] m = baseMessage(COMMAND_REBOOT, false, true);
        m[8] = 1;
        return m;
    }

    public static byte[] printReady(int length) {
        byte[] m = baseMessage(COMMAND_PRINT_READY, false, false);
        m[8] = (byte) (length >>> 24);
        m[9] = (byte) (length >>> 16);
        m[10] = (byte) (length >>> 8);
        m[11] = (byte) length;
        m[12] = 1;
        m[13] = 1;
        return m;
    }

    static void putU16(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >>> 8);
        b[offset + 1] = (byte) value;
    }

    static int u8(byte[] b, int offset) {
        return offset < b.length ? b[offset] & 0xFF : 0;
    }

    static int u16(byte[] b, int offset) {
        return (u8(b, offset) << 8) | u8(b, offset + 1);
    }

    /** A message received from the printer. */
    public static final class Response {
        public final byte[] data;

        public Response(byte[] data) {
            if (data.length < HEADER_LENGTH)
                throw new IllegalArgumentException("Response too short: " + data.length);
            this.data = data;
        }

        public int getAck() { return u16(data, 5); }
        public int getError() { return u8(data, 7); }

        /** Payload byte (offset relative to the start of the payload); 0 if out of range. */
        public int payload(int offset) { return u8(data, HEADER_LENGTH + offset); }

        @Override
        public String toString() {
            return "Response(ack=" + getAck() + ", error=" + getError() + ", " + Hex.encode(data) + ")";
        }
    }

    /** Result of START_SESSION. */
    public static final class SessionInfo {
        public final int batteryLevel;
        public final int mtu;

        SessionInfo(Response r) {
            batteryLevel = u16(r.data, 9) & 0x3F;
            mtu = u16(r.data, 11);
        }

        @Override
        public String toString() { return "battery=" + batteryLevel + " mtu=" + mtu; }
    }

    /** Result of GET_STATUS. */
    public static final class Status {
        public final int errorCode;
        public final int batteryLevel;
        public final boolean usbConnected;
        public final boolean coverOpen;
        public final boolean noPaper;
        public final boolean wrongSmartSheet;

        Status(Response r) {
            int i = (r.payload(0) << 8) | r.payload(1);
            errorCode = r.payload(2);
            batteryLevel = i & 0x3F;
            usbConnected = ((i >> 7) & 1) == 1;
            int queueFlags = (r.payload(4) << 8) | r.payload(5);
            coverOpen = (queueFlags & 1) != 0;
            noPaper = (queueFlags & 2) != 0;
            wrongSmartSheet = (queueFlags & 16) != 0;
        }

        @Override
        public String toString() {
            return "error=" + errorCode + " battery=" + batteryLevel + " usb=" + usbConnected
                    + " coverOpen=" + coverOpen + " noPaper=" + noPaper + " wrongSmartSheet=" + wrongSmartSheet;
        }
    }

    /** Result of SETTING_ACCESSORY (read). */
    public static final class Settings {
        public final int autoPowerOff;
        public final String firmwareVersion;
        public final int tmdVersion;
        public final int photosPrinted;
        public final int colorId;

        Settings(Response r) {
            autoPowerOff = r.payload(0);
            firmwareVersion = r.payload(1) + "." + r.payload(2) + "." + r.payload(3);
            tmdVersion = r.payload(5);
            photosPrinted = (r.payload(6) << 8) | r.payload(7);
            colorId = r.payload(8);
        }

        @Override
        public String toString() {
            return "autoPowerOff=" + autoPowerOff + "min fw=" + firmwareVersion + " tmd=" + tmdVersion
                    + " printed=" + photosPrinted + " color=" + colorId;
        }
    }

    /** Result of PRINT_READY. */
    public static final class PrintReady {
        public final int unknown;
        public final int errorCode;

        PrintReady(Response r) {
            unknown = r.payload(2);
            errorCode = r.payload(3);
        }
    }
}
