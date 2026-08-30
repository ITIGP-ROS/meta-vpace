DESCRIPTION = "Ackermann Robot Hardware Interface"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main"
SRCREV = "20deeab4c5e8959cc1a2b09a201f7ecdfcf3abaa"

S = "${WORKDIR}/git/src/ackermann_hardware"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    hardware-interface \
    pluginlib \
    rclcpp \
    rclcpp-lifecycle \
    rosidl-adapter \
"

ROS_EXEC_DEPENDS = " \
    hardware-interface \
    pluginlib \
    rclcpp \
    rclcpp-lifecycle \
    joint-state-broadcaster \
    imu-sensor-broadcaster \
    ackermann-steering-controller \
    controller-manager \
    robot-state-publisher \
    urdf-parser-plugin \
"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

FILES_SOLIBSDEV = ""
FILES:${PN} += "${ros_libdir}/*.so"
INSANE_SKIP:${PN} += "dev-so"

# for swupdate (bundled by ackermann-update)
#
# --owner/--group, not fakeroot: PSEUDO_IGNORE_PATHS excludes ${WORKDIR}/deploy-,
# so fakeroot wouldn't fix the uid stamped into the tarball anyway. DEPLOYDIR (not
# DEPLOY_DIR_IMAGE) puts this under sstate.
inherit deploy
do_deploy() {
    tar czf ${DEPLOYDIR}/ackermann-hardware-${MACHINE}.tar.gz \
        --owner=root:0 --group=root:0 \
        --warning=no-file-changed \
        -C ${D} .
}
# After do_populate_sysroot too: it hardlinks ${D}, bumping ctimes, and tar
# fails at random if a ctime moves mid-read. --warning hides the message, not
# the exit code.
addtask deploy after do_install do_populate_sysroot do_package before do_build