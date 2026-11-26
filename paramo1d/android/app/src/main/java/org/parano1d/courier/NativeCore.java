package org.parano1d.courier;

final class NativeCore {
    static { System.loadLibrary("parano1d_android"); }
    static native String call(String request);
}
