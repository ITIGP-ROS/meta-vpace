/*
 * wifi_cred_send.cpp — send WiFi credentials over CAN, authenticated with SecOC
 * (truncated AES-128-CMAC + freshness), segmented with ISO-TP (ISO 15765-2).
 * Runs on Linux/SocketCAN (Jetson, PC, anything).
 *
 * TWO RECEIVERS NEED THESE CREDENTIALS, and each gets its OWN ISO-TP session:
 *
 *   cluster   0x205 us -> QNX host    0x206 host  -> us   (Flow Control)
 *   esp32     0x207 us -> ESP32 ECU   0x208 esp32 -> us   (Flow Control)
 *
 * They are NOT one broadcast. ISO-TP Flow Control is point-to-point: if both
 * receivers answered FC on a shared ID they would be two transmitters on one
 * ID. They emit identical CTS frames today, so it appears to work — but the
 * moment one needs to send Wait (0x31) or Overflow (0x32) the frames diverge
 * mid-transmission and the bus takes bit errors. Hence -t/--target, and hence
 * `--target both` sends the payload twice rather than once.
 *
 * This is the SENDER half of the receivers in qnx-host/can/{wifi_cred.c,
 * isotp_rx.c} and the ESP32's src/logs/can.c. The wire format is dictated by
 * those receivers — do not change it here alone:
 *
 *   payload = "SSID;PASSWORD" || freshness(4 B, big-endian) || MAC(4 B)
 *   MAC     = AES-128-CMAC(key, "SSID;PASSWORD" || freshness)[0..3]
 *
 * A receiver accepts a message only if the MAC verifies AND the freshness is
 * STRICTLY GREATER than the last value it accepted (persisted across reboot),
 * so every send must use a larger counter than the previous one. We default to
 * max(unix time, last_sent+1), which is monotonic without needing to know what
 * either receiver currently holds. The two receivers keep INDEPENDENT floors,
 * so they do not have to agree on the number.
 *
 * The AES-CMAC comes from the same aes_cmac.c the QNX side compiles, so the two
 * implementations cannot drift apart.
 *
 * Build:  make          (see Makefile)
 * Usage:  ./wifi_cred_send -i can0 -k secoc.key -s MySSID -p MyPassword
 *         ./wifi_cred_send ... --target both      (provision host AND ESP32)
 */

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>

#include <unistd.h>
#include <fcntl.h>
#include <getopt.h>
#include <time.h>
#include <errno.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <poll.h>
#include <linux/can.h>
#include <linux/can/raw.h>

extern "C" {
#include "aes_cmac.h"
}

/* ------------------------------ wire constants ---------------------------- */

/* ISO-TP is point-to-point: exactly one node may answer Flow Control per
 * session. So each receiver owns an ID pair and we send the credentials once
 * per receiver, rather than broadcasting one message both would answer.
 *
 *   cluster  0x205 / 0x206   QNX host, qnx-host/can/wifi_cred.c
 *   esp32    0x207 / 0x208   ESP32 ECU, common/config.h
 *
 * Set from -t/--target (or --cred-id/--fc-id) before any frame is built, hence
 * not const. */
static canid_t CAN_ID_CREDS = 0x205;         /* us -> receiver  (SF/FF/CF)    */
static canid_t CAN_ID_FC    = 0x206;         /* receiver -> us  (Flow Control)*/

struct Target { const char *name; canid_t creds; canid_t fc; };
static const Target TARGETS[] = {
    { "cluster", 0x205, 0x206 },
    { "esp32",   0x207, 0x208 },
};

static const size_t  MAC_TRUNC    = 4;       /* truncated SecOC tag length    */
static const size_t  FV_LEN       = 4;       /* freshness counter length      */
static const size_t  ISOTP_MAX    = 256;     /* receiver's ISOTP_MAX_LEN      */

static const int     N_BS_MS      = 1000;    /* max wait for Flow Control     */

/* ------------------------------- exit codes -------------------------------
 * Distinct codes so a caller (Qt/QProcess, scripts) can react to the actual
 * failure instead of parsing text. */
enum {
    EXIT_OK        = 0,   /* frames delivered to the host's ISO-TP receiver   */
    EXIT_USAGE     = 2,   /* bad/missing arguments                            */
    EXIT_KEY       = 3,   /* key file missing or malformed                    */
    EXIT_CAN       = 4,   /* CAN interface missing / socket error             */
    EXIT_TRANSFER  = 5,   /* no Flow Control, refused, or write failed        */
    EXIT_PAYLOAD   = 6    /* payload exceeds the receiver's limit             */
};

/* --------------------------------- options -------------------------------- */

struct Opts {
    std::string iface   = "can0";
    std::string keypath = "secoc.key";
    std::string fvpath  = "/var/lib/wifi_cred_txfv";
    std::string ssid;
    std::string pass;
    /* Defaults to BOTH: on this vehicle the Jetson is the provisioning source
     * for the QNX host AND the ESP32, so a caller that says nothing wants both
     * provisioned. Defaulting to one receiver means any caller that forgets the
     * flag silently leaves the other without credentials, which is a failure
     * mode nobody notices until the ESP32 will not join the network.
     *
     * Consequence to know about: if one receiver is powered off, its send waits
     * N_BS_MS for flow control and the tool exits EXIT_TRANSFER even though the
     * other receiver was provisioned fine. Use --json to see per-target results
     * (one line each, with its own ok/code) rather than judging by exit code. */
    std::string target  = "both";   /* cluster | esp32 | both           */
    uint32_t    fv        = 0;      /* 0 = auto (time-based, monotonic) */
    long        cred_id   = -1;     /* >=0 overrides the target preset  */
    long        fc_id     = -1;
    bool        verbose   = false;
    bool        dry_run   = false;
    bool        use_stdin = false;  /* read creds from stdin, not argv  */
    bool        json      = false;  /* single-line JSON result          */
};

static void usage(const char *argv0)
{
    fprintf(stderr,
        "Usage: %s -s SSID -p PASSWORD [options]\n"
        "       %s --stdin [options]      < creds.txt\n"
        "\n"
        "  -i <iface>    CAN interface            (default can0)\n"
        "  -k <file>     16-byte or 32-hex key    (default secoc.key)\n"
        "  -s <ssid>     WiFi SSID\n"
        "  -p <pass>     WiFi password\n"
        "  --stdin       read SSID on line 1 and password on line 2 from stdin\n"
        "                instead of -s/-p. USE THIS FROM GUI/SERVICE CODE: an\n"
        "                argv password is visible to every user via `ps aux`.\n"
        "  -t, --target  who receives them: cluster (0x205/0x206, default),\n"
        "                esp32 (0x207/0x208), or both (sent in sequence)\n"
        "  --cred-id <n> override the credential CAN ID (single target only)\n"
        "  --fc-id <n>   override the flow-control CAN ID\n"
        "  -f <n>        explicit freshness value (default: auto, monotonic)\n"
        "  -F <file>     freshness state file     (default /var/lib/wifi_cred_txfv)\n"
        "  -j, --json    print one JSON line with the result (one line PER TARGET\n"
        "                when --target both)\n"
        "  -n            dry run: build and print frames, send nothing\n"
        "  -v            verbose (hex dumps, per-frame trace)\n"
        "\n"
        "Exit codes: 0 delivered, 2 usage, 3 key, 4 CAN interface,\n"
        "            5 transfer failed (no flow control), 6 payload too large.\n"
        "\n"
        "NOTE: exit 0 means the frames reached the host's ISO-TP receiver. The\n"
        "host does not send a verdict back over CAN, so MAC/freshness acceptance\n"
        "must be read from its log (slog2info -b can_udp | grep -i wifi).\n"
        "\n"
        "The key must be the SAME file content as /data/wifi/secoc.key on the\n"
        "QNX host. Freshness must exceed the host's last accepted value.\n",
        argv0, argv0);
}

/* JSON-escape a string for the -j output (quotes, backslash, control chars). */
static std::string json_escape(const std::string &s)
{
    std::string o;
    for (unsigned char c : s) {
        switch (c) {
        case '"':  o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n";  break;
        case '\r': o += "\\r";  break;
        case '\t': o += "\\t";  break;
        default:
            if (c < 0x20) { char b[7]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
            else o += (char)c;
        }
    }
    return o;
}

/* Single-line JSON result. The password is NEVER included. */
static void emit_json(bool ok, int code, const std::string &msg,
                      const std::string &ssid, uint32_t fv, size_t bytes,
                      const char *target = "cluster")
{
    printf("{\"ok\":%s,\"code\":%d,\"message\":\"%s\","
           "\"ssid\":\"%s\",\"freshness\":%u,\"payload_bytes\":%zu,"
           "\"target\":\"%s\"}\n",
           ok ? "true" : "false", code, json_escape(msg).c_str(),
           json_escape(ssid).c_str(), fv, bytes, target);
    fflush(stdout);
}

/* Read SSID (line 1) and password (line 2) from stdin. Keeps the password out
 * of the process argument list, which `ps` exposes to every local user. */
static bool read_creds_stdin(std::string &ssid, std::string &pass)
{
    std::string line[2];
    for (int i = 0; i < 2; i++) {
        int ch;
        while ((ch = fgetc(stdin)) != EOF && ch != '\n') line[i] += (char)ch;
        if (line[i].empty() && ch == EOF) return false;
        /* tolerate CRLF from a Windows-authored file */
        if (!line[i].empty() && line[i].back() == '\r') line[i].pop_back();
    }
    ssid = line[0];
    pass = line[1];
    return !ssid.empty() && !pass.empty();
}

/* ------------------------------- key loading ------------------------------ */
/* Mirrors load_key() in qnx-host/can/wifi_cred.c: raw 16 bytes, or 32 hex
 * chars with optional trailing whitespace. */
static bool load_key(const std::string &path, uint8_t key[16])
{
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) {
        fprintf(stderr, "error: cannot open key file '%s': %s\n",
                path.c_str(), strerror(errno));
        return false;
    }
    unsigned char buf[128];
    size_t rd = fread(buf, 1, sizeof(buf), f);
    fclose(f);

    if (rd == 16) { memcpy(key, buf, 16); return true; }   /* raw key */

    while (rd > 0 && (buf[rd-1]=='\n' || buf[rd-1]=='\r' ||
                      buf[rd-1]==' '  || buf[rd-1]=='\t')) rd--;
    if (rd != 32) {
        fprintf(stderr, "error: key must be 16 raw bytes or 32 hex chars "
                        "(got %zu usable bytes)\n", rd);
        return false;
    }
    for (int i = 0; i < 16; i++) {
        unsigned v;
        if (sscanf((const char *)buf + 2 * i, "%2x", &v) != 1) {
            fprintf(stderr, "error: bad hex in key at char %d\n", 2 * i);
            return false;
        }
        key[i] = (uint8_t)v;
    }
    return true;
}

/* ----------------------------- freshness counter -------------------------- */
/* The host only checks "strictly greater than last accepted", so any monotonic
 * source works. Unix time is monotonic across reboots; the state file only
 * breaks ties when two sends land in the same second. */
static uint32_t load_last_fv(const std::string &path)
{
    FILE *f = fopen(path.c_str(), "r");
    if (!f) return 0;
    unsigned long v = 0;
    if (fscanf(f, "%lu", &v) != 1) v = 0;
    fclose(f);
    return (uint32_t)v;
}

static void store_last_fv(const std::string &path, uint32_t fv)
{
    FILE *f = fopen(path.c_str(), "w");
    if (!f) {
        fprintf(stderr, "warning: cannot persist freshness to '%s': %s\n"
                        "         (use -F to pick a writable path, or -f to set it)\n",
                path.c_str(), strerror(errno));
        return;
    }
    fprintf(f, "%lu\n", (unsigned long)fv);
    fclose(f);
}

static uint32_t next_freshness(const std::string &path)
{
    uint32_t last = load_last_fv(path);
    uint32_t now  = (uint32_t)time(nullptr);
    return (now > last) ? now : (last + 1);
}

/* -------------------------------- utilities ------------------------------- */

static void hexdump(const char *label, const uint8_t *p, size_t n)
{
    printf("%s (%zu bytes):\n", label, n);
    for (size_t i = 0; i < n; i += 16) {
        printf("  %04zx  ", i);
        for (size_t j = 0; j < 16; j++) {
            if (i + j < n) printf("%02X ", p[i + j]); else printf("   ");
        }
        printf(" |");
        for (size_t j = 0; j < 16 && i + j < n; j++) {
            uint8_t c = p[i + j];
            putchar((c >= 32 && c < 127) ? c : '.');
        }
        printf("|\n");
    }
}

/* --------------------------- payload construction ------------------------- */

static std::vector<uint8_t> build_payload(const std::string &ssid,
                                          const std::string &pass,
                                          uint32_t fv,
                                          const uint8_t key[16],
                                          bool verbose)
{
    std::vector<uint8_t> p;
    p.insert(p.end(), ssid.begin(), ssid.end());
    p.push_back(';');
    p.insert(p.end(), pass.begin(), pass.end());

    /* freshness, big-endian — matches the receiver's shift-and-OR decode */
    p.push_back((uint8_t)(fv >> 24));
    p.push_back((uint8_t)(fv >> 16));
    p.push_back((uint8_t)(fv >> 8));
    p.push_back((uint8_t)(fv));

    /* MAC covers credentials || freshness, i.e. everything built so far */
    uint8_t full[16];
    aes128_cmac(key, p.data(), p.size(), full);

    if (verbose) {
        hexdump("MAC input (creds || freshness)", p.data(), p.size());
        printf("full CMAC: ");
        for (int i = 0; i < 16; i++) printf("%02X", full[i]);
        printf("   -> truncated to %zu bytes\n", MAC_TRUNC);
    }

    p.insert(p.end(), full, full + MAC_TRUNC);
    return p;
}

/* ---------------------------------- CAN ----------------------------------- */

static int can_open(const std::string &iface)
{
    int s = socket(PF_CAN, SOCK_RAW, CAN_RAW);
    if (s < 0) { perror("socket(PF_CAN)"); return -1; }

    struct ifreq ifr {};
    strncpy(ifr.ifr_name, iface.c_str(), IFNAMSIZ - 1);
    if (ioctl(s, SIOCGIFINDEX, &ifr) < 0) {
        fprintf(stderr, "error: interface '%s' not found: %s\n",
                iface.c_str(), strerror(errno));
        close(s);
        return -1;
    }

    struct sockaddr_can addr {};
    addr.can_family  = AF_CAN;
    addr.can_ifindex = ifr.ifr_ifindex;
    if (bind(s, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind"); close(s); return -1;
    }

    /* We only care about inbound Flow Control frames. */
    struct can_filter fl {};
    fl.can_id   = CAN_ID_FC;
    fl.can_mask = CAN_SFF_MASK;
    setsockopt(s, SOL_CAN_RAW, CAN_RAW_FILTER, &fl, sizeof(fl));

    return s;
}

/* `trace` (not dry_run) gates all frame printing, so --json output stays a
 * single parseable line even during a dry run. */
static bool can_send(int s, canid_t id, const uint8_t *data, uint8_t len,
                     bool trace, bool dry_run)
{
    struct can_frame f {};
    f.can_id  = id;
    f.can_dlc = len;
    memcpy(f.data, data, len);

    if (trace) {
        printf("  TX %03X [%u] ", id, len);
        for (int i = 0; i < len; i++) printf("%02X ", f.data[i]);
        printf("\n");
    }
    if (dry_run) return true;

    ssize_t n = write(s, &f, sizeof(f));
    if (n != (ssize_t)sizeof(f)) {
        fprintf(stderr, "error: CAN write failed: %s\n", strerror(errno));
        return false;
    }
    return true;
}

/* Wait for the host's Flow Control. Returns true on Clear-To-Send and fills
 * block_size / stmin; false on timeout, Overflow, or too many Waits. */
static bool wait_flow_control(int s, uint8_t *block_size, uint8_t *stmin,
                              bool verbose)
{
    int waits = 0;
    for (;;) {
        struct pollfd pfd { s, POLLIN, 0 };
        int pr = poll(&pfd, 1, N_BS_MS);
        if (pr == 0) {
            fprintf(stderr, "error: no Flow Control from the host within %d ms "
                            "(is the bridge running and is 0x%03X reaching it?)\n",
                    N_BS_MS, CAN_ID_CREDS);
            return false;
        }
        if (pr < 0) {
            if (errno == EINTR) continue;
            perror("poll"); return false;
        }

        struct can_frame f {};
        if (read(s, &f, sizeof(f)) != (ssize_t)sizeof(f)) continue;
        if ((f.can_id & CAN_SFF_MASK) != CAN_ID_FC) continue;
        if (f.can_dlc < 3 || (f.data[0] >> 4) != 0x3)  continue;

        uint8_t fs = f.data[0] & 0x0F;
        if (verbose)
            printf("  RX %03X [%u] %02X %02X %02X   (FlowStatus=%u)\n",
                   CAN_ID_FC, f.can_dlc, f.data[0], f.data[1], f.data[2], fs);

        if (fs == 0x0) {                       /* Clear To Send */
            *block_size = f.data[1];
            *stmin      = f.data[2];
            return true;
        }
        if (fs == 0x1) {                       /* Wait */
            if (++waits > 10) {
                fprintf(stderr, "error: host kept sending WAIT — giving up\n");
                return false;
            }
            continue;
        }
        fprintf(stderr, "error: host refused the transfer "
                        "(FlowStatus=%u, 2=Overflow — payload too large?)\n", fs);
        return false;
    }
}

/* ISO-TP STmin: 0x00-0x7F = milliseconds, 0xF1-0xF9 = 100..900 microseconds. */
static void stmin_delay(uint8_t stmin)
{
    if (stmin == 0) return;
    struct timespec ts {};
    if (stmin <= 0x7F)                  { ts.tv_sec = 0; ts.tv_nsec = (long)stmin * 1000000L; }
    else if (stmin >= 0xF1 && stmin <= 0xF9) { ts.tv_sec = 0; ts.tv_nsec = (long)(stmin - 0xF0) * 100000L; }
    else return;                        /* reserved value: treat as 0 */
    nanosleep(&ts, nullptr);
}

/* Segment and transmit the payload per ISO 15765-2. */
static bool isotp_send(int s, const std::vector<uint8_t> &p,
                       bool verbose, bool dry_run)
{
    const size_t len = p.size();

    if (len <= 7) {
        /* Single Frame. (Not reachable in practice: creds+FV+MAC is always >7,
         * but keep the protocol complete rather than silently wrong.) */
        uint8_t d[8] = {0};
        d[0] = (uint8_t)(0x00 | (len & 0x0F));
        memcpy(&d[1], p.data(), len);
        return can_send(s, CAN_ID_CREDS, d, (uint8_t)(len + 1), verbose, dry_run);
    }

    /* ---- First Frame: 12-bit length, 6 payload bytes ---- */
    uint8_t ff[8];
    ff[0] = (uint8_t)(0x10 | ((len >> 8) & 0x0F));
    ff[1] = (uint8_t)(len & 0xFF);
    memcpy(&ff[2], p.data(), 6);
    if (!can_send(s, CAN_ID_CREDS, ff, 8, verbose, dry_run)) return false;

    size_t sent = 6;

    /* ---- Flow Control from the host ---- */
    uint8_t bs = 0, stmin = 0;
    if (dry_run) {
        if (verbose) printf("  (dry run: assuming CTS, BS=0, STmin=0)\n");
    } else if (!wait_flow_control(s, &bs, &stmin, verbose)) {
        return false;
    }

    /* ---- Consecutive Frames ---- */
    uint8_t sn = 1;
    size_t  in_block = 0;
    while (sent < len) {
        size_t n = len - sent;
        if (n > 7) n = 7;

        uint8_t cf[8] = {0};
        cf[0] = (uint8_t)(0x20 | (sn & 0x0F));
        memcpy(&cf[1], p.data() + sent, n);
        if (!can_send(s, CAN_ID_CREDS, cf, (uint8_t)(n + 1), verbose, dry_run))
            return false;

        sent += n;
        sn = (uint8_t)((sn + 1) & 0x0F);   /* wraps 15 -> 0, as the receiver expects */

        if (sent < len) {
            /* BS = 0 means "send everything without further Flow Control". */
            if (bs != 0 && ++in_block >= bs) {
                in_block = 0;
                if (!dry_run && !wait_flow_control(s, &bs, &stmin, verbose))
                    return false;
            } else {
                stmin_delay(stmin);
            }
        }
    }
    return true;
}

/* ---------------------------------- main ---------------------------------- */

int main(int argc, char **argv)
{
    Opts o;

    static const struct option longopts[] = {
        {"stdin",   no_argument,       nullptr, 1000},
        {"json",    no_argument,       nullptr, 'j'},
        {"target",  required_argument, nullptr, 't'},
        {"cred-id", required_argument, nullptr, 1001},
        {"fc-id",   required_argument, nullptr, 1002},
        {"help",    no_argument,       nullptr, 'h'},
        {nullptr, 0,                 nullptr, 0}
    };

    int c;
    while ((c = getopt_long(argc, argv, "i:k:s:p:t:f:F:jnvh", longopts, nullptr)) != -1) {
        switch (c) {
        case 'i':  o.iface     = optarg; break;
        case 'k':  o.keypath   = optarg; break;
        case 's':  o.ssid      = optarg; break;
        case 'p':  o.pass      = optarg; break;
        case 't':  o.target    = optarg; break;
        case 'f':  o.fv        = (uint32_t)strtoul(optarg, nullptr, 0); break;
        case 'F':  o.fvpath    = optarg; break;
        case 'j':  o.json      = true;   break;
        case 'n':  o.dry_run   = true;   break;
        case 'v':  o.verbose   = true;   break;
        case 1000: o.use_stdin = true;   break;
        case 1001: o.cred_id   = strtol(optarg, nullptr, 0); break;
        case 1002: o.fc_id     = strtol(optarg, nullptr, 0); break;
        case 'h':  usage(argv[0]); return EXIT_OK;
        default:   usage(argv[0]); return EXIT_USAGE;
        }
    }

    /* Resolve --target / --cred-id / --fc-id into the list of sends to perform. */
    std::vector<Target> sends;
    bool overridden = (o.cred_id >= 0 || o.fc_id >= 0);

    if (o.target == "both") {
        if (overridden) {
            fprintf(stderr, "error: --cred-id/--fc-id cannot be combined with "
                            "--target both\n");
            return EXIT_USAGE;
        }
        for (const Target &t : TARGETS) sends.push_back(t);
    } else {
        const Target *base = nullptr;
        for (const Target &t : TARGETS)
            if (o.target == t.name) { base = &t; break; }
        if (!base) {
            fprintf(stderr, "error: unknown target '%s' (want cluster, esp32 or both)\n",
                    o.target.c_str());
            return EXIT_USAGE;
        }
        Target t = *base;
        if (o.cred_id >= 0) t.creds = (canid_t)o.cred_id;
        if (o.fc_id   >= 0) t.fc    = (canid_t)o.fc_id;
        if (overridden)     t.name  = "custom";
        sends.push_back(t);
    }

    if (o.use_stdin) {
        if (!read_creds_stdin(o.ssid, o.pass)) {
            const char *m = "stdin must supply SSID on line 1 and password on line 2";
            if (o.json) emit_json(false, EXIT_USAGE, m, "", 0, 0);
            else fprintf(stderr, "error: %s\n", m);
            return EXIT_USAGE;
        }
    }

    if (o.ssid.empty() || o.pass.empty()) {
        const char *m = "SSID and password are required (-s/-p, or --stdin)";
        if (o.json) { emit_json(false, EXIT_USAGE, m, o.ssid, 0, 0); return EXIT_USAGE; }
        fprintf(stderr, "error: %s\n\n", m);
        usage(argv[0]);
        return EXIT_USAGE;
    }
    /* The receiver splits on the FIRST ';', so a ';' in the SSID would silently
     * move the boundary. Refuse rather than send something that mis-parses. */
    if (o.ssid.find(';') != std::string::npos) {
        const char *m = "SSID must not contain ';' (it is the field separator)";
        if (o.json) emit_json(false, EXIT_USAGE, m, o.ssid, 0, 0);
        else fprintf(stderr, "error: %s\n", m);
        return EXIT_USAGE;
    }

    uint8_t key[16];
    if (!load_key(o.keypath, key)) {           /* load_key already explained why */
        if (o.json) emit_json(false, EXIT_KEY, "key file missing or malformed",
                              o.ssid, 0, 0);
        return EXIT_KEY;
    }

    /* Trace frames when asked (-v) or when dry-running for a human, but never
     * in --json mode: the caller parses stdout. */
    bool trace = (o.verbose || o.dry_run) && !o.json;
    int  first_failure = EXIT_OK;

    for (const Target &t : sends) {
        CAN_ID_CREDS = t.creds;
        CAN_ID_FC    = t.fc;

        /* Each receiver keeps its own freshness floor, so consuming one value
         * per send is correct — they need not agree on the number. */
        uint32_t fv = o.fv ? o.fv : next_freshness(o.fvpath);

        std::vector<uint8_t> payload = build_payload(o.ssid, o.pass, fv, key,
                                                     o.verbose && !o.json);

        if (payload.size() > ISOTP_MAX) {
            char m[128];
            snprintf(m, sizeof m, "payload %zu B exceeds the receiver limit of %zu B",
                     payload.size(), ISOTP_MAX);
            if (o.json) emit_json(false, EXIT_PAYLOAD, m, o.ssid, fv, payload.size(), t.name);
            else fprintf(stderr, "error: %s\n", m);
            return EXIT_PAYLOAD;      /* size is target-independent: retrying is pointless */
        }

        if (!o.json) {
            if (sends.size() > 1) printf("\n--- target: %s ---\n", t.name);
            printf("SSID      : %s\n", o.ssid.c_str());
            printf("password  : %zu chars\n", o.pass.size());
            printf("freshness : %u\n", fv);
            printf("payload   : %zu bytes (creds %zu + fv %zu + mac %zu)\n",
                   payload.size(), payload.size() - FV_LEN - MAC_TRUNC, FV_LEN, MAC_TRUNC);
            printf("interface : %s   ids: creds 0x%03X, flow-control 0x%03X\n",
                   o.iface.c_str(), CAN_ID_CREDS, CAN_ID_FC);
            if (o.verbose) hexdump("full payload (on the wire)",
                                   payload.data(), payload.size());
        }

        int s = -1;
        if (!o.dry_run) {
            s = can_open(o.iface);
            if (s < 0) {
                if (o.json) emit_json(false, EXIT_CAN,
                                      "cannot open CAN interface '" + o.iface + "'",
                                      o.ssid, fv, payload.size(), t.name);
                return EXIT_CAN;      /* the interface will not fix itself */
            }
        }

        bool ok = isotp_send(s, payload, trace, o.dry_run);
        if (s >= 0) close(s);

        if (!ok) {
            char m[192];
            snprintf(m, sizeof m,
                     "transfer failed (no flow control, refused, or write error) — "
                     "is the %s receiver powered and listening on 0x%03X?",
                     t.name, CAN_ID_CREDS);
            if (o.json) emit_json(false, EXIT_TRANSFER, m, o.ssid, fv, payload.size(), t.name);
            else fprintf(stderr, "\nFAILED [%s] — %s\n", t.name, m);
            /* Keep going: one dead receiver must not stop us provisioning the other. */
            if (first_failure == EXIT_OK) first_failure = EXIT_TRANSFER;
            continue;
        }

        /* Only bump our stored counter once the frames actually went out, so a
         * failed attempt does not burn a freshness value. */
        if (!o.dry_run && !o.fv) store_last_fv(o.fvpath, fv);

        if (o.json) {
            std::string msg = std::string("delivered to the ") + t.name +
                              " ISO-TP receiver";
            emit_json(true, EXIT_OK, msg, o.ssid, fv, payload.size(), t.name);
        } else {
            printf("\nSent to %s. The receiver verifies the MAC and requires "
                   "freshness > its last accepted value.\n", t.name);
        }
    }

    if (first_failure == EXIT_OK && !o.json && sends.size() > 1)
        printf("\nAll targets delivered.\n");
    return first_failure;
}
