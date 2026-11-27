#!/system/bin/sh
# v7 vendor OIS poller. Must run as root (KernelSU su), because the hall node
# is 0444 system:system and KernelSU hides `su` from non-authorized apps.
# One line per sample: "<boottime_seconds>|<8 comma-separated ADC values>".
# /proc/uptime is CLOCK_BOOTTIME, the same time base as Camera2 REALTIME
# timestamps on this device, so lines align with SENSOR_TIMESTAMP directly.
# A node read costs ~125 ms in the driver, so this is ~8 Hz telemetry.
NODE=/sys/class/camera/ois/ois_hall_position
i=0
while [ "$i" -lt 4000 ]; do
  read -r u rest < /proc/uptime
  h=$(cat "$NODE" 2>/dev/null)
  echo "$u|$h"
  i=$((i + 1))
done
