@echo off
rem ============================================================
rem  gates-toolkit fetch-binaries.cmd - binary download/update entry
rem  PS5 compatible: invokes system powershell.exe (5.1) to run fetch-binaries.ps1
rem
rem  Usage:
rem    fetch-binaries.cmd                # download current platform
rem    fetch-binaries.cmd -Platform all  # download all platforms
rem    fetch-binaries.cmd -Force         # force re-download
rem
rem  All arguments are passed through to fetch-binaries.ps1
rem ============================================================
setlocal
chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%fetch-binaries.ps1"

if not exist "%PS_SCRIPT%" (
    echo [ERROR] fetch-binaries.ps1 not found: %PS_SCRIPT%
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
exit /b %ERRORLEVEL%
