#!/system/bin/sh
# 开机自动恢复「弹幕伴侣」的无障碍绑定（带自愈，常驻）
# 放在 /data/adb/service.d/ 下，Magisk 开机以 root 身份执行。
#
# 背景：这台电视的 HomeGuard 守护会周期性重写 enabled_accessibility_services，
# 把我们这条挤掉；列表里没有我们 → 无障碍服务被系统停掉 → 遥控器菜单键手势失灵、
# 弹幕也不出。之前用 `( ... ) &` 起后台循环，实测会被回收，一死就永久失联。
# 这里改成前台常驻循环（service.d 的脚本本身已由 Magisk 后台执行），
# 并且除了"列表里没我"之外，还会检测"列表里有我但进程没了"，强制重建绑定。

export PATH=/system/bin:/system/xbin:/sbin:$PATH

ME="tv.yaohui.danmu/tv.yaohui.danmu.DanmuA11yService"
PKG="tv.yaohui.danmu"
LOG=/data/local/tmp/danmu-a11y.log
INTERVAL=20

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

# 从冒号分隔的列表里去掉我们自己
strip_me() {
  echo "$1" | tr ':' '\n' | awk -v m="$ME" 'BEGIN{ORS=""} $0!="" && $0!=m { if (n++) printf ":"; printf "%s", $0 }'
}

# 等系统启动完成（最多 3 分钟）
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do
  sleep 1
  i=$((i + 1))
done
sleep 6

log "guard start (pid $$)"
missing=0
dead=0
beat=0

while true; do
  CUR=$(settings get secure enabled_accessibility_services 2>/dev/null)

  case "$CUR" in
    *"$ME"*)
      missing=0
      # 列表里有我们，但进程不在：连续 3 次确认后强制重建绑定（等价于重启服务）
      if pidof "$PKG" >/dev/null 2>&1; then
        dead=0
      else
        dead=$((dead + 1))
        if [ $dead -ge 3 ]; then
          BARE=$(strip_me "$CUR")
          if [ -z "$BARE" ]; then
            settings put secure enabled_accessibility_services "$ME"
          else
            settings put secure enabled_accessibility_services "$BARE"
            sleep 2
            settings put secure enabled_accessibility_services "$BARE:$ME"
          fi
          settings put secure accessibility_enabled 1
          log "restart: process gone 3x, rebind [$CUR]"
          dead=0
        fi
      fi
      ;;
    *)
      missing=$((missing + 1))
      case "$CUR" in
        ""|null) NEW="$ME" ;;
        *)       NEW="$CUR:$ME" ;;
      esac
      settings put secure enabled_accessibility_services "$NEW"
      settings put secure accessibility_enabled 1
      AFTER=$(settings get secure enabled_accessibility_services 2>/dev/null)
      case "$AFTER" in
        *"$ME"*) log "rebound: [$CUR] -> [$AFTER]" ;;
        *)       log "rebind FAILED: [$CUR] -> [$AFTER]" ;;
      esac
      ;;
  esac

  # 心跳：每 10 分钟记一笔，方便事后看它到底活到什么时候
  beat=$((beat + 1))
  if [ $beat -ge 30 ]; then
    beat=0
    log "alive (missing=$missing dead=$dead)"
  fi

  # 日志别无限长
  if [ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 65536 ]; then
    tail -c 32768 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
  fi

  sleep $INTERVAL
done
