param(
    [string]$ProjectRoot = ".",
    [string]$PackageName = "com.example.storagenas",
    [string]$DeviceId,
    [string]$WirelessEndpoint,
    [int]$ReconnectTimeoutSec = 45
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-SdkDir {
    param([string]$Root)

    $localPropsPath = Join-Path $Root "local.properties"
    if (-not (Test-Path $localPropsPath)) {
        throw "local.properties not found at: $localPropsPath"
    }

    $sdkLine = Get-Content $localPropsPath | Where-Object { $_ -match "^sdk\.dir=" } | Select-Object -First 1
    if (-not $sdkLine) {
        throw "sdk.dir is missing in local.properties"
    }

    $sdkRaw = $sdkLine.Substring("sdk.dir=".Length).Trim()
    $sdkDir = $sdkRaw -replace "\\:", ":" -replace "\\\\", "\"
    if (-not (Test-Path $sdkDir)) {
        throw "Android SDK dir does not exist: $sdkDir"
    }
    return $sdkDir
}

function Get-LatestBuildToolsDir {
    param([string]$SdkDir)

    $buildToolsRoot = Join-Path $SdkDir "build-tools"
    if (-not (Test-Path $buildToolsRoot)) {
        throw "build-tools folder not found under SDK: $buildToolsRoot"
    }

    $candidates = Get-ChildItem $buildToolsRoot -Directory
    if (-not $candidates) {
        throw "No build-tools versions found in: $buildToolsRoot"
    }

    $latest = $candidates |
        Sort-Object -Property @{
            Expression = {
                try { [version]$_.Name } catch { [version]"0.0.0.0" }
            }
            Descending = $true
        } |
        Select-Object -First 1

    return $latest.FullName
}

function Resolve-TargetDevice {
    param(
        [string]$AdbPath,
        [string]$RequestedDeviceId
    )

    $onlineIds = Get-OnlineDeviceIds -AdbPath $AdbPath

    if ($onlineIds.Count -eq 0) {
        throw "No online ADB devices found."
    }

    if ($RequestedDeviceId) {
        if ($onlineIds -notcontains $RequestedDeviceId) {
            throw "Requested device is not online: $RequestedDeviceId`nOnline: $($onlineIds -join ', ')"
        }
        return $RequestedDeviceId
    }

    if ($onlineIds.Count -eq 1) {
        return $onlineIds[0]
    }

    $wireless = $onlineIds | Where-Object { $_ -match "_adb-tls-connect\._tcp" -or $_ -match "^\d{1,3}(\.\d{1,3}){3}:\d+$" }
    if ($wireless.Count -ge 1) {
        return $wireless[0]
    }

    $physical = $onlineIds | Where-Object { $_ -notmatch "^emulator-\d+$" }
    if ($physical.Count -eq 1) {
        return $physical[0]
    }

    throw "Multiple devices online and no unique wireless/physical target detected. Pass -DeviceId. Online: $($onlineIds -join ', ')"
}

function Get-OnlineDeviceIds {
    param([string]$AdbPath)

    $raw = & $AdbPath devices -l
    $deviceLines = $raw | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" }
    $onlineIds = @()
    foreach ($line in $deviceLines) {
        if ($line -match "^(\S+)\s+device\b") {
            $onlineIds += $Matches[1]
        }
    }
    return $onlineIds
}

function Ensure-AdbDeviceOnline {
    param(
        [string]$AdbPath,
        [string]$RequestedDeviceId,
        [string]$WirelessConnectEndpoint,
        [int]$TimeoutSec = 45
    )

    & $AdbPath start-server | Out-Null
    & $AdbPath reconnect | Out-Null

    if ($WirelessConnectEndpoint) {
        Write-Host "Attempting wireless ADB connect to: $WirelessConnectEndpoint"
        & $AdbPath connect $WirelessConnectEndpoint | Out-Null
    } elseif ($RequestedDeviceId -and ($RequestedDeviceId -match "^\d{1,3}(\.\d{1,3}){3}:\d+$")) {
        Write-Host "Attempting wireless ADB connect to requested endpoint: $RequestedDeviceId"
        & $AdbPath connect $RequestedDeviceId | Out-Null
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $onlineIds = Get-OnlineDeviceIds -AdbPath $AdbPath

        if ($RequestedDeviceId) {
            if ($onlineIds -contains $RequestedDeviceId) {
                return $RequestedDeviceId
            }
        } else {
            $candidate = $null
            if ($WirelessConnectEndpoint -and ($onlineIds -contains $WirelessConnectEndpoint)) {
                $candidate = $WirelessConnectEndpoint
            } elseif ($onlineIds.Count -gt 0) {
                $candidate = Resolve-TargetDevice -AdbPath $AdbPath -RequestedDeviceId $null
            }

            if ($candidate) {
                return $candidate
            }
        }

        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    $current = Get-OnlineDeviceIds -AdbPath $AdbPath
    $requestedMsg = if ($RequestedDeviceId) { "requested '$RequestedDeviceId'" } else { "any suitable" }
    throw "Timed out waiting for $requestedMsg device to become online. Online now: $($current -join ', ')"
}

function Test-ApkIntegrity {
    param(
        [string]$ApkPath,
        [string]$ApkSignerPath
    )

    if (-not (Test-Path $ApkPath)) {
        throw "APK not found: $ApkPath"
    }

    $apkFile = Get-Item $ApkPath
    if ($apkFile.Length -le 0) {
        throw "APK file is empty: $ApkPath"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $entryNames = $zip.Entries | ForEach-Object { $_.FullName }
        if ($entryNames -notcontains "AndroidManifest.xml") {
            throw "APK integrity check failed: AndroidManifest.xml missing."
        }
        if (-not ($entryNames | Where-Object { $_ -match "^classes\d*\.dex$" })) {
            throw "APK integrity check failed: classes.dex not found."
        }
    }
    finally {
        $zip.Dispose()
    }

    $verifyLines = & $ApkSignerPath verify --verbose --print-certs $ApkPath
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed (apksigner exit code: $LASTEXITCODE)"
    }
    $verifyLines | ForEach-Object { Write-Host $_ }

    $hash = Get-FileHash -Algorithm SHA256 -Path $ApkPath
    return $hash.Hash
}

$root = (Resolve-Path $ProjectRoot).Path
Write-Host "Project root: $root"

$sdkDir = Resolve-SdkDir -Root $root
$adbPath = Join-Path $sdkDir "platform-tools\adb.exe"
if (-not (Test-Path $adbPath)) {
    throw "adb.exe not found at: $adbPath"
}

$buildToolsDir = Get-LatestBuildToolsDir -SdkDir $sdkDir
$apkSignerPath = Join-Path $buildToolsDir "apksigner.bat"
if (-not (Test-Path $apkSignerPath)) {
    throw "apksigner.bat not found in: $buildToolsDir"
}

Push-Location $root
try {
    Write-Host "Building debug APK..."
    & ".\gradlew.bat" ":app:assembleDebug"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed (exit code: $LASTEXITCODE)"
    }

    $apkPath = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    Write-Host "Verifying APK integrity..."
    $sha256 = Test-ApkIntegrity -ApkPath $apkPath -ApkSignerPath $apkSignerPath
    Write-Host "APK SHA-256: $sha256"

    $targetDevice = Ensure-AdbDeviceOnline `
        -AdbPath $adbPath `
        -RequestedDeviceId $DeviceId `
        -WirelessConnectEndpoint $WirelessEndpoint `
        -TimeoutSec $ReconnectTimeoutSec

    Write-Host "Installing to device: $targetDevice"
    & $adbPath -s $targetDevice install -r $apkPath
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Install failed once; attempting reconnect and one retry..."
        $targetDevice = Ensure-AdbDeviceOnline `
            -AdbPath $adbPath `
            -RequestedDeviceId $targetDevice `
            -WirelessConnectEndpoint $WirelessEndpoint `
            -TimeoutSec $ReconnectTimeoutSec
        & $adbPath -s $targetDevice install -r $apkPath
        if ($LASTEXITCODE -ne 0) {
            throw "adb install failed after retry (exit code: $LASTEXITCODE)"
        }
    }

    $installedPath = & $adbPath -s $targetDevice shell pm path $PackageName
    if (-not ($installedPath -match "^package:")) {
        throw "Install verification failed for package: $PackageName"
    }

    Write-Host "Deployment successful."
    Write-Host "Installed package path: $installedPath"
}
finally {
    Pop-Location
}
