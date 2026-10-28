#!/usr/bin/env bash
# In-memory production-renderer fixture. No provider, settings, DB, or user content access.
set -euo pipefail
if (( $# < 3 )); then
  echo "Usage: $0 baseline|optimized MAIN_APK TEST_APK [output-directory]" >&2
  exit 2
fi
label="$1"
main_apk="$2"
test_apk="$3"
output_dir="${4:-artifacts/chat-performance/$label}"
adb_path="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
serial="${SERIAL:-392288d7}"
package="${PACKAGE:-app.amber.agent.graphite}"
runner="$package.test/app.amber.agent.ChatDeviceFixtureRunner"
case "$label" in
  baseline) optimized=false ;;
  optimized) optimized=true ;;
  *) echo "Expected baseline or optimized" >&2; exit 2 ;;
esac
# A labelled APK pair may need the same layout strategy on both sides.
# CHAT_RENDER_LAYOUT=true forces the production streamingTailLayout for both.
case "${CHAT_RENDER_LAYOUT:-}" in
  true|optimized) optimized=true ;;
  false|animate) optimized=false ;;
  "") ;;
  *) echo "CHAT_RENDER_LAYOUT must be true/false" >&2; exit 2 ;;
esac
# macOS 27's arm64 adb USB backend can abort inside libunwind. ADB_ARCH=native opts out.
adb_cmd=("$adb_path")
if [[ "$(uname -s)" == Darwin && "${ADB_ARCH:-x86_64}" == x86_64 ]]; then
  adb_cmd=(arch -x86_64 "$adb_path")
fi
run_adb() { "${adb_cmd[@]}" -s "$serial" "$@"; }
mkdir -p "$output_dir"
run_adb get-state
# Scope the display cap to this measurement and restore both original values.
# A window hint alone is ignored by MIUI's touch boost on this device.
# SKIP_REFRESH_CAP=true leaves system refresh settings alone (emulator smoke).
skip_refresh_cap=false
case "${SKIP_REFRESH_CAP:-}" in
  true|1|yes) skip_refresh_cap=true ;;
esac
# settings get prints "null" when unset. Never `put` an empty string: that
# deletes the key on this panel (4c left user_refresh_rate/miui_refresh_rate
# unset after a restore that treated a blank/whitespace capture as a value).
read_setting() {
  local ns="$1" key="$2"
  local raw
  raw="$(run_adb shell settings get "$ns" "$key" | tr -d '\r' | tail -n 1)"
  raw="${raw#"${raw%%[![:space:]]*}"}"
  raw="${raw%"${raw##*[![:space:]]}"}"
  if [[ -z "$raw" || "$raw" == null || "$raw" == none ]]; then
    printf 'null'
  else
    printf '%s' "$raw"
  fi
}
normalize_setting() {
  local v="$1"
  if [[ "$v" == null || "$v" == none || -z "$v" ]]; then
    printf 'null'
    return
  fi
  if [[ "$v" =~ ^[0-9]+[.]0+$ ]]; then
    printf '%s' "${v%%.*}"
    return
  fi
  printf '%s' "$v"
}
write_setting() {
  local ns="$1" key="$2" value="$3"
  if [[ "$value" == null || -z "$value" ]]; then
    run_adb shell settings delete "$ns" "$key" > /dev/null
  else
    run_adb shell settings put "$ns" "$key" "$value"
  fi
}
peak_before="$(read_setting system peak_refresh_rate)"
min_before="$(read_setting system min_refresh_rate)"
# MIUI keeps its own per-panel preference; peak/min alone are ignored once set.
user_rate_before="$(read_setting secure user_refresh_rate)"
miui_rate_before="$(read_setting secure miui_refresh_rate)"
printf 'peak_refresh_rate=%s\nmin_refresh_rate=%s\nuser_refresh_rate=%s\nmiui_refresh_rate=%s\nskip_refresh_cap=%s\n' \
  "$peak_before" "$min_before" "$user_rate_before" "$miui_rate_before" "$skip_refresh_cap" \
  > "$output_dir/refresh-before.txt"
restore_refresh() {
  if [[ "$skip_refresh_cap" == true ]]; then return 0; fi
  # peak/min first, then the MIUI keys. Deleting min_refresh_rate can clear
  # user/miui on this panel, so write those last and retry once.
  write_setting system peak_refresh_rate "$peak_before"
  write_setting system min_refresh_rate "$min_before"
  write_setting secure user_refresh_rate "$user_rate_before"
  write_setting secure miui_refresh_rate "$miui_rate_before"
  local user_now miui_now
  user_now="$(read_setting secure user_refresh_rate)"
  miui_now="$(read_setting secure miui_refresh_rate)"
  if [[ "$(normalize_setting "$user_now")" != "$(normalize_setting "$user_rate_before")" || \
        "$(normalize_setting "$miui_now")" != "$(normalize_setting "$miui_rate_before")" ]]; then
    write_setting secure user_refresh_rate "$user_rate_before"
    write_setting secure miui_refresh_rate "$miui_rate_before"
  fi
}
verify_refresh_restored() {
  local peak min user miui
  peak="$(read_setting system peak_refresh_rate)"
  min="$(read_setting system min_refresh_rate)"
  user="$(read_setting secure user_refresh_rate)"
  miui="$(read_setting secure miui_refresh_rate)"
  printf 'peak_refresh_rate=%s\nmin_refresh_rate=%s\nuser_refresh_rate=%s\nmiui_refresh_rate=%s\nskip_refresh_cap=%s\n' \
    "$peak" "$min" "$user" "$miui" "$skip_refresh_cap" \
    > "$output_dir/refresh-after.txt"
  if [[ "$skip_refresh_cap" == true ]]; then return 0; fi
  if [[ "$(normalize_setting "$peak")" != "$(normalize_setting "$peak_before")" || \
        "$(normalize_setting "$min")" != "$(normalize_setting "$min_before")" || \
        "$(normalize_setting "$user")" != "$(normalize_setting "$user_rate_before")" || \
        "$(normalize_setting "$miui")" != "$(normalize_setting "$miui_rate_before")" ]]; then
    echo "Refresh restore mismatch: before peak=$peak_before min=$min_before user=$user_rate_before miui=$miui_rate_before after peak=$peak min=$min user=$user miui=$miui" >&2
    return 1
  fi
}
trap restore_refresh EXIT
apply_refresh_cap() {
  if [[ "$skip_refresh_cap" == true ]]; then return 0; fi
  # Re-assert for every profile: MIUI can promote the panel back to 120 Hz
  # between instrumentation processes even while the system cap is set.
  run_adb shell settings put system peak_refresh_rate 60.0
  run_adb shell settings put system min_refresh_rate 60.0
  run_adb shell settings put secure user_refresh_rate 60
  run_adb shell settings put secure miui_refresh_rate 60
}
apply_refresh_cap
run_adb shell input keyevent KEYCODE_WAKEUP
# A secure keyguard needs the owner; this command only dismisses an unsecured keyguard.
run_adb shell wm dismiss-keyguard
run_adb shell dumpsys window policy > "$output_dir/window-policy.txt"
run_adb shell dumpsys display > "$output_dir/display.txt"
run_adb shell getprop ro.build.fingerprint > "$output_dir/fingerprint.txt"
shasum -a 256 "$main_apk" "$test_apk" > "$output_dir/apk-sha256.txt"
install_apk() {
  local apk="$1" log="$2"
  run_adb shell input keyevent KEYCODE_WAKEUP || true
  if run_adb install -r -t "$apk" | tee "$log"; then
    return 0
  fi
  # Switching to a new package on this panel can raise
  # INSTALL_FAILED_USER_RESTRICTED if the USB-install confirm is
  # missed (4d baseline androidTest). Wake and retry once.
  echo "Retrying install of $apk after wakeup" | tee -a "$log"
  sleep 2
  run_adb shell input keyevent KEYCODE_WAKEUP || true
  run_adb install -r -t "$apk" | tee -a "$log"
}
install_apk "$main_apk" "$output_dir/install-main.txt"
install_apk "$test_apk" "$output_dir/install-test.txt"
# Both runs use the same temporary display cap, font, content and gestures.
trace_running=false
stop_trace() {
  if [[ "$trace_running" == true ]]; then
    run_adb shell atrace --async_stop -z > "$output_dir/$profile.ctrace" || true
    trace_running=false
  fi
}
cleanup() { stop_trace; restore_refresh; }
trap cleanup EXIT
for profile in ${PROFILES:-short chinese mixed}; do
  # Optional controlled-temperature sampling. Each wait is short enough to
  # remain interruptible, and the refresh restoration trap stays installed.
  if [[ -n "${CHAT_PERF_TEMPERATURE_MAX:-}" ]]; then
    [[ "$CHAT_PERF_TEMPERATURE_MAX" =~ ^[0-9]+$ ]] || { echo "Invalid temperature limit" >&2; exit 2; }
    cooled=false
    for ((attempt=0; attempt<30; attempt++)); do
      temperature="$(run_adb shell dumpsys battery | sed -n 's/.*temperature: *//p' | tr -d '\r')"
      [[ "$temperature" =~ ^[0-9]+$ ]] || { echo "Device temperature unavailable" >&2; exit 1; }
      cpu_cool=true
      cpu_temperature=not-requested
      if [[ -n "${CHAT_PERF_CPU_TEMPERATURE_MAX:-}" ]]; then
        [[ "$CHAT_PERF_CPU_TEMPERATURE_MAX" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo "Invalid CPU temperature limit" >&2; exit 2; }
        cpu_temperature="$(run_adb shell dumpsys thermalservice | sed -n '/Current temperatures from HAL:/,/Current cooling devices from HAL:/p' | sed -n 's/.*mValue=\([0-9.]*\), mType=0, mName=CPU0.*/\1/p' | tr -d '\r')"
        [[ "$cpu_temperature" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo "CPU0 temperature unavailable" >&2; exit 1; }
        if ! awk -v actual="$cpu_temperature" -v limit="$CHAT_PERF_CPU_TEMPERATURE_MAX" 'BEGIN { exit !(actual <= limit) }'; then cpu_cool=false; fi
      fi
      printf '%s battery=%s cpu0=%s\n' "$(date -u +%FT%TZ)" "$temperature" "$cpu_temperature" >> "$output_dir/$profile-cooling.txt"
      if (( temperature <= CHAT_PERF_TEMPERATURE_MAX )) && [[ "$cpu_cool" == true ]]; then cooled=true; break; fi
      sleep 20
    done
    if [[ "$cooled" != true ]]; then
      echo "Temperature did not recover to requested window" >&2
      exit 1
    fi
  fi
  run_adb shell dumpsys battery > "$output_dir/$profile-battery-before.txt"
  run_adb shell dumpsys thermalservice > "$output_dir/$profile-thermal-before.txt"
  run_adb shell cat /proc/loadavg > "$output_dir/$profile-load-before.txt"
  date -u +%FT%TZ > "$output_dir/$profile-started.txt"
  apply_refresh_cap
  run_adb shell dumpsys display | rg -m1 'mActiveSfDisplayMode' > "$output_dir/$profile-refresh-before.txt" || true
  run_adb shell dumpsys gfxinfo "$package" reset > /dev/null
  if run_adb shell atrace --async_start -b 32768 -a "$package" gfx view sched freq idle dalvik \
    > "$output_dir/$profile-trace-start.txt" 2>&1; then
    trace_running=true
  fi
  # MIUI may freeze a newly started instrumentation process before Application
  # initialization. A single shell foreground kick avoids changing freezer policy.
  ( sleep 1
    run_adb shell am start -W --activity-single-top -n "$package/androidx.activity.ComponentActivity"
  ) > "$output_dir/$profile-foreground-kick.txt" 2>&1 &
  kick_pid=$!
  # Optional supplementary scenario: start from an empty reply so the mixed
  # profile crosses the 3072-unit chunk threshold on screen.
  from_zero_args=()
  if [[ "${CHAT_RENDER_FROM_ZERO:-}" == true ]]; then from_zero_args=(-e amberRenderFromZero true); fi
  run_adb shell am instrument -w ${from_zero_args[@]+"${from_zero_args[@]}"} \
    -e amberRenderPerf true -e amberRenderLabel "$label" \
    -e amberRenderProfile "$profile" -e amberRenderOptimizedLayout "$optimized" \
    -e class app.amber.agent.ChatRenderingDevicePerfTest "$runner" \
    | tee "$output_dir/$profile-instrumentation.txt"
  wait "$kick_pid" || true
  stop_trace
  run_adb shell dumpsys battery > "$output_dir/$profile-battery-after.txt"
  run_adb shell dumpsys thermalservice > "$output_dir/$profile-thermal-after.txt"
  run_adb shell cat /proc/loadavg > "$output_dir/$profile-load-after.txt"
  if ! rg -q 'OK \(1 test\)' "$output_dir/$profile-instrumentation.txt"; then
    echo "Fixture did not complete; inspect the instrumentation result and foreground/device state." >&2
    exit 1
  fi
  run_adb pull "/sdcard/Android/data/$package/files/amber-render-$label-$profile.json" \
    "$output_dir/$profile.json"
  run_adb pull "/sdcard/Android/data/$package/files/amber-render-$label-$profile-gfxinfo.txt" \
    "$output_dir/$profile-gfxinfo.txt"
  run_adb logcat -d -s AmberRenderPerf:I > "$output_dir/$profile-logcat.txt"
done
restore_refresh
verify_refresh_restored
