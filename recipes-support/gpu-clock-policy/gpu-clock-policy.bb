SUMMARY = "Apply a clock policy at boot so inference does not start from a parked GPU clock"
DESCRIPTION = "The nvhost_podgov governor parked the GPU at 306 MHz of 1173 MHz for \
99.9% of uptime, because measured load never crossed its threshold. Short, infrequent \
inference pays that penalty on every frame. Runs NVIDIA's jetson_clocks by default; \
GPU_CLOCK_MODE=targeted in /etc/default/gpu-clock-policy switches to a GPU-only \
devfreq floor, which is the only mode that can select a frequency below maximum."
LICENSE = "CLOSED"

SRC_URI = " \
    file://gpu-clock-policy.sh \
    file://gpu-clock-policy.service \
    file://gpu-clock-policy.default \
"

S = "${WORKDIR}"

inherit systemd

RDEPENDS:${PN} += "systemd"

# Hard dependency: GPU_CLOCK_MODE defaults to jetson_clocks, so the tool must be
# present or the unit fails at boot. Also gives `jetson_clocks --show` / `--store`
# for tuning. (jetson_clocks is a bash script; tegra-tools pulls bash in itself.)
RDEPENDS:${PN} += "tegra-tools-jetson-clocks"

SYSTEMD_SERVICE:${PN} = "gpu-clock-policy.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${WORKDIR}/gpu-clock-policy.sh ${D}${bindir}/gpu-clock-policy.sh

    install -d ${D}${systemd_unitdir}/system
    install -m 0644 ${WORKDIR}/gpu-clock-policy.service ${D}${systemd_unitdir}/system/

    install -d ${D}${sysconfdir}/default
    install -m 0644 ${WORKDIR}/gpu-clock-policy.default ${D}${sysconfdir}/default/gpu-clock-policy
}

FILES:${PN} += " \
    ${bindir}/gpu-clock-policy.sh \
    ${systemd_unitdir}/system/gpu-clock-policy.service \
    ${sysconfdir}/default/gpu-clock-policy \
"
