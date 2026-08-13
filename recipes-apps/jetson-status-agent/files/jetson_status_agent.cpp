/*
 * jetson_status_agent.cpp — publishes this board's health onto the vehicle log feed.
 *
 * The dashboard shows three nodes on one Adafruit IO feed (itiros/feeds/logs), in
 * one five-field line, so there is one parser rather than three:
 *
 *     ID:A2 | CODE:60 | SEV:0 | TS:261542 | AUX:2B455F00
 *
 * ID:A2 is this board. ID:01 is the Edge (ESP32 + STM32) and ID:A0 is the Cluster's
 * Yocto guest, which publishes the SAME 0x58 SYS HEALTH layout used below — the ECU
 * id is the only thing that separates the two summaries. See README.md for the full
 * contract, including every AUX layout.
 *
 * NO MQTT LIBRARY. Publishing spawns mosquitto_pub, which is already in the image
 * for ivi-ota-agent, exactly as busmon does on the Cluster. That keeps this a single
 * translation unit with nothing to link and nothing to keep in step with a library's
 * ABI, on a board whose whole job is something else.
 *
 * Credentials are read from /etc/ivi-ota/agent.conf — the file the OTA agent already
 * has. There is deliberately no second copy of the key to install, rotate or leak.
 *
 * RATE LIMIT — the reason the interval has a floor in code. Adafruit IO allows 30
 * data points per minute ACCOUNT-WIDE, shared with the ESP32 (which paces itself at
 * one publish per 3 s) and the Cluster. This agent's steady state is two points a
 * minute. Blow the quota and you throttle the ESP32's logs and the OTA feed with it.
 *
 * Build:  ${CXX} -std=c++17 -o jetson_status_agent jetson_status_agent.cpp
 * Usage:  jetson_status_agent --probe          what can this board read?
 *         jetson_status_agent --dry-run        print lines, publish nothing
 *         jetson_status_agent -i 60            run (the service does this)
 */

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <csignal>
#include <getopt.h>
#include <glob.h>
#include <spawn.h>
#include <sys/statvfs.h>
#include <time.h>
#include <unistd.h>

extern "C" char **environ;

// ---------------------------------------------------------------- the protocol

static constexpr uint8_t ECU_ID = 0xA2;   // this board's id in the log line

enum Code : uint8_t {
    CODE_BOOT       = 0x30,   // shared with the ESP32
    CODE_SYS_HEALTH = 0x58,   // shared with the Cluster guest — same AUX layout
    CODE_GPU_LOAD   = 0x60,
    CODE_THERMAL    = 0x61,
    CODE_DISK_LOW   = 0x62,
    // 0x63-0x6F are free. Adding one means a name and an AUX decoder on the
    // dashboard side too, so say which code before you use it.
};

enum Sev : int { SEV_INFO = 0, SEV_WARN = 1, SEV_ERROR = 2, SEV_FATAL = 3 };

// Below this the account's 30 points/minute stop being ours to spend. A floor in
// code rather than a line in the docs, because the docs are not what runs.
static constexpr int MIN_INTERVAL_S = 30;

static constexpr const char *DEFAULT_CONF = "/etc/ivi-ota/agent.conf";

// ------------------------------------------------------------------- utilities

static uint64_t mono_ms()
{
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return uint64_t(ts.tv_sec) * 1000ULL + uint64_t(ts.tv_nsec) / 1000000ULL;
}

// Percentages are packed into one byte. 104 % out of a rounding error would arrive
// intact and overfill a gauge on the dashboard, so it is capped here rather than
// trusted. -1 means "could not read", and is kept as -1 until packing time.
static int clamp_pct(double v)
{
    if (std::isnan(v)) return 0;
    long r = std::lround(v);
    return int(std::max(0L, std::min(100L, r)));
}

static int clamp_byte(double v)
{
    if (std::isnan(v)) return 0;
    long r = std::lround(v);
    return int(std::max(0L, std::min(255L, r)));
}

static bool read_file(const char *path, std::string &out)
{
    FILE *f = std::fopen(path, "r");
    if (!f) return false;
    char buf[4096];
    size_t n = std::fread(buf, 1, sizeof(buf) - 1, f);
    std::fclose(f);
    buf[n] = '\0';
    out.assign(buf, n);
    return true;
}

static std::string trim(const std::string &s)
{
    size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return "";
    size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}

// ------------------------------------------------------------------ the readers
//
// Every reader returns -1 when the figure cannot be read, never a plausible zero:
// --probe prints that as "n/a" so a missing sensor is visibly missing rather than
// quietly reported as idle. Only at packing time does -1 become 0 on the wire.

static double uptime_s()
{
    std::string s;
    if (!read_file("/proc/uptime", s)) return -1;
    return std::atof(s.c_str());
}

// Busy percentage over the interval SINCE THE LAST CALL.
//
// /proc/stat holds monotonic counters, not a rate, so one read can only give the
// average since boot — which on a board that was busy at startup and idle ever
// since reports "busy" forever. The delta against the previous sample is the only
// number that describes the interval the summary covers. The first call has nothing
// to subtract and reports the since-boot average, which is the honest answer for the
// one sample where no interval exists yet.
static double cpu_used_pct()
{
    std::string s;
    if (!read_file("/proc/stat", s)) return -1;

    unsigned long long u = 0, n = 0, sy = 0, id = 0, io = 0, irq = 0, sirq = 0, st = 0;
    if (std::sscanf(s.c_str(), "cpu %llu %llu %llu %llu %llu %llu %llu %llu",
                    &u, &n, &sy, &id, &io, &irq, &sirq, &st) < 4)
        return -1;

    const unsigned long long idle  = id + io;
    const unsigned long long total = u + n + sy + idle + irq + sirq + st;

    static unsigned long long prev_total = 0, prev_idle = 0;
    const unsigned long long dt = total - prev_total;
    const unsigned long long di = idle  - prev_idle;
    prev_total = total;
    prev_idle  = idle;

    if (dt == 0 || di > dt) return 0;      // two reads inside one jiffy
    return 100.0 - (double(di) * 100.0 / double(dt));
}

// Used share of RAM, from MemAvailable. NOT MemTotal - MemFree: page cache is not
// memory pressure, and counting it makes a healthy board look like it is about to
// be OOM-killed.
static double mem_used_pct()
{
    std::string s;
    if (!read_file("/proc/meminfo", s)) return -1;

    auto field = [&](const char *key) -> long long {
        size_t p = s.find(key);
        if (p == std::string::npos) return -1;
        p = s.find(':', p);
        if (p == std::string::npos) return -1;
        return std::atoll(s.c_str() + p + 1);          // kB
    };

    const long long total = field("MemTotal");
    if (total <= 0) return -1;
    long long avail = field("MemAvailable");
    if (avail < 0) {                                   // very old kernels
        const long long fr = field("MemFree"), bu = field("Buffers"), ca = field("Cached");
        avail = std::max(0LL, fr) + std::max(0LL, bu) + std::max(0LL, ca);
    }
    return double(total - avail) * 100.0 / double(total);
}

// (used %, free MiB) for the root filesystem. f_bavail, not f_bfree: the blocks
// reserved for root are not free space to the process that runs out of disk.
static void disk_usage(double &used_pct, uint32_t &free_mib)
{
    used_pct = -1;
    free_mib = 0;
    struct statvfs st {};
    if (statvfs("/", &st) != 0) return;
    const double total = double(st.f_blocks) * double(st.f_frsize);
    const double freeb = double(st.f_bavail) * double(st.f_frsize);
    if (total <= 0) return;
    used_pct = (total - freeb) * 100.0 / total;
    const double mib = freeb / (1024.0 * 1024.0);
    free_mib = uint32_t(std::min(mib, double(0xFFFFFF)));
}

static bool first_glob_match(const char *pattern, std::string &out)
{
    glob_t g{};
    if (glob(pattern, 0, nullptr, &g) != 0) { globfree(&g); return false; }
    bool found = false;
    if (g.gl_pathc > 0) { out = g.gl_pathv[0]; found = true; }
    globfree(&g);
    return found;
}

// Which sysfs node carries GPU load depends on the JetPack/meta-tegra generation,
// so several are tried and the first that answers wins. All of them report TENTHS
// of a percent (0-1000), not a percent.
static const char *GPU_LOAD_PATHS[] = {
    "/sys/devices/gpu.0/load",
    "/sys/devices/platform/gpu.0/load",
    "/sys/devices/platform/*.gpu/load",
    "/sys/devices/platform/*.ga10b/load",
    "/sys/devices/platform/bus@0/*.gpu/load",
    nullptr,
};

static const char *g_gpu_source = "none";

static double gpu_load_pct()
{
    for (int i = 0; GPU_LOAD_PATHS[i]; ++i) {
        std::string path;
        if (!first_glob_match(GPU_LOAD_PATHS[i], path)) continue;
        std::string s;
        if (!read_file(path.c_str(), s)) continue;
        g_gpu_source = GPU_LOAD_PATHS[i];
        return std::atoi(s.c_str()) / 10.0;
    }
    return -1;
}

// The share of system RAM held by GPU allocations. The Jetson has no separate VRAM
// — memory is unified — so this is a slice of the same RAM mem_used_pct() measures,
// not a second pool. It is still worth its own field: a pipeline leaking device
// memory moves this one and not the other.
//
// Needs debugfs mounted (mount -t debugfs none /sys/kernel/debug) and root. Absent
// is normal on a hardened image; the figure then reads 0 and the README says so.
static double gpu_mem_pct()
{
    std::string s;
    if (!read_file("/sys/kernel/debug/nvmap/iovmm/clients", s)) return -1;

    long long total_kb = 0;
    size_t pos = 0;
    while (pos < s.size()) {
        size_t eol = s.find('\n', pos);
        if (eol == std::string::npos) eol = s.size();
        const std::string line = s.substr(pos, eol - pos);
        pos = eol + 1;
        // The size column is the last field and is suffixed with K.
        const size_t sp = line.find_last_of(" \t");
        if (sp == std::string::npos) continue;
        const std::string tok = trim(line.substr(sp + 1));
        if (tok.size() < 2 || tok.back() != 'K') continue;
        total_kb += std::atoll(tok.c_str());
    }
    if (total_kb <= 0) return -1;

    std::string mi;
    if (!read_file("/proc/meminfo", mi)) return -1;
    const long long mem_total = std::atoll(mi.c_str() + mi.find(':') + 1);
    if (mem_total <= 0) return -1;
    return double(total_kb) * 100.0 / double(mem_total);
}

// The hottest zone that is actually a compute die. Zone NAMES differ across Tegra
// generations, so it matches on the type string rather than on a fixed index — a
// fixed thermal_zone0 is how you end up publishing the PMIC's temperature.
static double soc_temp_c()
{
    glob_t g{};
    if (glob("/sys/class/thermal/thermal_zone*", 0, nullptr, &g) != 0) {
        globfree(&g);
        return -1;
    }

    double best = -1;
    for (size_t i = 0; i < g.gl_pathc; ++i) {
        std::string type, milli;
        if (!read_file((std::string(g.gl_pathv[i]) + "/type").c_str(), type)) continue;
        for (char &c : type) c = char(toupper((unsigned char)c));
        if (type.find("CPU") == std::string::npos && type.find("GPU") == std::string::npos
            && type.find("SOC") == std::string::npos && type.find("TJ") == std::string::npos
            && type.find("AO") == std::string::npos)
            continue;
        if (!read_file((std::string(g.gl_pathv[i]) + "/temp").c_str(), milli)) continue;
        best = std::max(best, std::atof(milli.c_str()) / 1000.0);
    }
    globfree(&g);
    return best;
}

// ------------------------------------------------------------------- the packing

static uint32_t pack_sys_health(int up_min, double mem, double cpu)
{
    return (uint32_t(up_min & 0xFFFF) << 16)
         | (uint32_t(clamp_pct(mem < 0 ? 0 : mem)) << 8)
         |  uint32_t(clamp_pct(cpu < 0 ? 0 : cpu));
}

static uint32_t pack_gpu_load(double gpu, double gpu_mem, double temp)
{
    return (uint32_t(clamp_pct(gpu     < 0 ? 0 : gpu))     << 24)
         | (uint32_t(clamp_pct(gpu_mem < 0 ? 0 : gpu_mem)) << 16)
         | (uint32_t(clamp_byte(temp   < 0 ? 0 : temp))    <<  8);
}

static uint32_t pack_disk(double used, uint32_t free_mib)
{
    return (uint32_t(clamp_pct(used < 0 ? 0 : used)) << 24) | (free_mib & 0xFFFFFF);
}

// ------------------------------------------------------------------ the publisher

struct Config {
    std::string user = "itiros";
    std::string key;
    std::string host = "io.adafruit.com";
    std::string port = "8883";
};

// agent.conf is shell syntax and is SOURCED by ivi_ota_agent.sh, so it is read the
// same way here: KEY=value, # comments, optional quotes. Only the four keys this
// agent needs are taken; everything else in the file belongs to the OTA agent.
static bool load_conf(const char *path, Config &cfg, bool verbose)
{
    std::string s;
    if (!read_file(path, s)) {
        std::fprintf(stderr, "[status] cannot read %s — no credentials\n", path);
        return false;
    }

    size_t pos = 0;
    while (pos < s.size()) {
        size_t eol = s.find('\n', pos);
        if (eol == std::string::npos) eol = s.size();
        std::string line = trim(s.substr(pos, eol - pos));
        pos = eol + 1;

        if (line.empty() || line[0] == '#') continue;
        if (line.rfind("export ", 0) == 0) line = trim(line.substr(7));

        const size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        const std::string k = trim(line.substr(0, eq));
        std::string v = trim(line.substr(eq + 1));
        if (v.size() >= 2 && (v.front() == '"' || v.front() == '\'') && v.back() == v.front())
            v = v.substr(1, v.size() - 2);

        if      (k == "AIO_USER") cfg.user = v;
        else if (k == "AIO_KEY")  cfg.key  = v;
        else if (k == "AIO_HOST") cfg.host = v;
        else if (k == "AIO_PORT") cfg.port = v;
    }

    if (cfg.key.empty()) {
        std::fprintf(stderr, "[status] %s has no AIO_KEY\n", path);
        return false;
    }
    if (verbose)
        std::printf("[status] broker %s:%s as %s, topic %s/feeds/logs\n",
                    cfg.host.c_str(), cfg.port.c_str(), cfg.user.c_str(), cfg.user.c_str());
    return true;
}

static bool g_dry_run = false;

// Forked and not waited on: a blocking publish would stall the sample loop, and a
// TLS handshake to Adafruit can take seconds on a bad link. SIGCHLD is ignored in
// main() so the children are reaped by init rather than piling up as zombies.
//
// The key goes in argv, so it is visible in `ps` to anything already running as
// root on this board. That is the same exposure ivi_ota_agent.sh and busmon already
// accept, and mosquitto_pub offers no file-based password option.
static void publish(const Config &cfg, uint8_t code, int sev, uint32_t aux)
{
    char payload[128];
    std::snprintf(payload, sizeof(payload),
                  "ID:%02X | CODE:%02X | SEV:%d | TS:%llu | AUX:%08X",
                  ECU_ID, code, sev, (unsigned long long)mono_ms(), aux);

    std::printf("%s\n", payload);
    std::fflush(stdout);
    if (g_dry_run) return;

    const std::string topic = cfg.user + "/feeds/logs";
    char *const argv[] = {
        (char *)"mosquitto_pub",
        (char *)"-h", (char *)cfg.host.c_str(),
        (char *)"-p", (char *)cfg.port.c_str(),
        (char *)"--capath", (char *)"/etc/ssl/certs",
        (char *)"-u", (char *)cfg.user.c_str(),
        (char *)"-P", (char *)cfg.key.c_str(),
        (char *)"-t", (char *)topic.c_str(),
        (char *)"-m", payload,
        nullptr
    };

    pid_t pid;
    if (posix_spawnp(&pid, "mosquitto_pub", nullptr, nullptr, argv, environ) != 0)
        std::fprintf(stderr, "[status] could not spawn mosquitto_pub\n");
}

// ---------------------------------------------------------------------- the loop

struct Sample {
    int      up_min   = 0;
    double   cpu      = -1;
    double   mem      = -1;
    double   gpu      = -1;
    double   gpu_mem  = -1;
    double   temp     = -1;
    double   disk_used = -1;
    uint32_t disk_free_mib = 0;
};

static Sample take_sample()
{
    Sample s;
    const double up = uptime_s();
    s.up_min  = up < 0 ? 0 : int(up / 60.0);
    s.cpu     = cpu_used_pct();
    s.mem     = mem_used_pct();
    s.gpu     = gpu_load_pct();
    s.gpu_mem = gpu_mem_pct();
    s.temp    = soc_temp_c();
    disk_usage(s.disk_used, s.disk_free_mib);
    return s;
}

struct Thresholds {
    double temp_warn = 80;
    double temp_crit = 95;
    double disk_warn = 90;
};

// Edge-triggered state. -1 means "nothing said yet", so a healthy boot stays quiet
// instead of announcing that nothing is wrong.
struct EventState {
    int thermal = -1;      // -1 unknown, 0 ok, 1 warn, 2 crit
    int disk    = -1;      // -1 unknown, 0 ok, 1 low
};

static void publish_periodic(const Config &cfg, const Sample &s)
{
    publish(cfg, CODE_SYS_HEALTH, SEV_INFO, pack_sys_health(s.up_min, s.mem, s.cpu));
    publish(cfg, CODE_GPU_LOAD,   SEV_INFO, pack_gpu_load(s.gpu, s.gpu_mem, s.temp));
}

// Only on a transition. A DISK LOW every minute for a week is ten thousand identical
// rows, and the one that matters — where it crossed — is then impossible to find.
static void publish_events(const Config &cfg, const Sample &s,
                           const Thresholds &th, EventState &st)
{
    if (s.temp >= 0) {
        const int level = s.temp >= th.temp_crit ? 2 : s.temp >= th.temp_warn ? 1 : 0;
        if (level != st.thermal) {
            const int sev = level == 2 ? SEV_ERROR : level == 1 ? SEV_WARN : SEV_INFO;
            // The recovery carries the temperature it came back TO. "Back under the
            // limit" is only useful if it says what it came back to.
            publish(cfg, CODE_THERMAL, sev, uint32_t(clamp_byte(s.temp)));
            st.thermal = level;
        }
    }

    if (s.disk_used >= 0) {
        const int level = s.disk_used >= th.disk_warn ? 1 : 0;
        if (level != st.disk) {
            publish(cfg, CODE_DISK_LOW, level ? SEV_WARN : SEV_INFO,
                    pack_disk(s.disk_used, s.disk_free_mib));
            st.disk = level;
        }
    }
}

static void probe()
{
    auto show = [](double v, const char *unit) {
        static char buf[64];
        if (v < 0) std::snprintf(buf, sizeof(buf), "n/a");
        else       std::snprintf(buf, sizeof(buf), "%.0f%s", v, unit);
        return std::string(buf);
    };

    cpu_used_pct();                    // prime the delta
    sleep(1);                          // so the CPU figure describes this second
    const Sample s = take_sample();

    std::printf("--- what this board can read ---\n");
    std::printf("  uptime      %d min\n", s.up_min);
    std::printf("  cpu         %s      (/proc/stat delta)\n", show(s.cpu, " %").c_str());
    std::printf("  memory      %s      (/proc/meminfo MemAvailable)\n", show(s.mem, " %").c_str());
    std::printf("  disk        %s used, %u MiB free\n", show(s.disk_used, " %").c_str(), s.disk_free_mib);
    std::printf("  gpu load    %s      (%s)\n", show(s.gpu, " %").c_str(), g_gpu_source);
    std::printf("  gpu memory  %s      (/sys/kernel/debug/nvmap — needs debugfs + root)\n",
                show(s.gpu_mem, " %").c_str());
    std::printf("  soc temp    %s      (hottest CPU/GPU/SOC/TJ thermal zone)\n",
                show(s.temp, " C").c_str());
    std::printf("\n--- the lines this would publish ---\n");

    Config cfg;
    g_dry_run = true;
    publish_periodic(cfg, s);
}

static void usage(const char *argv0)
{
    std::printf(
        "usage: %s [options]\n"
        "  -c, --conf PATH       credentials file (default %s)\n"
        "  -i, --interval N      seconds between ticks (minimum %d, default 60)\n"
        "      --temp-warn N     SoC °C for a THERMAL LIMIT warning (default 80)\n"
        "      --temp-crit N     SoC °C for a THERMAL LIMIT error   (default 95)\n"
        "      --disk-warn N     root filesystem %% full for DISK LOW (default 90)\n"
        "  -p, --probe           print what this board can read, then exit\n"
        "  -n, --dry-run         print the lines, publish nothing\n"
        "  -1, --once            one tick, then exit\n"
        "  -v, --verbose         say which broker and topic on start\n"
        "  -h, --help\n", argv0, DEFAULT_CONF, MIN_INTERVAL_S);
}

int main(int argc, char **argv)
{
    const char *conf_path = DEFAULT_CONF;
    int  interval = 60;
    bool once = false, do_probe = false, verbose = false;
    Thresholds th;

    static const option longopts[] = {
        {"conf",      required_argument, nullptr, 'c'},
        {"interval",  required_argument, nullptr, 'i'},
        {"temp-warn", required_argument, nullptr, 1000},
        {"temp-crit", required_argument, nullptr, 1001},
        {"disk-warn", required_argument, nullptr, 1002},
        {"probe",     no_argument,       nullptr, 'p'},
        {"dry-run",   no_argument,       nullptr, 'n'},
        {"once",      no_argument,       nullptr, '1'},
        {"verbose",   no_argument,       nullptr, 'v'},
        {"help",      no_argument,       nullptr, 'h'},
        {nullptr, 0, nullptr, 0}
    };

    int opt;
    while ((opt = getopt_long(argc, argv, "c:i:pn1vh", longopts, nullptr)) != -1) {
        switch (opt) {
        case 'c':  conf_path = optarg; break;
        case 'i':  interval = std::atoi(optarg); break;
        case 1000: th.temp_warn = std::atof(optarg); break;
        case 1001: th.temp_crit = std::atof(optarg); break;
        case 1002: th.disk_warn = std::atof(optarg); break;
        case 'p':  do_probe = true; break;
        case 'n':  g_dry_run = true; break;
        case '1':  once = true; break;
        case 'v':  verbose = true; break;
        case 'h':  usage(argv[0]); return 0;
        default:   usage(argv[0]); return 2;
        }
    }

    if (do_probe) { probe(); return 0; }

    if (interval < MIN_INTERVAL_S) {
        std::fprintf(stderr,
                     "[status] --interval %d is below the %d s floor: the broker's 30 "
                     "points/minute are shared with the ESP32 and the Cluster. "
                     "See README section 6.\n", interval, MIN_INTERVAL_S);
        return 2;
    }

    // The conf is read even for a dry run -- that is how you check the file parses
    // before trusting the service with it -- but a missing one is only fatal when we
    // are actually going to publish.
    Config cfg;
    if (!load_conf(conf_path, cfg, verbose) && !g_dry_run)
        return 1;

    // Reaped by init; we never wait on a publish. Without this every mosquitto_pub
    // stays a zombie until the agent exits, which for a service is never.
    std::signal(SIGCHLD, SIG_IGN);

    publish(cfg, CODE_BOOT, SEV_INFO, 0);

    EventState st;
    for (;;) {
        // Sampled once and passed to both: reading /proc twice, once for the
        // periodic message and once for the event check, lets them disagree.
        const Sample s = take_sample();
        publish_periodic(cfg, s);
        publish_events(cfg, s, th, st);
        if (once) return 0;
        sleep(unsigned(interval));
    }
}
