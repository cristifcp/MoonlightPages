# Packs this project into one zip that is safe to upload to a cloud folder.
#
# It keeps everything you wrote: Kotlin sources, Gradle files, the Gradle wrapper, assets
# (including the bundled Moby Dick), the manifest, branding, and the README.
#
# It leaves out everything Android Studio regenerates on the next Gradle sync, which is
# where almost all of the folder's size goes:
#   build, .gradle, .kotlin, captures, .cxx, .externalNativeBuild  - build output and caches
#   local.properties                                              - this PC's SDK path only
#   .idea/caches, .idea/libraries, .idea/workspace.xml             - window and index state
#
# Usage:  right-click this file > Run with PowerShell
#     or: powershell -ExecutionPolicy Bypass -File tools\backup.ps1
# The zip lands next to the project folder, named MoonlightPages-<date>.zip (re-running the
# same day overwrites it). Copy that one file to Drive, OneDrive, or wherever you like.

$root = Split-Path -Parent $PSScriptRoot
$name = Split-Path $root -Leaf
$zip = Join-Path (Split-Path $root -Parent) "$name-$(Get-Date -Format 'yyyy-MM-dd').zip"

$skip = '(^|\\)(build|\.gradle|\.kotlin|\.git|captures|\.cxx|\.externalNativeBuild)(\\|$)' +
        '|(^|\\)\.idea\\(caches|libraries|workspace\.xml)(\\|$)' +
        '|(^|\\)local\.properties$'

$all = Get-ChildItem $root -Recurse -File -Force
$keep = @($all | Where-Object { $_.FullName.Substring($root.Length + 1) -notmatch $skip })

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Remove-Item $zip -Force -ErrorAction SilentlyContinue
$archive = [System.IO.Compression.ZipFile]::Open($zip, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $keep) {
        $entry = "$name/" + $file.FullName.Substring($root.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, $file.FullName, $entry, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }

$kept = ($keep | Measure-Object Length -Sum).Sum
$left = (($all | Measure-Object Length -Sum).Sum) - $kept
"Backed up $($keep.Count) files ({0:N1} MB of source) into:" -f ($kept / 1MB)
"  $zip  ({0:N1} MB)" -f ((Get-Item $zip).Length / 1MB)
"Left out $($all.Count - $keep.Count) build and cache files ({0:N1} MB); Gradle recreates them." -f ($left / 1MB)
