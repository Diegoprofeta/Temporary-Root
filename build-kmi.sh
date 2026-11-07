#!/usr/bin/env bash
# One-off: rebuild temproot.ko for a given KMI using the DDK container, per Makefile recipe.
set -euo pipefail
cd /mnt/c/Data/Projects/TemporaryRoot
KMI="${1:-android13-5.15}"
IMG="ghcr.io/ylarod/ddk-min:${KMI}-20260828"

podman pull "$IMG"

podman run --rm --pid=host --network=none -v "$(pwd)":/proj -e KMI="$KMI" "$IMG" \
    bash -c 'set -e; cd /proj/lkm; make -j$(nproc); llvm-objcopy --strip-unneeded -R .comment -R .note.gnu.property -R .note.Linux -R .note.GNU-stack -R .BTF -R .BTF.base -R .llvm_addrsig -R .hyp.text -R .hyp.bss -R .hyp.rodata -R .hyp.event_ids -R .hyp.patchable_function_entries -R .hyp.data temproot.ko; mkdir -p /proj/app/src/main/jni/ko; cp temproot.ko /proj/app/src/main/jni/ko/temproot-$KMI.ko; make clean; echo DONE_KO'

ls -l "app/src/main/jni/ko/temproot-$KMI.ko"
echo BUILD_SCRIPT_OK
