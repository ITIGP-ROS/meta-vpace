DESCRIPTION = "Ackermann Robot Bringup Files"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake
inherit systemd

SRC_URI = "\
    git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main \
    file://ackermann-drive.service \
    file://ackermann-lidar.service \
    file://ackermann-localization.service \
    file://ackermann-navigation.service \
    file://ackermann-joystick.service \
    file://ackermann-drive-ready \
    file://ackermann-drive-watchdog \
    file://ackermann-drive-watchdog.service \
"
SRCREV = "20deeab4c5e8959cc1a2b09a201f7ecdfcf3abaa"

S = "${WORKDIR}/git/src/ackermann_bringup"

# S points into the git clone, so the file:// entries above need their own path. Same
# arrangement as camera-sign-detect-bringup.bb and update-coordinator.bb, which mix the
# two fetchers the same way.
UNPACKDIR ?= "${WORKDIR}"

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
    joy \
    lifecycle-msgs \
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

SYSTEMD_SERVICE:${PN} = "\
    ackermann-drive.service \
    ackermann-lidar.service \
    ackermann-localization.service \
    ackermann-navigation.service \
    ackermann-joystick.service \
    ackermann-drive-watchdog.service \
"
# The ordered units enable via their own [Install] sections. ackermann-joystick.service
# is the exception: it has no [Install], since 99-vpace-gamepad.rules starts it when a
# pad appears instead. Same arrangement as camera-sign-detect-bringup.bb.
SYSTEMD_AUTO_ENABLE = "enable"

# 99-vpace-gamepad.rules' SYMLINK+="gamepad" gives the joystick unit's BindsTo= a
# stable device name -- both halves are required for it to ever start.
RDEPENDS:${PN} += "gamepad-udev-rules"

FILES:${PN} += "\
    ${systemd_system_unitdir}/ackermann-drive.service \
    ${systemd_system_unitdir}/ackermann-lidar.service \
    ${systemd_system_unitdir}/ackermann-localization.service \
    ${systemd_system_unitdir}/ackermann-navigation.service \
    ${systemd_system_unitdir}/ackermann-joystick.service \
    ${systemd_system_unitdir}/ackermann-drive-watchdog.service \
    ${bindir}/ackermann-drive-ready \
    ${bindir}/ackermann-drive-watchdog \
"

do_install:append() {
    install -d ${D}${systemd_system_unitdir}
    for u in ackermann-drive ackermann-lidar ackermann-localization \
             ackermann-navigation ackermann-joystick ackermann-drive-watchdog; do
        install -m 0644 ${UNPACKDIR}/$u.service ${D}${systemd_system_unitdir}/$u.service
    done
    install -d ${D}${bindir}
    install -m 0755 ${UNPACKDIR}/ackermann-drive-ready ${D}${bindir}/ackermann-drive-ready
    install -m 0755 ${UNPACKDIR}/ackermann-drive-watchdog ${D}${bindir}/ackermann-drive-watchdog
}

# for swupdate (bundled by ackermann-update)
#
# --owner/--group, not fakeroot: PSEUDO_IGNORE_PATHS excludes ${WORKDIR}/deploy-,
# so fakeroot wouldn't fix the uid stamped into the tarball anyway. DEPLOYDIR (not
# DEPLOY_DIR_IMAGE) puts this under sstate.
inherit deploy
do_deploy() {
    tar czf ${DEPLOYDIR}/ackermann-bringup-${MACHINE}.tar.gz \
        --owner=root:0 --group=root:0 \
        --warning=no-file-changed \
        -C ${D} .
}
# After do_populate_sysroot too: it hardlinks ${D}, bumping ctimes, and tar
# fails at random if a ctime moves mid-read. --warning hides the message, not
# the exit code.
addtask deploy after do_install do_populate_sysroot do_package before do_build