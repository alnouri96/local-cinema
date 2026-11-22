package com.archos.mediacenter.video.leanback.cinema;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;

/**
 * Pure-Java local ADB-over-TCP client targeting 127.0.0.1:5555 so the TV app can inject
 * input keyevent / input text into external apps (SmartTube, custom launchers) and self-grant
 * WRITE_SECURE_SETTINGS without requiring an external computer.
 */
public final class TvLocalAdbBridge {
    private static final String TAG = "CinemaLocalAdb";
    private static final String PREFS = "cinema_tv_remote";
    private static final String KEY_ENABLED = "local_adb_enabled";
    private static final String KEY_PRIV = "adb_rsa_priv";
    private static final String KEY_PUB = "adb_rsa_pub";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int ADB_VERSION = 0x01000001;
    private static final int MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final byte[] SHA1_DIGEST_INFO_PREFIX = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private static final Object LOCK = new Object();
    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LocalAdbIo");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger NEXT_LOCAL_ID = new AtomicInteger(1);

    private static Socket socket;
    private static InputStream in;
    private static OutputStream out;
    private static volatile boolean connected;
    private static volatile String lastError = "";
    private static KeyPair cachedKeyPair;

    public record ConnectResult(boolean success, String message) {}

    private TvLocalAdbBridge() {}

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            IO_EXEC.execute(TvLocalAdbBridge::disconnectLockedSafe);
        }
    }

    public static boolean isReady() {
        Socket s = socket;
        return connected && s != null && !s.isClosed() && s.isConnected();
    }

    public static String getLastError() {
        return lastError;
    }

    public static void reconnectIfEnabledAsync(Context context) {
        if (context == null || !isEnabled(context)) return;
        Context app = context.getApplicationContext();
        IO_EXEC.execute(() -> {
            ConnectResult res = connectBlocking(app, 6000, false);
            if (res.success()) {
                TvRemotePermissionKeeper.ensureAccessibilityEnabled(app);
                CinemaLinkServer.broadcastRemoteStatus(app);
            }
        });
    }

    public static void connectInteractiveAsync(Context context, java.util.function.Consumer<ConnectResult> callback) {
        Context app = context.getApplicationContext();
        setEnabled(app, true);
        IO_EXEC.execute(() -> {
            ConnectResult res = connectBlocking(app, 22000, true);
            if (res.success()) {
                grantWriteSecureSettingsInternal(app);
                TvRemotePermissionKeeper.ensureAccessibilityEnabled(app);
                CinemaLinkServer.broadcastRemoteStatus(app);
            }
            if (callback != null) callback.accept(res);
        });
    }

    public static boolean sendKeyEvent(Context context, int keyCode) {
        return sendKeyEvent(context, keyCode, false);
    }

    static boolean sendKeyEvent(Context context, int keyCode, boolean held) {
        if (context == null || !isEnabled(context)) return false;
        Context app = context.getApplicationContext();
        try {
            Future<Boolean> f = IO_EXEC.submit(() -> {
                if (!ensureConnectedInternal(app)) return false;
                ShellResult r = execShellInternal("input keyevent " + (held ? "--longpress " : "") + keyCode, 2500);
                return r.ok;
            });
            return Boolean.TRUE.equals(f.get(3000, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean sendTextAscii(Context context, String text) {
        if (context == null || text == null || text.isEmpty() || !isEnabled(context)) return false;
        if (!isPureAscii(text)) return false;
        String escaped = escapeForInputText(text);
        if (escaped.isEmpty()) return false;
        Context app = context.getApplicationContext();
        try {
            Future<Boolean> f = IO_EXEC.submit(() -> {
                if (!ensureConnectedInternal(app)) return false;
                ShellResult r = execShellInternal("input text " + escaped, 3500);
                return r.ok;
            });
            return Boolean.TRUE.equals(f.get(4000, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean launchPackage(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isBlank() || !isEnabled(context)) return false;
        if (!packageName.matches("[A-Za-z0-9._-]+")) return false;
        Context app = context.getApplicationContext();
        try {
            Future<Boolean> f = IO_EXEC.submit(() -> {
                if (!ensureConnectedInternal(app)) return false;
                ShellResult r = execShellInternal("monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1", 3500);
                return r.ok;
            });
            return Boolean.TRUE.equals(f.get(4000, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    public static ConnectResult testAndGrantBlocking(Context context) {
        if (context == null) return new ConnectResult(false, "Missing context");
        Context app = context.getApplicationContext();
        try {
            return IO_EXEC.submit(() -> {
                ConnectResult cr = connectBlocking(app, 20000, true);
                if (!cr.success()) return cr;
                ShellResult echo = execShellInternal("echo localcinema_ok", 3000);
                if (!echo.ok || !echo.output.contains("localcinema_ok")) {
                    return new ConnectResult(false, "ADB shell did not respond");
                }
                boolean granted = grantWriteSecureSettingsInternal(app);
                TvRemotePermissionKeeper.ensureAccessibilityEnabled(app);
                CinemaLinkServer.broadcastRemoteStatus(app);
                return new ConnectResult(true, granted
                        ? "ADB connected & WRITE_SECURE_SETTINGS active"
                        : "ADB shell active");
            }).get(24, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new ConnectResult(false, e.getMessage() != null ? e.getMessage() : "ADB timeout");
        }
    }

    private static boolean grantWriteSecureSettingsInternal(Context context) {
        if (TvRemotePermissionKeeper.hasWriteSecureSettings(context)) return true;
        ShellResult r = execShellInternal("pm grant " + context.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS", 4000);
        return r.ok && TvRemotePermissionKeeper.hasWriteSecureSettings(context);
    }

    private static boolean ensureConnectedInternal(Context context) {
        if (isReady()) return true;
        return connectBlocking(context, 4000, false).success();
    }

    private static ConnectResult connectBlocking(Context context, int authTimeoutMs, boolean allowPubKeyPrompt) {
        synchronized (LOCK) {
            if (isReady()) {
                return new ConnectResult(true, "Connected");
            }
            disconnectLocked();
            try {
                KeyPair kp = getOrCreateKeyPair(context);
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.connect(new InetSocketAddress("127.0.0.1", 5555), 2500);
                s.setSoTimeout(4500);
                socket = s;
                in = s.getInputStream();
                out = s.getOutputStream();

                writePacket(A_CNXN, ADB_VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
                boolean sentPubKey = false;
                while (true) {
                    AdbPacket pkt = readPacket();
                    if (pkt.command == A_CNXN) {
                        connected = true;
                        lastError = "";
                        s.setSoTimeout(5000);
                        return new ConnectResult(true, "Connected");
                    }
                    if (pkt.command != A_AUTH || pkt.arg0 != AUTH_TOKEN) {
                        throw new IOException("Unexpected ADB handshake command: 0x" + Integer.toHexString(pkt.command));
                    }
                    if (!sentPubKey) {
                        byte[] sig = signAdbToken(kp.getPrivate(), pkt.payload);
                        writePacket(A_AUTH, AUTH_SIGNATURE, 0, sig);
                        sentPubKey = true;
                    } else {
                        if (!allowPubKeyPrompt) {
                            lastError = "Authorization required";
                            disconnectLocked();
                            return new ConnectResult(false, lastError);
                        }
                        s.setSoTimeout(authTimeoutMs);
                        byte[] pubStruct = encodeAndroidPublicKey((RSAPublicKey) kp.getPublic(), "localcinema@tv");
                        writePacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, pubStruct);
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                lastError = msg;
                Log.w(TAG, "Local ADB connect failed: " + msg);
                disconnectLocked();
                return new ConnectResult(false, msg);
            }
        }
    }

    private record ShellResult(boolean ok, String output) {}

    private static ShellResult execShellInternal(String command, int timeoutMs) {
        synchronized (LOCK) {
            if (!isReady()) return new ShellResult(false, "");
            int localId = NEXT_LOCAL_ID.getAndIncrement();
            int remoteId = 0;
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            try {
                socket.setSoTimeout(timeoutMs);
                byte[] openPayload = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
                writePacket(A_OPEN, localId, 0, openPayload);
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < deadline) {
                    AdbPacket pkt = readPacket();
                    if (pkt.command == A_OKAY && pkt.arg1 == localId) {
                        remoteId = pkt.arg0;
                    } else if (pkt.command == A_WRTE && pkt.arg1 == localId) {
                        if (remoteId == 0) remoteId = pkt.arg0;
                        if (pkt.payload.length > 0 && outBuf.size() < 65536) {
                            outBuf.write(pkt.payload);
                        }
                        writePacket(A_OKAY, localId, remoteId, new byte[0]);
                    } else if (pkt.command == A_CLSE && pkt.arg1 == localId) {
                        if (remoteId == 0) remoteId = pkt.arg0;
                        if (remoteId != 0) {
                            try {
                                writePacket(A_CLSE, localId, remoteId, new byte[0]);
                            } catch (Exception ignored) {}
                        }
                        return new ShellResult(true, outBuf.toString(StandardCharsets.UTF_8.name()));
                    }
                }
                return new ShellResult(remoteId != 0, outBuf.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "IO error";
                disconnectLocked();
                return new ShellResult(false, "");
            }
        }
    }

    private static void disconnectLockedSafe() {
        synchronized (LOCK) {
            disconnectLocked();
        }
    }

    private static void disconnectLocked() {
        connected = false;
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        in = null;
        out = null;
        socket = null;
    }

    private static void writePacket(int cmd, int arg0, int arg1, byte[] payload) throws IOException {
        byte[] data = payload != null ? payload : new byte[0];
        ByteBuffer hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(cmd);
        hdr.putInt(arg0);
        hdr.putInt(arg1);
        hdr.putInt(data.length);
        hdr.putInt(payloadChecksum(data));
        hdr.putInt(~cmd);
        out.write(hdr.array());
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private record AdbPacket(int command, int arg0, int arg1, byte[] payload) {}

    private static AdbPacket readPacket() throws IOException {
        byte[] hdrBytes = readFully(24);
        ByteBuffer hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = hdr.getInt();
        int arg0 = hdr.getInt();
        int arg1 = hdr.getInt();
        int len = hdr.getInt();
        hdr.getInt(); // crc
        hdr.getInt(); // magic
        if (len < 0 || len > 1024 * 1024) throw new IOException("Invalid ADB packet length: " + len);
        byte[] payload = len > 0 ? readFully(len) : new byte[0];
        return new AdbPacket(cmd, arg0, arg1, payload);
    }

    private static byte[] readFully(int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new EOFException("ADB stream closed");
            off += n;
        }
        return buf;
    }

    private static int payloadChecksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += (b & 0xff);
        return sum;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static KeyPair getOrCreateKeyPair(Context context) throws Exception {
        if (cachedKeyPair != null) return cachedKeyPair;
        SharedPreferences p = prefs(context);
        String privB64 = p.getString(KEY_PRIV, "");
        String pubB64 = p.getString(KEY_PUB, "");
        if (!privB64.isEmpty() && !pubB64.isEmpty()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)));
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)));
                cachedKeyPair = new KeyPair(pub, priv);
                return cachedKeyPair;
            } catch (Exception ignored) {}
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        p.edit()
                .putString(KEY_PRIV, Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString(KEY_PUB, Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                .apply();
        cachedKeyPair = kp;
        return kp;
    }

    private static byte[] signAdbToken(PrivateKey key, byte[] token) throws Exception {
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.length, token.length);
        try {
            Signature sig = Signature.getInstance("NONEwithRSA");
            sig.initSign(key);
            sig.update(digestInfo);
            return sig.sign();
        } catch (Exception ignored) {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(digestInfo);
        }
    }

    private static byte[] encodeAndroidPublicKey(RSAPublicKey key, String comment) {
        final int words = 64; // 2048-bit / 32
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger r = BigInteger.ONE.shiftLeft(words * 32);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));

        ByteBuffer bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv.intValue());
        writeLeWords(bb, n, words, r32);
        writeLeWords(bb, rr, words, r32);
        bb.putInt(key.getPublicExponent().intValue());

        byte[] encoded = Base64.encode(bb.array(), Base64.NO_WRAP);
        byte[] suffix = (" " + comment + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] outBytes = new byte[encoded.length + suffix.length];
        System.arraycopy(encoded, 0, outBytes, 0, encoded.length);
        System.arraycopy(suffix, 0, outBytes, encoded.length, suffix.length);
        return outBytes;
    }

    private static void writeLeWords(ByteBuffer bb, BigInteger val, int words, BigInteger r32) {
        BigInteger cur = val;
        for (int i = 0; i < words; i++) {
            bb.putInt(cur.mod(r32).intValue());
            cur = cur.shiftRight(32);
        }
    }

    private static boolean isPureAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7e || s.charAt(i) < 0x20) return false;
        }
        return true;
    }

    private static String escapeForInputText(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') sb.append("%s");
            else if (c == '\'') sb.append("'\\''");
            else sb.append(c);
        }
        sb.append('\'');
        return sb.toString();
    }
}
