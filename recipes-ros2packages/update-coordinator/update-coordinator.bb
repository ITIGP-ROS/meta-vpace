SUMMARY = "SecOC-authenticated CAN-based ECU firmware update coordinator"
DESCRIPTION = "ROS2 node orchestrating cluster/edge/Jetson self-updates \
over CAN with SecOC freshness and CMAC authentication."
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_python
inherit systemd

SRC_URI = "\
    git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main \
    file://ota_secoc.key \
    file://update-coordinator.service \
"

SRCREV = "70b5631cae679a467f9a9e9f907737dec4d60247"

S = "${WORKDIR}/git/src/update_coordinator"

UNPACKDIR ?= "${WORKDIR}"

SYSTEMD_SERVICE:${PN} = "update-coordinator.service"
SYSTEMD_AUTO_ENABLE = "enable"

ROS_EXEC_DEPENDS = " \
    rclpy \
    std-srvs \
    python3-cryptography \
    ament-index-python \
    launch \
    launch-ros \
"

RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

do_install:append() {
    install -d ${D}${sysconfdir}
    install -m 0400 ${UNPACKDIR}/ota_secoc.key ${D}${sysconfdir}/ota_secoc.key

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${UNPACKDIR}/update-coordinator.service ${D}${systemd_system_unitdir}/update-coordinator.service
}

FILES:${PN} += "${sysconfdir}/ota_secoc.key \
                ${systemd_system_unitdir}/update-coordinator.service"
