use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "C" fn Java_dev_fileassistant_RustBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        android_logger::Config::default().with_min_level(log::Level::Debug),
    );
    log::info!("FileAssistant Rust core initialized");
}

#[no_mangle]
pub extern "C" fn Java_dev_fileassistant_RustBridge_nativeClassify(
    env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let _path: String = env.get_string(&path).unwrap().into();
    let proposal = r#"{"action":"move","destination":"Documents/inbox","confidence":0.95}"#;
    env.new_string(proposal).unwrap().into_raw()
}

#[no_mangle]
pub extern "C" fn Java_dev_fileassistant_RustBridge_nativeOnFileEvent(
    env: JNIEnv,
    _class: JClass,
    event_type: JString,
    path: JString,
) {
    let event: String = env.get_string(&event_type).unwrap().into();
    let p: String = env.get_string(&path).unwrap().into();
    log::info!("FileEvent: {} on {}", event, p);
}
