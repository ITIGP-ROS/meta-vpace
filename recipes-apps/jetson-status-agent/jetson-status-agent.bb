SUMMARY = "Jetson status agent: publishes CPU/GPU/memory/thermal health to the log feed"
DESCRIPTION = "Samples /proc and the Tegra sysfs nodes and publishes this board's \
health onto the vehicle's Adafruit IO log feed (<user>/feeds/logs) as ID:A2, in the \
same five-field line the ESP32 and the Cluster guest use. Two data points a minute \
(CODE:58 SYS HEALTH and CODE:60 GPU LOAD), plus edge-triggered thermal and disk \
events. Credentials come from ivi-ota-agent's /etc/ivi-ota/agent.conf; there is no \
second copy of the key."
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

# mosquitto-clients -> mosquitto_pub, which is how this publishes. No MQTT library
#                      is linked; the binary spawns the tool, exactly as busmon does
#                      on the Cluster. ivi-ota-agent already pulls this in, so on a
#                      normal image it costs nothing.
# ivi-ota-agent     -> NOT a dependency. The agent reads its /etc/ivi-ota/agent.conf
#                      if it is there and exits with a clear message if it is not, so
#                      the two can be installed independently. Adding a hard RDEPENDS
#                      here would drag the whole OTA stack onto a board that only
#                      wanted telemetry.
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
