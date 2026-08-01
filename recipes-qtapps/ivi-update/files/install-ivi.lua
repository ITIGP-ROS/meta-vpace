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

local function is_service_active()
    -- systemctl is-active: exit 0 = active, exit 3 = inactive
    local ok, reason, code = os.execute("systemctl is-active ivi-app >/dev/null 2>&1")
    return ok == true and code == 0
end

local function cleanup(staging, tarball, backup, assets_backup)
    os.execute("rm -rf '" .. staging .. "'")
    os.execute("rm -f '" .. tarball .. "'")
    os.execute("rm -f '" .. backup .. "'")
    if assets_backup then
        os.execute("rm -rf '" .. assets_backup .. "'")
    end
end

function postinst()
    local staging = "/tmp/ivi-app-staging"
    local tarball = "/tmp/ivi-app.tar.gz"
    local target = "/usr/bin/appIVI"
    local backup = target .. ".bak"
    local tmp = target .. ".new"
    local assets_src = staging .. "/usr/assets"
    local assets_backup = "/usr/assets.bak"

    -- Clean up any leftovers from a previous failed attempt
    os.execute("rm -rf '" .. staging .. "'")
    os.execute("rm -f '" .. backup .. "'")
    os.execute("rm -rf '" .. assets_backup .. "'")

    -- Ensure current binary exists before we back it up
    if not file_exists(target) then
        cleanup(staging, tarball, backup, assets_backup)
        return false, "Target binary does not exist: " .. target
    end

    -- Stage the update
    if not exec("mkdir -p '" .. staging .. "'") then
        cleanup(staging, tarball, backup, assets_backup)
        return false, "Failed to create staging directory"
    end

    if not exec("tar xzf '" .. tarball .. "' -C '" .. staging .. "'") then
        cleanup(staging, tarball, backup, assets_backup)
        return false, "Extraction failed"
    end

    local new_binary = staging .. "/usr/bin/appIVI"
    if not file_exists(new_binary) then
        cleanup(staging, tarball, backup, assets_backup)
        return false, "appIVI binary not found in update"
    end

    -- Verify new binary is actually executable
    if not exec("test -x '" .. new_binary .. "'") then
        cleanup(staging, tarball, backup, assets_backup)
        return false, "New binary is not executable"
    end

    -- Backup current binary
    if not exec("cp '" .. target .. "' '" .. backup .. "'") then
        cleanup(staging, tarball, backup, assets_backup)
        return false, "Failed to backup current binary"
    end

    -- Atomic swap: copy to temp, then mv over target
    if not exec("cp '" .. new_binary .. "' '" .. tmp .. "'") then
        cleanup(staging, tarball, backup, assets_backup)
        return false, "Failed to stage new binary"
    end

    if not exec("mv '" .. tmp .. "' '" .. target .. "'") then
        -- Emergency: try to restore immediately
        exec("cp '" .. backup .. "' '" .. target .. "'")
        cleanup(staging, tarball, backup, assets_backup)
        return false, "Failed to swap new binary into place"
    end

    -- Handle assets: backup old, install new, rollback on failure
    local has_new_assets = file_exists(assets_src)
    local had_old_assets = file_exists("/usr/assets")

    if has_new_assets then
        if had_old_assets then
            if not exec("cp -r '/usr/assets' '" .. assets_backup .. "'") then
                -- Revert binary
                exec("cp '" .. backup .. "' '" .. tmp .. "'")
                exec("mv '" .. tmp .. "' '" .. target .. "'")
                cleanup(staging, tarball, backup, assets_backup)
                return false, "Failed to backup existing assets"
            end
        end

        if not exec("cp -r '" .. assets_src .. "' /usr/") then
            -- Revert binary
            exec("cp '" .. backup .. "' '" .. tmp .. "'")
            exec("mv '" .. tmp .. "' '" .. target .. "'")
            -- Revert assets
            if had_old_assets then
                exec("rm -rf /usr/assets")
                exec("mv '" .. assets_backup .. "' /usr/assets")
            else
                exec("rm -rf /usr/assets")
            end
            cleanup(staging, tarball, backup, assets_backup)
            return false, "Failed to copy new assets"
        end
    end

    -- Restart service
    if not exec("systemctl restart ivi-app") then
        -- Revert binary
        exec("cp '" .. backup .. "' '" .. tmp .. "'")
        exec("mv '" .. tmp .. "' '" .. target .. "'")
        -- Revert assets
        if has_new_assets then
            if had_old_assets then
                exec("rm -rf /usr/assets")
                exec("mv '" .. assets_backup .. "' /usr/assets")
            else
                exec("rm -rf /usr/assets")
            end
        end
        cleanup(staging, tarball, backup, assets_backup)
        return false, "Failed to restart IVI service"
    end

    -- Health check: verify sustained activity over ~4 seconds
    os.execute("sleep 1")
    local active = true
    for i = 1, 6 do
        if not is_service_active() then
            active = false
            break
        end
        os.execute("sleep 0.5")
    end

    if not active then
        print("IVI service failed health check after update, rolling back")

        -- Revert binary
        exec("cp '" .. backup .. "' '" .. tmp .. "'")
        exec("mv '" .. tmp .. "' '" .. target .. "'")

        -- Revert assets
        if has_new_assets then
            if had_old_assets then
                exec("rm -rf /usr/assets")
                exec("mv '" .. assets_backup .. "' /usr/assets")
            else
                exec("rm -rf /usr/assets")
            end
        end

        -- Restart old version
        exec("systemctl restart ivi-app")

        -- Wait for recovery (up to 10s)
        for i = 1, 20 do
            if is_service_active() then
                break
            end
            os.execute("sleep 0.5")
        end

        cleanup(staging, tarball, backup, assets_backup)
        -- Return false so SWUpdate knows the update failed
        return false, "Update rolled back: new IVI service failed health check"
    end

    -- Success
    cleanup(staging, tarball, backup, assets_backup)
    return true, "IVI app updated successfully"
end