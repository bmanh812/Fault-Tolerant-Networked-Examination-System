package vn.edu.exam.common.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class FrameCodec {
    private FrameCodec() {
    }

    public static String readFrame(InputStream input, int maxMessageBytes) throws IOException {
        int first = input.read();
        if (first == -1) {
            return null;
        }

        byte[] header = new byte[4];
        header[0] = (byte) first;
        readFully(input, header, 1, 3);
        int length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt();
        if (length <= 0 || length > maxMessageBytes) {
            throw new IOException("Invalid message length: " + length);
        }

        byte[] body = new byte[length];
        readFully(input, body, 0, length);
        return new String(body, StandardCharsets.UTF_8);
    }

    public static void writeFrame(OutputStream output, String json, int maxMessageBytes) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length == 0 || body.length > maxMessageBytes) {
            throw new IOException("Invalid message length: " + body.length);
        }
        output.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.length).array());
        output.write(body);
        output.flush();
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(target, offset + total, length - total);
            if (read == -1) {
                throw new EOFException("Connection closed in the middle of a message");
            }
            total += read;
        }
    }
}

