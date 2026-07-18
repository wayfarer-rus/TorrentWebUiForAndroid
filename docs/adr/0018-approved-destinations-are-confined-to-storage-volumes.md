# Approved Destinations Are Confined to Storage Volumes

Although Android All Files Access is broad, the product permits an Approved Destination only when its canonical writable path is inside a backend-reported shared or external storage volume. The backend rejects system directories, app-private directories, and symlink escapes; this preserves real SSH-copyable paths without turning the WebUI into a general filesystem manager.
