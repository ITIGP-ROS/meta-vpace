DESCRIPTION = "V-PACE AI traffic sign detection bringup package"
MAINTAINER = "youhana <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main"
SRCREV = "65e4dd7882795f8321dfcc15edcdd28dc9e3682d"

S = "${WORKDIR}/git/src/camera_sign_detect_bringup"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    ament-index-python \
    launch \
    launch-ros \
    rclcpp \
    rclcpp-components \
    vision-msgs \
"

ROS_EXEC_DEPENDS = " \
    ament-index-python \
    launch \
    launch-ros \
    rclcpp \
    rclcpp-components \
    v4l2-camera \
    ros2-yolos-cpp \
    vision-msgs \
"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS}"

FILES_SOLIBSDEV = ""
# Package launch, params, and models directories
FILES:${PN} += " \
    ${ros_libdir}/*.so \
    ${ros_datadir}/${ROS_BPN}/launch \
    ${ros_datadir}/${ROS_BPN}/params \
    ${ros_datadir}/${ROS_BPN}/models \
"
INSANE_SKIP:${PN} += "dev-so"