#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TOOLS_DIR="${VERIFY_DIR}/.tools"
INSTALL_DIR="${TOOLS_DIR}/z3-4.15.2"
CHECK_ONLY=false

if [[ "${1:-}" == "--check" ]]; then
  CHECK_ONLY=true
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--check]" >&2
  exit 2
fi

if [[ -x "${INSTALL_DIR}/bin/z3" ]]; then
  version="$(${INSTALL_DIR}/bin/z3 --version)"
  if [[ "${version}" == *"4.15.2"* ]]; then
    echo "Using ${version} from ${INSTALL_DIR}"
    exit 0
  fi
  echo "Existing local Z3 has unexpected version: ${version}" >&2
  exit 2
fi

if [[ "${CHECK_ONLY}" == true ]]; then
  echo "COULD-NOT-EVALUATE: pinned Z3 is not installed at ${INSTALL_DIR}" >&2
  exit 2
fi

os="$(uname -s)"
arch="$(uname -m)"
case "${os}/${arch}" in
  Darwin/arm64)
    archive="z3-4.15.2-arm64-osx-13.7.6.zip"
    checksum="fdc797b046a8b1e030200d30c4c32724fc01be359c3ab88a47ce03655cf6efa4"
    ;;
  Darwin/x86_64)
    archive="z3-4.15.2-x64-osx-13.7.6.zip"
    checksum="2c0fb34703660cb3c182c84d702674f52b56f9454cdc6c30d58611a1c2d69851"
    ;;
  Linux/x86_64)
    archive="z3-4.15.2-x64-glibc-2.39.zip"
    checksum="85d2da1bf440fca3288874c2a06e23f96d09befcc21b5a7489fe0fa40444e685"
    ;;
  *)
    echo "COULD-NOT-EVALUATE: unsupported Z3 bootstrap platform ${os}/${arch}" >&2
    exit 2
    ;;
esac

for command_name in curl unzip; do
  command -v "${command_name}" >/dev/null 2>&1 || {
    echo "COULD-NOT-EVALUATE: missing command ${command_name}" >&2
    exit 2
  }
done

mkdir -p "${TOOLS_DIR}"
temp_dir="$(mktemp -d)"
trap 'rm -rf "${temp_dir}"' EXIT

url="https://github.com/Z3Prover/z3/releases/download/z3-4.15.2/${archive}"
curl -sSfL -o "${temp_dir}/${archive}" "${url}"

actual_checksum="$(shasum -a 256 "${temp_dir}/${archive}" | awk '{print $1}')"
if [[ "${actual_checksum}" != "${checksum}" ]]; then
  echo "COULD-NOT-EVALUATE: Z3 archive checksum mismatch" >&2
  exit 2
fi

unzip -q "${temp_dir}/${archive}" -d "${temp_dir}/unpacked"
extracted_dir="${temp_dir}/unpacked/${archive%.zip}"
if [[ ! -x "${extracted_dir}/bin/z3" ]]; then
  echo "COULD-NOT-EVALUATE: Z3 archive did not contain bin/z3" >&2
  exit 2
fi

mv "${extracted_dir}" "${INSTALL_DIR}"
"${INSTALL_DIR}/bin/z3" --version
