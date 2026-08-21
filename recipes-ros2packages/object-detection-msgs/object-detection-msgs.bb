DESCRIPTION = "3D object detection messages (KITTI-style detections for the IVI Drive View)"
MAINTAINER = "Ragib Arnab <rae3840924@gmail.com>"

# Was MIT against ros2-lidar-object-detection's root LICENSE. The merged
# ros2-lidar-perception repo has no root LICENSE yet, and there is nothing in
# the package itself to checksum -- package.xml says MIT but package.xml is not
# a licence text. CLOSED is honest in the meantime and matches how
# camera-sign-detect-bringup.bb handles the same gap.
#
# Once a LICENSE lands at the repo root, replace the line below with:
#     LICENSE = "MIT"
#     LIC_FILES_CHKSUM = "file://${WORKDIR}/git/LICENSE;md5=<new md5>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

# REPOINTED. This used to fetch ros2-lidar-object-detection (the Python
# pipeline) at ab27210. The message definitions now live in the merged
# ros2-lidar-perception repo alongside the C++ detector and tracker that
# produce them, so both are versioned together and cannot drift apart.
#
# Consequence for the workspace: src/ros2-lidar-object-detection also contains
# an object_detection_msgs, so having BOTH submodules checked out gives colcon
# two packages of the same name. On the Yocto side there is no ambiguity (only
# this recipe exists), but the old submodule should be dropped from
# ros2_ws_gp once nothing depends on the Python pipeline.
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
