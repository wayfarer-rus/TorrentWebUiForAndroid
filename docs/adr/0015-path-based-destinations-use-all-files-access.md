# Path-Based Destinations Use All Files Access

Milestone 4 replaces generic SAF destination access with Android All Files Access (`MANAGE_EXTERNAL_STORAGE`) so every approved destination can be represented by the real filesystem path used by the torrent engine and device SSH tools. The WebUI still limits ordinary selection and management to an explicitly approved folder, despite the broader platform permission.
