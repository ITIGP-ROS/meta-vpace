DESCRIPTION = "Ackermann Robot Model Description Files"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "file://ackermann_description"
S = "${WORKDIR}/ackermann_description"

ROS_BUILD_DEPENDS = "ament-cmake-native"
ROS_EXEC_DEPENDS = "xacro"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"