# Scripts

Utility and build scripts for the project.

## Conventions

- Scripts should be idempotent where possible.
- Document prerequisites and expected output.
- Use shell scripts (`#!/usr/bin/env bash`) for cross-platform compatibility.
- Keep scripts focused on a single task.

## Planned Scripts

| Script | Purpose |
|--------|---------|
| `build-native.sh` | Build native libtorrent library for target ABI |
| `test-device.sh` | Install and run device validation tests |
| `clean-all.sh` | Clean Gradle, CMake, and build outputs |
