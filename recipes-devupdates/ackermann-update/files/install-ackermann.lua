-- install-ackermann.lua — extract-only installer for the Ackermann stack payload.
--
-- The three tarballs (ackermann-description, ackermann-bringup,
-- ackermann-hardware) are merged into /opt/ros/humble. The stack is started
-- manually (`ros2 launch ackermann_bringup robot.launch.py`), so there is
-- nothing to restart or health-check: the new files take effect on the next
-- launch. Merging over the live install tree is safe because no ackermann
-- process is running while swupdate runs this script.

local ROS_PREFIX = "/opt/ros/humble"

local bundles = {
    { tarball = "/tmp/ackermann-description.tar.gz", sentinel = "opt/ros/humble/share/ackermann_description/package.xml" },
    { tarball = "/tmp/ackermann-bringup.tar.gz",     sentinel = "opt/ros/humble/share/ackermann_bringup/package.xml" },
    { tarball = "/tmp/ackermann-hardware.tar.gz",    sentinel = "opt/ros/humble/share/ackermann_hardware/package.xml" },
}

local function file_exists(name)
    local f = io.open(name, "r")
    if f then
        f:close()
        return true
    end
    return false
end

local function exec(cmd)
    local ok, reason, code = os.execute(cmd .. " >/dev/null 2>&1")
    return ok == true
end

function postinst()
    for _, b in ipairs(bundles) do
        local staging = "/tmp/ackermann-staging-" .. b.tarball:match("[^/]+$")
        os.execute("rm -rf '" .. staging .. "'")

        if not exec("mkdir -p '" .. staging .. "'") then
            return false, "Failed to create staging directory"
        end

        if not exec("tar xzf '" .. b.tarball .. "' -C '" .. staging .. "'") then
            os.execute("rm -rf '" .. staging .. "'")
            return false, "Extraction failed: " .. b.tarball
        end

        if not file_exists(staging .. "/" .. b.sentinel) then
            os.execute("rm -rf '" .. staging .. "'")
            return false, "Missing expected file in update: " .. b.sentinel
        end

        -- Merge over the live install tree. The tarball paths mirror the image
        -- rootfs, so the subtree is <staging>/opt/ros/humble.
        if not exec("cp -a '" .. staging .. "/opt/ros/humble/.' '" .. ROS_PREFIX .. "/'") then
            os.execute("rm -rf '" .. staging .. "'")
            return false, "Failed to install: " .. b.tarball
        end

        os.execute("rm -rf '" .. staging .. "'")
    end

    -- Cleanup
    os.execute("rm -f /tmp/ackermann-description.tar.gz /tmp/ackermann-bringup.tar.gz /tmp/ackermann-hardware.tar.gz")

    return true, "Ackermann stack updated successfully"
end
