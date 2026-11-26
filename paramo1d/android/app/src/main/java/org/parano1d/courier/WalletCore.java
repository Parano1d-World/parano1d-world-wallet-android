package org.parano1d.courier;

/** iOS wallet core (canonical review, signing, payment journal). The caller wipes `secret` after the call. */
final class WalletCore {
    static { System.loadLibrary("parano1d_android"); }
    static native String call(String request, byte[] secret);
}
