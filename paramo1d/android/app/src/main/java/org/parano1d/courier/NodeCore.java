package org.parano1d.courier;

/** iOS Node core (token/NFT planner and submit), built into the same library as NativeCore. No secrets. */
final class NodeCore {
    static { System.loadLibrary("parano1d_android"); }
    static native String call(String request);
}
