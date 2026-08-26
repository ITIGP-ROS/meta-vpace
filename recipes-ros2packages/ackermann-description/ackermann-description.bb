DESCRIPTION = "Ackermann Robot Model Description Files"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main"
SRCREV = "e4e47a00926bd99b107db5a65eb91d77d1465f21"

S = "${WORKDIR}/git/src/ackermann_description"

ROS_BUILD_DEPENDS = "ament-cmake-native"
ROS_EXEC_DEPENDS = "xacro"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

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
    tar czf ${DEPLOYDIR}/ackermann-description-${MACHINE}.tar.gz \
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