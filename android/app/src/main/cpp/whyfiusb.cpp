// SPIKE: receive-only monitor-mode probe for RTL8812AU-family USB sticks.
//
// Kotlin opens the stick through Android's UsbManager (no root) and passes
// the file descriptor here; libusb wraps it and devourer brings the chip up
// in monitor mode. We never transmit. Besides counters, a per-device table
// (transmitter MAC, role, network names, WPS identity, traffic volume) is
// kept in memory for the on-phone device list; Kotlin decides what, if
// anything, leaves the phone. Each step is appended to the app's diagnostic trace *before* it is
// attempted, so a native crash still shows how far bring-up got.

#include <jni.h>
#include <android/log.h>
#include <libusb.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <ctime>
#include <exception>
#include <memory>
#include <mutex>
#include <map>
#include <set>
#include <string>
#include <thread>
#include <time.h>
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
    BEACON_CH_MATCH, BEACON_CH_OTHER, DEVICES, NO_RSSI, PCAP_BYTES, PCAP_TRUNCATED,
    EAPOL_KEY_FRAMES,
    STAT_COUNT
};

std::atomic<long long> g_stats[STAT_COUNT];
std::atomic<bool> g_stop{false};
std::atomic<bool> g_running{false};
std::mutex g_sets_mu;
std::set<std::string> g_probe_sources, g_clients;
std::vector<long long> g_per_channel(256);
std::string g_trace_path;
std::mutex g_pcap_mu;
FILE *g_pcap = nullptr;
long long g_pcap_bytes = 0;
constexpr long long MAX_PCAP_BYTES = 200LL * 1024 * 1024;

void put32(FILE *f, uint32_t v) { std::fwrite(&v, sizeof v, 1, f); }

void closePcap() {
    std::lock_guard<std::mutex> lock(g_pcap_mu);
    if (g_pcap) std::fclose(g_pcap);
    g_pcap = nullptr;
}

bool openPcap(const std::string &path) {
    if (path.empty()) return true;
    std::lock_guard<std::mutex> lock(g_pcap_mu);
    FILE *existing = std::fopen(path.c_str(), "rb");
    const bool append = existing != nullptr;
    long long previous_bytes = 0;
    if (existing) {
        unsigned char header[24] = {};
        bool valid = std::fread(header, 1, sizeof header, existing) == sizeof header &&
            header[0] == 0xd4 && header[1] == 0xc3 && header[2] == 0xb2 && header[3] == 0xa1 &&
            header[20] == 105 && header[21] == 0 && header[22] == 0 && header[23] == 0;
        if (std::fseek(existing, 0, SEEK_END) == 0) previous_bytes = std::ftell(existing);
        std::fclose(existing);
        if (!valid || previous_bytes < 24) return false;
        if (previous_bytes >= MAX_PCAP_BYTES) {
            g_pcap_bytes = previous_bytes;
            g_stats[PCAP_BYTES] = g_pcap_bytes;
            g_stats[PCAP_TRUNCATED] = 1;
            return true;
        }
    }
    g_pcap = std::fopen(path.c_str(), append ? "ab" : "wb");
    if (!g_pcap) return false;
    if (append) {
        g_pcap_bytes = previous_bytes;
        g_stats[PCAP_BYTES] = g_pcap_bytes;
        return true;
    }
    // Classic little-endian PCAP, DLT_IEEE802_11. The adapter supplies a full
    // 802.11 frame including FCS; no radiotap metadata is invented here.
    put32(g_pcap, 0xa1b2c3d4); uint16_t v = 2; std::fwrite(&v, 2, 1, g_pcap); v = 4; std::fwrite(&v, 2, 1, g_pcap);
    put32(g_pcap, 0); put32(g_pcap, 0); put32(g_pcap, 65535); put32(g_pcap, 105);
    g_pcap_bytes = 24;
    g_stats[PCAP_BYTES] = g_pcap_bytes;
    return true;
}

void writePcap(const Packet &packet) {
    std::lock_guard<std::mutex> lock(g_pcap_mu);
    if (!g_pcap || g_pcap_bytes + 16 + static_cast<long long>(packet.Data.size()) > MAX_PCAP_BYTES) {
        if (g_pcap) { g_stats[PCAP_TRUNCATED] = 1; std::fclose(g_pcap); g_pcap = nullptr; }
        return;
    }
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    const auto us = std::chrono::duration_cast<std::chrono::microseconds>(now).count();
    put32(g_pcap, static_cast<uint32_t>(us / 1000000)); put32(g_pcap, static_cast<uint32_t>(us % 1000000));
    put32(g_pcap, static_cast<uint32_t>(packet.Data.size())); put32(g_pcap, static_cast<uint32_t>(packet.Data.size()));
    std::fwrite(packet.Data.data(), 1, packet.Data.size(), g_pcap);
    g_pcap_bytes += 16 + static_cast<long long>(packet.Data.size());
    g_stats[PCAP_BYTES] = g_pcap_bytes;
}

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

// One transmitter (or a client seen as a data-frame receiver).
constexpr int ROLE_AP = 1, ROLE_CLIENT = 2;
constexpr size_t MAX_DEVICES = 4000, MAX_NAMES = 16;

struct Device {
    int roles = 0;
    long long frames = 0, probeReqs = 0, bytesUp = 0, bytesDown = 0;
    int bestRssi = -1000;  // -1000 = no reading
    int lastRssi = -1000;
    long long lastRssiBootMs = 0;
    int lastRssiChannel = 0;
    std::set<int> channels;
    std::string ssid;                // beaconed network name (APs)
    std::set<std::string> probed;    // network names asked for (clients)
    bool wildcardProbe = false;
    std::set<std::string> bssids;    // APs this client exchanged data with
    std::string wpsName, wpsManufacturer, wpsModel, wpsModelNumber, wpsSerial;
    std::string wpsPrimaryType, wpsConfigMethods, wpsState, wpsLocked;
    std::string security, akms, pairwiseCiphers, groupCipher, pmf;
    long long firstMs = 0, lastMs = 0;
};

std::map<uint64_t, Device> g_devices;  // guarded by g_sets_mu
long long g_start_ms = 0;

long long nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

long long bootMs() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

uint64_t key(const uint8_t *p) {
    uint64_t k = 0;
    for (int i = 0; i < 6; i++) k = (k << 8) | p[i];
    return k;
}

std::string keyMac(uint64_t k) {
    uint8_t b[6];
    for (int i = 5; i >= 0; i--) { b[i] = k & 0xff; k >>= 8; }
    return mac(b);
}

bool isUnicast(const uint8_t *p) { return (p[0] & 0x01) == 0; }

// Printable, single-line, separator-free text for the TSV export.
std::string clean(const uint8_t *p, size_t n) {
    std::string out;
    for (size_t i = 0; i < n && out.size() < 64; i++) {
        uint8_t c = p[i];
        out += (c >= 0x20 && c != 0x7f && c != '\t' && c != '|') ? static_cast<char>(c) : '?';
    }
    return out;
}

bool allZero(const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) if (p[i]) return false;
    return true;
}

Device *device(uint64_t k, long long t) {  // caller holds g_sets_mu
    auto it = g_devices.find(k);
    if (it == g_devices.end()) {
        if (g_devices.size() >= MAX_DEVICES) return nullptr;
        it = g_devices.emplace(k, Device{}).first;
        it->second.firstMs = t;
        g_stats[DEVICES] = static_cast<long long>(g_devices.size());
    }
    it->second.lastMs = t;
    return &it->second;
}

// Signal in dBm from the PHY status, per Realtek phydm (Jaguar series):
// per-path power = (gain_trsw & 0x7F) - 110. Only frames that carry a PHY
// status have one (A-MPDU subframes don't); absurd values are dropped.
int frameRssi(const rx_pkt_attrib &a) {
    if (!a.physt) return -1000;
    int best = -1000;
    for (int i = 0; i < 2; i++) {
        if (a.rssi[i] == 0) continue;
        int dbm = (a.rssi[i] & 0x7F) - 110;
        if (dbm >= -100 && dbm <= -5 && dbm > best) best = dbm;
    }
    return best;
}

struct Ies {
    std::string ssid;
    bool hasSsid = false, wildcard = false;
    int channel = 0;  // DS Parameter Set (2.4 GHz) or HT Operation primary channel
    std::string wpsName, wpsManufacturer, wpsModel, wpsModelNumber, wpsSerial;
    std::string wpsPrimaryType, wpsConfigMethods, wpsState, wpsLocked;
    std::string security, akms, pairwiseCiphers, groupCipher, pmf;
};

std::string hexValue(const uint8_t *p, size_t n) {
    std::string value;
    char b[3];
    for (size_t i = 0; i < n; i++) {
        std::snprintf(b, sizeof b, "%02x", p[i]);
        value += b;
    }
    return value;
}

std::string suite(const uint8_t *p, bool akm) {
    if (!((p[0] == 0x00 && p[1] == 0x0f && p[2] == 0xac) ||
          (p[0] == 0x00 && p[1] == 0x50 && p[2] == 0xf2))) return hexValue(p, 4);
    if (akm) {
        switch (p[3]) {
            case 1: return "802.1X"; case 2: return "PSK"; case 3: return "FT-802.1X";
            case 4: return "FT-PSK"; case 5: return "802.1X-SHA256"; case 6: return "PSK-SHA256";
            case 8: return "SAE"; case 9: return "FT-SAE"; case 11: return "Suite-B";
            case 12: return "Suite-B-192"; case 18: return "OWE";
            default: return hexValue(p, 4);
        }
    }
    switch (p[3]) {
        case 1: return "WEP40"; case 2: return "TKIP"; case 4: return "CCMP-128";
        case 5: return "WEP104"; case 8: return "GCMP-128"; case 9: return "GCMP-256";
        case 10: return "CCMP-256"; default: return hexValue(p, 4);
    }
}

void parseSecurity(const uint8_t *p, size_t n, Ies &out, const char *protocol) {
    if (n < 8 || p[0] != 1 || p[1] != 0) return;
    size_t i = 2;
    const std::string group = suite(p + i, false); i += 4;
    auto list = [&](bool akm, std::string &target) -> bool {
        if (i + 2 > n) return false;
        const size_t count = p[i] | (static_cast<size_t>(p[i + 1]) << 8); i += 2;
        if (count > (n - i) / 4) return false;
        for (size_t j = 0; j < count; j++, i += 4) {
            const auto name = suite(p + i, akm);
            if (target.find(name) == std::string::npos) {
                if (!target.empty()) target += "+";
                target += name;
            }
        }
        return true;
    };
    std::string pairwise, akms;
    if (!list(false, pairwise) || !list(true, akms)) return;
    if (!out.security.empty()) out.security += "+";
    out.security += protocol;
    out.groupCipher = group;
    out.pairwiseCiphers = pairwise;
    out.akms = akms;
    if (i + 2 <= n && protocol[0] == 'R') {
        const int caps = p[i] | (p[i + 1] << 8);
        out.pmf = (caps & 0x40) ? "required" : ((caps & 0x80) ? "capable" : "disabled");
    }
}

// WPS attributes (big-endian type/length) inside vendor IE 00:50:F2 type 4.
void parseWps(const uint8_t *p, size_t n, Ies &out) {
    size_t i = 0;
    while (i + 4 <= n) {
        const int type = (p[i] << 8) | p[i + 1], len = (p[i + 2] << 8) | p[i + 3];
        if (i + 4 + len > n) break;
        const uint8_t *v = p + i + 4;
        if (type == 0x1011) out.wpsName = clean(v, len);
        else if (type == 0x1021) out.wpsManufacturer = clean(v, len);
        else if (type == 0x1023) out.wpsModel = clean(v, len);
        else if (type == 0x1024) out.wpsModelNumber = clean(v, len);
        else if (type == 0x1042) out.wpsSerial = clean(v, len);
        else if (type == 0x1054 && len == 8) out.wpsPrimaryType =
            std::to_string((v[0] << 8) | v[1]) + ":" + hexValue(v + 2, 4) + ":" +
            std::to_string((v[6] << 8) | v[7]);
        else if (type == 0x1008 && len == 2) out.wpsConfigMethods = hexValue(v, 2);
        else if (type == 0x1044 && len == 1) out.wpsState = v[0] == 2 ? "configured" : (v[0] == 1 ? "unconfigured" : hexValue(v, 1));
        else if (type == 0x1057 && len == 1) out.wpsLocked = v[0] ? "yes" : "no";
        i += 4 + len;
    }
}

Ies parseIes(const uint8_t *p, size_t n) {
    Ies out;
    size_t i = 0;
    while (i + 2 <= n) {
        const int id = p[i], len = p[i + 1];
        if (i + 2 + len > n) break;
        const uint8_t *v = p + i + 2;
        if (id == 0 && !out.hasSsid) {
            out.hasSsid = true;
            out.wildcard = len == 0 || allZero(v, len);
            if (!out.wildcard) out.ssid = clean(v, len);
        } else if (id == 3 && len >= 1) {
            out.channel = v[0];
        } else if (id == 61 && len >= 1 && out.channel == 0) {
            out.channel = v[0];
        } else if (id == 48) {
            parseSecurity(v, len, out, "RSN");
        } else if (id == 221 && len >= 4 && v[0] == 0x00 && v[1] == 0x50 && v[2] == 0xF2 && v[3] == 0x01) {
            parseSecurity(v + 4, len - 4, out, "WPA");
        } else if (id == 221 && len >= 4 && v[0] == 0x00 && v[1] == 0x50 && v[2] == 0xF2 && v[3] == 0x04) {
            parseWps(v + 4, len - 4, out);
        }
        i += 2 + len;
    }
    return out;
}

void applyWps(Device *dv, const Ies &ies) {
    if (!ies.wpsName.empty()) dv->wpsName = ies.wpsName;
    if (!ies.wpsManufacturer.empty()) dv->wpsManufacturer = ies.wpsManufacturer;
    if (!ies.wpsModel.empty()) dv->wpsModel = ies.wpsModel;
    if (!ies.wpsModelNumber.empty()) dv->wpsModelNumber = ies.wpsModelNumber;
    if (!ies.wpsSerial.empty()) dv->wpsSerial = ies.wpsSerial;
    if (!ies.wpsPrimaryType.empty()) dv->wpsPrimaryType = ies.wpsPrimaryType;
    if (!ies.wpsConfigMethods.empty()) dv->wpsConfigMethods = ies.wpsConfigMethods;
    if (!ies.wpsState.empty()) dv->wpsState = ies.wpsState;
    if (!ies.wpsLocked.empty()) dv->wpsLocked = ies.wpsLocked;
    if (!ies.security.empty()) {
        dv->security = ies.security;
        dv->akms = ies.akms;
        dv->pairwiseCiphers = ies.pairwiseCiphers;
        dv->groupCipher = ies.groupCipher;
        dv->pmf = ies.pmf;
    }
}

// Count cleartext EAPOL-Key data frames, including QoS and four-address data.
// This is evidence of key exchange traffic, not proof of a complete handshake.
bool isEapolKey(const uint8_t *d, size_t n) {
    if ((d[0] & 0x0c) != 0x08 || (d[1] & 0x40)) return false;
    const bool qos = (d[0] & 0x80) != 0;
    const size_t header = 24 + ((d[1] & 0x03) == 0x03 ? 6 : 0) +
        (qos ? 2 : 0) + (qos && (d[1] & 0x80) ? 4 : 0);
    if (n < header + 8 + 4) return false;
    const uint8_t *llc = d + header;
    const uint8_t eapolLlc[] = {0xaa, 0xaa, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8e};
    if (!std::equal(eapolLlc, eapolLlc + 8, llc)) return false;
    const uint8_t *e = llc + 8;
    const size_t declared = (static_cast<size_t>(e[2]) << 8) | e[3];
    return e[1] == 3 && declared >= 95 && n >= header + 8 + 4 + declared;
}

void onFrame(const Packet &packet) {
    const auto &a = packet.RxAtrib;
    if (a.crc_err || a.icv_err) { g_stats[BAD_FCS_OR_SHORT]++; return; }
    writePcap(packet);
    const auto &raw = packet.Data;
    // Data includes the 4-byte FCS; parse only the frame itself.
    if (raw.size() < 4 + 10) { g_stats[BAD_FCS_OR_SHORT]++; return; }
    const uint8_t *d = raw.data();
    const size_t n = raw.size() - 4;

    g_stats[TOTAL]++;
    const int ch = static_cast<int>(g_stats[CURRENT_CHANNEL].load());
    const int rssi = frameRssi(a);
    if (rssi == -1000) {
        g_stats[NO_RSSI]++;
    } else {
        if (rssi < g_stats[RSSI_MIN]) g_stats[RSSI_MIN] = rssi;
        if (rssi > g_stats[RSSI_MAX]) g_stats[RSSI_MAX] = rssi;
    }

    const uint8_t fc0 = d[0], fc1 = d[1];
    const int type = (fc0 >> 2) & 0x3, subtype = (fc0 >> 4) & 0xF;
    const long long t = nowMs() - g_start_ms;

    std::lock_guard<std::mutex> lock(g_sets_mu);
    if (ch > 0 && ch < 256) g_per_channel[ch]++;
    if (type == 1) { g_stats[CTRL]++; return; }  // control frames carry no transmitter identity worth keeping
    if (n < 24) { g_stats[BAD_FCS_OR_SHORT]++; return; }
    const uint8_t *addr1 = d + 4, *addr2 = d + 10, *addr3 = d + 16;

    auto seen = [&](Device *dv) {
        if (!dv) return;
        dv->frames++;
        if (rssi > dv->bestRssi) dv->bestRssi = rssi;
        if (rssi != -1000) {
            dv->lastRssi = rssi;
            dv->lastRssiBootMs = bootMs();
            dv->lastRssiChannel = ch;
        }
        if (ch > 0) dv->channels.insert(ch);
    };

    if (type == 0) {
        g_stats[MGMT]++;
        if (subtype == 8 || subtype == 5) {  // beacon / probe response: addr2 is the AP
            if (subtype == 8) g_stats[BEACON]++; else g_stats[PROBE_RESP]++;
            if (n < 36) return;
            const Ies ies = parseIes(d + 36, n - 36);
            if (subtype == 8 && ies.channel > 0)
                g_stats[ies.channel == ch ? BEACON_CH_MATCH : BEACON_CH_OTHER]++;
            Device *dv = device(key(addr2), t);
            seen(dv);
            if (dv) {
                dv->roles |= ROLE_AP;
                if (!ies.ssid.empty()) dv->ssid = ies.ssid;
                applyWps(dv, ies);
                if (ies.security.empty() && dv->security.empty())
                    dv->security = ((d[34] | (d[35] << 8)) & 0x10) ? "Protected (legacy/unknown)" : "Open";
            }
        } else if (subtype == 4) {  // probe request: addr2 is the searching client
            g_stats[PROBE_REQ]++;
            const Ies ies = parseIes(d + 24, n - 24);
            if (!ies.wildcard && ies.hasSsid) g_stats[PROBE_REQ_DIRECTED]++;
            if (g_probe_sources.insert(mac(addr2)).second && (addr2[0] & 0x02))
                g_stats[PROBE_REQ_LOCAL_ADMIN]++;
            g_stats[PROBE_REQ_SOURCES] = static_cast<long long>(g_probe_sources.size());
            Device *dv = device(key(addr2), t);
            seen(dv);
            if (dv) {
                dv->roles |= ROLE_CLIENT;
                dv->probeReqs++;
                if (ies.wildcard) dv->wildcardProbe = true;
                else if (!ies.ssid.empty() && dv->probed.size() < MAX_NAMES) dv->probed.insert(ies.ssid);
                applyWps(dv, ies);
            }
        } else if ((subtype == 0 || subtype == 2) && isUnicast(addr1)) {
            // (re)association request: client addr2 joins AP addr1; body has the SSID.
            const size_t fixed = subtype == 0 ? 4 : 10;
            Device *dv = device(key(addr2), t);
            seen(dv);
            if (dv) {
                dv->roles |= ROLE_CLIENT;
                if (dv->bssids.size() < MAX_NAMES) dv->bssids.insert(mac(addr1));
                if (n > 24 + fixed) {
                    const Ies ies = parseIes(d + 24 + fixed, n - 24 - fixed);
                    if (!ies.ssid.empty() && dv->probed.size() < MAX_NAMES) dv->probed.insert(ies.ssid);
                    applyWps(dv, ies);
                }
            }
        } else if (isUnicast(addr2)) {
            seen(device(key(addr2), t));
        }
        (void)addr3;
    } else if (type == 2) {
        g_stats[DATA]++;
        if (isEapolKey(d, n)) g_stats[EAPOL_KEY_FRAMES]++;
        const int ds = fc1 & 0x3;
        const long long len = static_cast<long long>(n);
        if (ds == 0x1 && isUnicast(addr2)) {  // ToDS: client addr2 -> AP addr1
            Device *dv = device(key(addr2), t);
            seen(dv);
            if (dv) {
                dv->roles |= ROLE_CLIENT;
                dv->bytesUp += len;
                if (dv->bssids.size() < MAX_NAMES) dv->bssids.insert(mac(addr1));
                g_clients.insert(mac(addr2) + ">" + mac(addr1));
                g_stats[CLIENTS_TO_AP] = static_cast<long long>(g_clients.size());
            }
            Device *ap = device(key(addr1), t);
            if (ap) ap->roles |= ROLE_AP;
        } else if (ds == 0x2 && isUnicast(addr1)) {  // FromDS: AP addr2 -> client addr1
            Device *ap = device(key(addr2), t);
            seen(ap);
            if (ap) ap->roles |= ROLE_AP;
            Device *dv = device(key(addr1), t);
            if (dv) {
                dv->roles |= ROLE_CLIENT;
                dv->bytesDown += len;
                if (dv->bssids.size() < MAX_NAMES) dv->bssids.insert(mac(addr2));
                g_clients.insert(mac(addr1) + ">" + mac(addr2));
                g_stats[CLIENTS_TO_AP] = static_cast<long long>(g_clients.size());
            }
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
    g_devices.clear();
    g_start_ms = nowMs();
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
                                               jstring pcapPath, jintArray channels, jint dwellMs, jint totalMs) {
    if (g_running.exchange(true)) return env->NewStringUTF("a test is already running");
    g_stop = false;
    resetStats();
    g_trace_path = jstr(env, tracePath);
    const std::string pcap_path = jstr(env, pcapPath);
    std::vector<int> chans(env->GetArrayLength(channels));
    env->GetIntArrayRegion(channels, 0, static_cast<jsize>(chans.size()), chans.data());
    std::string result;
    if (chans.empty()) {
        result = "no channels given";
    } else if (!openPcap(pcap_path)) {
        result = "could not create PCAP file";
    } else {
        try {
            result = runProbe(fd, jstr(env, lockDir), chans, dwellMs, totalMs);
        } catch (const std::exception &e) {
            result = std::string("exception: ") + e.what();
        }
    }
    closePcap();
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

// One line per device, tab-separated (UsbMonitorDevices.parse — keep in sync):
// mac, roles, frames, probeReqs, bytesUp, bytesDown, bestRssi (or empty),
// channels (comma), ssid, probed (|-joined), wildcard (0/1), bssids (comma),
// wpsName, wpsManufacturer, wpsModel, firstMs, lastMs,
// lastRssi, lastRssiBootMs (Android elapsed-realtime clock), lastRssiChannel,
// wpsPrimaryType, wpsModelNumber, wpsSerial, wpsConfigMethods, wpsState,
// wpsLocked, security, akms, pairwiseCiphers, groupCipher, pmf
extern "C" JNIEXPORT jstring JNICALL
Java_com_whyfi_app_usbmon_UsbMonitorNative_devices(JNIEnv *env, jobject) {
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_sets_mu);
        for (const auto &[k, dv] : g_devices) {
            auto join = [](const std::set<std::string> &xs, char sep) {
                std::string r;
                for (const auto &x : xs) { if (!r.empty()) r += sep; r += x; }
                return r;
            };
            std::string chans;
            for (int c : dv.channels) { if (!chans.empty()) chans += ','; chans += std::to_string(c); }
            out += keyMac(k) + '\t' + std::to_string(dv.roles) + '\t' + std::to_string(dv.frames) + '\t' +
                   std::to_string(dv.probeReqs) + '\t' + std::to_string(dv.bytesUp) + '\t' +
                   std::to_string(dv.bytesDown) + '\t' + (dv.bestRssi == -1000 ? "" : std::to_string(dv.bestRssi)) +
                   '\t' + chans + '\t' + dv.ssid + '\t' + join(dv.probed, '|') + '\t' +
                   (dv.wildcardProbe ? "1" : "0") + '\t' + join(dv.bssids, ',') + '\t' + dv.wpsName + '\t' +
                   dv.wpsManufacturer + '\t' + dv.wpsModel + '\t' + std::to_string(dv.firstMs) + '\t' +
                   std::to_string(dv.lastMs) + '\t' +
                   (dv.lastRssi == -1000 ? "" : std::to_string(dv.lastRssi)) + '\t' +
                   std::to_string(dv.lastRssiBootMs) + '\t' +
                   std::to_string(dv.lastRssiChannel) + '\t' + dv.wpsPrimaryType + '\t' +
                   dv.wpsModelNumber + '\t' + dv.wpsSerial + '\t' + dv.wpsConfigMethods + '\t' +
                   dv.wpsState + '\t' + dv.wpsLocked + '\t' + dv.security + '\t' + dv.akms + '\t' +
                   dv.pairwiseCiphers + '\t' + dv.groupCipher + '\t' + dv.pmf + '\n';
        }
    }
    // NewStringUTF wants modified UTF-8; clean() already reduced names to
    // printable bytes, but non-ASCII ones may still be invalid UTF-8 — go
    // through a byte array so the JVM decodes (and replaces) properly.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    jclass strClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(strClass, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strClass);
    return result;
}
