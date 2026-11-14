#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <unistd.h>

// Helper to check if a file or directory exists at the Linux level
bool fileExists(const char* path) {
    return access(path, F_OK) == 0;
}

// Helper to scan a /proc file for suspicious keywords
bool scanProcFile(const char* procPath, const std::vector<std::string>& keywords) {
    std::ifstream file(procPath);
    if (!file.is_open()) return false;

    std::string line;
    while (std::getline(file, line)) {
        for (const auto& kw : keywords) {
            if (line.find(kw) != std::string::npos) {
                return true;
            }
        }
    }
    return false;
}

// Check if a debugger is attached via TracerPid in /proc/self/status
bool isDebuggerAttached() {
    std::ifstream statusFile("/proc/self/status");
    std::string line;
    while (std::getline(statusFile, line)) {
        if (line.rfind("TracerPid:", 0) == 0) {
            int pid = std::stoi(line.substr(10));
            return pid > 0;
        }
    }
    return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mobilescanner_NativeBridge_runNativeChecks(
        JNIEnv* env,
        jobject /* this */) {

    std::vector<std::string> flags;

    // 1. Check Root / Magisk / KernelSU binaries
    const char* rootPaths[] = {
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/ap"
    };
    for (const char* path : rootPaths) {
        if (fileExists(path)) {
            flags.push_back("HIGH|Root or Magisk Binary Found|" + std::string(path));
        }
    }

    // 2. Check /proc/self/mounts for Zygisk / Magisk / Shamiko mounts
    if (scanProcFile("/proc/self/mounts", {"magisk", "zygisk", "kernelsu", "apatch"})) {
        flags.push_back("HIGH|Root Mount Detected|/proc/self/mounts contains Magisk/Zygisk");
    }

    // 3. Check /proc/self/maps for Frida / Xposed / LSPosed injection
    if (scanProcFile("/proc/self/maps", {"frida", "xposed", "lsposed", "substrate"})) {
        flags.push_back("HIGH|Memory Hook Detected|Frida or LSPosed injected into scanner");
    }

    // 4. Check TracerPid for active debugger
    if (isDebuggerAttached()) {
        flags.push_back("HIGH|Debugger Attached|TracerPid is non-zero in /proc/self/status");
    }

    if (flags.empty()) {
        return env->NewStringUTF("CLEAN");
    }

    std::ostringstream joined;
    for (size_t i = 0; i < flags.size(); ++i) {
        if (i > 0) joined << ";";
        joined << flags[i];
    }
    return env->NewStringUTF(joined.str().c_str());
}
