cmake_minimum_required(VERSION 3.22.1)

project(TorrentJNI)

# ---------------------------------------------------------------------------
# Boost (headers-only)
# ---------------------------------------------------------------------------
set(Boost_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/../dep CACHE PATH "Boost include dir")
set(Boost_NO_SYSTEM_PATHS ON CACHE BOOL "Prefer our Boost")

# ---------------------------------------------------------------------------
# libtorrent-rasterbar 2.0.10 (static, minimal features)
# ---------------------------------------------------------------------------
set(lt_USE_OPENSSL OFF CACHE BOOL "Disable OpenSSL")
set(lt_USE_LIBRESOLVE OFF CACHE BOOL "Disable libresolve")
set(lt_USE_STREAMING OFF CACHE BOOL "Disable streaming")
set(lt_ENABLE_EXAMPLES OFF CACHE BOOL "No examples")
set(lt_ENABLE_TESTS OFF CACHE BOOL "No tests")
set(lt_ENABLE_PYTHON_BINDINGS OFF CACHE BOOL "No Python")
set(lt_STRICT_ANDROID ON CACHE BOOL "Android-specific build")
set(lt_INSTALL OFF CACHE BOOL "Skip install step")

add_subdirectory(
    ${CMAKE_SOURCE_DIR}/../libtorrent
    ${CMAKE_BINARY_DIR}/libtorrent-build
)

# ---------------------------------------------------------------------------
# JNI shared library
# ---------------------------------------------------------------------------
add_library(torrent-jni SHARED
    src/main/jni/torrent_jni.cpp
)

# Include libtorrent headers AND boost headers for JNI
target_include_directories(torrent-jni PRIVATE
    ${CMAKE_SOURCE_DIR}/src/main/jni
    ${CMAKE_SOURCE_DIR}/../libtorrent/include
    ${CMAKE_SOURCE_DIR}/../dep
)

target_link_libraries(torrent-jni
    PRIVATE torrent-rasterbar
    PRIVATE log
    PRIVATE android
)

target_compile_options(torrent-jni PRIVATE -std=c++17 -Wall)
