package io.github.jackwener.dsh.engine

// Package must match the JNI symbol exported by native/engine-jni/dshengine.cc. Muse keeps the
// upstream package here (not io.github.markhunter123.muse.engine) so the published upstream
// prebuilt libdshengine.so (native-node24.22.1-c5b68634a6, symbol unchanged) loads as-is; see
// native/prebuilt.sh — the only fork delta is this class's package name.

/** JNI entry into libdshengine.so, which calls node::Start on the given fds. `args` excludes argv[0]. */
object NodeRuntime {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("dshengine")
    }

    @JvmStatic
    external fun nativeStart(args: Array<String>, stdinFd: Int, stdoutFd: Int, stderrFd: Int): Int
}
