package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Vibgram "Vibe lock": optional end-to-end locking of a normal one-to-one chat between two Vibgram apps.
 *
 * - Only turns on when both people agree: one sends a request, the other's Vibgram accepts.
 * - Keys: an ECDH P-256 key pair per account on this phone; the shared key is HKDF-SHA256 of the ECDH secret.
 * - Messages: AES-256-GCM. On the wire a locked message is a normal Telegram text message that reads
 *   "🔒 Vibe lock message" followed by the scrambled text, so other apps show what it is.
 * - Telegram's servers only see the scrambled text. Decrypted text is kept on this phone like any other chat.
 * - Chats with people who don't use Vibgram never change: nothing is locked unless their Vibgram accepted.
 */
public class VibeLock {

    public static final String HEADER = "🔒 Vibe lock message\n";
    private static final String TOKEN = "VL1:";
    public static final String REQ = "VL1REQ:";
    public static final String ACK = "VL1ACK:";
    public static final String OFF = "VL1OFF";

    public static final int NONE = 0, REQUESTED = 1, INCOMING = 2, ON = 3;

    private static final byte[] INFO = "vibe-lock-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom random = new SecureRandom();

    /** fingerprint pair -> AES key, for every chat ever locked on this phone (all accounts). */
    private static volatile Map<String, byte[]> index;
    private static final KeyPair[] identities = new KeyPair[UserConfig.MAX_ACCOUNT_COUNT];

    // ---------------------------------------------------------------- storage

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock" + account, Context.MODE_PRIVATE);
    }

    private static SharedPreferences indexPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibelock_index", Context.MODE_PRIVATE);
    }

    private static Map<String, byte[]> index() {
        Map<String, byte[]> m = index;
        if (m == null) {
            synchronized (VibeLock.class) {
                m = index;
                if (m == null) {
                    m = new ConcurrentHashMap<>();
                    try {
                        for (Map.Entry<String, ?> e : indexPrefs().getAll().entrySet()) {
                            if (e.getValue() instanceof String) {
                                m.put(e.getKey(), Base64.decode((String) e.getValue(), Base64.NO_WRAP));
                            }
                        }
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                    index = m;
                }
            }
        }
        return m;
    }

    public static int state(int account, long userId) {
        return prefs(account).getInt("state_" + userId, NONE);
    }

    public static void setState(int account, long userId, int state) {
        prefs(account).edit().putInt("state_" + userId, state).apply();
    }

    public static boolean isOn(int account, long dialogId) {
        return dialogId > 0 && state(account, dialogId) == ON && prefs(account).contains("key_" + dialogId);
    }

    public static String peerKey(int account, long userId) {
        return prefs(account).getString("peer_" + userId, null);
    }

    public static void setPeerKey(int account, long userId, String pubB64) {
        prefs(account).edit().putString("peer_" + userId, pubB64).apply();
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

    // ---------------------------------------------------------------- keys

    private static synchronized KeyPair identity(int account) throws Exception {
        if (identities[account] != null) {
            return identities[account];
        }
        SharedPreferences p = prefs(account);
        String priv = p.getString("my_priv", null);
        String pub = p.getString("my_pub", null);
        KeyFactory kf = KeyFactory.getInstance("EC");
        KeyPair kp;
        if (priv != null && pub != null) {
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
            PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)));
            kp = new KeyPair(pu, pk);
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), random);
            kp = g.generateKeyPair();
            p.edit()
                    .putString("my_priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString("my_pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                    .commit();
        }
        identities[account] = kp;
        return kp;
    }

    public static String myPublicKey(int account) {
        try {
            return Base64.encodeToString(identity(account).getPublic().getEncoded(), Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] fingerprint(byte[] pubEncoded) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(pubEncoded);
        byte[] out = new byte[8];
        System.arraycopy(h, 0, out, 0, 8);
        return out;
    }

    /** Short code both people can compare, e.g. "4F2A 91C0 7B3E". */
    public static String safetyCode(int account, long userId) {
        try {
            String peer = peerKey(account, userId);
            if (peer == null) {
                return null;
            }
            byte[] a = identity(account).getPublic().getEncoded();
            byte[] b = Base64.decode(peer, Base64.NO_WRAP);
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

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return a.length - b.length;
    }

    private static String pairId(byte[] fpA, byte[] fpB) {
        String a = Utilities.bytesToHex(fpA), b = Utilities.bytesToHex(fpB);
        return a.compareTo(b) <= 0 ? a + b : b + a;
    }

    /** Works out the shared key with the peer's public key and turns the lock on. */
    public static boolean activate(int account, long userId, String peerPubB64) {
        try {
            KeyPair me = identity(account);
            byte[] peerEncoded = Base64.decode(peerPubB64, Base64.NO_WRAP);
            PublicKey peer = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(peerEncoded));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(me.getPrivate());
            ka.doPhase(peer, true);
            byte[] secret = ka.generateSecret();

            byte[] myFp = fingerprint(me.getPublic().getEncoded());
            byte[] peerFp = fingerprint(peerEncoded);
            String pair = pairId(myFp, peerFp);
            byte[] key = hkdf(Utilities.hexToBytes(pair), secret);

            String keyB64 = Base64.encodeToString(key, Base64.NO_WRAP);
            prefs(account).edit()
                    .putString("peer_" + userId, peerPubB64)
                    .putString("key_" + userId, keyB64)
                    .putString("pfp_" + userId, Utilities.bytesToHex(peerFp))
                    .putInt("state_" + userId, ON)
                    .apply();
            index().put(pair, key);
            indexPrefs().edit().putString(pair, keyB64).apply();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private static byte[] hkdf(byte[] salt, byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(INFO);
        mac.update((byte) 1);
        return mac.doFinal();
    }

    // ---------------------------------------------------------------- messages

    public static boolean isHandshake(String text) {
        return text != null && (text.contains("\n" + REQ) || text.contains("\n" + ACK) || text.endsWith("\n" + OFF));
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

    public static String encrypt(int account, long userId, String plain) {
        try {
            SharedPreferences p = prefs(account);
            String keyB64 = p.getString("key_" + userId, null);
            String pfp = p.getString("pfp_" + userId, null);
            if (keyB64 == null || pfp == null) {
                return null;
            }
            byte[] myFp = fingerprint(identity(account).getPublic().getEncoded());
            byte[] peerFp = Utilities.hexToBytes(pfp);
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.decode(keyB64, Base64.NO_WRAP), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(myFp);
            c.updateAAD(peerFp);
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[8 + 8 + 12 + ct.length];
            System.arraycopy(myFp, 0, out, 0, 8);
            System.arraycopy(peerFp, 0, out, 8, 8);
            System.arraycopy(iv, 0, out, 16, 12);
            System.arraycopy(ct, 0, out, 28, ct.length);
            return HEADER + TOKEN + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Plain text of a locked message, or null if this phone has no key for it. */
    public static String decrypt(String text) {
        if (!isLockedText(text)) {
            return null;
        }
        try {
            String body = text.substring(HEADER.length()).trim();
            if (!body.startsWith(TOKEN)) {
                return null;
            }
            byte[] in = Base64.decode(body.substring(TOKEN.length()), Base64.NO_WRAP);
            if (in.length < 28 + 16) {
                return null;
            }
            byte[] senderFp = new byte[8], recipFp = new byte[8], iv = new byte[12];
            System.arraycopy(in, 0, senderFp, 0, 8);
            System.arraycopy(in, 8, recipFp, 0, 8);
            System.arraycopy(in, 16, iv, 0, 12);
            byte[] key = index().get(pairId(senderFp, recipFp));
            if (key == null) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(senderFp);
            c.updateAAD(recipFp);
            byte[] pt = c.doFinal(in, 28, in.length - 28);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Called for every message read from the network or the local database. */
    public static void onDeserialized(TLRPC.Message m) {
        if (m == null || !isLockedText(m.message)) {
            return;
        }
        String plain = decrypt(m.message);
        if (plain != null) {
            m.message = plain;
            if (m.entities != null && !m.entities.isEmpty()) {
                m.entities = new ArrayList<>();
            }
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
}
