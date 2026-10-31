# Builds Plumbob.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner (debug key).
$ErrorActionPreference = "Stop"
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$bt   = "$sdk\build-tools\36.1.0"
$jar  = "$sdk\platforms\android-36.1\android.jar"
$jdk  = "C:\Program Files\Android\Android Studio\jbr\bin"
$env:JAVA_HOME = Split-Path $jdk
$out  = "$PSScriptRoot\build"

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" compile --dir "$PSScriptRoot\res" -o "$out\res.zip"
& "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $jar --manifest "$PSScriptRoot\AndroidManifest.xml" "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 failed" }

$src = Get-ChildItem -Recurse "$PSScriptRoot\src" -Filter *.java | ForEach-Object FullName
& "$jdk\javac.exe" --release 11 -cp $jar -encoding UTF-8 -d "$out\classes" $src
if ($LASTEXITCODE) { throw "javac failed" }

$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --lib $jar --min-api 26 --output "$out\dex" $classes
if ($LASTEXITCODE) { throw "d8 failed" }

Push-Location "$out\dex"; & "$jdk\jar.exe" uf "$out\unsigned.apk" classes.dex; Pop-Location
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android `
    --out "$PSScriptRoot\..\Plumbob.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
Write-Host "OK: $(Resolve-Path "$PSScriptRoot\..\Plumbob.apk")"
