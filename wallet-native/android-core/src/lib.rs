//! JNI entry points for the iOS Node and Native cores on Android. `NativeCore.call`
//! (the Paramo1d device node) is exported by the `paramo1d` crate itself.
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::ffi::{c_char, CStr};
use zeroize::Zeroizing;

extern "C" {
    fn paranoid_node_call(input: *const u8, length: usize) -> *mut c_char;
    fn paranoid_node_free(value: *mut c_char);
    fn paranoid_ios_call(input: *const u8, length: usize, secret: *const u8, secret_length: usize) -> *mut c_char;
    fn paranoid_ios_free(value: *mut c_char);
}

// Keep the cores' C entry points linked even though only the JNI functions below are used from Java.
#[used]
static KEEP: [unsafe extern "C" fn(*const u8, usize) -> *mut c_char; 1] = [paranoid_ios_node::paranoid_node_call];
// The wallet core (token/NFT signing, payment journal) must be linked too: without a Rust reference its crate is
// dropped and the library fails to load on the phone ("cannot locate symbol paranoid_ios_call").
#[used]
static KEEP_WALLET: [unsafe extern "C" fn(*const u8, usize, *const u8, usize) -> *mut c_char; 1] = [paranoid_ios_core::paranoid_ios_call];
#[used]
static KEEP_WALLET_FREE: [unsafe extern "C" fn(*mut c_char); 1] = [paranoid_ios_core::paranoid_ios_free];

fn text(value: *mut c_char) -> String {
    if value.is_null() { return r#"{"ok":false,"error":"Native operation failed"}"#.into(); }
    unsafe { CStr::from_ptr(value) }.to_string_lossy().into_owned()
}

fn reply(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// org.parano1d.courier.NodeCore.call(String): iOS Node operations (native_prepare, native_submit,
/// settle_payment, expire_payment, payment_plan …). No secrets ever pass through here.
#[no_mangle]
pub extern "system" fn Java_org_parano1d_courier_NodeCore_call(mut env: JNIEnv, _class: JClass, request: JString) -> jstring {
    let input: String = match env.get_string(&request) { Ok(s) => s.into(), Err(_) => return reply(&mut env, r#"{"ok":false,"error":"Invalid node request"}"#.into()) };
    let output = unsafe {
        let pointer = paranoid_node_call(input.as_ptr(), input.len());
        let text = text(pointer);
        if !pointer.is_null() { paranoid_node_free(pointer); }
        text
    };
    reply(&mut env, output)
}

/// org.parano1d.courier.WalletCore.call(String, byte[] secret): iOS wallet-core operations
/// (review, authorize, payment_pending …). The 32-byte secret is optional, copied into zeroizing
/// memory, and the Java caller wipes its own array afterwards.
#[no_mangle]
pub extern "system" fn Java_org_parano1d_courier_WalletCore_call<'local>(mut env: JNIEnv<'local>, _class: JClass<'local>, request: JString<'local>, secret: JByteArray<'local>) -> jstring {
    let input: String = match env.get_string(&request) { Ok(s) => s.into(), Err(_) => return reply(&mut env, r#"{"error":"Invalid native input"}"#.into()) };
    let key: Option<Zeroizing<Vec<u8>>> = if secret.is_null() { None } else {
        match env.convert_byte_array(&secret) { Ok(bytes) => Some(Zeroizing::new(bytes)), Err(_) => return reply(&mut env, r#"{"error":"Invalid native input"}"#.into()) }
    };
    let output = unsafe {
        let (pointer, length) = key.as_ref().map(|k| (k.as_ptr(), k.len())).unwrap_or((std::ptr::null(), 0));
        let value = paranoid_ios_call(input.as_ptr(), input.len(), pointer, length);
        let text = text(value);
        if !value.is_null() { paranoid_ios_free(value); }
        Zeroizing::new(text)
    };
    reply(&mut env, output.to_string())
}

// The Paramo1d device node's own JNI entry point (org.parano1d.courier.NativeCore), re-exported so
// this one library serves NativeCore, NodeCore and WalletCore.
#[cfg(target_os = "android")]
pub use paramo1d::courier_api::Java_org_parano1d_courier_NativeCore_call;
