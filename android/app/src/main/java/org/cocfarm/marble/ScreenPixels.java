package org.cocfarm.marble;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Android screencap's raw RGBA8888 format. Reject other formats rather than read shifted pixels. */
record ScreenPixels(int width, int height) {
    static final int HEADER_BYTES = 16;

    static ScreenPixels readHeader(byte[] capture) throws IOException {
        if (capture.length >= HEADER_BYTES) {
            ByteBuffer header = ByteBuffer.wrap(capture).order(ByteOrder.LITTLE_ENDIAN);
            int width = header.getInt();
            int height = header.getInt();
            int format = header.getInt();
            if (width > 0 && height > 0 && width <= 4096 && height <= 4096 && format == 1 &&
                    capture.length == HEADER_BYTES + 4L * width * height) {
                return new ScreenPixels(width, height);
            }
        }
        throw new IOException("Unrecognized raw screen capture. Verify the phone's display settings.");
    }
}
