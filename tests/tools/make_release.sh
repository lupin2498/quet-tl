#!/bin/bash
# Xuất bộ cài từ kết quả CI của ĐÚNG commit đang đứng (HEAD):
#   - APK: bản release chưa ký do CI dựng (nhánh ci/android-build) -> zipalign -> ký bằng khoá gốc của ứng dụng;
#   - IPA: bản CI đã ký ad-hoc (nhánh ci/ios).
# Chỉ xuất khi cả sáu nhánh kết quả ci/* đều ghi "sha=<HEAD> ... status=success" (build Android, máy ảo API 24 / 29 / 34,
# iOS Simulator, thuật toán Swift trên Linux) và chứng thư của APK đúng là khoá gốc — ký bằng khoá khác thì máy đã cài
# bản cũ không cập nhật đè được.
#
# Cách dùng: tests/tools/make_release.sh <phiên-bản> <thư-mục-ra> [file-keystore]
#   file-keystore mặc định: android/keystore/quettailieu.jks (không nằm trong repo; mật khẩu / bí danh như build.gradle.kts)
# Cần: git, zipalign, apksigner, aapt (Android build-tools).
set -euo pipefail
cd "$(dirname "$0")/../.."

VER=${1:?thiếu phiên bản, ví dụ 1.5}
OUT=${2:?thiếu thư mục ra}
KS=${3:-android/keystore/quettailieu.jks}
KS_PASS=quettailieu
ALIAS=quettailieu
# SHA-256 chứng thư của khoá gốc (các bản đã phát hành ký bằng khoá này)
WANT_CERT=c624003cb83bb2b567565dcd273d42d9b738642930867b1732d7a4087379aad5

HEAD=$(git rev-parse HEAD)
git fetch -q origin '+refs/heads/ci/*:refs/remotes/origin/ci/*'
bad=0
for b in android-build android-24 android-29 android-34 ios ios-algo; do
    s=$(git log -1 --format=%s "origin/ci/$b" 2>/dev/null || true)
    case "$s" in
        *"sha=$HEAD"*"status=success"*) echo "ci/$b: đạt" ;;
        *) echo "ci/$b CHƯA đạt cho $HEAD: ${s:-không có nhánh}"; bad=1 ;;
    esac
done
[ "$bad" = 0 ] || { echo "Chưa đủ kết quả CI cho commit này — không xuất bộ cài."; exit 1; }

[ -f "$KS" ] || { echo "Không có file khoá ký: $KS"; exit 1; }
mkdir -p "$OUT"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

git show origin/ci/android-build:apk/app-release-unsigned.apk > "$TMP/unsigned.apk"
zipalign -f -p 4 "$TMP/unsigned.apk" "$TMP/aligned.apk"
APK="$OUT/QuetTaiLieu-$VER.apk"
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$ALIAS" --out "$APK" "$TMP/aligned.apk"
rm -f "$APK.idsig"
cert=$(apksigner verify --print-certs "$APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
if [ "$cert" != "$WANT_CERT" ]; then
    rm -f "$APK"
    echo "SAI KHOÁ KÝ: chứng thư $cert, cần $WANT_CERT (khoá gốc). Không xuất APK."
    exit 1
fi
badging=$(aapt dump badging "$APK" | head -1)
case "$badging" in
    *"versionName='$VER'"*) ;;
    *) echo "APK không phải bản $VER: $badging"; exit 1 ;;
esac

IPA="$OUT/QuetTaiLieu-$VER.ipa"
git show origin/ci/ios:QuetTaiLieu-unsigned.ipa > "$IPA"
# Info.plist.txt do CI in ra bằng plutil -p: dòng "CFBundleShortVersionString = 1.5"
if ! git show origin/ci/ios:Info.plist.txt | grep -Eq "CFBundleShortVersionString = \"?$VER\"?\s*\$"; then
    echo "IPA không phải bản $VER:"; git show origin/ci/ios:Info.plist.txt | grep CFBundleShortVersionString || true
    rm -f "$IPA"
    exit 1
fi

echo "$badging"
ls -la "$APK" "$IPA"
sha256sum "$APK" "$IPA"
