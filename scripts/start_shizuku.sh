#!/bin/sh
# Arranca el servidor de Shizuku en el POS conectado por USB.
# Hay que ejecutarlo después de cada reinicio del POS; POS Network Tester se reconecta solo.
set -e

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

APK_PATH=$("$ADB" shell pm path moe.shizuku.privileged.api | tr -d '\r' | sed 's/^package://')
if [ -z "$APK_PATH" ]; then
    echo "Shizuku no está instalado en el dispositivo." >&2
    exit 1
fi

ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
case "$ABI" in
    arm64*) LIB_DIR=arm64 ;;
    armeabi*) LIB_DIR=arm ;;
    x86_64) LIB_DIR=x86_64 ;;
    x86) LIB_DIR=x86 ;;
    *) echo "ABI no soportada: $ABI" >&2; exit 1 ;;
esac

"$ADB" shell "${APK_PATH%/base.apk}/lib/$LIB_DIR/libshizuku.so"
