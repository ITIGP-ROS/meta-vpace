local function file_exists(name)
    local f = io.open(name, "r")
    if f then
        f:close()
        return true
    end
    return false
end

function postinst()
    local staging = "/tmp/ivi-app-staging"
    local tarball = "/tmp/ivi-app.tar.gz"
    local target = "/usr/bin/appIVI"
    local backup = target .. ".bak"

    os.execute("mkdir -p " .. staging)

    local success, err = os.execute("tar xzf " .. tarball .. " -C " .. staging)
    if not success then
        print("Failed to extract " .. tarball .. ": " .. tostring(err))
        return false, "Extraction failed"
    end

    local new_binary = staging .. "/usr/bin/appIVI"
    if not file_exists(new_binary) then
        print("appIVI binary not found in update")
        return false, "Binary not found"
    end

    -- Backup current binary
    os.execute("cp " .. target .. " " .. backup .. " 2>/dev/null")

    -- Atomic swap
    local tmp = target .. ".new"
    os.execute("cp " .. new_binary .. " " .. tmp)
    os.execute("mv " .. tmp .. " " .. target)

    local assets_src = staging .. "/usr/assets"
    if file_exists(assets_src) then
        os.execute("cp -r " .. assets_src .. " /usr/")
    end

    -- Restart and check
    os.execute("systemctl restart ivi-app")
    os.execute("sleep 3")

    local handle = io.popen("systemctl is-active ivi-app")
    local status = handle:read("*a")
    handle:close()
    status = status:match("^%s*(.-)%s*$")

    if status ~= "active" then
        print("IVI service failed to start after update, rolling back")
        os.execute("cp " .. backup .. " " .. tmp)
        os.execute("mv " .. tmp .. " " .. target)
        os.execute("systemctl restart ivi-app")
    end

    os.execute("rm -rf " .. staging)
    os.execute("rm -f " .. tarball)

    return true, "IVI app updated successfully"
end
