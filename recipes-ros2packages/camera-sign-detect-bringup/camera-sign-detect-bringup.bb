DESCRIPTION = "V-PACE AI traffic sign detection bringup package"
MAINTAINER = "youhana <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "file://camera_sign_detect_bringup"

S = "${WORKDIR}/camera_sign_detect_bringup"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    ament-index-python \
    launch \
    launch-ros \
    rclcpp-components \
"

ROS_EXEC_DEPENDS = " \
    ament-index-python \
    launch \
    launch-ros \
    rclcpp-components \
    v4l2-camera \
    ros2-yolos-cpp \
"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS}"

# Package launch, params, and models directories
FILES:${PN} += " \
    ${ros_datadir}/${ROS_BPN}/launch \
    ${ros_datadir}/${ROS_BPN}/params \
    ${ros_datadir}/${ROS_BPN}/models \
"