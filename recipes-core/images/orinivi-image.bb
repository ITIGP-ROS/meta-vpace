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
#
# Two radios are supported: the MT7601U USB dongle and the Intel 8265 M.2 card. Both sets
# of modules are installed unconditionally so the image boots with WiFi whichever one is
# fitted -- the unused driver costs a probe that finds no device.
#
# The kernel fragments (linux-jammy-nvidia-tegra_5.15.bbappend) already set CONFIG_IWLWIFI=m
# and CONFIG_IWLMVM=m, so the modules were being BUILT and packaged all along; they simply
# were not in IMAGE_INSTALL, which left the 8265 sitting on the PCI bus with driver=NONE and
# no wireless interface at all. A kernel fragment enables a build, it does not install it.
IMAGE_INSTALL:append = " kernel-module-mt7601u linux-firmware-mt7601u "
IMAGE_INSTALL:append = " kernel-module-iwlwifi kernel-module-iwlmvm linux-firmware-iwlwifi-8265 "

# Bluetooth for the Intel 8265. The BT half of the card is a USB function (8087:0a2b), not
# PCIe, so it needs the USB HCI transport rather than anything on the PCI side.
#
# ALL FOUR MODULES ARE REQUIRED -- btrtl and btbcm are NOT optional here despite the card
# being Intel. btusb is compiled with CONFIG_BT_HCIBTUSB_RTL=y and CONFIG_BT_HCIBTUSB_BCM=y,
# so it references btrtl_setup_realtek/btrtl_shutdown_realtek and the btbcm symbols
# unconditionally at load time. Ship btusb without btrtl and modprobe fails outright with
# "Unknown symbol btrtl_setup_realtek" -- the Intel radio never appears, and the error names
# a Realtek symbol, which sends you looking in the wrong place entirely.
#
# ibt-12-16 is the correct firmware for the 8265 specifically (the .sfi is the operational
# image, the .ddc the tuning parameters); the 8260 takes ibt-11-5 and the 7265 ibt-hw-37-8.
IMAGE_INSTALL:append = " kernel-module-btusb kernel-module-btintel kernel-module-btrtl kernel-module-btbcm "
IMAGE_INSTALL:append = " linux-firmware-ibt-12-16 "
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
"

# --- TensorRT dev tools (ONNX -> TRT engine conversion on device) ---
#
# NVIDIA requires TensorRT engines be built ON THE TARGET, so an x86 build host cannot produce
# them -- and until now neither could the Jetson, because tensorrt-core ships libnvinfer but no
# ONNX parser. That blocked all TensorRT work. With the parser present we ship a portable .onnx
# and let the device build and cache its own engine on first boot, which also removes a real
# failure mode: a prebuilt .engine is bound to an exact TensorRT version and stops loading
# silently if the image moves to a different L4T.
#
# tensorrt-plugins-prebuilt is what carries libnvonnxparser.so.10.3.0 (plus .so.10 and .so).
# It also brings libnvinfer_plugin, which CUDA-PointPillars does NOT need -- that registers its
# pillar-scatter plugin from inside its own binary via REGISTER_TENSORRT_PLUGIN -- but the two
# ship in the same package.
#
# TWO THINGS THAT WILL OTHERWISE COST SOMEONE AN AFTERNOON:
#
#   * trtexec IS NOT ON $PATH. tensorrt-trtexec-prebuilt sets FILES:${PN} to
#     ${prefix}/src/tensorrt/bin, so it lands at /usr/src/tensorrt/bin/trtexec.
#
#   * The UNVERSIONED libnvonnxparser.so symlink goes to the -dev package, per the default
#     FILES_SOLIBSDEV. Runtime is fine -- the SONAME is libnvonnxparser.so.10, which is in the
#     runtime package -- but anything COMPILING against -lnvonnxparser on the device also needs
#     tensorrt-plugins-prebuilt-dev.
#
# No PREFERRED_PROVIDER needed here: meta-tegra's tegra-common.inc already pins
# tensorrt-plugins and tensorrt-trtexec to the -prebuilt recipes, so the source-built
# tensorrt-plugins under meta-tegra/external/openembedded-layer/ does not collide.
#
# This image is TensorRT 10.3.0.30 (libnvinfer.so.10.3.0), NOT 8.6.
IMAGE_INSTALL:append = " \
    tensorrt-trtexec-prebuilt \
    tensorrt-plugins-prebuilt \
"