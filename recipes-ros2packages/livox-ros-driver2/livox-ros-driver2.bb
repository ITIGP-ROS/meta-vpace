DESCRIPTION = "Livox ROS2 Driver"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/Livox-SDK/livox_ros_driver2.git;protocol=https;branch=master \
           file://0001-fix-qa-so.patch \
           "
SRCREV = "13eb05e4e6dd7a765b934d0c5fd6236676a57b49"

S = "${WORKDIR}/git"

EXTRA_OECMAKE += "-DROS_EDITION=ROS2 -DDISTRO_ROS=humble"

# File substitution (from build.sh)
do_unpack:append() {
    import os
    import shutil
    s = d.getVar('S')
    
    ros2_pkg = os.path.join(s, "package_ROS2.xml")
    pkg_xml = os.path.join(s, "package.xml")
    if os.path.exists(ros2_pkg):
        shutil.copy2(ros2_pkg, pkg_xml)
    
    launch_ros2 = os.path.join(s, "launch_ROS2")
    launch = os.path.join(s, "launch")
    if os.path.exists(launch_ros2):
        if os.path.exists(launch):
            shutil.rmtree(launch)
        shutil.copytree(launch_ros2, launch)
}

ROS_BUILD_DEPENDS = " \
    ament-cmake-auto-native \
    rosidl-default-generators-native \
    rclcpp \
    rclcpp-components \
    std-msgs \
    sensor-msgs \
    rcutils \
    rcl-interfaces \
    pcl \
    pcl-conversions \
"

ROS_EXEC_DEPENDS = " \
    rclcpp \
    rclcpp-components \
    std-msgs \
    sensor-msgs \
    rcutils \
    rcl-interfaces \
    rosidl-default-runtime \
"

DEPENDS = "${ROS_BUILD_DEPENDS} livox-sdk2"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS} livox-sdk2"


do_compile:prepend() {
    export ROS_DISTRO="humble"
}