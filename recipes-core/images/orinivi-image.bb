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

# Mirrors the head unit over RDP on :3389, ON BY DEFAULT, so the board can be driven
# with no panel attached. The compositor stays on the DRM backend, so the app still
# renders Quick3D on the GPU. Idle cost is ~35% of one core with nobody connected and
# no frame-rate loss; a connected viewer costs ~2 cores and takes 60 fps to 52.
# Disable per board with `weston-remote-display off`.
#
# NOTE: the RDP backend does NO authentication. Anyone who can reach port 3389 gets
# full control of the head unit. Drop this line for a build that must not expose it.
IMAGE_INSTALL:append = " weston-remote-display "

# Virtual microphone, ON BY DEFAULT. This board has no audio capture hardware at all, so
# the IVI app's Vosk recognition has nothing to listen to; this decodes an Opus/RTP
# stream from a developer's laptop into a PulseAudio null sink whose monitor is the
# default capture source. Sender script ships at
# /usr/share/ivi-remote-mic/ivi-remote-mic-send — copy it to the laptop.
#
# NOTE: this listens on UDP 5004 with no authentication, and what arrives is fed to the
# VOICE COMMAND path — a step beyond the RDP exposure above, which only lets someone
# watch. Drop this line for a build that must not accept audio from the network.
IMAGE_INSTALL:append = " ivi-remote-mic "

# Auto-mounts a USB-connected phone (MTP) at the gvfs-shaped path the IVI app already
# looks for, so its media browser picks it up with no app changes. Pulls in simple-mtpfs
# and libmtp. Idle cost is nothing — a udev rule and a template unit that only runs while
# a phone is attached.
IMAGE_INSTALL:append = " ivi-mtp-mount "

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
# Stable /dev/camera-front symlink (the Brio moves between /dev/videoN across replugs)
IMAGE_INSTALL:append = " camera-udev-rules "
# Boot clock policy: runs jetson_clocks so the GPU does not sit parked at 306 MHz
# (pulls in tegra-tools-jetson-clocks via RDEPENDS; listed explicitly for visibility)
IMAGE_INSTALL:append = " gpu-clock-policy tegra-tools-jetson-clocks "
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


## ---- ROS2 -TESTING ONLY ---
IMAGE_INSTALL:append = " \
    rosbag2 \
    ros2bag \
    rosbag2-transport \
    rosbag2-storage-mcap \
    rosbag2-compression-zstd \
    joy \
    teleop-twist-joy \
    teleop-twist-keyboard \
"