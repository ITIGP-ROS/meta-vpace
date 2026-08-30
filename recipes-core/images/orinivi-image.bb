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
    lidar-perception-bringup \
"

# --- IVI ---
IMAGE_INSTALL:append = " ivi "

# Mirrors the head unit over RDP on :3389, on by default (`weston-remote-display off`
# to disable). No authentication -- anyone reaching port 3389 gets full control.
IMAGE_INSTALL:append = " weston-remote-display "

# Virtual mic, on by default: this board has no capture hardware, so this decodes an
# Opus/RTP stream from a laptop into a PulseAudio null sink for Vosk. Listens on UDP
# 5004 with no authentication, feeding the voice command path directly.
IMAGE_INSTALL:append = " ivi-remote-mic "

# Auto-mounts a USB phone (MTP) at the gvfs-shaped path the IVI app already looks for.
IMAGE_INSTALL:append = " ivi-mtp-mount "

# Touch support
IMAGE_INSTALL:append = " libinput libinput-bin"
IMAGE_INSTALL:append = " \
    kernel-module-hid-multitouch \
    "

# JoySticks support
IMAGE_INSTALL:append = " kernel-module-xpad kernel-module-joydev kernel-module-uhid "

# --- OTA & Telemetry ---
IMAGE_INSTALL:append = " ivi-ota-agent"
IMAGE_INSTALL:append = " jetson-status-agent"

# --- Network configuration ---

IMAGE_INSTALL:append = " networkmanager-nmcli "

#  WiFi kernel module and firmware
#
# Both the MT7601U USB dongle and Intel 8265 M.2 card are installed unconditionally
# so the image boots with WiFi whichever is fitted -- the unused driver just probes
# and finds nothing. The kernel fragment enables these builds but doesn't install
# them, so they still need to be listed explicitly here.
IMAGE_INSTALL:append = " kernel-module-mt7601u linux-firmware-mt7601u "
IMAGE_INSTALL:append = " kernel-module-iwlwifi kernel-module-iwlmvm linux-firmware-iwlwifi-8265 "

# Bluetooth for the Intel 8265 (USB HCI, not PCIe). All four modules are required --
# btusb references btrtl/btbcm symbols unconditionally at load time even on this
# Intel card, so dropping either fails modprobe with a misleading Realtek error.
# ibt-12-16 is the 8265-specific firmware (8260 takes ibt-11-5, 7265 ibt-hw-37-8).
IMAGE_INSTALL:append = " kernel-module-btusb kernel-module-btintel kernel-module-btrtl kernel-module-btbcm "
IMAGE_INSTALL:append = " linux-firmware-ibt-12-16 "
# bluetoothd policy. bluez5 ships no main.conf, so without this the adapter is DOWN
# after every boot and the gamepad cannot connect until somebody brings hci0 up by hand.
IMAGE_INSTALL:append = " bluez-config "
# CAN kernel modules
IMAGE_INSTALL:append = " can-utils kernel-module-can kernel-module-mttcan kernel-module-can-raw "
# USB camera kernel module
IMAGE_INSTALL:append = " kernel-module-uvcvideo "
# Stable /dev/camera-front symlink (the Brio moves between /dev/videoN across replugs)
IMAGE_INSTALL:append = " camera-udev-rules gamepad-udev-rules "
# Disable the Tegra Security Engine crypto driver -- it oopses and the CCPLEX
# watchdog then resets the board. See recipes-support/tegra-se-blacklist/.
IMAGE_INSTALL:append = " tegra-se-blacklist "
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


# --- Timestamp the build for traceability ---
IMAGE_POSTPROCESS_COMMAND += "write_vpace_build; "
write_vpace_build() {
    echo "${METADATA_REVISION} ${DATE}" > ${IMAGE_ROOTFS}/etc/vpace-build
}



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
    ros2controlcli \
    compressed-image-transport \
"

# --- TensorRT dev tools (ONNX -> TRT engine conversion on device) ---
#
# TensorRT engines must be built on-target; tensorrt-plugins-prebuilt is what brings
# the ONNX parser (libnvonnxparser) needed for that. Two gotchas: trtexec lands at
# /usr/src/tensorrt/bin/trtexec, not on $PATH; and the unversioned libnvonnxparser.so
# symlink is in the -dev package, needed for anything compiling against it on-device.
#
# This image is TensorRT 10.3.0.30, not 8.6.
IMAGE_INSTALL:append = " \
    tensorrt-trtexec-prebuilt \
    tensorrt-plugins-prebuilt \
"