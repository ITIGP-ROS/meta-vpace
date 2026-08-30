SUMMARY = "Jetson status agent: publishes CPU/GPU/memory/thermal health to the log feed"
DESCRIPTION = "Samples /proc and Tegra sysfs and publishes this board's health onto \
the vehicle's Adafruit IO log feed as ID:A2, one point a minute plus edge-triggered \
thermal/disk/WiFi events. Credentials come from ivi-ota-agent's agent.conf."
LICENSE = "CLOSED"
LIC_FILES_CHKSUM = ""

PV = "1.0"

SRC_URI = "\
    file://jetson_status_agent.cpp \
    file://jetson-status-agent.service \
"

# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
# The ?= keeps the newer definition and supplies the old default otherwise.
UNPACKDIR ?= "${WORKDIR}"
S = "${UNPACKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "jetson-status-agent.service"
SYSTEMD_AUTO_ENABLE = "enable"

# Publishes via mosquitto_pub (spawned, no MQTT library linked), same as busmon on
# the Cluster. No RDEPENDS on ivi-ota-agent: it reads agent.conf if present and
# exits cleanly if not, so telemetry-only boards don't need the whole OTA stack.
RDEPENDS:${PN} = "mosquitto-clients"

do_compile() {
    ${CXX} ${CXXFLAGS} ${LDFLAGS} -std=c++17 -o jetson_status_agent \
        ${S}/jetson_status_agent.cpp
}

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${B}/jetson_status_agent ${D}${bindir}/jetson_status_agent

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/jetson-status-agent.service \
        ${D}${systemd_system_unitdir}/jetson-status-agent.service
}

FILES:${PN} += "${systemd_system_unitdir}/jetson-status-agent.service"
