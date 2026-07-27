SUMMARY = "IVI OTA agent (MQTT campaign -> download -> decrypt -> swupdate)"
DESCRIPTION = "Subscribes to an Adafruit IO feed, validates the campaign, downloads \
the encrypted .swu from Backblaze B2, RSA-unwraps the AES session key, decrypts, \
and hands the package to swupdate."
LICENSE = "CLOSED"

SRC_URI = "file://ivi_ota_agent.sh \
           file://ivi-ota-agent.service"

S = "${WORKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "ivi-ota-agent.service"
SYSTEMD_AUTO_ENABLE = "enable"

# mosquitto-clients -> mosquitto_sub / mosquitto_pub
# curl              -> the download
# openssl           -> RSA unwrap + AES decrypt + base64 (the agent uses
#                      `openssl base64`, NOT coreutils, so coreutils is NOT needed)
# swupdate          -> the actual install
# cpio              -> the .swu magic sanity check
RDEPENDS:${PN} = "mosquitto-clients curl openssl swupdate cpio"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${WORKDIR}/ivi_ota_agent.sh ${D}${bindir}/ivi_ota_agent.sh

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/ivi-ota-agent.service ${D}${systemd_system_unitdir}/ivi-ota-agent.service

    # Created empty and root-only. The RSA private key and agent.conf are pushed
    # at deploy time by deploy_ivi_agent.sh -- NEVER baked into the image. Baking
    # them in ships secrets inside an artifact that gets copied around, and a
    # rootfs update would wipe them anyway.
    install -d -m 0700 ${D}${sysconfdir}/ivi-ota
}

FILES:${PN} += "${systemd_system_unitdir}/ivi-ota-agent.service \
                ${sysconfdir}/ivi-ota"

# The agent keeps its installed-version file under /var/lib/ivi-ota. That path
# MUST be persistent -- if /var/lib is volatile the board forgets what it is
# running and reinstalls on every boot.
