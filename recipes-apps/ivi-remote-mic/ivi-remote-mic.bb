SUMMARY = "Virtual microphone for the IVI head unit, fed from a remote machine over RTP"
DESCRIPTION = "This board has no audio capture hardware at all -- /proc/asound/pcm lists \
no capture device, /dev/snd holds only HDMI playback PCMs, and there is no snd-usb-audio \
module -- so the IVI app's Vosk speech recognition has nothing to listen to. This creates \
a PulseAudio null sink whose monitor becomes the default capture source, and decodes an \
Opus/RTP stream into it, so a laptop's microphone becomes the head unit's microphone. \
\
Opus over UDP rather than raw audio over an SSH pipe because the link is usually WiFi: \
TCP cannot discard stale audio, so packet loss turns into unbounded, permanent latency \
drift, while RTP with a jitter buffer drops late packets and stays current."
LICENSE = "CLOSED"

PV = "1.0"

SRC_URI = "\
    file://ivi-remote-mic \
    file://ivi-remote-mic.service \
    file://ivi-remote-mic-send \
"

# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
# The ?= keeps the newer definition and supplies the old default otherwise.
UNPACKDIR ?= "${WORKDIR}"
S = "${UNPACKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "ivi-remote-mic.service"
SYSTEMD_AUTO_ENABLE = "enable"

# Depend on the individual PLUGIN packages, not the gstreamer1.0-plugins-{base,good}
# umbrellas. Those umbrellas only pull their plugins in with RRECOMMENDS, so depending
# on them is a soft dependency: it happens to work on a normal image (recommends are
# installed by default) and silently loses elements under NO_RECOMMENDATIONS or a
# trimmed build. The failure mode is a restart-looping service complaining "no element
# opusdec", which is a long way from "your image dropped a recommendation".
#
# pulseaudio-server        -> pactl specifically. pulseaudio.inc lists ${bindir}/pactl
#                             under -server, not -misc, which is easy to get backwards.
# gstreamer1.0             -> gst-launch-1.0
# ...-base-opus            -> opusdec        <- see the note below
# ...-base-audioconvert    -> audioconvert
# ...-base-audioresample   -> audioresample
# ...-good-udp             -> udpsrc
# ...-good-rtp             -> rtpopusdepay
# ...-good-rtpmanager      -> rtpjitterbuffer
# ...-good-pulseaudio      -> pulsesink
#
# NOTE: gstreamer1.0-plugins-base-opus only EXISTS if gst-plugins-base was built with
# the 'opus' PACKAGECONFIG, which is not in poky's default set -- conf/distro/vpace.conf
# turns it on. If that line is ever dropped this recipe stops building rather than
# producing an image that fails at runtime, which is the point of naming it here.
# pulseaudio-module-remap-source is NOT optional. Without it the virtual mic is only a
# null-sink monitor, which PulseAudio and GStreamer report as device.class="monitor" --
# and Qt filters monitor-class devices out of QMediaDevices::audioInputs(), so the IVI
# app reports "No audio device detected" and never opens a stream. The remap source
# reports device.class="filter" instead, which Qt accepts. See the note in
# files/ivi-remote-mic.
#
# Poky splits all 68 PulseAudio modules into their own packages (do_split_packages in
# pulseaudio.inc), and only a handful land in a default image -- this one has to be
# asked for by name. No PulseAudio rebuild is involved.
RDEPENDS:${PN} = "\
    pulseaudio-server \
    pulseaudio-module-remap-source \
    gstreamer1.0 \
    gstreamer1.0-plugins-base-opus \
    gstreamer1.0-plugins-base-audioconvert \
    gstreamer1.0-plugins-base-audioresample \
    gstreamer1.0-plugins-good-udp \
    gstreamer1.0-plugins-good-rtp \
    gstreamer1.0-plugins-good-rtpmanager \
    gstreamer1.0-plugins-good-pulseaudio \
"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${S}/ivi-remote-mic ${D}${bindir}/ivi-remote-mic

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/ivi-remote-mic.service \
        ${D}${systemd_system_unitdir}/ivi-remote-mic.service

    # The sender runs on the DEVELOPER'S machine, not here. It is shipped so it travels
    # with the image and can be copied off with scp; deliberately not in ${bindir},
    # because running it on the Jetson would only stream the Jetson's own (nonexistent)
    # microphone back to itself.
    install -d ${D}${datadir}/ivi-remote-mic
    install -m 0755 ${S}/ivi-remote-mic-send \
        ${D}${datadir}/ivi-remote-mic/ivi-remote-mic-send
}

FILES:${PN} += "\
    ${systemd_system_unitdir}/ivi-remote-mic.service \
    ${datadir}/ivi-remote-mic \
"
