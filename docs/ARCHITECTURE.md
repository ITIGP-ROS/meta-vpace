# meta-vpace — Architecture and Technology Reference

**The Jetson Orin node of the V-PACE system**

---

## About this document

`meta-vpace` is the Yocto layer that produces the operating system image for one node of
the V-PACE system: the NVIDIA Jetson Orin. V-PACE is a four-node system built as an ITI
graduation project; the other three nodes are separate pieces of hardware with their own
codebases and their own toolchains. This layer builds only the Orin node, and communicates
with the rest over CAN and with a cloud service over MQTT.

The reader is assumed to know Yocto. Topics covered in depth are the ones specific to this
platform: how NVIDIA's Tegra layers constrain the build, how meta-ros maps a ROS 2
distribution into recipes, why TensorRT engines cannot be cross-built, how the SWUpdate A/B
mechanism is wired to the Tegra partition layout, and how the OTA and CAN security
mechanisms fit together.

### What this document covers

- The layer's composition and its relationship to the upstream layers it depends on.
- The `vpace` distribution and the `orinivi-image` it produces.
- Every subsystem on the Orin node: kernel and board bring-up, graphics and the IVI head
  unit, the ROS 2 perception and control stack, GPU inference, connectivity.
- The interfaces this node exposes to the rest of the vehicle and to the cloud.
- The OTA update architecture, end to end.
- The security mechanisms as designed.
- How to build the image and flash a board.

### What this document does not cover

- The internals of the other three nodes. They appear only as peers at the far end of a
  CAN identifier or an MQTT feed.
- Development and bench tooling that is not part of the vehicle's function. Two such
  facilities have their own notes under [`docs/deep-dives/`](deep-dives/).
- A recipe-by-recipe catalogue. The recipes carry their own inline documentation.

---

## Table of contents

1. [Introduction and scope](#1-introduction-and-scope)
2. [Target hardware and platform baseline](#2-target-hardware-and-platform-baseline)
3. [Layer composition](#3-layer-composition)
4. [The `vpace` distribution](#4-the-vpace-distribution)
5. [The `orinivi-image`](#5-the-orinivi-image)
6. [Kernel and board bring-up](#6-kernel-and-board-bring-up)
7. [Graphics and the IVI head unit](#7-graphics-and-the-ivi-head-unit)
8. [The ROS 2 layer](#8-the-ros-2-layer)
9. [GPU and on-device inference](#9-gpu-and-on-device-inference)
10. [Connectivity](#10-connectivity)
11. [Interfaces to the other nodes](#11-interfaces-to-the-other-nodes)
12. [OTA update architecture](#12-ota-update-architecture)
13. [Security mechanisms](#13-security-mechanisms)
14. [Build and flash guide](#14-build-and-flash-guide)
15. [Conventions and maintenance](#15-conventions-and-maintenance)
16. [Glossary](#16-glossary)
17. [Appendix: layer-specific variables](#17-appendix-layer-specific-variables)

---

# 1. Introduction and scope

## 1.1 The node and the system

V-PACE is a system built from four computing nodes joined by a CAN bus. Each node is a
different class of machine chosen for a different class of work, and each is developed
independently:

| Node | Role in the vehicle from this node's point of view |
|---|---|
| Instrument cluster | The CAN peer this node exchanges WiFi credentials with and requests OTA approval from. Referred to in the code as *the cluster* or *the QNX host*. |
| **Jetson Orin** | **The subject of this layer.** Perception, autonomy, the in-vehicle infotainment head unit, and the cloud-facing OTA and telemetry path. |
| Body/edge ECU | A second CAN peer, addressed as *esp32* in the WiFi credential and update-coordination protocols. |
| Vehicle control MCU | A third CAN peer, addressed as *tiva* in the liveness protocol. |

The three peers are reached only over CAN. Section
[11](#11-interfaces-to-the-other-nodes) documents every message that crosses the boundary,
in both directions.

## 1.2 Why the Orin node needs a custom Linux distribution

The Orin node carries a workload that no off-the-shelf image satisfies at once:

- **A GPU-accelerated perception stack.** A 3D LiDAR detector runs a TensorRT-compiled
  PointPillars network on point clouds from a Livox sensor; a camera pipeline runs a YOLO
  variant on a USB camera for traffic-sign detection. Both need CUDA and TensorRT present in
  the sysroot at build time and on the device at runtime, at matching versions.
- **A control stack.** A `ros2_control` hardware interface drives an Ackermann steering
  platform, which pulls in the controller manager, the steering controllers library and a
  URDF model of the vehicle.
- **A graphical head unit.** A Qt 6 application using Qt Quick 3D, running on a Wayland
  compositor with GPU rendering, with audio, Bluetooth, speech recognition and USB media
  browsing.
- **A field-updatable image.** The vehicle must accept signed software updates over the
  air without a laptop, without losing its learned state, and without the possibility of
  being downgraded into a known-bad version by a replayed message.

Each of those individually could be satisfied by a general-purpose distribution and a pile
of packages. Together they cannot, for three reasons that recur throughout the layer:

1. **NVIDIA's stack is not distribution-neutral.** CUDA, TensorRT and the L4T kernel come
   from `meta-tegra`, and anything that links against the Tegra OpenCV inherits a
   `find_package(CUDA)` requirement whether or not it uses CUDA itself. That constraint
   propagates into recipes that look entirely unrelated — see §8.6.
2. **ROS 2 on an embedded target is a build problem, not a runtime problem.** Upstream ROS
   assumes `colcon` on a developer machine with a full toolchain. Cross-compiling it means
   dealing with generated code, native/target tool splits, and `ament` packages that are
   only exported in `-native` form. §8.1 covers what that costs.
3. **The update mechanism has to be designed with the partition layout, not bolted on.**
   Tegra boards have a fixed A/B partition scheme and a bootloader capsule mechanism, and
   SWUpdate has to be told about both. §12 covers it.

Building a purpose-made layer is the answer to all three: the constraints are encoded once,
in configuration that travels with the repository, rather than being rediscovered on each
developer's machine.

## 1.3 Design principles visible in the tree

Four decisions shape almost every file in the layer, and naming them up front makes the
rest of the document shorter.

**Configuration lives in the layer, not in `local.conf`.** `local.conf` is not tracked and
therefore cannot be relied on. Anything whose absence would produce a *silently* wrong
image is pinned in `conf/distro/vpace.conf` or in the image recipe. The `PACKAGECONFIG`
entries in the distro are the clearest example: without the Opus codec enabled in
`gst-plugins-base`, the audio path builds cleanly and simply does not work.

**Failure modes are documented at the point of failure.** The layer's inline comments are
unusually long, and they are long on purpose: nearly every one records a failure that was
expensive to diagnose and would otherwise be rediscovered. A recurring theme is that the
interesting failures on this platform are *silent* — a parameter that does not exist and is
discarded without warning, a shared-memory channel that reports itself connected and
delivers nothing, a kernel module that is built but never installed. Those are the cases
where a comment saves days.

**State that must survive an update lives on a separate partition.** The rootfs is
replaced wholesale by every OTA. Anything that must persist — security counters, learned
network profiles, the installed-version record — is rooted under `/data`, and §12.6
explains why each item is there.

**Licence declarations tell the truth even when the truth is inconvenient.** Two recipes
in the tree declare a licence that differs from what the upstream `package.xml` claims,
because the actual licence file in the tree says something else. §15.3 works through the
example. The image manifest is a legal artefact; making it say the comfortable thing would
defeat its purpose.

## 1.4 How to read the rest

Sections 2–5 are the platform: what hardware is targeted, how the layer is assembled, what
the distribution and image contain. Sections 6–10 are the subsystems, roughly in boot
order. Section 11 is the external interface surface. Section 12 is the update architecture.
Sections 13–17 are reference material.

Sections 1, 2, 5, 8, 9 and 12 alone give the shape of the system.

---

# 2. Target hardware and platform baseline

## 2.1 The compute module

| Property | Value |
|---|---|
| Yocto `MACHINE` | `p3768-0000-p3767-0001` |
| Module | NVIDIA Jetson Orin NX, part number P3767-0001 |
| Carrier board | P3768, the Orin Nano/NX developer kit carrier |
| SoC architecture | Ampere, compute capability 8.7 (`sm_87`) |
| Yocto release | scarthgap |
| Distribution | `vpace`, derived from `tegrademo` |
| TensorRT | 10.3.0.30 (`libnvinfer.so.10.3.0`) |
| Kernel | `linux-jammy-nvidia-tegra` 5.15, NVIDIA's L4T tree |

The machine name is NVIDIA's own convention: `<carrier>-<module>`. It matters more than it
looks, because it selects the device tree, the partition layout, the flashing package and
the set of partition names that SWUpdate writes into — all of which appear again in §12.

The compute capability, `8.7`, appears explicitly in two recipes as
`CUDA_ARCHITECTURES = "87"`, and it is not a detail that can be left to a default. A
TensorRT engine is compiled for one architecture and refuses to load on another, and CUDA
kernels compiled with the wrong `-gencode` either fail to load or fall back to JIT
compilation at first use. §9 covers the consequences.

## 2.2 Attached hardware

The image ships drivers and firmware for the following, all of which appear as explicit
entries in `recipes-core/images/orinivi-image.bb`:

| Device | Attachment | Software support in this layer |
|---|---|---|
| Livox LiDAR | Ethernet | `livox-ros-driver2`, `livox-sdk2`; the wired Ethernet link is dedicated to it |
| USB camera | USB (UVC) | `kernel-module-uvcvideo`, a stable `/dev/camera-front` symlink, `v4l2_camera` with a frame-rate patch |
| CAN bus | Tegra MTTCAN controller | `kernel-module-can`, `kernel-module-mttcan`, `kernel-module-can-raw`, `can-utils`, a `systemd-networkd` link profile |
| WiFi — MT7601U | USB dongle | `kernel-module-mt7601u`, `linux-firmware-mt7601u` |
| WiFi — Intel 8265 | M.2 (PCIe) | `kernel-module-iwlwifi`, `kernel-module-iwlmvm`, `linux-firmware-iwlwifi-8265` |
| Bluetooth — Intel 8265 | USB function of the same card (`8087:0a2b`) | `btusb`, `btintel`, `btrtl`, `btbcm`, `linux-firmware-ibt-12-16` |
| Touch panel | USB HID | `kernel-module-hid-multitouch`, `libinput` |
| Gamepad | USB / HID | `kernel-module-xpad`, `kernel-module-joydev`, `kernel-module-uhid` |
| Phone (media source) | USB MTP | `ivi-mtp-mount`, `simple-mtpfs`, `libmtp` |

Two of those rows carry non-obvious detail that §6 and §10 return to. Both WiFi radios ship
unconditionally because a given board may have either fitted, and the cost of the unused
driver is a probe that finds no device. The Bluetooth row lists four modules for an Intel
card because `btusb` is compiled with the Realtek and Broadcom setup paths enabled and
references their symbols unconditionally at load time; omitting `btrtl` makes `modprobe`
fail with an error naming a Realtek symbol on a board with no Realtek hardware.

Notably absent: **audio capture**. The board has no microphone input of any kind. The head
unit's speech recognition therefore has nothing to listen to on a bench, which is what the
remote-microphone facility in [`docs/deep-dives/remote-mic-testing.md`](deep-dives/remote-mic-testing.md)
exists to work around during development.

## 2.3 Storage and partition layout

The board boots from NVMe. The layout that matters to this layer:

| Partition | Purpose | Written by |
|---|---|---|
| `APP` | Rootfs slot A | Flashing, or an OTA installing into slot B's counterpart |
| `APP_b` | Rootfs slot B | The same, inverted |
| Kernel A / B | Kernel image per slot | SWUpdate, per slot |
| Kernel DTB A / B | Device tree per slot | SWUpdate, per slot |
| ESP | EFI system partition, bootloader capsule staging | SWUpdate, as an archive |
| `nvme0n1p15` (UDA) | `/data` — persistent state | The running system |

The A/B pair is the mechanism that makes an OTA safe: an update writes the *inactive* slot
in full, and the bootloader switches slots only after the write is complete and verified.
A failed or interrupted update leaves the running slot untouched. §12.2 covers the
`sw-description` that expresses this.

`/data` is the counterweight. Because an update replaces an entire rootfs, everything under
`/etc` and `/var` is discarded on each update. Three categories of state cannot tolerate
that and are rooted on `/data` instead — SecOC freshness counters, NetworkManager's learned
profiles, and the OTA installed-version record. `recipes-core/data-partition-mount/` creates
and mounts the partition at boot, formatting it if it is unformatted, and §12.6 explains
each directory it creates.

One caveat: `/data` survives an
SWUpdate rootfs swap, but a full NVIDIA `flash.sh` that rewrites the partition table can
take `nvme0n1p15` with it. The script's `blkid "$DATA_DEV" || mkfs.ext4 "$DATA_DEV"` will
then silently create a fresh, empty filesystem. That is the correct behaviour for a new
board and a data-loss event for a provisioned one.

## 2.4 The software baseline underneath

The image is not built from scratch. It descends from NVIDIA's demonstration distribution,
which supplies the kernel, the graphics stack, the CUDA toolchain and a working Weston
configuration for Tegra:

```
poky (scarthgap)
  └── meta-tegra                 L4T kernel, CUDA, TensorRT, Tegra graphics
       └── meta-tegrademo        tegrademo distro, demo-image-weston, swupdate integration
            └── vpace            this layer's distro
                 └── orinivi-image
```

Both the distribution (`conf/distro/vpace.conf` begins with
`require conf/distro/tegrademo.conf`) and the image (`orinivi-image.bb` begins with
`require recipes-demo/images/demo-image-weston.bb`) are extensions rather than
replacements. That choice is what makes the layer as small as it is: the graphics stack,
the kernel packaging, the device tree handling and the Tegra-specific SWUpdate plumbing are
all inherited, and this layer's job is to add the vehicle to them.

The cost of that choice appears exactly once, in the distribution, as
`INHERIT:remove = "tegra-support-sanity"` — see §4.3.

---

# 3. Layer composition

## 3.1 Identity and priority

`conf/layer.conf` is conventional in shape. The values that carry meaning:

| Variable | Value | Consequence |
|---|---|---|
| `BBFILE_COLLECTIONS` | `meta-vpace` | The layer's name everywhere else |
| `BBFILE_PRIORITY_meta-vpace` | `6` | Higher than the layers it appends to, so its `.bbappend` files and recipe overrides win |
| `LAYERSERIES_COMPAT_meta-vpace` | `scarthgap` | Refuses to parse against any other Yocto release |
| `LICENSE_FLAGS_ACCEPTED` | `commercial_gstreamer1.0-plugins-ugly` | Pre-accepts the one commercial-flagged package the media path needs |

The priority of 6 is chosen relative to the layers this one appends to, not as an absolute.
The `.bbappend` files in `recipes-bbappends/` target recipes in `meta-ros2-humble`,
`meta-swupdate`, `meta-tegrademo` and poky, and this layer must be the last word on them.

`LAYERSERIES_COMPAT` being a single release rather than a list is deliberate. The layer
uses scarthgap-era constructs — `UNPACKDIR`, the current `FILESEXTRAPATHS` semantics, the
scarthgap licence name set — and several `.bbappend` files exist specifically to correct
scarthgap-era upstream behaviour. Claiming compatibility with a release nobody has tested
would convert a clean parse-time refusal into a confusing build-time failure.

The `LICENSE_FLAGS_ACCEPTED` line pre-accepts `gstreamer1.0-plugins-ugly`. That entry is a
layer-level rather than build-level decision because the head unit's media playback needs
it, and a developer who has to discover the requirement from a build failure has already
lost an hour.

## 3.2 The dependency set, and what each layer actually supplies

`LAYERDEPENDS_meta-vpace` lists eleven layers. The list is maintained on a strict rule that
is stated in the file itself and is worth repeating, because it is unusual: **only direct
dependencies are listed.** A layer that arrives transitively — `meta-python`, pulled in by
both `meta-qt6` and the ROS layers — is deliberately absent, because listing it would
obscure which relationships this layer actually asserts.

| Dependency | What this layer takes from it |
|---|---|
| `core` | oe-core. |
| `tegra` | `meta-tegra`: `cuda.bbclass`, inherited by every CUDA-built ROS recipe and by four `.bbappend` files; `tensorrt-core`; `tegra-tools-jetson-clocks`; the `gstreamer1.0-plugins-nv*` elements; and the kernel recipe that `linux-jammy-nvidia-tegra_5.15.bbappend` targets. |
| `tegrademo` | `meta-tegrademo`: `conf/distro/tegrademo.conf`, which the distro requires; `recipes-demo/images/demo-image-weston.bb`, which the image requires; and the `swupdate-image-tegra` / `swupdate-machine-config` recipes that two `.bbappend` files target, which live in its `dynamic-layers/meta-swupdate`. |
| `swupdate` | `meta-swupdate`: `swupdate.bbclass`, inherited by both partial-update payload recipes, and the `swupdate` recipe itself. |
| `openembedded-layer` | `meta-oe`: `libmtp` (USB media), `libopus` (the audio path), `can-utils`, `ttf-noto-emoji`. |
| `networking-layer` | `meta-networking`: `networkmanager` and `networkmanager-nmcli`; `mosquitto-clients`, used by both the OTA agent and the telemetry agent. |
| `filesystems-layer` | `meta-filesystems`: `simple-mtpfs`, the FUSE filesystem the MTP auto-mount is built on. |
| `qt6-layer` | `meta-qt6`: `qt6-cmake.bbclass` and the Qt 6 runtime the head unit links against. |
| `ros-common-layer` | `meta-ros`: `python3-lark-parser`, needed by one `.bbappend`. |
| `ros2-layer` | `meta-ros`: the ROS 2 infrastructure classes, and the `tl-expected` recipe one `.bbappend` targets. |
| `ros2-humble-layer` | `meta-ros`: `ros_distro_humble.bbclass`, `ros_ament_cmake.bbclass`, and every upstream ROS package this layer installs or appends. |

One entry is conspicuously *not* in the list, and the reason is instructive.
`conf/distro/vpace.conf` contains `INHERIT:remove = "tegra-support-sanity"`, which names a
class from `meta-tegra-support`. Because it is a *removal*, it is a no-op when that layer is
absent — removing something that was never added changes nothing. Declaring a dependency on
a layer only to delete one of its classes would force every consumer of `meta-vpace` to
clone a layer it does not otherwise need. The distinction between "references by name" and
"depends on" matters here, and the file records it explicitly so the next person to tidy the
list does not helpfully add it.

## 3.3 Directory organisation

The layer uses more `recipes-*` directories than a small layer normally would, and the
grouping is by *function within the vehicle* rather than by the OpenEmbedded convention of
grouping by upstream category:

| Directory | Contents |
|---|---|
| `recipes-core/` | The image recipe, and the `/data` partition mount |
| `recipes-apps/` | Native C/C++ and shell services that are not ROS nodes: OTA agent, telemetry agent, CAN liveness responder, WiFi credential sender, MTP auto-mount, remote microphone |
| `recipes-qtapps/` | The Qt 6 head unit, and its OTA payload recipe |
| `recipes-ros2packages/` | First-party ROS 2 packages: the Ackermann control stack, the LiDAR perception pipeline, the camera detection bring-up, the update coordinator |
| `recipes-bbappends/` | Every modification to a recipe owned by another layer |
| `recipes-connectivity/` | CAN, NetworkManager, Bluetooth and Ethernet configuration packages |
| `recipes-support/` | Small policy packages: udev rules, module blacklists, GPU clock policy, the SWUpdate public key |
| `recipes-graphics/` | Display facilities beyond what `weston-init` provides |
| `recipes-kernel/` | The kernel `.bbappend` and its configuration fragments |
| `recipes-livox/` | The Livox SDK the LiDAR driver links against |
| `recipes-speech/` | The prebuilt Vosk speech recognition library |
| `recipes-devupdates/` | The Ackermann OTA payload recipe |
| `conf/include/` | Shared `.inc` files that enforce cross-recipe invariants |

`recipes-bbappends/` collects every `.bbappend` in one directory rather than scattering
them into the category directories they belong to, so "what does this layer change about
somebody else's recipe?" is answered by listing one directory. There are 21 of them,
grouped by problem class in §8.6.

## 3.4 The `conf/include/` mechanism

Two `.inc` files exist, and both exist for the same reason: to convert a convention that
was being maintained by hand into a mechanism that cannot be violated.

**`conf/include/ros2-lidar-perception.inc`** holds the `SRC_URI`, `SRCREV` and `PV` for the
five ROS 2 packages that live in one upstream repository. Repeating a `SRCREV` in five
recipes is not merely untidy; it admits a failure mode where the detector is built from one
revision and the message definitions it links against from another. That mismatch does not
break the build. It produces a node that disagrees with its own message definitions at
runtime, which is dramatically more expensive to find than a compile error. One `SRCREV`
in one file makes the mismatch unrepresentable. Each of the five recipes then sets only its
own `S` to the appropriate package subdirectory of the clone.

**`conf/include/vpace-ota-version.inc`** holds `VPACE_OTA_VERSION`, the single OTA version
number for the whole vehicle. It is required by three recipes: both partial-update payloads
and the OTA agent itself. §12.4 explains why the number must be singular and what breaks
when it is not.

---

# 4. The `vpace` distribution

`conf/distro/vpace.conf` is forty lines, of which about a third are comments explaining
individual settings. It does four things: it identifies the distribution, it shapes the
feature set, it removes one inherited sanity check, and it pins four `PACKAGECONFIG`
values that would otherwise fail silently.

## 4.1 Identity and derivation

```
require conf/distro/tegrademo.conf

DISTRO = "vpace"
DISTRO_NAME = "V-PACE Distribution"
DISTRO_VERSION_BASE = "1.0"
DISTRO_VERSION = "${DISTRO_VERSION_BASE}.0"
```

Deriving from `tegrademo` rather than from `poky` inherits NVIDIA's Tegra-specific
distribution policy wholesale: the graphics stack selection, the CUDA and TensorRT provider
pins, the kernel provider, and the SWUpdate integration that `meta-tegrademo` carries in its
`dynamic-layers/meta-swupdate`. Deriving from `poky` instead would mean reproducing all of
it, and reproducing it correctly against each meta-tegra uprev.

`DISTRO_VERSION` is split into a base and a full version. It appears in the OTA
`sw-description` for the full-image payload as `version = "@@DISTRO_VERSION@@"`, so it is
the version string a full-image update presents to SWUpdate. Note that this is *not* the
same number as `VPACE_OTA_VERSION`, which governs the agent's version gate; §12.4 covers
the distinction.

## 4.2 Feature set

```
DISTRO_FEATURES:append = " wayland"
DISTRO_FEATURES:remove = " x11"
DISTRO_FEATURES:remove = " ptest debuginfod virtualization nfc 3g pcmcia zeroconf nfs "
BBMASK += "docker-moby_%.bbappend"
```

The Wayland/no-X11 pair is the significant one. The head unit is a Qt 6 application running
under Weston with `QT_QPA_PLATFORM=wayland`; there is no X server on the image and nothing
that would use one. Removing `x11` rather than merely not using it prevents packages from
quietly building X11 backends they will never load, which on a Qt build is a substantial
amount of compile time and a substantial amount of image size.

The second removal list is a size-and-time decision on features the vehicle has no use for.
`virtualization` is the one with a follow-on consequence: removing it leaves a stale
`docker-moby` `.bbappend` elsewhere in the layer stack that would still be parsed, so
`BBMASK` suppresses it. That is a good illustration of a general property of feature
removal in OpenEmbedded — removing a feature does not remove everything that referenced it,
and the leftovers surface as parse errors rather than as anything self-explanatory.

## 4.3 The sanity-check removal

```
INHERIT:remove = "tegra-support-sanity"
```

`tegrademo` inherits a QA class from `meta-tegra-support` that validates assumptions about
the distribution which this layer deliberately violates — principally the feature-set
changes above. Removing the class is the mechanism by which a derived distribution is
allowed to diverge from its parent's expectations.

As noted in §3.2, this line is why `meta-tegra-support` is *not* a layer dependency: a
removal of an absent class is a no-op.

## 4.4 The `PACKAGECONFIG` pins

Four `PACKAGECONFIG` settings live in the distribution rather than in a build's
`local.conf`. All four share a property that makes their placement a correctness matter
rather than a convenience: **the failure without them is silent.**

```
PACKAGECONFIG:append:pn-ivi-ota-agent = " ros2-coordination"
PACKAGECONFIG:pn-camera-sign-detect-bringup = "trt"
PACKAGECONFIG:append:pn-weston = " rdp"
PACKAGECONFIG:append:pn-gstreamer1.0-plugins-base = " opus"
```

**`ivi-ota-agent` / `ros2-coordination`** enables the agent's integration with the
`update-coordinator` ROS 2 node. With the flag set, the agent's `do_install` rewrites its
own systemd unit to order after `update-coordinator.service`, and adds `ros2cli` and
`update-coordinator` to its runtime dependencies. Without it, the agent still installs
updates — it simply does so without immobilising the vehicle first, which is not a
behaviour anyone would notice until it mattered.

**`camera-sign-detect-bringup` / `trt`** selects the TensorRT inference backend over the
default ONNX Runtime backend. The recipe bakes the choice into the package at configure
time via `-DCAMERA_SIGN_DETECT_BACKEND`, and — importantly — the runtime dependency follows
the choice: `trt` pulls `ros2-yolos-cpp-trt`, `onnx` pulls `ros2-yolos-cpp`. Note the
assignment is `PACKAGECONFIG:pn-...` rather than `:append`, i.e. a replacement: the default
`onnx` must be displaced, not supplemented, or both backends would be requested.

**`weston` / `rdp`** builds `rdp-backend.so`. This one exists purely to support a
development facility and is called out here only because its absence is the classic silent
failure: Weston starts normally, the module is simply not there.

**`gst-plugins-base` / `opus`** builds `opusenc`/`opusdec`. Opus is not in poky's default
`PACKAGECONFIG` set for `gst-plugins-base`, and `libopus` comes from `meta-oe`. The RTP
payloaders that carry Opus live in `plugins-good` and are present regardless; only the
codec itself is gated here. The rationale recorded in the file for choosing Opus over raw
L16 is bandwidth and loss tolerance — roughly 24 kbps instead of 256, with packet-loss
concealment that reconstructs across a dropped frame, which matters because the link in
question is usually WiFi.

## 4.5 What the distribution deliberately does not set

The distribution sets no `INCOMPATIBLE_LICENSE`. That is relevant to §15.3: one recipe in
the tree declares a non-commercial licence, and the absence of a licence filter means the
declaration is recorded in the image manifest without blocking the build. The intent is
visibility, not enforcement.

Two commented-out lines at the end of the file record a Qt platform configuration that was
tried and rejected in favour of setting the same variables per-service in `ivi-app.service`,
where they can be conditioned on the actual display situation. §7.4 covers that.

---

# 5. The `orinivi-image`

`recipes-core/images/orinivi-image.bb` is the layer's single image recipe. It is a
composition rather than a construction: it requires `demo-image-weston` from
`meta-tegrademo` and then adds the vehicle to it.

## 5.1 Base and framework

```
require recipes-demo/images/demo-image-weston.bb

inherit ros_distro_${ROS_DISTRO}
inherit ${ROS_DISTRO_TYPE}_image

IMAGE_INSTALL:append = " ros-core "
IMAGE_INSTALL:append = " bash "
VIRTUAL-RUNTIME_sh = "bash"
IMAGE_FSTYPES:append = " tar.gz"
```

The base image supplies the Tegra graphics stack, Weston, the kernel and device tree
packaging, and a working `weston-init`. The two `inherit` lines bring in meta-ros's image
support, which arranges the `/opt/ros/humble` layout, the environment setup scripts and the
ROS package indexing.

`VIRTUAL-RUNTIME_sh = "bash"` replaces the default BusyBox `ash` as `/bin/sh`. This is a
functional requirement rather than a preference: the ROS 2 environment setup scripts
(`setup.bash` and the per-package hooks) are bash scripts, and `ivi-app.service` sources
`/opt/ros/humble/setup.bash` before executing the head unit.

`IMAGE_FSTYPES:append = " tar.gz"` produces the rootfs tarball that the OTA full-image
payload consumes. §12.2 covers how it is referenced.

## 5.2 What the image contains, by function

Rather than reproduce the recipe line by line, the following groups its ~40
`IMAGE_INSTALL:append` lines by what they are for. Each group is covered in detail by a
later section.

**Perception and control (§8, §9)**
`livox-ros-driver2`, `ackermann-hardware`, `ackermann-description`, `ackermann-bringup`,
`camera-sign-detect-bringup`, `lidar-perception-bringup`. Their own `RDEPENDS` pull in the
detector, the tracker, the message package and the ROS infrastructure they need, so the
image lists only the top of each chain.

**Head unit (§7)**
`ivi` — the Qt 6 application, which itself depends on the Qt runtime, PulseAudio and its
Bluetooth modules, GStreamer including the NVIDIA hardware elements, Vosk, and the fonts.
`ivi-mtp-mount` adds USB phone media browsing.

**Update and telemetry (§12)**
`swupdate`, `swupdate-key`, `data-partition-mount`, `ivi-ota-agent`, `jetson-status-agent`.

**Connectivity (§10)**
`networkmanager-nmcli`, `nm-config`, `can-config`, `can-utils`, `bluez-config`,
`wifi-cred-sender`, `liveliness-respond`, and the WiFi/Bluetooth kernel modules and
firmware enumerated in §2.2.

**Board policy (§6)**
`tegra-se-blacklist`, `gpu-clock-policy` with `tegra-tools-jetson-clocks`,
`camera-udev-rules`, and the input kernel modules for touch and gamepad.

**Development and test tooling**
`rosbag2` and its storage/compression plugins, `ros2bag`, `rosbag2-transport`, `joy`,
`teleop-twist-joy`, `teleop-twist-keyboard`, `ros2controlcli`, `compressed-image-transport`.
These are grouped under an explicit `## ---- ROS2 -TESTING ONLY ---` heading in the recipe
and are the obvious first candidates for removal in a production image.

**TensorRT device tooling (§9)**
`tensorrt-trtexec-prebuilt`, `tensorrt-plugins-prebuilt`. §9.3 covers why these must be on
the device and not merely in the sysroot.

## 5.3 Kernel modules are not installed by being enabled

One line in the recipe carries a lesson general enough to state plainly, because it cost
real time:

> A kernel fragment enables a build, it does not install it.

The kernel `.bbappend` sets `CONFIG_IWLWIFI=m` and `CONFIG_IWLMVM=m`, so the Intel WiFi
modules were being compiled and packaged from the day the fragment landed. They were not in
`IMAGE_INSTALL`, so they were not on the image. The observable symptom was an Intel 8265
sitting on the PCI bus with `driver=NONE` and no wireless interface at all — which reads
like a hardware or firmware problem, not a packaging one.

Every kernel module the vehicle needs is therefore listed explicitly in `IMAGE_INSTALL`,
even where the fragment that enables it is right there in the same layer.

## 5.4 Provenance stamp

```
IMAGE_POSTPROCESS_COMMAND += "write_vpace_build; "
write_vpace_build() {
    echo "${METADATA_REVISION} ${DATE}" > ${IMAGE_ROOTFS}/etc/vpace-build
}
```

Every image carries `/etc/vpace-build`, containing the metadata revision of the layer stack
and the build date. On a fleet where boards are flashed at different times and updated over
the air independently, "which build is this?" is otherwise a question with no reliable
answer — the distro version is coarse, and the OTA version counter tracks payloads rather
than images.

## 5.5 Services that are deliberately not enabled at boot

Three units ship in the image without being enabled, and in each case the reason is
recorded where the decision is made rather than being left as an apparent oversight.

**`camera-sign-detect.service`** (`SYSTEMD_AUTO_ENABLE = "disable"`). The unit is started by
a udev rule when the camera appears. Enabling it at boot would start `v4l2_camera` with no
device to open.

**`lidar-perception.service`** (`SYSTEMD_AUTO_ENABLE = "disable"`). This one is subtler.
`livox-ros-driver2` has no systemd unit in this layer, so nothing publishes `/livox/lidar`
unless the driver is started by hand. Auto-enabling the perception pipeline would load a
PointPillars engine onto the GPU at every boot and then block forever waiting on a topic
that never arrives — GPU memory held, journal quiet, nothing indicating anything is wrong.
The recipe records the condition under which this should change: the moment the Livox
driver gets a unit to order against, this flips to `enable` and the corresponding
`After=`/`Requires=` are added.

**Nothing starts the LiDAR driver.** The LiDAR perception path is presently a
manually-started pipeline.

By contrast, `ivi-app.service`, `ivi-ota-agent.service`, `update-coordinator.service`,
`gpu-clock-policy.service` and `data-partition-mount.service` are all enabled at boot.

---

# 6. Kernel and board bring-up

Four packages in this layer exist purely to make the hardware behave, and every one of them
was written in response to an observed failure on the board rather than to a specification.
They are grouped here because they are the layer between "the SoC boots" and "the vehicle
software can run".

## 6.1 The kernel `.bbappend`

`recipes-kernel/linux/linux-jammy-nvidia-tegra_5.15.bbappend` is five lines:

```
FILESEXTRAPATHS:prepend := "${THISDIR}/${PN}:"
SRC_URI:append = " \
                    file://mt7601u.cfg \
                    file://iwlwifi-8265.cfg \
                    file://uvcvideo.cfg \
"
KERNEL_MODULE_AUTOLOAD:append = " can mttcan"
```

Three configuration fragments enable, as modules, the MT7601U WiFi driver, the Intel
iwlwifi/iwlmvm pair, and the UVC video driver for the USB camera. The kernel itself is
NVIDIA's L4T 5.15 tree from `meta-tegra`; nothing here patches it.

`KERNEL_MODULE_AUTOLOAD` is the one line that changes runtime behaviour rather than build
output. `can` and `mttcan` are loaded at boot unconditionally, because the CAN link profile
in `can-config` is applied by `systemd-networkd` and there is no device to match on until
the controller driver is loaded. Autoloading is the simplest ordering guarantee available
for something that must be present before networking starts.

Everything else — the WiFi, Bluetooth and camera modules — is loaded on demand by udev when
the corresponding device appears, and is installed into the image explicitly as described
in §5.3.

## 6.2 Disabling the Tegra Security Engine

`recipes-support/tegra-se-blacklist/` installs one file into `/etc/modprobe.d/` and is
arguably the single most important package in the layer, because without it the board
resets under load.

The `tegra_se` driver provides hardware-accelerated crypto on the SoC. On this board it
corrupts its own `crypto_engine` request queue and oopses inside
`crypto_dequeue_request` with a NULL write and `LIST_POISON` values in registers — the
signature of a list entry being used after it was removed. That kills the crypto kernel
thread. Every subsequent caller blocks waiting on a thread that will never run, and the
CCPLEX watchdog eventually resets the SoC, recording `reset_reason BCCPLEXWDT`.

What makes this expensive to diagnose is the shape of the symptom. The board reboots. It
does not reboot at a consistent point, it does not leave a panic in the journal (the
journal is on the filesystem the crypto thread is now blocking), and the reset reason
points at the watchdog rather than at the driver. The captured trace and its decode are
preserved in the comments of `blacklist-tegra-se.conf`.

Blacklisting the module falls back to the kernel's software crypto implementations. That is
a measurable performance cost on a workload that does bulk crypto — which this vehicle's
workload is not; the crypto here is CMAC over 8-byte CAN frames and occasional RSA/AES
during an OTA.

## 6.3 GPU clock policy

`recipes-support/gpu-clock-policy/` addresses a performance problem that is invisible unless
someone thinks to look at `devfreq` statistics.

The Tegra GPU is managed by the `nvhost_podgov` governor, which scales frequency on measured
load and parks at the minimum when load stays under threshold. On this board that meant the
GPU sat at **306 MHz of an available 1173 MHz for 99.9% of uptime** — the `devfreq`
transition statistics recorded 1,955,344 ms at the minimum frequency against roughly 1.7
seconds across every other frequency combined, in 19 transitions.

For a workload with sustained GPU load that would self-correct. For this workload it does
not: the sign-detection pipeline runs inference at 2 Hz, so the GPU is genuinely idle
between frames, every inference starts from the parked clock, and `podgov`'s 25 ms polling
interval cannot react within the duration of a single inference. The governor is behaving
exactly as designed and producing the wrong answer for short, infrequent bursts.

The recipe records an important negative result alongside the fix: **`nvpmodel` is not the
limiter.** Power mode 4 (40 W) already permits the hardware maximum of 1173 MHz, and
`MAXN_SUPER` (mode 0) sets the GPU's `MAX_FREQ` to -1, resolving to the same ceiling.
Changing power mode cannot raise the GPU clock, so the obvious first thing to try does not
help.

The package installs a boot service offering three modes, selected by `GPU_CLOCK_MODE` in
`/etc/default/gpu-clock-policy`:

| Mode | Behaviour |
|---|---|
| `jetson_clocks` (default) | Runs NVIDIA's `jetson_clocks`, which maximises CPU, GPU and EMC together and disables every CPU idle state. Cannot express a floor below maximum. |
| `targeted` | GPU only: disables rail gating and sets the `devfreq` floor to `GPU_MIN_FREQ`. Writes nothing to CPU frequency, CPU idle states or EMC. The only mode that can select an intermediate frequency. |
| `off` | Changes nothing. |

Measured at idle over 10-second averages with the pipeline stopped:

| Policy | GPU MHz | CPU MHz | EMC MHz | VDD_IN | Tj | CPU idle states disabled |
|---|---|---|---|---|---|---|
| Baseline (stock governor) | 305 | 822 | 2133 | 6.73 W | 65.1 °C | 0 |
| `targeted` (GPU floor) | 1165 | 904 | 3199 | 9.49 W | 65.9 °C | 0 |
| `jetson_clocks` (all max) | 1170 | 1984 | 3199 | 9.82 W | 66.1 °C | 12 |

The `targeted` mode reaches essentially the same GPU frequency as `jetson_clocks` for
0.33 W less and without disabling CPU idle states — which is the interesting result, since
the CPU maximisation and idle-state disabling are what `jetson_clocks` does *in addition*
to the part that was actually needed.

The default is `jetson_clocks` for predictability, and `tegra-tools-jetson-clocks` is
therefore a hard `RDEPENDS` — with the default mode, the unit fails at boot if the tool is
absent. The image also lists `tegra-tools-jetson-clocks` explicitly, redundantly with that
`RDEPENDS`, for visibility.

## 6.4 Stable camera device naming

`recipes-support/camera-udev-rules/` installs a udev rule that gives the front camera a
fixed `/dev/camera-front` symlink.

The problem is mundane and the consequence is not. The camera was observed at `/dev/video0`,
`/dev/video1` and `/dev/video2` across replugs — UVC devices claim numbers in enumeration
order, and this board has other video nodes. `camera_params.yaml` in the detection package
hardcodes a device path, so a replug could silently point the detector at a different
device, or at nothing.

The rule does more than provide a symlink. It also carries `TAG+="systemd"` and a
`SYSTEMD_WANTS` that starts `camera-sign-detect.service` when the camera appears, which is
why that service is not enabled at boot (§5.5), and the service's `BindsTo=` uses the
stable name to stop cleanly on unplug. The coupling is made explicit in
`camera-sign-detect-bringup.bb`, which carries `RDEPENDS:${PN} += "camera-udev-rules"`
even though both packages are already in the image — installed apart from each other, the
unit would never start, or the rule would name a service that does not exist.

---

# 7. Graphics and the IVI head unit

## 7.1 The display stack

The head unit runs on Weston, the reference Wayland compositor, on the DRM backend — i.e.
directly on the kernel's display hardware, with GPU rendering through NVIDIA's EGL
implementation. There is no X server on the image (§4.2).

`recipes-bbappends/weston-init/` overrides the shipped `weston.ini` with a configuration
appropriate to an appliance:

```
[core]
shell=kiosk-shell.so
require-input=false
idle-time=0

[output]
name=HDMI-A-1
mode=1024x600

[libinput]
enable_tap=true
```

`kiosk-shell.so` gives a single fullscreen application with no desktop furniture — no
panel, no window decorations, no way for a user to reach anything but the application.
`require-input=false` lets the compositor start on a board with no input device attached,
which is the normal case on a vehicle with a touch panel that enumerates late.
`idle-time=0` disables the screen blanking timeout, since an instrument display that goes
dark after inactivity is a defect rather than a power saving.

The output block fixes the panel geometry at 1024×600, which is also the fixed logical size
of the application's root window (§7.4).

A second `.bbappend`, on `weston` itself, carries a patch in `files/` that is deliberately
**not applied**, because the patch is a no-op on the configuration actually shipped.
`libweston` computes `output->width = current_mode->width / scale`, so the expression the
patch changes is identical to the one it replaces whenever `scale == 1` — and the shipped
configuration keeps the compositor at scale 1, doing the 2x on the client with
`QT_SCALE_FACTOR` instead. It becomes relevant only if the output moves to a scale above 1.

## 7.2 The head unit application

`recipes-qtapps/ivi/ivi.bb` builds `appIVI`, a Qt 6 application fetched from a first-party
repository. It is the most complex recipe in the layer, because it is simultaneously a Qt
application and a ROS 2 node:

```
inherit qt6-cmake systemd
inherit ros_distro_humble
inherit ros_ament_cmake
```

That combination is unusual and produces two problems the recipe has to solve.

**Install prefix.** `ros_ament_cmake` installs to `/opt/ros/humble` by default, which is
correct for a ROS package and wrong for an application whose systemd unit and asset paths
are written against `/usr`. The recipe appends `-DCMAKE_INSTALL_PREFIX=${prefix}` to
`EXTRA_OECMAKE`, relying on the appended `-D` winning over the class's earlier one.

**`ament_cmake` package staging.** Consuming `rclcpp` and the message packages at configure
time pulls in the target `ament_cmake*` packages, because their installed CMake
configuration files call `find_package(ament_cmake_*)` `REQUIRED` for the full set that the
`ament_cmake` umbrella re-exports. meta-ros only propagates these as `-native` variants, so
each of the fourteen target ones has to be named in `DEPENDS` explicitly. The recipe does
so, with a comment noting that this is the same flattening superflore performs when
generating recipes.

The runtime dependency list is long and deliberately explicit. Beyond the obvious Qt
modules, two entries record findings that would otherwise be lost:

- **`pulseaudio-module-loopback`.** Required for phone audio over Bluetooth and pulled in by
  none of the `bluez5` packages. A phone is an A2DP *source*, which PulseAudio exposes as
  `bluez_source.<MAC>.a2dp_source`; `module-bluetooth-policy` routes that to the speakers by
  loading `module-loopback`. Without the module on disk, the policy module fails and falls
  back to setting the card profile to "off". The observable result: the phone pairs, AVRCP
  metadata and transport controls all work, and there is no sound.
- **`gstreamer1.0-plugins-nvvideo4linux2` and `-nvvidconv`.** The NVIDIA hardware decode and
  colour-conversion elements, alongside the software plugin sets, so media playback uses the
  hardware path.

## 7.3 The DDS transport profile — a load-bearing configuration file

The recipe installs `/etc/dds-udp-only.xml` and points the application at it via
`FASTRTPS_DEFAULT_PROFILES_FILE`. This is not tuning. Without it, the head unit receives
**nothing** from the ROS 2 publishers while appearing, by every diagnostic available, to be
correctly connected to them.

The mechanism: `appIVI` runs as the `weston` user, while every perception publisher runs as
`root`. Fast DDS creates its shared-memory segments and port ring buffers under `/dev/shm`
using the creating process's umask, yielding mode 0644:

```
-rw-r--r-- root   root   fastrtps_port7421          <- root publisher
-rw-r--r-- weston weston fastrtps_port7417          <- appIVI
-rw-r--r-- root   root   sem.fastrtps_port7421_mutex
```

Whichever user creates a port owns it and the other user gets read access only. A RELIABLE
reader must write ACKNACKs *back into the writer's port*, so `weston` cannot complete a
shared-memory channel to a root-owned publisher.

The failure presents as anything but a transport problem. Discovery runs over UDP multicast
and succeeds, so `ros2 topic info -v` reports the subscription MATCHED, with a stable GID
and fully compatible QoS on both ends — while not a single sample is delivered and the
application's ROS spin thread sits at zero CPU. It is intermittent across boots, because it
depends on which user's participant happened to create the shared port first, which is why
restarting the head unit sometimes appeared to fix it.

Measured, same publisher, same instant, `ros2 topic hz /object_detections_3d`:

| Running as | Result |
|---|---|
| `root` | 9.6 Hz |
| `weston` | nothing at all |
| `weston`, with this profile | 9.6 Hz |

The profile declares a single UDPv4 transport and sets `useBuiltinTransports` to false —
the latter is required, because without it the builtin SHM+UDP pair is added back alongside
the declared transport and the shared-memory locators reappear.

**The cost is quantified and small.** Shared memory is genuinely faster than loopback UDP,
but not measurably so at this traffic level. On `/object_detections_3d` the mean message is
2.5–2.8 KB (max 3.98 KB) at roughly 20 KB/s — one UDP datagram per message, no
fragmentation. The shared-memory advantage on a 2.5 KB payload is tens of microseconds
against a 104 ms message period, roughly 0.05% of it, while the detector itself spends
31 ms per frame.

**The scope is narrow.** The profile applies to `appIVI` alone, via that one environment
variable. The bulk path — the LiDAR driver or a bag player feeding ~1.9 MB point clouds at
~19 MB/s into the detector — is root-to-root and keeps shared memory untouched, as does the
camera pipeline. Publishers keep their default transports; a participant advertising only
UDP locators is simply reached over UDP, so nothing else on the board changes.

**When to revisit** is recorded too: if the head unit ever subscribes to a point cloud or
camera stream again, the tradeoff flips at megabyte payloads and the right answer becomes
fixing the shared-memory permissions instead — giving every publisher and the application
`UMask=0000` so the `/dev/shm` files land 0666. That was rejected here as fragile, because
the perception pipeline is often launched by hand from a root shell whose default umask of
022 silently recreates root-owned 0644 ports and breaks the channel again.

## 7.4 The service unit

`ivi-app.service` runs the application as the `weston` user under `graphical.target`, after
and requiring `weston.service`. Three of its decisions are documented in the unit itself.

**Environment.** `QT_QPA_PLATFORM=wayland`, `QT_MEDIA_BACKEND=gstreamer`, an explicit
`XDG_RUNTIME_DIR=/run/user/1000` and `WAYLAND_DISPLAY=wayland-1`. `ExecStart` sources
`/opt/ros/humble/setup.bash` before exec'ing the binary, which is why bash is `/bin/sh`
(§5.1).

**`Wants=network-online.target` without a matching `After=`.** This is deliberate and the
unit says so at length. The `Wants=` pulls the target into the boot transaction, making it
somewhat more likely the network is up by the time the application starts; it does not order
against it. Adding `After=network-online.target` would hold the head unit back until
`NetworkManager-wait-online` finishes or times out (~30 s), leaving a blank display on
precisely the units that have no saved WiFi profile — and WiFi is joined *through* this
application, so those are the units that need it soonest. The real fix lives in the
application, which watches RTNETLINK and rebuilds its ROS node when an IPv4 address appears,
because Fast DDS scans interfaces once per participant and never rescans.

**`EnvironmentFile=-/run/ivi-scale.env`.** An optional file supplying `QT_SCALE_FACTOR`, and
nothing else. The application's root window is a fixed `1024×600` whose fonts and icons are
fixed-pixel while its containers are fraction-based, so the layout is correct only when the
logical viewport is exactly that size. On a real 1024×600 panel the file is absent, no scale
factor is set, and Qt's default of 1 is right. The leading `-` is what makes the panel case
correct for free.

## 7.5 Speech recognition

`recipes-speech/vosk/` packages Vosk as a prebuilt shared library — a header and a `.so`,
with `do_configure` and `do_compile` marked `noexec` and several QA checks skipped
(`already-stripped`, `ldflags`, `buildpaths`, `dev-so`), because no source is available.
The acoustic model ships as data inside the application's own repository and is installed by
`ivi.bb` into `/usr/assets/models/vosk`.

Prebuilt binaries in a Yocto image are a compromise: the package cannot be
rebuilt for a different architecture, cannot be patched, and its QA exemptions mean genuine
problems in it would not be caught. It is accepted here because the alternative is no
offline speech recognition at all.

Because the board has no audio capture hardware (§2.2), this path has nothing to listen to
on a bench. The workaround used during development is documented separately in
[`docs/deep-dives/remote-mic-testing.md`](deep-dives/remote-mic-testing.md).

## 7.6 USB media browsing

`recipes-apps/ivi-mtp-mount/` makes a USB-connected phone's media visible to the head unit
without changing the application at all.

The application already implements MTP support, but it looks for the mount at gvfs's path
layout — `/run/user/<uid>/gvfs/mtp:host=<name>/<storage>` — and this image has neither gvfs
nor any MTP filesystem, so nothing ever appeared. The lookup is plain `QDir` with no gvfs
API and no D-Bus, which means *any* FUSE mount at the same path shape satisfies it. The
package therefore mounts `simple-mtpfs` exactly there.

The wiring is a udev rule, a systemd template unit and a helper script:

- The rule matches `ENV{ID_MTP_DEVICE}=="1"`, which is set by libmtp's own
  `69-libmtp.rules` — hence this rule being numbered 99, so it runs afterwards. Because
  libmtp's rules invoke `mtp-probe` to inspect USB interface descriptors rather than
  matching a vendor/product table, phones absent from libmtp's device table still work.
- The instance name is `$env{ID_VENDOR}_$env{ID_MODEL}`, which becomes the directory name
  the application parses for its display label — so `Xiaomi_Mi_9T` shows in the UI as
  "Xiaomi Mi 9T".
- Unplug is handled by a `RUN+=` invoking `systemctl --no-block stop`. The unit's comment
  explains why the systemd-native alternative does not fit: stop-on-unplug needs `BindsTo=`
  on the generated `.device` unit, whose name derives from the devpath and therefore cannot
  be written into a packaged unit file. Remove events do not carry the deadlock risk that
  add events do, and `--no-block` keeps udev from waiting on the job.

The template unit is shipped with `SYSTEMD_AUTO_ENABLE = "disable"`: a template has nothing
to enable, and with no phone attached there is no device to mount.

One packaging detail is recorded because it looks like a mistake and is not: `simple-mtpfs`
depends on FUSE 2 while the rest of the image is on FUSE 3, so both end up installed.

---

# 8. The ROS 2 layer

The Orin node runs ROS 2 Humble. Everything about the robotics stack — perception,
tracking, control, and the coordination protocol used during updates — is ROS 2 packages
built into the image as ordinary OpenEmbedded packages.

## 8.1 How ROS 2 becomes a Yocto image

This is the part of the build that is least like ordinary Yocto work, so it is worth
setting out before the packages themselves.

Upstream ROS 2 is distributed as source, built with `colcon` on a developer machine, into a
workspace overlaid on the environment with a shell script. None of that maps onto a
cross-compiled read-only image. `meta-ros` bridges the gap with a generator called
**superflore**, which reads the ROS distribution index and emits one BitBake recipe per ROS
package — several thousand of them for Humble. Those generated recipes are what
`meta-ros2-humble` contains.

Three consequences shape every recipe in this layer.

**Naming is transformed.** ROS package names use underscores; recipe names use hyphens.
`object_detection_msgs` is the package, `object-detection-msgs` is the recipe, and
`${ROS_BPN}` inside a recipe is the underscore form. Dependencies are declared in the
hyphenated form. Getting this wrong produces a "nothing provides" error that names a
package which visibly exists.

**Dependencies are declared in ROS terms and translated.** Recipes set `ROS_BUILD_DEPENDS`,
`ROS_BUILDTOOL_DEPENDS` and `ROS_EXEC_DEPENDS` — mirroring `package.xml` — and then assign
them into `DEPENDS` and `RDEPENDS`. Every first-party recipe in this layer follows that
shape, which keeps them comparable to the generated ones.

**The class stack does the work.** Three classes appear throughout:

| Class | Role |
|---|---|
| `ros_distro_humble` | Sets `ROS_DISTRO`, the install prefix `/opt/ros/humble`, and the variables (`ros_libdir`, `ros_datadir`) the rest depends on |
| `ros_ament_cmake` | Builds an `ament_cmake` package: CMake configuration with the ament CMake path, the ament index registration, and the environment hooks |
| `ros_ament_python` | The same for a pure-Python `ament` package |

A recurring wrinkle: `ros_ament_cmake` inherits `python3native`, so any `find_package(Python3
COMPONENTS Interpreter Development)` inside a package resolves the *native* interpreter and
reports the host's `SOABI`. §8.6 covers the one recipe where that produced a real defect.

Another, visible in three recipes as `do_compile:prepend() { export ROS_DISTRO="humble" }`:
some packages read `ROS_DISTRO` from the environment during their build, and it is not
exported into the compile task by default.

## 8.2 Node inventory

The image contains four functional groups of first-party ROS 2 software.

| Group | Packages | Started by |
|---|---|---|
| Ackermann control | `ackermann-description`, `ackermann-hardware`, `ackermann-bringup` | Launched manually / by the bring-up launch file |
| LiDAR perception | `livox-ros-driver2`, `cuda-pointpillars-ros`, `lidar-tracking`, `object-detection-msgs`, `object-visualization`, `lidar-perception-bringup` | `lidar-perception.service` (not enabled at boot — §5.5) |
| Camera detection | `camera-sign-detect-bringup`, `ros2-yolos-cpp-trt` | `camera-sign-detect.service`, triggered by udev on camera plug |
| Update coordination | `update-coordinator` | `update-coordinator.service`, enabled at boot |

The head unit (§7) is a fifth ROS 2 participant — it is a subscriber, built as a Qt
application rather than as a ROS package, but it joins the same DDS domain.

## 8.3 The Ackermann control stack

Three packages, all built from one first-party workspace repository at a pinned `SRCREV`,
each with `S` pointing at its own subdirectory.

**`ackermann-description`** ships the vehicle's URDF/xacro model. Build dependencies are
just `ament-cmake-native`; the runtime dependency is `xacro`. It is data, not code.

**`ackermann-hardware`** implements a `ros2_control` hardware interface — the plugin that
translates the controller framework's abstract command and state interfaces into whatever
the vehicle's actuators actually speak. It builds against `hardware-interface`, `pluginlib`,
`rclcpp-lifecycle` and `rosidl-adapter`.

Its packaging carries a wrinkle common to `pluginlib` plugins:

```
FILES_SOLIBSDEV = ""
FILES:${PN} += "${ros_libdir}/*.so"
INSANE_SKIP:${PN} += "dev-so"
```

A plugin is loaded by `dlopen` at runtime under its unversioned `.so` name, but OpenEmbedded's
default `FILES_SOLIBSDEV` sends unversioned `.so` files to the `-dev` package. Left alone,
the plugin would be absent from the runtime image and `pluginlib` would fail to find a class
that is definitely installed. Clearing `FILES_SOLIBSDEV`, claiming the `.so` for the main
package, and silencing the resulting QA warning is the standard fix — the same three lines
appear in `camera-sign-detect-bringup.bb`.

**`ackermann-bringup`** is the launch and configuration package, and its runtime dependency
list is effectively the specification of the running system, because a launch package's
`RDEPENDS` are the nodes it spawns:

- Core: `controller-manager`, `robot-state-publisher`, `joint-state-publisher`, `tf2-ros`
- Controllers: `joint-state-broadcaster`, `imu-sensor-broadcaster`,
  `ackermann-steering-controller`
- Input and fusion: `twist-mux`, `robot-localization`
- Sensor adaptation: `pointcloud-to-laserscan`, `livox-ros-driver2`
- Navigation: eighteen `nav2-*` packages plus `spatio-temporal-voxel-layer`

The Nav2 list is explicitly curated rather than pulling the `navigation2` metapackage — the
recipe's comment notes that RViz, teleop and `slam-toolbox` were removed because mapping is
done on a separate machine rather than on the vehicle. On an embedded image that curation is
worth real space and build time.

`pointcloud-to-laserscan` in that list is what lets a 3D LiDAR feed Nav2's 2D costmap
machinery: it flattens a point cloud into a synthetic `LaserScan`.

All three packages define a `do_deploy` that tarballs their install directory into
`DEPLOY_DIR_IMAGE`. That is not for the image — it is for the OTA payload recipe that
bundles all three (§12.3).

## 8.4 The LiDAR perception pipeline

Five ROS packages from one repository, sharing `conf/include/ros2-lidar-perception.inc`
(§3.4), plus the Livox driver and SDK.

The data path:

```
Livox sensor  →  livox-ros-driver2  →  /livox/lidar (PointCloud2)
              →  cuda_pointpillars_node  (TensorRT PointPillars detection
                                          + in-process AB3DMOT tracking)
              →  /object_detections_3d (object_detection_msgs/Object3dArray)
              →  the IVI Drive View
```

**`livox-sdk2`** and **`livox-ros-driver2`** are the vendor driver. The recipe carries a
`do_unpack:append` written in Python that reproduces what the vendor's `build.sh` does:
copies `package_ROS2.xml` over `package.xml` and replaces `launch/` with `launch_ROS2/`. The
upstream repository ships a single tree supporting both ROS 1 and ROS 2 and expects a build
script to select between them; a Yocto recipe has no build script, so the selection moves
into `do_unpack`.

**`object-detection-msgs`** defines the 3D detection message types. Its recipe records a
repointing: the messages used to live in a separate Python-pipeline repository and now live
alongside the C++ detector and tracker that produce them, so the two are versioned together
and cannot drift apart — the same reasoning as the shared `SRCREV` (§3.4), applied at the
level of repository organisation.

**`lidar-tracking`** is a C++ port of AB3DMOT, a 3D multi-object tracker. Its only
third-party dependency is Eigen — the port deliberately avoids PCL, OpenCV and the
NumPy-shaped helpers the Python original leaned on, which is what makes it viable to build
and run on the target.

Its licence declaration is the layer's worked example of honest licensing and is covered in
§15.3.

**`cuda-pointpillars-ros`** is the detector, and the most involved recipe in the layer after
the head unit. It is covered in §9 alongside the rest of the TensorRT story.

**`lidar-perception-bringup`** carries the launch file, the parameter profiles and the
systemd unit. Two of the unit's decisions matter architecturally.

*The model is copied to `/data` before launch.* The TensorRT wrapper derives its engine
cache path from the model path — it writes
`<model>.<hash>.trt<ver>.sm<arch>.<precision>.engine` next to the `.onnx`. Left at the
installed location that is inside the rootfs. The rootfs is not read-only, so this appears
to work; it is still wrong, because SWUpdate writes a whole new rootfs to the *other* A/B
slot, discarding the cache on every update. The first boot after each OTA would then silently
spend minutes rebuilding an engine it had already built. `/data` is the only partition that
survives. The `ExecStartPre` copies rather than symlinks — the engine is written into the
same directory as the `.onnx`, so that directory must be the writable one — and guards the
copy with `[ -e ]`, which makes it idempotent and, more importantly, means a model replaced
by hand on the board is not silently reverted on the next boot.

*`RequiresMountsFor=/data`* rather than naming the mount unit. `data-partition-mount.service`
is a oneshot ordered before `local-fs.target`, so systemd resolves the dependency from the
path — and the ordering keeps working if the mount ever moves.

The unit also sets `StartLimitIntervalSec` and `StartLimitBurst` to stop a crash loop, with
a comment recording a genuine systemd trap: those are `[Unit]` keys, not `[Service]` keys.
They lived in `[Service]` until systemd 229 and are still widely copied that way; systemd
255 parses the file without complaint and silently ignores them in the wrong section, so the
limit does not apply and nothing says so. `systemd-analyze verify` is what catches it.

Finally, `KillSignal=SIGINT`: `ros2 launch` tears its nodes down cleanly on SIGINT, whereas
the default SIGTERM leaves them to be killed by the cgroup sweep.

## 8.5 The camera sign-detection pipeline

`camera-sign-detect-bringup` launches a composable-node pipeline: `v4l2_camera` capturing
from `/dev/camera-front`, a YOLO detector, and a CAN publisher node that puts detected sign
classes onto the vehicle bus.

The inference backend is a build-time choice (§4.4). With `trt` selected, the runtime
dependency resolves to `ros2-yolos-cpp-trt` — a TensorRT-backed ROS 2 wrapper around the
YOLOs-CPP inference library, built with `inherit cuda` and `CUDA_ARCHITECTURES = "87"`, and
the only recipe in the layer declaring `AGPL-3.0-only`.

The service unit is the most carefully constructed in the layer and each constraint has a
reason:

**`BindsTo=dev-camera\x2dfront.device`** — bound to the *symlink's* device unit, not
`dev-videoN`, because `/dev/videoN` is not stable (§6.4). The udev rule's
`SYMLINK+="camera-front"` becomes an alias on the device unit, so the name is stable enough
to write into a packaged unit file. `BindsTo` is also what stops the pipeline on unplug —
and it is why this package, unlike `ivi-mtp-mount`, needs no `systemctl` call from a udev
rule: here the unit name is known in advance, so the systemd-native form works.

**`Requires=systemd-networkd-wait-online@can0.service`** — `can0` must be *up*, not merely
present. The CAN publisher node throws in its constructor when the interface cannot be
opened, and because it is a *composable* node, `LoadComposableNodes` logs the failure and
launch carries on: the container stays up, the unit reports active, detections keep
publishing on the ROS topic, and nothing ever reaches the vehicle bus. That silent
half-loaded state is exactly what a udev trigger firing before `systemd-networkd` has
configured `can0` produces. `Requires=` rather than `Wants=` so a `can0` that never comes up
fails the unit loudly instead.

That dependency only works because `can0.network` declares `RequiredForOnline=carrier` —
§10.1 explains why.

**`StartLimitIntervalSec=300` / `StartLimitBurst=3`** — startup loads a model onto the GPU,
so a crash loop is expensive. Same `[Unit]`-versus-`[Service]` trap as above.

**No `[Install]` section** — the unit is started by udev, and must never be pulled in at
boot.

The pipeline's throughput was substantially wrong when first measured, and the investigation
and fix are recorded in
[`docs/deep-dives/camera-pipeline-optimization.md`](deep-dives/camera-pipeline-optimization.md).
In summary: the pipeline was configured for 2 Hz and ran at 30 Hz, because *every knob
meant to throttle it was silently inert*. `time_per_frame` is not a parameter any release of
`v4l2_camera` implements — rclcpp keeps unknown YAML keys as parameter overrides and
discards them without warning. The requested image size was not an advertised mode, so V4L2
substituted a different one while the node still logged success. `publish_image` was a
no-op. And the camera published `rgb8` while the detector asked `cv_bridge` for `bgr8`, so
every frame was converted twice. The result was TensorRT doing fifteen times the intended
work and 18.4 MB/s of discarded frames crossing a USB 2.0 bus shared with the WiFi dongle.

The fix required a patch to `v4l2_camera` itself, carried in this layer (§8.6), because no
upstream release can set the capture frame rate at all.

## 8.6 Modifications to upstream recipes

`recipes-bbappends/` holds 21 `.bbappend` files. Grouped by the class of problem they
solve, they fall into six categories.

### Group 1 — CUDA leaking through OpenCV

`cv-bridge`, `compressed-image-transport`, `nav2-waypoint-follower`, `v4l2-camera`: all four
simply `inherit cuda`.

The reason is transitive and initially baffling. The image's OpenCV is the Tegra build,
which uses CUDA. `OpenCVConfig.cmake` therefore contains `find_package(CUDA EXACT 12.6)`.
Any package that calls `find_package(cv_bridge)` pulls `cv_bridgeConfig.cmake`, which pulls
`OpenCVConfig.cmake`, which fails with `Could NOT find CUDA (missing:
CUDA_TOOLKIT_ROOT_DIR ...)` — in a recipe that never mentions CUDA and does no GPU work.
`inherit cuda` supplies the toolkit paths and the configure succeeds.

### Group 2 — licence identifiers scarthgap does not recognise

`geographic-msgs`, `joint-state-publisher`, `pointcloud-to-laserscan`: each remaps a
`package.xml` licence of plain `"BSD"` to `BSD-3-Clause` with the corresponding
`LIC_FILES_CHKSUM` against `${COMMON_LICENSE_DIR}`. `openvdb-vendor` does the same for
`"MPL-2.0-license"` → `MPL-2.0`.

These are generated recipes carrying whatever the upstream `package.xml` said, and scarthgap's
licence name list is stricter than what ROS package authors have historically written.

### Group 3 — compiler strictness against older ROS code

`nav2-controller` and `nav2-velocity-smoother` add
`CXXFLAGS += "-Wno-error=deprecated-declarations"`. The Humble releases of both use the
deprecated topic-subscription API, and the build promotes the warning to an error.

### Group 4 — packaging and QA adjustments

`tl-expected` sets `ALLOW_EMPTY:${PN} = "1"`. It is a header-only library, so its runtime
package would otherwise be empty and not produced at all, and anything with it in `RDEPENDS`
fails to resolve. There is an open upstream issue about this, referenced in the file.

`openvdb-vendor` additionally sets `do_compile[network] = "1"` and skips `dev-so` and
`staticdev`. "Vendor" packages in the ROS ecosystem download their real payload during the
build, which is contrary to Yocto's fetch/build separation but is what the package does.

`spatio-temporal-voxel-layer` supplies a set of build tools the generated recipe does not
declare — `rosidl-adapter`, `ament-cmake-ros`, the gmock/gtest/pytest ament helpers,
`python3-numpy-native`, `python3-lark-parser-native`, `rpyutils-native` — and prepends a
`PYTHONPATH` export so the target `site-packages` is visible during compile. The recipe also
carries a note that this package runs out of memory when built with high parallelism, with
the author's own acknowledgement that limiting threads is a workaround rather than a fix.

### Group 5 — real defects with real consequences

Four `.bbappend` files fix things that were genuinely broken.

**`rosbag2-py`** — `inherit python3targetconfig`. Its six pybind11 extensions were installed
under the *build host's* `EXT_SUFFIX`: `_reader.cpython-312-x86_64-linux-gnu.so` on an
aarch64 image. The ELF inside each is correct AArch64; only the filename triplet is wrong.
CPython looks the extension up by the *target* suffix, so it never finds them:

```
ModuleNotFoundError: No module named 'rosbag2_py._reader'
```

which takes out `record`, `play`, `info`, `convert` and `reindex` alike. The cause is the
`python3native` inheritance described in §8.1: the interpreter that `find_package(Python3)`
runs is the native one and reports the host `SOABI`, and the class's `-DPYTHON_SOABI` is not
what `pybind11_add_module()` reads. `python3targetconfig` points `sysconfig` at the target.
This is the same fix meta-ros already carries for `rclpy` — which is why 109 other extensions
on the image are named correctly and these six were not.

**`mcap-vendor`** — restores a missing link against zstd. `libmcap.so` calls into zstd but
its `DT_NEEDED` lists only libc, libgcc and libstdc++, so the MCAP storage plugin aborts the
moment `pluginlib` `dlopen`s it:

```
libmcap.so: undefined symbol: ZSTD_CCtx_setParameter
[FATAL] No storage could be initialized. Abort
```

Silent by default, because rosbag2 writes sqlite3 unless asked for `-s mcap`, and
`ros2 bag list storage` reads the plugin XML without `dlopen`ing, so it lists MCAP as
available either way. The missing link comes from meta-ros's own fetch-conversion patch,
which swapped `find_package(zstd)` for `pkg_check_modules(ZSTD libzstd REQUIRED)` but
reduced `ament_target_dependencies(mcap zstd)` to `ament_target_dependencies(mcap)`, leaving
the results unused. This layer restores the link on top of it — using `:append` rather than
`+=` so it lands after meta-ros's own `SRC_URI` patch list.

**`steering-controllers-library`** and **`ackermann-steering-controller`** — a paired patch
fixing a kinematics error. Humble's `SteeringOdometry` shares one `wheel_track_` between the
traction-axle differential-speed formula and the steering-axle angle-splitting formula. That
is only exact when front and rear track are equal, and on this vehicle they are not — 0.12 m
front against 0.18179 m rear, per the bring-up package's controller configuration. Every
steering-angle calculation was using the traction axle's track. The companion patch fixes
the call site, which only ever passed one track width.

The fix exists upstream on the `kilted` branch, but that rework depends on newer
`hardware_interface` handle APIs that Humble does not have, so it cannot be cherry-picked.
These patches backport only the track-width split onto Humble's existing API.

**`v4l2-camera`** — adds frame-rate control. Upstream `v4l2_camera` cannot set the capture
frame rate: it applies a format and streams at whatever interval that format defaults to,
and never calls `VIDIOC_S_PARM`. That is true of 0.6.2 (humble), 0.7.1 (jazzy), 0.8.0
(rolling) and master, so there is nothing to upgrade to. Setting the rate from outside the
node does not work either, because `VIDIOC_S_FMT` resets the frame interval — measured on
target, `1/5` set beforehand reads back as `1/30` once the node applies its format. §8.5
covers what this cost.

### Group 6 — SWUpdate and Weston

Covered in §12.2 and §7.1 respectively.

## 8.7 The update coordinator

`update-coordinator` is a ROS 2 Python node that orchestrates firmware updates across the
vehicle's ECUs over CAN, with SecOC authentication. It is the one ROS node that is enabled
at boot.

Its role in the OTA flow is described in §12.5 and its CAN interface in §11.3. As a package
it is unremarkable: `ros_ament_python`, `rclpy`, `std-srvs`, `python3-cryptography` for the
CMAC, and a `do_install:append` that installs its SecOC key at mode 0400 and its systemd
unit.

A measurement recorded elsewhere in the tree: this node once burned a third of a CPU core at idle —
more than the entire detection pipeline — because its raw CAN socket had no filter. It woke
for all 333 frames per second on the bus, across 14 identifiers, **not one** of which it
handles. Each frame cost a `select()` wake, a `recv`, an unpack, a queue put and a guard
condition that woke the whole ROS executor, just to early-return. `SO_ATTACH_FILTER` on a
raw CAN socket is not an optimisation on a busy bus; it is the difference between a node
that costs nothing and one that costs a core.

---

# 9. GPU and on-device inference

Both perception pipelines run their neural networks through TensorRT, NVIDIA's inference
runtime. TensorRT is the single most awkward dependency in the build, and the reasons are
worth setting out in full, because they dictate decisions in three recipes, one systemd unit
and the image itself.

## 9.1 What TensorRT does, and why that is inconvenient here

TensorRT is not a framework that executes a network graph. It is an optimising compiler.
Given a network — typically as an ONNX file — it produces an **engine**: a serialised,
hardware-specific plan containing selected kernels, fused operations, chosen precisions and
memory layouts, tuned by actually timing candidate implementations on the device it is
running on.

That last clause is the whole problem. Engine construction is a *measurement* process, so
NVIDIA requires that engines be built on the target hardware. An x86 build host cannot
produce one for an Orin. The resulting engine is bound to:

- the GPU architecture (`sm_87` here),
- the exact TensorRT version,
- the driver and CUDA version,
- and the build-time precision and workspace configuration.

An engine that does not match refuses to load — and, in the worst case, does so with a
message about a serialisation version rather than about the L4T version having moved.

## 9.2 The consequence: ship ONNX, build on first run

The layer therefore ships only the portable `.onnx` file and lets each device build and
cache its own engine on first run. `cuda-pointpillars-ros.bb` states this explicitly and
installs only the ONNX model into `${ros_datadir}/${ROS_BPN}/model`.

This removes a real failure mode as well as an impossible one. A prebuilt engine bound to an
exact TensorRT version stops loading *silently* if the image moves to a different L4T — the
kind of failure that appears months later, on a subset of the fleet, after an unrelated
change.

The cost is the first run: building a PointPillars engine on an Orin takes minutes. That is
why `lidar-perception.service` caches the engine on `/data` (§8.4) and why its start limit
allows three attempts in ten minutes rather than the usual tighter loop.

## 9.3 Why TensorRT tooling ships *on the device*

Because engines are built on the target, the target needs the parts of TensorRT that build
them — which is not what `tensorrt-core` provides. The image therefore installs:

```
IMAGE_INSTALL:append = " \
    tensorrt-trtexec-prebuilt \
    tensorrt-plugins-prebuilt \
"
```

`tensorrt-core` ships `libnvinfer` but **no ONNX parser**. Until the parser was present, all
TensorRT work on the device was blocked. `tensorrt-plugins-prebuilt` is what carries
`libnvonnxparser.so.10.3.0` (with its `.so.10` and `.so` links). It also brings
`libnvinfer_plugin`, which CUDA-PointPillars does not need — it registers its own
pillar-scatter plugin from inside its own binary via `REGISTER_TENSORRT_PLUGIN` — but the two
ship in the same package.

Two packaging facts are recorded in the image recipe because each costs an afternoon:

- **`trtexec` is not on `$PATH`.** `tensorrt-trtexec-prebuilt` sets `FILES:${PN}` to
  `${prefix}/src/tensorrt/bin`, so the binary lands at `/usr/src/tensorrt/bin/trtexec`.
- **The unversioned `libnvonnxparser.so` symlink is in the `-dev` package**, per the default
  `FILES_SOLIBSDEV`. Runtime is fine — the SONAME is `libnvonnxparser.so.10`, which is in the
  runtime package — but anything *compiling* against `-lnvonnxparser` on the device also needs
  `tensorrt-plugins-prebuilt-dev`.

No `PREFERRED_PROVIDER` is needed: meta-tegra's `tegra-common.inc` already pins
`tensorrt-plugins` and `tensorrt-trtexec` to the `-prebuilt` recipes, so the source-built
`tensorrt-plugins` under meta-tegra's `external/openembedded-layer/` does not collide.

The image records the version it is built against: **TensorRT 10.3.0.30**
(`libnvinfer.so.10.3.0`), not 8.6. That distinction matters because a great deal of published
CUDA-PointPillars material assumes the 8.x API.

## 9.4 Cross-compiling against TensorRT

The other half of the problem is the *build*, and `cuda-pointpillars-ros.bb` is where it is
solved.

The upstream package defaults its TensorRT include and library paths to
`/usr/include/aarch64-linux-gnu` and `/usr/lib/aarch64-linux-gnu`. That is correct for
JetPack's `.deb` packages on a running board and wrong for a Yocto cross build:
`tensorrt-core` and `tensorrt-plugins-prebuilt` both install to `${includedir}`/`${libdir}`
with no multiarch component.

Left unset, the compiler is pointed at the **build host's** `/usr/include` — which on an
x86_64 builder either fails to find `NvInfer.h`, or, considerably worse, finds a different
version of it. The recipe redirects both:

```
EXTRA_OECMAKE += " \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF \
    -DGPU_SMS=87 \
    -DTENSORRT_INCLUDE_DIRS=${STAGING_INCDIR} \
    -DTENSORRT_LIBRARY_DIRS=${STAGING_LIBDIR} \
"
```

The recipe's comment names the gap precisely: `cuda.bbclass` covers the CUDA *toolkit* paths
but says nothing about TensorRT, and these two lines are the part it does not reach.

`COMPATIBLE_MACHINE = "(tegra)"` makes the constraint explicit — TensorRT only exists on
Tegra and the package cannot be built without it, so a non-Tegra build gets a clear refusal
rather than a confusing failure.

## 9.5 Compute capability, declared twice

```
CUDA_ARCHITECTURES = "87"
EXTRA_OECMAKE += " -DGPU_SMS=87 "
```

The two are the same number expressed to two different consumers. `CUDA_ARCHITECTURES` feeds
`CMAKE_CUDA_ARCHITECTURES` through `cuda.bbclass`; `GPU_SMS` is the package's own
`-gencode` list, which predates CMake's CUDA language support and is what actually reaches
`nvcc` in this package. Setting only one leaves the other at a default that may or may not
include `sm_87`.

`ros2-yolos-cpp-trt` sets `CUDA_ARCHITECTURES = "87"` as well; it uses the CMake mechanism
only.

## 9.6 Dependency structure of the detector

```
DEPENDS  = "${ROS_BUILD_DEPENDS} tensorrt-core tensorrt-plugins-prebuilt"
RDEPENDS = "${ROS_EXEC_DEPENDS} tensorrt-core tensorrt-plugins-prebuilt"
```

Both TensorRT packages appear in both lists. `tensorrt-core` carries `libnvinfer`;
`tensorrt-plugins-prebuilt` carries `libnvonnxparser`, which this package links **by its
unversioned name** — and per §9.3 the unversioned `.so` is in the `-dev` package, so it must
be staged at build time even though only the SONAME matters at runtime.

The recipe also declares its licence carefully. The package descends from NVIDIA's
CUDA-PointPillars — the decoder, pillar scatter and preprocessing all carry 2021 NVIDIA SPDX
headers — but ships no `LICENSE` file, so `LIC_FILES_CHKSUM` is taken over one of those
headers, which are byte-identical across the ten files carrying them.

Finally, `DEBUG_PREFIX_MAP` is set and appended to `TARGET_CC_ARCH`, with
`INSANE_SKIP` for `already-stripped`, `dev-so` and `buildpaths` — the usual accommodations
for a package whose build system strips its own output and embeds paths.

---

# 10. Connectivity

The Orin node has three network interfaces with three different owners.

| Interface | Purpose | Managed by |
|---|---|---|
| `can0` | The vehicle bus — all inter-node communication | `systemd-networkd` |
| Ethernet | Dedicated to the Livox LiDAR | NetworkManager, via a shipped profile |
| WiFi | Internet: OTA campaigns and telemetry | NetworkManager, profiles learned at runtime |

## 10.1 CAN

`recipes-connectivity/can-config/` installs a single `systemd-networkd` link profile and
enables `systemd-networkd`:

```
[Match]
Name=can0

[CAN]
BitRate=500000
RestartSec=100ms

[Link]
ActivationPolicy=up
RequiredForOnline=carrier
```

500 kbit/s is the vehicle bus rate. `RestartSec=100ms` enables automatic bus-off recovery:
a CAN controller that accumulates enough transmit errors takes itself off the bus, and
without automatic restart it stays off until something intervenes.

**`RequiredForOnline=carrier`** is what makes
`systemd-networkd-wait-online@can0.service` usable as an ordering dependency — which
`camera-sign-detect.service` requires and `update-coordinator.service` wants (§8.5).

The value matters. `wait-online` defaults to a minimum operational state of `degraded`,
which implies an address. **A CAN link never gets an address and tops out at `carrier`**, so
without this line the wait-online instance hangs until its timeout and then fails.

It is worth being precise about what the line does *not* do: it does not newly enlist `can0`
in `network-online.target`. A managed link is required for online by default, so `can0` was
already counted and already failing it. Measured on the board before the line existed:

```
networkctl status can0  ->  State: carrier (configured)
                            Online state: offline
                            Required For Online: yes

systemd-networkd-wait-online -i can0            -> timeout, exit 1  (8.2 s)
systemd-networkd-wait-online -i can0:carrier    -> exit 0           (0.005 s)
```

So the line strictly *removes* a blocker. It does not fix
`systemd-networkd-wait-online.service` itself, which still times out at the full two minutes
because the stock `80-wired.network` also claims the onboard Ethernet interface as required
for online, and that interface has no carrier whenever no cable is attached. That is a
separate, pre-existing issue.

The kernel modules `can` and `mttcan` are autoloaded (§6.1), and `can-raw` and `can-utils`
are in the image.

## 10.2 The NetworkManager / systemd-networkd division of labour

Both network managers run, deliberately, and each is explicitly kept off the other's
interfaces. This is the kind of arrangement that goes wrong quietly if only half of it is
configured, so `nm-config` installs both halves:

- `/etc/NetworkManager/conf.d/10-unmanaged-interfaces.conf` — `unmanaged-devices=interface-name:can0`, keeping NetworkManager off the CAN link.
- `/usr/lib/systemd/network/10-eth-unmanaged.network` — a networkd config file, installed into networkd's directory, keeping networkd off Ethernet.

A `static-eth` package also exists in the tree, providing a `systemd-networkd` static
Ethernet configuration. It is **intentionally not installed** — the image recipe says so
explicitly — because NetworkManager owns Ethernet now. It remains in the tree as the
alternative path.

## 10.3 NetworkManager configuration

Four files, each solving a distinct problem.

**`20-keyfile-path.conf`** repoints NetworkManager's writable connection store at `/data`, so
WiFi profiles learned at runtime survive an OTA (§12.6).

That has a consequence the recipe handles carefully: `path` *replaces* the default
`/etc/NetworkManager/system-connections` rather than adding to it. A profile left in `/etc`
after that change would silently stop being read — and since the shipped profile is the
LiDAR's Ethernet link, the LiDAR would go dead with no error. The `static-eth.nmconnection`
profile is therefore installed into `/usr/lib/NetworkManager/system-connections`, which
NetworkManager always reads regardless of `path`, and which is the conventional home for a
profile that ships with an image rather than being learned on the box.

Mode 0600 on that file is mandatory, not tidiness: NetworkManager silently ignores any
keyfile readable or writable by any user or group other than root.

**`nm-state-on-data.conf`** is a service drop-in that bind-mounts `/var/lib/NetworkManager`
onto `/data`, so `timestamps` and `seen-bssids` survive a flash as well as the profiles
themselves.

**`30-wifi-powersave.conf`** disables WiFi power save, and the reasoning is a good example of
a measurement changing a conclusion. In power save the client tells the access point it is
sleeping and the AP *buffers downlink frames* until the next DTIM beacon. Uplink is
unaffected, so the damage is one-directional and reads like a bad link or a bad AP.

Measured over a MiFi at −27 dBm, 60 pings each way:

| Path | min / avg / max (ms) | mdev |
|---|---|---|
| Jetson → AP (uplink) | 1.20 / 1.63 / 3.60 | — |
| laptop → AP | 1.11 / 2.22 / 9.88 | 1.55 |
| **laptop → Jetson, power save ON** | **2.03 / 22.77 / 306.49** | **59.08** |
| laptop → Jetson, power save OFF | 2.03 / 2.61 / 4.41 | 0.66 |

An 8.7× better average, 69× better worst case, 90× less jitter. Bulk transfer improved far
less (51/53 → 60/65 Mbit/s), which is the tell: this throttles latency, not bandwidth.

The file also records how to recognise the same problem again. The worst case pins to
*one beacon interval*: `iw dev <dev> link` reported
`beacon int: 100` and `dtim period: 1`, and the observed maximum was 96–306 ms, i.e. one to
three beacons of buffering. **If a link shows a maximum latency that is a clean multiple of
the beacon interval, suspect power save before suspecting RF.** Crucially the radio was not
at fault and said so — 78 transmit retries in 145,128 packets (0.05%), zero transmit
failures, zero beacon loss, MCS 15 at 40 MHz. Interference and congestion both show up as
retries and rate downshifts, and neither was present; chasing antennas, channels or AP
placement on those numbers would have found nothing.

Two implementation details: the setting is a `conf.d` drop-in rather than a per-profile
setting, because profiles here are learned at runtime and would each need the setting
repeated; and the value must be `2`. The values are `0` = use global default, `1` = leave
alone, `2` = disable, `3` = enable — and `1` and `2` are **not** synonyms. `1` tells
NetworkManager not to touch the driver's default, which for `iwlwifi` is power save *on*.
Runtime `iw ... set power_save off` works only until the next reconnect, because
NetworkManager reapplies its own setting on each activation.

## 10.4 WiFi and Bluetooth hardware support

Both WiFi radios are supported unconditionally, as described in §2.2 and §5.3.

Bluetooth is provided by the Intel 8265, with one trap. The BT half of
the card is a **USB function** (`8087:0a2b`), not PCIe, so it needs the USB HCI transport
rather than anything on the PCI side. Four modules are required and `btrtl`/`btbcm` are not
optional despite the card being Intel: `btusb` is compiled with
`CONFIG_BT_HCIBTUSB_RTL=y` and `CONFIG_BT_HCIBTUSB_BCM=y`, so it references
`btrtl_setup_realtek`/`btrtl_shutdown_realtek` and the `btbcm` symbols unconditionally at
load time. Ship `btusb` without `btrtl` and `modprobe` fails outright with
`Unknown symbol btrtl_setup_realtek` — the Intel radio never appears, and the error names a
Realtek symbol, which sends the reader looking in entirely the wrong place.

Firmware matters too: `ibt-12-16` is correct for the 8265 specifically (the `.sfi` is the
operational image, the `.ddc` the tuning parameters). The 8260 takes `ibt-11-5` and the 7265
`ibt-hw-37-8`.

**`bluez-config`** exists because bluez5 ships no `main.conf` at all. Without one the adapter
is DOWN after every boot and the gamepad cannot connect until somebody brings `hci0` up by
hand. The recipe carries a forward-looking note: it was checked against bluez5 5.72, and if a
future uprev starts packaging `${sysconfdir}/bluetooth/main.conf` the two packages will
conflict on that path at rootfs time — at which point the correct fix is to convert this into
a `bluez5_%.bbappend` that overwrites the file, not a second package shipping the same path.

---

# 11. Interfaces to the other nodes

Every message that crosses the boundary between the Orin node and anything else, described
from this node's side. Two transports carry them: the vehicle CAN bus, and MQTT over the
internet.

## 11.1 CAN: node liveness

`recipes-apps/liveliness-respond/` is a small C++ service that answers a liveness probe from
the host.

| CAN ID | Direction | DLC | Payload |
|---|---|---|---|
| `0x7A0` | host → all three nodes | 1 | rolling sequence counter, `u8` |
| `0x7A1` | Tiva → host | 1 | echo of the request byte, unchanged |
| `0x7A2` | **Jetson → host** | 1 | echo of the request byte, unchanged |
| `0x7A3` | ESP32 → host | 1 | echo of the request byte, unchanged |

The protocol is deliberately trivial: the host broadcasts a request with a rolling counter on
`0x7A0`, and each node echoes the byte back on its own response identifier. Matching the
echoed byte to the sent byte tells the host both that the node is alive and that it is
current, without any state on either side.

The responder is generic — the response identifier is a command-line option, defaulting to
`0x7A2` — so the same binary could serve as any node's responder. It runs as a systemd
service on `can0`.

## 11.2 CAN: WiFi credential provisioning

`recipes-apps/wifi-cred-sender/` sends WiFi credentials from the Orin to two peers. It is
launched by the head unit application, which is where a user enters an SSID and password —
so the Orin is the node that learns the credentials and the others receive them.

It is the most protocol-heavy piece of code in the layer, combining ISO-TP segmentation with
SecOC authentication.

### Addressing

| Target | Orin → peer | Peer → Orin (Flow Control) |
|---|---|---|
| Cluster | `0x205` | `0x206` |
| ESP32 | `0x207` | `0x208` |

**These are two separate sessions, not one broadcast**, and the reason is a genuine
protocol constraint rather than a preference. ISO-TP Flow Control is point-to-point: exactly
one node may answer FC per session. If both receivers answered on a shared identifier they
would be two transmitters on one ID. Today they emit identical Clear-To-Send frames so it
appears to work — but the moment one needs to send Wait (`0x31`) or Overflow (`0x32`) the
frames diverge mid-transmission and the bus takes bit errors. Hence the `--target` option,
and hence `--target both` sends the payload twice rather than once.

### Wire format

```
payload = "SSID;PASSWORD" || freshness (4 B, big-endian) || MAC (4 B)
MAC     = AES-128-CMAC(key, "SSID;PASSWORD" || freshness)[0..3]
```

Segmentation is ISO 15765-2 (ISO-TP): a Single Frame if it fits, otherwise a First Frame,
then Consecutive Frames paced by the receiver's Flow Control.

### Acceptance rule

A receiver accepts a message only if **both** conditions hold:

1. the MAC verifies, and
2. the freshness value is **strictly greater** than the last value that receiver accepted,
   persisted across reboot.

Condition 2 is what makes a captured frame useless to replay. Every send must therefore use
a larger counter than the previous one. The sender defaults to `max(unix_time, last_sent+1)`,
which is monotonic without needing to know what either receiver currently holds — and the
two receivers keep **independent** floors, so they do not have to agree on the number.

The freshness counter is persisted at `/data/secoc/wifi_cred_txfv` (§12.6). Because the head
unit runs as `weston` rather than root, that one file is owned by `weston` while the
directory above it stays root-owned — an arrangement `mount-data-partition.sh` sets up
explicitly, and whose failure it deliberately does not swallow (§12.6).

The AES-CMAC implementation is shared source with the peer implementations, so the two
cannot drift apart. The wire format is described in the source as dictated by the receivers —
it cannot be changed on this side alone.

## 11.3 CAN: update coordination

`update-coordinator` orchestrates firmware updates across ECUs, and its CAN interface is
SecOC-authenticated in both directions — unlike the credential sender, which only transmits.

It uses a DID (data identifier) scheme with one freshness counter file per DID, stored under
`/data/secoc/update_coordinator/`:

| DID | Meaning | Direction |
|---|---|---|
| 1 | Cluster REQUEST | inbound |
| 2 | Cluster RUNNING | inbound |
| 3 | Cluster APPROVE | **outbound** |
| 4 | ESP32 REQUEST | inbound |
| 5 | ESP32 RUNNING | inbound |
| 6 | ESP32 APPROVE | **outbound** |

Outbound counters use a strict `+1` increment; inbound counters record the last accepted
value per DID, which is what enforces the strictly-greater rule on received frames.

Its key material is installed at `/etc/ota_secoc.key`, mode 0400, root-only.

The node's role in an update is to hold the vehicle still: the OTA agent asks it to lock the
system before installing and to unlock afterwards. §12.5 covers the sequencing, including
why the lock and unlock are structured the way they are.

## 11.4 MQTT: the cloud interface

Two services on the Orin talk to an Adafruit IO MQTT broker over TLS. Three feeds are used,
all under one account:

| Feed | Direction | Used by | Content |
|---|---|---|---|
| `ivi-ota` | inbound (and command/response) | `ivi-ota-agent` | Campaign announcements; ping and version queries |
| `ivi-status` | outbound | `ivi-ota-agent` | Update progress and result reports |
| `logs` | outbound | `jetson-status-agent` | Vehicle health telemetry |

Feed names are configurable — `AIO_FEED` and `AIO_STATUS_FEED` in the agent configuration —
with those values as defaults. The full topic is `<account>/feeds/<feed>`.

### Campaign format

A campaign is a single pipe-delimited line of at most 1024 bytes published to the `ivi-ota`
feed:

```
IVI1|<version>|<url>|<wrapped_key>[|<size>|<sha256>]
```

| Field | Meaning |
|---|---|
| `IVI1` | Target tag. The agent ignores anything not carrying its tag. |
| `<version>` | Strict semver, `X.Y.Z` |
| `<url>` | `https://` URL of the encrypted package, which must begin with an allowlisted prefix |
| `<wrapped_key>` | `base64(RSA(KEY_HEX:IV_HEX))` — the AES session key and IV, wrapped to this device's public key |
| `<size>` | Optional. Byte size of the **decrypted** `.swu` |
| `<sha256>` | Optional. SHA-256 of the **decrypted** `.swu` |

Pipe delimiting is safe because neither a URL nor base64 can contain `|`, so the split is
unambiguous. Fields 5 and 6 are optional: the publishing dashboard computes both but does not
send them yet. When present they are checked; when absent the integrity guarantee falls back
entirely to SWUpdate's own checksums and signature.

### Commands and replies

The same feed carries two queries, and the agent replies on the status feed:

| Received | Reply |
|---|---|
| `P` | `R1\|status\|pong — agent alive, running <version>` |
| `Q1?` (or `IVI1?`) | `R1\|version\|X.Y.Z` |

Status reports during an update use the same `R1|status|<text>` form — for example
`R1|status|starting update 1.1.1 -> 1.1.2`, `R1|status|refused downgrade …`,
`R1|status|FAILED <version> — decryption failed`.

### Telemetry format

`jetson-status-agent` publishes to the `logs` feed, which carries three nodes' messages
interleaved, so every line identifies its sender. This board's ECU identifier is `0xA2`.

Messages are a five-field line carrying a code, a severity and a packed auxiliary value:

| Code | Meaning |
|---|---|
| `0x30` | BOOT (shared with the ESP32) |
| `0x58` | SYS HEALTH (shared with the cluster guest — same AUX layout) |
| `0x60` | GPU LOAD |
| `0x61` | THERMAL |
| `0x62` | DISK LOW |
| `0x63` | SYS SUMMARY — carries `0x58` and `0x60` in one point; the default the periodic tick sends. The only code with a 16-digit AUX. |
| `0x64`–`0x6F` | Unallocated |

Severity is `0` INFO, `1` WARN, `2` ERROR, `3` FATAL.

Two deliberate exceptions and one constraint:

- **The WiFi address does not take a code.** It is published as a bare dotted quad, meant to
  be read by a human off the feed rather than decoded by the dashboard. It is the one message
  on this feed that is not in the five-field format, and anything parsing the feed strictly
  must tolerate it. It exists because "the vehicle has no address" is the single most useful
  thing the feed can say — `0.0.0.0` is what no-WiFi-address looks like.
- **Percentages are packed into a single byte and clamped in code**, so a rounding artefact
  of 104% cannot arrive intact and overfill a dashboard gauge. `-1` means "could not read"
  and is preserved as `-1` until packing time.
- **A minimum publish interval of 30 s is enforced in code.** The Adafruit IO account allows
  30 points per minute across all feeds, shared with the OTA feed. The floor lives in the
  program rather than in documentation, because documentation is not what runs.

---

# 12. OTA update architecture

The vehicle must be updatable in the field, without a laptop, without losing state, and
without any possibility of being pushed backwards into a known-bad version. This section
covers the whole mechanism: what SWUpdate provides, how it is wired to the Tegra partition
layout, the three payload types this layer produces, the version discipline that binds them,
and the agent that drives the whole thing from an MQTT message.

## 12.1 SWUpdate concepts

SWUpdate is an embedded update framework. Its unit of work is a `.swu` file: a cpio archive
containing a manifest called `sw-description`, the artefacts the manifest refers to, and —
when signing is enabled — a detached signature `sw-description.sig`.

The manifest declares, per machine and optionally per slot, three kinds of entry:

| Entry kind | Meaning |
|---|---|
| `images` | Written to a raw device or partition |
| `files` | Written into a mounted filesystem at a path |
| `scripts` | Executed at defined points in the install (`preinst`, `postinst`), typically Lua |

SWUpdate is invoked with a *selection* — a mode name — which chooses which block of the
manifest applies. That is the mechanism the A/B logic is built on.

Two configuration decisions in this layer make the framework trustworthy:

**`CONFIG_SIGNED_IMAGES=y`**, supplied by `recipes-bbappends/swupdate/files/signing.cfg`.
This is the trust anchor for the entire update path. With it, SWUpdate verifies
`sw-description.sig` against a provisioned public key before installing anything. Without
it, every other check in the system is merely a convenience.

**A pinned public key path.** `recipes-bbappends/swupdate-machine-config/` injects
`public-key-file = "/usr/share/swupdate/swupdate.pem"` into the generated `swupdate.cfg`,
and `recipes-support/swupdate-key/` installs the corresponding public key at that path. The
key travels in the image; the private half never does (§13.4).

Signed images also require that every entry in the manifest carry a SHA-256, which is why
this layer overrides the stock Tegra `sw-description` (§12.2).

## 12.2 Full-image updates and the A/B slot layout

`recipes-bbappends/swupdate-image-tegra/` overrides `meta-tegrademo`'s `sw-description` with
one that adds `sha256` to every entry, and sets:

```
ROOTFS_FILENAME = "${SWUPDATE_CORE_IMAGE_NAME}-humble-${MACHINE}.rootfs.tar.gz"
```

The `-humble-` component is what makes the name match the tarball meta-ros's image class
actually produces.

The manifest declares two slots. Each contains the same four kinds of entry, with the
partition names inverted between them:

| Entry | Slot A block writes to | Slot B block writes to |
|---|---|---|
| Rootfs (`archive`, `installed-directly`, `preserve-attributes`) | `APP_b` | `APP` |
| Kernel image | Kernel B partition | Kernel A partition |
| Kernel DTB | Kernel DTB B partition | Kernel DTB A partition |
| Bootloader capsule `tegra-bl.cap` | Capsule install path | same |
| ESP archive | `/boot/efi` | same |
| Lua script `tegra-swupdate-script.lua` | — | — |

The naming is the part that trips people up on first reading: the block named `slot_a` is
the one selected **when running from slot A**, and it therefore writes into `APP_b`. An
update always writes the slot it is *not* running from.

Each slot's block begins with a `diskformat` partition entry (`fstype = "ext4"`,
`force = "true"`), so the target rootfs is reformatted before the archive is unpacked into
it. That guarantees the new slot contains exactly the new image and no residue of whatever
was there before.

The bootloader capsule entries carry `install-if-different` with a version, so a firmware
capsule is only written when it actually changes — governed by
`TEGRA_SWUPDATE_BOOTLOADER_INSTALL_ONLY_IF_DIFFERENT`, set in the build configuration
(§14.3).

`hardware-compatibility: [ "1.0" ]` is SWUpdate's guard against installing a package built
for different hardware.

### The fresh-flash marker patch

`recipes-bbappends/swupdate-machine-config/` also carries
`0001-genconfig-create-complete-markers-on-fresh-flash.patch`, which addresses a
first-boot-only defect in the Tegra slot bookkeeping.

The generator's logic promotes an `-inprogress` marker to a complete marker after a
successful boot, which handles the normal update case. It has no case for a slot with **no
markers at all** — which is exactly the state after an initial `flash.sh`, as opposed to
after a capsule recovery. The patch adds that case, creating complete markers for both slots
on a fresh boot. Without it, the first OTA on a freshly flashed board misreads the slot state.

## 12.3 Partial updates: the two payload recipes

Reinstalling an entire rootfs to ship a new build of one application is disproportionate — on
a tethered connection it is the difference between tens of megabytes and a few. The layer
therefore produces two additional, much smaller payloads.

Both are built the same way: a recipe that `inherit swupdate`, a `sw-description` naming the
artefacts, and a Lua `postinst` script that does the actual installation on the device.

The artefacts come from `do_deploy` tasks on the packages themselves. `ivi.bb`,
`ackermann-description.bb`, `ackermann-bringup.bb` and `ackermann-hardware.bb` each define:

```
do_deploy() {
    tar czf ${DEPLOY_DIR_IMAGE}/<name>-${MACHINE}.tar.gz -C ${D} .
}
addtask deploy after do_install before do_build
```

That tarball is not part of the image; it exists solely so the payload recipes can pick it
up out of `DEPLOY_DIR_IMAGE`.

**`recipes-qtapps/ivi-update/`** bundles one tarball, `ivi-app-${MACHINE}.tar.gz`, delivered
to `/tmp/ivi-app.tar.gz`, plus `install-ivi.lua`. The Lua script performs a careful
in-place replacement: it unpacks into a staging directory, backs up the existing
`/usr/bin/appIVI` and the assets tree, installs the new binary via a `.new` temporary and a
rename, and cleans up. It checks whether the service is currently active so it can restart it
appropriately, and it removes leftovers from a previous failed attempt on entry.

**`recipes-devupdates/ackermann-update/`** bundles three tarballs — description, bring-up and
hardware — delivered to `/tmp/`, plus `install-ackermann.lua`. The three ROS packages ship as
one unit because they are versioned as one unit (§3.4 applies the same reasoning at the
repository level).

Both declare `IMAGE_DEPENDS` on the packages that produce their tarballs, `SWUPDATE_IMAGES`
naming the tarballs, and both add their version variable to `do_swuimage[vardeps]` so a
version bump correctly invalidates the task.

Both payloads are delivered through the same agent and the same `IVI1` tag as full-image
updates. The agent does not distinguish between them — which is precisely what makes §12.4
necessary.

## 12.4 One version counter for the whole vehicle

`conf/include/vpace-ota-version.inc` defines a single variable:

```
VPACE_OTA_VERSION ?= "1.1.2"
```

Three recipes require this file: `ivi-update.bb` (as `IVI_APP_VERSION`),
`ackermann-update.bb` (as `ACKERMANN_VERSION`), and `ivi-ota-agent_1.0.bb` (as the generated
`INITIAL_VERSION`).

### Why there is only one

The agent keeps a **single** installed-version record at `/data/ota/installed_version`, and
every campaign is compared against it regardless of which payload it carries. There is no
per-target counter.

The consequence is unavoidable arithmetic: installing IVI app 1.0.7 immediately makes
ackermann 1.0.6 uninstallable, because the shared counter has already passed it and the
downgrade gate refuses anything not strictly greater. The two payload versions therefore
cannot be independent in any meaningful sense — they were only ever kept equal by hand. The
include file converts that convention into a mechanism.

The operational rule follows directly: **bump the number here, rebuild both `.swu` payloads,
publish whichever one you actually want to ship.** There is no requirement to publish both.

### The `INITIAL_VERSION` floor

The same variable also generates the agent's `INITIAL_VERSION` — the version the agent
reports when it has no version file to read. That happens on a board flashed with NVIDIA's
`flash.sh` (which can take `/data`'s partition with it), a board whose `/data` failed to
mount, or a board that has never taken an OTA.

The floor has to name the version actually baked into the image, and both errors are real:

- **Too low** (the old hardcoded `0.0.0`) and every archived campaign looks like an upgrade.
  The downgrade gate is the only thing stopping a replayed old-but-validly-signed package —
  SWUpdate's signature check passes those happily.
- **Too high** and a freshly flashed board refuses the very campaign it should take.

`ivi-ota-agent`'s `do_install` generates `INITIAL_VERSION=` into the installed
`/etc/ivi-ota/agent.conf` from this variable, and **deletes** any `INITIAL_VERSION` already
present in the out-of-tree source configuration first. `agent.conf` is sourced by `sh`, so
the appended value would win regardless — but a file containing two different answers is a
trap for whoever reads it on the board next.

Generating rather than hand-maintaining is the point. A hand-maintained floor in a gitignored
file drifts the first time someone bumps a payload version and forgets, and nothing complains
until a flashed board either accepts a downgrade or refuses a legitimate update.

## 12.5 The agent: end-to-end flow

`recipes-apps/ivi-ota-agent/ivi-ota-agent/ivi_ota_agent.sh` is a 748-line POSIX shell script
running as a systemd service. It is written for BusyBox — the image has no package manager
and ships no `base64` binary, so it uses `openssl base64` throughout — and logs to the
journal.

### The central rule

The script states its own threat model in its header, and it is the correct one:

> **The MQTT message is untrusted input.** It is a HINT — what version, where to get it,
> which session key — never an AUTHORITY on whether to install.

And, crucially, it is explicit about what the encryption does and does not buy. The AES
session key is wrapped with the device's **public** key, and a public key is public. An
attacker can generate their own session key, encrypt their own `.swu` with it, wrap it for
the device, and publish that. **Decrypting successfully proves nothing about origin.** It
buys confidentiality — nobody reads the image off the wire or out of the bucket — and
nothing else.

Authenticity is SWUpdate's job, via the signature over `sw-description`. Every check the
agent performs is about *not wasting time on garbage*, with one exception: the downgrade
gate, which closes the one hole the signature does not, because a replayed old but
legitimately signed package verifies perfectly.

### The sequence

1. **Subscribe** to `<account>/feeds/<AIO_FEED>` over TLS on port 8883.
2. **Answer queries.** `P` → pong with the running version; `Q1?` → the running version.
3. **Tag match.** Anything not beginning with `IVI1|` is ignored.
4. **Reject shell metacharacters.** The message arrived from the internet and is about to be
   substituted into shell words. Base64 is `[A-Za-z0-9+/=]` and a signed bucket URL is
   similarly constrained, so any of ``` ` $ ; & < > ( ) ' " ``` or a space means the message
   is malformed or hostile either way.
5. **Validate the version** as strict semver.
6. **Check the URL against `OTA_URL_PREFIX`.** Without this allowlist, a forged message
   points the board at any host on the internet — server-side request forgery, or simply a
   huge file to exhaust the disk.
7. **Idempotence.** Adafruit IO replays the last feed value on every reconnect, so a campaign
   whose version equals the running version is skipped. Without this the board reinstalls on
   every boot.
8. **Downgrade gate.** Unless `ALLOW_DOWNGRADE=1`, the offered version must be strictly
   greater than the installed one. Semver comparison is done field by field, because
   BusyBox's `sort` may lack `-V`.
9. **Rate limit.** A minimum interval between attempts, recorded in a timestamp file.
10. **Ask the driver.** §12.5.1.
11. **Lock the vehicle**, download, decrypt, verify, install, **unlock**. §12.5.2.
12. **Report** on the status feed at each stage.

Everything before step 10 is free, which is why the approval prompt sits exactly there: tag,
version, allowlist, downgrade gate and rate limit have all passed, so the prompt can state a
version the agent actually believes in, and a refusal costs the driver nothing.

### 12.5.1 Driver approval

The agent and the head unit communicate through a directory rather than a socket or D-Bus:

- The head unit rewrites `<approval-dir>/ui-alive` every second. The agent treats the head
  unit as present only if that file's mtime is recent.
- To ask, the agent writes a JSON offer into `<approval-dir>/offers/<id>.json` —
  `{"id", "target", "version", "requested_at", "stops_vehicle"}`.
- The head unit writes a one-word verdict — `approve` or `deny` — into
  `<approval-dir>/verdicts/<id>`.

Three details are load-bearing:

- **The offer is written to a temporary name and renamed into place.** A plain redirect is
  not atomic and the application's inotify watch fires on the first byte, so it would read a
  half-written file. It would recover on its next poll, which is exactly why the bug would go
  unnoticed.
- **The agent notices a head unit that dies mid-prompt** and falls back to the `ON_NO_UI`
  policy rather than waiting out the full timeout on every campaign.
- **The agent withdraws its own offer on timeout.** The application never answers on the
  driver's behalf, so an abandoned offer would sit on screen forever.

`ON_NO_UI` selects the behaviour when no head unit is present: `deny` refuses, anything else
proceeds automatically. Unrecognised verdicts are treated as refusals.

### 12.5.2 Holding the vehicle

Registering the update with `update_coordinator` is what stops the car. The agent calls
`/update_coordinator/self_start`; the coordinator puts `jetson` in its active set, which
drives `/emergency_stop/lock` and makes `twist_mux` drop every `cmd_vel` until the agent
reports done via `/update_coordinator/self_done`.

Two properties of this design follow from that.

**It fails open by construction.** `lock_system` is silently a no-op when
`update-coordinator` is not installed — `command -v ros2` fails and the helper returns
success. That is why a stock image updates without ever stopping the vehicle: nothing is
listening, not because the agent chose not to ask.

**The unlock must run on every path out.** `run_update()` exists as a separate function
purely so the unlock cannot be skipped: there are eight early returns inside it — download
failure, key unwrap, decryption, cpio magic, size, hash, and SWUpdate itself — and locking
inline with a release at the bottom would leave the car immobilised on any one of them. The
coordinator does have a force-release timeout, but it is set to 1200 s in the unit, so
relying on it means **twenty minutes of an immobilised car after a download that failed in
two seconds.**

The lock is taken at *approval*, not at download start, because approving is what commits the
driver: `stops_vehicle` is `true` in the offer, and holding the road until the install
completes is the point.

### 12.5.3 Download, decrypt, verify

**Download** is anonymous. The bucket is private, but the campaign URL carries its own
authorisation token, so no storage credential lives on the device. The token expires (seven
days), which means an old campaign replayed after that fails at the download rather than
installing something stale. `curl` is invoked with `--proto '=https' --tlsv1.2`, a 900-second
cap and `--max-filesize`.

**Key unwrap** is `openssl base64 -d -A` then `openssl pkeyutl -decrypt` with the device
private key, yielding `KEY_HEX:IV_HEX`. `-A` is required: the wrapped key is one long
unwrapped line and `openssl` chokes past 76 characters without it. The unwrapped value is
never logged. Both halves are validated by length and character class — 64 hex characters
for the AES-256 key, 32 for the IV — and a failure is reported with the two plausible causes
named (wrong keypair, or a corrupt field) rather than as a generic error.

**Decryption** is AES-256-CBC.

**Sanity checks before spending a SWUpdate run.** AES-CBC has no MAC, so a corrupted download
decrypts to garbage rather than failing. SWUpdate would catch that on its own checksums, but
late and with a worse message. A cpio magic check costs nothing and localises the fault.
The optional size and SHA-256 fields from the campaign are checked here too, when present.

**Install** hands the `.swu` to SWUpdate, which verifies the signature and the per-entry
hashes and performs the slot write.

**Report** publishes the outcome. `mosquitto_pub` has no timeout option and libmosquitto
sits on a stalled connection for roughly 60 seconds, so the agent runs the publish in the
background under a watchdog kill — a detail that matters because the two post-install reports
happen at the least convenient moment.

## 12.6 Persistent state

`recipes-core/data-partition-mount/` runs before `local-fs.target` and does three things:
adds `/data` to `/etc/fstab` if absent, formats `/dev/nvme0n1p15` if it is unformatted,
and mounts it. It then creates three directory trees, and the script's own comment explains
why each cannot live on the rootfs:

**`secoc/`** — SecOC freshness counters. The CAN peers keep counting across *our* flash. If
ours reset, our frames are rejected — and the rejection is logged on the *other* processor,
so on this board it presents as "the OTA request just never gets approved", with nothing in
the local journal to explain it.

**`network/`** — NetworkManager's learned WiFi profiles and state directory, so a reflashed
board rejoins the last network it was on instead of needing a keyboard and a monitor.

**`ota/`** — the installed-version record. Lose it and the board reports its
`INITIAL_VERSION` floor instead: it reinstalls the campaign it is already running (holding
the vehicle while it does), and the downgrade gate stops rejecting replayed old packages,
which is the one attack the signature check does not cover.

Two implementation choices in that script:

**Everything is guarded on the mount having actually succeeded.** Creating these directories
under an *unmounted* `/data` writes them into the rootfs directory hiding beneath the mount
point. It looks like it works, and then silently loses every value on the next flash — the
exact failure the arrangement exists to prevent. A guard that is skipped is worse than no
guard at all here.

**One failure is deliberately not swallowed.** `wifi_cred_send` is launched by the head unit
as `weston`, so `/data/secoc/wifi_cred_txfv` must be writable by that user while the
directory above stays root-owned. If the `chown` fails, `weston` cannot write the store,
`wifi_cred_send` prints one warning to a stderr nobody is reading, **and the send still
succeeds** — so the counter quietly stops advancing and nothing looks wrong until a receiver
starts rejecting the vehicle. The script therefore logs that failure loudly into the journal.

The file is also pre-created empty on purpose: the loader's `fscanf` fails on an empty file
and returns 0, and the sender then falls back to Unix time, which is exactly how a
never-provisioned board has always behaved.

The script uses plain `mkdir`/`chmod` rather than `install -d` because it runs before
`local-fs.target` on a BusyBox userland, where `mkdir` is the option certain to be present.

One policy difference between two consumers of `/data` is worth noting because it is
intentional: the OTA agent **fails open** if `/data` is unavailable — it warns and carries on
— while `update-coordinator.service` **fails closed** on the same partition. An update agent
that refuses to run is a vehicle that can never be fixed remotely; a coordinator that runs
without its freshness counters is a security regression.

---

# 13. Security mechanisms

This section describes the mechanisms the system implements, and the properties each one is
designed to provide. It is a description of the design, not a threat assessment.

## 13.1 The update trust chain

The chain has one anchor and several layers built on it.

**Anchor: signature verification in SWUpdate.** `CONFIG_SIGNED_IMAGES=y` makes SWUpdate
verify `sw-description.sig` against a public key provisioned on the device before it installs
anything. The public key is baked into the image at `/usr/share/swupdate/swupdate.pem` and
pinned in `swupdate.cfg` by the `swupdate-machine-config` `.bbappend`. Build configuration
selects RSA signing and names the private key file, which lives outside the image entirely
(§13.4).

**Per-entry integrity.** Signed images require every entry in the manifest to carry a
`sha256`, computed at build time by SWUpdate's own `$swupdate_get_sha256()` expansion. This
is why the layer overrides the stock Tegra `sw-description` rather than using it as shipped.

**Hardware compatibility.** `hardware-compatibility: [ "1.0" ]` in every manifest prevents a
package built for different hardware from installing.

**Slot isolation.** An update writes only the inactive slot, and the target rootfs is
reformatted before the archive lands. The running system is never modified in place, so an
interrupted or failed update leaves a bootable system.

**Confidentiality in transit and at rest.** Packages are encrypted with a per-campaign
AES-256-CBC session key, wrapped to the device's RSA public key. The bucket is private and
the campaign URL carries a short-lived authorisation token, so no storage credential lives on
the device.

As §12.5 sets out, the encryption provides confidentiality only — anyone can wrap a key to a
public key. Authenticity comes from the signature.

## 13.2 Replay and downgrade protection

A signature alone does not distinguish a current package from an old one. A replayed old but
legitimately signed package verifies perfectly. Three mechanisms close that:

1. **Monotonic version gating.** With `ALLOW_DOWNGRADE=0`, the agent installs only versions
   strictly greater than the recorded installed version. This is the primary gate.
2. **The record survives updates.** The installed-version file lives on `/data`, which
   survives a rootfs swap. Placing it on the rootfs would reset it on every update and defeat
   the gate.
3. **A correct floor when the record is missing.** `INITIAL_VERSION` is generated from the
   image's own OTA version (§12.4), so a board with no record reports the version it is
   actually running rather than `0.0.0`.

Two secondary mechanisms limit exposure: campaign URLs expire after seven days, so an old
campaign fails at download; and the agent rate-limits install attempts.

## 13.3 CAN message authentication — SecOC

Inter-node CAN messages carrying security-relevant content are authenticated with SecOC
(Secure Onboard Communication), in the form the peers implement:

**Authentication.** A truncated AES-128-CMAC over the message content concatenated with the
freshness value. The first 4 bytes of the CMAC are transmitted. Truncation is what makes the
scheme fit CAN's 8-byte frames.

**Freshness.** A 4-byte big-endian counter accompanies each message. A receiver accepts only
if the MAC verifies **and** the freshness is strictly greater than the last value it
accepted, persisted across reboot. That is what makes a captured frame useless to replay.

**Counter policy differs by direction and by service:**

| Service | Direction | Counter policy |
|---|---|---|
| `wifi-cred-sender` | transmit only | `max(unix_time, last_sent + 1)` — monotonic without knowing the receiver's floor |
| `update-coordinator` | transmit and receive | strict `+1` per outbound DID; per-DID last-accepted value for inbound |

Receivers keep **independent** floors, so the two targets of a credential send do not have to
agree on the number.

**Key material.** `update-coordinator` installs its key at `/etc/ota_secoc.key`, mode 0400,
root-only. The credential sender's key ships in its package.

**Shared implementation.** The AES-CMAC code is the same source the peer implementations
compile, so the two implementations cannot drift apart — a property that matters more than
it sounds for a truncated MAC, where a padding or endianness disagreement produces silent
rejection rather than an error.

**Counter persistence** is on `/data` for the reasons in §12.6, and the `chown` failure on
the credential sender's store is deliberately logged loudly because its failure mode is a
counter that silently stops advancing.

## 13.4 Handling of key material

Private key material never enters the repository. `.gitignore` excludes `secret/`, and the
build configuration points at that directory by absolute path:

| Variable | Points at |
|---|---|
| `SWUPDATE_PRIVATE_KEY` | The RSA private key used to sign `.swu` packages |
| `OTA_AGENT_PRIVATE_KEY_PATH` | The device private key that unwraps AES session keys |
| `OTA_AGENT_CONF_PATH` | The agent configuration containing broker credentials |

The agent's key and configuration are installed into `/etc/ivi-ota/` with mode 0600 in a
directory created 0700. The build fails if the paths are unset or unreadable, which is the
correct behaviour — an image built without them would install but never authenticate.

`INITIAL_VERSION` is the one value deliberately *not* taken from the out-of-tree
configuration; §12.4 explains why it is generated instead.

## 13.5 Input validation on untrusted messages

The OTA agent validates MQTT input at every stage, since the message crosses from the
internet into shell:

- **Shell metacharacter rejection.** Any of ``` ` $ ; & < > ( ) ' " ``` or a space in the
  message body rejects it. The legitimate content — base64 and a signed URL — cannot contain
  any of them.
- **URL allowlist.** The URL must begin with a configured prefix. Without it a forged message
  points the board at an arbitrary host.
- **Strict format validation.** Semver by regular expression; AES key and IV by exact hex
  length; package by cpio magic before SWUpdate is invoked.
- **Size cap.** `--max-filesize` on the download, plus the optional declared size checked
  against the decrypted result.
- **Bounded waits.** Every network operation has a timeout, including the status publish,
  which is run under a watchdog kill because `mosquitto_pub` has no timeout option.

## 13.6 Process isolation on the device

The head unit runs as the unprivileged `weston` user, not root. That is a security property
first, and the cause of two engineering problems described earlier — the DDS shared-memory
permission failure (§7.3) and the SecOC counter ownership arrangement (§12.6). Both were
solved without granting the application root.

---

# 14. Build and flash guide

## 14.1 Host prerequisites

A standard Yocto build host: a 64-bit Linux distribution supported by scarthgap, with the
usual `build-essential`-class package set, Python 3, `git`, `chrpath`, `diffstat`, `zstd`
and `lz4`. Yocto's own documented host requirements for scarthgap apply unchanged.

Resource requirements are set by the size of the stack rather than by this layer. A ROS 2
Humble image with CUDA, TensorRT and Qt 6 is a very large build:

| Resource | Guidance |
|---|---|
| Disk | Several hundred GB for `tmp`, plus a shared downloads and sstate cache |
| RAM | One `.bbappend` in this layer records an out-of-memory failure in `spatio-temporal-voxel-layer` at high parallelism (§8.6); the reference configuration uses `BB_NUMBER_THREADS = "4"` and `PARALLEL_MAKE = "-j 4"` |
| Time | A first build from cold sstate is measured in many hours |

`INHERIT += "rm_work"` in the reference configuration deletes each recipe's work directory
after it builds, which trades the ability to inspect a build tree afterwards for a very large
saving in disk. On a stack this size that trade is usually worth taking.

## 14.2 Workspace layout

The reference workspace holds the layer stack as sibling checkouts, with `tegra-demo-distro`
providing the poky/meta-tegra/meta-oe set through its own `layers/` directory:

```
Yocto_WS/
├── tegra-demo-distro/        NVIDIA demo distro; supplies layers/meta, meta-tegra,
│   ├── layers/               meta-oe, meta-python, meta-networking, meta-filesystems,
│   └── setup-env             meta-tegra-community, meta-tegra-support, meta-tegrademo
├── meta-vpace/               this layer
├── meta-qt6/
├── meta-ros/                 meta-ros-common, meta-ros2, meta-ros2-humble
├── meta-swupdate/
├── meta-openembedded/
├── poky/
├── share/                    shared DL_DIR and SSTATE_DIR
└── build-orin/               the build directory
```

`tegra-demo-distro` supplies a `setup-env` script that initialises a build directory with a
given `MACHINE` and `DISTRO` and generates a `bblayers.conf` from its own template. This
layer is then added to that stack.

## 14.3 Build configuration

### `bblayers.conf`

The reference `BBLAYERS`, in order:

```
tegra-demo-distro/layers/meta
tegra-demo-distro/layers/meta-tegra
tegra-demo-distro/layers/meta-oe
tegra-demo-distro/layers/meta-python
tegra-demo-distro/layers/meta-networking
tegra-demo-distro/layers/meta-filesystems
tegra-demo-distro/layers/meta-tegra-community
tegra-demo-distro/layers/meta-tegra-support
tegra-demo-distro/layers/meta-demo-ci
tegra-demo-distro/layers/meta-tegrademo
meta-vpace
meta-qt6
meta-ros/meta-ros-common
meta-ros/meta-ros2
meta-ros/meta-ros2-humble
meta-swupdate
```

`meta-vpace` sits above the Tegra layers and below the Qt and ROS layers in the list; its
`BBFILE_PRIORITY` of 6 is what actually decides precedence for its `.bbappend` files, not
list position.

### `local.conf` — required settings

These are the settings without which the build either fails or produces a wrong image. They
are required because they are machine-, site- or secret-specific and therefore cannot be
pinned in the layer (contrast §4.4, which pins everything that *can* be).

```conf
MACHINE ?= "p3768-0000-p3767-0001"
DISTRO  ?= "vpace"

# SWUpdate: name the image the full-image payload wraps, and sign it.
SWUPDATE_CORE_IMAGE_NAME = "orinivi-image"
SWUPDATE_SIGNING = "RSA"
SWUPDATE_PRIVATE_KEY = "<abs path>/meta-vpace/secret/swupdate-private-key.pem"

# OTA agent secrets — see §13.4. Outside the repository by design.
OTA_AGENT_PRIVATE_KEY_PATH = "<abs path>/meta-vpace/secret/ivi_priv.pem"
OTA_AGENT_CONF_PATH        = "<abs path>/meta-vpace/secret/agent.conf"

# Tegra capsule policy and the marker path on the persistent partition.
TEGRA_SWUPDATE_BOOTLOADER_INSTALL_ONLY_IF_DIFFERENT = "true"
TEGRA_SWUPDATE_LAST_CAPSULE_UPDATE_COMPLETE_SLOT_MARKER = "/data/swupdate-capsule-update-slot-"

# The rootfs tarball the full-image payload consumes.
IMAGE_FSTYPES:append = " tar.gz"

# Touch panel and audio.
MACHINE_FEATURES:append = " alsa touchscreen "

LICENSE_FLAGS_ACCEPTED += "commercial"
```

Site-specific settings in the reference configuration:

```conf
DL_DIR     ?= "<abs path>/share/downloads"
SSTATE_DIR ?= "<abs path>/share/sstate-cache"
BB_NUMBER_THREADS = "4"
PARALLEL_MAKE     = "-j 4"
INHERIT += "rm_work"
EXTRA_IMAGE_FEATURES ?= "debug-tweaks"
```

`debug-tweaks` is a development convenience — it permits an empty root password among other
things — and is the first line to remove for a production image.

### Secrets

Three files must exist under `meta-vpace/secret/`, which is `.gitignore`d:

| File | Purpose |
|---|---|
| `swupdate-private-key.pem` | Signs `.swu` packages. Its public half is `recipes-support/swupdate-key/files/swupdate.pem`, installed on the device. |
| `ivi_priv.pem` | The device's RSA private key, used to unwrap AES session keys from campaigns. |
| `agent.conf` | Broker host and credentials, feed names, the URL allowlist prefix, approval policy. `INITIAL_VERSION` in it is ignored and regenerated (§12.4). |

## 14.4 Build targets

Initialise the environment (from `tegra-demo-distro`, with the machine and distro set), then:

**The image:**

```bash
bitbake orinivi-image
```

**The full-image OTA payload:**

```bash
bitbake swupdate-image-tegra
```

**The IVI application partial payload:**

```bash
bitbake ivi-update
```

**The Ackermann stack partial payload:**

```bash
bitbake ackermann-update
```

All four land in `tmp/deploy/images/p3768-0000-p3767-0001/`. The `.swu` files are the
signed packages; the full-image one is named after the image, the partial ones after their
recipes.

Before building a payload, confirm `VPACE_OTA_VERSION` in
`conf/include/vpace-ota-version.inc` is greater than what the target boards report (§12.4).
Bumping it invalidates `do_swuimage` for both payload recipes, which is what the
`vardeps` lines exist to guarantee.

## 14.5 Flashing

Flashing uses meta-tegra's standard mechanism. The image build produces a
`*.tegraflash.tar.gz` in the deploy directory containing the partition images, the flashing
tools and a `doflash.sh` driver script.

The procedure:

1. Put the board into recovery mode — hold the force-recovery button while power-cycling,
   with a USB cable from the carrier's device port to the host.
2. Confirm the host sees it: `lsusb` shows an `0955:` NVIDIA device.
3. Unpack the tegraflash tarball into a working directory.
4. Run `./doflash.sh` from that directory, as root.

The board reboots into the newly flashed image when the script completes.

**What a flash does to `/data`.** A full flash rewrites the partition table, so
`nvme0n1p15` can be recreated empty. `mount-data-partition.sh` will then format and mount a
fresh filesystem, and the board loses its SecOC freshness counters, its learned WiFi
profiles and its installed-version record. On a board that has been in service, capture
those before flashing; on a new board there is nothing to lose. §12.6 lists what is at
stake in each directory.

## 14.6 What a correct first boot looks like

After a flash:

- `systemctl status data-partition-mount` succeeded, and `/data` is mounted with
  `secoc/`, `network/` and `ota/` present.
- `systemctl status gpu-clock-policy` succeeded; `cat /sys/…/devfreq/…/cur_freq` shows a
  GPU near 1170 MHz rather than 305 MHz (§6.3).
- `weston.service` is active and `ivi-app.service` is active as user `weston`, with the head
  unit on the panel.
- `networkctl status can0` reports `State: carrier (configured)` — an address is never
  expected on a CAN link (§10.1).
- `ivi-ota-agent` is connected: `journalctl -u ivi-ota-agent` shows the broker and topic.
- `update-coordinator` is active.
- `cat /etc/vpace-build` gives the metadata revision and build date of the running image
  (§5.4).
- `lsmod | grep -c tegra_se` returns 0 — the security engine must not be loaded (§6.2).

Two things are expected *not* to be running: `lidar-perception.service` and
`camera-sign-detect.service`. The first is disabled deliberately (§5.5) and the second is
started by udev when the camera is plugged in.

---

# 15. Conventions and maintenance

## 15.1 Recipe conventions

**Layout.** One directory per recipe under a `recipes-<group>/` directory, with loose files
in a `files/` subdirectory alongside. Recipes that mix a `git://` fetch with `file://`
entries set `UNPACKDIR ?= "${WORKDIR}"` and reference the unpacked files through
`${UNPACKDIR}` or `${WORKDIR}` explicitly, because `S` points into the git clone and the
`file://` entries land elsewhere. Three recipes do this and each notes the arrangement.

**Portability of `UNPACKDIR`.** Several recipes carry:

```
# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
UNPACKDIR ?= "${WORKDIR}"
```

The `?=` keeps the newer definition where it exists and supplies the old default otherwise.

**ROS recipes** declare `ROS_BUILD_DEPENDS` / `ROS_BUILDTOOL_DEPENDS` / `ROS_EXEC_DEPENDS`
and then assign them into `DEPENDS` / `RDEPENDS`, mirroring `package.xml` and matching the
shape of the generated meta-ros recipes.

**`pluginlib` plugins** need the three-line unversioned-`.so` fix described in §8.3.

**Systemd units** ship with `SYSTEMD_SERVICE:${PN}` and an explicit `SYSTEMD_AUTO_ENABLE`.
Where the value is `disable`, the recipe says why (§5.5).

## 15.2 Source revision pinning

Every first-party ROS package pins an explicit `SRCREV`. The five LiDAR-perception packages
share one through `conf/include/ros2-lidar-perception.inc` (§3.4).

**One recipe uses `AUTOREV`**: `ivi.bb`, with `SRCREV = "${AUTOREV}"` and
`PV = "1.0+git${SRCPV}"`. That means every build re-fetches the head of the application's
`main` branch, so the head unit is never stale — appropriate for the component under the most
active development, and a deliberate exception rather than an oversight. It also means image
builds are not reproducible while it stands. Pinning it is the right move for any build whose
output needs to be reproducible after the fact.

Third-party sources are pinned: the Livox driver, the YOLO wrapper.

## 15.3 Licence declarations

The layer's policy is that a licence declaration must describe what is actually in the tree,
because the image manifest is a legal artefact. Two recipes illustrate it.

**`lidar-tracking`** declares `LICENSE = "AB3DMOT-academic"` with
`NO_GENERIC_LICENSE[AB3DMOT-academic] = "LICENSE"`. Its `package.xml` says MIT. Its
`LICENSE` file is the AB3DMOT software licence agreement, which reads "ACADEMIC OR
NON-PROFIT ORGANIZATION NONCOMMERCIAL RESEARCH USE ONLY". The two cannot both be true.
Declaring MIT would put MIT into the image's licence manifest — that is, it would make the
product's stated legal position a copy of the wrong one of two contradictory files, silently.

The recipe declares what is in the tree and lets the manifest tell the truth. Nothing filters
on it (the distro sets no `INCOMPATIBLE_LICENSE`, §4.5), so this does not block a build; it
makes the problem visible instead of burying it. The recipe also records the three ways to
resolve it: confirm the C++ port is an independent implementation of the published algorithm
and delete the inherited `LICENSE`; obtain a commercial grant; or accept the restriction on
the shipped product.

**`object-detection-msgs`** and **`object-visualization`** declare `CLOSED` where the source
of truth is ambiguous — `package.xml` or `setup.py` says MIT, but there is no licence text in
the package to checksum. `CLOSED` is honest in the meantime, and each recipe records exactly
what to change once a `LICENSE` file lands at the repository root.

The general rule: **when the evidence is contradictory, declare what the tree contains and
write down how to resolve it.** A comfortable declaration that cannot be substantiated is
worse than an inconvenient one that can.

## 15.4 Adding a recipe

For a new first-party ROS 2 package:

1. Create `recipes-ros2packages/<name>/<name>.bb` — hyphenated recipe name for an
   underscored package name.
2. `inherit ros_distro_humble` and the appropriate build class.
3. `SRC_URI` and a pinned `SRCREV`; `S` pointing at the package subdirectory if the
   repository holds several. If it belongs to an existing multi-package repository, `require`
   that repository's `.inc` instead of repeating the fetch.
4. Declare `ROS_*_DEPENDS` and assign into `DEPENDS`/`RDEPENDS`.
5. If it exports a `pluginlib` plugin, apply the unversioned-`.so` fix.
6. If it needs a service, ship the unit and set `SYSTEMD_AUTO_ENABLE` deliberately.
7. Add the package to `IMAGE_INSTALL:append` in `orinivi-image.bb` unless another package's
   `RDEPENDS` pulls it in.
8. If it must be OTA-updatable on its own, add a `do_deploy` tarball and a payload recipe.

For a modification to somebody else's recipe, put the `.bbappend` in `recipes-bbappends/` and
say in a comment *why* — §8.6 shows the standard this sets.

## 15.5 Adding an OTA payload

1. Give each package a `do_deploy` producing a tarball in `DEPLOY_DIR_IMAGE`.
2. Create a payload recipe that `inherit swupdate`, with a `sw-description` listing the
   tarballs as `files` entries with `sha256` expansions, and a Lua `postinst` script.
3. `require conf/include/vpace-ota-version.inc` and derive the payload's version variable
   from `VPACE_OTA_VERSION` — never a literal.
4. Set `IMAGE_DEPENDS` to the producing packages and `SWUPDATE_IMAGES` to the tarball names.
5. Add the version variable to `do_swuimage[vardeps]`.

The `sw-description` files in `recipes-qtapps/ivi-update/files/` and
`recipes-devupdates/ackermann-update/files/` are the templates to copy.

## 15.6 Notes for future maintenance

Conditions recorded in the tree that should trigger a change when they occur:

| Condition | What to do |
|---|---|
| The Livox driver gets a systemd unit | Flip `lidar-perception.service` to `SYSTEMD_AUTO_ENABLE = "enable"` and add the matching `After=`/`Requires=` (§5.5) |
| A Livox-trained detection model replaces the KITTI one | Change `MODEL_NAME` in `lidar-perception.service` |
| bluez5 starts packaging `main.conf` | Convert `bluez-config` into a `bluez5_%.bbappend` that overwrites the file, not a second package shipping the same path (§10.4) |
| The head unit subscribes to a point cloud or camera stream again | Revisit the DDS transport profile; at megabyte payloads the tradeoff flips (§7.3) |
| A `LICENSE` file lands at the perception repository root | Update `object-detection-msgs` and `object-visualization` to MIT with the new checksum (§15.3) |
| The compositor moves to a scale above 1 | The unapplied Weston screen-share patch becomes relevant again (§7.1) |
| The AB3DMOT licence question is resolved | Update `lidar-tracking`'s `LICENSE` and `LIC_FILES_CHKSUM` (§15.3) |

---

# 16. Glossary

Domain and project terms. Yocto and OpenEmbedded vocabulary is assumed.

**A/B slots** — Two complete rootfs partitions. An update writes the inactive one and the
bootloader switches on success, so a failed update leaves a bootable system.

**AB3DMOT** — A 3D multi-object tracking algorithm. `lidar-tracking` is a C++ port of it.

**ament** — ROS 2's build-system conventions and CMake/Python helper packages.
`ament_cmake` and `ament_python` are the two package types.

**AVRCP** — Audio/Video Remote Control Profile, the Bluetooth profile carrying track metadata
and transport controls.

**A2DP** — Advanced Audio Distribution Profile, the Bluetooth profile carrying stereo audio.
A phone is an A2DP *source*.

**Bus-off** — The state a CAN controller enters after accumulating too many transmit errors.
Recovery requires a restart, which `RestartSec` automates.

**Campaign** — One OTA announcement: a pipe-delimited message naming a version, a package URL
and a wrapped session key (§11.4).

**CMAC** — Cipher-based Message Authentication Code. SecOC here uses AES-128-CMAC truncated
to 4 bytes.

**Composable node** — A ROS 2 node loadable into a shared process container. Load failures
are logged rather than fatal, which is why §8.5 needs an explicit `can0` dependency.

**DDS** — Data Distribution Service, the publish/subscribe middleware under ROS 2. The
default implementation in Humble is Fast DDS.

**devfreq** — The Linux framework governing device (here, GPU) frequency scaling.

**DID** — Data Identifier. In the update coordinator's protocol, a numeric tag identifying a
particular message type and its freshness counter (§11.3).

**DRM backend** — Weston running directly on the kernel's Direct Rendering Manager, i.e. on
real display hardware.

**Freshness value** — The monotonic counter in a SecOC message that makes replay detectable.

**ISO-TP** — ISO 15765-2, the transport protocol that segments messages larger than 8 bytes
across multiple CAN frames with receiver-paced flow control.

**Kiosk shell** — A Weston shell presenting a single fullscreen application with no desktop
furniture.

**L4T** — Linux for Tegra, NVIDIA's kernel and BSP for Jetson hardware.

**meta-ros / superflore** — The layer and the generator that turn a ROS distribution index
into BitBake recipes (§8.1).

**MTP** — Media Transfer Protocol, how phones expose media storage over USB.

**nvpmodel** — NVIDIA's power-mode tool. It caps power budgets; it does not set clocks
(§6.3).

**ONNX** — Open Neural Network Exchange, the portable model format shipped in place of a
TensorRT engine.

**PointPillars** — A 3D object detection network for LiDAR point clouds, encoding points into
vertical "pillars" before a 2D backbone.

**ros2_control** — ROS 2's controller framework. A *hardware interface* plugin adapts real
actuators to the framework's abstract interfaces.

**SecOC** — Secure Onboard Communication: authenticated in-vehicle messaging using a
truncated MAC plus a freshness counter (§13.3).

**sm_87** — CUDA compute capability 8.7, the Orin's GPU architecture.

**sw-description** — SWUpdate's manifest, listing images, files and scripts per machine and
slot; signed when signed images are enabled.

**SWUpdate** — The embedded update framework used here (§12.1).

**tegraflash** — meta-tegra's flashing package: partition images, NVIDIA tools and a driver
script.

**TensorRT** — NVIDIA's inference compiler and runtime. Produces a hardware-specific
*engine* from a network (§9.1).

**trtexec** — TensorRT's command-line engine builder and benchmarking tool. On this image it
lives at `/usr/src/tensorrt/bin/trtexec`, not on `$PATH`.

**twist_mux** — A ROS node arbitrating between multiple `cmd_vel` sources by priority. The
update coordinator's lock works by making it drop everything.

**UDA partition** — The user data area partition in the Tegra layout; here `nvme0n1p15`,
mounted at `/data`.

**Vosk** — An offline speech recognition library, shipped prebuilt (§7.5).

**Wayland / Weston** — The display server protocol, and its reference compositor.

**xacro** — The XML macro language ROS uses to generate URDF robot descriptions.

**YOLO** — A family of single-shot object detection networks; used here for traffic-sign
detection.

---

# 17. Appendix: layer-specific variables

Variables defined or consumed by this layer. Standard OpenEmbedded and meta-ros variables are
not listed.

## 17.1 Defined by this layer

| Variable | Defined in | Default | Meaning |
|---|---|---|---|
| `VPACE_OTA_VERSION` | `conf/include/vpace-ota-version.inc` | `1.1.2` | The single OTA version counter for the whole vehicle. Feeds both payload versions and the agent's `INITIAL_VERSION` floor (§12.4). |
| `IVI_APP_VERSION` | `recipes-qtapps/ivi-update/ivi-update.bb` | `${VPACE_OTA_VERSION}` | Version written into the IVI payload's `sw-description`. Never set to a literal. |
| `ACKERMANN_VERSION` | `recipes-devupdates/ackermann-update/ackermann-update.bb` | `${VPACE_OTA_VERSION}` | The same for the Ackermann payload. |
| `ROS2_LIDAR_PERCEPTION_REPO` | `conf/include/ros2-lidar-perception.inc` | `github.com/ITIGP-ROS/ros2-lidar-perception.git` | Repository shared by the five perception packages. |
| `ROS2_LIDAR_PERCEPTION_BRANCH` | same | `main` | Branch for the above. |

## 17.2 Required from the build configuration

| Variable | Required by | Meaning |
|---|---|---|
| `SWUPDATE_CORE_IMAGE_NAME` | `swupdate-image-tegra.bbappend` | The image the full-image payload wraps. Must be `orinivi-image`. |
| `SWUPDATE_SIGNING` | meta-swupdate | Signing algorithm; `RSA` here. |
| `SWUPDATE_PRIVATE_KEY` | meta-swupdate | Absolute path to the signing key, outside the repository. |
| `OTA_AGENT_PRIVATE_KEY_PATH` | `ivi-ota-agent_1.0.bb` | Device RSA private key, installed to `/etc/ivi-ota/ivi_priv.pem` mode 0600. |
| `OTA_AGENT_CONF_PATH` | `ivi-ota-agent_1.0.bb` | Agent configuration, installed to `/etc/ivi-ota/agent.conf` mode 0600 with `INITIAL_VERSION` regenerated. |
| `TEGRA_SWUPDATE_BOOTLOADER_INSTALL_ONLY_IF_DIFFERENT` | Tegra `sw-description` | Whether to write the bootloader capsule unconditionally. |
| `TEGRA_SWUPDATE_LAST_CAPSULE_UPDATE_COMPLETE_SLOT_MARKER` | Tegra slot bookkeeping | Marker path; placed under `/data` so it survives updates. |

## 17.3 Set by the distribution

| Variable | Value | Section |
|---|---|---|
| `DISTRO` | `vpace` | §4.1 |
| `DISTRO_VERSION` | `1.0.0` | §4.1 |
| `PACKAGECONFIG:append:pn-ivi-ota-agent` | `ros2-coordination` | §4.4 |
| `PACKAGECONFIG:pn-camera-sign-detect-bringup` | `trt` | §4.4 |
| `PACKAGECONFIG:append:pn-weston` | `rdp` | §4.4 |
| `PACKAGECONFIG:append:pn-gstreamer1.0-plugins-base` | `opus` | §4.4 |

## 17.4 Runtime configuration on the device

Not BitBake variables — these are read on the board.

| Setting | File | Meaning |
|---|---|---|
| `GPU_CLOCK_MODE` | `/etc/default/gpu-clock-policy` | `jetson_clocks` (default), `targeted`, or `off` (§6.3). |
| `GPU_MIN_FREQ` | same | The devfreq floor used in `targeted` mode. |
| `AIO_HOST`, `AIO_PORT`, `AIO_USER` | `/etc/ivi-ota/agent.conf` | MQTT broker; defaults `io.adafruit.com`, `8883`. |
| `AIO_FEED` | same | Campaign feed; default `ivi-ota`. |
| `AIO_STATUS_FEED` | same | Status feed; default `ivi-status`. |
| `TARGET_TAG` | same | Campaign tag; default `IVI1`. |
| `OTA_URL_PREFIX` | same | URL allowlist prefix. Mandatory (§13.5). |
| `ALLOW_DOWNGRADE` | same | `0` enforces monotonic versions (§13.2). |
| `INITIAL_VERSION` | same | Generated at build time; do not hand-edit (§12.4). |
| `REQUIRE_APPROVAL`, `ON_NO_UI`, `APPROVAL_TIMEOUT_S` | same | Driver approval policy (§12.5.1). |
| `MIN_RETRY_SECS`, `MAX_BYTES`, `REPORT_TIMEOUT_S` | same | Rate limit, download cap, publish watchdog. |
| `VERSION_FILE` | same | Installed-version record; default `/data/ota/installed_version`. |
| `MODEL_NAME`, `MODEL_DIR`, `MODEL_SRC` | `lidar-perception.service` | Detection model selection and engine cache location (§8.4). |
| `FASTRTPS_DEFAULT_PROFILES_FILE` | `ivi-app.service` | Points the head unit at `/etc/dds-udp-only.xml`. Load-bearing (§7.3). |
| `QT_SCALE_FACTOR` | `/run/ivi-scale.env` | Optional; absent on a real panel (§7.4). |

---

*Related documents: [camera pipeline optimization](deep-dives/camera-pipeline-optimization.md),
[remote microphone testing](deep-dives/remote-mic-testing.md).*
