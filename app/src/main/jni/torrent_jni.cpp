#include <jni.h>
#include <string>
#include <unordered_map>
#include <vector>
#include <mutex>
#include <cstring>
#include <algorithm>
#include <sys/stat.h>

#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/session_status.hpp>
#include <libtorrent/alert.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/error_code.hpp>
#include <libtorrent/info_hash.hpp>
#include <libtorrent/hex.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/add_torrent_params.hpp>

#include <android/log.h>

#define TAG "TorrentJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Per-session state
// ---------------------------------------------------------------------------
struct SessionEntry {
    std::unique_ptr<lt::session> session;
    std::unordered_map<uint64_t, lt::torrent_handle> torrents;
    std::unordered_map<uint64_t, std::string> torrent_save_paths;
    uint64_t next_torrent_id = 1;
};

static std::unordered_map<uint64_t, SessionEntry> g_sessions;
static std::mutex g_mutex;
static uint64_t g_next_session_id = 1;

// ---------------------------------------------------------------------------
// Helpers
static std::string pop_last_error(lt::session& s) {
    try {
        std::vector<lt::alert*> alerts;
        s.pop_alerts(&alerts);
        for (auto* alert : alerts) {
            // Check if this is an error alert by category
            if (alert->category() & lt::alert::error_notification) {
                return alert->message();
            }
        }
    } catch (std::exception const& e) {
        return e.what();
    }
    return "";
}

// ---------------------------------------------------------------------------
// JNI: Session management
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeInit(
        JNIEnv*, jobject, jstring) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);

        lt::session_params params;
        params.settings.set_int(lt::settings_pack::alert_mask,
            static_cast<int>(lt::alert::error_notification | lt::alert::storage_notification
                             | lt::alert::status_notification | lt::alert::tracker_notification
                             | lt::alert::performance_warning));
        params.settings.set_int(lt::settings_pack::alert_queue_size, 256);
        params.settings.set_int(lt::settings_pack::connections_limit, 160);
        params.settings.set_bool(lt::settings_pack::enable_upnp, false);
        params.settings.set_bool(lt::settings_pack::enable_natpmp, false);
        params.settings.set_bool(lt::settings_pack::enable_dht, true);

        uint64_t id = g_next_session_id++;
        g_sessions[id].session = std::make_unique<lt::session>(std::move(params));

        LOGI("Session %llu created", id);
        return static_cast<jlong>(id);
    } catch (std::exception const& e) {
        LOGE("nativeInit failed: %s", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeDestroy(
        JNIEnv*, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        g_sessions.erase(id);
        LOGI("Session %llu destroyed", id);
        return JNI_TRUE;
    } catch (std::exception const& e) {
        LOGE("nativeDestroy failed: %s", e.what());
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// JNI: Version
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(lt::version());
}

// ---------------------------------------------------------------------------
// JNI: Add magnet
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeAddMagnet(
        JNIEnv* env, jobject, jlong jId, jstring jMagnet, jstring jDestinationPath,
        jboolean jStartPaused, jboolean jMetadataOnly) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return -1;

        const char* cmag = env->GetStringUTFChars(jMagnet, nullptr);
        std::string magnet(cmag);
        env->ReleaseStringUTFChars(jMagnet, cmag);

        const char* cdestination = env->GetStringUTFChars(jDestinationPath, nullptr);
        std::string destinationPath(cdestination);
        env->ReleaseStringUTFChars(jDestinationPath, cdestination);

        lt::add_torrent_params p = lt::parse_magnet_uri(magnet);
        p.save_path = destinationPath;
        if (jStartPaused == JNI_TRUE) {
            p.flags &= ~lt::torrent_flags::auto_managed;
            p.flags |= lt::torrent_flags::paused;
        }
        if (jMetadataOnly == JNI_TRUE) {
            // upload_mode permits metadata exchange but never requests payload pieces.
            p.flags |= lt::torrent_flags::upload_mode;
        }

        lt::torrent_handle h = sit->second.session->add_torrent(p);
        uint64_t torrentId = sit->second.next_torrent_id++;
        sit->second.torrents[torrentId] = h;
        sit->second.torrent_save_paths[torrentId] = std::move(destinationPath);

        LOGI("Added torrent %llu", torrentId);
        return static_cast<jlong>(torrentId);
    } catch (std::exception const&) {
        LOGE("nativeAddMagnet failed");
        return -1;
    }
}

// ---------------------------------------------------------------------------
// JNI: Inspect torrent-owned data at the current save path.
// 0 = metadata pending, 1 = no owned files present, 2 = owned data present.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeInspectTorrentOwnedData(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto sit = g_sessions.find(static_cast<uint64_t>(jId));
        if (sit == g_sessions.end()) return 0;
        auto const tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        auto pit = sit->second.torrent_save_paths.find(tid);
        if (tit == sit->second.torrents.end() || pit == sit->second.torrent_save_paths.end()) return 0;
        auto info = tit->second.torrent_file();
        if (!info) return 0;
        auto const& files = info->files();
        for (lt::file_index_t index : files.file_range()) {
            std::string path = pit->second + "/" + files.file_path(index);
            struct stat value {};
            if (::lstat(path.c_str(), &value) == 0) return 2;
        }
        return 1;
    } catch (std::exception const&) {
        LOGE("nativeInspectTorrentOwnedData failed");
        return 0;
    }
}

// ---------------------------------------------------------------------------
// JNI: Pause / Resume / Remove
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativePauseTorrent(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return JNI_FALSE;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return JNI_FALSE;

        LOGI("Pausing torrent %llu", (uint64_t)tid);
        // User/storage safety pauses must not be undone by libtorrent auto-management.
        // Clear auto-managed first, then set the paused flag synchronously.
        tit->second.unset_flags(lt::torrent_flags::auto_managed | lt::torrent_flags::stop_when_ready);
        tit->second.set_flags(lt::torrent_flags::paused);
        return JNI_TRUE;
    } catch (std::exception const& e) {
        LOGE("nativePauseTorrent failed: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeResumeMetadataOnly(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return JNI_FALSE;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return JNI_FALSE;

        LOGI("Resuming metadata-only validation for torrent %llu", tid);
        tit->second.set_flags(lt::torrent_flags::upload_mode);
        tit->second.unset_flags(lt::torrent_flags::stop_when_ready);
        tit->second.resume();
        return JNI_TRUE;
    } catch (std::exception const& e) {
        LOGE("nativeResumeMetadataOnly failed: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeResumeTorrent(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return JNI_FALSE;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return JNI_FALSE;

        LOGI("Resuming torrent %llu", tid);
        tit->second.unset_flags(lt::torrent_flags::upload_mode | lt::torrent_flags::stop_when_ready);
        tit->second.resume();
        return JNI_TRUE;
    } catch (std::exception const& e) {
        LOGE("nativeResumeTorrent failed: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeRemoveTorrent(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId, jboolean jDeleteFiles) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return JNI_FALSE;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return JNI_FALSE;

        LOGI("Removing torrent %llu, delete_files=%d", tid, (int)jDeleteFiles);
        int flags = jDeleteFiles ? lt::session::delete_files : 0;
        sit->second.session->remove_torrent(
            tit->second, static_cast<lt::remove_flags_t>(flags));
        sit->second.torrents.erase(tit);
        sit->second.torrent_save_paths.erase(tid);
        return JNI_TRUE;
    } catch (std::exception const& e) {
        LOGE("nativeRemoveTorrent failed: %s", e.what());
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// JNI: Get torrent status
// Returns jlongArray: [id, progress*1000, downloadRate, uploadRate, peers, stateCode]
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetTorrentStatus(
        JNIEnv* env, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return nullptr;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return nullptr;

        lt::torrent_status st = tit->second.status(lt::torrent_handle::query_name);
        // Check if torrent is paused by examining the pause flag from handle flags (more reliable than status.flags).
        lt::torrent_flags_t handleFlags = tit->second.flags();
        bool paused = (handleFlags & lt::torrent_flags::paused) != 0;
        LOGI("Torrent %llu state=%d name=%s paused=%d (handleFlags=0x%x, status.flags=0x%x)",
             (uint64_t)tid, (int)st.state, st.name.c_str(), (int)paused,
             static_cast<unsigned>(handleFlags), static_cast<unsigned>(st.flags));

        jlong values[7] = {
            static_cast<jlong>(tid),
            static_cast<jlong>(st.progress * 1000.0f),
            static_cast<jlong>(st.download_rate),
            static_cast<jlong>(st.upload_rate),
            static_cast<jlong>(st.num_peers),
            static_cast<jlong>(static_cast<int>(st.state)),
            static_cast<jlong>(paused ? 1 : 0)
        };

        jlongArray arr = env->NewLongArray(7);
        env->SetLongArrayRegion(arr, 0, 7, values);
        return arr;
    } catch (std::exception const& e) {
        LOGE("nativeGetTorrentStatus failed: %s", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetTorrentName(
        JNIEnv* env, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return env->NewStringUTF("");

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return env->NewStringUTF("");

        lt::torrent_status st = tit->second.status(lt::torrent_handle::query_name);
        LOGI("Torrent %llu state=%d name=%s", tid, (int)st.state, st.name.c_str());
        return env->NewStringUTF(st.name.c_str());
    } catch (std::exception const& e) {
        LOGE("nativeGetTorrentName failed: %s", e.what());
        return env->NewStringUTF("");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetLastError(
        JNIEnv* env, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return env->NewStringUTF("Session not found");
        std::string err = pop_last_error(*sit->second.session);
        return env->NewStringUTF(err.c_str());
    } catch (std::exception const& e) {
        return env->NewStringUTF(e.what());
    }
}

// ---------------------------------------------------------------------------
// JNI: Get the save path for one torrent
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetTorrentSavePath(
        JNIEnv* env, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return env->NewStringUTF("");

        uint64_t torrentId = static_cast<uint64_t>(jTorrentId);
        auto path = sit->second.torrent_save_paths.find(torrentId);
        if (path == sit->second.torrent_save_paths.end()) return env->NewStringUTF("");
        return env->NewStringUTF(path->second.c_str());
    } catch (std::exception const& e) {
        return env->NewStringUTF(e.what());
    }
}

// ---------------------------------------------------------------------------
// JNI: Get all torrent IDs
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetAllTorrentIds(
        JNIEnv* env, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) {
            return env->NewLongArray(0);
        }

        auto const& torrents = sit->second.torrents;
        jlongArray arr = env->NewLongArray(static_cast<jsize>(torrents.size()));
        int i = 0;
        for (auto const& [tid, _] : torrents) {
            jlong val = static_cast<jlong>(tid);
            env->SetLongArrayRegion(arr, i, 1, &val);
            i++;
        }
        return arr;
    } catch (std::exception const& e) {
        LOGE("nativeGetAllTorrentIds failed: %s", e.what());
        return env->NewLongArray(0);
    }
}

// ---------------------------------------------------------------------------
// JNI: Get all pending alerts as a JSON string
// Format: [{"type":"...","message":"...","category":"...","torrent_id":1,"info_hash":"..."}, ...]
// ---------------------------------------------------------------------------

static std::string escape_json_string(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;      break;
        }
    }
    return out;
}

static std::string alert_to_json(SessionEntry const& entry, lt::alert* alert) {
    std::string json;
    json += "{";
    json += "\"type\":\"";
    json += escape_json_string(alert->what());
    json += "\",";

    json += "\"message\":\"";
    json += escape_json_string(alert->message());
    json += "\",";

    // Category: extract alert type name (e.g. "state_changed" from "state_changed_alert")
    std::string cat = alert->what();
    if (cat.size() > 6 && cat.substr(cat.size() - 6) == "_alert") {
        cat = cat.substr(0, cat.size() - 6);
    }
    json += "\"category\":\"";
    json += escape_json_string(cat);
    json += "\",";

    // Correlate with the stable JNI torrent ID first. Unlike a v1-only info hash,
    // this remains available for v2-only torrents and before metadata is complete.
    int64_t torrent_id = -1;
    std::string info_hash;
    if (auto const* torrent_alert = dynamic_cast<lt::torrent_alert const*>(alert)) {
        try {
            if (torrent_alert->handle.is_valid()) {
                for (auto const& [candidate_id, handle] : entry.torrents) {
                    if (handle == torrent_alert->handle) {
                        torrent_id = static_cast<int64_t>(candidate_id);
                        break;
                    }
                }
                auto const hashes = torrent_alert->handle.info_hashes();
                if (hashes.has_v1()) {
                    info_hash = lt::aux::to_hex(hashes.v1.to_string());
                }
            }
        } catch (...) {
            torrent_id = -1;
            info_hash.clear();
        }
    }
    json += "\"torrent_id\":";
    json += std::to_string(torrent_id);
    json += ",";
    json += "\"info_hash\":\"";
    json += escape_json_string(info_hash);
    json += "\"";
    json += "}";
    return json;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetAllAlerts(
        JNIEnv* env, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) {
            return env->NewStringUTF("[]");
        }

        std::vector<lt::alert*> alerts;
        sit->second.session->pop_alerts(&alerts);

        if (alerts.empty()) {
            return env->NewStringUTF("[]");
        }

        // Build JSON array string.
        std::string json = "[";
        for (size_t i = 0; i < alerts.size(); ++i) {
            if (i > 0) json += ",";
            json += alert_to_json(sit->second, alerts[i]);
        }
        json += "]";

        return env->NewStringUTF(json.c_str());
    } catch (std::exception const& e) {
        LOGE("nativeGetAllAlerts failed: %s", e.what());
        return env->NewStringUTF("[]");
    }
}

// ---------------------------------------------------------------------------
// JNI: Get all torrent info_hash values as a string array (hex-encoded)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetAllTorrentHashes(
        JNIEnv* env, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) {
            return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
        }

        auto const& torrents = sit->second.torrents;
        jobjectArray arr = env->NewObjectArray(
            static_cast<jsize>(torrents.size()),
            env->FindClass("java/lang/String"),
            nullptr);

        jstring emptyStr = env->NewStringUTF("");
        int i = 0;
        for (auto const& [tid, handle] : torrents) {
            // Return empty string for all torrents to avoid crashes from info_hashes() calls.
            // This is a Stage 1 limitation; full hash retrieval will be implemented later.
            env->SetObjectArrayElement(arr, i, emptyStr);
            i++;
        }
        env->DeleteLocalRef(emptyStr);
        return arr;
    } catch (std::exception const& e) {
        LOGE("nativeGetAllTorrentHashes failed: %s", e.what());
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
}

// ---------------------------------------------------------------------------
// JNI: Look up torrent ID by info_hash (hex-encoded string)
// Returns -1 if not found.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetTorrentIdByHash(
        JNIEnv* env, jobject, jlong jId, jstring jHashHex) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t sid = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(sid);
        if (sit == g_sessions.end()) return -1L;

        const char* hashStr = env->GetStringUTFChars(jHashHex, nullptr);
        std::string target(hashStr);
        env->ReleaseStringUTFChars(jHashHex, hashStr);

        // Normalize: lowercase, 40-char hex.
        std::transform(target.begin(), target.end(), target.begin(), ::tolower);

        for (auto const& [tid, handle] : sit->second.torrents) {
            try {
                if (!handle.is_valid()) continue;
                lt::sha1_hash hash = handle.info_hashes().v1;
                if (hash.is_all_zeros()) continue;
                std::string h = lt::aux::to_hex(hash.to_string());
                std::transform(h.begin(), h.end(), h.begin(), ::tolower);
                if (h == target) return static_cast<jlong>(tid);
            } catch (...) {
                // Skip torrents whose metadata isn't available yet.
            }
        }
        return -1L;
    } catch (std::exception const& e) {
        LOGE("nativeGetTorrentIdByHash failed: %s", e.what());
        return -1L;
    }
}

// ---------------------------------------------------------------------------
// JNI: Save torrent resume data (for checkpointing)
// Asynchronous: calls save_resume_data() and returns immediately.
// The actual data is retrieved via the save_resume_data_alert posted to the alert queue.
// For M3, we use the QueueStore for durable persistence; this method is a no-op placeholder.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeSaveTorrentResumeData(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t sid = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(sid);
        if (sit == g_sessions.end()) return JNI_FALSE;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return JNI_FALSE;

        // Request save resume data (asynchronous, will post save_resume_data_alert)
        tit->second.save_resume_data();
        return JNI_TRUE;
    } catch (std::exception const& e) {
        LOGE("nativeSaveTorrentResumeData failed: %s", e.what());
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// JNI: Load torrent resume data (for recovery)
// Returns null — native resume data is not available in libtorrent 2.0.10
// without processing the save_resume_data_alert asynchronously.
// QueueStore handles durable persistence for M3.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeLoadTorrentResumeData(
        JNIEnv*, jobject, jlong, jlong) {
    // Native resume data requires async alert processing; not available synchronously.
    // QueueStore handles durable persistence for M3.
    return nullptr;
}

// ---------------------------------------------------------------------------
// JNI: Move torrent storage to a new destination (async)
// libtorrent posts storage_moved_alert or storage_moved_failed_alert.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeMoveStorage(
        JNIEnv* env, jobject, jlong jId, jlong jTorrentId, jstring jTargetPath,
        jboolean jReuseExisting) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return JNI_FALSE;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return JNI_FALSE;

        const char* cpath = env->GetStringUTFChars(jTargetPath, nullptr);
        std::string targetPath(cpath);
        env->ReleaseStringUTFChars(jTargetPath, cpath);

        auto const flags = jReuseExisting == JNI_TRUE
            ? lt::move_flags_t::dont_replace
            : lt::move_flags_t::always_replace_files;
        tit->second.move_storage(targetPath, flags);
        // Keep reporting the durable source until Kotlin receives storage_moved_alert
        // and commits the target after its queue update succeeds.
        return JNI_TRUE;
    } catch (std::exception const&) {
        LOGE("nativeMoveStorage failed");
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// JNI: Roll the live libtorrent handle back to the durable canonical source.
// Completion/failure is correlated through the normal storage move alerts.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeRollbackStorage(
        JNIEnv* env, jobject, jlong jId, jlong jTorrentId, jstring jSourcePath) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto sit = g_sessions.find(static_cast<uint64_t>(jId));
        if (sit == g_sessions.end()) return JNI_FALSE;
        auto tit = sit->second.torrents.find(static_cast<uint64_t>(jTorrentId));
        if (tit == sit->second.torrents.end() || !tit->second.is_valid()) return JNI_FALSE;
        const char* cpath = env->GetStringUTFChars(jSourcePath, nullptr);
        std::string sourcePath(cpath);
        env->ReleaseStringUTFChars(jSourcePath, cpath);
        // The preserved canonical source must never be overwritten by partial target data.
        tit->second.move_storage(sourcePath, lt::move_flags_t::dont_replace);
        return JNI_TRUE;
    } catch (std::exception const&) {
        LOGE("nativeRollbackStorage failed");
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// JNI: Verify the torrent's current storage using libtorrent piece hashing.
// Completion is reported through torrent_checked_alert.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeVerifyTorrent(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto sit = g_sessions.find(static_cast<uint64_t>(jId));
        if (sit == g_sessions.end()) return JNI_FALSE;
        auto tit = sit->second.torrents.find(static_cast<uint64_t>(jTorrentId));
        if (tit == sit->second.torrents.end() || !tit->second.is_valid()) return JNI_FALSE;
        // Libtorrent does not hash a paused torrent. stop_when_ready pauses at the
        // post-check state boundary so corrupt target data cannot be downloaded/repaired
        // before Kotlin classifies the verification result.
        tit->second.set_flags(lt::torrent_flags::stop_when_ready);
        tit->second.unset_flags(lt::torrent_flags::paused | lt::torrent_flags::upload_mode);
        tit->second.force_recheck();
        return JNI_TRUE;
    } catch (std::exception const&) {
        LOGE("nativeVerifyTorrent failed");
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// JNI: Commit the tracked save path after durable move finalization.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeCommitTorrentSavePath(
        JNIEnv* env, jobject, jlong jId, jlong jTorrentId, jstring jTargetPath) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        if (sit->second.torrents.find(tid) == sit->second.torrents.end()) return;

        const char* cpath = env->GetStringUTFChars(jTargetPath, nullptr);
        sit->second.torrent_save_paths[tid] = cpath;
        env->ReleaseStringUTFChars(jTargetPath, cpath);
    } catch (std::exception const&) {
        LOGE("nativeCommitTorrentSavePath failed");
    }
}

// ---------------------------------------------------------------------------
// JNI: Remove torrent resume data (called when removing a torrent)
// No-op for M3 — QueueStore handles cleanup.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeRemoveTorrentResumeData(
        JNIEnv*, jobject, jlong, jlong) {
    // No-op for M3 — QueueStore handles cleanup.
}
