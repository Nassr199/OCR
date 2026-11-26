#!/usr/bin/env bash
# CI: runs the very same Vulkan code + SPIR-V on Linux (Mesa lavapipe, a conformant Vulkan
# implementation) and compares every pixel with a CPU port of ScanMask.classify.
set -euo pipefail
cd "$(dirname "$0")/.."
sudo apt-get -qq update >/dev/null
sudo apt-get -qq install -y mesa-vulkan-drivers libvulkan-dev >/dev/null
gcc -std=c11 -O2 -Wall -Wextra -I native -o /tmp/vk_test native/vk_test.c native/vk_classify.c -lvulkan
/tmp/vk_test
