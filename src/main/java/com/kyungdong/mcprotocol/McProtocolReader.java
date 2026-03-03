package com.kyungdong.mcprotocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Mitsubishi PLC MC Protocol(3E Frame, Binary) reader example.
 *
 * Reads word devices from D100 to D110 (inclusive).
 */
public class McProtocolReader {
    private static final int COMMAND_BATCH_READ = 0x0401;
    private static final int SUBCOMMAND_WORD = 0x0000;
    private static final int DEVICE_CODE_D = 0xA8;

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "192.168.0.10";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        int startAddress = 100;
        int points = 11; // D100 ~ D110

        try {
            int[] values = readWordDevices(host, port, startAddress, points);
            for (int i = 0; i < values.length; i++) {
                System.out.printf("D%d = %d%n", startAddress + i, values[i]);
            }
        } catch (IOException e) {
            System.err.println("MC Protocol read failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static int[] readWordDevices(String host, int port, int startAddress, int points) throws IOException {
        byte[] request = buildBatchReadRequest(startAddress, points);

        try (Socket socket = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream())) {

            out.write(request);
            out.flush();

            byte[] header = new byte[9];
            in.readFully(header);

            int dataLength = toUnsignedShortLE(header[7], header[8]);
            byte[] data = new byte[dataLength];
            in.readFully(data);

            int endCode = toUnsignedShortLE(data[0], data[1]);
            if (endCode != 0) {
                throw new IOException(String.format("PLC end code error: 0x%04X", endCode));
            }

            int expectedDataBytes = points * 2;
            if (data.length < 2 + expectedDataBytes) {
                throw new IOException("Response data length is shorter than expected");
            }

            int[] result = new int[points];
            ByteBuffer buffer = ByteBuffer.wrap(data, 2, expectedDataBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < points; i++) {
                result[i] = Short.toUnsignedInt(buffer.getShort());
            }
            return result;
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
        frame.put((byte) 0x50).put((byte) 0x00); // subheader
        frame.put((byte) 0x00);                  // network no.
        frame.put((byte) 0xFF);                  // PLC no.
        frame.put((byte) 0xFF).put((byte) 0x03); // I/O no.
        frame.put((byte) 0x00);                  // station no.
        frame.putShort((short) data.length);     // request data length
        frame.put(data);

        return frame.array();
    }

    private static int toUnsignedShortLE(byte low, byte high) {
        return (low & 0xFF) | ((high & 0xFF) << 8);
    }
}
