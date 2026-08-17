DESCRIPTION = "Ackermann Robot Model Description Files"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main"
SRCREV = "63167756513c23e323d7a6e0ec220fe8f2a704d6"

S = "${WORKDIR}/git/src/ackermann_description"

ROS_BUILD_DEPENDS = "ament-cmake-native"
ROS_EXEC_DEPENDS = "xacro"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

# for swupdate (bundled by ackermann-update)
do_deploy() {
    tar czf ${DEPLOY_DIR_IMAGE}/ackermann-description-${MACHINE}.tar.gz \
        --warning=no-file-changed \
        -C ${D} .
}
addtask deploy after do_install before do_build