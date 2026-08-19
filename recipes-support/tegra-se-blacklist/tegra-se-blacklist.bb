SUMMARY = "Disable the Tegra Security Engine crypto driver (oopses and hangs the board)"
DESCRIPTION = "tegra_se corrupts its own crypto_engine request queue and oopses in \
crypto_dequeue_request with a NULL write and LIST_POISON in registers. That kills the \
crypto kthread, blocks its waiters, and the CCPLEX watchdog then resets the SoC \
(reset_reason BCCPLEXWDT). Blacklisting it falls back to software crypto. \
See the comments in blacklist-tegra-se.conf for the captured trace and decode."
LICENSE = "CLOSED"

SRC_URI = "file://blacklist-tegra-se.conf"

S = "${WORKDIR}"

do_install() {
    install -d ${D}${sysconfdir}/modprobe.d
    install -m 0644 ${WORKDIR}/blacklist-tegra-se.conf ${D}${sysconfdir}/modprobe.d/
}

FILES:${PN} += "${sysconfdir}/modprobe.d/blacklist-tegra-se.conf"
