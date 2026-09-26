#!/usr/bin/env bash
#
# build-proot-arm64.sh — Reproducibly build the PRoot execution bridge for
# Android arm64-v8a as a standalone PIE executable packaged as libproot.so.
#
# PRoot is the user-space compatibility layer selected by ADR-004 as the
# Android execution bridge/loader. It is shipped in jniLibs/<abi>/ so the
# Android package installer extracts it into the APK's native library area
# (read-only, executable) — the only modern Android contract that allows an
# app to execute a bundled native program. The downloaded Linux rootfs stays
# out-of-band data (ADR-006); this binary only provides the loader/ptrace
# bridge, it is NOT a security sandbox (see AGENTS.md "PRoot boundary").
#
# This script clones third-party SOURCE into a build dir under /tmp (never
# into the workspace) and writes only the verified, stripped build outputs
# into app/src/main/jniLibs/arm64-v8a/:
#   libproot.so         the PRoot PIE executable (exec'd by the app)
#   libproot-loader.so  PRoot's freestanding ELF loader, shipped separately so
#                       PRoot can find it via the PROOT_LOADER env var instead
#                       of extracting its embedded copy into app-private
#                       writable storage (app_data_file), whose exec is denied
#                       under u:r:untrusted_app on targetSdk 29+. Files in
#                       nativeLibraryDir are apk_data_file, which the app
#                       domain may execute.
#
# Pinned inputs (reproducibility):
#   PRoot   : termux/proot  v5.1.107.92  (git rev 7266fb3e8516535682f5a9c8f3a7e70f6506eddb)
#             GPL-2.0-or-later — a compatible fork of upstream proot-me/proot
#             (also GPL-2.0-or-later) carrying the Android ptrace/proc patches.
#   Talloc  : samba talloc 2.4.2 tarball (LGPL-3.0-or-later), statically linked.
#   NDK     : Android NDK r28c (28.2.13676358), clang 19, 16 KB page capable.
#
# License obligations produced by this build (see docs/research note):
#   - libproot.so is a derivative of GPL-2.0-or-later PRoot + LGPL-3.0-or-later
#     talloc. The combined work is distributable under GPL-2.0+ (PRoot's
#     "or later" clause permits upgrading to GPLv3, which is compatible with
#     LGPL-3.0). Source for both is pinned above; this script reproduces the
#     binary. Talloc is statically linked, so LGPL-3.0 sections 4-6 require
#     that users can re-link against a modified talloc — satisfied by
#     publishing the exact source revisions and this build script.
#
# Usage:
#   scripts/build-proot-arm64.sh            # build + verify + install
#   scripts/build-proot-arm64.sh --no-install  # build + verify only
#   scripts/build-proot-arm64.sh --repro-check # build twice, compare hashes
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (pinned for reproducibility)
# ---------------------------------------------------------------------------
PROOT_REPO="https://github.com/termux/proot.git"
PROOT_TAG="v5.1.107.92"
PROOT_REV="7266fb3e8516535682f5a9c8f3a7e70f6506eddb"
PROOT_VERSION="5.1.107.92"

TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz"
TALLOC_TGZ="talloc-2.4.2.tar.gz"
TALLOC_SHA256="85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"
TALLOC_VERSION="2.4.2"

NDK_VERSION="28.2.13676358"   # Android NDK r28c
ANDROID_API="29"              # minSdk 29 (Android 10) — lowest supported target

# 16 KB page-size compatibility: all LOAD segments aligned to 0x4000.
PAGE_SIZE_FLAG="-Wl,-z,max-page-size=16384"
# ld.lld parallel string-merge is non-deterministic; single-threaded linking
# yields a bit-identical binary across rebuilds (verified).
THREADS_FLAG="-Wl,--threads=1"

# Reproducible stripped output hash (sha256) for this exact configuration.
# Bumped with Patch C (the link2symlink fix, NusaDesk issue #1): the loader
# is unaffected and keeps its own pin below.
EXPECTED_SHA256="243c26a7f512e9211b04cacedd6927f6291b3eaef54ec2c7d8c2ecf4e0282335"
# Same for the freestanding loader shipped as libproot-loader.so.
EXPECTED_LOADER_SHA256="12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_DIR="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a"
OUTPUT_NAME="libproot.so"
LOADER_OUTPUT_NAME="libproot-loader.so"

BUILD_ROOT="${BUILD_ROOT:-/tmp/linux-wrapper-proot-build}"
SDK_ROOT="${SDK_ROOT:-/tmp/linux-wrapper-android-sdk}"

DO_INSTALL=1
REPRO_CHECK=0
for arg in "$@"; do
  case "$arg" in
    --no-install) DO_INSTALL=0 ;;
    --repro-check) REPRO_CHECK=1; DO_INSTALL=0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log() { printf '\033[1;34m[build-proot]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build-proot] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }
require git curl sha256sum make

# ---------------------------------------------------------------------------
# 1. Locate / install the Android NDK
# ---------------------------------------------------------------------------
NDK_DIR="${SDK_ROOT}/ndk/${NDK_VERSION}"
if [[ ! -x "${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${ANDROID_API}-clang" ]]; then
  log "NDK ${NDK_VERSION} not found at ${NDK_DIR}; installing via sdkmanager..."
  SDKMGR="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
  [[ -x "${SDKMGR}" ]] || die "sdkmanager not found at ${SDKMGR}; install Android cmdline-tools first."
  yes | "${SDKMGR}" --install "ndk;${NDK_VERSION}" || die "NDK install failed."
fi
TOOL="${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="${TOOL}/bin:${PATH}"
CC="${TOOL}/bin/aarch64-linux-android${ANDROID_API}-clang"
[[ -x "${CC}" ]] || die "NDK aarch64 clang not found at ${CC}"
log "NDK: ${NDK_DIR} (API ${ANDROID_API})"

# ---------------------------------------------------------------------------
# 2. Fetch sources into /tmp build root (never the workspace)
# ---------------------------------------------------------------------------
mkdir -p "${BUILD_ROOT}"
PROOT_SRC="${BUILD_ROOT}/proot-src"
TALLOC_SRC="${BUILD_ROOT}/talloc-src"
TALLOC_BUILD="${BUILD_ROOT}/talloc-build"

if [[ ! -d "${PROOT_SRC}/.git" ]]; then
  log "Cloning PRoot ${PROOT_TAG} (rev ${PROOT_REV:0:12})..."
  git clone --depth 1 --branch "${PROOT_TAG}" "${PROOT_REPO}" "${PROOT_SRC}"
else
  log "PRoot source already present at ${PROOT_SRC}"
fi
cd "${PROOT_SRC}"
ACTUAL_REV="$(git rev-parse HEAD)"
[[ "${ACTUAL_REV}" == "${PROOT_REV}" ]] || die "PRoot rev mismatch: ${ACTUAL_REV} != ${PROOT_REV}"
log "PRoot rev verified: ${ACTUAL_REV} (${PROOT_TAG})"

if [[ ! -f "${TALLOC_SRC}/talloc.c" ]]; then
  log "Downloading talloc ${TALLOC_VERSION}..."
  ( cd "${BUILD_ROOT}" && curl -fsSL -o "${TALLOC_TGZ}" "${TALLOC_URL}" )
  ( cd "${BUILD_ROOT}" && echo "${TALLOC_SHA256}  ${TALLOC_TGZ}" | sha256sum -c - )
  ( cd "${BUILD_ROOT}" && tar xzf "${TALLOC_TGZ}" && mv "talloc-${TALLOC_VERSION}" "${TALLOC_SRC}" )
else
  log "Talloc source already present at ${TALLOC_SRC}"
fi

# ---------------------------------------------------------------------------
# 3. Apply recorded build patches (see docs/research note for full origin)
# ---------------------------------------------------------------------------
log "Applying build patches..."

# Patch A: ashmem_memfd.c uses strcmp/memset without <string.h>. glibc pulls it
# transitively; bionic does not, so it is a hard error under the NDK.
# Upstream termux/proot does not include this; it is a local compatibility fix.
ASHMEM="${PROOT_SRC}/src/extension/ashmem_memfd/ashmem_memfd.c"
if ! grep -q '<string.h>' "${ASHMEM}"; then
  sed -i '3a #include <string.h>' "${ASHMEM}"
  log "  Patch A: added <string.h> to ashmem_memfd.c"
fi

# Patch B: loader/loader-info.awk uses gawk-only strtonum() and \y word
# boundaries. Replace with a mawk-compatible hex parser and $NF symbol match.
# This only affects the generated offset constant, which is verified correct.
AWK_SCRIPT="${PROOT_SRC}/src/loader/loader-info.awk"
if grep -q 'strtonum' "${AWK_SCRIPT}"; then
  cat > "${AWK_SCRIPT}" <<'AWKEOF'
# Note: This file is included only for targets which have pokedata workaround
# Patched for mawk compatibility: strtonum() is gawk-only and \y word
# boundaries are not supported by mawk. Use a manual hex parser and match the
# symbol name in the last field ($NF).
function hex2num(s,   n, i, c, v) {
	sub(/^0x/, "", s)
	n = 0
	for (i = 1; i <= length(s); i++) {
		c = tolower(substr(s, i, 1))
		v = index("0123456789abcdef", c) - 1
		n = n * 16 + v
	}
	return n
}
$NF == "pokedata_workaround" { pokedata_workaround = hex2num("0x" $2) }
$NF == "_start"              { start = hex2num("0x" $2) }
END {
	print "#include <unistd.h>"
	print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround-start) ";"
}
AWKEOF
  log "  Patch B: rewrote loader-info.awk for mawk compatibility"
fi

# Patch C: the link2symlink extension of this revision does not learn that a
# source may be a symbolic link of its own: it renames the source to a name
# built from the *content* of that link, relative to PRoot's own working
# directory, and any failure after that rename -- EEXIST from an earlier
# attempt is enough -- leaves the source renamed away while the guest is told
# EPERM.  The patch below (NusaDesk issue #1; upstream termux/proot does not
# carry it) never moves nor unlinks a source that is an ordinary symbolic
# link: without AT_SYMLINK_FOLLOW the new name is another symbolic link with
# the same content, with it the target is resolved and the usual conversion
# applies to that target.  A source that names one of the extension's own
# entries is routed to the group it belongs to instead of being moved into a
# group of its own, and a failure while a source is converted rolls the
# source back.
L2S_SOURCE="${PROOT_SRC}/src/extension/link2symlink/link2symlink.c"
if ! grep -q 'normalize_joined_path' "${L2S_SOURCE}"; then
  git -C "${PROOT_SRC}" apply <<'L2S_PATCH_C'
diff --git a/src/extension/link2symlink/link2symlink.c b/src/extension/link2symlink/link2symlink.c
index 9c67b10..ffc58d2 100644
--- a/src/extension/link2symlink/link2symlink.c
+++ b/src/extension/link2symlink/link2symlink.c
@@ -227,11 +227,14 @@ static int l2s_access(const char *path)
 	int dir_fd;
 
 	if (l2s_entry(path, &dir_fd, &name) < 0)
-		return -1;
+		return errno > 0 ? -errno : -ENOENT;
 
 	/* No AT_SYMLINK_NOFOLLOW: access(2) follows, and a dangling
 	 * intermediate has always counted as a free slot here.  */
-	return (dir_fd < 0) ? access(path, F_OK) : faccessat(dir_fd, name, F_OK, 0);
+	if (dir_fd < 0)
+		return access(path, F_OK) == 0 ? 0 : (errno > 0 ? -errno : -ENOENT);
+
+	return faccessat(dir_fd, name, F_OK, 0) == 0 ? 0 : (errno > 0 ? -errno : -ENOENT);
 }
 
 static int l2s_symlink(const char *target, const char *path)
@@ -240,9 +243,12 @@ static int l2s_symlink(const char *target, const char *path)
 	int dir_fd;
 
 	if (l2s_entry(path, &dir_fd, &name) < 0)
-		return -1;
+		return errno > 0 ? -errno : -ENOENT;
+
+	if (dir_fd < 0)
+		return symlink(target, path) == 0 ? 0 : (errno > 0 ? -errno : -EPERM);
 
-	return (dir_fd < 0) ? symlink(target, path) : symlinkat(target, dir_fd, name);
+	return symlinkat(target, dir_fd, name) == 0 ? 0 : (errno > 0 ? -errno : -EPERM);
 }
 
 static int l2s_unlink(const char *path)
@@ -251,9 +257,12 @@ static int l2s_unlink(const char *path)
 	int dir_fd;
 
 	if (l2s_entry(path, &dir_fd, &name) < 0)
-		return -1;
+		return errno > 0 ? -errno : -ENOENT;
+
+	if (dir_fd < 0)
+		return unlink(path) == 0 ? 0 : (errno > 0 ? -errno : -EPERM);
 
-	return (dir_fd < 0) ? unlink(path) : unlinkat(dir_fd, name, 0);
+	return unlinkat(dir_fd, name, 0) == 0 ? 0 : (errno > 0 ? -errno : -EPERM);
 }
 
 static int l2s_rename(const char *old_path, const char *new_path)
@@ -264,17 +273,18 @@ static int l2s_rename(const char *old_path, const char *new_path)
 	int new_dir_fd;
 
 	if (l2s_entry(old_path, &old_dir_fd, &old_name) < 0)
-		return -1;
+		return errno > 0 ? -errno : -ENOENT;
 	if (l2s_entry(new_path, &new_dir_fd, &new_name) < 0)
-		return -1;
+		return errno > 0 ? -errno : -ENOENT;
 
 	if (old_dir_fd < 0 && new_dir_fd < 0)
-		return rename(old_path, new_path);
+		return rename(old_path, new_path) == 0 ? 0 : (errno > 0 ? -errno : -EPERM);
 
 	/* An absolute path with AT_FDCWD is the side that isn't in the
 	 * l2s directory -- the file being moved into it, typically.  */
 	return renameat(old_dir_fd < 0 ? AT_FDCWD : old_dir_fd, old_name,
-			new_dir_fd < 0 ? AT_FDCWD : new_dir_fd, new_name);
+			new_dir_fd < 0 ? AT_FDCWD : new_dir_fd, new_name) == 0
+		? 0 : (errno > 0 ? -errno : -EPERM);
 }
 
 /**
@@ -294,7 +304,7 @@ static int my_readlink(const char symlink[PATH_MAX], char value[PATH_MAX])
 		? readlink(symlink, value, PATH_MAX)
 		: readlinkat(dir_fd, name, value, PATH_MAX);
 	if (size < 0)
-		return size;
+		return errno > 0 ? -errno : -ENOENT;
 	if (size >= PATH_MAX)
 		return -ENAMETOOLONG;
 	value[size] = '\0';
@@ -475,26 +485,95 @@ static void readlink_proc_fd(struct readlink_proc_fd_state *state)
 	state->substituted = true;
 }
 
+/**
+ * Normalize the absolute path @path -- drop "." components and redundant
+ * slashes, resolve ".." ones -- into @result.  A ".." that would escape
+ * the root is dropped.  The paths normalized here are host paths, hence
+ * always absolute.  This function returns -errno if an error occured,
+ * otherwise 0.
+ */
+static int normalize_joined_path(const char *path, char result[PATH_MAX])
+{
+	const char *cursor = path;
+	size_t length = 0;
+
+	if (path[0] != '/')
+		return -EINVAL;
+
+	while (*cursor != '\0') {
+		const char *start;
+		size_t segment_length;
+
+		while (*cursor == '/')
+			cursor++;
+
+		start = cursor;
+		while (*cursor != '\0' && *cursor != '/')
+			cursor++;
+		segment_length = cursor - start;
+
+		if (segment_length == 0)
+			continue;
+
+		if (segment_length == 1 && start[0] == '.')
+			continue;
+
+		if (segment_length == 2 && start[0] == '.' && start[1] == '.') {
+			while (length > 0 && result[length - 1] != '/')
+				length--;
+			if (length > 0)
+				length--;
+			continue;
+		}
+
+		if (length + segment_length + 2 >= PATH_MAX)
+			return -ENAMETOOLONG;
+
+		result[length++] = '/';
+		memcpy(result + length, start, segment_length);
+		length += segment_length;
+	}
+
+	if (length == 0)
+		result[length++] = '/';
+	result[length] = '\0';
+
+	return 0;
+}
+
 /**
  * Move the path pointed to by @tracee's @sysarg to a new location,
  * symlink the original path to this new one, make @tracee's @sysarg
  * point to the new location.  This function returns -errno if an
  * error occured, otherwise 0.
+ *
+ * @follow tells whether the caller asked for the symbolic link @sysarg
+ * names to be dereferenced, that is, whether the syscall is linkat(2)
+ * with AT_SYMLINK_FOLLOW.  Such a source is resolved here, since the
+ * kernel is never asked to do it.  A source that is an ordinary
+ * symbolic link and is not dereferenced is emulated by another symbolic
+ * link.  Either way the source itself is never renamed nor unlinked:
+ * only the file a regular source names, or the entries of this
+ * extension a source names, are converted.
  */
-static int move_and_symlink_path(Tracee *tracee, Reg sysarg, Reg link_target_sysarg)
+static int move_and_symlink_path(Tracee *tracee, Reg sysarg, Reg link_target_sysarg, bool follow)
 {
 	char original[PATH_MAX];
 	char intermediate[PATH_MAX];
 	char new_intermediate[PATH_MAX];
 	char final[PATH_MAX];
 	char new_final[PATH_MAX];
+	char resolved[PATH_MAX];
 	char * name;
+	char * source_name;
 	struct stat statl;
+	struct stat targetl;
 	ssize_t size;
 	int status;
 	int link_count;
 	int first_link = 1;
 	int intermediate_suffix = 1;
+	int depth = 0;
 
 	/* Note: this path was already canonicalized.  */
 	size = read_string(tracee, original, peek_reg(tracee, CURRENT, sysarg), PATH_MAX);
@@ -503,6 +582,11 @@ static int move_and_symlink_path(Tracee *tracee, Reg sysarg, Reg link_target_sys
 	if (size >= PATH_MAX)
 		return -ENAMETOOLONG;
 
+restart:
+	/* A chain of symbolic links cannot be longer than this.  */
+	if (++depth > 40)
+		return -ELOOP;
+
 	/* Sanity check: directories can't be linked.  */
 	status = lstat(original, &statl);
 	if (status < 0)
@@ -512,6 +596,8 @@ static int move_and_symlink_path(Tracee *tracee, Reg sysarg, Reg link_target_sys
 
 	/* Check if it is a symbolic link.  */
 	if (S_ISLNK(statl.st_mode)) {
+		bool from_machinery = false;
+
 		/* get name */
 		size = my_readlink(original, intermediate);
 		if (size < 0)
@@ -523,8 +609,94 @@ static int move_and_symlink_path(Tracee *tracee, Reg sysarg, Reg link_target_sys
 		else
 			name++;
 
-		if (strncmp(name, PREFIX, strlen(PREFIX)) == 0)
+		if (strncmp(name, PREFIX, strlen(PREFIX)) == 0) {
+			status = lstat(intermediate, &targetl);
+			if (status == 0 && S_ISLNK(targetl.st_mode)) {
+				/* The target is the intermediate of the
+				 * group the source belongs to.  */
+				from_machinery = true;
+			} else {
+				/* Or the source itself is such an
+				 * intermediate, it then names its final
+				 * file directly.  Only that shape belongs
+				 * here; an ordinary link that merely
+				 * points into the directory must not be
+				 * taken for one of our entries.  */
+				source_name = strrchr(original, '/');
+				source_name = (source_name == NULL ? original : source_name + 1);
+
+				if (strncmp(source_name, PREFIX, strlen(PREFIX)) == 0) {
+					strcpy(intermediate, original);
+					from_machinery = true;
+				}
+			}
+		}
+
+		if (from_machinery) {
 			first_link = 0;
+		} else if (!follow) {
+			/* Without AT_SYMLINK_FOLLOW the link names
+			 * the symbolic link itself: emulate it with
+			 * another symbolic link that has the same
+			 * content.  */
+			status = read_path(tracee, final, peek_reg(tracee, CURRENT, link_target_sysarg));
+			if (status < 0)
+				return status;
+
+			status = symlink(intermediate, final);
+			if (status < 0)
+				return errno > 0 ? -errno : -EPERM;
+
+			poke_reg(tracee, SYSARG_RESULT, 0);
+			set_sysnum(tracee, PR_void);
+			return 0;
+		} else {
+			/* With AT_SYMLINK_FOLLOW the link names the
+			 * target of the symbolic link: resolve one
+			 * level and try again with the resolved path.
+			 * A guest-absolute target is translated here;
+			 * a relative one is first joined to the
+			 * directory the symbolic link lies in.  */
+			if (intermediate[0] == '/') {
+				status = translate_path(tracee, resolved, AT_FDCWD, intermediate, false);
+				if (status < 0)
+					return status;
+			} else {
+				name = strrchr(original, '/');
+				if (name == NULL)
+					return -EINVAL;
+
+				*name = '\0';
+				if (snprintf(resolved, PATH_MAX, "%s/%s", original, intermediate) >= PATH_MAX) {
+					*name = '/';
+					return -ENAMETOOLONG;
+				}
+				*name = '/';
+
+				status = normalize_joined_path(resolved, new_intermediate);
+				if (status < 0)
+					return status;
+				strcpy(resolved, new_intermediate);
+			}
+
+			strcpy(original, resolved);
+			goto restart;
+		}
+	} else if (is_l2s_file(original)) {
+		/* The source is the final file of a group: the links it
+		 * already has must keep working, so point the new name at
+		 * its intermediate instead of moving it into a group of
+		 * its own.  */
+		size_t length = strlen(original);
+
+		memcpy(intermediate, original, length - 5);
+		intermediate[length - 5] = '\0';
+
+		status = lstat(intermediate, &targetl);
+		if (status < 0 || !S_ISLNK(targetl.st_mode))
+			return -ENOENT;
+
+		first_link = 0;
 	} else {
 		/* compute new name */
 		name = strrchr(original,'/');
@@ -571,7 +743,7 @@ static int move_and_symlink_path(Tracee *tracee, Reg sysarg, Reg link_target_sys
 		do {
 			sprintf(new_intermediate, "%s%04d", intermediate, intermediate_suffix);
 			intermediate_suffix++;
-		} while ((l2s_access(new_intermediate) != -1) && (intermediate_suffix < 1000));
+		} while ((l2s_access(new_intermediate) == 0) && (intermediate_suffix < 1000));
 		strcpy(intermediate, new_intermediate);
 
 		strcpy(final, intermediate);
@@ -585,13 +757,18 @@ static int move_and_symlink_path(Tracee *tracee, Reg sysarg, Reg link_target_sys
 
 		/* Symlink the intermediate to the final file.  */
 		status = l2s_symlink(final, intermediate);
-		if (status < 0)
+		if (status < 0) {
+			l2s_rename(final, original);
 			return status;
+		}
 
 		/* Symlink the original path to the intermediate one.  */
 		status = symlink(intermediate, original);
-		if (status < 0)
-			return status;
+		if (status < 0) {
+			l2s_unlink(intermediate);
+			l2s_rename(final, original);
+			return errno > 0 ? -errno : -EPERM;
+		}
 	} else {
 		/*Move the original content to new location, by incrementing count at end of path. */
 		size = my_readlink(intermediate, final);
@@ -671,8 +848,13 @@ static int decrement_link_count(Tracee *tracee, Reg sysarg)
 		return 0;
 
 	size = my_readlink(original, intermediate);
-	if (size < 0)
-		return size;
+	if (size < 0) {
+		/* Unreadable although lstat(2) reported a symbolic link:
+		 * nothing can be said about its group, so let the kernel
+		 * unlink it rather than failing the call.  */
+		VERBOSE(tracee, 1, "Skiping deref of unreadable link2symlink \"%s\"", original);
+		return 0;
+	}
 
 	name = strrchr(intermediate, '/');
 	if (name == NULL)
@@ -696,39 +878,59 @@ static int decrement_link_count(Tracee *tracee, Reg sysarg)
 	link_count = atoi(final + strlen(final) - 4);
 	link_count--;
 
-	/* Check if it is or is not the last link to delete */
+	/* Check if it is or is not the last link to delete.
+	 *
+	 * The failures below describe the state of the group, not the
+	 * syscall: a group whose final file is gone is already broken,
+	 * and the tracee's unlink(2) must never be turned into an error
+	 * by this bookkeeping -- the file would then be impossible to
+	 * remove at all.  Hence the skip-and-let-it-through.  */
 	if (link_count > 0) {
 		strncpy(new_final, final, strlen(final) - 4);
 		sprintf(new_final + strlen(final) - 4, "%04d", link_count);
 
 		status = l2s_rename(final, new_final);
-		if (status < 0)
-			return status;
+		if (status < 0) {
+			VERBOSE(tracee, 1, "Skiping deref of broken link2symlink \"%s\" -> \"%s\"", original, intermediate);
+			return 0;
+		}
 		status = notify_extensions(tracee, LINK2SYMLINK_RENAME, (intptr_t) final, (intptr_t) new_final);
 		if (status < 0)
-			return status;
+			return 0;
 
 		strcpy(final, new_final);
 
-		/* Symlink the intermediate to the final file.  */
+		/* Symlink the intermediate to the final file.  The
+		 * intermediate may already be gone, that is exactly what
+		 * recreating it is for.  */
 		status = l2s_unlink(intermediate);
-		if (status < 0)
-			return status;
+		if (status < 0 && status != -ENOENT) {
+			VERBOSE(tracee, 1, "Skiping deref of broken link2symlink \"%s\" -> \"%s\"", original, intermediate);
+			return 0;
+		}
 
 		status = l2s_symlink(final, intermediate);
-		if (status < 0)
-			return status;
+		if (status < 0) {
+			VERBOSE(tracee, 1, "Skiping deref of broken link2symlink \"%s\" -> \"%s\"", original, intermediate);
+			return 0;
+		}
 	} else {
-		/* If it is the last, delete the intermediate and final */
+		/* If it is the last, delete the intermediate and final.
+		 * The intermediate goes first: an interruption leaves an
+		 * orphan final file, never a link that dangles.  */
 		status = l2s_unlink(intermediate);
-		if (status < 0)
-			return status;
+		if (status < 0 && status != -ENOENT) {
+			VERBOSE(tracee, 1, "Skiping deref of broken link2symlink \"%s\" -> \"%s\"", original, intermediate);
+			return 0;
+		}
 		status = l2s_unlink(final);
-		if (status < 0)
-			return status;
+		if (status < 0 && status != -ENOENT) {
+			VERBOSE(tracee, 1, "Skiping deref of broken link2symlink \"%s\" -> \"%s\"", original, intermediate);
+			return 0;
+		}
 		status = notify_extensions(tracee, LINK2SYMLINK_UNLINK, (intptr_t) final, 0);
 		if (status < 0)
-			return status;
+			return 0;
 		}
 
 	return 0;
@@ -1228,7 +1430,7 @@ int link2symlink_callback(Extension *extension, ExtensionEvent event,
 			 *     int symlink(const char *oldpath, const char *newpath);
 			 */
 
-			status = move_and_symlink_path(tracee, SYSARG_1, SYSARG_2);
+			status = move_and_symlink_path(tracee, SYSARG_1, SYSARG_2, false);
 			if (status < 0)
 				return status;
 
@@ -1265,7 +1467,8 @@ int link2symlink_callback(Extension *extension, ExtensionEvent event,
 			 *   newdirfd + newpath -> newpath
 			 */
 
-			status = move_and_symlink_path(tracee, SYSARG_2, SYSARG_4);
+			status = move_and_symlink_path(tracee, SYSARG_2, SYSARG_4,
+							(peek_reg(tracee, CURRENT, SYSARG_5) & AT_SYMLINK_FOLLOW) != 0);
 			if (status < 0)
 				return status;
 
L2S_PATCH_C
  log "  Patch C: fixed the link2symlink handling of symbolic link sources"
fi

# ---------------------------------------------------------------------------
# 4. Build static libtalloc.a with a minimal replace.h shim
# ---------------------------------------------------------------------------
log "Building static libtalloc.a..."
mkdir -p "${TALLOC_BUILD}"
# talloc.c includes samba's replace.h (LGPL-3.0). Bionic already provides every
# libc symbol talloc uses, so a minimal shim suffices and avoids the waf
# cross-compile toolchain (which needs qemu/gawk unavailable here).
cat > "${TALLOC_BUILD}/replace.h" <<'REPEOF'
#ifndef _MINI_REPLACE_H
#define _MINI_REPLACE_H
#define NO_CONFIG_H 1
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <unistd.h>
#include <sys/types.h>
#include <stdbool.h>
#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a,b) ((a) > (b) ? (a) : (b))
#endif
#define _PUBLIC_ __attribute__((visibility("default")))
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))
#define __STRING(x) #x
#define __STRINGSTRING(x) __STRING(x)
#define __LINESTR__ __STRINGSTRING(__LINE__)
#define __location__ __FILE__ ":" __LINESTR__
#endif
REPEOF

( cd "${TALLOC_SRC}"
  "${CC}" -fPIC -O2 -fstack-protector-strong \
    -DNO_CONFIG_H=1 -DHAVE_GETAUXVAL=1 -D__STDC_WANT_LIB_EXT1__=1 \
    -DHAVE_VA_COPY=1 -DHAVE_CONSTRUCTOR_ATTRIBUTE=1 \
    -DTALLOC_BUILD_VERSION_MAJOR=2 -DTALLOC_BUILD_VERSION_MINOR=4 -DTALLOC_BUILD_VERSION_RELEASE=2 \
    -I"${TALLOC_BUILD}" -I"${TALLOC_SRC}" \
    -c talloc.c -o "${TALLOC_BUILD}/talloc.o"
)
llvm-ar rcs "${TALLOC_BUILD}/libtalloc.a" "${TALLOC_BUILD}/talloc.o"
llvm-ranlib "${TALLOC_BUILD}/libtalloc.a"
log "  libtalloc.a: $(llvm-nm "${TALLOC_BUILD}/libtalloc.a" | grep -c ' T ') exported symbols"

# ---------------------------------------------------------------------------
# 5. Build PRoot (PIE executable, bundled 32+64-bit loaders, static talloc)
# ---------------------------------------------------------------------------
log "Building PRoot ${PROOT_VERSION} for arm64-v8a..."
# PRoot's GNUmakefile uses `+=` on CPPFLAGS/CFLAGS/LDFLAGS, so they MUST come
# from the environment (command-line overrides would discard the makefile's
# essential additions like -D_GNU_SOURCE and -ltalloc).
export CC
export STRIP=llvm-strip
export OBJCOPY=llvm-objcopy
export OBJDUMP=llvm-objdump
export CPPFLAGS="-DVERSION=\"${PROOT_VERSION}\" -DARG_MAX=131072 -I${TALLOC_SRC} -I${TALLOC_BUILD}"
export CFLAGS="-fPIE -O2 -fstack-protector-strong"
export LDFLAGS="-L${TALLOC_BUILD} -pie ${PAGE_SIZE_FLAG} ${THREADS_FLAG}"

build_one() {
  make -C "${PROOT_SRC}/src" clean >/dev/null 2>&1 || true
  make -C "${PROOT_SRC}/src" proot >/dev/null 2>&1
  llvm-strip --strip-all "${PROOT_SRC}/src/proot" -o "$1"
}

OUT_BIN="${BUILD_ROOT}/${OUTPUT_NAME}"
build_one "${OUT_BIN}"
log "Built: ${OUT_BIN} ($(stat -c%s "${OUT_BIN}") bytes)"

# The loader is an order-only byproduct of `make proot` (src/loader/loader).
# It is a freestanding static EXEC, not a PIE, so the readelf verification in
# step 7 does not apply; it is verified by hash only.
OUT_LOADER="${BUILD_ROOT}/${LOADER_OUTPUT_NAME}"
llvm-strip --strip-all "${PROOT_SRC}/src/loader/loader" -o "${OUT_LOADER}"
log "Built loader: ${OUT_LOADER} ($(stat -c%s "${OUT_LOADER}") bytes)"
ACTUAL_LOADER_SHA256="$(sha256sum "${OUT_LOADER}" | awk '{print $1}')"
log "Loader stripped sha256: ${ACTUAL_LOADER_SHA256}"

# ---------------------------------------------------------------------------
# 6. Reproducibility verification
# ---------------------------------------------------------------------------
ACTUAL_SHA256="$(sha256sum "${OUT_BIN}" | awk '{print $1}')"
log "Stripped sha256: ${ACTUAL_SHA256}"

if [[ "${REPRO_CHECK}" -eq 1 ]]; then
  log "Reproducibility check: rebuilding and comparing..."
  SECOND="${BUILD_ROOT}/${OUTPUT_NAME}.second"
  build_one "${SECOND}"
  SECOND_SHA256="$(sha256sum "${SECOND}" | awk '{print $1}')"
  if cmp -s "${OUT_BIN}" "${SECOND}"; then
    log "  PASS: bit-identical across clean rebuilds (${SECOND_SHA256})"
  else
    die "  FAIL: builds are not bit-identical (${ACTUAL_SHA256} vs ${SECOND_SHA256})"
  fi
  rm -f "${SECOND}"
fi

# Fail closed: a mismatch between the actual stripped output hash and the
# pinned expected hash means the build is not reproducible against the
# recorded baseline. Refuse to install an unverified binary. This gate runs
# after the --repro-check comparison so that mode still reports its
# bit-identical result before the pinned-hash enforcement.
if [[ "${ACTUAL_LOADER_SHA256}" != "${EXPECTED_LOADER_SHA256}" ]]; then
  die "Loader hash mismatch: actual ${ACTUAL_LOADER_SHA256} != expected ${EXPECTED_LOADER_SHA256}"
fi
log "Loader hash matches expected pinned output."
if [[ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]]; then
  die "PRoot hash mismatch: actual ${ACTUAL_SHA256} != expected ${EXPECTED_SHA256}"
fi
log "Hash matches expected pinned output."

# ---------------------------------------------------------------------------
# 7. readelf verification of the packaged executable
# ---------------------------------------------------------------------------
verify_elf() {
  local bin="$1"
  local err=0
  local class type machine interp
  class="$(readelf -h "${bin}" | awk '/Class:/{print $2}')"
  type="$(readelf -h "${bin}" | awk '/Type:/{print $0}')"
  machine="$(readelf -h "${bin}" | awk '/Machine:/{print $2}')"
  interp="$(readelf -p .interp "${bin}" 2>/dev/null | awk -F']' '/linker/{gsub(/^ +/,"",$2); print $2}')"
  # LOAD segment alignments (last column of each LOAD line).
  local maxalign
  maxalign="$(readelf -lW "${bin}" | awk '/^[[:space:]]*LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
  # GNU_STACK flags are the 7th whitespace field on the GNU_STACK line.
  local stack
  stack="$(readelf -lW "${bin}" | awk '/^[[:space:]]*GNU_STACK/{print $7}')"

  [[ "${class}" == "ELF64" ]] || { log "  FAIL class=${class}"; err=1; }
  echo "${type}" | grep -q "Position-Independent Executable" || { log "  FAIL type=${type}"; err=1; }
  [[ "${machine}" == "AArch64" ]] || { log "  FAIL machine=${machine}"; err=1; }
  echo "${interp}" | grep -q "linker64" || { log "  FAIL interp=${interp}"; err=1; }
  # Every LOAD segment must be aligned to 0x4000 (16384) for 16 KB pages.
  local a
  for a in ${maxalign}; do [[ "${a}" == "0x4000" ]] || { log "  FAIL LOAD align=${a}"; err=1; }; done
  # GNU_STACK must be RW (not RWE); RWE means an executable stack.
  [[ "${stack}" == "RW" ]] || { log "  FAIL GNU_STACK=${stack} (execstack!)"; err=1; }
  return ${err}
}

log "readelf verification:"
if verify_elf "${OUT_BIN}"; then
  log "  PASS: ELF64 AArch64 PIE, Android linker64, 16 KB LOAD alignment, noexecstack"
else
  die "readelf verification failed."
fi

# ---------------------------------------------------------------------------
# 8. Install into jniLibs/arm64-v8a
# ---------------------------------------------------------------------------
if [[ "${DO_INSTALL}" -eq 1 ]]; then
  mkdir -p "${JNILIBS_DIR}"
  cp "${OUT_BIN}" "${JNILIBS_DIR}/${OUTPUT_NAME}"
  cp "${OUT_LOADER}" "${JNILIBS_DIR}/${LOADER_OUTPUT_NAME}"
  log "Installed: ${JNILIBS_DIR}/${OUTPUT_NAME}"
  log "  installed sha256: $(sha256sum "${JNILIBS_DIR}/${OUTPUT_NAME}" | awk '{print $1}')"
  log "Installed: ${JNILIBS_DIR}/${LOADER_OUTPUT_NAME}"
  log "  installed sha256: $(sha256sum "${JNILIBS_DIR}/${LOADER_OUTPUT_NAME}" | awk '{print $1}')"
fi

log "Done."
