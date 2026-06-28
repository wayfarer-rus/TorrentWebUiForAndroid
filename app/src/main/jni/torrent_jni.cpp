#include <jni.h>
#include <string>
#include <unordered_map>
#include <vector>
#include <mutex>
#include <cstring>

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
            static_cast<int>(lt::alert::error_notification | lt::alert::storage_notification));
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

        tit->second.pause();
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

        jlong values[6] = {
            static_cast<jlong>(tid),
            static_cast<jlong>(st.progress * 1000.0f),
            static_cast<jlong>(st.download_rate),
            static_cast<jlong>(st.upload_rate),
            static_cast<jlong>(st.num_peers),
            static_cast<jlong>(static_cast<int>(st.state))
        };

        jlongArray arr = env->NewLongArray(6);
        env->SetLongArrayRegion(arr, 0, 6, values);
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
// JNI: Pop all pending alerts (called during polling to prevent queue buildup)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_andreiefimov_torrentwebui_TorrentSession_nativePopAlerts(
        JNIEnv*, jobject, jlong jId) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        uint64_t id = static_cast<uint64_t>(jId);
        auto sit = g_sessions.find(id);
        if (sit == g_sessions.end()) return;
        std::vector<lt::alert*> alerts;
        sit->second.session->pop_alerts(&alerts);
        // Alerts are consumed here to prevent queue saturation.
        // Error alerts are still accessible via nativeGetLastError().
    } catch (std::exception const& e) {
        LOGE("nativePopAlerts failed: %s", e.what());
    }
}

// ---------------------------------------------------------------------------
// JNI: Get session save path
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
