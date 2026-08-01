/*
 * liveliness_respond.cpp — CAN node-liveness responder for the Jetson.
 * Runs on Linux/SocketCAN. Listens on can0 (default) for the HOST's rolling
 * ping and echoes it back so the host can prove this node is powered and its
 * CAN stack is alive.
 *
 * Node liveness ping — the only frames the HOST originates besides the OTA
 * gate. One byte each; the responder ID is what identifies the node, so the
 * payload carries no node id.
 *
 *   ID    Msg                 DLC  Signal  Bytes  Type  Notes
 *   0x7A0 NodePingRequest     1    seq     0      u8    rolling counter, host -> all three
 *   0x7A1 NodePingRespTiva    1    seq     0      u8    echo the request byte unchanged
 *   0x7A2 NodePingRespJetson  1    seq     0      u8    "
 *   0x7A3 NodePingRespEsp32   1    seq     0      u8    "
 *
 * Answer UNCONDITIONALLY — not gated on init state or a ready flag. The point
 * is to prove the node is powered and its CAN stack is alive; a node that
 * withholds the reply because it considers itself "not ready" is reported as
 * dead.
 *
 * Not SecOC-protected, unlike the OTA gate: these carry no command, and a
 * forged response can only make a dead node look alive.
 *
 * The response ID is configurable (-r) so the same binary can serve as the
 * Tiva (0x7A1) or ESP32 (0x7A3) responder if ever needed; the responder ID
 * is what identifies the node.
 *
 * Build:  make          (see recipe)
 * Usage:  ./liveliness_respond -i can0 -r 0x7A2 -v
 */

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#include <errno.h>
#include <getopt.h>
#include <net/if.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <linux/can.h>
#include <linux/can/raw.h>

/* ------------------------------ wire constants ---------------------------- */

static const canid_t NODE_PING_REQ   = 0x7A0;   /* host -> all three nodes    */
static const canid_t NODE_PING_RESP  = 0x7A2;   /* this node (Jetson) -> host */
static const int     N_POLL_MS       = 1000;    /* poll timeout (idle wakeup) */

/* --------------------------------- options -------------------------------- */

struct Opts {
    std::string iface   = "can0";
    canid_t     resp_id = NODE_PING_RESP;
    bool        verbose = false;
};

static void usage(const char *argv0)
{
    fprintf(stderr,
        "Usage: %s [options]\n"
        "\n"
        "  -i <iface>   CAN interface         (default can0)\n"
        "  -r <id>      response CAN ID       (default 0x7A2)\n"
        "  -v           verbose: log every request/reply\n"
        "\n"
        "Listens for NodePingRequest (0x7A0, DLC 1, seq byte) and echoes the\n"
        "byte unchanged on the response ID. Answers unconditionally, with no\n"
        "init/ready gating: a powered node with a live CAN stack must answer\n"
        "even before it considers itself ready.\n",
        argv0);
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

    /* Only inbound NodePingRequest frames. */
    struct can_filter fl {};
    fl.can_id   = NODE_PING_REQ;
    fl.can_mask = CAN_SFF_MASK;
    if (setsockopt(s, SOL_CAN_RAW, CAN_RAW_FILTER, &fl, sizeof(fl)) < 0) {
        perror("setsockopt(CAN_RAW_FILTER)"); close(s); return -1;
    }

    return s;
}

static bool can_send(int s, canid_t id, uint8_t byte, bool verbose)
{
    struct can_frame f {};
    f.can_id  = id;
    f.can_dlc = 1;
    f.data[0] = byte;

    if (verbose)
        printf("  TX %03X [1] %02X\n", id & CAN_SFF_MASK, f.data[0]);

    ssize_t n = write(s, &f, sizeof(f));
    if (n != (ssize_t)sizeof(f)) {
        fprintf(stderr, "error: CAN write failed: %s\n", strerror(errno));
        return false;
    }
    return true;
}

/* ---------------------------------- main ---------------------------------- */

int main(int argc, char **argv)
{
    Opts o;

    int c;
    while ((c = getopt(argc, argv, "i:r:vh")) != -1) {
        switch (c) {
        case 'i':  o.iface   = optarg; break;
        case 'r':  o.resp_id = (canid_t)strtoul(optarg, nullptr, 0); break;
        case 'v':  o.verbose = true;   break;
        case 'h':  usage(argv[0]); return 0;
        default:   usage(argv[0]); return 2;
        }
    }
    o.resp_id &= CAN_SFF_MASK;

    int s = can_open(o.iface);
    if (s < 0) return 1;

    if (o.verbose)
        printf("listening on %s for 0x%03X, replying on 0x%03X\n",
               o.iface.c_str(), NODE_PING_REQ, o.resp_id);

    for (;;) {
        struct pollfd pfd { s, POLLIN, 0 };
        int pr = poll(&pfd, 1, N_POLL_MS);
        if (pr < 0) {
            if (errno == EINTR) continue;
            perror("poll"); close(s); return 1;
        }
        if (pr == 0) continue;                    /* idle timeout, keep waiting */

        struct can_frame f {};
        ssize_t n = read(s, &f, sizeof(f));
        if (n < 0) {
            if (errno == EINTR) continue;
            perror("read"); close(s); return 1;   /* iface gone: let systemd retry */
        }
        if (n != (ssize_t)sizeof(f)) continue;
        if ((f.can_id & CAN_SFF_MASK) != NODE_PING_REQ) continue;
        if (f.can_dlc != 1) {                     /* malformed request: ignore */
            if (o.verbose)
                printf("  RX %03X [%u] dropped (DLC != 1)\n",
                       NODE_PING_REQ, f.can_dlc);
            continue;
        }

        if (o.verbose)
            printf("  RX %03X [1] %02X\n", NODE_PING_REQ, f.data[0]);

        /* Unconditional echo: never gated on init state or readiness. */
        if (!can_send(s, o.resp_id, f.data[0], o.verbose)) {
            close(s); return 1;
        }
    }
}
