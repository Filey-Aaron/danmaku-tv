#!/system/bin/sh
# 开机自动恢复「弹幕伴侣」的无障碍绑定
# 放在 /data/adb/service.d/ 下，Magisk 开机以 root 身份执行。
#
# 背景：这台电视重启后 enabled_accessibility_services 里的弹幕服务会被挤掉
# （只剩 Aerial 桌面守护），服务不跑 → 遥控器菜单键手势失效、弹幕也不出。
# 这里在开机完成后追加回来，并且长期盯着（前 15 分钟 15 秒查一次，
# 之后每 5 分钟查一次），防被别的守护重写。

export PATH=/system/bin:/system/xbin:/sbin:$PATH

ME="tv.yaohui.danmu/tv.yaohui.danmu.DanmuA11yService"
LOG=/data/local/tmp/danmu-a11y.log

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

(
  # 等系统启动完成（最多 3 分钟）
  i=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do
    sleep 1
    i=$((i + 1))
  done
  sleep 6

  n=0
  while true; do
    CUR=$(settings get secure enabled_accessibility_services 2>/dev/null)
    case "$CUR" in
      *"$ME"*) : ;;
      *)
        if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then
          settings put secure enabled_accessibility_services "$ME"
        else
          settings put secure enabled_accessibility_services "$CUR:$ME"
        fi
        settings put secure accessibility_enabled 1
        log "rebound: [$CUR] -> [$(settings get secure enabled_accessibility_services)]"
        ;;
    esac
    # 前 15 分钟盯紧点（开机那阵子最容易被动），之后放松到 5 分钟一次
    if [ $n -lt 60 ]; then
      sleep 15
    else
      sleep 300
    fi
    n=$((n + 1))
  done
) &
