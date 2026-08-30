SUMMARY = "Virtual microphone for the IVI head unit, fed from a remote machine over RTP"
DESCRIPTION = "This board has no audio capture hardware, so Vosk has nothing to listen \
to. Creates a PulseAudio null sink and decodes an Opus/RTP stream into it, so a \
laptop's mic becomes the head unit's mic. Opus/UDP rather than an SSH pipe because \
TCP can't discard stale audio and RTP's jitter buffer can."
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

# Individual plugin packages, not the gstreamer1.0-plugins-{base,good} umbrellas --
# those only pull plugins in via RRECOMMENDS, a soft dependency that silently drops
# under NO_RECOMMENDATIONS. gstreamer1.0-plugins-base-opus needs the 'opus'
# PACKAGECONFIG, turned on in conf/distro/vpace.conf. pulseaudio-module-remap-source
# is required, not optional: without it Qt filters out the monitor-class device and
# the app reports "No audio device detected" -- see files/ivi-remote-mic.
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

    # Runs on the developer's machine, not here -- shipped so it travels with the
    # image and can be scp'd off. Not in ${bindir}: running it on the Jetson would
    # just stream its own nonexistent mic back to itself.
    install -d ${D}${datadir}/ivi-remote-mic
    install -m 0755 ${S}/ivi-remote-mic-send \
        ${D}${datadir}/ivi-remote-mic/ivi-remote-mic-send
}

FILES:${PN} += "\
    ${systemd_system_unitdir}/ivi-remote-mic.service \
    ${datadir}/ivi-remote-mic \
"
