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

# --- OTA  ---
IMAGE_INSTALL:append = " ivi-ota-agent"


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


