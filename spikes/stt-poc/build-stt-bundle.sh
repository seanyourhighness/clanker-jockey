#!/usr/bin/env bash
# Build the ClankerJockey STT bundle: whisper-server (Linux x86_64) + ggml-small.en.bin
#
# Output layout (unpack into <gamedir>/clankerjockey/stt/):
#   bin/whisper-server + libwhisper.so.1 + libggml*.so.0
#   models/ggml-small.en.bin
#
# Requirements: git, cmake, ninja (optional), g++, ~500MB disk, internet.
# The whisper.cpp checkout lives at .deps/whisper.cpp (gitignored).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/.deps/whisper.cpp"
OUT="${1:-/tmp/clankerjockey-stt-bundle}"
MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin"
MODEL_NAME="ggml-small.en.bin"

echo "==> whisper.cpp checkout"
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git "$SRC"
fi
cd "$SRC"
git fetch --depth 1 origin 2>/dev/null || true

echo "==> building whisper-server (Linux, CPU)"
cmake -B build -DCMAKE_BUILD_TYPE=Release \
  -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_SERVER=ON \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_CURL=OFF -DGGML_NATIVE=ON
cmake --build build --target whisper-server -j"$(nproc)"

echo "==> downloading model"
mkdir -p models
[ -f "models/$MODEL_NAME" ] || curl -sL -o "models/$MODEL_NAME" "$MODEL_URL"

echo "==> assembling bundle at $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/models"
cp build/bin/whisper-server "$OUT/bin/"
cp build/bin/libwhisper.so.1.9.3 "$OUT/bin/" 2>/dev/null || cp build/bin/libwhisper.so.1 "$OUT/bin/"
cp build/bin/libggml.so.0.22.0 "$OUT/bin/" 2>/dev/null || true
cp build/bin/libggml-base.so.0.22.0 "$OUT/bin/" 2>/dev/null || true
cp build/bin/libggml-cpu.so.0.22.0 "$OUT/bin/" 2>/dev/null || true
# symlinks so the binary finds its libs (LD_LIBRARY_PATH=bin/ in SttEngine)
cd "$OUT/bin"
ln -sf libwhisper.so.1.9.3 libwhisper.so.1
ln -sf libggml.so.0.22.0 libggml.so.0
ln -sf libggml-base.so.0.22.0 libggml-base.so.0
ln -sf libggml-cpu.so.0.22.0 libggml-cpu.so.0
cd "$SRC"
cp "models/$MODEL_NAME" "$OUT/models/"

echo "==> done"
du -sh "$OUT"
echo "Unpack into <gamedir>/clankerjockey/stt/ (bin/ + models/)."

# ---------------------------------------------------------------------------
# Windows bundle (optional, cross-compiled from Linux/WSL via mingw-w64)
#   ./build-stt-bundle.sh /tmp/clankerjockey-stt-bundle /tmp/clankerjockey-stt-bundle-win
# ---------------------------------------------------------------------------
if [ -n "${2:-}" ] && command -v x86_64-w64-mingw32-g++ >/dev/null 2>&1; then
  WIN_OUT="$2"
  echo "==> building whisper-server.exe (mingw-w64 cross-compile)"
  # _WIN32_WINNT=0x0601 keeps the Windows 7 API level: the ggml-cpu thread
  # power-throttling block (THREAD_POWER_THROTTLING_STATE, Win8.1+) is not in
  # the Ubuntu mingw-w64 headers and would fail to compile otherwise.
  cmake -B build-win -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_SYSTEM_NAME=Windows \
    -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
    -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ \
    -DCMAKE_C_FLAGS="-D_WIN32_WINNT=0x0601" \
    -DCMAKE_CXX_FLAGS="-D_WIN32_WINNT=0x0601" \
    -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_SERVER=ON \
    -DWHISPER_BUILD_TESTS=OFF -DWHISPER_CURL=OFF -DGGML_NATIVE=OFF
  cmake --build build-win --target whisper-server -j"$(nproc)"

  echo "==> assembling Windows bundle at $WIN_OUT"
  rm -rf "$WIN_OUT"
  mkdir -p "$WIN_OUT/bin" "$WIN_OUT/models"
  cp build-win/bin/whisper-server.exe "$WIN_OUT/bin/"
  # mingw runtime DLLs (whisper/ggml are statically linked into the .exe)
  RT="$(x86_64-w64-mingw32-g++ -print-search-dirs 2>/dev/null | head -1)"
  # NOTE: libwinpthread-1.dll (a transitive libgomp/OpenMP dep) lives under
  # /usr/x86_64-w64-mingw32/lib on Ubuntu, NOT in the gcc dir — search both,
  # or the Windows bundle fails to launch (verified 2026-09-07).
  for dll in libgcc_s_seh-1.dll libgomp-1.dll libstdc++-6.dll libwinpthread-1.dll; do
    found="$(find /usr/lib/gcc/x86_64-w64-mingw32 /usr/x86_64-w64-mingw32 -name "$dll" 2>/dev/null | head -1)"
    [ -n "$found" ] && cp "$found" "$WIN_OUT/bin/" || echo "WARN: $dll not found"
  done
  cp "models/$MODEL_NAME" "$WIN_OUT/models/"
  du -sh "$WIN_OUT"
  echo "Unpack into <gamedir>/clankerjockey/stt/ (bin/ + models/). Verify once on a Windows box."
fi
