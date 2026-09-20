#!/bin/sh
# ---------------------------------------------------------------------------
# mvnw - Maven launcher for jvm-incident-agent (POSIX sh).
#
# This file is hand-written on purpose. `mvn wrapper:wrapper` (maven-wrapper
# 3.3.4, distributionType=only-script) generates a mvnw that resolves its
# distribution cache to ${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists, i.e. it
# writes ~9 MB of Maven outside the repository. This launcher keeps every
# downloaded byte inside the project and reuses an already-installed Maven:
#
#   JIA_MAVEN_HOME  Maven home to use as-is (must contain bin/mvn). Nothing is
#                   downloaded while this is set, so an offline machine only has
#                   to point at a directory once:
#                     JIA_MAVEN_HOME=/opt/apache-maven-3.9.9 ./mvnw -v
#   JIA_MAVEN_CACHE Where a downloaded distribution is unpacked.
#                   Default: <this directory>/.mvn/wrapper/dists
#
# Resolution order:
#   1. $JIA_MAVEN_HOME/bin/mvn
#   2. $JIA_MAVEN_CACHE/apache-maven-<version>/bin/mvn, if already unpacked
#   3. otherwise download distributionUrl from
#      .mvn/wrapper/maven-wrapper.properties, verify distributionSha256Sum when
#      present, unpack into the cache, and run that.
#
# No maven-wrapper.jar is involved: the two properties below are parsed here.
#
# MAVEN_OPTS always carries -Dfile.encoding=UTF-8, because the JVM default
# charset on Windows is the ANSI code page (GBK on the machine this project was
# built on) while the corpus, the fixtures and the sources are all UTF-8.
# ---------------------------------------------------------------------------

set -u

die() {
    printf 'mvnw: %s\n' "$1" >&2
    exit 1
}

say() {
    printf 'mvnw: %s\n' "$1" >&2
}

# ---- locate this script; it may be called from anywhere, path may have spaces
SELF="$0"
while [ -L "$SELF" ]; do
    LINK=$(readlink "$SELF" 2>/dev/null) || break
    case "$LINK" in
        /*) SELF="$LINK" ;;
        *) SELF=$(dirname "$SELF")/"$LINK" ;;
    esac
done
PRG_DIR=$(CDPATH= cd -P -- "$(dirname -- "$SELF")" && pwd) \
    || die "cannot work out the directory of $0"

PROPS="$PRG_DIR/.mvn/wrapper/maven-wrapper.properties"
CACHE_DIR="${JIA_MAVEN_CACHE:-$PRG_DIR/.mvn/wrapper/dists}"

# ---- encoding / maven home hints for the child JVM
MAVEN_OPTS="${MAVEN_OPTS:-} "
case "$MAVEN_OPTS" in
    *-Dfile.encoding=*) ;;
    *) MAVEN_OPTS="-Dfile.encoding=UTF-8 $MAVEN_OPTS" ;;
esac
export MAVEN_OPTS
export MAVEN_PROJECTBASEDIR="$PRG_DIR"

# read one key out of the wrapper properties, tolerating CRLF and spaces
read_prop() {
    [ -f "$PROPS" ] || return 0
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$PROPS" | tr -d '\r' | tail -n 1
}

download() { # download <url> <dest>
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 3 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        return 2
    fi
}

sha256_of() { # sha256_of <file> | prints the hex digest, or fails
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | cut -d' ' -f1
    else
        return 1
    fi
}

extract() { # extract <zip> <into-directory>
    mkdir -p "$2" || return 1
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$1" -d "$2"
    else
        JARTOOL=""
        if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ]; then
            JARTOOL="$JAVA_HOME/bin/jar"
        elif command -v jar >/dev/null 2>&1; then
            JARTOOL=$(command -v jar)
        fi
        [ -n "$JARTOOL" ] || return 2
        (CDPATH= cd -P -- "$2" && "$JARTOOL" xf "$1")
    fi
}

# the first argument is a Maven home whose bin/mvn we will run
MVN_CMD=""

if [ -n "${JIA_MAVEN_HOME:-}" ]; then
    [ -x "${JIA_MAVEN_HOME%/}/bin/mvn" ] \
        || die "JIA_MAVEN_HOME=${JIA_MAVEN_HOME} has no executable bin/mvn"
    MVN_CMD="${JIA_MAVEN_HOME%/}/bin/mvn"
else
    DIST_URL=$(read_prop distributionUrl)
    [ -n "$DIST_URL" ] || die "no JIA_MAVEN_HOME set and no distributionUrl in $PROPS"
    DIST_NAME=${DIST_URL##*/}
    DIST_NAME=${DIST_NAME%.zip}
    DIST_NAME=${DIST_NAME%-bin} # apache-maven-3.9.9-bin.zip -> apache-maven-3.9.9
    MAVEN_HOME_CANDIDATE="$CACHE_DIR/$DIST_NAME"

    if [ -x "$MAVEN_HOME_CANDIDATE/bin/mvn" ]; then
        MVN_CMD="$MAVEN_HOME_CANDIDATE/bin/mvn"
    else
        say "$MAVEN_HOME_CANDIDATE is not unpacked yet"
        say "downloading $DIST_URL"
        ZIP="$CACHE_DIR/$DIST_NAME.zip"
        mkdir -p "$CACHE_DIR" || die "cannot create cache directory $CACHE_DIR"
        download "$DIST_URL" "$ZIP"
        case $? in
            0) ;;
            2) rm -f "$ZIP"; die "neither curl nor wget is available - set JIA_MAVEN_HOME to a Maven home" ;;
            *) rm -f "$ZIP"; die "download of $DIST_URL failed - set JIA_MAVEN_HOME to a Maven home" ;;
        esac

        DIST_SHA=$(read_prop distributionSha256Sum)
        if [ -n "$DIST_SHA" ]; then
            ACTUAL=$(sha256_of "$ZIP") || { rm -f "$ZIP"; die "checksum requested but no sha256sum/shasum/openssl found"; }
            if [ "$(printf '%s' "$DIST_SHA" | tr 'A-F' 'a-f')" != "$ACTUAL" ]; then
                rm -f "$ZIP"
                die "checksum mismatch for $DIST_NAME.zip: expected $DIST_SHA, got $ACTUAL"
            fi
            say "checksum ok ($DIST_NAME.zip)"
        fi

        extract "$ZIP" "$CACHE_DIR" || die "cannot unpack $ZIP (needs unzip, or jar from a JDK)"
        rm -f "$ZIP"
        chmod -R +x "$MAVEN_HOME_CANDIDATE/bin" 2>/dev/null || true

        if [ -x "$MAVEN_HOME_CANDIDATE/bin/mvn" ]; then
            MVN_CMD="$MAVEN_HOME_CANDIDATE/bin/mvn"
        else
            # a distribution that unpacked into a differently named directory
            for D in "$MAVEN_HOME_CANDIDATE"/*/; do
                [ -x "${D}bin/mvn" ] && { MVN_CMD="${D}bin/mvn"; break; }
            done
        fi
        [ -n "$MVN_CMD" ] || die "downloaded distribution has no bin/mvn under $MAVEN_HOME_CANDIDATE"
    fi
fi

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
    die "JAVA_HOME is not set and there is no java on PATH"
fi

exec "$MVN_CMD" -Dmaven.multiModuleProjectDirectory="$PRG_DIR" "$@"
