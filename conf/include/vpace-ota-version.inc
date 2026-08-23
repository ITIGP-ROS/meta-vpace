# The OTA version. ONE number for the whole vehicle.
#
# ============================================================================
# WHY THERE IS ONLY ONE
#
# ivi_ota_agent.sh keeps a SINGLE installed-version record at
# /data/ota/installed_version, and every campaign is compared against it --
# regardless of which payload the campaign carries. There is no per-target
# counter. So installing IVI app 1.0.7 immediately makes ackermann 1.0.6
# uninstallable: the shared counter has already passed it and ALLOW_DOWNGRADE=0
# refuses anything that is not strictly greater.
#
# The two payload recipes therefore cannot be versioned independently in any
# meaningful sense -- they were only ever kept equal by hand. This file makes
# that the mechanism instead of a convention.
#
# BUMP THIS, REBUILD BOTH .swu PAYLOADS, PUBLISH WHICHEVER ONE YOU ACTUALLY
# WANT TO SHIP. There is no requirement to publish both.
#
# ============================================================================
# THIS IS ALSO THE AGENT'S INITIAL_VERSION FLOOR
#
# ivi-ota-agent_1.0.bb generates INITIAL_VERSION= into the installed
# /etc/ivi-ota/agent.conf from this variable, overriding whatever the
# out-of-tree secret/agent.conf says.
#
# INITIAL_VERSION is what the agent reports when it has no version file to read
# -- a board flashed with NVIDIA's flash.sh (which can take /data's nvme0n1p15
# with it), a board whose /data failed to mount, or a board that has never taken
# an OTA. It has to name the version actually baked into the image, because:
#
#   - too LOW (the old hardcoded 0.0.0) and every archived campaign looks like an
#     upgrade. The downgrade gate is the only thing stopping a replayed old-but-
#     validly-signed package -- swupdate's signature check passes those happily.
#   - too HIGH and a freshly flashed board refuses the very campaign it should
#     take.
#
# Generating it is the point: hand-maintaining the floor in a gitignored file
# means it drifts silently the first time someone bumps a payload version and
# forgets, and the failure is invisible until a flashed board misbehaves.
# ============================================================================

VPACE_OTA_VERSION ?= "1.1.2"
