package org.parano1d.courier;

/** Minimal BLAKE3 (hash mode, 32-byte output) for checking Parano1d asset metadata and artwork. */
final class Blake3 {
    private static final int[] IV = {0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19};
    private static final int[] PERM = {2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8};
    private static final int CHUNK_START = 1, CHUNK_END = 2, PARENT = 4, ROOT = 8;
    private static final int CHUNK_LEN = 1024, BLOCK_LEN = 64;

    private Blake3() {}

    private static void g(int[] s, int a, int b, int c, int d, int x, int y) {
        s[a] = s[a] + s[b] + x; s[d] = Integer.rotateRight(s[d] ^ s[a], 16);
        s[c] = s[c] + s[d]; s[b] = Integer.rotateRight(s[b] ^ s[c], 12);
        s[a] = s[a] + s[b] + y; s[d] = Integer.rotateRight(s[d] ^ s[a], 8);
        s[c] = s[c] + s[d]; s[b] = Integer.rotateRight(s[b] ^ s[c], 7);
    }

    private static int[] compress(int[] cv, int[] m, long counter, int len, int flags) {
        int[] s = {cv[0], cv[1], cv[2], cv[3], cv[4], cv[5], cv[6], cv[7], IV[0], IV[1], IV[2], IV[3], (int) counter, (int) (counter >>> 32), len, flags};
        int[] w = m.clone();
        for (int r = 0; r < 7; r++) {
            g(s, 0, 4, 8, 12, w[0], w[1]); g(s, 1, 5, 9, 13, w[2], w[3]); g(s, 2, 6, 10, 14, w[4], w[5]); g(s, 3, 7, 11, 15, w[6], w[7]);
            g(s, 0, 5, 10, 15, w[8], w[9]); g(s, 1, 6, 11, 12, w[10], w[11]); g(s, 2, 7, 8, 13, w[12], w[13]); g(s, 3, 4, 9, 14, w[14], w[15]);
            if (r < 6) { int[] p = new int[16]; for (int i = 0; i < 16; i++) p[i] = w[PERM[i]]; w = p; }
        }
        int[] out = new int[16];
        for (int i = 0; i < 8; i++) { out[i] = s[i] ^ s[i + 8]; out[i + 8] = s[i + 8] ^ cv[i]; }
        return out;
    }

    private static int[] words(byte[] b, int off, int len) {
        int[] m = new int[16];
        for (int i = 0; i < len; i++) m[i >> 2] |= (b[off + i] & 0xff) << (8 * (i & 3));
        return m;
    }

    /** Chaining value of one chunk; when root is true, returns the root output words. */
    private static int[] chunk(byte[] data, int off, int len, long index, boolean root) {
        int[] cv = IV.clone();
        int blocks = Math.max(1, (len + BLOCK_LEN - 1) / BLOCK_LEN);
        for (int b = 0; b < blocks; b++) {
            int start = b * BLOCK_LEN, blen = Math.min(BLOCK_LEN, len - start);
            if (blen < 0) blen = 0;
            int flags = (b == 0 ? CHUNK_START : 0) | (b == blocks - 1 ? CHUNK_END : 0);
            if (b == blocks - 1 && root) flags |= ROOT;
            int[] out = compress(cv, words(data, off + start, blen), index, blen, flags);
            cv = java.util.Arrays.copyOf(out, 8);
        }
        return cv;
    }

    private static int[] parent(int[] left, int[] right, boolean root) {
        int[] m = new int[16];
        System.arraycopy(left, 0, m, 0, 8); System.arraycopy(right, 0, m, 8, 8);
        return java.util.Arrays.copyOf(compress(IV, m, 0, BLOCK_LEN, PARENT | (root ? ROOT : 0)), 8);
    }

    /** Hash of chunks [first, first+count), following BLAKE3's left-heavy binary tree. */
    private static int[] subtree(byte[] data, long first, long count, boolean root) {
        if (count == 1) {
            int off = (int) (first * CHUNK_LEN);
            return chunk(data, off, Math.min(CHUNK_LEN, data.length - off), first, root);
        }
        long left = Long.highestOneBit(count - 1);
        return parent(subtree(data, first, left, false), subtree(data, first + left, count - left, false), root);
    }

    static byte[] hash(byte[] data) {
        long chunks = Math.max(1, (data.length + CHUNK_LEN - 1) / CHUNK_LEN);
        int[] cv = subtree(data, 0, chunks, true);
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) out[i] = (byte) (cv[i >> 2] >>> (8 * (i & 3)));
        return out;
    }

    /** BLAKE3(domain || bytes) as lowercase hex, as Parano1d's media hashes are defined. */
    static String domainHex(String domain, byte[] bytes) {
        byte[] prefix = domain.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] all = new byte[prefix.length + 1 + bytes.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length); // domain then NUL, e.g. "Parano1d/native-image/v1\0"
        System.arraycopy(bytes, 0, all, prefix.length + 1, bytes.length);
        StringBuilder s = new StringBuilder();
        for (byte b : hash(all)) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
}
