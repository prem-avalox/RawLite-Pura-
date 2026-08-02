#!/usr/bin/env bash
set -euo pipefail

readonly PRODUCTION_PACKAGE="com.example.rawlite"
readonly DEVICE_CAPTURE_DIR="/sdcard/Android/data/${PRODUCTION_PACKAGE}/files/Movies"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"
apk_path="${1:-${repo_dir}/app/build/outputs/apk/debug/app-debug.apk}"
backup_root="${RAWLITE_BACKUP_ROOT:-$(dirname "${repo_dir}")/RawLite_device_backups}"
adb_bin="${ADB_BIN:-$(command -v adb || true)}"

if [[ -z "${adb_bin}" ]]; then
    echo "ERROR: adb no está disponible."
    exit 1
fi
if [[ ! -f "${apk_path}" ]]; then
    echo "ERROR: no existe el APK: ${apk_path}"
    exit 1
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    device_serial="${ANDROID_SERIAL}"
else
    device_count="$("${adb_bin}" devices | awk 'NR > 1 && $2 == "device" {count += 1} END {print count + 0}')"
    if [[ "${device_count}" -ne 1 ]]; then
        echo "ERROR: conecta exactamente un dispositivo o define ANDROID_SERIAL."
        exit 1
    fi
    device_serial="$("${adb_bin}" devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
fi

adb=("${adb_bin}" -s "${device_serial}")
device_state="$("${adb[@]}" get-state 2>/dev/null || true)"
if [[ "${device_state}" != "device" ]]; then
    echo "ERROR: el dispositivo ${device_serial} no está disponible y desbloqueado para ADB."
    exit 1
fi

device_name="$("${adb[@]}" shell getprop ro.product.device | tr -d '\r')"
if [[ "${device_name}" != "oriole" ]]; then
    echo "ERROR: el dispositivo es ${device_name}; esta instalación está limitada al Pixel 6 (oriole)."
    exit 1
fi

running_pid="$("${adb[@]}" shell pidof "${PRODUCTION_PACKAGE}" 2>/dev/null | tr -d '\r' || true)"
if [[ -n "${running_pid}" ]]; then
    echo "ERROR: RawLite está ejecutándose (PID ${running_pid}). Ciérrala antes de instalar."
    echo "No se forzó su cierre para evitar interrumpir una grabación."
    exit 1
fi

remote_inventory="$("${adb[@]}" shell "if [ -d '${DEVICE_CAPTURE_DIR}' ]; then find '${DEVICE_CAPTURE_DIR}' -type f -exec stat -c '%s' {} +; fi" | tr -d '\r')"
remote_count="$(awk 'NF {count += 1} END {print count + 0}' <<<"${remote_inventory}")"
remote_bytes="$(awk 'NF {bytes += $1} END {printf \"%.0f\", bytes + 0}' <<<"${remote_inventory}")"

if [[ "${remote_count}" -gt 0 ]]; then
    timestamp="$(date '+%Y%m%d_%H%M%S')"
    final_backup="${backup_root}/RawLite_${timestamp}_${device_serial}"
    incomplete_backup="${final_backup}.incomplete"
    mkdir -p "${incomplete_backup}"

    echo "Respaldando ${remote_count} archivo(s), ${remote_bytes} bytes..."
    "${adb[@]}" pull "${DEVICE_CAPTURE_DIR}/." "${incomplete_backup}/"

    local_inventory="$(find "${incomplete_backup}" -type f -exec stat -f '%z' {} + 2>/dev/null || find "${incomplete_backup}" -type f -exec stat -c '%s' {} +)"
    local_count="$(awk 'NF {count += 1} END {print count + 0}' <<<"${local_inventory}")"
    local_bytes="$(awk 'NF {bytes += $1} END {printf \"%.0f\", bytes + 0}' <<<"${local_inventory}")"
    if [[ "${local_count}" != "${remote_count}" || "${local_bytes}" != "${remote_bytes}" ]]; then
        echo "ERROR: el respaldo no coincide con el dispositivo."
        echo "Origen: ${remote_count} archivos / ${remote_bytes} bytes"
        echo "Copia:  ${local_count} archivos / ${local_bytes} bytes"
        echo "La copia incompleta se conservó en ${incomplete_backup}. No se instaló nada."
        exit 1
    fi

    mv "${incomplete_backup}" "${final_backup}"
    {
        echo "device_serial=${device_serial}"
        echo "source=${DEVICE_CAPTURE_DIR}"
        echo "files=${remote_count}"
        echo "bytes=${remote_bytes}"
        echo "created=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    } > "${final_backup}/BACKUP_VERIFIED.txt"
    echo "Respaldo verificado: ${final_backup}"
else
    echo "No hay tomas en el almacenamiento privado de RawLite que respaldar."
fi

echo "Instalando sin desinstalar ni borrar datos..."
"${adb[@]}" install -r "${apk_path}"
installed_version="$("${adb[@]}" shell dumpsys package "${PRODUCTION_PACKAGE}" | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r')"
if [[ -z "${installed_version}" ]]; then
    echo "ERROR: ADB terminó, pero el paquete de producción no aparece instalado."
    exit 1
fi
echo "RawLite ${installed_version} instalada de forma segura en ${device_serial}."
