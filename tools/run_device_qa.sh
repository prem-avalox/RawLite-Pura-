#!/usr/bin/env bash
set -euo pipefail

readonly PRODUCTION_PACKAGE="com.example.rawlite"
readonly QA_PACKAGE="com.example.rawlite.qa"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"
adb_bin="${ADB_BIN:-$(command -v adb || true)}"

if [[ -z "${adb_bin}" ]]; then
    echo "ERROR: adb no está disponible."
    exit 1
fi
if [[ "${PRODUCTION_PACKAGE}" == "${QA_PACKAGE}" ]]; then
    echo "ERROR: el paquete QA no está aislado de producción."
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

device_name="$("${adb_bin}" -s "${device_serial}" shell getprop ro.product.device | tr -d '\r')"
if [[ "${device_name}" != "oriole" ]]; then
    echo "ERROR: las pruebas nativas requieren Pixel 6 (oriole), no ${device_name}."
    exit 1
fi

echo "Ejecutando QA aislado como ${QA_PACKAGE}; ${PRODUCTION_PACKAGE} no será instalado ni desinstalado."
cd "${repo_dir}"
ANDROID_SERIAL="${device_serial}" ./gradlew :qa:connectedDebugAndroidTest
