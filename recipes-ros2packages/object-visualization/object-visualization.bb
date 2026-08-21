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

# rclpy is deliberately listed here even though package.xml does NOT declare it.
# object3d_visualizer_node.py imports rclpy on its first line, so a target
# without it gets an ImportError the moment the node starts -- and because the
# launch file runs the visualiser as a plain Node, that failure would show up as
# a dead visualiser next to a perfectly healthy detector, which reads like a
# marker/RViz problem rather than a missing package. The right long-term fix is
# to add <exec_depend>rclpy</exec_depend> to package.xml upstream.
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
