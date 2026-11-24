@echo off
setlocal
rem 改成你电视的 IP（电视: 设置 - 关于 - 状态信息）
set TV=192.168.1.159:5555

echo Connecting to TV %TV% ...
adb connect %TV% >nul 2>&1
adb -s %TV% wait-for-device

echo Opening Danmu settings ...
adb -s %TV% shell am start -n tv.yaohui.danmu/.SettingsActivity
if errorlevel 1 (
  echo.
  echo FAILED. Check that the TV is on and on the same LAN.
  pause
  exit /b 1
)
echo.
echo Done. Window should appear on the TV now.
timeout /t 2 >nul
