#!/system/bin/sh
# Change Reader root watcher. $1=folder $2=include hidden (1/0) $3=max folders
R="$1"; H="$2"; MAX="$3"
T=""
if command -v inotifyd >/dev/null 2>&1; then T="inotifyd"
elif toybox 2>/dev/null | tr ' ' '\n' | grep -qx inotifyd; then T="toybox inotifyd"
else
  for B in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /sbin/.magisk/busybox/busybox /system/xbin/busybox /system/bin/busybox; do
    if [ -x "$B" ] && "$B" --list 2>/dev/null | grep -qx inotifyd; then T="$B inotifyd"; break; fi
  done
fi
if [ -z "$T" ]; then echo "#INFO tool=none"; exit 0; fi
echo "#INFO tool=$T"
L="/data/local/tmp/changereader_dirs.$$"
if [ "$H" = "1" ]; then find "$R" -type d 2>/dev/null | head -n "$MAX" > "$L"
else find "$R" -type d 2>/dev/null | grep -v '/\.' | head -n "$MAX" > "$L"; fi
N=$(wc -l < "$L" | tr -d ' ')
echo "#INFO dirs=$N"
set --
while IFS= read -r D; do set -- "$@" "$D:ncwdmyeDM"; done < "$L"
rm -f "$L"
echo "#INFO ready"
exec $T - "$@"
