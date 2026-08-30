# The OTA version. ONE number for the whole vehicle: ivi_ota_agent.sh tracks a single
# installed-version counter shared by both .swu payloads, so bump this and rebuild both.
#
# Also generated into agent.conf as INITIAL_VERSION (the floor reported when no version
# file exists yet) -- must match what's baked into the image or the downgrade gate misfires.

VPACE_OTA_VERSION ?= "1.1.3"
