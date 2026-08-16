#!/bin/sh
# ivi_ota_agent.sh — MQTT-driven OTA agent for the IVI target.
#
# Subscribes to an Adafruit IO feed, validates the campaign, downloads the
# encrypted .swu over TLS, hybrid-decrypts it, and hands it to swupdate.
#
# ============================================================================
# THE CENTRAL RULE: THE MQTT MESSAGE IS UNTRUSTED INPUT.
#
# It is a HINT (what version, where to get it, which session key) -- never an
# AUTHORITY (whether to install it). Anyone who can publish to the feed can put
# anything in it.
#
# NOTE CAREFULLY WHAT THE ENCRYPTION DOES AND DOES NOT DO. The AES key is wrapped
# with our RSA *public* key, and a public key is public: an attacker can generate
# their own session key, encrypt their own .swu with it, wrap it for us, and
# publish that. Decrypting successfully proves NOTHING about origin. It buys
# confidentiality (nobody reads the image off the wire or out of the bucket) and
# nothing else.
#
# AUTHENTICITY IS SWUPDATE'S JOB. The package carries sw-description.sig, and
# swupdate verifies it against the public key provisioned on this device before
# installing anything. That verification is the trust anchor and it MUST be
# enabled (CONFIG_SIGNED_IMAGES). Without it, this agent will happily install a
# forged image -- every check below is only about not wasting time on garbage.
#
# THE ONE HOLE THE SIGNATURE DOES NOT CLOSE: a replayed OLD but legitimately
# signed package verifies perfectly. ALLOW_DOWNGRADE=0 is the gate that closes
# it, which is why the version compare below is not cosmetic.
# ============================================================================
#
# Campaign format (Adafruit IO feed value, <=1024 bytes):
#     IVI1|<version>|<url>|<wrapped_key>[|<size>|<sha256>]
#            |         |        |           |       `- sha256 of the DECRYPTED .swu
#            |         |        |           `--------- byte size of the DECRYPTED .swu
#            |         |        `--------------------- base64(RSA(KEY_HEX:IV_HEX))
#            |         `------------------------------ https://<allowlisted prefix>/...swu.enc
#            `---------------------------------------- semver
# Pipe-delimited: neither a url nor base64 can contain '|', so the split is
# unambiguous. Fields 5 and 6 are OPTIONAL -- the dashboard computes both but
# does not publish them yet; if present they are checked, if absent the integrity
# guarantee falls back entirely to swupdate's own checksums and signature.
#
# Also answers, on the same feed:
#     P     -> R1|status|pong ...     (dashboard's Ping button)
#     Q1?   -> R1|version|X.Y.Z       (dashboard's Get Application Version)
#
# LOGGING: stdout -> journald.
#     journalctl -u ivi-ota-agent -f
#
# REQUIRES: mosquitto_sub, mosquitto_pub, curl, openssl, swupdate, sha256sum
set -e

# Overridable so the agent can be exercised off-board without root. Production
# always uses the defaults; deploy_ivi_agent.sh installs to exactly these paths.
CONF="${IVI_OTA_CONF:-/etc/ivi-ota/agent.conf}"
PRIV_KEY="${IVI_OTA_PRIV_KEY:-/etc/ivi-ota/ivi_priv.pem}"

log()  { echo "[ivi-ota] $*"; }
warn() { echo "[ivi-ota] WARNING: $*" >&2; }

_ros2_call() {
    [ -f /opt/ros/humble/setup.bash ] && . /opt/ros/humble/setup.bash
    command -v ros2 >/dev/null 2>&1 || return 0
    _err=$(ros2 service call "$@" 2>&1 >/dev/null)
    _rc=$?
    [ "$_rc" -eq 0 ] || {
        _err=$(printf '%s\n' "$_err" | tail -n 1)
        warn "ros2 service call $1 failed: $_err"
        return 1
    }
    return 0
}

# --- vehicle lock ------------------------------------------------------------
# Registering this update with update_coordinator is what stops the car: the
# coordinator puts "jetson" in its active set, which drives /emergency_stop/lock
# and makes twist_mux drop every cmd_vel until we report done.
#
# Silently a no-op when update-coordinator is not installed — `command -v ros2`
# fails inside _ros2_call and it returns success. That is why a stock image
# updates without ever stopping: nothing is listening, not because the agent
# chose not to ask.
lock_system() {
    log "registering update with the coordinator — the vehicle will hold"
    _ros2_call /update_coordinator/self_start std_srvs/srv/Trigger "{}"
}

# MUST run on every path out of run_update(), successful or not. The coordinator
# does have a timeout that force-releases a stuck update, but it is set to 1200 s
# in update-coordinator.service (-p timeout_sec:=1200.0), so relying on it means
# TWENTY MINUTES of an immobilised car after a download that failed in two
# seconds.
unlock_system() {
    _ros2_call /update_coordinator/self_done std_srvs/srv/Trigger "{}"
    log "update finished — vehicle released"
}

[ -r "$CONF" ] || {
    echo "[ivi-ota] ERROR: no $CONF — run deploy_ivi_agent.sh from the PC." >&2
    exit 1
}
# shellcheck disable=SC1090
. "$CONF"

: "${AIO_HOST:=io.adafruit.com}"
: "${AIO_PORT:=8883}"
: "${AIO_FEED:=ivi-ota}"
# Replies go to a SEPARATE feed. An Adafruit IO feed holds ONE value, so replying
# on AIO_FEED would overwrite the campaign -- and the /get on the next boot would
# read back our own status line instead of the update to install.
: "${AIO_STATUS_FEED:=ivi-status}"
: "${TARGET_TAG:=IVI1}"
: "${ALLOW_DOWNGRADE:=0}"
: "${MIN_RETRY_SECS:=60}"
# NOT /tmp. /tmp is tmpfs on most Yocto images, and this holds the encrypted blob
# AND the decrypted .swu at the same time -- ~110 MB of RAM for a 53 MiB update,
# on a board that also has to run the IVI app. Override in agent.conf if your /tmp
# is real disk and you would rather keep it there.
: "${WORKDIR:=/var/lib/ivi-ota}"
# Ceiling on what we will pull down, so a forged url cannot fill the disk. Raise
# it in agent.conf when the image outgrows it.
: "${MAX_BYTES:=268435456}"
# Extra args for swupdate. If your build uses CONFIG_SIGNED_IMAGES with an
# explicit key path, put it here: SWUPDATE_ARGS="-v -k /etc/swupdate/public.pem"
: "${SWUPDATE_ARGS:=-v}"
: "${REBOOT_AFTER_UPDATE:=0}"
# How long a single status publish may block before we abandon it. See report().
# Adafruit IO normally round-trips in under two seconds, so this is not a latency
# budget -- it is a bound on a broker that intermittently accepts the TCP
# connection and then stalls, which libmosquitto would otherwise sit on for ~60 s.
: "${REPORT_TIMEOUT_S:=15}"

# ---- driver approval on the head unit ---------------------------------------
# The IVI app shows a prompt and writes back a verdict. See
# IVI/OTA_APPROVAL_PROTOCOL.md for the file layout and the reasoning.
: "${REQUIRE_APPROVAL:=1}"
: "${APPROVAL_DIR:=/run/ota-approval}"
# How long to wait for a human. Longer than the app's own 5 s auto-accept, so a
# running head unit always answers well inside this; the timeout only matters if
# the app dies mid-prompt.
: "${APPROVAL_TIMEOUT_S:=120}"
# What to do when NO head unit can answer — the app is not running, or its
# liveness file is stale. `approve` keeps the old fully-automatic behaviour so a
# broken screen cannot block updates forever; `deny` makes the gate absolute at
# the cost of that availability. See the note in the protocol doc: with
# `approve`, anyone able to stop the app gets automatic updates back.
: "${ON_NO_UI:=approve}"
# Older than this and the UI is considered gone. The app rewrites its liveness
# file once a second, so 10 s is ten missed beats.
: "${UI_ALIVE_MAX_AGE_S:=10}"

for v in AIO_USER AIO_KEY OTA_URL_PREFIX; do
    eval "val=\$$v"
    [ -n "$val" ] || { echo "[ivi-ota] ERROR: $v unset in $CONF" >&2; exit 1; }
done
case "$OTA_URL_PREFIX" in
    https://*) ;;
    *) echo "[ivi-ota] ERROR: OTA_URL_PREFIX must be https:// — it is the allowlist" >&2; exit 1 ;;
esac
[ -r "$PRIV_KEY" ] || {
    echo "[ivi-ota] ERROR: no readable private key at $PRIV_KEY." >&2
    echo "[ivi-ota] Without it the session key cannot be unwrapped and no update can be applied." >&2
    exit 1
}

TOPIC="$AIO_USER/feeds/$AIO_FEED"
STATUS_TOPIC="$AIO_USER/feeds/$AIO_STATUS_FEED"
CLIENT_ID="ivi-$(cut -c1-8 /etc/machine-id 2>/dev/null || echo unknown)"
LAST_TRY_FILE="$WORKDIR/.last_try"
# What we believe is installed. swupdate does not hand back a queryable version,
# and this image exposes none, so the agent owns this file: written only after a
# successful install, seeded from INITIAL_VERSION on first boot.
#
# ============================================================================
# THIS LIVES ON /data, AND DELIBERATELY *NOT* UNDER $WORKDIR.
#
# WORKDIR is /var/lib/ivi-ota, which is on the A/B rootfs pair nvme0n1p1/p2.
# Payload updates (the IVI app, the ackermann stack) write into the RUNNING
# rootfs via their lua scripts, so those preserve it -- but a full rootfs .swu
# swaps the partition wholesale and the file is gone. /data is nvme0n1p15 and
# survives. Same reasoning as the SecOC counters and the WiFi profiles that
# already live there; see mount-data-partition.sh.
#
# The decoupling from $WORKDIR is load-bearing, not tidiness: agent.conf is
# sourced ABOVE this block and pins WORKDIR=/var/lib/ivi-ota, so deriving the
# path from it would put the file straight back on the rootfs. An explicit
# VERSION_FILE= in agent.conf still overrides this, which is the escape hatch.
#
# WHAT LOSING IT COSTS -- it is not a cosmetic counter:
#   - The downgrade gate is DEFEATED. Read the header: a replayed old-but-signed
#     package passes swupdate's signature check, and this version compare is the
#     only thing that stops it. A board reporting INITIAL_VERSION accepts every
#     archived campaign above that floor.
#   - The next boot REINSTALLS. Adafruit's /get replays the last campaign, the
#     idempotence check below no longer matches, and since approval triggers
#     lock_system that holds the vehicle for the whole install.
#   - Both delivery paths desync at once. This is ONE counter shared by
#     ivi-update and ackermann-update (see ackermann-update.bb) -- not one per
#     target.
#
# If your build maintains /etc/sw-versions, that is the more canonical source and
# this can be pointed at it -- but it must agree with the `version` field in the
# package's sw-description or the downgrade gate compares the wrong things, and
# /etc is on the rootfs, so it would reintroduce exactly the problem above.
# ============================================================================
VERSION_FILE="${VERSION_FILE:-/data/ota/installed_version}"

mkdir -p "$WORKDIR"
chmod 700 "$WORKDIR"

# --- the version file's home ------------------------------------------------
# mount-data-partition.sh creates /data/ota at boot, but only when /data really
# mounted. Recreate it here rather than assuming, and NON-FATALLY: `set -e` is on
# and this must not be able to stop the agent from starting.
_vdir=$(dirname "$VERSION_FILE")
mkdir -p "$_vdir" 2>/dev/null || :

# FAIL OPEN, LOUDLY. A missing /data degrades to a non-persistent version file
# rather than refusing to run -- the opposite choice to update-coordinator.service,
# which fails closed on the same partition. The trade is deliberate: an OTA agent
# that will not start also stops answering the dashboard's ping and version
# queries, so the board looks dead rather than degraded. The INITIAL_VERSION floor
# in agent.conf is what keeps this state safe -- it must name the version actually
# baked into the image, NOT 0.0.0, or the downgrade gate is wide open here.
#
# Only warn when the file is ACTUALLY meant to be on /data (an operator who
# overrode VERSION_FILE elsewhere does not need to hear about the partition), and
# only when mountpoint exists -- a missing binary must not produce a scary line
# every boot about a partition that is fine.
case "$VERSION_FILE" in
  /data/*)
    if command -v mountpoint >/dev/null 2>&1 && ! mountpoint -q /data; then
        warn "/data is NOT mounted -- $VERSION_FILE is on the rootfs and will be LOST
     on the next full update. The board will then report INITIAL_VERSION and
     reinstall whatever campaign is on the feed. Check: findmnt /data"
    fi
    ;;
esac

# One-time migration off the old rootfs path.
#
# Only fires on an IN-PLACE agent update, where the rootfs that holds the legacy
# file is still the running one. After an A/B swap /var/lib is empty and there is
# nothing to find -- that case is covered by INITIAL_VERSION, not by this.
if [ ! -e "$VERSION_FILE" ] && [ -r "$WORKDIR/installed_version" ]; then
    if cp "$WORKDIR/installed_version" "$VERSION_FILE" 2>/dev/null; then
        log "migrated installed version $(cat "$VERSION_FILE") from $WORKDIR to $VERSION_FILE"
    else
        warn "could not migrate $WORKDIR/installed_version to $VERSION_FILE"
    fi
fi

# Sleep in one-second slices so that when report() kills this watchdog, the most
# it can orphan is a single `sleep 1` rather than the whole remaining window.
_report_watchdog() {   # <pid-to-kill> <seconds>
    _n=0
    while [ "$_n" -lt "$2" ]; do
        sleep 1
        _n=$(( _n + 1 ))
    done
    kill -9 "$1" 2>/dev/null || :
}

# Publish a line to the dashboard's status feed. Best-effort: a failed report must
# never abort an update -- and, just as importantly, must never DELAY one.
#
# Every publish is a fresh TCP+TLS connection, and that path to the broker is
# intermittently broken in a specific way: the connection is accepted and then
# stalls, while the long-lived mosquitto_sub above and plain https downloads keep
# working. libmosquitto's own connect timeout is ~60 s, so an unbounded publish
# parks the agent for a minute -- and since two of these sit between swupdate
# returning and unlock_system, a cosmetic dashboard line was holding the VEHICLE
# for ~2 minutes after the install had already finished.
#
# So the call is bounded here. Notes on the shape of it:
#   - There is no timeout(1) on this image (BusyBox is built without the applet)
#     and mosquitto_pub has no timeout option, so the bound has to be a watchdog
#     that SIGKILLs the publish by pid.
#   - `if wait`, not bare `wait`: `set -e` is on and a killed child exits non-zero.
#   - Do NOT be tempted to replace the watchdog with a `kill -0` poll here. A
#     finished child stays a zombie until the shell reaps it, and `kill -0`
#     succeeds on a zombie, so such a loop would spin for the full window on a
#     publish that had already SUCCEEDED.
#
# Worst case this still adds REPORT_TIMEOUT_S to each of the two post-install
# reports before the vehicle is released. Making those asynchronous would take it
# to zero, but the reboot path publishes and then immediately reboots, so a
# backgrounded report there is a report that never lands.
report() {
    mosquitto_pub -h "$AIO_HOST" -p "$AIO_PORT" --capath /etc/ssl/certs \
                  -u "$AIO_USER" -P "$AIO_KEY" \
                  -t "$STATUS_TOPIC" -m "$1" >/dev/null 2>&1 &
    _pub=$!

    _report_watchdog "$_pub" "$REPORT_TIMEOUT_S" &
    _killer=$!

    # _pub_rc, not _rc: sh has no locals and _ros2_call already owns _rc. Nothing
    # calls one from inside the other today, so this is not a live bug -- it is a
    # name kept distinct so it never becomes one.
    if wait "$_pub" 2>/dev/null; then _pub_rc=0; else _pub_rc=$?; fi

    kill "$_killer" 2>/dev/null || :
    wait "$_killer" 2>/dev/null || :

    # 137 is 128+SIGKILL, i.e. the watchdog fired. Worth distinguishing in the
    # journal: a timeout means the broker stalled, anything else means it
    # answered and refused us (bad key, bad topic) -- different problems.
    case "$_pub_rc" in
        0)   ;;
        137) warn "status publish timed out after ${REPORT_TIMEOUT_S}s — broker stalled: $1" ;;
        *)   warn "could not publish status (mosquitto_pub exit $_pub_rc): $1" ;;
    esac
}

installed_version() {
    if [ -r "$VERSION_FILE" ]; then
        _iv=$(cat "$VERSION_FILE" 2>/dev/null) || _iv=""
        # Validate rather than trust. An empty or malformed file is NOT the same
        # as a missing one: cut would hand "" to ver_gt, ${_x:-0} would turn that
        # into 0, and the downgrade gate would be off with nothing in the log to
        # say so. A truncated write is now impossible (see the mv below), but a
        # hand-edit typo is not -- and this file is meant to be hand-editable.
        if echo "$_iv" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
            echo "$_iv"
            return 0
        fi
        warn "$VERSION_FILE does not contain a version ('$_iv') -- falling back to
     ${INITIAL_VERSION:-0.0.0}. Fix it by hand: echo 1.0.6 > $VERSION_FILE"
    fi
    echo "${INITIAL_VERSION:-0.0.0}"
}

# --- driver approval ---------------------------------------------------------
# Is a head unit there to answer? It rewrites APPROVAL_DIR/ui-alive every second.
ui_is_alive() {
    _alive="$APPROVAL_DIR/ui-alive"
    [ -f "$_alive" ] || return 1
    _mtime=$(stat -c %Y "$_alive" 2>/dev/null) || return 1
    _age=$(( $(date +%s) - _mtime ))
    [ "$_age" -lt "$UI_ALIVE_MAX_AGE_S" ]
}

# ask_approval <version>  ->  0 = go ahead, 1 = refused
#
# Writes an offer for the IVI app to show, then waits for a one-word verdict.
# Called BEFORE the download: a refusal must not cost the driver 50 MB of a
# tethered connection.
ask_approval() {
    _av="$1"

    [ "$REQUIRE_APPROVAL" = "1" ] || return 0

    if ! ui_is_alive; then
        if [ "$ON_NO_UI" = "deny" ]; then
            warn "no head unit to approve $_av and ON_NO_UI=deny — refusing"
            report "R1|status|refused $_av — no head unit to approve it"
            return 1
        fi
        log "no head unit to ask (ON_NO_UI=approve) — proceeding automatically"
        return 0
    fi

    _oid="ivi-$(date +%s)"
    _offers="$APPROVAL_DIR/offers"
    _verdict="$APPROVAL_DIR/verdicts/$_oid"

    mkdir -p "$_offers" 2>/dev/null

    # Write to a temporary name and rename into place. A plain redirect is not
    # atomic and the app's inotify fires on the first byte, so it would read a
    # half-written file. It recovers on its next poll, which is exactly why this
    # would go unnoticed.
    # stops_vehicle is true because approving is what triggers the hold — the
    # agent locks the moment the driver accepts, before it even downloads.
    printf '{"id":"%s","target":"ivi","version":"%s","requested_at":%s,"stops_vehicle":true}\n' \
        "$_oid" "$_av" "$(date +%s)" > "$_offers/$_oid.json.tmp"
    mv "$_offers/$_oid.json.tmp" "$_offers/$_oid.json"

    log "waiting up to ${APPROVAL_TIMEOUT_S}s for the driver to approve $_av"

    _waited=0
    while [ "$_waited" -lt "$APPROVAL_TIMEOUT_S" ]; do
        if [ -f "$_verdict" ]; then
            _answer=$(cat "$_verdict" 2>/dev/null)
            rm -f "$_verdict" "$_offers/$_oid.json"
            case "$_answer" in
                approve) log "driver approved $_av";            return 0 ;;
                deny)    log "driver denied $_av"
                         report "R1|status|declined $_av on the head unit"
                         return 1 ;;
                *)       warn "unrecognised verdict '$_answer' — treating as a refusal"
                         return 1 ;;
            esac
        fi

        # The head unit may have died while the prompt was up. Stop waiting and
        # fall back to the ON_NO_UI policy rather than sitting here for the full
        # timeout on every campaign.
        if ! ui_is_alive; then
            rm -f "$_offers/$_oid.json"
            if [ "$ON_NO_UI" = "deny" ]; then
                warn "head unit went away mid-prompt and ON_NO_UI=deny — refusing"
                return 1
            fi
            warn "head unit went away mid-prompt — proceeding automatically"
            return 0
        fi

        sleep 1
        _waited=$(( _waited + 1 ))
    done

    # Withdraw our own offer: the app never answers on the driver's behalf, so
    # an abandoned offer would sit on screen forever.
    rm -f "$_offers/$_oid.json"
    warn "no answer within ${APPROVAL_TIMEOUT_S}s — refusing $_av"
    report "R1|status|no answer for $_av on the head unit"
    return 1
}

# --- semver compare, without `sort -V` ---------------------------------------
# busybox's sort may lack -V; compare numerically instead of assuming.
# ver_gt A B  -> true if A > B
ver_gt() {
    _a="$1"; _b="$2"
    [ "$_a" = "$_b" ] && return 1
    for _i in 1 2 3; do
        _x=$(echo "$_a" | cut -d. -f$_i); _y=$(echo "$_b" | cut -d. -f$_i)
        _x=${_x:-0}; _y=${_y:-0}
        [ "$_x" -gt "$_y" ] 2>/dev/null && return 0
        [ "$_x" -lt "$_y" ] 2>/dev/null && return 1
    done
    return 1
}

# --- handle one feed message -------------------------------------------------
handle() {
    _msg="$1"

    # --- ping ----------------------------------------------------------------
    # Answers "is this agent alive and processing the feed", i.e. would a campaign
    # published right now be acted on. The dashboard has no pong branch for IVI,
    # so this rides on R1|status.
    if [ "$_msg" = "P" ]; then
        log "ping -> pong"
        report "R1|status|pong — agent alive, running $(installed_version)"
        return 0
    fi

    # --- version query -------------------------------------------------------
    # The dashboard currently sends "Q1?" for the IVI target; "IVI1?" is accepted
    # too so the query still works if that is ever made consistent with the tag.
    if [ "$_msg" = "Q1?" ] || [ "$_msg" = "${TARGET_TAG}?" ]; then
        _v=$(installed_version)
        log "version query -> $_v"
        report "R1|version|$_v"
        return 0
    fi

    case "$_msg" in
        "$TARGET_TAG"'|'*) ;;
        *) log "not for us (no $TARGET_TAG tag) — ignoring"; return 0 ;;
    esac

    # Reject shell metacharacters outright, before any field is used anywhere near
    # a command. We never eval and always quote, but defence in depth: this string
    # arrived from the internet. Base64 is [A-Za-z0-9+/=] and a B2 signed url is
    # [A-Za-z0-9:/.?=_-], so nothing legitimate is caught by this.
    case "$_msg" in
        *'`'*|*'$'*|*';'*|*'&'*|*'<'*|*'>'*|*'('*|*')'*|*"'"*|*'"'*|*' '*)
            warn "message contains illegal characters — refusing"; return 0 ;;
    esac

    _ver=$(echo     "$_msg" | cut -d'|' -f2)
    _url=$(echo     "$_msg" | cut -d'|' -f3)
    _wrapped=$(echo "$_msg" | cut -d'|' -f4)
    _size=$(echo    "$_msg" | cut -d'|' -f5)
    _sha=$(echo     "$_msg" | cut -d'|' -f6)

    # version: strict semver
    echo "$_ver" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || {
        warn "bad version '$_ver' — refusing"; return 0; }

    # url: ALLOWLIST. Without this, a forged message points the board at any host
    # on the internet (SSRF, or a huge file to exhaust the disk).
    case "$_url" in
        "$OTA_URL_PREFIX"*) ;;
        *) warn "url not in allowlist — refusing.
     got:      $_url
     required: ${OTA_URL_PREFIX}*"; return 0 ;;
    esac

    [ -n "$_wrapped" ] || { warn "no wrapped key in campaign — refusing"; return 0; }

    # --- idempotence ---------------------------------------------------------
    # Adafruit IO's /get replays the last feed value on every reconnect, so
    # without this the board reinstalls on every boot.
    _running=$(installed_version)
    if [ "$_ver" = "$_running" ]; then
        log "already running $_ver — nothing to do"
        return 0
    fi

    if [ "$ALLOW_DOWNGRADE" = "0" ] && ! ver_gt "$_ver" "$_running"; then
        warn "REFUSING DOWNGRADE: offered $_ver, running $_running.
     A replayed OLD signed package passes swupdate's signature check -- this is
     the gate that stops it. Set ALLOW_DOWNGRADE=1 in $CONF to permit."
        report "R1|status|refused downgrade $_ver (running $_running)"
        return 0
    fi

    # --- rate limit ----------------------------------------------------------
    _now=$(date +%s)
    if [ -f "$LAST_TRY_FILE" ]; then
        _last=$(cat "$LAST_TRY_FILE" 2>/dev/null || echo 0)
        _delta=$(( _now - _last ))
        if [ "$_delta" -lt "$MIN_RETRY_SECS" ]; then
            log "rate limited (${_delta}s < ${MIN_RETRY_SECS}s) — skipping"
            return 0
        fi
    fi
    echo "$_now" > "$LAST_TRY_FILE"

    log "update offered: $_running -> $_ver"

    # Ask the driver BEFORE spending their bandwidth. Everything above this is
    # free: tag, version, allowlist, downgrade gate and rate limit have all
    # passed, so the prompt can state a version we actually believe in, and a
    # refusal costs nothing.
    if ! ask_approval "$_ver"; then
        return 0
    fi

    # Approval is what stops the vehicle. The driver said yes to an update, and
    # the update starts now — holding the road until the install is the point.
    #
    # Everything from here to the install lives in run_update() purely so this
    # unlock cannot be skipped. There are eight early returns in there (download
    # failure, key unwrap, decryption, cpio magic, size, sha, swupdate itself);
    # locking inline and releasing at the bottom would leave the car immobilised
    # on any one of them until the coordinator's 300 s timeout expired.
    lock_system
    run_update
    unlock_system
    return 0
}

run_update() {
    report "R1|status|starting update $_running -> $_ver"

    rm -rf "$WORKDIR/dl"; mkdir -p "$WORKDIR/dl"
    _enc="$WORKDIR/dl/update.swu.enc"
    _swu="$WORKDIR/dl/update.swu"

    # --- download ------------------------------------------------------------
    # Anonymous. The bucket is private, but the url carries its own
    # "?Authorization=" download token, so no storage credential lives on this
    # device. Note the token EXPIRES (7 days) -- an old campaign replayed after
    # that fails here rather than installing something stale.
    log "downloading encrypted package"
    if ! curl -fsSL --proto '=https' --tlsv1.2 --max-time 900 \
              --max-filesize "$MAX_BYTES" \
              -o "$_enc" "$_url"; then
        warn "download failed: $_url"
        report "R1|status|FAILED $_ver — download error (expired url? check the campaign age)"
        rm -rf "$WORKDIR/dl"
        return 0
    fi
    log "downloaded $(wc -c < "$_enc") bytes"

    # --- unwrap the session key ----------------------------------------------
    # base64 -> RSA-decrypt with the device private key -> "KEY_HEX:IV_HEX".
    # Never logged: it decrypts the image.
    # `openssl base64 -d -A`, not the coreutils `base64 -d`: the Jetson image is
    # BusyBox with no package manager and ships no base64 binary, while openssl is
    # already required here anyway. -A is required -- the wrapped key is one long
    # unwrapped line, and without it openssl chokes past 76 chars.
    _unwrapped=$(printf '%s' "$_wrapped" | openssl base64 -d -A 2>/dev/null \
                 | openssl pkeyutl -decrypt -inkey "$PRIV_KEY" 2>/dev/null) || _unwrapped=""
    if [ -z "$_unwrapped" ]; then
        warn "could not unwrap the session key.
     Either the campaign was wrapped for a DIFFERENT public key than the private
     key at $PRIV_KEY, or the field is corrupt."
        report "R1|status|FAILED $_ver — key unwrap failed (wrong keypair?)"
        rm -rf "$WORKDIR/dl"
        return 0
    fi

    _aes_key=${_unwrapped%%:*}
    _aes_iv=${_unwrapped##*:}
    echo "$_aes_key" | grep -Eq '^[0-9a-fA-F]{64}$' || {
        warn "unwrapped AES key is not 64 hex chars — refusing"
        report "R1|status|FAILED $_ver — malformed session key"
        rm -rf "$WORKDIR/dl"; return 0; }
    echo "$_aes_iv" | grep -Eq '^[0-9a-fA-F]{32}$' || {
        warn "unwrapped AES IV is not 32 hex chars — refusing"
        report "R1|status|FAILED $_ver — malformed session IV"
        rm -rf "$WORKDIR/dl"; return 0; }
    log "session key unwrapped (AES-256-CBC)"

    # --- decrypt -------------------------------------------------------------
    if ! openssl enc -d -aes-256-cbc -in "$_enc" -out "$_swu" \
                  -K "$_aes_key" -iv "$_aes_iv" 2>/dev/null; then
        warn "decryption failed — wrong key, or the blob is truncated"
        report "R1|status|FAILED $_ver — decryption failed"
        rm -rf "$WORKDIR/dl"
        return 0
    fi
    rm -f "$_enc"
    _got=$(wc -c < "$_swu")
    log "decrypted to $_got bytes"

    # --- cheap sanity checks before spending a swupdate run ------------------
    # AES-CBC has no MAC, so a corrupted download decrypts to garbage rather than
    # failing. swupdate would catch that on its own checksums, but late and with a
    # worse message. A cpio magic check costs nothing and localises the fault.
    _magic=$(dd if="$_swu" bs=1 count=6 2>/dev/null)
    case "$_magic" in
        070701|070702) ;;
        *) warn "decrypted payload is not a cpio archive (magic '$_magic') — refusing.
     A .swu is a cpio; this usually means the blob was corrupted in transit or
     encrypted with a different key than the one announced."
           report "R1|status|FAILED $_ver — not a valid .swu after decryption"
           rm -rf "$WORKDIR/dl"; return 0 ;;
    esac

    # Optional integrity fields. Absent in the current 4-field campaign; checked
    # whenever the dashboard starts publishing them.
    if echo "$_size" | grep -Eq '^[0-9]+$'; then
        [ "$_got" = "$_size" ] || {
            warn "size mismatch: got $_got, announced $_size — refusing"
            report "R1|status|FAILED $_ver — size mismatch"
            rm -rf "$WORKDIR/dl"; return 0; }
        log "size OK"
    fi
    if echo "$_sha" | grep -Eq '^[0-9a-f]{64}$'; then
        _calc=$(sha256sum "$_swu" | cut -d' ' -f1)
        [ "$_calc" = "$_sha" ] || {
            warn "sha256 mismatch — refusing.
     announced: $_sha
     actual:    $_calc"
            report "R1|status|FAILED $_ver — sha256 mismatch"
            rm -rf "$WORKDIR/dl"; return 0; }
        log "sha256 OK (integrity precheck; sw-description.sig is the trust anchor)"
    fi

    report "R1|status|downloaded and decrypted $_ver, installing"

    # --- hand off to swupdate ------------------------------------------------
    # swupdate does the real work: verifies sw-description.sig against the key
    # provisioned on this device, then runs the install handlers. THIS is where
    # trust is decided -- everything above only avoided wasting time.
    log "handing off to swupdate: swupdate $SWUPDATE_ARGS -i $_swu"
    # shellcheck disable=SC2086
    if swupdate $SWUPDATE_ARGS -i "$_swu"; then
        log "update to $_ver applied"
        # NOT a bare redirect. `set -e` is on and we are between lock_system and
        # unlock_system: a failed write (read-only /data, full partition, missing
        # directory) would kill the shell right here, the release would never run,
        # and the vehicle would stay immobilised until the coordinator's
        # timeout_sec force-releases it -- 1200 s, twenty minutes, per
        # update-coordinator.service.
        #
        # So the failure is downgraded to a warning. The cost is one spurious
        # reinstall on the next campaign, because we will still believe we are on
        # the old version. That is much cheaper than a stationary car.
        #
        # Written via .tmp + mv, the same way the approval offer above is: a bare
        # redirect TRUNCATES first, so losing power mid-write leaves a zero-byte
        # file. installed_version() would then hand an empty string to the
        # version compare, which ver_gt treats as 0.0.0 -- i.e. the downgrade gate
        # silently off, which is the worst of the three possible outcomes here.
        if ! { echo "$_ver" > "$VERSION_FILE.tmp" && mv "$VERSION_FILE.tmp" "$VERSION_FILE"; } 2>/dev/null; then
            rm -f "$VERSION_FILE.tmp" 2>/dev/null || :
            warn "installed $_ver but COULD NOT RECORD IT in $VERSION_FILE.
     This board will keep reporting the old version and will reinstall $_ver on
     the next campaign. Check that /data is mounted and writable: findmnt /data"
        fi
        report "R1|version|$_ver"
        report "R1|status|updated to $_ver"
        rm -rf "$WORKDIR/dl"
        if [ "$REBOOT_AFTER_UPDATE" = "1" ]; then
            log "rebooting as configured"
            report "R1|status|rebooting to complete $_ver"
            sync; reboot
        fi
    else
        warn "swupdate refused or failed the update to $_ver (see above).
     If the package is otherwise good, suspect signature verification: the key
     baked into this device must match the one that signed sw-description."
        report "R1|status|FAILED update to $_ver — see journalctl -u ivi-ota-agent"
        rm -rf "$WORKDIR/dl"
    fi
}

# --- main --------------------------------------------------------------------
log "agent starting — broker $AIO_HOST:$AIO_PORT topic $TOPIC as $CLIENT_ID"
log "policy: target=$TARGET_TAG allow_downgrade=$ALLOW_DOWNGRADE workdir=$WORKDIR"
log "url allowlist: ${OTA_URL_PREFIX}*"
log "version file: $VERSION_FILE"
log "installed version: $(installed_version)"

# Adafruit IO has no true MQTT retain. Publishing to <feed>/get makes IO
# republish the last value on the feed topic, which is how a freshly-booted board
# learns the current campaign. Safe unconditionally because handle() is
# idempotent against the installed version.
#
# Bounded like report(), for the same reason and with the same watchdog. This one
# is already backgrounded so it never delayed anything, but an unbounded publish
# against a stalled broker leaves a mosquitto_pub sitting in the process table
# for a minute holding the AIO key in its argv, which is exactly the litter that
# made this whole failure mode hard to read in `ps`.
( sleep 5
  # `-m "get"`, NOT `-n`. Adafruit's docs say to publish "anything" to that topic,
  # and `-n` sends a ZERO-LENGTH payload -- i.e. nothing. The publish succeeds and
  # IO simply never replays the value, so the agent sits connected and silent
  # through a campaign it should install. The content is ignored; it must exist.
  mosquitto_pub -h "$AIO_HOST" -p "$AIO_PORT" --capath /etc/ssl/certs \
                -u "$AIO_USER" -P "$AIO_KEY" \
                -t "$TOPIC/get" -m "get" >/dev/null 2>&1 &
  _get=$!
  _report_watchdog "$_get" "$REPORT_TIMEOUT_S" &
  _getdog=$!
  if wait "$_get" 2>/dev/null; then
      echo "[ivi-ota] requested last feed value via $TOPIC/get"
  else
      echo "[ivi-ota] WARNING: /get request failed or timed out (will still get new messages)" >&2
  fi
  kill "$_getdog" 2>/dev/null || :
  wait "$_getdog" 2>/dev/null || :
) &

# NOTE: -P puts the AIO key in this process's argv, visible to `ps`. mosquitto_sub
# offers no env/file alternative. Acceptable on a single-user appliance, but do
# not copy this pattern onto a multi-user box.
#
# No `-c`: clean session. We do not want the broker queueing stale campaigns for
# us while offline -- /get gives us the CURRENT one, which is what we want.
exec mosquitto_sub -h "$AIO_HOST" -p "$AIO_PORT" --capath /etc/ssl/certs \
                   -u "$AIO_USER" -P "$AIO_KEY" \
                   -t "$TOPIC" -q 1 -i "$CLIENT_ID" \
| while IFS= read -r line; do
      [ -n "$line" ] || continue
      log "feed: $line"
      handle "$line" || warn "handler error (continuing)"
  done
