package com.kyungdong.mcprotocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mitsubishi PLC MC Protocol(3E Frame, Binary) reader example.
 *
 * <p>Reads word devices from D100 to D110 (inclusive).
 *
 * <p>Run (Gradle):
 * <pre>
 * ./gradlew run --args="192.168.0.10 5000"
 * </pre>
 */
public class McProtocolReader {
    private static final Logger LOG = Logger.getLogger(McProtocolReader.class.getName());

    private static final int COMMAND_BATCH_READ = 0x0401;
    private static final int SUBCOMMAND_WORD = 0x0000;
    private static final int DEVICE_CODE_D = 0xA8;

    private static final int REQUEST_SUBHEADER = 0x5000;
    private static final int RESPONSE_SUBHEADER = 0xD000;

    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 3_000;
    private static final int RETRY_COUNT = 2;

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "192.168.0.10";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        int startAddress = 100;
        int points = 11; // D100 ~ D110

        try {
            int[] values = readWordDevicesWithRetry(host, port, startAddress, points, RETRY_COUNT);
            for (int i = 0; i < values.length; i++) {
                System.out.printf("D%d = %d%n", startAddress + i, values[i]);
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "MC Protocol read failed", e);
            System.exit(1);
        }
    }

    public static int[] readWordDevicesWithRetry(
            String host,
            int port,
            int startAddress,
            int points,
            int retryCount
    ) throws IOException {
        validateInput(host, port, startAddress, points);

        IOException lastError = null;
        int attempts = Math.max(1, retryCount + 1);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                LOG.info(String.format(
                        "MC read attempt %d/%d host=%s port=%d range=D%d~D%d",
                        attempt, attempts, host, port, startAddress, (startAddress + points - 1)
                ));
                return readWordDevices(host, port, startAddress, points);
            } catch (SocketTimeoutException e) {
                lastError = e;
                LOG.log(Level.WARNING, String.format("Timeout on attempt %d/%d", attempt, attempts), e);
            } catch (IOException e) {
                lastError = e;
                LOG.log(Level.WARNING, String.format("I/O error on attempt %d/%d", attempt, attempts), e);
            }
        }

        throw new IOException("Failed to read PLC after retries", lastError);
    }

    public static int[] readWordDevices(String host, int port, int startAddress, int points) throws IOException {
        byte[] request = buildBatchReadRequest(startAddress, points);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {

                out.write(request);
                out.flush();

                byte[] header = new byte[9];
                in.readFully(header);
                validateResponseHeader(header);

                int dataLength = toUnsignedShortLE(header[7], header[8]);
                if (dataLength < 2) {
                    throw new IOException("Invalid response length (must include end code)");
                }

                byte[] data = new byte[dataLength];
                in.readFully(data);

                int endCode = toUnsignedShortLE(data[0], data[1]);
                if (endCode != 0) {
                    throw new IOException(String.format("PLC end code error: 0x%04X", endCode));
                }

                int expectedDataBytes = points * 2;
                if (data.length < 2 + expectedDataBytes) {
                    throw new IOException(String.format(
                            "Response data length is shorter than expected. actual=%d expected>=%d",
                            data.length, 2 + expectedDataBytes
                    ));
                }

                int[] result = new int[points];
                ByteBuffer buffer = ByteBuffer.wrap(data, 2, expectedDataBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < points; i++) {
                    result[i] = Short.toUnsignedInt(buffer.getShort());
                }

                LOG.fine(() -> "Read values: " + Arrays.toString(result));
                return result;
            }
        }
    }

    private static byte[] buildBatchReadRequest(int startAddress, int points) {
        ByteBuffer requestData = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        requestData.putShort((short) 0x0010); // monitoring timer
        requestData.putShort((short) COMMAND_BATCH_READ);
        requestData.putShort((short) SUBCOMMAND_WORD);

        requestData.put((byte) (startAddress & 0xFF));
        requestData.put((byte) ((startAddress >>> 8) & 0xFF));
        requestData.put((byte) ((startAddress >>> 16) & 0xFF));

        requestData.put((byte) DEVICE_CODE_D);
        requestData.putShort((short) points);

        byte[] data = requestData.array();

        ByteBuffer frame = ByteBuffer.allocate(9 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putShort((short) REQUEST_SUBHEADER);
        frame.put((byte) 0x00);                  // network no.
        frame.put((byte) 0xFF);                  // PLC no.
        frame.put((byte) 0xFF).put((byte) 0x03); // I/O no.
        frame.put((byte) 0x00);                  // station no.
        frame.putShort((short) data.length);     // request data length
        frame.put(data);

        return frame.array();
    }

    private static void validateInput(String host, int port, int startAddress, int points) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in range 1~65535");
        }
        if (startAddress < 0) {
            throw new IllegalArgumentException("startAddress must be >= 0");
        }
        if (points <= 0 || points > 960) {
            throw new IllegalArgumentException("points must be in range 1~960");
        }
    }

    private static void validateResponseHeader(byte[] header) throws IOException {
        int subheader = toUnsignedShortLE(header[0], header[1]);
        if (subheader != RESPONSE_SUBHEADER) {
            throw new IOException(String.format("Invalid response subheader: 0x%04X", subheader));
        }
    }

    private static int toUnsignedShortLE(byte low, byte high) {
        return (low & 0xFF) | ((high & 0xFF) << 8);
    }
}
