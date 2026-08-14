SUMMARY = "Orin IVI Minimal Image"
LICENSE = "CLOSED"

# Inherit NVIDIA Weston base (graphics, kernel, device tree, weston-init)
require recipes-demo/images/demo-image-weston.bb


# --- ROS2 integration ---
inherit ros_distro_${ROS_DISTRO}
inherit ${ROS_DISTRO_TYPE}_image

# --- ROS2 core ---
IMAGE_INSTALL:append = " \
    ros-core \
"
IMAGE_INSTALL:append = " bash "
VIRTUAL-RUNTIME_sh = "bash"


# --- SWUpdate integration ---
IMAGE_INSTALL:append = " swupdate "
IMAGE_INSTALL:append = " swupdate-key "
IMAGE_INSTALL:append = " data-partition-mount "
IMAGE_FSTYPES:append = " tar.gz"

# --- our ROS2 packages ---
IMAGE_INSTALL:append = " \
    livox-ros-driver2 \
    ackermann-hardware \
    ackermann-description \
    ackermann-bringup \
    camera-sign-detect-bringup \
"

# --- IVI ---
IMAGE_INSTALL:append = " ivi "

# Touch support
IMAGE_INSTALL:append = " libinput libinput-bin"
IMAGE_INSTALL:append = " \
    kernel-module-hid-multitouch \
    "

# --- OTA & Telemetry ---
IMAGE_INSTALL:append = " ivi-ota-agent"
IMAGE_INSTALL:append = " jetson-status-agent"

# --- Network configuration ---

IMAGE_INSTALL:append = " networkmanager-nmcli "

#  WiFi kernel module and firmware
IMAGE_INSTALL:append = " kernel-module-mt7601u linux-firmware-mt7601u "
# CAN kernel modules
IMAGE_INSTALL:append = " can-utils kernel-module-can kernel-module-mttcan kernel-module-can-raw "
# USB camera kernel module
IMAGE_INSTALL:append = " kernel-module-uvcvideo "
# CAN interface configuration
IMAGE_INSTALL:append = " can-config "
# NetworkManager handles ethernet (nm-config provides connection profile)
# static-eth (systemd-networkd) is intentionally replaced
IMAGE_INSTALL:append = " nm-config "

# Wifi-credential sender (pushes SSID/password)
IMAGE_INSTALL:append = " wifi-cred-sender "

# CAN node-liveness responder (echoes host ping 0x7A0 -> 0x7A2)
IMAGE_INSTALL:append = " liveliness-respond "

# --- Qt6 multimedia config ---
PACKAGECONFIG:append:pn-qtmultimedia = " gstreamer alsa pulseaudio "
PACKAGECONFIG:append:pn-pulseaudio   = " systemd "


# --- Data recording (rosbag2) ---
# The image installs ros-core only, which does not include rosbag2, so the
# target currently cannot record sensor data at all -- `ros2 bag` is not even a
# valid verb on the device.
#
# Recording over WiFi is not an option: the LiDAR stream measures 5.21 MB/s
# (~41.7 Mbit/s) and a single PointCloud2 is ~520 KB, fragmenting into ~370 UDP
# datagrams -- losing any one drops the whole sample. The image ships
# kernel-module-mt7601u; MT7601U is a 1x1 2.4 GHz-only chipset.
#
# Storage is not a constraint: / is rw with 18.1 GB free (~55 min at 18.75 GB/h).
#
# ros2bag is listed explicitly even though the rosbag2 metapackage already
# RDEPENDS on it: the `ros2 bag record` / `ros2 bag play` CLI verbs come from
# ros2bag, and naming it turns a mistake into a build error rather than a
# discovery made on the robot mid-session.
#
# rosbag2-storage-mcap is NOT optional here -- it is absent from the rosbag2
# metapackage's RDEPENDS, so without this line the plugin is simply not built.
# mcap is preferred over the default sqlite3 storage because it is chunked and
# append-only, so a bag survives abrupt power loss (the vehicle kill switch).
# mcap-vendor is deliberately NOT listed: rosbag2-storage-mcap RDEPENDS on it.
IMAGE_INSTALL:append = " \
    rosbag2 \
    ros2bag \
    rosbag2-transport \
    rosbag2-storage-mcap \
    rosbag2-compression-zstd \
"

# --- Teleop (on-target, off the WiFi link) ---
# Measured on this vehicle: with the publisher steady at 20 Hz and the link
# nominally up, 18.8 % of control cycles commanded zero, across 22 dropout
# episodes in 10.4 s. That is safe -- the controller zeroes in a measured
# 33.36 ms, and it stays zero -- but it produces stuttering motion, which is
# unusable for collecting a detection dataset. Running teleop on the target
# removes the network from the control loop entirely.
IMAGE_INSTALL:append = " \
    joy \
    teleop-twist-joy \
    teleop-twist-keyboard \
"
