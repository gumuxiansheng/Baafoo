@echo off
rem ============================================================
rem  gates-toolkit setup-gates.cmd - Windows one-shot installer
rem  PS5 compatible: invokes system powershell.exe (5.1) to run setup-gates.ps1
rem
rem  Usage:
rem    setup-gates.cmd -Target C:\my\project -ProjectType spring-boot
rem    setup-gates.cmd -Target C:\my\project -ProjectType multi-module -SqlModule baafoo-server
rem
rem  All arguments are passed through to setup-gates.ps1
rem ============================================================
setlocal
chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%setup-gates.ps1"

if not exist "%PS_SCRIPT%" (
    echo [ERROR] setup-gates.ps1 not found: %PS_SCRIPT%
    exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
exit /b %ERRORLEVEL%
