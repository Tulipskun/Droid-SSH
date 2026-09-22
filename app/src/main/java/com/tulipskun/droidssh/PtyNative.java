package com.tulipskun.droidssh;

import android.util.Log;

/** JNI ไปยัง libdroidssh_pty.so (forkpty แบบ manual เพราะ Bionic ไม่มี forkpty) */
public final class PtyNative {
    private static final String TAG = "PtyNative";
    private static final boolean AVAILABLE;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("droidssh_pty");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "native pty unavailable, piped shell fallback: " + e.getMessage());
        }
        AVAILABLE = ok;
    }

    private PtyNative() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** @return master fd หรือค่าติดลบ (-errno); pid ผ่าน outPid[0] */
    public static native int forkPty(String[] argv, String[] envp, String workDir,
                                     int rows, int cols, int[] outPid);

    public static native int ptyRead(int fd, byte[] buf, int off, int len);

    public static native int ptyWrite(int fd, byte[] buf, int off, int len);

    public static native void ptyClose(int fd);

    public static native int ptyWait(int pid);

    public static native int ptyResize(int fd, int rows, int cols);

    public static native int ptyKill(int pid, int sig);
}
