DESCRIPTION = "Ackermann Robot Bringup Files"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main"
SRCREV = "e4e47a00926bd99b107db5a65eb91d77d1465f21"

S = "${WORKDIR}/git/src/ackermann_bringup"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    rclcpp \
    geometry-msgs \
    std-msgs \
    std-srvs \
    action-msgs \
    nav2-msgs \
"

# removed rviz and teleop as we sont be needing them on the project
# also remvoed slam-toolbox as it will be done on a separate computer and not on the robot itself

ROS_EXEC_DEPENDS = " \
    rclcpp \
    rclpy \
    geometry-msgs \
    nav-msgs \
    sensor-msgs \
    std-msgs \
    std-srvs \
    ament-index-python \
    launch \
    launch-ros \
    ackermann-description \
    xacro \
    ackermann-hardware \
    robot-state-publisher \
    joint-state-publisher \
    tf2-ros \
    controller-manager \
    joint-state-broadcaster \
    imu-sensor-broadcaster \
    ackermann-steering-controller \
    twist-mux \
    robot-localization \
    pointcloud-to-laserscan \
    livox-ros-driver2 \
"
# Nav2 (only the packages we actually need)
ROS_EXEC_DEPENDS += " \
    nav2-amcl \
    nav2-behavior-tree \
    nav2-behaviors \
    nav2-bt-navigator \
    nav2-common \
    nav2-controller \
    nav2-core \
    nav2-costmap-2d \
    nav2-lifecycle-manager \
    nav2-map-server \
    nav2-mppi-controller \
    nav2-msgs \
    nav2-planner \
    nav2-smac-planner \
    nav2-smoother \
    nav2-util \
    nav2-velocity-smoother \
    nav2-waypoint-follower \
    spatio-temporal-voxel-layer \
"


DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

# for swupdate (bundled by ackermann-update)
do_deploy() {
    tar czf ${DEPLOY_DIR_IMAGE}/ackermann-bringup-${MACHINE}.tar.gz \
        --warning=no-file-changed \
        -C ${D} .
}
addtask deploy after do_install before do_build