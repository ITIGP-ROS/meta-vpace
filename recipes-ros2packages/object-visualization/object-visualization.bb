DESCRIPTION = "RViz marker visualiser for object_detection_msgs/Object3dArray"
MAINTAINER = "Ragib Arnab <rae3840924@gmail.com>"

# setup.py says MIT; package.xml still says "TODO: License declaration" and
# there is no licence text in the package to checksum. CLOSED until a LICENSE
# lands at the repo root, then switch to MIT the same way as
# object-detection-msgs.bb.
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_python

require conf/include/ros2-lidar-perception.inc

S = "${WORKDIR}/git/src/object_visualization"

ROS_BUILD_DEPENDS = ""

# rclpy listed here even though package.xml doesn't declare it -- the node imports
# it on its first line, so without this it's an ImportError that looks like an
# RViz/marker problem. Should be added to package.xml upstream instead.
ROS_EXEC_DEPENDS = " \
    rclpy \
    geometry-msgs \
    visualization-msgs \
    object-detection-msgs \
"

DEPENDS = "${ROS_BUILD_DEPENDS} python3-setuptools-native"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS}"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}
