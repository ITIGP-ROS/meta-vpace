DESCRIPTION = "3D object detection messages (KITTI-style detections for the IVI Drive View)"
MAINTAINER = "Ragib Arnab <rae3840924@gmail.com>"

# CLOSED until ros2-lidar-perception gets a root LICENSE (package.xml says MIT,
# but that's not a license text to checksum). Then switch to MIT + LIC_FILES_CHKSUM.
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

# Repointed from ros2-lidar-object-detection (Python pipeline) to the merged
# ros2-lidar-perception repo, so msgs stay versioned with the C++ detector/tracker
# that produce them. The old submodule still has a same-named package and should
# be dropped from ros2_ws_gp once nothing depends on the Python pipeline.
require conf/include/ros2-lidar-perception.inc

S = "${WORKDIR}/git/src/object_detection_msgs"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    rosidl-default-generators-native \
    geometry-msgs \
"

ROS_EXEC_DEPENDS = " \
    geometry-msgs \
    rosidl-default-runtime \
"

DEPENDS = "${ROS_BUILD_DEPENDS} patchelf-native"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS}"

do_configure[network] = "1"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}

FILES_SOLIBSDEV = ""
