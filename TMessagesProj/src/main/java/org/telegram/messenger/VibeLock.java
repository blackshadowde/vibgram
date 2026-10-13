package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Vibgram "Vibe lock": optional end-to-end locking of a one-to-one chat between Vibgram apps, on several
 * Android phones / tablets per person.
 *
 * Keys
 * - Account identity: an ECDSA P-256 signing key held in the Android Keystore of the person's main phone.
 *   It signs the person's "device card" (the list of their devices). The safety code is made from it.
 * - Device keys: every Vibgram install has an identity key (ECDH P-256, inside the Keystore on Android 12+)
 *   and a signed pre-key (software, stored encrypted).
 *
 * Messages
 * - X3DH per device pair: SK = HKDF(DH(IKa, SPKb) || DH(EKa, IKb) || DH(EKa, SPKb)).
 * - Double Ratchet per device pair (Signal style): a new key for every message, a new ECDH step every turn,
 *   used keys deleted. Out-of-order / lost messages handled with skipped keys.
 * - Each message is encrypted once (AES-256-GCM, random key) and that key is sealed for every device that
 *   should read it - the other person's devices and our own other devices - each with its ratchet.
 * - A changed device card rides inside the next locked message, so new devices are picked up on their own.
 *
 * Linking: a new device posts a link message (its keys) to Saved Messages and shows a 6-digit code. The main
 * phone checks the code, adds the device to the signed card and sends the card + locked chats back, encrypted
 * for that device only.
 *
 * Storage: ratchet sessions, pre-keys and opened messages are encrypted with a storage key that is wrapped by a
 * Keystore AES-256-GCM master key. Telegram's servers only ever see the locked text.
 */
public class VibeLock {

    public static final String HEADER = "🔒 Vibe lock message\n";
    private static final String TOKEN = "VL3:";
    public static final String REQ = "VL3REQ:";
    public static final String ACK = "VL3ACK:";
    public static final String OFF = "VL3OFF";
    public static final String LINK = "VL3LINK:";
    public static final String LINKOK = "VL3LINKOK:";
    private static final String[] OLD_HIDDEN = {"VL1REQ:", "VL1ACK:", "VL2REQ:", "VL2ACK:"};
    private static final String[] OLD_OFF = {"VL1OFF", "VL2OFF"};

    public static final int NONE = 0, REQUESTED = 1, INCOMING = 2, ON = 3;

    private static final int MAX_SKIP = 1000;
    private static final int MAX_STORED_SKIPPED = 2000;
    private static final int MAX_SESSIONS_PER_PAIR = 3;
    private static final byte VERSION = 3;
    private static final int POINT = 65;

    private static final byte[] INFO_X3DH = "vibe-lock-v3-x3dh".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_RATCHET = "vibe-lock-v3-ratchet".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_KEY = "vibe-lock-v3-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_LINK = "vibe-lock-v3-link".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CARD_TAG = "vibe-lock-v3-card".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom random = new SecureRandom();

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_ALIAS = "vibelock_master";

    private static volatile SecretKey master;
    private static volatile byte[] storageKey;
    private static volatile byte[] x509Prefix;
    private static final KeyPair[] deviceKeys = new KeyPair[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] deviceInKeystore = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /** Vibe lock needs the Android Keystore (Android 6+). */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 23;
    }

    private static boolean keystoreAgreement() {
        return Build.VERSION.SDK_INT >= 31;
    }

    // ================================================================ preferences

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock3_" + account, Context.MODE_PRIVATE);
    }

    private static SharedPreferences globalPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock3_global", Context.MODE_PRIVATE);
    }

    private static SharedPreferences sessionPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock3_sessions", Context.MODE_PRIVATE);
    }

    public static int state(int account, long userId) {
        return prefs(account).getInt("state_" + userId, NONE);
    }

    public static void setState(int account, long userId, int state) {
        prefs(account).edit().putInt("state_" + userId, state).apply();
    }

    public static boolean isOn(int account, long dialogId) {
        return dialogId > 0 && supported() && state(account, dialogId) == ON
                && prefs(account).contains("card_" + dialogId) && prefs(account).contains("mycard");
    }

    public static int lastProcessed(int account, long userId) {
        return prefs(account).getInt("last_" + userId, 0);
    }

    public static void setLastProcessed(int account, long userId, int msgId) {
        prefs(account).edit().putInt("last_" + userId, msgId).apply();
    }

    public static int prompted(int account, long userId) {
        return prefs(account).getInt("asked_" + userId, 0);
    }

    public static void setPrompted(int account, long userId, int msgId) {
        prefs(account).edit().putInt("asked_" + userId, msgId).apply();
    }

    /** This phone holds the account identity (it can link and remove devices). */
    public static boolean isMainDevice(int account) {
        return prefs(account).getBoolean("main", false);
    }

    public static boolean isSetUp(int account) {
        return prefs(account).contains("mycard");
    }

    public static boolean isWaitingForLink(int account) {
        return prefs(account).getBoolean("linkwait", false);
    }

    // ================================================================ keystore + storage key

    private static SecretKey master() throws Exception {
        SecretKey k = master;
        if (k != null) {
            return k;
        }
        synchronized (VibeLock.class) {
            if (master != null) {
                return master;
            }
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(MASTER_ALIAS)) {
                k = ((KeyStore.SecretKeyEntry) ks.getEntry(MASTER_ALIAS, null)).getSecretKey();
            } else {
                KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
                kg.init(new KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                k = kg.generateKey();
            }
            master = k;
            return k;
        }
    }

    /** A random key for everything stored on the phone, wrapped by the Keystore master key. */
    private static byte[] storageKey() throws Exception {
        byte[] k = storageKey;
        if (k != null) {
            return k;
        }
        synchronized (VibeLock.class) {
            if (storageKey != null) {
                return storageKey;
            }
            SharedPreferences g = globalPrefs();
            String w = g.getString("sk_w", null);
            if (w != null) {
                byte[] in = unb64(w);
                int ivLen = in[0] & 0xFF;
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, master(), new GCMParameterSpec(128, in, 1, ivLen));
                k = c.doFinal(in, 1 + ivLen, in.length - 1 - ivLen);
            } else {
                k = new byte[32];
                random.nextBytes(k);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, master());
                byte[] iv = c.getIV();
                byte[] ct = c.doFinal(k);
                ByteBuffer b = ByteBuffer.allocate(1 + iv.length + ct.length);
                b.put((byte) iv.length).put(iv).put(ct);
                g.edit().putString("sk_w", b64(b.array())).commit();
            }
            storageKey = k;
            return k;
        }
    }

    private static byte[] seal(byte[] plain) throws Exception {
        return gcm(true, storageKey(), null, plain, null);
    }

    private static byte[] open(byte[] sealed) throws Exception {
        return gcm(false, storageKey(), null, sealed, null);
    }

    /** AES-256-GCM. With iv == null a random IV is put in front of the output (and read from the input). */
    private static byte[] gcm(boolean encrypt, byte[] key, byte[] iv, byte[] data, byte[] ad) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec ks = new SecretKeySpec(key, 0, 32, "AES");
        if (iv == null) {
            if (encrypt) {
                byte[] r = new byte[12];
                random.nextBytes(r);
                c.init(Cipher.ENCRYPT_MODE, ks, new GCMParameterSpec(128, r));
                if (ad != null) c.updateAAD(ad);
                return concat(r, c.doFinal(data));
            }
            c.init(Cipher.DECRYPT_MODE, ks, new GCMParameterSpec(128, data, 0, 12));
            if (ad != null) c.updateAAD(ad);
            return c.doFinal(data, 12, data.length - 12);
        }
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, ks, new GCMParameterSpec(128, iv));
        if (ad != null) c.updateAAD(ad);
        return c.doFinal(data);
    }

    // ================================================================ key helpers

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), random);
        return g.generateKeyPair();
    }

    /** The 65-byte public point of an EC key (X.509 minus its fixed prefix). */
    private static byte[] point(PublicKey k) {
        byte[] enc = k.getEncoded();
        if (x509Prefix == null) {
            x509Prefix = Arrays.copyOf(enc, enc.length - POINT);
        }
        return Arrays.copyOfRange(enc, enc.length - POINT, enc.length);
    }

    private static PublicKey fromPoint(byte[] p) throws Exception {
        if (x509Prefix == null) {
            point(newKeyPair().getPublic());
        }
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(concat(x509Prefix, p)));
    }

    private static PrivateKey privateKey(byte[] pkcs8) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private static byte[] fp(byte[] data) throws Exception {
        return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(data), 8);
    }

    private static byte[] dh(PrivateKey mine, PublicKey peer, boolean keystore) throws Exception {
        KeyAgreement ka = keystore ? KeyAgreement.getInstance("ECDH", KEYSTORE) : KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(peer, true);
        return ka.generateSecret();
    }

    // ================================================================ this device

    /** This install's identity key for the account (inside the Keystore on Android 12+). */
    private static synchronized KeyPair deviceKey(int account) throws Exception {
        if (deviceKeys[account] != null) {
            return deviceKeys[account];
        }
        SharedPreferences p = prefs(account);
        KeyPair kp;
        String sw = p.getString("dev_sw", null);
        if (sw != null) {
            kp = new KeyPair(fromPoint(unb64(p.getString("dev_pub", ""))), privateKey(open(unb64(sw))));
        } else if (keystoreAgreement()) {
            String alias = "vibelock3_dev_" + account;
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(alias)) {
                KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
                g.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .build());
                g.generateKeyPair();
            }
            kp = new KeyPair(ks.getCertificate(alias).getPublicKey(), (PrivateKey) ks.getKey(alias, null));
            deviceInKeystore[account] = true;
        } else {
            kp = newKeyPair();
            p.edit().putString("dev_sw", b64(seal(kp.getPrivate().getEncoded()))).putString("dev_pub", b64(point(kp.getPublic()))).commit();
        }
        deviceKeys[account] = kp;
        globalPrefs().edit().putString("dev_" + Utilities.bytesToHex(fp(point(kp.getPublic()))), String.valueOf(account)).apply();
        return kp;
    }

    private static byte[] deviceId(int account) throws Exception {
        return fp(point(deviceKey(account).getPublic()));
    }

    private static byte[] dhDevice(int account, PublicKey peer) throws Exception {
        KeyPair me = deviceKey(account);
        return dh(me.getPrivate(), peer, deviceInKeystore[account]);
    }

    /** This install's signed pre-key (software, stored encrypted). */
    private static KeyPair preKey(int account) throws Exception {
        SharedPreferences p = prefs(account);
        String w = p.getString("spk_w", null);
        if (w != null) {
            return new KeyPair(fromPoint(unb64(p.getString("spk_pub", ""))), privateKey(open(unb64(w))));
        }
        KeyPair kp = newKeyPair();
        p.edit().putString("spk_w", b64(seal(kp.getPrivate().getEncoded()))).putString("spk_pub", b64(point(kp.getPublic()))).commit();
        return kp;
    }

    // ================================================================ account identity + device card

    private static String sigAlias(int account) {
        return "vibelock3_sig_" + account;
    }

    private static PrivateKey sigKey(int account) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        return ks.containsAlias(sigAlias(account)) ? (PrivateKey) ks.getKey(sigAlias(account), null) : null;
    }

    /** A person's devices, signed by their account identity. */
    static class Card {
        byte[] sigPoint;
        int version;
        ArrayList<byte[][]> devices = new ArrayList<>(); // {identity point, pre-key point}
        byte[] signature;

        byte[] body() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(sigPoint, 0, POINT);
            o.write(ByteBuffer.allocate(4).putInt(version).array(), 0, 4);
            o.write(devices.size());
            for (byte[][] d : devices) {
                o.write(d[0], 0, POINT);
                o.write(d[1], 0, POINT);
            }
            return o.toByteArray();
        }

        byte[] toBytes() {
            byte[] b = body();
            ByteBuffer out = ByteBuffer.allocate(b.length + 1 + signature.length);
            out.put(b).put((byte) signature.length).put(signature);
            return out.array();
        }

        static Card parse(byte[] data) throws Exception {
            ByteBuffer in = ByteBuffer.wrap(data);
            Card c = new Card();
            c.sigPoint = new byte[POINT];
            in.get(c.sigPoint);
            c.version = in.getInt();
            int n = in.get() & 0xFF;
            for (int i = 0; i < n; i++) {
                byte[] ik = new byte[POINT], spk = new byte[POINT];
                in.get(ik);
                in.get(spk);
                c.devices.add(new byte[][]{ik, spk});
            }
            c.signature = new byte[in.get() & 0xFF];
            in.get(c.signature);
            if (!c.verify()) {
                throw new SecurityException("bad card signature");
            }
            return c;
        }

        boolean verify() {
            try {
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initVerify(fromPoint(sigPoint));
                s.update(CARD_TAG);
                s.update(body());
                return s.verify(signature);
            } catch (Throwable e) {
                return false;
            }
        }

        void sign(PrivateKey key) throws Exception {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(key);
            s.update(CARD_TAG);
            s.update(body());
            signature = s.sign();
        }

        String owner() throws Exception {
            return Utilities.bytesToHex(fp(sigPoint));
        }

        byte[][] device(byte[] devId) throws Exception {
            for (byte[][] d : devices) {
                if (Arrays.equals(fp(d[0]), devId)) {
                    return d;
                }
            }
            return null;
        }
    }

    private static Card loadCard(SharedPreferences p, String key) {
        try {
            String s = p.getString(key, null);
            return s == null ? null : Card.parse(unb64(s));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static Card myCard(int account) {
        return loadCard(prefs(account), "mycard");
    }

    /** First use on this account: this phone becomes the main device and creates the account identity. */
    public static synchronized boolean setUpHere(int account) {
        try {
            if (isSetUp(account)) {
                return true;
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            g.initialize(new KeyGenParameterSpec.Builder(sigAlias(account), KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build());
            KeyPair sig = g.generateKeyPair();
            Card c = new Card();
            c.sigPoint = point(sig.getPublic());
            c.version = 1;
            c.devices.add(new byte[][]{point(deviceKey(account).getPublic()), point(preKey(account).getPublic())});
            c.sign(sigKey(account));
            prefs(account).edit().putString("mycard", b64(c.toBytes())).putBoolean("main", true).remove("linkwait").commit();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Devices on this account, as {short id, "this device" flag}. */
    public static ArrayList<String[]> myDevices(int account) {
        ArrayList<String[]> out = new ArrayList<>();
        try {
            Card c = myCard(account);
            if (c == null) {
                return out;
            }
            byte[] me = deviceId(account);
            for (int i = 0; i < c.devices.size(); i++) {
                byte[] id = fp(c.devices.get(i)[0]);
                out.add(new String[]{Utilities.bytesToHex(id), Arrays.equals(id, me) ? "1" : "0", i == 0 ? "1" : "0"});
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return out;
    }

    /** Main phone only: takes a device off the account. */
    public static synchronized boolean removeDevice(int account, String devIdHex) {
        try {
            PrivateKey sk = sigKey(account);
            Card c = myCard(account);
            if (sk == null || c == null) {
                return false;
            }
            byte[] id = Utilities.hexToBytes(devIdHex);
            if (Arrays.equals(id, deviceId(account))) {
                return false;
            }
            for (Iterator<byte[][]> it = c.devices.iterator(); it.hasNext(); ) {
                if (Arrays.equals(fp(it.next()[0]), id)) {
                    it.remove();
                }
            }
            c.version++;
            c.sign(sk);
            prefs(account).edit().putString("mycard", b64(c.toBytes())).commit();
            dropSessionsWith(account, id);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Short code both people compare: made from both account identities, the same on every device. */
    public static String safetyCode(int account, long userId) {
        try {
            Card mine = myCard(account), theirs = loadCard(prefs(account), "card_" + userId);
            if (mine == null || theirs == null) {
                return null;
            }
            return code(mine.sigPoint, theirs.sigPoint, 6);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String code(byte[] a, byte[] b, int bytes) throws Exception {
        byte[] first = compare(a, b) <= 0 ? a : b;
        byte[] second = first == a ? b : a;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(first);
        if (second != null) md.update(second);
        byte[] h = md.digest();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            if (i > 0 && i % 2 == 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", h[i] & 0xFF));
        }
        return sb.toString();
    }

    // ================================================================ KDFs

    private static byte[] hmac(byte[] key, byte[]... parts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) {
            mac.update(p);
        }
        return mac.doFinal();
    }

    /** HKDF-SHA256 (RFC 5869). */
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        byte[] prk = hmac(salt == null ? new byte[32] : salt, ikm);
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            t = hmac(prk, t, info, new byte[]{(byte) i});
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    private static byte[][] kdfRoot(byte[] rk, byte[] dhOut) throws Exception {
        byte[] o = hkdf(rk, dhOut, INFO_RATCHET, 64);
        return new byte[][]{Arrays.copyOfRange(o, 0, 32), Arrays.copyOfRange(o, 32, 64)};
    }

    private static byte[][] kdfChain(byte[] ck) throws Exception {
        return new byte[][]{hmac(ck, new byte[]{1}), hmac(ck, new byte[]{2})};
    }

    // ================================================================ ratchet sessions (one or more per device pair)

    private static class Session {
        byte[] rk, cks, ckr;
        PrivateKey dhsPriv;
        byte[] dhsPub;      // point
        byte[] dhr;         // point
        int ns, nr, pn;
        byte[] pendingEk;   // our X3DH one-time key, sent until the other device answers
        byte[] theirEk;     // their X3DH one-time key, when they started this session
        JSONObject skipped = new JSONObject();

        JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("rk", b64(rk));
            if (cks != null) j.put("cks", b64(cks));
            if (ckr != null) j.put("ckr", b64(ckr));
            j.put("dhsPriv", b64(dhsPriv.getEncoded()));
            j.put("dhsPub", b64(dhsPub));
            if (dhr != null) j.put("dhr", b64(dhr));
            if (pendingEk != null) j.put("pek", b64(pendingEk));
            if (theirEk != null) j.put("tek", b64(theirEk));
            j.put("ns", ns);
            j.put("nr", nr);
            j.put("pn", pn);
            j.put("skipped", skipped);
            return j;
        }

        static Session fromJson(JSONObject j) throws Exception {
            Session s = new Session();
            s.rk = unb64(j.getString("rk"));
            s.cks = j.has("cks") ? unb64(j.getString("cks")) : null;
            s.ckr = j.has("ckr") ? unb64(j.getString("ckr")) : null;
            s.dhsPriv = privateKey(unb64(j.getString("dhsPriv")));
            s.dhsPub = unb64(j.getString("dhsPub"));
            s.dhr = j.has("dhr") ? unb64(j.getString("dhr")) : null;
            s.pendingEk = j.has("pek") ? unb64(j.getString("pek")) : null;
            s.theirEk = j.has("tek") ? unb64(j.getString("tek")) : null;
            s.ns = j.getInt("ns");
            s.nr = j.getInt("nr");
            s.pn = j.getInt("pn");
            JSONObject sk = j.optJSONObject("skipped");
            s.skipped = sk != null ? sk : new JSONObject();
            return s;
        }
    }

    private static String sid(byte[] myDev, byte[] peerDev) {
        return Utilities.bytesToHex(myDev) + ":" + Utilities.bytesToHex(peerDev);
    }

    private static ArrayList<Session> loadSessions(String sid) {
        ArrayList<Session> out = new ArrayList<>();
        try {
            String s = sessionPrefs().getString(sid, null);
            if (s != null) {
                JSONArray a = new JSONArray(new String(open(unb64(s)), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    out.add(Session.fromJson(a.getJSONObject(i)));
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return out;
    }

    private static void saveSessions(String sid, ArrayList<Session> list) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < Math.min(list.size(), MAX_SESSIONS_PER_PAIR); i++) {
            a.put(list.get(i).toJson());
        }
        sessionPrefs().edit().putString(sid, b64(seal(a.toString().getBytes(StandardCharsets.UTF_8)))).commit();
    }

    private static void dropSessionsWith(int account, byte[] peerDev) {
        try {
            sessionPrefs().edit().remove(sid(deviceId(account), peerDev)).commit();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void dhRatchet(Session s, byte[] peerDh) throws Exception {
        s.pn = s.ns;
        s.ns = 0;
        s.nr = 0;
        s.dhr = peerDh;
        PublicKey peer = fromPoint(peerDh);
        byte[][] a = kdfRoot(s.rk, dh(s.dhsPriv, peer, false));
        s.rk = a[0];
        s.ckr = a[1];
        KeyPair next = newKeyPair();
        s.dhsPriv = next.getPrivate();
        s.dhsPub = point(next.getPublic());
        byte[][] b = kdfRoot(s.rk, dh(s.dhsPriv, peer, false));
        s.rk = b[0];
        s.cks = b[1];
    }

    private static void skipKeys(Session s, int until) throws Exception {
        if (s.ckr == null) {
            return;
        }
        if (until - s.nr > MAX_SKIP) {
            throw new IllegalStateException("too many skipped messages");
        }
        while (s.nr < until) {
            byte[][] k = kdfChain(s.ckr);
            s.ckr = k[1];
            s.skipped.put(b64(s.dhr) + ":" + s.nr, b64(k[0]));
            s.nr++;
        }
        while (s.skipped.length() > MAX_STORED_SKIPPED) {
            Iterator<String> it = s.skipped.keys();
            if (!it.hasNext()) {
                break;
            }
            s.skipped.remove(it.next());
        }
    }

    /** X3DH as the starting side, towards one of the other person's (or our own) devices. */
    private static Session startSession(int account, byte[] peerIk, byte[] peerSpk) throws Exception {
        PublicKey ik = fromPoint(peerIk), spk = fromPoint(peerSpk);
        KeyPair ek = newKeyPair();
        byte[] sk = hkdf(null, concat(concat(dhDevice(account, spk), dh(ek.getPrivate(), ik, false)), dh(ek.getPrivate(), spk, false)), INFO_X3DH, 32);
        Session s = new Session();
        KeyPair dhs = newKeyPair();
        s.dhsPriv = dhs.getPrivate();
        s.dhsPub = point(dhs.getPublic());
        s.dhr = peerSpk;
        byte[][] a = kdfRoot(sk, dh(s.dhsPriv, spk, false));
        s.rk = a[0];
        s.cks = a[1];
        s.pendingEk = point(ek.getPublic());
        return s;
    }

    /** X3DH as the answering side, when a device started a session with us. */
    private static Session answerSession(int account, byte[] peerIk, byte[] theirEk) throws Exception {
        PublicKey ik = fromPoint(peerIk), ek = fromPoint(theirEk);
        KeyPair spk = preKey(account);
        byte[] sk = hkdf(null, concat(concat(dh(spk.getPrivate(), ik, false), dhDevice(account, ek)), dh(spk.getPrivate(), ek, false)), INFO_X3DH, 32);
        Session s = new Session();
        s.rk = sk;
        s.dhsPriv = spk.getPrivate();
        s.dhsPub = point(spk.getPublic());
        s.theirEk = theirEk;
        return s;
    }

    // ================================================================ request / accept / off

    public static synchronized String makeRequest(int account, long userId) {
        try {
            if (isWaitingForLink(account) || (!isSetUp(account) && !setUpHere(account))) {
                return null;
            }
            byte[] reqId = new byte[8];
            random.nextBytes(reqId);
            String id = Utilities.bytesToHex(reqId);
            SharedPreferences p = prefs(account);
            String old = p.getString("reqid_" + userId, null);
            SharedPreferences.Editor g = globalPrefs().edit();
            if (old != null) {
                g.remove("pend_" + old);
            }
            g.putString("pend_" + id, account + ":" + userId).commit();
            p.edit().putString("reqid_" + userId, id).putInt("state_" + userId, REQUESTED).commit();
            return "🔒 Vibe lock request\n"
                    + "I'd like to lock this chat so only Vibgram on our phones can read it. Open the chat in Vibgram to accept.\n"
                    + REQ + id + "." + p.getString("mycard", "");
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void cancelRequest(int account, long userId) {
        SharedPreferences p = prefs(account);
        String id = p.getString("reqid_" + userId, null);
        if (id != null) {
            globalPrefs().edit().remove("pend_" + id).apply();
        }
        p.edit().remove("reqid_" + userId).putInt("state_" + userId, NONE).apply();
    }

    /** Someone asked us: keep their request until we decide. Returns false if it isn't valid. */
    public static boolean rememberRequest(int account, long userId, String reqValue) {
        try {
            String[] parts = reqValue.split("\\.");
            Card.parse(unb64(parts[1]));
            prefs(account).edit().putString("req_" + userId, reqValue).putInt("state_" + userId, INCOMING).apply();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String pendingRequest(int account, long userId) {
        return prefs(account).getString("req_" + userId, null);
    }

    /** True if their identity differs from the one we had for them before (new code to compare). */
    public static boolean requestChangesIdentity(int account, long userId, String reqValue) {
        try {
            Card had = loadCard(prefs(account), "card_" + userId);
            Card now = Card.parse(unb64(reqValue.split("\\.")[1]));
            return had != null && !Arrays.equals(had.sigPoint, now.sigPoint);
        } catch (Throwable e) {
            return false;
        }
    }

    private static void storeContactCard(int account, long userId, Card c) throws Exception {
        SharedPreferences p = prefs(account);
        Card old = loadCard(p, "card_" + userId);
        p.edit().putString("card_" + userId, b64(c.toBytes())).putString("who_" + c.owner(), String.valueOf(userId)).commit();
        if (old != null) {
            // sessions with devices that are no longer theirs are deleted
            for (byte[][] d : old.devices) {
                if (c.device(fp(d[0])) == null) {
                    dropSessionsWith(account, fp(d[0]));
                }
            }
        }
    }

    /** Accepts a request. Returns the answer text to send, or null. */
    public static synchronized String accept(int account, long userId) {
        try {
            String value = prefs(account).getString("req_" + userId, null);
            if (value == null || isWaitingForLink(account) || (!isSetUp(account) && !setUpHere(account))) {
                return null;
            }
            String[] parts = value.split("\\.");
            Card theirs = Card.parse(unb64(parts[1]));
            storeContactCard(account, userId, theirs);
            SharedPreferences p = prefs(account);
            String mine = p.getString("reqid_" + userId, null);
            if (mine != null) {
                globalPrefs().edit().remove("pend_" + mine).apply();
            }
            p.edit().remove("reqid_" + userId).remove("req_" + userId).putInt("state_" + userId, ON).putInt("sentv_" + userId, 0).commit();
            return "🔒 Vibe lock is on\n"
                    + "Messages in this chat are now locked between our Vibgram apps.\n"
                    + ACK + parts[0] + "." + p.getString("mycard", "");
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** The answer to a request we sent from this phone. Returns the user id, or 0. */
    public static synchronized long handleAck(String text) {
        try {
            String v = handshakeValue(text, ACK);
            if (v == null) {
                return 0;
            }
            String[] parts = v.split("\\.");
            String pend = globalPrefs().getString("pend_" + parts[0], null);
            if (pend == null) {
                return 0;
            }
            String[] pp = pend.split(":");
            int account = Integer.parseInt(pp[0]);
            long userId = Long.parseLong(pp[1]);
            finishAck(account, userId, parts[1]);
            globalPrefs().edit().remove("pend_" + parts[0]).commit();
            return userId;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** An answer seen in the chat (also when another of our devices sent the request). */
    public static synchronized boolean acceptAnswer(int account, long userId, String ackValue) {
        try {
            String[] parts = ackValue.split("\\.");
            finishAck(account, userId, parts[1]);
            globalPrefs().edit().remove("pend_" + parts[0]).commit();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void finishAck(int account, long userId, String cardB64) throws Exception {
        Card theirs = Card.parse(unb64(cardB64));
        storeContactCard(account, userId, theirs);
        prefs(account).edit().remove("reqid_" + userId).putInt("state_" + userId, ON).putInt("sentv_" + userId, 0).commit();
    }

    /** Our other device accepted their request: take the card from their request. */
    public static synchronized boolean ourOtherDeviceAccepted(int account, long userId) {
        try {
            String value = prefs(account).getString("req_" + userId, null);
            if (value == null) {
                return false;
            }
            storeContactCard(account, userId, Card.parse(unb64(value.split("\\.")[1])));
            prefs(account).edit().remove("req_" + userId).putInt("state_" + userId, ON).commit();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static synchronized void turnOff(int account, long userId) {
        try {
            SharedPreferences p = prefs(account);
            Card c = loadCard(p, "card_" + userId);
            if (c != null) {
                for (byte[][] d : c.devices) {
                    dropSessionsWith(account, fp(d[0]));
                }
            }
            String id = p.getString("reqid_" + userId, null);
            if (id != null) {
                globalPrefs().edit().remove("pend_" + id).apply();
            }
            p.edit().remove("reqid_" + userId).remove("req_" + userId).putInt("state_" + userId, NONE).commit();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Both asked at once: the side with the bigger identity answers. */
    public static boolean shouldAnswerCrossedRequest(int account, String theirReqValue) {
        try {
            Card mine = myCard(account);
            Card theirs = Card.parse(unb64(theirReqValue.split("\\.")[1]));
            return mine != null && compare(fp(mine.sigPoint), fp(theirs.sigPoint)) > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    // ================================================================ linking devices

    /** On the new device: the text to post in Saved Messages, and remember that we are waiting. */
    public static synchronized String makeLinkRequest(int account) {
        try {
            byte[] ik = point(deviceKey(account).getPublic());
            byte[] spk = point(preKey(account).getPublic());
            prefs(account).edit().putBoolean("linkwait", true).commit();
            return "🔒 Vibe lock: link a device\n"
                    + "Open Saved Messages in Vibgram on your main phone to approve this device.\n"
                    + LINK + b64(concat(ik, spk));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void cancelLink(int account) {
        prefs(account).edit().remove("linkwait").apply();
    }

    /** 6-digit code shown on both phones while linking. */
    public static String linkCode(String linkValue) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(unb64(linkValue));
            int n = ((h[0] & 0xFF) << 16 | (h[1] & 0xFF) << 8 | (h[2] & 0xFF)) % 1000000;
            String s = String.format("%06d", n);
            return s.substring(0, 3) + " " + s.substring(3);
        } catch (Throwable e) {
            return "";
        }
    }

    public static String myLinkCode(int account) {
        try {
            return linkCode(b64(concat(point(deviceKey(account).getPublic()), point(preKey(account).getPublic()))));
        } catch (Throwable e) {
            return "";
        }
    }

    /** True if a link request is for a device that isn't on our card yet. */
    public static boolean isNewDeviceLink(int account, String linkValue) {
        try {
            Card c = myCard(account);
            byte[] raw = unb64(linkValue);
            return c != null && raw.length == 2 * POINT && c.device(fp(Arrays.copyOf(raw, POINT))) == null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Main phone: adds the device to the signed card and returns the texts to post in Saved Messages
     * (the card and the locked chats, encrypted for that device only), or null.
     */
    public static synchronized ArrayList<String> approveLink(int account, String linkValue) {
        try {
            PrivateKey sk = sigKey(account);
            Card c = myCard(account);
            if (sk == null || c == null) {
                return null;
            }
            byte[] raw = unb64(linkValue);
            byte[] ik = Arrays.copyOf(raw, POINT), spk = Arrays.copyOfRange(raw, POINT, 2 * POINT);
            if (c.device(fp(ik)) == null) {
                c.devices.add(new byte[][]{ik, spk});
                c.version++;
                c.sign(sk);
                prefs(account).edit().putString("mycard", b64(c.toBytes())).commit();
            }
            // bundle: card + every locked chat (user id + their card)
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] cb = c.toBytes();
            o.write(ByteBuffer.allocate(2).putShort((short) cb.length).array());
            o.write(cb);
            SharedPreferences p = prefs(account);
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (e.getKey().startsWith("state_") && Integer.valueOf(ON).equals(e.getValue())) {
                    long uid = Long.parseLong(e.getKey().substring(6));
                    String card = p.getString("card_" + uid, null);
                    if (card == null) {
                        continue;
                    }
                    byte[] theirs = unb64(card);
                    o.write(ByteBuffer.allocate(8).putLong(uid).array());
                    o.write(ByteBuffer.allocate(2).putShort((short) theirs.length).array());
                    o.write(theirs);
                }
            }
            KeyPair eph = newKeyPair();
            byte[] key = hkdf(null, dh(eph.getPrivate(), fromPoint(ik), false), INFO_LINK, 32);
            String blob = b64(concat(point(eph.getPublic()), gcm(true, key, null, o.toByteArray(), ik)));
            String target = Utilities.bytesToHex(fp(ik));
            ArrayList<String> out = new ArrayList<>();
            int size = 3000, total = (blob.length() + size - 1) / size;
            for (int i = 0; i < total; i++) {
                out.add("🔒 Vibe lock: device approved (" + (i + 1) + "/" + total + ")\n"
                        + LINKOK + target + "." + i + "." + total + "." + blob.substring(i * size, Math.min(blob.length(), (i + 1) * size)));
            }
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** New device: collects the approval parts; installs everything once all parts are here. */
    public static synchronized int handleLinkApproval(String text) {
        try {
            String v = handshakeValue(text, LINKOK);
            if (v == null) {
                return -1;
            }
            String[] parts = v.split("\\.");
            String acc = globalPrefs().getString("dev_" + parts[0], null);
            if (acc == null) {
                return -1; // not for this phone
            }
            int account = Integer.parseInt(acc);
            SharedPreferences p = prefs(account);
            if (!p.getBoolean("linkwait", false)) {
                return -1;
            }
            int index = Integer.parseInt(parts[1]), total = Integer.parseInt(parts[2]);
            p.edit().putString("linkpart_" + index, parts[3]).commit();
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < total; i++) {
                String s = p.getString("linkpart_" + i, null);
                if (s == null) {
                    return -1;
                }
                all.append(s);
            }
            byte[] blob = unb64(all.toString());
            byte[] ephPoint = Arrays.copyOf(blob, POINT);
            byte[] ik = point(deviceKey(account).getPublic());
            byte[] key = hkdf(null, dhDevice(account, fromPoint(ephPoint)), INFO_LINK, 32);
            ByteBuffer in = ByteBuffer.wrap(gcm(false, key, null, Arrays.copyOfRange(blob, POINT, blob.length), ik));
            byte[] cb = new byte[in.getShort() & 0xFFFF];
            in.get(cb);
            Card mine = Card.parse(cb);
            if (mine.device(fp(ik)) == null) {
                return -1;
            }
            SharedPreferences.Editor e = p.edit().putString("mycard", b64(mine.toBytes())).putBoolean("main", false).remove("linkwait");
            for (int i = 0; i < total; i++) {
                e.remove("linkpart_" + i);
            }
            e.commit();
            while (in.remaining() > 0) {
                long uid = in.getLong();
                byte[] theirs = new byte[in.getShort() & 0xFFFF];
                in.get(theirs);
                storeContactCard(account, uid, Card.parse(theirs));
                p.edit().putInt("state_" + uid, ON).putInt("sentv_" + uid, 0).commit(); // our first message carries the card with this device
            }
            return account;
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        }
    }

    // ================================================================ handshake text helpers

    public static boolean isHandshake(String text) {
        if (text == null) {
            return false;
        }
        if (text.contains("\n" + REQ) || text.contains("\n" + ACK) || text.endsWith("\n" + OFF)
                || text.contains("\n" + LINK) || text.contains("\n" + LINKOK)) {
            return true;
        }
        for (String t : OLD_HIDDEN) {
            if (text.contains("\n" + t)) {
                return true;
            }
        }
        for (String t : OLD_OFF) {
            if (text.endsWith("\n" + t)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOffMessage(String text) {
        return text != null && text.endsWith("\n" + OFF);
    }

    public static boolean isLockedText(String text) {
        return text != null && text.startsWith(HEADER);
    }

    public static String handshakeValue(String text, String token) {
        if (text == null) {
            return null;
        }
        int i = text.indexOf("\n" + token);
        if (i < 0) {
            return null;
        }
        String v = text.substring(i + 1 + token.length()).trim();
        int nl = v.indexOf('\n');
        return nl >= 0 ? v.substring(0, nl) : v;
    }

    // ================================================================ sending

    public static synchronized String encrypt(int account, long userId, String plain) {
        try {
            SharedPreferences p = prefs(account);
            Card mine = myCard(account), theirs = loadCard(p, "card_" + userId);
            if (mine == null || theirs == null) {
                return null;
            }
            byte[] myDev = deviceId(account);
            byte[] mySig = fp(mine.sigPoint);

            // content: optional updated card (so they learn our new devices) + the text
            boolean sendCard = p.getInt("sentv_" + userId, 0) < mine.version;
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            if (sendCard) {
                byte[] cb = mine.toBytes();
                content.write(1);
                content.write(ByteBuffer.allocate(2).putShort((short) cb.length).array());
                content.write(cb);
            } else {
                content.write(0);
            }
            content.write(plain.getBytes(StandardCharsets.UTF_8));

            // every device that should read it: theirs + our other devices
            ArrayList<byte[][]> targets = new ArrayList<>(theirs.devices);
            for (byte[][] d : mine.devices) {
                if (!Arrays.equals(fp(d[0]), myDev)) {
                    targets.add(d);
                }
            }
            if (targets.size() > 20) {
                return null;
            }

            ByteBuffer hb = ByteBuffer.allocate(1 + 8 + 8 + 1);
            hb.put(VERSION).put(mySig).put(myDev).put((byte) targets.size());
            byte[] outer = hb.array();

            byte[] key = new byte[32];
            random.nextBytes(key);
            byte[] body = gcm(true, key, null, content.toByteArray(), outer);
            byte[] bodyHash = MessageDigest.getInstance("SHA-256").digest(body);

            ByteArrayOutputStream entries = new ByteArrayOutputStream();
            for (byte[][] d : targets) {
                byte[] peerDev = fp(d[0]);
                String sid = sid(myDev, peerDev);
                ArrayList<Session> list = loadSessions(sid);
                Session s;
                if (list.isEmpty()) {
                    s = startSession(account, d[0], d[1]);
                    list.add(s);
                } else {
                    s = list.get(0);
                }
                byte[][] k = kdfChain(s.cks);
                s.cks = k[1];
                ByteArrayOutputStream eh = new ByteArrayOutputStream();
                eh.write(peerDev);
                eh.write(s.pendingEk != null ? 1 : 0);
                if (s.pendingEk != null) {
                    eh.write(s.pendingEk);
                }
                eh.write(s.dhsPub);
                eh.write(ByteBuffer.allocate(8).putInt(s.pn).putInt(s.ns).array());
                s.ns++;
                byte[] entryHeader = eh.toByteArray();
                byte[] mk = hkdf(null, k[0], INFO_KEY, 44);
                byte[] sealedKey = gcm(true, mk, Arrays.copyOfRange(mk, 32, 44), key, concat(concat(outer, entryHeader), bodyHash));
                entries.write(entryHeader);
                entries.write(sealedKey);
                saveSessions(sid, list);
            }
            String wire = HEADER + TOKEN + b64(concat(concat(outer, entries.toByteArray()), body));
            if (sendCard) {
                p.edit().putInt("sentv_" + userId, mine.version).apply();
            }
            cachePut("o:", wire, plain);
            return wire;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ================================================================ receiving

    public static String decrypt(String text) {
        if (!isLockedText(text)) {
            return null;
        }
        String body = text.substring(HEADER.length()).trim();
        if (!body.startsWith(TOKEN)) {
            return null;
        }
        String cached = cacheGet("i:", text);
        if (cached != null) {
            return cached;
        }
        synchronized (VibeLock.class) {
            cached = cacheGet("i:", text);
            if (cached != null) {
                return cached;
            }
            String opened = openMessage(body.substring(TOKEN.length()), text);
            return opened != null ? opened : cacheGet("o:", text);
        }
    }

    private static class Entry {
        int account;
        byte[] header, sealedKey, ek, dhPub;
        int pn, n;
    }

    private static String openMessage(String b64body, String wire) {
        try {
            ByteBuffer in = ByteBuffer.wrap(unb64(b64body));
            if (in.get() != VERSION) {
                return null;
            }
            byte[] senderSig = new byte[8], senderDev = new byte[8];
            in.get(senderSig);
            in.get(senderDev);
            int count = in.get() & 0xFF;
            byte[] all = in.array();
            byte[] outer = Arrays.copyOfRange(all, 0, 18);

            // entries for devices on this phone (normally one; more if several accounts here are recipients)
            ArrayList<Entry> local = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int start = in.position();
                byte[] recip = new byte[8];
                in.get(recip);
                int flag = in.get();
                byte[] e = null;
                if ((flag & 1) != 0) {
                    e = new byte[POINT];
                    in.get(e);
                }
                byte[] dhp = new byte[POINT];
                in.get(dhp);
                int epn = in.getInt(), en = in.getInt();
                byte[] header = Arrays.copyOfRange(all, start, in.position());
                byte[] sk = new byte[48];
                in.get(sk);
                String acc = globalPrefs().getString("dev_" + Utilities.bytesToHex(recip), null);
                if (acc != null) {
                    Entry x = new Entry();
                    x.account = Integer.parseInt(acc);
                    x.header = header;
                    x.sealedKey = sk;
                    x.ek = e;
                    x.dhPub = dhp;
                    x.pn = epn;
                    x.n = en;
                    local.add(x);
                }
            }
            if (local.isEmpty()) {
                return null;
            }
            byte[] body = Arrays.copyOfRange(all, in.position(), all.length);
            byte[] bodyHash = MessageDigest.getInstance("SHA-256").digest(body);
            String result = null;
            for (Entry x : local) {
                String plain = openEntry(x, outer, body, bodyHash, senderSig, senderDev);
                if (plain != null && result == null) {
                    result = plain;
                }
            }
            if (result != null) {
                cachePut("i:", wire, result);
            }
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String openEntry(Entry x, byte[] outer, byte[] body, byte[] bodyHash, byte[] senderSig, byte[] senderDev) {
        try {
            int account = x.account;
            SharedPreferences p = prefs(account);
            Card mine = myCard(account);
            if (mine == null) {
                return null;
            }
            // who sent it, and the sender device's identity key
            String senderHex = Utilities.bytesToHex(senderSig);
            long userId;
            Card senderCard;
            if (senderHex.equals(mine.owner())) {
                userId = 0; // one of our own devices
                senderCard = mine;
            } else {
                String who = p.getString("who_" + senderHex, null);
                if (who == null) {
                    return null;
                }
                userId = Long.parseLong(who);
                senderCard = loadCard(p, "card_" + userId);
            }
            byte[][] senderDevice = senderCard != null ? senderCard.device(senderDev) : null;
            if (senderDevice == null) {
                return null;
            }

            String sid = sid(deviceId(account), senderDev);
            ArrayList<Session> list = loadSessions(sid);
            ArrayList<Session> candidates = new ArrayList<>();
            if (x.ek != null) {
                boolean known = false;
                for (Session s : list) {
                    if (s.theirEk != null && Arrays.equals(s.theirEk, x.ek)) {
                        known = true;
                    }
                }
                if (!known) {
                    candidates.add(answerSession(account, senderDevice[0], x.ek));
                }
            }
            candidates.addAll(list);

            for (Session original : candidates) {
                Session s = Session.fromJson(original.toJson()); // work on a copy; keep the original if this fails
                try {
                    byte[] mkRaw;
                    String skippedId = b64(x.dhPub) + ":" + x.n;
                    if (s.skipped.has(skippedId)) {
                        mkRaw = unb64(s.skipped.getString(skippedId));
                        s.skipped.remove(skippedId);
                    } else {
                        if (s.dhr == null || !Arrays.equals(x.dhPub, s.dhr)) {
                            skipKeys(s, x.pn);
                            dhRatchet(s, x.dhPub);
                        }
                        skipKeys(s, x.n);
                        byte[][] k = kdfChain(s.ckr);
                        s.ckr = k[1];
                        s.nr++;
                        mkRaw = k[0];
                    }
                    byte[] mk = hkdf(null, mkRaw, INFO_KEY, 44);
                    byte[] key = gcm(false, mk, Arrays.copyOfRange(mk, 32, 44), x.sealedKey, concat(concat(outer, x.header), bodyHash));
                    byte[] content = gcm(false, key, null, body, outer);
                    s.pendingEk = null; // they answered: no need to send our start keys any more

                    ArrayList<Session> updated = new ArrayList<>();
                    updated.add(s);
                    for (Session other : list) {
                        if (other != original) {
                            updated.add(other);
                        }
                    }
                    saveSessions(sid, updated);

                    ByteBuffer cb = ByteBuffer.wrap(content);
                    int type = cb.get();
                    if (type == 1) {
                        byte[] cardBytes = new byte[cb.getShort() & 0xFFFF];
                        cb.get(cardBytes);
                        applyCardUpdate(account, userId, senderCard, cardBytes);
                    }
                    byte[] textBytes = new byte[cb.remaining()];
                    cb.get(textBytes);
                    return new String(textBytes, StandardCharsets.UTF_8);
                } catch (Throwable ignore) {
                    // try the next session
                }
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** A newer card from the sender: same identity, higher version, valid signature. */
    private static void applyCardUpdate(int account, long userId, Card current, byte[] cardBytes) {
        try {
            Card c = Card.parse(cardBytes);
            if (!Arrays.equals(c.sigPoint, current.sigPoint) || c.version <= current.version) {
                return;
            }
            if (userId == 0) {
                prefs(account).edit().putString("mycard", b64(c.toBytes())).commit();
                if (c.device(deviceId(account)) == null) {
                    // this device was removed from the account
                    prefs(account).edit().remove("mycard").commit();
                }
            } else {
                storeContactCard(account, userId, c);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Called for every message read from the network or the local database. */
    public static void onDeserialized(TLRPC.Message m) {
        if (m == null || m.message == null) {
            return;
        }
        if (isLockedText(m.message)) {
            String plain = decrypt(m.message);
            if (plain != null) {
                m.message = plain;
                if (m.entities != null && !m.entities.isEmpty()) {
                    m.entities = new ArrayList<>();
                }
            }
        } else if (m.message.contains("\n" + ACK)) {
            handleAck(m.message);
        } else if (m.message.contains("\n" + LINKOK)) {
            handleLinkApproval(m.message);
        }
    }

    /** Placeholder sent instead if a message can't be locked: a locked chat never sends plain text. */
    private static final String NOT_LOCKED = HEADER + "(this message could not be locked and was not sent)";

    /**
     * How many characters fit in one locked message for this chat. Every device that reads it adds its own
     * sealed key, so the room shrinks a little per device (worst case: 3-byte characters, start keys, card).
     */
    public static int maxChars(int account, long userId) {
        try {
            Card mine = myCard(account), theirs = loadCard(prefs(account), "card_" + userId);
            int devices = (theirs != null ? theirs.devices.size() : 1) + (mine != null ? Math.max(0, mine.devices.size() - 1) : 0);
            int cardBytes = mine != null && prefs(account).getInt("sentv_" + userId, 0) < mine.version ? 3 + mine.toBytes().length : 0;
            int bytes = (4096 - HEADER.length() - TOKEN.length() - 8) * 3 / 4 - 18 - devices * 195 - 29 - cardBytes;
            return Math.max(100, bytes / 3);
        } catch (Throwable e) {
            return 500;
        }
    }

    /** Locks an outgoing text message when this chat has Vibe lock on. */
    public static void lockRequest(int account, long dialogId, TLRPC.TL_messages_sendMessage req) {
        if (req == null || !isOn(account, dialogId) || TextUtils.isEmpty(req.message) || isHandshake(req.message) || isLockedText(req.message)) {
            return;
        }
        String enc = encrypt(account, dialogId, req.message);
        if (enc == null || enc.length() > 4096) {
            enc = NOT_LOCKED;
        }
        {
            req.message = enc;
            req.entities = new ArrayList<>();
            req.flags &= ~8;
            req.no_webpage = true;
            if (req.reply_to != null && req.reply_to.quote_text != null) {
                req.reply_to.quote_text = null;
                req.reply_to.quote_entities = new ArrayList<>();
                req.reply_to.quote_offset = 0;
                req.reply_to.flags &= ~(4 | 8 | 16);
            }
        }
    }

    /** Locks an edited text message when this chat has Vibe lock on. */
    public static void lockEdit(int account, long dialogId, TLRPC.TL_messages_editMessage req) {
        if (req == null || (req.flags & 2048) == 0 || !isOn(account, dialogId) || TextUtils.isEmpty(req.message) || isHandshake(req.message) || isLockedText(req.message)) {
            return;
        }
        String enc = encrypt(account, dialogId, req.message);
        if (enc == null || enc.length() > 4096) {
            enc = NOT_LOCKED;
        }
        req.message = enc;
        req.entities = new ArrayList<>();
        req.flags &= ~8;
        req.no_webpage = true;
    }

    // ================================================================ opened-message cache

    private static class CacheDb extends SQLiteOpenHelper {
        CacheDb(Context c) {
            super(c, "vibelock_cache.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS opened (h TEXT PRIMARY KEY, v BLOB)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int o, int n) {
        }
    }

    private static volatile CacheDb cacheDb;
    private static final Map<String, byte[]> memoryCache = new ConcurrentHashMap<>();
    private static volatile boolean noDb;

    private static CacheDb db() {
        if (cacheDb == null && !noDb) {
            synchronized (VibeLock.class) {
                if (cacheDb == null && !noDb) {
                    try {
                        cacheDb = new CacheDb(ApplicationLoader.applicationContext);
                    } catch (Throwable e) {
                        noDb = true;
                    }
                }
            }
        }
        return cacheDb;
    }

    private static String cacheId(String kind, String wire) throws Exception {
        return kind + Utilities.bytesToHex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(wire.getBytes(StandardCharsets.UTF_8)), 16));
    }

    private static void cachePut(String kind, String wire, String plain) {
        try {
            String h = cacheId(kind, wire);
            byte[] v = seal(plain.getBytes(StandardCharsets.UTF_8));
            CacheDb d = db();
            if (d != null) {
                ContentValues cv = new ContentValues();
                cv.put("h", h);
                cv.put("v", v);
                d.getWritableDatabase().insertWithOnConflict("opened", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            } else {
                memoryCache.put(h, v);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String cacheGet(String kind, String wire) {
        try {
            String h = cacheId(kind, wire);
            byte[] v = null;
            CacheDb d = db();
            if (d != null) {
                Cursor c = d.getReadableDatabase().rawQuery("SELECT v FROM opened WHERE h = ?", new String[]{h});
                try {
                    if (c.moveToFirst()) {
                        v = c.getBlob(0);
                    }
                } finally {
                    c.close();
                }
            } else {
                v = memoryCache.get(h);
            }
            return v == null ? null : new String(open(v), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================ helpers

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return a.length - b.length;
    }
}
