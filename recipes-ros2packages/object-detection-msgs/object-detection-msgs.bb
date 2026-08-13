DESCRIPTION = "3D object detection messages (KITTI-style detections for the IVI Drive View)"
MAINTAINER = "Ragib Arnab <rae3840924@gmail.com>"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${WORKDIR}/git/LICENSE;md5=f4931a5db767fe919e150121404ed4f0"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2-lidar-object-detection.git;protocol=https;branch=master"
SRCREV = "ab272101bb76bf249066da22c9ab3e4612d8d1d7"

# The package sits under src/ inside the repo
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
