#!/usr/bin/env bash
#
# Claude vision OMR pipeline for Audiveris (experimental).
#
#   claude-omr.sh prepare <input> [-o <out-dir>] [-s <sheets>]
#       Render the input pages, detect the layout and write the pitch-labelled detail tiles
#       plus request.md, in <out-dir>/<radix>-claude/ (default <out-dir>: input folder).
#   claude-omr.sh finish <file.claude.json> [--parts] [--no-pdf] [--no-preview]
#       Convert the description to <radix>.mxl, then (if MuseScore is found) to <radix>.pdf
#       and a small <radix>-preview*.png for a visual check.
#       --parts: also partition the score into one <Part>.mxl (+ <Part>.pdf) per instrument or
#       voice, in <radix>-parts/.
#   claude-omr.sh build
#       (Re)build the Audiveris launcher of this repository.
#
# Output is kept short on purpose (it ends up in Claude's context): full logs are written to
# files next to the results.
#
# Environment overrides: AUDIVERIS (launcher), MUSESCORE (MuseScore executable), JAVA_HOME.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
LAUNCHER="$REPO_DIR/app/build/install/app/bin/Audiveris"

die () { echo "ERROR: $*" >&2; exit 1; }

# Absolute path, in a form the Java side understands (C:/... on Windows)
abspath () {
    local p
    p="$(cd "$(dirname "$1")" 2>/dev/null && pwd)/$(basename "$1")" || die "no such path: $1"
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$p"; else echo "$p"; fi
}

find_java () {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" -o -x "$JAVA_HOME/bin/java.exe" ]; then
        return
    fi
    for d in "/c/Program Files/Eclipse Adoptium"/jdk-25* "/c/Program Files/Zulu"/zulu-25* \
             "/c/Program Files/Java"/jdk-25* "/c/Program Files/Microsoft"/jdk-25* \
             /usr/lib/jvm/*25* /Library/Java/JavaVirtualMachines/*25*/Contents/Home; do
        if [ -x "$d/bin/java" ] || [ -x "$d/bin/java.exe" ]; then
            export JAVA_HOME="$d"
            return
        fi
    done
    command -v java >/dev/null 2>&1 || die "no JDK 25 found: install one and set JAVA_HOME"
}

# Written after each successful build (the jar keeps its date when its content is unchanged)
STAMP="$REPO_DIR/app/build/claude-omr.stamp"

build () {
    find_java
    echo "Building Audiveris launcher (first run or sources changed)..." >&2
    mkdir -p "$REPO_DIR/app/build"
    (cd "$REPO_DIR" && ./gradlew -q :app:installDist > "$REPO_DIR/app/build/claude-omr-build.log" 2>&1) \
        || die "build failed, see app/build/claude-omr-build.log"
    touch "$STAMP"
}

# Make sure an up-to-date launcher is available (called before output redirections)
ensure_launcher () {
    [ -z "${AUDIVERIS:-}" ] || return 0
    find_java
    if [ ! -x "$LAUNCHER" ] || [ ! -f "$STAMP" ] \
        || [ -n "$(find "$REPO_DIR/app/src/main" "$REPO_DIR/app/build.gradle" -newer "$STAMP" -print -quit 2>/dev/null)" ]; then
        build
    fi
}

audiveris () {
    if [ -n "${AUDIVERIS:-}" ]; then "$AUDIVERIS" "$@"; else "$LAUNCHER" "$@"; fi
}

find_musescore () {
    if [ -n "${MUSESCORE:-}" ]; then echo "$MUSESCORE"; return; fi
    for c in mscore4 mscore MuseScore4 musescore4 musescore mscore3 MuseScore3; do
        if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return; fi
    done
    for c in "/c/Program Files/MuseScore 4/bin/MuseScore4.exe" \
             "/c/Program Files/MuseScore 3/bin/MuseScore3.exe" \
             "/Applications/MuseScore 4.app/Contents/MacOS/mscore"; do
        if [ -x "$c" ]; then echo "$c"; return; fi
    done
}

cmd_prepare () {
    local input="" out="" sheets=""
    while [ $# -gt 0 ]; do
        case "$1" in
            -o) out="$2"; shift 2 ;;
            -s) sheets="$2"; shift 2 ;;
            *) input="$1"; shift ;;
        esac
    done
    [ -n "$input" ] || die "usage: claude-omr.sh prepare <input> [-o <out-dir>] [-s <sheets>]"
    input="$(abspath "$input")"
    [ -n "$out" ] || out="$(dirname "$input")"
    mkdir -p "$out"
    out="$(abspath "$out")"

    local name radix folder log
    name="$(basename "$input")"
    radix="${name%.*}"
    folder="$out/$radix-claude"
    mkdir -p "$folder"
    log="$folder/audiveris.log"

    local args=(-batch -claude)
    if [ -n "$sheets" ]; then args+=(-sheets $sheets); fi
    args+=(-output "$out" "$input")

    ensure_launcher
    audiveris "${args[@]}" > "$log" 2>&1
    [ -f "$folder/request.md" ] || { grep -E "ERROR|Exception" "$log" | head -5; die "prepare failed, see $log"; }

    local pages tiles
    pages=$(ls "$folder"/page-*.png 2>/dev/null | grep -c -E 'page-[0-9]+\.png$')
    tiles=$(ls "$folder"/page-*-s*-m*.png 2>/dev/null | wc -l)
    echo "Prepared: $folder"
    echo "Request:  $folder/request.md"
    echo "Pages: $pages, detail tiles: $tiles"
    grep -h "no layout detected" "$folder/request.md" | head -3
    echo "Write:    $out/$radix.claude.json"
}

cmd_finish () {
    local json="" pdf=1 preview=1 parts=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --parts) parts=1; shift ;;
            --no-pdf) pdf=0; shift ;;
            --no-preview) preview=0; shift ;;
            *) json="$1"; shift ;;
        esac
    done
    [ -n "$json" ] || die "usage: claude-omr.sh finish <file.claude.json> [--parts] [--no-pdf] [--no-preview]"
    json="$(abspath "$json")"
    local dir name radix log partsdir
    dir="$(dirname "$json")"
    name="$(basename "$json")"
    radix="${name%.claude.json}"
    log="$dir/$radix.convert.log"
    partsdir="$dir/$radix-parts"

    rm -f "$dir/$radix.mxl"
    rm -f "$partsdir"/*.mxl "$partsdir"/*.pdf 2>/dev/null
    local args=(-batch -output "$dir")
    if [ "$parts" = 1 ]; then args+=(-parts); fi
    ensure_launcher
    audiveris "${args[@]}" "$json" > "$log" 2>&1

    if [ ! -f "$dir/$radix.mxl" ]; then
        echo "Conversion FAILED:"
        grep -E "Exception: " "$log" | sed -E 's/^.*Exception: //' | awk '!seen[$0]++' | head -5
        exit 2
    fi

    local warnings
    warnings=$(grep -E "WARN .*Claude OMR:" "$log" | sed -E 's/^.*Claude OMR: //')
    echo "MusicXML: $dir/$radix.mxl"
    grep -h "Claude OMR summary:" "$log" | tail -1 | sed -E 's/^.*Claude OMR summary: /Contents: /'
    if [ -n "$warnings" ]; then
        echo "Warnings ($(echo "$warnings" | wc -l)):"
        echo "$warnings" | head -30
    else
        echo "Warnings: none"
    fi

    local part_files=()
    if [ "$parts" = 1 ]; then
        if grep -q "Claude OMR parts: single-part score" "$log"; then
            echo "Parts:    single-part score, nothing to partition"
        else
            local f
            for f in "$partsdir"/*.mxl; do [ -f "$f" ] && part_files+=("$f"); done
            if [ ${#part_files[@]} -eq 0 ]; then
                echo "Parts FAILED:"
                grep -E "Exception: " "$log" | sed -E 's/^.*Exception: //' | awk '!seen[$0]++' | head -5
                exit 2
            fi
            echo "Parts:    ${#part_files[@]} in $partsdir/"
            grep -h "Claude OMR part: " "$log" | sed -E 's/^.*Claude OMR part: (.*) -> .*[\/\]([^\/\]*) \((.*)\)$/  \1 -> \2 (\3)/'
        fi
    fi

    [ "$pdf" = 1 ] || return 0
    local mscore
    mscore="$(find_musescore)"
    if [ -z "$mscore" ]; then
        echo "MuseScore not found (set MUSESCORE): no PDF written"
        return 0
    fi
    rm -f "$dir/$radix.pdf" "$dir/$radix"-preview*.png
    "$mscore" -o "$dir/$radix.pdf" "$dir/$radix.mxl" >> "$log" 2>&1
    [ -f "$dir/$radix.pdf" ] && echo "PDF:      $dir/$radix.pdf" || echo "PDF export failed, see $log"
    if [ ${#part_files[@]} -gt 0 ]; then
        local f ok=0
        for f in "${part_files[@]}"; do
            "$mscore" -o "${f%.mxl}.pdf" "$f" >> "$log" 2>&1 && [ -f "${f%.mxl}.pdf" ] && ok=$((ok + 1))
        done
        echo "Part PDFs: $ok of ${#part_files[@]} in $partsdir/"
    fi
    if [ "$preview" = 1 ]; then
        "$mscore" -r 60 -o "$dir/$radix-preview.png" "$dir/$radix.mxl" >> "$log" 2>&1
        ls "$dir/$radix"-preview*.png 2>/dev/null | sed 's/^/Preview:  /'
    fi
}

case "${1:-}" in
    prepare) shift; cmd_prepare "$@" ;;
    finish) shift; cmd_finish "$@" ;;
    build) build; echo "Launcher: $LAUNCHER" ;;
    *) sed -n '3,19p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
