param(
    [Parameter(Mandatory = $true)]
    [string]$VersionName,
    [int]$VersionCode,
    [string]$AppName = "Anisubroid",
    [string]$BuildTask = "assembleDebug",
    [switch]$SkipBuild,
    [switch]$SkipPush,
    [string]$Branch = "main",
    [switch]$CreateRelease,
    [string]$Repo = "Mayeggx/Anisubroid",
    [string]$ReleaseAssetPath,
    [string]$ReleaseTitle,
    [string]$ReleaseNotes
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\use-e-drive-android-env.ps1"

$projectRoot = Split-Path $PSScriptRoot -Parent
$gradleFile = Join-Path $projectRoot "app\build.gradle.kts"
$apkPath = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
$releaseDir = Join-Path $projectRoot "release"
$tagName = "v$VersionName"
$autoStashName = "release.ps1-auto-stash-$([DateTimeOffset]::Now.ToUnixTimeSeconds())"
$didAutoStash = $false
$tagAlreadyExists = $false

function Get-TrackedStatus {
    return @(& git status --porcelain | Where-Object { $_ -and -not $_.StartsWith("?? ") })
}

function Get-GitHubHeaders {
    $cred = @"
protocol=https
host=github.com

"@ | git credential fill

    $tokenLine = $cred | Select-String '^password=' | Select-Object -First 1
    if (-not $tokenLine) {
        throw "Failed to resolve GitHub token from git credential helper."
    }

    $token = $tokenLine.ToString().Split('=', 2)[1]
    return @{
        Authorization = "Bearer $token"
        Accept = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
}

function Read-VersionInfo {
    param([string]$Content)

    $versionNameMatch = [regex]::Match($Content, 'versionName\s*=\s*"([^"]+)"')
    $versionCodeMatch = [regex]::Match($Content, 'versionCode\s*=\s*(\d+)')

    if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
        throw "Failed to read versionName/versionCode from $gradleFile"
    }

    return [pscustomobject]@{
        VersionName = $versionNameMatch.Groups[1].Value
        VersionCode = [int]$versionCodeMatch.Groups[1].Value
    }
}

function Get-ArtifactName {
    param(
        [string]$Name,
        [string]$Version
    )

    return "$Name-v$Version.apk"
}

function Get-DefaultReleaseNotes {
    param(
        [string]$Name,
        [string]$Version,
        [string]$AssetFileName
    )

    return @"
$Name $Version

Assets:
- $AssetFileName
"@
}

Push-Location $projectRoot
try {
    $statusBefore = Get-TrackedStatus
    if ($statusBefore.Count -gt 0) {
        Write-Output "Detected tracked local changes. Auto stashing before release..."
        & git stash push -m $autoStashName | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "git stash push failed." }
        $didAutoStash = $true
        Write-Output "AutoStash=$autoStashName"
    }

    $statusAfterStash = Get-TrackedStatus
    if ($statusAfterStash.Count -gt 0) {
        throw "Git worktree is not clean after auto stash. Resolve manually and rerun."
    }

    $gitUserName = (& git config user.name).Trim()
    $gitUserEmail = (& git config user.email).Trim()
    if ([string]::IsNullOrWhiteSpace($gitUserName) -or [string]::IsNullOrWhiteSpace($gitUserEmail)) {
        throw "Git user.name/user.email is not configured for this repository."
    }

    $content = Get-Content $gradleFile -Raw
    $current = Read-VersionInfo -Content $content

    $existingTag = (& git tag --list $tagName)
    $tagAlreadyExists = -not [string]::IsNullOrWhiteSpace(($existingTag | Select-Object -First 1))
    if ($tagAlreadyExists) {
        Write-Output "Tag already exists: $tagName (republish mode)"
    }

    if (-not $PSBoundParameters.ContainsKey("VersionCode")) {
        if ($tagAlreadyExists -and $current.VersionName -eq $VersionName) {
            $VersionCode = $current.VersionCode
        } else {
            $VersionCode = $current.VersionCode + 1
        }
    }

    $updated = $content
    $updated = [regex]::Replace($updated, 'versionName\s*=\s*"([^"]+)"', "versionName = `"$VersionName`"", 1)
    $updated = [regex]::Replace($updated, 'versionCode\s*=\s*(\d+)', "versionCode = $VersionCode", 1)

    if ($updated -ne $content) {
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText((Resolve-Path $gradleFile), $updated, $utf8NoBom)
    }

    $after = Read-VersionInfo -Content (Get-Content $gradleFile -Raw)
    if ($after.VersionName -ne $VersionName -or $after.VersionCode -ne $VersionCode) {
        throw "Version update verification failed."
    }

    Write-Output "VersionName=$($after.VersionName)"
    Write-Output "VersionCode=$($after.VersionCode)"

    if (-not $SkipBuild) {
        Write-Output "Building APK with task $BuildTask ..."
        & .\gradlew.bat clean $BuildTask --console=plain --no-daemon
        if (-not (Test-Path $apkPath)) {
            throw "Build finished but APK was not found: $apkPath"
        }
        Write-Output "APK=$apkPath"
    }

    $artifactName = Get-ArtifactName -Name $AppName -Version $VersionName
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
    $releaseAsset = Join-Path $releaseDir $artifactName
    Copy-Item -Force $apkPath $releaseAsset
    Write-Output "ReleaseAsset=$releaseAsset"

    if (-not $tagAlreadyExists) {
        & git add $gradleFile
        if ($LASTEXITCODE -ne 0) { throw "git add failed." }
        & git commit -m "Release $tagName"
        if ($LASTEXITCODE -ne 0) { throw "git commit failed." }
        & git tag -a $tagName -m "Release $tagName"
        if ($LASTEXITCODE -ne 0) { throw "git tag failed." }

        if (-not $SkipPush) {
            & git push origin $Branch
            if ($LASTEXITCODE -ne 0) { throw "git push branch failed." }
            & git push origin $tagName
            if ($LASTEXITCODE -ne 0) { throw "git push tag failed." }
        }
    } else {
        Write-Output "Skip commit/tag because $tagName already exists."
    }

    Write-Output "Release prep complete."
    Write-Output "Tag=$tagName"

    if ($CreateRelease) {
        $assetPath = if ($ReleaseAssetPath) { $ReleaseAssetPath } else { $releaseAsset }
        if (-not (Test-Path $assetPath)) {
            throw "Release asset not found: $assetPath"
        }

        $assetFileName = Split-Path $assetPath -Leaf
        $finalReleaseTitle = if ($ReleaseTitle) { $ReleaseTitle } else { "v$VersionName" }
        $finalReleaseNotes =
            if ($ReleaseNotes) {
                $ReleaseNotes
            } else {
                Get-DefaultReleaseNotes -Name $AppName -Version $VersionName -AssetFileName $assetFileName
            }

        $headers = Get-GitHubHeaders
        $releaseBody =
            @{
                tag_name = $tagName
                target_commitish = $Branch
                name = $finalReleaseTitle
                body = $finalReleaseNotes
                draft = $false
                prerelease = $false
            } | ConvertTo-Json

        $release = $null
        try {
            $release =
                Invoke-RestMethod `
                    -Method Get `
                    -Headers $headers `
                    -Uri "https://api.github.com/repos/$Repo/releases/tags/$tagName"
            Write-Output "Use existing GitHub release for $tagName"
        } catch {
            $release =
                Invoke-RestMethod `
                    -Method Post `
                    -Headers $headers `
                    -Uri "https://api.github.com/repos/$Repo/releases" `
                    -Body $releaseBody `
                    -ContentType "application/json"
            Write-Output "Created new GitHub release for $tagName"
        }

        $assetFileName = Split-Path $assetPath -Leaf
        $existingAsset = $release.assets | Where-Object { $_.name -eq $assetFileName } | Select-Object -First 1
        if ($existingAsset) {
            Invoke-RestMethod `
                -Method Delete `
                -Headers $headers `
                -Uri "https://api.github.com/repos/$Repo/releases/assets/$($existingAsset.id)"
            Write-Output "Deleted existing asset=$assetFileName"
        }

        $uploadUrl = ($release.upload_url -replace '\{\?name,label\}', '') + "?name=$([System.Uri]::EscapeDataString($assetFileName))"
        Invoke-RestMethod `
            -Method Post `
            -Headers @{
                Authorization = $headers.Authorization
                Accept = $headers.Accept
                "Content-Type" = "application/vnd.android.package-archive"
            } `
            -Uri $uploadUrl `
            -InFile $assetPath | Out-Null

        Write-Output "ReleaseUrl=$($release.html_url)"
        Write-Output "UploadedAsset=$(Split-Path $assetPath -Leaf)"
    } else {
        Write-Output "Next: create a GitHub Release based on $tagName and upload only the APK as a manual asset if needed."
        Write-Output "Note: GitHub will still auto-generate source archives (zip/tar.gz); those cannot be removed."
    }
} finally {
    if ($didAutoStash) {
        Write-Output "Restoring auto stashed changes..."
        $stashLine = (& git stash list | Select-String -SimpleMatch $autoStashName | Select-Object -First 1)
        if ($stashLine) {
            $stashRef = ($stashLine.ToString().Split(':', 2)[0]).Trim()
            & git stash pop $stashRef
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "git stash pop failed. Please resolve manually and run: git stash list"
            } else {
                Write-Output "Auto stash restored."
            }
        } else {
            Write-Output "Auto stash entry not found. It may have been restored already."
        }
    }
    Pop-Location
}
