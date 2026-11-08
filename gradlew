#!/bin/sh
# Gradle wrapper (UNIX). Wrapper jar diunduh otomatis oleh GitHub Actions;
# untuk build lokal, unduh sekali: lihat README.
APP_HOME=$(dirname "$0")
exec gradle "$@" -p "$APP_HOME"
