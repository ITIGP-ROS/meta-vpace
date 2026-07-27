DESCRIPTION = "Ackermann Robot Hardware Interface"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "file://ackermann_hardware"
S = "${WORKDIR}/ackermann_hardware"

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