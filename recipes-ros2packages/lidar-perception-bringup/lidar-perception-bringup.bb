DESCRIPTION = "Launch files and parameter profiles for the LiDAR perception pipeline"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"

# package.xml says MIT but there is no licence text to checksum. Same situation
# and same resolution as object-detection-msgs.bb.
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake
inherit systemd

require conf/include/ros2-lidar-perception.inc

# S points into the git clone, so the file:// entry needs its own path. Same
# arrangement as camera-sign-detect-bringup.bb and update-coordinator.bb, which
# mix the two fetchers the same way.
SRC_URI += "file://lidar-perception.service"
UNPACKDIR ?= "${WORKDIR}"

S = "${WORKDIR}/git/src/lidar_perception_bringup"

SYSTEMD_SERVICE:${PN} = "lidar-perception.service"

# Enabled 2026-08-26 now that ackermann-lidar.service exists to publish /livox/lidar --
# previously auto-enabling would've loaded a PointPillars engine onto the GPU at every
# boot with nothing to feed it. lidar-perception.service Requires=/After= that unit.
SYSTEMD_AUTO_ENABLE = "enable"

# Declared explicitly since Requires= on a missing unit is a hard systemd failure.
RDEPENDS:${PN} += "ackermann-bringup"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
"

# The launch file is the whole package, so its exec deps ARE the pipeline.
# ament-index-python and launch/launch-ros are what perception.launch.py itself
# imports; the rest are the nodes it spawns.
ROS_EXEC_DEPENDS = " \
    ament-index-python \
    launch \
    launch-ros \
    ros2launch \
    cuda-pointpillars-ros \
    object-visualization \
"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS}"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}

do_install:append() {
    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${UNPACKDIR}/lidar-perception.service \
        ${D}${systemd_system_unitdir}/lidar-perception.service
}

FILES:${PN} += " \
    ${ros_datadir}/${ROS_BPN}/launch \
    ${ros_datadir}/${ROS_BPN}/params \
    ${systemd_system_unitdir}/lidar-perception.service \
"
