package com.bitxeno.atvloadly;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Random;

/**
 * A small RFC 6455 WebSocket client (text frames only) with no third-party
 * dependencies. It speaks to the local Go backend, so it always targets
 * ws://127.0.0.1:9000 and never needs TLS or permessage-deflate.
 *
 * The backend streams both machine-readable JSON (pairing discovery) and plain
 * engine log lines (install / login / pair progress) as text frames; each frame
 * is delivered to {@link Listener#onMessage} verbatim and the UI parses it.
 *
 * All callbacks run on a background reader thread. Implementations must marshal
 * to the main thread themselves.
 */
public class WebSocketClient {

    private static final String TAG = "atvloadly.Ws";
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Consumer of incoming text frames and lifecycle events. */
    public interface Listener {
        void onMessage(String text);

        void onOpen();

        void onClose(String reason);

        void onError(String message);
    }

    private final String path;
    private final Listener listener;
    private Socket socket;
    private volatile boolean closing = false;

    public WebSocketClient(String path, Listener listener) {
        this.path = path;
        this.listener = listener;
    }

    /** Open the connection. Blocks; call from a background thread. */
    public void connect() {
        try {
            socket = new Socket("127.0.0.1", 9000);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // ---- Opening handshake (HTTP/1.1 Upgrade) ----
            String key = base64(randomKey());
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: 127.0.0.1:9000\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            req.append("\r\n");
            out.write(req.toString().getBytes("UTF-8"));
            out.flush();

            // The backend answers 101 with a Sec-WebSocket-Accept we verify, so a
            // non-WebSocket response is caught here rather than corrupting frames.
            String accept = expectedAccept(key);
            if (!readHandshake(in, accept)) {
                fail("handshake rejected");
                return;
            }

            if (listener != null) listener.onOpen();

            // ---- Frame reader loop ----
            while (!closing) {
                String text = readTextFrame(in);
                if (text == null) break;
                if (listener != null) listener.onMessage(text);
            }
            close(null);
        } catch (Exception e) {
            if (!closing) fail(e.getMessage() == null ? "connection error" : e.getMessage());
        }
    }

    /** Send a text frame. Safe to call from any thread. */
    public void send(final String text) {
        final WebSocketClient self = this;
        new Thread(new Runnable() {
            public void run() {
                try {
                    synchronized (self) {
                        if (socket == null || socket.isClosed()) return;
                        writeFrame(socket.getOutputStream(), text);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "send failed: " + e);
                }
            }
        }).start();
    }

    /** Close the connection gracefully. */
    public void close() {
        close("client closed");
    }

    private synchronized void close(String reason) {
        closing = true;
        try {
            if (socket != null && !socket.isClosed()) {
                // Best-effort close frame (opcode 0x88) so the backend handler
                // returns promptly instead of waiting on a dead socket.
                socket.getOutputStream().write(new byte[]{(byte) 0x88, 0x00});
                socket.getOutputStream().flush();
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
        if (listener != null && reason != null) listener.onClose(reason);
    }

    private void fail(String message) {
        Log.w(TAG, "ws error: " + message);
        close(null);
        if (listener != null) listener.onError(message);
    }

    // ---- Handshake helpers -------------------------------------------------

    private static byte[] randomKey() {
        byte[] b = new byte[16];
        new Random().nextBytes(b);
        return b;
    }

    private static String base64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static String expectedAccept(String clientKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((clientKey + WS_MAGIC).getBytes("UTF-8"));
            return base64(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Read HTTP response headers until the blank line, returning true only when
     * the status is 101 Switching Protocols and the accept key matches.
     */
    private static boolean readHandshake(InputStream in, String expectedAccept)
            throws IOException {
        StringBuilder head = new StringBuilder();
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            head.append((char) c);
            if (prev == '\r' && c == '\n' && head.length() >= 4) {
                String tail = head.substring(head.length() - 4);
                if (tail.equals("\r\n\r\n")) break;
            }
            prev = c;
            if (head.length() > 8192) break;
        }
        String h = head.toString();
        if (!h.contains("101")) return false;
        return expectedAccept.isEmpty() || h.contains(expectedAccept);
    }

    // ---- Frame I/O ---------------------------------------------------------

    /** Read one text frame; returns null on EOF or a close frame. */
    private static String readTextFrame(InputStream in) throws IOException {
        while (true) {
            int b1 = in.read();
            if (b1 == -1) return null;
            int b2 = in.read();
            if (b2 == -1) return null;
            int opcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7F;
            if (len == 126) {
                len = ((long) (in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (len == 127) {
                for (int i = 0; i < 8; i++) in.read(); // 64-bit length unused here
                len = 0;
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i & 3]);
                }
            }
            if (opcode == 0x8) return null;           // close
            if (opcode == 0x9) continue;              // ping: ignored, no pong
            if (opcode == 0xA) continue;              // pong
            if (opcode == 0x1 || opcode == 0x0) {
                return new String(payload, "UTF-8");
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("EOF in frame");
            off += n;
        }
    }

    /** Write one masked text frame (client-to-server frames must be masked). */
    private static void writeFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes("UTF-8");
        out.write(0x81); // FIN + text
        int len = payload.length;
        if (len < 126) {
            out.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) out.write((len >> (8 * i)) & 0xFF);
        }
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        out.write(mask);
        for (int i = 0; i < len; i++) {
            out.write(payload[i] ^ mask[i & 3]);
        }
        out.flush();
    }
}
