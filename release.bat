@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%" || goto :cd_failed

set "VERSION=%~1"
if "%VERSION%"=="" set "VERSION=1.8"
set "MODE=%~2"
set "ZIP_NAME=Limecraft-%VERSION%-windows.zip"
set "APP_IMAGE=%CD%\dist\Limecraft"
set "APP_EXE=%APP_IMAGE%\Limecraft.exe"
set "ZIP_PATH=%CD%\%ZIP_NAME%"

echo [release] Building packaged app image...
call ship.bat
if errorlevel 1 goto :package_failed

if exist "%ZIP_NAME%" del /f /q "%ZIP_NAME%"

echo [release] Creating zip artifact...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -LiteralPath '.\dist\Limecraft' -DestinationPath '.\%ZIP_NAME%' -Force"
if errorlevel 1 goto :zip_failed

echo [release] Running privacy / leak scan...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$root = Resolve-Path '.\dist\Limecraft';" ^
  "$blocked = @('launcher.properties','secure-tokens.properties','accounts.json','profiles.json');" ^
  "$badFiles = Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object { $blocked -contains $_.Name };" ^
  "if ($badFiles.Count -gt 0) { Write-Host 'Blocked runtime data files were found in the package:'; $badFiles | ForEach-Object { Write-Host (' - ' + $_.FullName) }; exit 2 };" ^
  "$textExtensions = @('.cfg','.properties','.txt','.json','.xml','.ini','.md','.bat','.cmd','.ps1','.html','.css');" ^
  "$needles = @($env:USERNAME, $env:USERPROFILE) | Where-Object { $_ -and $_.Trim() -ne '' };" ^
  "$matches = New-Object System.Collections.Generic.List[string];" ^
  "Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object { $textExtensions -contains $_.Extension.ToLowerInvariant() } | ForEach-Object { try { $text = Get-Content -LiteralPath $_.FullName -Raw -ErrorAction Stop; foreach ($needle in $needles) { if ($text -like ('*' + $needle + '*')) { $matches.Add($_.FullName); break } } } catch { } };" ^
  "if ($matches.Count -gt 0) { Write-Host 'Potential private-path matches were found in text/config files:'; $matches | Sort-Object -Unique | ForEach-Object { Write-Host (' - ' + $_) }; Write-Host 'Review those files before uploading the release.'; exit 3 };" ^
  "Write-Host 'Leak scan passed.'"
if errorlevel 1 goto :scan_failed

echo [release] SHA-256:
certutil -hashfile "%ZIP_NAME%" SHA256
if errorlevel 1 goto :hash_failed

echo.
echo [release] Artifact paths:
echo   App image: %APP_IMAGE%
echo   Executable: %APP_EXE%
echo   Zip: %ZIP_PATH%
echo.

if /I "%MODE%"=="upload" goto :upload
if /I "%MODE%"=="publish" goto :upload

echo [release] Finished local release packaging.
exit /b 0

:upload
echo [release] Upload mode requested.
where gh >nul 2>nul
if errorlevel 1 goto :gh_missing
where git >nul 2>nul
if errorlevel 1 goto :git_missing

git diff --quiet --ignore-submodules HEAD --
if errorlevel 1 goto :dirty_tree

git rev-parse --verify "refs/tags/%VERSION%" >nul 2>nul
if errorlevel 1 (
  echo [release] Creating git tag %VERSION%...
  git tag "%VERSION%"
  if errorlevel 1 goto :tag_failed
) else (
  echo [release] Reusing existing git tag %VERSION%.
)

echo [release] Pushing current branch...
git push origin HEAD
if errorlevel 1 goto :push_failed

echo [release] Pushing tag %VERSION%...
git push origin "%VERSION%"
if errorlevel 1 goto :push_failed

gh release view "%VERSION%" >nul 2>nul
if errorlevel 1 (
  echo [release] Creating GitHub release %VERSION%...
  gh release create "%VERSION%" "%ZIP_PATH%" --title "%VERSION%" --notes "Limecraft %VERSION%"
  if errorlevel 1 goto :upload_failed
) else (
  echo [release] Uploading artifact to existing GitHub release %VERSION%...
  gh release upload "%VERSION%" "%ZIP_PATH%" --clobber
  if errorlevel 1 goto :upload_failed
)

echo [release] GitHub release upload finished.
exit /b 0

:cd_failed
echo [release] Failed to enter the repo directory.
echo Suggestion: run this script from inside the Limecraft repository.
exit /b 1

:package_failed
echo [release] Packaging failed.
echo Suggestion: close any running Limecraft window or process that might be locking .\dist, then retry.
exit /b 1

:zip_failed
echo [release] Zip creation failed.
echo Suggestion: delete any stale %ZIP_NAME% file and retry.
exit /b 1

:scan_failed
echo [release] Leak scan failed.
echo Suggestion: inspect the listed text/config files and remove private launcher data before uploading.
exit /b 1

:hash_failed
echo [release] Hash generation failed.
echo Suggestion: confirm certutil is available on this Windows install.
exit /b 1

:gh_missing
echo [release] GitHub CLI was not found.
echo Suggestion: install gh and authenticate it before using upload mode.
exit /b 1

:git_missing
echo [release] Git was not found in PATH.
echo Suggestion: install Git for Windows or run upload mode from a shell where git is available.
exit /b 1

:dirty_tree
echo [release] Upload mode requires a clean git working tree.
echo Suggestion: commit or stash your changes, then rerun release.bat %VERSION% upload.
exit /b 1

:tag_failed
echo [release] Failed to create git tag %VERSION%.
echo Suggestion: delete or inspect the existing tag state, then retry.
exit /b 1

:push_failed
echo [release] Git push failed.
echo Suggestion: verify your remote, credentials, and branch permissions before retrying upload mode.
exit /b 1

:upload_failed
echo [release] GitHub release upload failed.
echo Suggestion: confirm gh auth status and that you have permission to publish releases for the repo.
exit /b 1
