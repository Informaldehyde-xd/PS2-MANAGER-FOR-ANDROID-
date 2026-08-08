# libcue2pops.so

The "Convert to VCD (POPS)" feature (`Cue2PopsConverter.kt`) shells out to a
native binary named **libcue2pops.so** (the `lib*.so` name/prefix is required
so Android's installer will extract it to `nativeLibraryDir`, even though
it's an executable, not a JNI library).

**You no longer need to build or drop this file in manually.** It's compiled
automatically as part of the normal Gradle build via
`app/src/main/cpp/CMakeLists.txt`, which pulls in the real CUE2POPS v2.0
source from https://github.com/israpps/cue2pops.

- **Local build:** clone the sources into `app/src/main/cpp/cue2pops` once
  (`git clone --depth 1 https://github.com/israpps/cue2pops app/src/main/cpp/cue2pops`),
  then just run `gradle assembleDebug` as usual — CMake builds it and Android
  Gradle Plugin writes the result straight into this folder, per-ABI.
- **CI build:** the GitHub Actions workflow (`.github/workflows/Build.yml`)
  does that clone step automatically before building, so a fresh checkout
  just works with no manual setup.

Only `arm64-v8a` and `armeabi-v7a` are built by default (see `abiFilters` in
`app/build.gradle.kts`) since that covers essentially all real Android
phones/tablets.

If `libcue2pops.so` isn't present for a device's ABI at runtime, the
conversion feature will fail with a clear error message rather than crashing.
