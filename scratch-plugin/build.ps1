# Skeleton build: javac against a local IDE distribution, run the parser tests, package with python zipfile.
# Usage:  powershell -File build.ps1 -IdeHome C:\path\to\idea-2025.3     (or set $env:IDEA_HOME)
# The IDE distribution supplies both the platform jars and the JBR (javac/java). Gradle + verifyPlugin are still TODO.
param([string]$IdeHome = $env:IDEA_HOME)
$ErrorActionPreference = "Stop"
$env:PYTHONIOENCODING = "utf-8"
if (-not $IdeHome) { throw "pass -IdeHome <unpacked IDE dir> or set IDEA_HOME (an unpacked IntelliJ 2025.3 distribution)" }
$javac = "$IdeHome\jbr\bin\javac.exe"
$java = "$IdeHome\jbr\bin\java.exe"
$out = "$PSScriptRoot\out"

if (-not (Test-Path $javac)) { throw "javac not found at $javac" }
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\test-classes" | Out-Null

$cp = "$IdeHome\lib\*;$IdeHome\lib\modules\*"
$sources = @(Get-ChildItem -Recurse "$PSScriptRoot\src\main\java" -Filter *.java | ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 -proc:none -Xlint:all -Xlint:-serial -cp $cp -d "$out\classes" @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# parser/diagnosis tests: fixtures are real r25.2 output; no server needed
$tests = @(Get-ChildItem -Recurse "$PSScriptRoot\src\test\java" -Filter *.java | ForEach-Object { $_.FullName })  # @(): one file must still splat as an array
& $javac -encoding UTF-8 -proc:none -cp "$out\classes;$cp" -d "$out\test-classes" @tests
if ($LASTEXITCODE -ne 0) { throw "test javac failed with exit code $LASTEXITCODE" }
& $java -cp "$out\classes;$out\test-classes;$cp" p4gate.P4ParseTest
if ($LASTEXITCODE -ne 0) { throw "tests failed" }

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
