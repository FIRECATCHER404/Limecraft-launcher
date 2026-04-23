@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
set "STAGE_DIR=dist-stage"

powershell -ExecutionPolicy Bypass -Command "if (Test-Path '.\%STAGE_DIR%') { Remove-Item -LiteralPath '.\%STAGE_DIR%' -Recurse -Force }"
if errorlevel 1 exit /b 1

powershell -ExecutionPolicy Bypass -File ".\tools\package-exe.ps1" -Name Limecraft -Dest %STAGE_DIR%
if errorlevel 1 exit /b 1

if exist ".\dist" (
    powershell -ExecutionPolicy Bypass -Command "Remove-Item -LiteralPath '.\dist' -Recurse -Force"
    if errorlevel 1 (
        echo Failed to replace .\dist. The existing packaged app is probably still open.
        echo The fresh build is available in .\%STAGE_DIR%\Limecraft
        exit /b 1
    )
)

powershell -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Path '.\dist' -Force | Out-Null; Move-Item -LiteralPath '.\%STAGE_DIR%\Limecraft' -Destination '.\dist\Limecraft'; if (Test-Path '.\%STAGE_DIR%') { Remove-Item -LiteralPath '.\%STAGE_DIR%' -Recurse -Force }"
