#!/bin/sh
# Clock policy for the sign-detection workload.
#
# Why this exists: the nvhost_podgov governor scales on measured load and parks the
# GPU at min_freq when load stays under its threshold. On this board that meant
# 306 MHz out of 1173 MHz for 99.9% of uptime (devfreq trans_stat: 1,955,344 ms at
# minimum against ~1.7 s across every other frequency combined, 19 transitions).
#
# That hurts most for short, infrequent inference. At 2 Hz the GPU is idle between
# frames, so every inference starts from the parked clock, and podgov's 25 ms polling
# cannot react inside a single inference.
#
# nvpmodel is NOT the limiter: mode 4 (40W) already allows the hardware maximum of
# 1173 MHz, and MAXN_SUPER (mode 0) sets GPU MAX_FREQ -1, resolving to the same
# ceiling. Changing power mode cannot raise the GPU clock.
#
# Two modes, selected by GPU_CLOCK_MODE in /etc/default/gpu-clock-policy:
#
#   targeted       GPU only: disable rail gating, set the devfreq floor to
#                  GPU_MIN_FREQ. Writes nothing to CPU frequency, CPU idle states
#                  or EMC. Same mechanism jetson_clocks uses for the GPU, but it
#                  stops there and it accepts an intermediate floor.
#
#   jetson_clocks  Run NVIDIA's tool, which maximises CPU, GPU and EMC together
#                  and disables every CPU idle state, so cores never sleep.
#                  Cannot express a floor below maximum.
#
#   off            Change nothing.
#
# Measured on this board, idle, 10 s averages (pipeline stopped):
#
#   policy                   GPU MHz  CPU MHz  EMC MHz  VDD_IN  tj     idle off
#   baseline (stock)             305      822     2133   6.73W  65.1C     0
#   targeted (GPU floor)        1165      904     3199   9.49W  65.9C     0
#   jetson_clocks (all max)     1170     1984     3199   9.82W  66.1C    12
#
# Three things worth knowing from that:
#
#  - The gap between the two policies is only 0.34 W at idle, and it comes from
#    pinning CPU cores to 1984 MHz, not from the idle states.
#
#  - Disabling every cpuidle state costs nothing measurable. Measured directly,
#    GPU held constant: 8.05 W with cpuidle enabled, 8.05 W with all 12 states
#    disabled. c7 (core powergate, the only real power-saving state) has 0.0-0.5%
#    residency since boot because this workload never idles the 30 ms it needs,
#    and disabling cpuidle's WFI does not make cores spin -- the kernel's default
#    ARM64 idle path still issues WFI, it is just no longer governor-managed.
#
#    Caveat for later: once the camera drops to 5 fps and inference to 2 Hz, CPU
#    load falls and cores may start reaching c7's 30 ms threshold. c7 exit latency
#    is 5000 us, which would then land on the wake path of a 2 Hz inference. Re-check
#    per-core c7 residency after the rebuild; if it climbs, disabling c7 becomes a
#    small win rather than a cost.
#
#  - "targeted" does not keep EMC low. It writes nothing to EMC, but EMC scales
#    to 3199 MHz on its own once the GPU clock rises, because GPU memory
#    bandwidth demand drives it. Most of the +2.76 W is that, not the GPU alone.
#
# Absolute wattages drift with IVI background activity between runs, so only trust
# comparisons measured within a single run, as all of the above were.

set -eu

# Fallback must match the shipped /etc/default/gpu-clock-policy. The unit loads that
# file with EnvironmentFile=- , which tolerates it being missing, so a mismatch here
# would silently boot a different policy instead of failing loudly.
MODE="${GPU_CLOCK_MODE:-jetson_clocks}"

log() { echo "gpu-clock-policy: $*"; }

apply_targeted()
{
    FLOOR="${GPU_MIN_FREQ:-}"
    if [ -z "$FLOOR" ]; then
        log "GPU_MIN_FREQ not set, leaving governor untouched"
        return 0
    fi

    # jetson_clocks does exactly two things to the iGPU: clears railgate_enable and
    # writes max_freq into min_freq. Rail gating powers the GPU rail down when idle,
    # adding wake-up latency to the first frame after a gap -- precisely the case we
    # are optimising for at 2 Hz. It already reads 0 here; this keeps it true.
    RAILGATE=/sys/devices/platform/gpu.0/railgate_enable
    if [ -w "$RAILGATE" ]; then
        echo 0 > "$RAILGATE" || log "WARNING: could not disable rail gating"
    fi

    found=0
    for d in /sys/class/devfreq/*.gpu; do
        [ -e "$d/min_freq" ] || continue
        found=1

        max=$(cat "$d/max_freq")
        target="$FLOOR"

        # Never ask for more than the ceiling the current power mode allows.
        if [ "$target" -gt "$max" ]; then
            log "clamping requested $target to max_freq $max"
            target="$max"
        fi

        if ! echo "$target" > "$d/min_freq"; then
            log "ERROR: failed writing $target to $d/min_freq"
            return 1
        fi

        log "$(basename "$d") min_freq=$(cat "$d/min_freq") max_freq=$max governor=$(cat "$d/governor")"
    done

    if [ "$found" -eq 0 ]; then
        log "ERROR: no GPU devfreq node under /sys/class/devfreq"
        return 1
    fi
}

apply_jetson_clocks()
{
    if ! command -v jetson_clocks >/dev/null 2>&1; then
        log "ERROR: jetson_clocks not installed (add tegra-tools-jetson-clocks to the image)"
        return 1
    fi

    log "running jetson_clocks (maximises CPU, GPU and EMC; disables CPU idle states)"
    jetson_clocks
    jetson_clocks --show || true
}

case "$MODE" in
    targeted)      apply_targeted ;;
    jetson_clocks) apply_jetson_clocks ;;
    off)           log "GPU_CLOCK_MODE=off, changing nothing" ;;
    *)             log "ERROR: unknown GPU_CLOCK_MODE '$MODE' (targeted|jetson_clocks|off)"; exit 1 ;;
esac
