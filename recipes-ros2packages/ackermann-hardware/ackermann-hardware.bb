DESCRIPTION = "Ackermann Robot Hardware Interface"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main"
SRCREV = "63167756513c23e323d7a6e0ec220fe8f2a704d6"

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
do_deploy() {
    tar czf ${DEPLOY_DIR_IMAGE}/ackermann-hardware-${MACHINE}.tar.gz \
        --warning=no-file-changed \
        -C ${D} .
}
addtask deploy after do_install before do_build