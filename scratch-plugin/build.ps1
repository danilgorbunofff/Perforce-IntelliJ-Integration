# Day-0 skeleton build: javac against the local IDE distribution, package with python zipfile.
# Usage:  powershell -File build.ps1
$ErrorActionPreference = "Stop"
$env:PYTHONIOENCODING = "utf-8"
$gate = "C:\Users\danil_gorbunov\.copilot\session-state\c5230b9d-290c-4ffa-9d23-d034a7dcd0eb\files\day0-gate"
$ide = "$gate\idea-2025.3"
$javac = "$ide\jbr\bin\javac.exe"
$out = "$PSScriptRoot\out"

if (-not (Test-Path $javac)) { throw "javac not found at $javac" }
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes" | Out-Null

$sources = Get-ChildItem -Recurse "$PSScriptRoot\src\main\java" -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -proc:none -cp "$ide\lib\*;$ide\lib\modules\*" -d "$out\classes" @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

@'
import zipfile, os, sys
root = sys.argv[1]
classes = os.path.join(root, "out", "classes")
jar = os.path.join(root, "out", "p4-gate.jar")
with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
    z.writestr("META-INF/plugin.xml", open(os.path.join(root, "src", "main", "resources", "META-INF", "plugin.xml"), encoding="utf-8").read())
    for dirpath, _, files in os.walk(classes):
        for f in files:
            full = os.path.join(dirpath, f)
            z.write(full, os.path.relpath(full, classes))
zipname = os.path.join(root, "out", "perforce-intellij-integration-day0.zip")
with zipfile.ZipFile(zipname, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(jar, "lib/p4-gate.jar")
print("packaged:", zipname, os.path.getsize(zipname), "bytes")
'@ | python - "$PSScriptRoot"
