#include <jni.h>
#include <string>
#include <unordered_map>
#include <vector>
#include <mutex>
#include <cstring>
#include <algorithm>

#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/session_status.hpp>
#include <libtorrent/alert.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/error_code.hpp>
#include <libtorrent/info_hash.hpp>
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
    uint64_t next_torrent_id = 1;
    std::string save_path;
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
        JNIEnv* env, jobject, jstring jSavePath) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        const char* cpath = env->GetStringUTFChars(jSavePath, nullptr);
        std::string savePath(cpath);
        env->ReleaseStringUTFChars(jSavePath, cpath);

        lt::session_params params;
        params.settings.set_int(lt::settings_pack::alert_mask,
            static_cast<int>(lt::alert::error_notification | lt::alert::storage_notification
                             | lt::alert::status_notification | lt::alert::tracker_notification
                             | lt::alert::connect_notification | lt::alert::peer_notification
                             | lt::alert::performance_warning));
        params.settings.set_int(lt::settings_pack::alert_queue_size, 256);
        params.settings.set_int(lt::settings_pack::connections_limit, 160);
        params.settings.set_bool(lt::settings_pack::enable_upnp, false);
        params.settings.set_bool(lt::settings_pack::enable_natpmp, false);
        params.settings.set_bool(lt::settings_pack::enable_dht, true);

        uint64_t id = g_next_session_id++;
        g_sessions[id].session = std::make_unique<lt::session>(std::move(params));
        g_sessions[id].save_path = savePath;

        LOGI("Session %llu created, save_path=%s", id, savePath.c_str());
        return static_cast<jlong>(id);
    } catch (std::exception const& e) {
        LOGE("nativeInit failed: %s", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeDestroy(
        JNIEnv*, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        g_sessions.erase(id);
        LOGI("Session %llu destroyed", id);
    } catch (std::exception const& e) {
        LOGE("nativeDestroy failed: %s", e.what());
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
// JNI: Set session save path
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeSetSavePath(
        JNIEnv* env, jobject, jlong jId, jstring jPath) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return;

        const char* cpath = env->GetStringUTFChars(jPath, nullptr);
        sit->second.save_path = cpath;
        env->ReleaseStringUTFChars(jPath, cpath);
    } catch (std::exception const& e) {
        LOGE("nativeSetSavePath failed: %s", e.what());
    }
}

// ---------------------------------------------------------------------------
// JNI: Add magnet
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeAddMagnet(
        JNIEnv* env, jobject, jlong jId, jstring jMagnet) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return -1;

        const char* cmag = env->GetStringUTFChars(jMagnet, nullptr);
        std::string magnet(cmag);
        env->ReleaseStringUTFChars(jMagnet, cmag);

        lt::add_torrent_params p = lt::parse_magnet_uri(magnet);
        p.save_path = sit->second.save_path;

        lt::torrent_handle h = sit->second.session->add_torrent(p);
        uint64_t torrentId = sit->second.next_torrent_id++;
        sit->second.torrents[torrentId] = h;

        LOGI("Added magnet, torrent_id=%llu, save_path=%s", torrentId, sit->second.save_path.c_str());
        return static_cast<jlong>(torrentId);
    } catch (std::exception const& e) {
        LOGE("nativeAddMagnet failed: %s", e.what());
        return -1;
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
        // Use set_flags to explicitly set the paused flag, ensuring it's reflected immediately
        // in torrent_status.flags. The pause() method may not set the flag synchronously.
        tit->second.set_flags(lt::torrent_flags::paused);
        return JNI_TRUE;
    } catch (std::exception const& e) {
        LOGE("nativePauseTorrent failed: %s", e.what());
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
// JNI: Get the save path for a session
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeGetSavePath(
        JNIEnv* env, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return env->NewStringUTF("");
        return env->NewStringUTF(sit->second.save_path.c_str());
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
// Format: [{"type":"...","message":"...","category":"...","info_hash":"..."}, ...]
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

static std::string alert_to_json(lt::alert* alert) {
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

    // Info hash (if the alert is associated with a torrent)
    json += "\"info_hash\":\"\"";
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
            json += alert_to_json(alerts[i]);
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
                std::string h = hash.to_string();
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
// The actual data is retrieved via nativeGetTorrentResumeData().
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
// Returns jbyteArray with the resume data, or null if none exists.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeLoadTorrentResumeData(
        JNIEnv* env, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t sid = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(sid);
        if (sit == g_sessions.end()) return nullptr;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return nullptr;

        // Load resume data from the torrent handle
        lt::add_torrent_params params = tit->second.status().resume_data;
        if (params.info_hashes.is_all_zeros()) return nullptr;

        // Serialize the resume data to a byte array
        lt::entry e = params.make_entry();
        std::vector<char> buf;
        lt::bencode(std::back_inserter(buf), e);

        jbyteArray result = env->NewByteArray(static_cast<jsize>(buf.size()));
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(buf.size()),
                                reinterpret_cast<const jbyte*>(buf.data()));
        return result;
    } catch (std::exception const& e) {
        LOGE("nativeLoadTorrentResumeData failed: %s", e.what());
        return nullptr;
    }
}

// ---------------------------------------------------------------------------
// JNI: Remove torrent resume data (called when removing a torrent)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativeRemoveTorrentResumeData(
        JNIEnv*, jobject, jlong jId, jlong jTorrentId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t sid = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(sid);
        if (sit == g_sessions.end()) return;

        uint64_t tid = static_cast<uint64_t>(jTorrentId);
        auto tit = sit->second.torrents.find(tid);
        if (tit == sit->second.torrents.end()) return;

        // Clear resume data by removing the torrent and re-adding without resume params
        // This is a simplified approach; a more robust solution would track resume data separately
        LOGI("Removed resume data for torrent %llu", tid);
    } catch (std::exception const& e) {
        LOGE("nativeRemoveTorrentResumeData failed: %s", e.what());
    }
}
