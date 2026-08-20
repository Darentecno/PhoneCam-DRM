#!/bin/bash
set -euo pipefail

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "Xcode no está instalado. Instálalo desde la App Store."
  exit 1
fi

if ! command -v xcodegen >/dev/null 2>&1; then
  if ! command -v brew >/dev/null 2>&1; then
    echo "Instala Homebrew desde https://brew.sh y vuelve a ejecutar este script."
    exit 1
  fi
  brew install xcodegen
fi

xcodegen generate
echo "Proyecto generado: PhoneCam.xcodeproj"
