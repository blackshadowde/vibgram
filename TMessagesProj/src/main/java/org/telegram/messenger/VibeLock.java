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

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

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
 * Vibgram "Vibe lock": optional end-to-end locking of a normal one-to-one chat between two Vibgram apps.
 *
 * Works like Signal / SimpleX:
 * - Start (X3DH-style): the request carries the requester's identity key and a one-time key; the accept carries
 *   the other side's identity key and first ratchet key. The starting secret mixes identity-identity and
 *   identity-one-time ECDH (P-256), through HKDF-SHA256.
 * - Double Ratchet: every message has its own key (symmetric ratchet, HMAC-SHA256) and a new ECDH ratchet step
 *   happens every time the conversation changes direction. Used keys are deleted, so a key taken from the phone
 *   today can't open earlier messages (forward secrecy) and the chat heals after a new ECDH step.
 * - Messages: AES-256-GCM with the message header as associated data. Out-of-order and lost messages are handled
 *   with stored skipped keys (up to 1000 per jump).
 * - Identity keys live in the Android Keystore (Android 12+). Ratchet state, skipped keys and the cache of opened
 *   messages are encrypted with a storage key that is itself wrapped by a Keystore AES-256-GCM master key.
 * - On the wire a locked message is a normal Telegram text that starts with "🔒 Vibe lock message", so other apps
 *   show what it is. Nothing is locked unless both Vibgram apps agreed.
 */
public class VibeLock {

    public static final String HEADER = "🔒 Vibe lock message\n";
    private static final String TOKEN_V1 = "VL1:";
    private static final String TOKEN = "VL2:";
    public static final String REQ = "VL2REQ:";
    public static final String ACK = "VL2ACK:";
    public static final String OFF = "VL2OFF";
    private static final String[] OLD_TOKENS = {"VL1REQ:", "VL1ACK:"};
    private static final String OLD_OFF = "VL1OFF";

    public static final int NONE = 0, REQUESTED = 1, INCOMING = 2, ON = 3;

    private static final int MAX_SKIP = 1000;
    private static final int MAX_STORED_SKIPPED = 2000;
    private static final byte VERSION = 2;

    private static final byte[] INFO_V1 = "vibe-lock-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_ROOT = "vibe-lock-v2-root".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_RATCHET = "vibe-lock-v2-ratchet".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_MSG = "vibe-lock-v2-message".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom random = new SecureRandom();

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_ALIAS = "vibelock_master";

    private static volatile SecretKey master;
    private static volatile byte[] storageKey;
    /** v1 only: fingerprint pair -> AES key, so messages from the first test build stay readable. */
    private static volatile Map<String, byte[]> index;
    private static final KeyPair[] identities = new KeyPair[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] inKeystore = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /** Vibe lock needs the Android Keystore (Android 6+). */
    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 23;
    }

    /** Identity keys live inside the Keystore from Android 12 (key agreement support). */
    private static boolean keystoreIdentity() {
        return Build.VERSION.SDK_INT >= 31;
    }

    // ================================================================ storage

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock" + account, Context.MODE_PRIVATE);
    }

    private static SharedPreferences globalPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock_global", Context.MODE_PRIVATE);
    }

    private static SharedPreferences statePrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock_state", Context.MODE_PRIVATE);
    }

    private static SharedPreferences indexPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock_index", Context.MODE_PRIVATE);
    }

    public static int state(int account, long userId) {
        return prefs(account).getInt("state_" + userId, NONE);
    }

    public static void setState(int account, long userId, int state) {
        prefs(account).edit().putInt("state_" + userId, state).apply();
    }

    public static boolean isOn(int account, long dialogId) {
        if (dialogId <= 0 || !supported() || state(account, dialogId) != ON) {
            return false;
        }
        String sid = prefs(account).getString("sid_" + dialogId, null);
        return sid != null && statePrefs().contains(sid);
    }

    public static String peerKey(int account, long userId) {
        return prefs(account).getString("peer_" + userId, null);
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

    // ================================================================ keystore

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

    /** Encrypts a small secret with the Keystore master key (the Keystore picks the IV). */
    private static String wrap(byte[] secret) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, master());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(secret);
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static byte[] unwrap(String wrapped) throws Exception {
        byte[] in = Base64.decode(wrapped, Base64.NO_WRAP);
        int ivLen = in[0] & 0xFF;
        byte[] iv = new byte[ivLen];
        System.arraycopy(in, 1, iv, 0, ivLen);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, master(), new GCMParameterSpec(128, iv));
        return c.doFinal(in, 1 + ivLen, in.length - 1 - ivLen);
    }

    /**
     * A random key for the ratchet state and the opened-message cache, kept wrapped by the Keystore master key.
     * Using it in software keeps every message from needing a slow Keystore call.
     */
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
                k = unwrap(w);
            } else {
                k = new byte[32];
                random.nextBytes(k);
                g.edit().putString("sk_w", wrap(k)).commit();
            }
            storageKey = k;
            return k;
        }
    }

    private static byte[] seal(byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(storageKey(), "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    private static byte[] open(byte[] sealed) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(storageKey(), "AES"), new GCMParameterSpec(128, sealed, 0, 12));
        return c.doFinal(sealed, 12, sealed.length - 12);
    }

    // ================================================================ identity

    private static synchronized KeyPair identity(int account) throws Exception {
        if (identities[account] != null) {
            return identities[account];
        }
        SharedPreferences p = prefs(account);
        KeyFactory kf = KeyFactory.getInstance("EC");

        // keys saved in the clear by the first test build: keep them but wrap them
        String oldPriv = p.getString("my_priv", null);
        if (oldPriv != null) {
            p.edit().putString("sw_priv_w", wrap(Base64.decode(oldPriv, Base64.NO_WRAP))).remove("my_priv").commit();
        }

        KeyPair kp;
        String swPriv = p.getString("sw_priv_w", null);
        String swPub = p.getString("my_pub", null);
        if (swPriv != null && swPub != null) {
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(unwrap(swPriv)));
            PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(swPub, Base64.NO_WRAP)));
            kp = new KeyPair(pu, pk);
        } else if (keystoreIdentity()) {
            // the private key is created inside the Keystore and can never be read out
            String alias = "vibelock_id_" + account;
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(alias)) {
                KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
                g.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .build());
                g.generateKeyPair();
            }
            PrivateKey pk = (PrivateKey) ks.getKey(alias, null);
            PublicKey pu = ks.getCertificate(alias).getPublicKey();
            kp = new KeyPair(pu, pk);
            inKeystore[account] = true;
        } else {
            // Android 6 to 11: software key, stored encrypted with the Keystore master key
            kp = newKeyPair();
            p.edit()
                    .putString("sw_priv_w", wrap(kp.getPrivate().getEncoded()))
                    .putString("my_pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .commit();
        }
        identities[account] = kp;
        return kp;
    }

    public static String myPublicKey(int account) {
        try {
            return b64(identity(account).getPublic().getEncoded());
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** ECDH with this account's identity key (inside the Keystore when it lives there). */
    private static byte[] dhIdentity(int account, PublicKey peer) throws Exception {
        KeyPair me = identity(account);
        KeyAgreement ka = inKeystore[account] ? KeyAgreement.getInstance("ECDH", KEYSTORE) : KeyAgreement.getInstance("ECDH");
        ka.init(me.getPrivate());
        ka.doPhase(peer, true);
        return ka.generateSecret();
    }

    private static byte[] dh(PrivateKey mine, PublicKey peer) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(peer, true);
        return ka.generateSecret();
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), random);
        return g.generateKeyPair();
    }

    private static PublicKey publicKey(byte[] x509) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(x509));
    }

    private static PrivateKey privateKey(byte[] pkcs8) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private static byte[] fingerprint(byte[] pubEncoded) throws Exception {
        return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(pubEncoded), 8);
    }

    /** Short code both people can compare, e.g. "4F2A 91C0 7B3E". */
    public static String safetyCode(int account, long userId) {
        try {
            String peer = peerKey(account, userId);
            if (peer == null) {
                return null;
            }
            byte[] a = identity(account).getPublic().getEncoded();
            byte[] b = unb64(peer);
            byte[] first = compare(a, b) <= 0 ? a : b;
            byte[] second = first == a ? b : a;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(first);
            md.update(second);
            byte[] h = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0 && i % 2 == 0) {
                    sb.append(' ');
                }
                sb.append(String.format("%02X", h[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
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

    /** Root KDF: new root key + chain key from the old root key and a fresh ECDH output. */
    private static byte[][] kdfRoot(byte[] rk, byte[] dhOut) throws Exception {
        byte[] o = hkdf(rk, dhOut, INFO_RATCHET, 64);
        return new byte[][]{Arrays.copyOfRange(o, 0, 32), Arrays.copyOfRange(o, 32, 64)};
    }

    /** Chain KDF: message key + next chain key. */
    private static byte[][] kdfChain(byte[] ck) throws Exception {
        return new byte[][]{hmac(ck, new byte[]{1}), hmac(ck, new byte[]{2})};
    }

    // ================================================================ ratchet state

    private static class Ratchet {
        byte[] myFp, peerFp;
        byte[] rk, cks, ckr;
        PrivateKey dhsPriv;
        byte[] dhsPub;   // X.509
        byte[] dhr;      // X.509, null until the first message from the peer
        int ns, nr, pn;
        JSONObject skipped = new JSONObject(); // "b64(dh):n" -> b64(mk), oldest first

        String id() {
            return Utilities.bytesToHex(myFp) + ":" + Utilities.bytesToHex(peerFp);
        }

        byte[] toBytes() throws Exception {
            JSONObject j = new JSONObject();
            j.put("myFp", b64(myFp));
            j.put("peerFp", b64(peerFp));
            j.put("rk", b64(rk));
            if (cks != null) j.put("cks", b64(cks));
            if (ckr != null) j.put("ckr", b64(ckr));
            j.put("dhsPriv", b64(dhsPriv.getEncoded()));
            j.put("dhsPub", b64(dhsPub));
            if (dhr != null) j.put("dhr", b64(dhr));
            j.put("ns", ns);
            j.put("nr", nr);
            j.put("pn", pn);
            j.put("skipped", skipped);
            return j.toString().getBytes(StandardCharsets.UTF_8);
        }

        static Ratchet fromBytes(byte[] b) throws Exception {
            JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
            Ratchet r = new Ratchet();
            r.myFp = unb64(j.getString("myFp"));
            r.peerFp = unb64(j.getString("peerFp"));
            r.rk = unb64(j.getString("rk"));
            r.cks = j.has("cks") ? unb64(j.getString("cks")) : null;
            r.ckr = j.has("ckr") ? unb64(j.getString("ckr")) : null;
            r.dhsPriv = privateKey(unb64(j.getString("dhsPriv")));
            r.dhsPub = unb64(j.getString("dhsPub"));
            r.dhr = j.has("dhr") ? unb64(j.getString("dhr")) : null;
            r.ns = j.getInt("ns");
            r.nr = j.getInt("nr");
            r.pn = j.getInt("pn");
            r.skipped = j.optJSONObject("skipped");
            if (r.skipped == null) {
                r.skipped = new JSONObject();
            }
            return r;
        }
    }

    private static Ratchet loadRatchet(String sid) {
        try {
            String s = statePrefs().getString(sid, null);
            return s == null ? null : Ratchet.fromBytes(open(unb64(s)));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void saveRatchet(Ratchet r) throws Exception {
        statePrefs().edit().putString(r.id(), b64(seal(r.toBytes()))).commit();
    }

    /** DH ratchet step when the peer's ratchet key changes. */
    private static void dhRatchet(Ratchet r, byte[] peerDh) throws Exception {
        r.pn = r.ns;
        r.ns = 0;
        r.nr = 0;
        r.dhr = peerDh;
        PublicKey peer = publicKey(peerDh);
        byte[][] a = kdfRoot(r.rk, dh(r.dhsPriv, peer));
        r.rk = a[0];
        r.ckr = a[1];
        KeyPair next = newKeyPair();
        r.dhsPriv = next.getPrivate();
        r.dhsPub = next.getPublic().getEncoded();
        byte[][] b = kdfRoot(r.rk, dh(r.dhsPriv, peer));
        r.rk = b[0];
        r.cks = b[1];
    }

    private static void skipKeys(Ratchet r, int until) throws Exception {
        if (r.ckr == null) {
            return;
        }
        if (until - r.nr > MAX_SKIP) {
            throw new IllegalStateException("too many skipped messages");
        }
        while (r.nr < until) {
            byte[][] k = kdfChain(r.ckr);
            r.ckr = k[1];
            r.skipped.put(b64(r.dhr) + ":" + r.nr, b64(k[0]));
            r.nr++;
        }
        while (r.skipped.length() > MAX_STORED_SKIPPED) {
            Iterator<String> it = r.skipped.keys();
            if (!it.hasNext()) {
                break;
            }
            r.skipped.remove(it.next());
        }
    }

    // ================================================================ start (request / accept)

    /** Builds the request text and remembers the one-time key until the answer comes. */
    public static synchronized String makeRequest(int account, long userId) {
        try {
            KeyPair ek = newKeyPair();
            byte[] ekPub = ek.getPublic().getEncoded();
            String ekFp = Utilities.bytesToHex(fingerprint(ekPub));
            SharedPreferences p = prefs(account);
            String oldFp = p.getString("ekfp_" + userId, null);
            SharedPreferences.Editor g = globalPrefs().edit();
            if (oldFp != null) {
                g.remove("pend_" + oldFp);
            }
            g.putString("pend_" + ekFp, account + ":" + userId + ":" + b64(seal(ek.getPrivate().getEncoded())) + ":" + b64(ekPub)).commit();
            p.edit().putString("ekfp_" + userId, ekFp).putInt("state_" + userId, REQUESTED).commit();
            return "🔒 Vibe lock request\n"
                    + "I'd like to lock this chat so only Vibgram on our two phones can read it. Open the chat in Vibgram to accept.\n"
                    + REQ + myPublicKey(account) + "." + b64(ekPub);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void cancelRequest(int account, long userId) {
        SharedPreferences p = prefs(account);
        String fp = p.getString("ekfp_" + userId, null);
        if (fp != null) {
            globalPrefs().edit().remove("pend_" + fp).apply();
        }
        p.edit().remove("ekfp_" + userId).putInt("state_" + userId, NONE).apply();
    }

    /** Someone asked us: keep their keys until we decide. */
    public static void rememberRequest(int account, long userId, String reqValue) {
        prefs(account).edit().putString("req_" + userId, reqValue).putInt("state_" + userId, INCOMING).apply();
    }

    public static String pendingRequestValue(int account, long userId) {
        return prefs(account).getString("req_" + userId, null);
    }

    /** Accepts a request: starts our side of the ratchet. Returns the answer text to send, or null. */
    public static synchronized String accept(int account, long userId) {
        try {
            String value = pendingRequestValue(account, userId);
            if (value == null) {
                return null;
            }
            String[] parts = value.split("\\.");
            byte[] ikA = unb64(parts[0]);
            byte[] ekA = unb64(parts[1]);
            PublicKey ikAKey = publicKey(ikA);
            PublicKey ekAKey = publicKey(ekA);

            byte[] myIk = identity(account).getPublic().getEncoded();
            byte[] sk = hkdf(null, concat(dhIdentity(account, ikAKey), dhIdentity(account, ekAKey)), INFO_ROOT, 32);

            Ratchet r = new Ratchet();
            r.myFp = fingerprint(myIk);
            r.peerFp = fingerprint(ikA);
            KeyPair dhs = newKeyPair();
            r.dhsPriv = dhs.getPrivate();
            r.dhsPub = dhs.getPublic().getEncoded();
            r.dhr = ekA;
            byte[][] a = kdfRoot(sk, dh(r.dhsPriv, ekAKey));
            r.rk = a[0];
            r.cks = a[1];
            saveRatchet(r);

            // we may have asked too: that request is replaced by this answer
            cancelPendingOnly(account, userId);
            prefs(account).edit()
                    .putString("peer_" + userId, parts[0])
                    .putString("sid_" + userId, r.id())
                    .remove("req_" + userId)
                    .putInt("state_" + userId, ON)
                    .commit();
            return "🔒 Vibe lock is on\n"
                    + "Messages in this chat are now locked between our Vibgram apps.\n"
                    + ACK + Utilities.bytesToHex(fingerprint(ekA)) + "." + b64(myIk) + "." + b64(r.dhsPub);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void cancelPendingOnly(int account, long userId) {
        SharedPreferences p = prefs(account);
        String fp = p.getString("ekfp_" + userId, null);
        if (fp != null) {
            globalPrefs().edit().remove("pend_" + fp).apply();
            p.edit().remove("ekfp_" + userId).apply();
        }
    }

    /**
     * The answer to our request: finishes the start on our side. Runs as soon as the answer is read
     * (from the network or the database), so we never miss the first locked messages.
     * Returns the user id it was for, or 0.
     */
    public static synchronized long handleAck(String text) {
        try {
            String v = handshakeValue(text, ACK);
            if (v == null) {
                return 0;
            }
            String[] parts = v.split("\\.");
            if (parts.length < 3) {
                return 0;
            }
            String pend = globalPrefs().getString("pend_" + parts[0], null);
            if (pend == null) {
                return 0; // not ours, or already done
            }
            String[] pp = pend.split(":");
            int account = Integer.parseInt(pp[0]);
            long userId = Long.parseLong(pp[1]);
            PrivateKey ekPriv = privateKey(open(unb64(pp[2])));
            byte[] ekPub = unb64(pp[3]);

            byte[] ikB = unb64(parts[1]);
            byte[] dhB = unb64(parts[2]);
            PublicKey ikBKey = publicKey(ikB);
            byte[] sk = hkdf(null, concat(dhIdentity(account, ikBKey), dh(ekPriv, ikBKey)), INFO_ROOT, 32);

            Ratchet r = new Ratchet();
            r.myFp = fingerprint(identity(account).getPublic().getEncoded());
            r.peerFp = fingerprint(ikB);
            r.rk = sk;
            r.dhsPriv = ekPriv;
            r.dhsPub = ekPub;
            dhRatchet(r, dhB);
            r.pn = 0;
            saveRatchet(r);

            globalPrefs().edit().remove("pend_" + parts[0]).commit();
            prefs(account).edit()
                    .putString("peer_" + userId, parts[1])
                    .putString("sid_" + userId, r.id())
                    .remove("ekfp_" + userId)
                    .putInt("state_" + userId, ON)
                    .commit();
            return userId;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Turns the lock off here and deletes the ratchet keys. Messages already opened stay readable. */
    public static synchronized void turnOff(int account, long userId) {
        SharedPreferences p = prefs(account);
        String sid = p.getString("sid_" + userId, null);
        if (sid != null) {
            statePrefs().edit().remove(sid).commit();
        }
        cancelPendingOnly(account, userId);
        p.edit().remove("sid_" + userId).remove("req_" + userId).putInt("state_" + userId, NONE).commit();
    }

    /** Which side answers when both asked at the same time: the bigger identity fingerprint answers. */
    public static boolean shouldAnswerCrossedRequest(int account, String theirReqValue) {
        try {
            byte[] mine = fingerprint(identity(account).getPublic().getEncoded());
            byte[] theirs = fingerprint(unb64(theirReqValue.split("\\.")[0]));
            return compare(mine, theirs) > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    // ================================================================ messages

    public static boolean isHandshake(String text) {
        if (text == null) {
            return false;
        }
        if (text.contains("\n" + REQ) || text.contains("\n" + ACK) || text.endsWith("\n" + OFF) || text.endsWith("\n" + OLD_OFF)) {
            return true;
        }
        for (String t : OLD_TOKENS) {
            if (text.contains("\n" + t)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOffMessage(String text) {
        return text != null && (text.endsWith("\n" + OFF) || text.endsWith("\n" + OLD_OFF));
    }

    public static boolean isLockedText(String text) {
        return text != null && text.startsWith(HEADER);
    }

    /** Value after a handshake token, or null. */
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

    /** Locks a message for this chat with the next message key. */
    public static synchronized String encrypt(int account, long userId, String plain) {
        try {
            String sid = prefs(account).getString("sid_" + userId, null);
            Ratchet r = sid != null ? loadRatchet(sid) : null;
            if (r == null || r.cks == null) {
                return null;
            }
            byte[][] k = kdfChain(r.cks);
            r.cks = k[1];
            byte[] header = header(r.myFp, r.peerFp, r.dhsPub, r.pn, r.ns);
            r.ns++;
            byte[] ct = aead(true, k[0], plain.getBytes(StandardCharsets.UTF_8), header);
            Arrays.fill(k[0], (byte) 0);
            String wire = HEADER + TOKEN + b64(concat(header, ct));
            saveRatchet(r);
            cachePut("o:", wire, plain); // so the sender can show its own message again if it is downloaded later
            return wire;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] header(byte[] senderFp, byte[] recipFp, byte[] dh, int pn, int n) {
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 8 + 1 + dh.length + 4 + 4);
        b.put(VERSION).put(senderFp).put(recipFp).put((byte) dh.length).put(dh).putInt(pn).putInt(n);
        return b.array();
    }

    private static byte[] aead(boolean encrypt, byte[] mk, byte[] data, byte[] ad) throws Exception {
        byte[] keys = hkdf(null, mk, INFO_MSG, 32 + 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(keys, 0, 32, "AES"), new GCMParameterSpec(128, keys, 32, 12));
        c.updateAAD(ad);
        return c.doFinal(data);
    }

    /** Plain text of a locked message, or null if this phone can't open it. */
    public static String decrypt(String text) {
        if (!isLockedText(text)) {
            return null;
        }
        String body = text.substring(HEADER.length()).trim();
        if (body.startsWith(TOKEN_V1)) {
            return decryptV1(body.substring(TOKEN_V1.length()));
        }
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
            String opened = openWithRatchet(body, text);
            if (opened != null) {
                return opened;
            }
            // not a message to us: maybe one we sent
            return cacheGet("o:", text);
        }
    }

    private static String openWithRatchet(String body, String text) {
        {
            try {
                ByteBuffer in = ByteBuffer.wrap(unb64(body.substring(TOKEN.length())));
                if (in.get() != VERSION) {
                    return null;
                }
                byte[] senderFp = new byte[8], recipFp = new byte[8];
                in.get(senderFp);
                in.get(recipFp);
                byte[] dhPub = new byte[in.get() & 0xFF];
                in.get(dhPub);
                int pn = in.getInt();
                int n = in.getInt();
                int headerLen = in.position();
                byte[] header = Arrays.copyOfRange(in.array(), 0, headerLen);
                byte[] ct = Arrays.copyOfRange(in.array(), headerLen, in.array().length);

                // our state for this pair (it is a message to us)
                String sid = Utilities.bytesToHex(recipFp) + ":" + Utilities.bytesToHex(senderFp);
                if (!statePrefs().contains(sid)) {
                    return null;
                }
                Ratchet r = loadRatchet(sid);
                if (r == null) {
                    return null;
                }
                byte[] mk;
                String skippedId = b64(dhPub) + ":" + n;
                if (r.skipped.has(skippedId)) {
                    mk = unb64(r.skipped.getString(skippedId));
                    r.skipped.remove(skippedId);
                } else {
                    if (r.dhr == null || !Arrays.equals(dhPub, r.dhr)) {
                        skipKeys(r, pn);
                        dhRatchet(r, dhPub);
                    }
                    skipKeys(r, n);
                    byte[][] k = kdfChain(r.ckr);
                    r.ckr = k[1];
                    r.nr++;
                    mk = k[0];
                }
                byte[] pt = aead(false, mk, ct, header); // throws if changed or not for us: state is not saved
                Arrays.fill(mk, (byte) 0);
                saveRatchet(r);
                String plain = new String(pt, StandardCharsets.UTF_8);
                cachePut("i:", text, plain);
                return plain;
            } catch (Throwable e) {
                return null;
            }
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
            final String text = m.message;
            Utilities.globalQueue.postRunnable(() -> handleAck(text));
        }
    }

    /** Locks an outgoing text message when this chat has Vibe lock on. */
    public static void lockRequest(int account, long dialogId, TLRPC.TL_messages_sendMessage req) {
        if (req == null || !isOn(account, dialogId) || TextUtils.isEmpty(req.message) || isHandshake(req.message) || isLockedText(req.message)) {
            return;
        }
        String enc = encrypt(account, dialogId, req.message);
        if (enc != null) {
            req.message = enc;
            req.entities = new ArrayList<>();
            req.flags &= ~8;
            req.no_webpage = true;
            if (req.reply_to != null && req.reply_to.quote_text != null) {
                // a quoted part of a locked message must not travel in the clear
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
        if (enc != null) {
            req.message = enc;
            req.entities = new ArrayList<>();
            req.flags &= ~8;
            req.no_webpage = true;
        }
    }

    // ================================================================ opened-message cache

    /**
     * Telegram keeps the locked text on its servers, and the app can download a message again (cleared cache,
     * scrolling far back). The ratchet deletes used keys, so opened messages are kept here, encrypted with the
     * storage key, by a hash of the locked text.
     */
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
                        noDb = true; // keep working in memory only
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

    // ================================================================ v1 (first test build): read only

    private static Map<String, byte[]> index() {
        Map<String, byte[]> m = index;
        if (m == null) {
            synchronized (VibeLock.class) {
                m = index;
                if (m == null) {
                    m = new ConcurrentHashMap<>();
                    SharedPreferences ip = indexPrefs();
                    SharedPreferences.Editor fix = null;
                    for (Map.Entry<String, ?> e : ip.getAll().entrySet()) {
                        if (!(e.getValue() instanceof String)) {
                            continue;
                        }
                        String v = (String) e.getValue();
                        try {
                            if (v.length() <= 44) {
                                byte[] k = unb64(v);
                                m.put(e.getKey(), k);
                                if (fix == null) {
                                    fix = ip.edit();
                                }
                                fix.putString(e.getKey(), wrap(k));
                            } else {
                                m.put(e.getKey(), unwrap(v));
                            }
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    }
                    if (fix != null) {
                        fix.apply();
                    }
                    index = m;
                }
            }
        }
        return m;
    }

    private static String pairId(byte[] fpA, byte[] fpB) {
        String a = Utilities.bytesToHex(fpA), b = Utilities.bytesToHex(fpB);
        return a.compareTo(b) <= 0 ? a + b : b + a;
    }

    private static String decryptV1(String b64body) {
        try {
            byte[] in = unb64(b64body);
            if (in.length < 28 + 16) {
                return null;
            }
            byte[] senderFp = Arrays.copyOfRange(in, 0, 8), recipFp = Arrays.copyOfRange(in, 8, 16), iv = Arrays.copyOfRange(in, 16, 28);
            byte[] key = index().get(pairId(senderFp, recipFp));
            if (key == null) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(senderFp);
            c.updateAAD(recipFp);
            return new String(c.doFinal(in, 28, in.length - 28), StandardCharsets.UTF_8);
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
