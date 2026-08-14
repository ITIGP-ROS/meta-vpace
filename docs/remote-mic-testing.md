# Testing the IVI remote microphone

Streams a laptop's microphone to the head unit so the Vosk speech recognition has
something to listen to.

```
laptop mic ──► opusenc ──► RTP/UDP :5004 ──► udpsrc ──► opusdec ──► pulsesink
                                                                        │
                                                                        ▼
                                                              null sink  ivi_mic
                                                                        │
                                                               ivi_mic.monitor
                                                                        │  module-remap-source
                                                                        ▼
                                                              ivi_mic_in  ◄── the app records here
```

---

## ⚠ Current state: the last hop is hand-installed

`module-remap-source` is **not yet in the image**. It was copied onto the board by hand
to prove the fix, and **it disappears on reboot.**

Without it the app finds no microphone at all — see [Why the remap source is
needed](#why-the-remap-source-is-needed).

Until the recipe change is built and flashed, re-do this after every reboot:

```sh
# on the laptop — push the module onto the board
scp module-remap-source.so root@10.42.0.50:/usr/lib/pulseaudio/modules/
```

```sh
# on the board — layer a real source over the monitor and make it the default
XDG_RUNTIME_DIR=/run/user/1000 su weston -c "pactl load-module module-remap-source master=ivi_mic.monitor source_name=ivi_mic_in source_properties=device.description=IVI_Remote_Mic_Input"
XDG_RUNTIME_DIR=/run/user/1000 su weston -c "pactl set-default-source ivi_mic_in"
```

The `.so` is extracted from `pulseaudio-module-remap-source-17.0-r0.armv8a.rpm` in
`build-orin/tmp/deploy/rpm/armv8a/` (zstd payload — `zstd -dc | cpio -idm`).

---

## Test procedure

### 1. Board is healthy

```sh
ssh root@10.42.0.50 'systemctl is-active ivi-remote-mic weston ivi-app; gst-inspect-1.0 opusdec >/dev/null && echo opusdec:OK'
```

Want `active` ×3 and `opusdec:OK`.

### 2. The virtual mic exists and is the default input

```sh
ssh root@10.42.0.50 'XDG_RUNTIME_DIR=/run/user/1000 su weston -c "pactl list short sources; pactl info | grep -i \"default source\""'
```

Want `ivi_mic_in` present and `Default Source: ivi_mic_in`.

### 3. Qt can actually *see* an input

This is the check that catches the failure we hit — PulseAudio having a source is not
enough, it has to be one Qt will accept.

```sh
ssh root@10.42.0.50 'XDG_RUNTIME_DIR=/run/user/1000 su weston -c "gst-device-monitor-1.0 Audio/Source"'
```

Want at least one device whose `device.class` is **not** `monitor`. `filter` is what
`module-remap-source` produces and is fine.

### 4. Laptop mic is live and loud enough

```sh
pactl list sources | grep -A8 "Name: alsa_input" | grep -iE "Mute:|Volume:"
```

If it's muted or very quiet, Vosk will fail even with a perfect link:

```sh
pactl set-source-volume alsa_input.pci-0000_00_1f.3.analog-stereo 70%
```

### 5. Start streaming

Leave this running in its own terminal:

```sh
~/ivi-remote-mic-send 10.42.0.50
```

### 6. Confirm audio is arriving

Sink state should flip to `RUNNING`, and a capture off the source should be mostly
non-zero:

```sh
ssh root@10.42.0.50 'XDG_RUNTIME_DIR=/run/user/1000 su weston -c "pactl list short sinks" | grep ivi_mic'
```

```sh
ssh root@10.42.0.50 'XDG_RUNTIME_DIR=/run/user/1000 su weston -c "parec -d ivi_mic_in --raw --format=s16le --rate=16000 --channels=1" 2>/dev/null | dd bs=3200 count=10 of=/tmp/c.raw 2>/dev/null; echo "non-zero: $(tr -d "\000" < /tmp/c.raw | wc -c) / $(wc -c < /tmp/c.raw)"; rm -f /tmp/c.raw'
```

A few hundred non-zero bytes is silence/noise; ~25000/26000 is real audio.

### 7. Watch the app, then speak

```sh
ssh root@10.42.0.50 'journalctl -u ivi-app -f'
```

Connect Remmina to `10.42.0.50`, **press and hold the mic button**, and say one word from
the grammar. Anything outside this list returns `[unk]` however good the audio is:

> weather · cairo · giza · milan · media · settings · wifi · bluetooth · open · back ·
> home · play · pause · stop · radio · audio · video · volume · up · down · mute · about

**Good:** `MIC PRESSED`, then a recognition result.
**Bad:** `No audio device detected` → go to step 3.

### 8. Confirm the app is on the right source

While the mic is held:

```sh
ssh root@10.42.0.50 'XDG_RUNTIME_DIR=/run/user/1000 su weston -c "pactl list source-outputs"'
```

Want `Source: ivi_mic_in`. Empty means the app never opened a stream.

---

## Troubleshooting by link

| Symptom | Where it broke | Check |
|---|---|---|
| `No audio device detected` in the app log | Qt sees no acceptable input | Step 3 — is there a non-`monitor` device? Is `module-remap-source` loaded? |
| Sink stays `SUSPENDED` | Nothing arriving on 5004 | Sender running? Right IP — `10.42.0.50` on ethernet, WiFi address differs |
| Sink `RUNNING` but capture is all zeros | Laptop mic muted/silent | Step 4 |
| `no element opusdec` in the service log | `opus` PACKAGECONFIG missing | `PACKAGECONFIG:append:pn-gstreamer1.0-plugins-base = " opus"` in `conf/distro/vpace.conf` |
| Service restart-looping | PulseAudio not reachable | Runs as `weston` with `XDG_RUNTIME_DIR=/run/user/1000`; needs the weston session up |
| Recognition always `[unk]` | Audio fine, word not in grammar | Use a word from the list above |

Handy: BusyBox here has no `timeout`, and `od` lacks `-A`. Use `dd bs=… count=…` to bound
a capture and `tr -d '\000' | wc -c` to count non-zero bytes.

Weston logs under `journalctl -t weston`, **not** `-u weston` — `PAMName=weston-autologin`
puts it in a session scope.

---

## Why the remap source is needed

A null sink gives you `ivi_mic.monitor`, which PulseAudio and GStreamer both report as
`device.class = monitor`. **Qt filters monitor-class devices out of `audioInputs()`**, so
the app's own device check found an empty list and printed `No audio device detected`
without ever attempting to open anything. GStreamer saw it fine — the filtering is above
GStreamer, in Qt.

`module-remap-source` layers an ordinary source on top of that monitor. It reports
`device.class = filter`, which is not excluded, so the app can see and open it.

---

## Outstanding recipe change

The fix is **not** a PulseAudio rebuild — poky already splits all 68 modules into
packages (`do_split_packages` in `pulseaudio.inc`), and
`pulseaudio-module-remap-source` was simply never installed. Two changes:

1. `recipes-core/images/orinivi-image.bb` — add `pulseaudio-module-remap-source`
   (or add it to `RDEPENDS` in `ivi-remote-mic.bb`, which is tidier since only this
   package needs it).
2. `files/ivi-remote-mic` — after creating the null sink, also load the remap source and
   make **that** the default, instead of pointing the default at the monitor.

Once those land, everything above works from a cold boot with no manual steps.
