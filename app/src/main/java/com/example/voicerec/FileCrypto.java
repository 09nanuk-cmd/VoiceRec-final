package com.example.voicerec;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifratura a blocchi AES-256-GCM (64 KB per blocco).
 * - Formato "dispositivo": la chiave del file è protetta da una chiave non esportabile dell'Android Keystore.
 * - Formato "esportazione": la chiave deriva da una frase segreta (PBKDF2) e il file si apre solo con questa app.
 * L'intestazione è autenticata, l'ordine dei blocchi è vincolato e il troncamento viene rilevato.
 */
public final class FileCrypto {
    private FileCrypto() {}

    public interface KeyWrapper {
        byte[] wrap(byte[] dek) throws GeneralSecurityException;
        byte[] unwrap(byte[] wrapped) throws GeneralSecurityException;
    }

    public static final class ExportInfo {
        public String name;
        public boolean noReshare;
    }

    private static final int CHUNK = 64 * 1024;
    private static final int TAG_BYTES = 16;
    private static final int PBKDF2_ITER = 150_000;
    private static final byte[] MAGIC_DEVICE = {'V', 'R', 'D', '1'};
    private static final byte[] MAGIC_EXPORT = {'V', 'R', 'X', '1'};
    private static final SecureRandom RNG = new SecureRandom();

    /** Legge e conserva i byte dell'intestazione, per autenticarla. */
    private static final class Capture extends FilterInputStream {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean on = true;
        Capture(InputStream in) { super(in); }
        @Override public int read() throws IOException {
            int b = super.read();
            if (on && b >= 0) buf.write(b);
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (on && n > 0) buf.write(b, off, n);
            return n;
        }
    }

    // ---------------- formato dispositivo ----------------

    public static void encryptDevice(InputStream in, OutputStream out, KeyWrapper kw)
            throws IOException, GeneralSecurityException {
        byte[] dek = new byte[32];
        RNG.nextBytes(dek);
        byte[] ivp = new byte[4];
        RNG.nextBytes(ivp);
        byte[] wrapped = kw.wrap(dek);
        ByteArrayOutputStream hb = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(hb);
        h.write(MAGIC_DEVICE);
        h.writeShort(wrapped.length);
        h.write(wrapped);
        h.write(ivp);
        h.flush();
        byte[] header = hb.toByteArray();
        out.write(header);
        encryptChunks(in, out, dek, ivp, header);
        out.flush();
        Arrays.fill(dek, (byte) 0);
    }

    public static void decryptDevice(InputStream in, OutputStream out, KeyWrapper kw)
            throws IOException, GeneralSecurityException {
        Capture cap = new Capture(in);
        DataInputStream d = new DataInputStream(cap);
        expectMagic(d, MAGIC_DEVICE);
        int wl = d.readUnsignedShort();
        if (wl <= 0 || wl > 512) throw new IOException("Formato non valido");
        byte[] wrapped = new byte[wl];
        d.readFully(wrapped);
        byte[] ivp = new byte[4];
        d.readFully(ivp);
        cap.on = false;
        byte[] header = cap.buf.toByteArray();
        byte[] dek = kw.unwrap(wrapped);
        try {
            decryptChunks(d, out, dek, ivp, header);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    // ---------------- formato esportazione ----------------

    public static void encryptExport(InputStream in, OutputStream out, char[] pass, String name, boolean noReshare)
            throws IOException, GeneralSecurityException {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] ivp = new byte[4];
        RNG.nextBytes(ivp);
        byte[] nameB = name.getBytes(StandardCharsets.UTF_8);
        if (nameB.length > 255) nameB = Arrays.copyOf(nameB, 255);
        ByteArrayOutputStream hb = new ByteArrayOutputStream();
        DataOutputStream h = new DataOutputStream(hb);
        h.write(MAGIC_EXPORT);
        h.writeByte(noReshare ? 1 : 0);
        h.writeByte(nameB.length);
        h.write(nameB);
        h.write(salt);
        h.writeInt(PBKDF2_ITER);
        h.write(ivp);
        h.flush();
        byte[] header = hb.toByteArray();
        byte[] key = derive(pass, salt, PBKDF2_ITER);
        out.write(header);
        encryptChunks(in, out, key, ivp, header);
        out.flush();
        Arrays.fill(key, (byte) 0);
    }

    public static ExportInfo decryptExport(InputStream in, OutputStream out, char[] pass)
            throws IOException, GeneralSecurityException {
        Capture cap = new Capture(in);
        DataInputStream d = new DataInputStream(cap);
        expectMagic(d, MAGIC_EXPORT);
        int flags = d.readUnsignedByte();
        int nl = d.readUnsignedByte();
        byte[] nameB = new byte[nl];
        d.readFully(nameB);
        byte[] salt = new byte[16];
        d.readFully(salt);
        int iter = d.readInt();
        if (iter < 10_000 || iter > 2_000_000) throw new IOException("Formato non valido");
        byte[] ivp = new byte[4];
        d.readFully(ivp);
        cap.on = false;
        byte[] header = cap.buf.toByteArray();
        byte[] key = derive(pass, salt, iter);
        try {
            decryptChunks(d, out, key, ivp, header);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        ExportInfo info = new ExportInfo();
        info.name = new String(nameB, StandardCharsets.UTF_8);
        info.noReshare = (flags & 1) != 0;
        return info;
    }

    // ---------------- blocchi ----------------

    private static void encryptChunks(InputStream in, OutputStream out, byte[] key, byte[] ivp, byte[] header)
            throws IOException, GeneralSecurityException {
        DataOutputStream d = new DataOutputStream(out);
        byte[] cur = new byte[CHUNK];
        byte[] nxt = new byte[CHUNK];
        int curLen = readFully(in, cur);
        long counter = 0;
        while (true) {
            int nxtLen = (curLen == CHUNK) ? readFully(in, nxt) : 0;
            boolean last = curLen < CHUNK || nxtLen == 0;
            byte[] ct = crypt(Cipher.ENCRYPT_MODE, key, ivp, counter, last, header, cur, 0, curLen);
            d.writeInt(ct.length);
            d.write(ct);
            if (last) break;
            counter++;
            byte[] t = cur; cur = nxt; nxt = t;
            curLen = nxtLen;
        }
        d.flush();
    }

    private static void decryptChunks(DataInputStream d, OutputStream out, byte[] key, byte[] ivp, byte[] header)
            throws IOException, GeneralSecurityException {
        long counter = 0;
        while (true) {
            int len;
            try {
                len = d.readInt();
            } catch (EOFException e) {
                throw new IOException("File troncato");
            }
            if (len < TAG_BYTES || len > CHUNK + TAG_BYTES) throw new IOException("Formato non valido");
            byte[] ct = new byte[len];
            d.readFully(ct);
            byte[] pt;
            boolean last = false;
            try {
                pt = crypt(Cipher.DECRYPT_MODE, key, ivp, counter, false, header, ct, 0, len);
            } catch (GeneralSecurityException e) {
                pt = crypt(Cipher.DECRYPT_MODE, key, ivp, counter, true, header, ct, 0, len);
                last = true;
            }
            out.write(pt);
            if (last) return;
            counter++;
        }
    }

    private static byte[] crypt(int mode, byte[] key, byte[] ivp, long ctr, boolean last, byte[] header,
                                byte[] buf, int off, int len) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        System.arraycopy(ivp, 0, iv, 0, 4);
        for (int i = 0; i < 8; i++) iv[11 - i] = (byte) (ctr >>> (8 * i));
        byte[] aad = Arrays.copyOf(header, header.length + 1);
        aad[header.length] = (byte) (last ? 1 : 0);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad);
        return c.doFinal(buf, off, len);
    }

    private static byte[] derive(char[] pass, byte[] salt, int iter) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, iter, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    private static void expectMagic(DataInputStream d, byte[] magic) throws IOException {
        byte[] m = new byte[4];
        d.readFully(m);
        if (!Arrays.equals(m, magic)) throw new IOException("Formato non valido");
    }
}
