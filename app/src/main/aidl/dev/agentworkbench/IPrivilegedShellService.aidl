package dev.agentworkbench;

import android.view.Surface;

interface IPrivilegedShellService {
    void destroy() = 16777114;
    String execute(String script, int timeoutMs, int maxOutputBytes) = 1;
    int createShadowDisplay(in Surface surface, int width, int height, int densityDpi) = 2;
    void releaseShadowDisplay() = 3;
    String shadowDisplayState() = 4;
    String launchPackageOnShadowDisplay(String packageName) = 5;
    String shadowTap(int x, int y) = 6;
    String shadowSwipe(int x1, int y1, int x2, int y2, int durationMs) = 7;
    String shadowText(String text) = 8;
    String shadowKeyEvent(int keyCode) = 9;
}
