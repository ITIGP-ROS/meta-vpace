SUMMARY = "IVI OTA agent (MQTT campaign -> download -> decrypt -> swupdate)"
DESCRIPTION = "Subscribes to an Adafruit IO feed, validates the campaign, downloads \
the encrypted .swu from Backblaze B2, RSA-unwraps the AES session key, decrypts, \
and hands the package to swupdate."
LICENSE = "CLOSED"

SRC_URI = "file://ivi_ota_agent.sh \
           file://ivi-ota-agent.service \
           file://ota-approval-tmpfiles.conf"

S = "${WORKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "ivi-ota-agent.service"
SYSTEMD_AUTO_ENABLE = "enable"

PACKAGECONFIG ??= ""
PACKAGECONFIG[ros2-coordination] = ",,,ros2cli update-coordinator"

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

    if ${@bb.utils.contains('PACKAGECONFIG', 'ros2-coordination', 'true', 'false', d)}; then
        sed -i '/^After=/ s/$/ update-coordinator.service/' \
            ${D}${systemd_system_unitdir}/ivi-ota-agent.service
    fi

    install -d -m 0700 ${D}${sysconfdir}/ivi-ota
    
    install -m 0600 ${OTA_AGENT_PRIVATE_KEY_PATH} ${D}${sysconfdir}/ivi-ota/ivi_priv.pem

    install -m 0600 ${OTA_AGENT_CONF_PATH} ${D}${sysconfdir}/ivi-ota/agent.conf

    # Approval spool. /run is a tmpfs, so systemd-tmpfiles has to recreate it on
    # every boot; the mode on verdicts/ is what lets the unprivileged IVI app
    # answer without being able to read or forge anything.
    install -d ${D}${nonarch_libdir}/tmpfiles.d
    install -m 0644 ${WORKDIR}/ota-approval-tmpfiles.conf \
        ${D}${nonarch_libdir}/tmpfiles.d/ota-approval.conf
}

FILES:${PN} += "${systemd_system_unitdir}/ivi-ota-agent.service \
                ${sysconfdir}/ivi-ota \
                ${nonarch_libdir}/tmpfiles.d"

# The agent keeps its installed-version file under /var/lib/ivi-ota. That path
# MUST be persistent -- if /var/lib is volatile the board forgets what it is
# running and reinstalls on every boot.
