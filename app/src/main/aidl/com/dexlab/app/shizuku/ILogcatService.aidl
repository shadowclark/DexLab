package com.dexlab.app.shizuku;

import android.os.ParcelFileDescriptor;
import com.dexlab.app.shizuku.ILogcatCallback;

interface ILogcatService {
    // Reserved transaction id used by the Shizuku server to destroy a UserService.
    void destroy() = 16777114;

    void start(ILogcatCallback callback, in String[] args) = 1;
    void stop() = 2;
    boolean isRunning() = 3;
    String[] packagePaths(String packageName) = 4;
    ParcelFileDescriptor openRead(String path) = 5;
}
