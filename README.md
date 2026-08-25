# meta-vpace

Yocto layer for the **Jetson Orin node** of V-PACE, a four-node system built as an
ITI graduation project. The other three nodes are separate hardware with their own
codebases; this layer builds the image for the Orin only, and speaks to them over CAN.

The image (`orinivi-image`) carries a Qt 6 IVI head unit, a ROS 2 Humble perception and
control stack (Livox LiDAR + TensorRT PointPillars, camera sign detection, Ackermann
`ros2_control`), and a signed A/B OTA path built on SWUpdate.

- **Machine:** `p3768-0000-p3767-0001` (Orin NX on the p3768 carrier)
- **Distro:** `vpace`, derived from `tegrademo`
- **Yocto release:** scarthgap
- **Depends on:** meta-tegra, meta-tegrademo, meta-swupdate, meta-oe, meta-networking,
  meta-filesystems, meta-qt6, meta-ros (common / ros2 / humble)

Full documentation — architecture, technologies, and the build & flash guide — is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
