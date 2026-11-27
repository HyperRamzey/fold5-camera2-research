#!/system/bin/sh
# v7 vendor OIS aux-node poller (root). Records the one-shot vendor nodes
# while the camera streams so their content can be inspected post-run.
i=0
while [ "$i" -lt 4000 ]; do
  read -r u rest < /proc/uptime
  e=$(cat /sys/class/camera/ois/ois_exif 2>/dev/null)
  g=$(cat /sys/class/camera/ois/ois_gain_rear 2>/dev/null)
  r=$(cat /sys/class/camera/ois/ois_rawdata 2>/dev/null)
  a=$(cat /sys/class/camera/ois/adc 2>/dev/null)
  echo "$u|exif=$e|gain=$g|raw=$r|adc=$a"
  i=$((i + 1))
done
