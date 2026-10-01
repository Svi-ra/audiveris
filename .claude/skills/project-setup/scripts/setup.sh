#!/usr/bin/env bash
#
# First-time setup of the Audiveris repository (safe to run again at any time).
#
#   setup.sh [--check] [--force] [--no-musescore] [--full-tests]
#
#   --check         only report, install / configure / build nothing
#   --force         rebuild and re-run the end-to-end tests even if already verified
#   --no-musescore  do not install MuseScore (optional, only needed for PDF output)
#   --full-tests    run the whole unit test suite instead of the quick subset
#
# Steps: environment detection, tools (git, curl, unzip/tar), JDK 25, JAVA_HOME, Gradle wrapper,
# OCR language data, MuseScore (optional), build, smoke test, end-to-end tests, final report.
# Output is a short report; details go to app/build/setup.log.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
BUILD_DIR="$REPO_DIR/app/build"
LOG="$BUILD_DIR/setup.log"
LAUNCHER="$BUILD_DIR/install/app/bin/Audiveris"
BUILD_STAMP="$BUILD_DIR/claude-omr.stamp"   # shared with the claude-omr skill script
SETUP_STAMP="$BUILD_DIR/setup.stamp"
OMR_SCRIPT="$REPO_DIR/.claude/skills/claude-omr/scripts/claude-omr.sh"
E2E_DIR="$BUILD_DIR/setup-e2e"
MIN_JAVA=25

CHECK_ONLY=0; FORCE=0; WITH_MUSESCORE=1; FULL_TESTS=0
for arg in "$@"; do
    case "$arg" in
        --check) CHECK_ONLY=1 ;;
        --force) FORCE=1 ;;
        --no-musescore) WITH_MUSESCORE=0 ;;
        --full-tests) FULL_TESTS=1 ;;
        -h|--help) sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $arg" >&2; exit 1 ;;
    esac
done

mkdir -p "$BUILD_DIR"
: > "$LOG"

# ------------------------------------------------------------------------------------------------
# Report helpers
# ------------------------------------------------------------------------------------------------
REPORT=()
NOTES=()
BLOCKERS=0

# status: present | installed | configured | built | passed | skipped | missing | FAILED
report () {
    REPORT+=("$(printf '%-14s %-11s %s' "$1" "$2" "$3")")
    case "$2" in
        FAILED) BLOCKERS=$((BLOCKERS + 1)) ;;
        missing) [ "${4:-required}" = required ] && BLOCKERS=$((BLOCKERS + 1)) ;;
    esac
}
note () { NOTES+=("$1"); }
log () { echo "[$(date +%H:%M:%S)] $*" >> "$LOG"; }
progress () { echo "... $*"; log "$*"; }
run () { log "\$ $*"; "$@" >> "$LOG" 2>&1; }

winpath () { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

# ------------------------------------------------------------------------------------------------
# 1. Environment
# ------------------------------------------------------------------------------------------------
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) OS=windows ;;
    Darwin) OS=macos ;;
    Linux) OS=linux; grep -qi microsoft /proc/version 2>/dev/null && OS_DETAIL=" (WSL)" ;;
    *) OS=unknown ;;
esac
case "$(uname -m)" in
    x86_64|amd64) ARCH=x64 ;;
    arm64|aarch64) ARCH=aarch64 ;;
    *) ARCH="$(uname -m)" ;;
esac
SUDO=""
if [ "$OS" = linux ]; then
    if [ "$(id -u)" = 0 ]; then SUDO=" "; elif sudo -n true 2>/dev/null; then SUDO="sudo -n"; fi
fi
PKG=""
for p in winget brew apt-get dnf pacman zypper; do
    if command -v "$p" >/dev/null 2>&1; then PKG="$p"; break; fi
done
report "Environment" present "$OS${OS_DETAIL:-} $ARCH, bash ${BASH_VERSION%%(*}, package manager: ${PKG:-none}$([ "$OS" = linux ] && { [ -n "$SUDO" ] && echo ", sudo ok" || echo ", no passwordless sudo"; })"
log "Repository: $REPO_DIR"

[ -x "$REPO_DIR/gradlew" ] || { report "Repository" FAILED "gradlew not found in $REPO_DIR"; }

# ------------------------------------------------------------------------------------------------
# 2. Basic tools
# ------------------------------------------------------------------------------------------------
pkg_install () {   # pkg_install <winget-id> <brew-formula> <apt> <dnf> <pacman>
    [ "$CHECK_ONLY" = 1 ] && return 1
    case "$PKG" in
        winget) run winget install --id "$1" -e --silent --accept-package-agreements --accept-source-agreements ;;
        brew) run brew install $2 ;;
        apt-get) [ -n "$SUDO" ] && run $SUDO apt-get install -y $3 ;;
        dnf) [ -n "$SUDO" ] && run $SUDO dnf install -y $4 ;;
        pacman) [ -n "$SUDO" ] && run $SUDO pacman -S --noconfirm $5 ;;
        *) return 1 ;;
    esac
}

missing_tools=()
for t in git curl tar; do
    command -v "$t" >/dev/null 2>&1 || missing_tools+=("$t")
done
if [ ${#missing_tools[@]} -gt 0 ]; then
    for t in "${missing_tools[@]}"; do pkg_install "Git.Git" "$t" "$t" "$t" "$t"; done
fi
still=()
for t in git curl tar; do command -v "$t" >/dev/null 2>&1 || still+=("$t"); done
if [ ${#still[@]} -eq 0 ]; then
    if [ ${#missing_tools[@]} -gt 0 ]; then
        report "Tools" installed "${missing_tools[*]}"
    else
        report "Tools" present "git, curl, tar"
    fi
else
    report "Tools" missing "${still[*]} (install them, then re-run)"
fi

# ------------------------------------------------------------------------------------------------
# 3. JDK 25+
# ------------------------------------------------------------------------------------------------
java_major () {   # java_major <java executable> -> major version or 0
    local v
    v="$("$1" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    case "$v" in ''|*[!0-9]*) echo 0 ;; *) echo "$v" ;; esac
}

java_home_of () {   # java_home_of <java executable>
    "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p' | head -1
}

find_jdk () {   # prints a JDK home (with javac) of version >= MIN_JAVA, or nothing
    local candidates=()
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
    if command -v java >/dev/null 2>&1; then candidates+=("$(java_home_of "$(command -v java)")"); fi
    for d in "/c/Program Files/Eclipse Adoptium"/jdk-* "/c/Program Files/Zulu"/zulu-* \
             "/c/Program Files/Java"/jdk-* "/c/Program Files/Microsoft"/jdk-* \
             "$HOME/.jdks"/* "$HOME/.local/jdks"/* /usr/lib/jvm/* \
             /Library/Java/JavaVirtualMachines/*/Contents/Home \
             /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home; do
        [ -d "$d" ] && candidates+=("$d")
    done
    local c j
    for c in "${candidates[@]}"; do
        c="$(echo "$c" | tr '\\' '/')"
        if command -v cygpath >/dev/null 2>&1; then c="$(cygpath -u "$c")"; fi
        j="$c/bin/java"; [ -x "$j" ] || j="$c/bin/java.exe"
        [ -x "$j" ] || continue
        { [ -x "$c/bin/javac" ] || [ -x "$c/bin/javac.exe" ]; } || continue   # JRE only
        if [ "$(java_major "$j")" -ge "$MIN_JAVA" ]; then echo "$c"; return; fi
    done
}

install_jdk_download () {   # official Temurin archive from Adoptium, user-local, no admin rights
    local os="$1" ext url dest tmp
    [ "$os" = mac ] && os=mac
    ext=tar.gz; [ "$OS" = windows ] && ext=zip
    url="https://api.adoptium.net/v3/binary/latest/$MIN_JAVA/ga/$os/$ARCH/jdk/hotspot/normal/eclipse"
    dest="$HOME/.local/jdks"
    tmp="$BUILD_DIR/jdk-download.$ext"
    mkdir -p "$dest"
    progress "Downloading Temurin $MIN_JAVA from adoptium.net"
    run curl -fL --retry 2 -o "$tmp" "$url" || return 1
    if [ "$ext" = zip ]; then
        if command -v unzip >/dev/null 2>&1; then run unzip -q -o "$tmp" -d "$dest" || return 1
        else run powershell -NoProfile -Command "Expand-Archive -Force '$(winpath "$tmp")' '$(winpath "$dest")'" || return 1; fi
    else
        run tar -xzf "$tmp" -C "$dest" || return 1
    fi
    rm -f "$tmp"
}

JDK="$(find_jdk)"
if [ -n "$JDK" ]; then
    report "JDK $MIN_JAVA" present "$(winpath "$JDK") ($("$JDK/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([^"]+)".*/\1/'))"
elif [ "$CHECK_ONLY" = 1 ]; then
    report "JDK $MIN_JAVA" missing "no JDK >= $MIN_JAVA found"
else
    progress "Installing JDK $MIN_JAVA"
    case "$OS" in
        windows) [ "$PKG" = winget ] && run winget install --id "EclipseAdoptium.Temurin.$MIN_JAVA.JDK" -e --silent --accept-package-agreements --accept-source-agreements ;;
        macos) [ "$PKG" = brew ] && run brew install --cask "temurin@$MIN_JAVA" ;;
        linux) case "$PKG" in
                   apt-get) [ -n "$SUDO" ] && run $SUDO apt-get install -y "openjdk-$MIN_JAVA-jdk" ;;
                   dnf) [ -n "$SUDO" ] && run $SUDO dnf install -y "java-$MIN_JAVA-openjdk-devel" ;;
               esac ;;
    esac
    JDK="$(find_jdk)"
    if [ -z "$JDK" ]; then
        case "$OS" in windows) install_jdk_download windows ;; macos) install_jdk_download mac ;; linux) install_jdk_download linux ;; esac
        JDK="$(find_jdk)"
    fi
    if [ -n "$JDK" ]; then
        report "JDK $MIN_JAVA" installed "$(winpath "$JDK")"
    else
        report "JDK $MIN_JAVA" FAILED "could not install automatically, install JDK $MIN_JAVA (e.g. Eclipse Temurin) then re-run"
    fi
fi

# ------------------------------------------------------------------------------------------------
# 4. JAVA_HOME (current process + persistent)
# ------------------------------------------------------------------------------------------------
if [ -n "$JDK" ]; then
    export JAVA_HOME="$JDK"
    export PATH="$JDK/bin:$PATH"
    persistent=""
    if [ "$OS" = windows ]; then
        for scope in Machine User; do
            v="$(powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('JAVA_HOME','$scope')" 2>/dev/null | tr -d '\r')"
            if [ -n "$v" ] && [ "$(java_major "$(cygpath -u "$v")/bin/java.exe")" -ge "$MIN_JAVA" ] 2>/dev/null; then
                persistent="$scope"; break
            fi
        done
        if [ -n "$persistent" ]; then
            report "JAVA_HOME" present "set ($persistent scope)"
        elif [ "$CHECK_ONLY" = 1 ]; then
            report "JAVA_HOME" missing "not set persistently" optional
        else
            run setx JAVA_HOME "$(cygpath -w "$JDK")"
            report "JAVA_HOME" configured "user variable set to $(winpath "$JDK")"
            note "JAVA_HOME was set for your user: open a new terminal to use it outside this setup."
        fi
    else
        rc="$HOME/.profile"; [ "$OS" = macos ] && rc="$HOME/.zprofile"
        default_java="$(command -v java 2>/dev/null)"
        if [ -n "$default_java" ] && [ "$(java_home_of "$default_java")" = "$JDK" ]; then
            report "JAVA_HOME" present "default java is JDK $MIN_JAVA"
        elif grep -q ">>> audiveris setup" "$rc" 2>/dev/null; then
            report "JAVA_HOME" present "configured in $rc"
        elif [ "$CHECK_ONLY" = 1 ]; then
            report "JAVA_HOME" missing "not configured" optional
        else
            {
                echo ""
                echo "# >>> audiveris setup >>>"
                echo "export JAVA_HOME=\"$JDK\""
                echo "export PATH=\"\$JAVA_HOME/bin:\$PATH\""
                echo "# <<< audiveris setup <<<"
            } >> "$rc"
            report "JAVA_HOME" configured "added to $rc"
            note "JAVA_HOME was added to $rc: open a new terminal to use it outside this setup."
        fi
    fi
fi

# ------------------------------------------------------------------------------------------------
# 5. Gradle wrapper distribution
# ------------------------------------------------------------------------------------------------
GRADLE_VERSION="$(sed -n 's/.*gradle-\([0-9.]*\)-bin.zip/\1/p' "$REPO_DIR/gradle/wrapper/gradle-wrapper.properties")"
if ls -d "$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION-bin" >/dev/null 2>&1; then
    report "Gradle" present "wrapper $GRADLE_VERSION"
else
    report "Gradle" present "wrapper $GRADLE_VERSION (downloaded on first build)"
fi

# ------------------------------------------------------------------------------------------------
# 6. OCR language data (Tesseract, used by the standard engine for texts)
# ------------------------------------------------------------------------------------------------
TESSDATA_TAG="$(sed -n 's/^theTessdataTag *= *//p' "$REPO_DIR/gradle.properties" | tr -d '\r ')"
if [ -n "${TESSDATA_PREFIX:-}" ]; then
    TESSDIR="$TESSDATA_PREFIX"
else
    case "$OS" in
        windows) TESSDIR="$(cygpath -u "$APPDATA")/AudiverisLtd/audiveris/config/tessdata" ;;
        macos) TESSDIR="$HOME/Library/Application Support/AudiverisLtd/audiveris/tessdata" ;;
        *) TESSDIR="${XDG_CONFIG_HOME:-$HOME/.config}/AudiverisLtd/audiveris/tessdata" ;;
    esac
fi
langs="$(ls "$TESSDIR"/*.traineddata 2>/dev/null | sed 's#.*/##; s#\.traineddata##' | tr '\n' ',' | sed 's/,$//')"
if [ -n "$langs" ]; then
    report "OCR data" present "$langs"
elif [ "$CHECK_ONLY" = 1 ]; then
    report "OCR data" missing "no language in $TESSDIR" optional
else
    progress "Downloading English OCR data"
    mkdir -p "$TESSDIR"
    if run curl -fL --retry 2 -o "$TESSDIR/eng.traineddata.part" \
        "https://github.com/tesseract-ocr/tessdata/raw/${TESSDATA_TAG:-4.1.0}/eng.traineddata" \
        && mv "$TESSDIR/eng.traineddata.part" "$TESSDIR/eng.traineddata"; then
        report "OCR data" installed "eng in $(winpath "$TESSDIR")"
    else
        rm -f "$TESSDIR/eng.traineddata.part"
        report "OCR data" missing "download failed; add languages from the Audiveris GUI (Tools > Install languages)" optional
    fi
fi

# ------------------------------------------------------------------------------------------------
# 7. MuseScore (optional: PDF output of the Claude vision mode)
# ------------------------------------------------------------------------------------------------
find_musescore () {
    if [ -n "${MUSESCORE:-}" ] && [ -x "$MUSESCORE" ]; then echo "$MUSESCORE"; return; fi
    for c in mscore4 mscore MuseScore4 musescore4 musescore mscore3 MuseScore3; do
        if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return; fi
    done
    for c in "/c/Program Files/MuseScore 4/bin/MuseScore4.exe" "/c/Program Files/MuseScore 3/bin/MuseScore3.exe" \
             "/Applications/MuseScore 4.app/Contents/MacOS/mscore" "$HOME/.local/bin/mscore4"; do
        if [ -x "$c" ]; then echo "$c"; return; fi
    done
}

MSCORE="$(find_musescore)"
if [ -n "$MSCORE" ]; then
    report "MuseScore" present "$(winpath "$MSCORE")"
elif [ "$WITH_MUSESCORE" = 0 ] || [ "$CHECK_ONLY" = 1 ]; then
    report "MuseScore" skipped "not installed (optional, only for PDF output)"
else
    progress "Installing MuseScore (optional, for PDF output)"
    case "$OS" in
        windows) [ "$PKG" = winget ] && run winget install --id Musescore.Musescore -e --silent --accept-package-agreements --accept-source-agreements ;;
        macos) [ "$PKG" = brew ] && run brew install --cask musescore ;;
        linux)
            if [ "$PKG" = apt-get ] && [ -n "$SUDO" ]; then run $SUDO apt-get install -y musescore3
            elif command -v flatpak >/dev/null 2>&1 && run flatpak install -y --user flathub org.musescore.MuseScore; then
                mkdir -p "$HOME/.local/bin"
                printf '#!/bin/sh\nexec flatpak run org.musescore.MuseScore "$@"\n' > "$HOME/.local/bin/mscore4"
                chmod +x "$HOME/.local/bin/mscore4"
            fi ;;
    esac
    MSCORE="$(find_musescore)"
    if [ -n "$MSCORE" ]; then
        report "MuseScore" installed "$(winpath "$MSCORE")"
    else
        report "MuseScore" missing "could not install automatically (optional, only for PDF output)" optional
    fi
fi
[ -n "$MSCORE" ] && export MUSESCORE="$MSCORE"

# ------------------------------------------------------------------------------------------------
# 8. Build
# ------------------------------------------------------------------------------------------------
sources_changed () {
    [ ! -f "$BUILD_STAMP" ] || [ -n "$(find "$REPO_DIR/app/src/main" "$REPO_DIR/app/build.gradle" \
        "$REPO_DIR/buildSrc/src" "$REPO_DIR/gradle.properties" -newer "$BUILD_STAMP" -print -quit 2>/dev/null)" ]
}

BUILD_OK=0
if [ "$BLOCKERS" -gt 0 ]; then
    report "Build" skipped "blocked by missing requirements"
elif [ "$CHECK_ONLY" = 1 ]; then
    if [ -x "$LAUNCHER" ] && ! sources_changed; then report "Build" present "launcher up to date"; BUILD_OK=1
    else report "Build" missing "not built or outdated" optional; fi
elif [ "$FORCE" = 0 ] && [ -x "$LAUNCHER" ] && ! sources_changed; then
    report "Build" present "launcher up to date"
    BUILD_OK=1
else
    progress "Building (first build downloads Gradle and dependencies, a few minutes)"
    if (cd "$REPO_DIR" && run ./gradlew -q :app:installDist); then
        touch "$BUILD_STAMP"
        report "Build" built "$(winpath "$LAUNCHER")"
        BUILD_OK=1
    else
        report "Build" FAILED "see $(winpath "$LOG")"
    fi
fi

# ------------------------------------------------------------------------------------------------
# 9. Verification: smoke test, end-to-end tests, unit tests
# ------------------------------------------------------------------------------------------------
HEAD="$(cd "$REPO_DIR" && git rev-parse HEAD 2>/dev/null)"
already_verified () {
    [ "$FORCE" = 0 ] && [ -f "$SETUP_STAMP" ] && [ "$SETUP_STAMP" -nt "$BUILD_STAMP" ] \
        && grep -q "head=$HEAD" "$SETUP_STAMP" && grep -qx "musescore=${MSCORE:+yes}" "$SETUP_STAMP"
}

E2E_OK=0
if [ "$BUILD_OK" = 1 ] && [ "$CHECK_ONLY" = 0 ]; then
    if already_verified; then
        report "Verification" present "already passed for this build ($(sed -n 's/^date=//p' "$SETUP_STAMP"))"
        E2E_OK=1
    else
        rm -rf "$E2E_DIR"; mkdir -p "$E2E_DIR"
        sample="$(winpath "$REPO_DIR/data/examples/zizi.png")"
        out="$(winpath "$E2E_DIR")"

        # Smoke test
        progress "Smoke test"
        if "$LAUNCHER" -batch -help > "$E2E_DIR/help.txt" 2>&1 && grep -q -- "-batch" "$E2E_DIR/help.txt"; then
            report "Smoke test" passed "launcher starts (-help)"
        else
            report "Smoke test" FAILED "see $(winpath "$E2E_DIR/help.txt")"
        fi

        # Standard engine, end to end
        progress "End-to-end test: standard OMR engine"
        "$LAUNCHER" -batch -transcribe -export -output "$out/standard" "$sample" > "$E2E_DIR/standard.log" 2>&1
        if [ -f "$E2E_DIR/standard/zizi.mxl" ]; then
            report "E2E standard" passed "zizi.png -> zizi.mxl"
        else
            report "E2E standard" FAILED "see $(winpath "$E2E_DIR/standard.log")"
        fi
        grep -q "No installed OCR languages" "$E2E_DIR/standard.log" && note "Audiveris reports no OCR language: texts will not be recognized."

        # Claude vision mode, end to end (prepare + finish of the sample description)
        if [ -x "$OMR_SCRIPT" ] || [ -f "$OMR_SCRIPT" ]; then
            progress "End-to-end test: Claude vision pipeline"
            mkdir -p "$E2E_DIR/claude"
            cp "$REPO_DIR/data/examples/claude/zizi.claude.json" "$E2E_DIR/claude/"
            bash "$OMR_SCRIPT" prepare "$REPO_DIR/data/examples/zizi.png" -o "$E2E_DIR/claude" > "$E2E_DIR/claude-prepare.txt" 2>&1
            bash "$OMR_SCRIPT" finish "$E2E_DIR/claude/zizi.claude.json" > "$E2E_DIR/claude-finish.txt" 2>&1
            tiles="$(ls "$E2E_DIR/claude/zizi-claude"/page-*-s*-m*.png 2>/dev/null | wc -l | tr -d ' ')"
            if [ -f "$E2E_DIR/claude/zizi.mxl" ] && [ "$tiles" -gt 0 ] && grep -q "Warnings: none" "$E2E_DIR/claude-finish.txt"; then
                detail="$tiles tiles, zizi.claude.json -> zizi.mxl"
                [ -f "$E2E_DIR/claude/zizi.pdf" ] && detail="$detail + zizi.pdf"
                report "E2E Claude" passed "$detail"
            else
                report "E2E Claude" FAILED "see $(winpath "$E2E_DIR")/claude-*.txt"
            fi
        fi

        # Unit tests
        if [ "$FULL_TESTS" = 1 ]; then filter=(); label="all unit tests"
        else filter=(--tests "org.audiveris.omr.claude.*"); label="quick subset"; fi
        progress "Unit tests ($label)"
        if (cd "$REPO_DIR" && run ./gradlew -q :app:test "${filter[@]}"); then
            count="$(cat "$BUILD_DIR"/test-results/test/*.xml 2>/dev/null | grep -o '<testsuite name="[^"]*" tests="[0-9]*"' | sed 's/.*tests="//; s/"//' | awk '{n+=$1} END {print n}')"
            report "Unit tests" passed "$label${count:+, $count tests}"
        else
            report "Unit tests" FAILED "see $(winpath "$LOG")"
        fi

        if [ "$BLOCKERS" -eq 0 ]; then
            E2E_OK=1
            printf 'head=%s\ndate=%s\nmusescore=%s\n' "$HEAD" "$(date '+%Y-%m-%d %H:%M')" "${MSCORE:+yes}" > "$SETUP_STAMP"
        fi
    fi
fi

# ------------------------------------------------------------------------------------------------
# 10. Report
# ------------------------------------------------------------------------------------------------
echo
echo "Audiveris setup report"
echo "----------------------"
printf '%s\n' "${REPORT[@]}"
for n in "${NOTES[@]+"${NOTES[@]}"}"; do echo "Note: $n"; done
echo "Log: $(winpath "$LOG")"
if [ "$CHECK_ONLY" = 1 ]; then
    if [ "$BLOCKERS" -eq 0 ] && [ "$BUILD_OK" = 1 ]; then echo "RESULT: READY (check only)"; exit 0; fi
    echo "RESULT: NOT READY (check only; run without --check to fix)"; exit 1
elif [ "$BLOCKERS" -eq 0 ] && [ "$E2E_OK" = 1 ]; then
    echo "RESULT: READY"
    echo "Launcher: $(winpath "$LAUNCHER")"
    exit 0
else
    echo "RESULT: NOT READY ($BLOCKERS blocking problem(s), see FAILED / missing lines)"
    exit 1
fi
