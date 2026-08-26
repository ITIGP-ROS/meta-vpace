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
"
SRCREV = "c4f7dae30d0f265cd685e233d27d8140f62ecf57"

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
"
# The four ordered units are pulled in at boot by their own [Install] sections, so the
# vehicle comes up drivable and with Nav2 idle and ready for a goal.
# ackermann-joystick.service is the exception and has NO [Install]: 99-vpace-gamepad.rules
# starts it when a pad appears. Listing it here still installs and registers the unit --
# enabling is what the rule replaces. Same arrangement as camera-sign-detect-bringup.bb.
SYSTEMD_AUTO_ENABLE = "enable"

# 99-vpace-gamepad.rules is what starts ackermann-joystick.service, and its
# SYMLINK+="gamepad" is what gives that unit's BindsTo= a stable device name. Installed
# apart from this package the unit would never start; installed here without the unit the
# rule would name a service that does not exist.
RDEPENDS:${PN} += "gamepad-udev-rules"

FILES:${PN} += "\
    ${systemd_system_unitdir}/ackermann-drive.service \
    ${systemd_system_unitdir}/ackermann-lidar.service \
    ${systemd_system_unitdir}/ackermann-localization.service \
    ${systemd_system_unitdir}/ackermann-navigation.service \
    ${systemd_system_unitdir}/ackermann-joystick.service \
    ${bindir}/ackermann-drive-ready \
"

do_install:append() {
    install -d ${D}${systemd_system_unitdir}
    for u in ackermann-drive ackermann-lidar ackermann-localization \
             ackermann-navigation ackermann-joystick; do
        install -m 0644 ${UNPACKDIR}/$u.service ${D}${systemd_system_unitdir}/$u.service
    done
    install -d ${D}${bindir}
    install -m 0755 ${UNPACKDIR}/ackermann-drive-ready ${D}${bindir}/ackermann-drive-ready
}

# for swupdate (bundled by ackermann-update)
#
# --owner/--group rather than a fakeroot task: everything ros_ament_cmake puts in ${D} is
# root-owned, but tar stamps the build user's uid into the archive unless told otherwise,
# and the target's `tar xzf` then restores that into /opt/ros/humble. Marking do_deploy
# fakeroot does not fix it -- bitbake.conf lists ${WORKDIR}/deploy- in PSEUDO_IGNORE_PATHS,
# so the archive is written outside pseudo either way, and sstate's outhash then dies
# looking uid 1000 up against the target passwd.
# DEPLOYDIR rather than DEPLOY_DIR_IMAGE: that is what puts the tarball under sstate, so a
# do_install sstate hit (which leaves ${D} empty) still yields a tarball.
inherit deploy
do_deploy() {
    tar czf ${DEPLOYDIR}/ackermann-bringup-${MACHINE}.tar.gz \
        --owner=root:0 --group=root:0 \
        --warning=no-file-changed \
        -C ${D} .
}
# after do_populate_sysroot/do_package, not just do_install: populate_sysroot hardlinks
# ${D} into sysroot-destdir (cpio -pdl), bumping the ctime of every file it links. tar
# re-stats each file after reading it and exits 1 when the ctime moved, so sharing the
# slot fails the task at random -- and --warning=no-file-changed hides the message but
# not the exit code.
addtask deploy after do_install do_populate_sysroot do_package before do_build