# Camera sign-detection pipeline optimization

The sign-detection pipeline was configured for 2 Hz but ran at 30 Hz. Every knob
meant to throttle it was silently inert, so TensorRT did 15x the intended work and
the camera pushed 18.4 MB/s of discarded frames across a USB 2.0 bus shared with the
WiFi dongle.

```
        BEFORE                                  AFTER
  camera 30 fps YUYV                      camera  5 fps YUYV   (VIDIOC_S_PARM)
        │  18.4 MB/s USB                        │  3.07 MB/s USB
        ▼                                       ▼
  yuy2 ──► rgb8   (v4l2_camera)           yuy2 passthrough     (no conversion)
        │                                       │
        ▼                                       ▼
  rgb8 ──► bgr8   (cv_bridge)             yuy2 ──► bgr8        (2/s only)
        │  30/s                                 │
        ▼                                       ▼
  TensorRT 30 Hz                          TensorRT 2 Hz        (frame skip)
```

## What was actually wrong

Four separate silent failures, none of which logged an error:

- **`time_per_frame` did not exist.** No release of `v4l2_camera` has it — not 0.6.2
  (humble), 0.7.1 (jazzy), 0.8.0 (rolling), nor upstream master. None of them calls
  `VIDIOC_S_PARM` at all. The parameter belongs to the
  [tier4 fork](https://github.com/tier4/ros2_v4l2_camera). rclcpp keeps unknown YAML
  keys as parameter overrides and discards them without warning.
- **`image_size: [640, 640]` is not an advertised mode.** V4L2 silently substituted
  640x480 while the node still logged `Success`.
- **`publish_image` is not a real parameter either.** Pure no-op.
- **Two colour conversions per frame.** The camera published `rgb8`, but the detector
  asks `cv_bridge` for `BGR8`, so every frame was converted twice.

Two hardware facts constrained the fix:

- **The camera's slowest advertised interval is 1/5 s.** `[1, 2]` was never reachable.
  Reaching 2 Hz requires dropping frames in software.
- **`VIDIOC_S_FMT` resets the frame interval.** Setting the rate from outside the node
  is undone the moment the node applies its format. Measured: set `1/5`, start the node
  with a differing `image_size`, and `G_PARM` reads back `1/30` at 28 Hz. It only
  appears to survive when the requested format already equals the current one, because
  `requestImageSize()` returns early and skips `S_FMT` — an accident that breaks on any
  fresh boot, replug or resolution change.

Two unrelated problems surfaced while measuring:

- **`/dev/videoN` is not stable.** The Brio was observed at video0, video1 and video2
  across replugs, while `camera_params.yaml` hardcoded `/dev/video0`.
- **`update_coordinator` burned a third of a CPU core at idle** — more than the entire
  detection pipeline. Its raw CAN socket had no filter, so it woke for all 333 frames/s
  on the bus (14 IDs), and **not one** of them was an ID it handles. Each frame cost a
  `select()` wake, a `recv`, an unpack, a queue put and a guard-condition trigger that
  woke the whole ROS executor, just to early-return.

## What changed

| Change | Where |
|---|---|
| Backport `time_per_frame` (`VIDIOC_S_PARM`) onto v4l2-camera 0.6.2 | `recipes-bbappends/v4l2-camera/` |
| `max_inference_rate` parameter + frame skip before `cv_bridge` | `ros2_yolos_cpp_trt`, `detector_node.cpp` |
| 5 fps, 640x480, `yuv422_yuy2` passthrough, stable device path | `ros2_ws_gp`, `camera_params.yaml` |
| `/dev/camera-front` udev rule (matches 046d:094c, index 0) | `recipes-support/camera-udev-rules/` |
| Boot clock policy — runs `jetson_clocks` | `recipes-support/gpu-clock-policy/` |
| `CAN_RAW_FILTER` on the OTA socket | `ros2_ws_gp`, `secoc_utils.py` |

Two design details worth remembering:

- **The frame skip runs before `cv_bridge::toCvShare`**, so dropped frames never pay
  for the colour conversion.
- **It advances a deadline rather than measuring from the last accepted frame.** A
  5 Hz source only divides into 5 / 2.5 / 1.67 Hz, so the naive approach yields
  1.67 Hz — 17% below target. The deadline approach holds a true 2 Hz average with
  gaps alternating 0.4 s / 0.6 s.

## Gains

Measured as an A/B on the same board at the same GPU clock, so the numbers are not
confounded by the clock change. 25 s samples, IVI running in both.

| | before | after | change |
|---|---|---|---|
| Detection rate | 29.96 Hz | 2.13 Hz | as designed |
| GPU load | 17.4% (peak 27.7%) | 5.6% (peak 6.9%) | **−68%** |
| `component_container` CPU | 9.3% | 0.7% | **−93%** |
| `update_coordinator` CPU | 33.6% | 0.00% | **eliminated** |
| Total CPU, all processes | 64.6% | 47.1% | −17.5 pts |
| Board power (VDD_IN) | 10.04 W | 8.77 W | −1.27 W |
| EMC utilisation | 7.0% | 3.0% | −57% |
| USB bandwidth | 18.4 MB/s | 3.07 MB/s | **−83%** |
| `/image_raw` ROS traffic | 27.6 MB/s | 3.07 MB/s | **−89%** |

The GPU figure understates the result: idle load with the pipeline stopped is also
**5.6%**, so the detection pipeline's GPU cost now sits below measurement noise.

The clock policy is a separate, deliberate trade in the other direction: pinning the
GPU at 1173 MHz instead of its parked 306 MHz costs about 2.8 W, so that inference
never starts from a cold clock. `nvhost_podgov` had kept the GPU at minimum for 99.9%
of uptime, and its 25 ms polling cannot react inside a single inference.

## Verifying after a rebuild

```bash
ros2 param dump /camera | grep time_per_frame
```

This is the acceptance test for the whole exercise. If the parameter is absent, the
backport did not take and the setting is silently inert again — the original bug.

```bash
ros2 topic hz /yolos_detector/detections
```

Expect ~2 Hz with `min` near 0.4 s and `max` near 0.6 s. Then confirm `/image_raw`
reports `640x480` / `yuv422_yuy2`, that the container log contains no
`possibly slow conversion` line, and that CAN id `0x215` still fires on a real sign.

## Notes

- `jetson_clocks` also disables all 12 CPU idle states. Measured cost on this board:
  **0.00 W** — c7 residency was already ~0%, and disabling cpuidle's WFI does not make
  cores spin, since the kernel's default ARM64 idle path still issues WFI.
- `GPU_CLOCK_MODE=targeted` in `/etc/default/gpu-clock-policy` switches to a GPU-only
  devfreq floor. It is the only mode that can select a frequency below maximum, which
  matters if `publish_timing` later shows a lower floor is sufficient.
- Moving the camera between ports on the hub cannot separate it from the WiFi dongle.
  The Brio reports `bcdUSB 2.00`, so it always enumerates on the 480 Mbps side, and all
  USB 2.0 devices share that root controller regardless of port.
