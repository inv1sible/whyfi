// SPIKE: receive-only monitor-mode probe for RTL8812AU-family USB sticks.
//
// Kotlin opens the stick through Android's UsbManager (no root) and passes
// the file descriptor here; libusb wraps it and devourer brings the chip up
// in monitor mode. We never transmit. Frames are only counted (types,
// probe requests, distinct transmitters, RSSI) — no MACs or SSIDs leave this
// file. Each step is appended to the app's diagnostic trace *before* it is
// attempted, so a native crash still shows how far bring-up got.

#include <jni.h>
#include <android/log.h>
#include <libusb.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <exception>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "IRtlDevice.h"
#include "RxPacket.h"
#include "SelectedChannel.h"
#include "UsbOpen.h"
#include "WiFiDriver.h"
#include "logger.h"

namespace {

constexpr const char *TAG = "whyfiusb";

// Stats slots shared with Kotlin (UsbMonitorNative.Stat) — keep in sync.
enum Stat {
    TOTAL, MGMT, CTRL, DATA, BEACON, PROBE_REQ, PROBE_RESP,
    PROBE_REQ_SOURCES, PROBE_REQ_LOCAL_ADMIN, PROBE_REQ_DIRECTED,
    CLIENTS_TO_AP, RSSI_MIN, RSSI_MAX, CURRENT_CHANNEL, STAGE, BAD_FCS_OR_SHORT,
    STAT_COUNT
};

std::atomic<long long> g_stats[STAT_COUNT];
std::atomic<bool> g_stop{false};
std::atomic<bool> g_running{false};
std::mutex g_sets_mu;
std::set<std::string> g_probe_sources, g_clients;
std::vector<long long> g_per_channel(256);
std::string g_trace_path;

void trace(const std::string &msg) {
    __android_log_write(ANDROID_LOG_INFO, TAG, msg.c_str());
    if (g_trace_path.empty()) return;
    // Same line format as DiagnosticTrace.kt: "<ISO-UTC> <message>".
    // Open/append/close per line so the line is on disk before the next step.
    if (FILE *f = std::fopen(g_trace_path.c_str(), "a")) {
        char ts[32];
        std::time_t now = std::time(nullptr);
        std::strftime(ts, sizeof ts, "%Y-%m-%dT%H:%M:%SZ", std::gmtime(&now));
        std::fprintf(f, "%s usbmon: %s\n", ts, msg.c_str());
        std::fclose(f);
    }
}

std::string mac(const uint8_t *p) {
    char b[18];
    std::snprintf(b, sizeof b, "%02x:%02x:%02x:%02x:%02x:%02x", p[0], p[1], p[2], p[3], p[4], p[5]);
    return b;
}

void onFrame(const Packet &packet) {
    const auto &d = packet.Data;
    g_stats[TOTAL]++;
    int ch = static_cast<int>(g_stats[CURRENT_CHANNEL].load());
    if (ch > 0 && ch < 256) {
        std::lock_guard<std::mutex> lock(g_sets_mu);
        g_per_channel[ch]++;
    }
    const int rssi = static_cast<int>(packet.RxAtrib.rssi[0]) - 110;  // devourer LinkHealth: raw - 110 = dBm
    if (rssi < g_stats[RSSI_MIN]) g_stats[RSSI_MIN] = rssi;
    if (rssi > g_stats[RSSI_MAX]) g_stats[RSSI_MAX] = rssi;

    if (d.size() < 16) { g_stats[BAD_FCS_OR_SHORT]++; return; }
    const uint8_t fc0 = d[0], fc1 = d[1];
    const int type = (fc0 >> 2) & 0x3, subtype = (fc0 >> 4) & 0xF;
    const uint8_t *addr1 = &d[4], *addr2 = &d[10];

    if (type == 0) {
        g_stats[MGMT]++;
        if (subtype == 8) g_stats[BEACON]++;
        else if (subtype == 5) g_stats[PROBE_RESP]++;
        else if (subtype == 4) {
            g_stats[PROBE_REQ]++;
            // Body starts after the 24-byte header; first IE is the SSID.
            if (d.size() >= 26 && d[24] == 0 && d[25] > 0) g_stats[PROBE_REQ_DIRECTED]++;
            std::lock_guard<std::mutex> lock(g_sets_mu);
            if (g_probe_sources.insert(mac(addr2)).second && (addr2[0] & 0x02))
                g_stats[PROBE_REQ_LOCAL_ADMIN]++;
            g_stats[PROBE_REQ_SOURCES] = static_cast<long long>(g_probe_sources.size());
        }
    } else if (type == 1) {
        g_stats[CTRL]++;
    } else if (type == 2) {
        g_stats[DATA]++;
        // ToDS=1, FromDS=0: a station sending to its AP -> addr2 is the client.
        if ((fc1 & 0x3) == 0x1 && d.size() >= 24) {
            std::lock_guard<std::mutex> lock(g_sets_mu);
            g_clients.insert(mac(addr2) + ">" + mac(addr1));
            g_stats[CLIENTS_TO_AP] = static_cast<long long>(g_clients.size());
        }
    }
}

void resetStats() {
    for (auto &s : g_stats) s = 0;
    g_stats[RSSI_MIN] = 0x7fff;
    g_stats[RSSI_MAX] = -0x7fff;
    std::lock_guard<std::mutex> lock(g_sets_mu);
    g_probe_sources.clear();
    g_clients.clear();
    std::fill(g_per_channel.begin(), g_per_channel.end(), 0);
}

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Returns "" on success or a one-line failure reason.
std::string runProbe(int fd, const std::string &lockDir, const std::vector<int> &channels, int dwellMs, int totalMs) {
    g_stats[STAGE] = 1;
    trace("libusb init (no device discovery, wrapping Android fd " + std::to_string(fd) + ")");
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    libusb_context *ctx = nullptr;
    int rc = libusb_init(&ctx);
    if (rc != 0) return std::string("libusb_init failed: ") + libusb_error_name(rc);

    libusb_device_handle *handle = nullptr;
    rc = libusb_wrap_sys_device(ctx, static_cast<intptr_t>(fd), &handle);
    if (rc != 0 || !handle) {
        libusb_exit(ctx);
        return std::string("libusb_wrap_sys_device failed: ") + libusb_error_name(rc);
    }

    auto logger = std::make_shared<Logger>();
    logger->set_level(Logger::Level::Info);

    g_stats[STAGE] = 2;
    trace("claiming USB interface (no reset: an app-owned fd can't re-enumerate)");
    std::shared_ptr<devourer::UsbDeviceLock> lock;
    rc = devourer::claim_interface_reset_reopen(ctx, handle, logger, false, lock, lockDir);
    if (rc != 0) {
        libusb_close(handle);
        libusb_exit(ctx);
        return "claiming the USB interface failed (rc " + std::to_string(rc) + ")";
    }

    g_stats[STAGE] = 3;
    trace("creating devourer radio");
    WiFiDriver driver(logger);
    std::unique_ptr<IRtlDevice> radio = driver.CreateRtlDevice(handle, ctx, lock);
    if (!radio) {
        libusb_release_interface(handle, 0);
        libusb_close(handle);
        libusb_exit(ctx);
        return "devourer has no driver for this chip (Jaguar1 = 8812AU/8811AU/8821AU only in this build)";
    }

    g_stats[STAGE] = 4;
    const SelectedChannel first{static_cast<uint8_t>(channels[0]), 0, CHANNEL_WIDTH_20};
    g_stats[CURRENT_CHANNEL] = channels[0];
    trace("bring-up: firmware + calibration + monitor mode on channel " + std::to_string(channels[0]));
    // rxError is written by the RX thread only before it sets rxDone, and read
    // here only after rxDone is seen (or after join) — no concurrent access.
    std::string rxError;
    std::atomic<bool> rxDone{false};
    std::thread rx([&]() {
        try {
            radio->Init(onFrame, first);
        } catch (const std::exception &e) {
            rxError = e.what();
        }
        rxDone = true;
    });

    // Don't retune while bring-up is still writing registers: the first frame
    // proves it finished (devourer's own sweep demo does the same, 10 s cap).
    for (int waited = 0; waited < 10000 && g_stats[TOTAL] == 0 && !g_stop && !rxDone; waited += 50)
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    trace(g_stats[TOTAL] > 0 ? "first frame received — monitor mode works"
                             : "no frame within 10 s of bring-up (continuing to hop)");

    g_stats[STAGE] = 5;
    const auto start = std::chrono::steady_clock::now();
    size_t i = 0;
    while (!g_stop && !rxDone &&
           std::chrono::steady_clock::now() - start < std::chrono::milliseconds(totalMs)) {
        const int ch = channels[i++ % channels.size()];
        if (i > 1) {
            radio->FastRetune(static_cast<uint8_t>(ch), true);
            g_stats[CURRENT_CHANNEL] = ch;
        }
        if (i <= channels.size()) trace("first visit to channel " + std::to_string(ch));
        std::this_thread::sleep_for(std::chrono::milliseconds(dwellMs));
    }

    g_stats[STAGE] = 6;
    trace("stopping RX");
    radio->StopRxLoop();
    rx.join();
    if (rxError.empty() && g_stats[TOTAL] == 0) rxError = "RX loop ended without receiving any frame";
    radio.reset();  // device (and its in-flight transfers) must die before libusb
    libusb_release_interface(handle, 0);
    libusb_close(handle);  // closes libusb's wrapper; Android still owns the fd
    libusb_exit(ctx);
    g_stats[STAGE] = 7;
    trace("done: " + std::to_string(g_stats[TOTAL].load()) + " frames");
    return rxError.empty() ? "" : "RX loop failed: " + rxError;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_whyfi_app_usbmon_UsbMonitorNative_run(JNIEnv *env, jobject, jint fd, jstring lockDir, jstring tracePath,
                                               jintArray channels, jint dwellMs, jint totalMs) {
    if (g_running.exchange(true)) return env->NewStringUTF("a test is already running");
    g_stop = false;
    resetStats();
    g_trace_path = jstr(env, tracePath);
    std::vector<int> chans(env->GetArrayLength(channels));
    env->GetIntArrayRegion(channels, 0, static_cast<jsize>(chans.size()), chans.data());
    std::string result;
    if (chans.empty()) {
        result = "no channels given";
    } else {
        try {
            result = runProbe(fd, jstr(env, lockDir), chans, dwellMs, totalMs);
        } catch (const std::exception &e) {
            result = std::string("exception: ") + e.what();
        }
    }
    if (!result.empty()) trace("FAILED: " + result);
    g_running = false;
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_whyfi_app_usbmon_UsbMonitorNative_stop(JNIEnv *, jobject) {
    g_stop = true;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_whyfi_app_usbmon_UsbMonitorNative_stats(JNIEnv *env, jobject) {
    jlong out[STAT_COUNT];
    for (int i = 0; i < STAT_COUNT; i++) out[i] = g_stats[i].load();
    jlongArray arr = env->NewLongArray(STAT_COUNT);
    env->SetLongArrayRegion(arr, 0, STAT_COUNT, out);
    return arr;
}

// Frames per channel as [ch0, n0, ch1, n1, ...] for channels that saw any.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_whyfi_app_usbmon_UsbMonitorNative_perChannel(JNIEnv *env, jobject) {
    std::vector<jlong> flat;
    {
        std::lock_guard<std::mutex> lock(g_sets_mu);
        for (int ch = 1; ch < 256; ch++)
            if (g_per_channel[ch] > 0) { flat.push_back(ch); flat.push_back(g_per_channel[ch]); }
    }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(flat.size()));
    env->SetLongArrayRegion(arr, 0, static_cast<jsize>(flat.size()), flat.data());
    return arr;
}
