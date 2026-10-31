# Builds PlumbobLED.apk and publishes it as GitHub release v<versionCode> (the in-app updater compares this tag).
# Bump android:versionCode in AndroidManifest.xml first. The APK must stay signed with the same debug key.
$ErrorActionPreference = "Stop"
$repo = "ntamas94/plumbob-led"
$manifest = Get-Content "$PSScriptRoot\AndroidManifest.xml" -Raw
if ($manifest -notmatch 'android:versionCode="(\d+)"') { throw "versionCode not found" }
$code = $Matches[1]
$tag = "v$code"

& "$PSScriptRoot\build.ps1"
if ($LASTEXITCODE) { throw "build failed" }
$apk = Resolve-Path "$PSScriptRoot\..\PlumbobLED.apk"

$notes = if ($args.Count) { $args -join " " } else { "Plumbob LED $tag" }
cmd /c "gh release view $tag --repo $repo >nul 2>&1"   # PS 5.1 turns gh's stderr into an error, cmd swallows it
if ($LASTEXITCODE -eq 0) {
    gh release upload $tag $apk --clobber --repo $repo
} else {
    gh release create $tag $apk --title "Plumbob LED $tag" --notes $notes --repo $repo
}
if ($LASTEXITCODE) { throw "gh release failed" }
Write-Host "Released $tag"
