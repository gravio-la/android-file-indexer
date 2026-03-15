package dev.fileassistant

object RustBridge {
    init {
        System.loadLibrary("fileassistant_core")
        nativeInit()
    }

    external fun nativeInit()
    external fun nativeClassify(path: String): String
    external fun nativeOnFileEvent(eventType: String, path: String)
}
