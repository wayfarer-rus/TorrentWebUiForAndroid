# Native Startup Owns Platform Permission Onboarding

Android startup requests and verifies every required platform permission before the torrent service becomes usable, including All Files Access for path-based destinations. The WebUI is a remote state-and-control layer after startup: it controls application-specific settings and torrent behavior, but does not own Android permission onboarding. Milestone 4 remains storage-focused and introduces no port or torrent-parameter configuration; its shared settings-model seam supports those later controls. This supersedes ADR 0013.
