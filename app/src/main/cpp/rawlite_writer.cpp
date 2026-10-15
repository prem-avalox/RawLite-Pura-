#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fcntl.h>
#include <functional>
#include <mutex>
#include <memory>
#include <numeric>
#include <string>
#include <system_error>
#include <sys/uio.h>
#include <thread>
#include <unistd.h>
#include <vector>
#include <zlib.h>

#include "raw10_lossless_codec.h"

namespace {

constexpr char kLogTag[] = "RawLiteNative";
constexpr size_t kDirectAlignment = 4096;
constexpr int kMaximumDirectBatchFrames = 8;
constexpr size_t kCompressedContainerHeaderBytes = 64;
constexpr size_t kCompressedFrameHeaderBytes = 32;
constexpr std::array<uint8_t, 8> kCompressedContainerMagic = {
    'R', 'L', 'R', '1', '0', 'C', '0', '1'
};
constexpr std::array<uint8_t, 4> kCompressedFrameMagic = {'R', 'L', 'F', '1'};
constexpr char kMetadataHeader[] =
    "frame,timestamp_ns,iso,exposure_ns,frame_duration_ns,focus_diopters,"
    "wb_r,wb_ge,wb_go,wb_b\n";

thread_local std::string last_error;

struct LensShadingCorrection {
    int source_width = 0;
    int source_height = 0;
    int origin_x = 0;
    int origin_y = 0;
    int cell_width = 0;
    int cell_height = 0;
    int cell_step = 0;
    int cfa = 0;
    int map_rows = 0;
    int map_columns = 0;
    std::vector<float> source_gains;
    std::vector<uint16_t> site_gains_q12;
};

struct LensShadingCache {
    std::mutex mutex;
    std::shared_ptr<const LensShadingCorrection> correction;
};

LensShadingCache p010_hlg_lens_shading_cache;
LensShadingCache argb_preview_lens_shading_cache;

std::shared_ptr<const LensShadingCorrection> lens_shading_correction(
    LensShadingCache& cache,
    bool reuse_if_missing,
    JNIEnv* env,
    jfloatArray gains_array,
    int map_rows,
    int map_columns,
    int source_width,
    int source_height,
    int origin_x,
    int origin_y,
    int cell_width,
    int cell_height,
    int cell_step,
    int cfa
) {
    if (gains_array == nullptr && reuse_if_missing) {
        std::lock_guard<std::mutex> lock(cache.mutex);
        const auto cached = cache.correction;
        const bool geometry_matches = cached != nullptr &&
            cached->source_width == source_width && cached->source_height == source_height &&
            cached->origin_x == origin_x && cached->origin_y == origin_y &&
            cached->cell_width == cell_width && cached->cell_height == cell_height &&
            cached->cell_step == cell_step && cached->cfa == cfa;
        return geometry_matches ? cached : nullptr;
    }
    const int gain_count = map_rows * map_columns * 4;
    if (gains_array == nullptr || map_rows < 2 || map_columns < 2 ||
        gain_count <= 0 || env->GetArrayLength(gains_array) != gain_count ||
        source_width < 2 || source_height < 2 || cell_width <= 0 || cell_height <= 0 ||
        cell_step <= 0 || cfa < 0 || cfa > 3) {
        return nullptr;
    }
    std::vector<float> source_gains(static_cast<size_t>(gain_count));
    env->GetFloatArrayRegion(gains_array, 0, gain_count, source_gains.data());
    if (env->ExceptionCheck()) return nullptr;
    if (std::any_of(source_gains.begin(), source_gains.end(), [](float gain) {
        return !std::isfinite(gain) || gain < 1.0f;
    })) return nullptr;

    std::lock_guard<std::mutex> lock(cache.mutex);
    const auto cached = cache.correction;
    bool reusable = cached != nullptr &&
        cached->source_width == source_width && cached->source_height == source_height &&
        cached->origin_x == origin_x && cached->origin_y == origin_y &&
        cached->cell_width == cell_width && cached->cell_height == cell_height &&
        cached->cell_step == cell_step && cached->cfa == cfa &&
        cached->map_rows == map_rows && cached->map_columns == map_columns &&
        cached->source_gains.size() == source_gains.size();
    if (reusable) {
        for (size_t index = 0; index < source_gains.size(); ++index) {
            const float reference = std::max(cached->source_gains[index], 1.0f);
            if (std::abs(source_gains[index] - cached->source_gains[index]) / reference > 0.01f) {
                reusable = false;
                break;
            }
        }
    }
    if (reusable) return cached;

    static constexpr int kSiteChannels[4][4] = {
        {0, 1, 2, 3},  // RGGB
        {1, 0, 3, 2},  // GRBG
        {1, 3, 0, 2},  // GBRG
        {3, 1, 2, 0},  // BGGR
    };
    auto correction = std::make_shared<LensShadingCorrection>();
    correction->source_width = source_width;
    correction->source_height = source_height;
    correction->origin_x = origin_x;
    correction->origin_y = origin_y;
    correction->cell_width = cell_width;
    correction->cell_height = cell_height;
    correction->cell_step = cell_step;
    correction->cfa = cfa;
    correction->map_rows = map_rows;
    correction->map_columns = map_columns;
    correction->source_gains = std::move(source_gains);
    correction->site_gains_q12.resize(
        static_cast<size_t>(cell_width) * cell_height * 4
    );
    const auto sample = [&](int source_x, int source_y, int channel) -> float {
        const float map_x = static_cast<float>(source_x) * (map_columns - 1) /
            static_cast<float>(source_width - 1);
        const float map_y = static_cast<float>(source_y) * (map_rows - 1) /
            static_cast<float>(source_height - 1);
        const int x0 = std::clamp(static_cast<int>(std::floor(map_x)), 0, map_columns - 1);
        const int y0 = std::clamp(static_cast<int>(std::floor(map_y)), 0, map_rows - 1);
        const int x1 = std::min(x0 + 1, map_columns - 1);
        const int y1 = std::min(y0 + 1, map_rows - 1);
        const float tx = map_x - x0;
        const float ty = map_y - y0;
        const auto at = [&](int x, int y) -> float {
            return correction->source_gains[
                (static_cast<size_t>(y) * map_columns + x) * 4 + channel
            ];
        };
        const float top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx;
        const float bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx;
        return top + (bottom - top) * ty;
    };
    for (int cell_y = 0; cell_y < cell_height; ++cell_y) {
        for (int cell_x = 0; cell_x < cell_width; ++cell_x) {
            const size_t base = (static_cast<size_t>(cell_y) * cell_width + cell_x) * 4;
            for (int site = 0; site < 4; ++site) {
                const int source_x = std::clamp(
                    origin_x + cell_x * cell_step + (site & 1), 0, source_width - 1
                );
                const int source_y = std::clamp(
                    origin_y + cell_y * cell_step + (site >> 1), 0, source_height - 1
                );
                const float gain = std::clamp(
                    sample(source_x, source_y, kSiteChannels[cfa][site]), 1.0f, 16.0f
                );
                correction->site_gains_q12[base + site] = static_cast<uint16_t>(
                    std::min(std::lround(gain * 4096.0f), 65535L)
                );
            }
        }
    }
    cache.correction = correction;
    return correction;
}

struct P010HlgLookupTables {
    int black_level = 0;
    int white_level = 0;
    float exposure_gain = 1.0f;
    float red_gain = 1.0f;
    float green_even_gain = 1.0f;
    float green_odd_gain = 1.0f;
    float blue_gain = 1.0f;
    std::array<int, 1024> red{};
    std::array<int, 1024> green_even{};
    std::array<int, 1024> green_odd{};
    std::array<int, 1024> blue{};
    std::array<uint16_t, 4097> hlg{};
};

std::mutex p010_hlg_lookup_mutex;
std::shared_ptr<const P010HlgLookupTables> p010_hlg_lookup_cache;

std::shared_ptr<const P010HlgLookupTables> p010_hlg_lookup_tables(
    int black_level,
    int white_level,
    float exposure_gain,
    float red_gain,
    float green_even_gain,
    float green_odd_gain,
    float blue_gain
) {
    std::lock_guard<std::mutex> lock(p010_hlg_lookup_mutex);
    const auto cached = p010_hlg_lookup_cache;
    if (cached != nullptr && cached->black_level == black_level &&
        cached->white_level == white_level && cached->exposure_gain == exposure_gain &&
        cached->red_gain == red_gain && cached->green_even_gain == green_even_gain &&
        cached->green_odd_gain == green_odd_gain && cached->blue_gain == blue_gain) {
        return cached;
    }

    constexpr int kLinearScale = 4096;
    auto tables = std::make_shared<P010HlgLookupTables>();
    tables->black_level = black_level;
    tables->white_level = white_level;
    tables->exposure_gain = exposure_gain;
    tables->red_gain = red_gain;
    tables->green_even_gain = green_even_gain;
    tables->green_odd_gain = green_odd_gain;
    tables->blue_gain = blue_gain;
    const auto normalized_linear = [black_level, white_level](int value, float gain) -> int {
        const float linear = std::clamp(
            (value - black_level) / static_cast<float>(white_level - black_level),
            0.0f,
            1.0f
        );
        return static_cast<int>(
            std::lround(std::clamp(linear * gain, 0.0f, 1.0f) * kLinearScale)
        );
    };
    for (int value = 0; value <= kLinearScale; ++value) {
        const float linear = std::clamp(
            exposure_gain * static_cast<float>(value) / kLinearScale,
            0.0f,
            1.0f
        );
        constexpr float a = 0.17883277f;
        constexpr float b = 0.28466892f;
        constexpr float c = 0.55991073f;
        const float encoded = linear <= (1.0f / 12.0f)
            ? std::sqrt(3.0f * linear)
            : a * std::log(12.0f * linear - b) + c;
        tables->hlg[value] = static_cast<uint16_t>(
            std::clamp(64.0f + encoded * 876.0f, 64.0f, 940.0f)
        );
    }
    for (int value = 0; value < 1024; ++value) {
        tables->red[value] = normalized_linear(value, red_gain);
        tables->green_even[value] = normalized_linear(value, green_even_gain);
        tables->green_odd[value] = normalized_linear(value, green_odd_gain);
        tables->blue[value] = normalized_linear(value, blue_gain);
    }
    p010_hlg_lookup_cache = tables;
    return tables;
}

struct Nv12Rec709LookupTables {
    int black_level = 0;
    int white_level = 0;
    float exposure_gain = 1.0f;
    float red_gain = 1.0f;
    float green_even_gain = 1.0f;
    float green_odd_gain = 1.0f;
    float blue_gain = 1.0f;
    std::array<int, 1024> red{};
    std::array<int, 1024> green_even{};
    std::array<int, 1024> green_odd{};
    std::array<int, 1024> blue{};
    std::array<uint8_t, 4097> rec709_cine{};
};

std::mutex nv12_rec709_lookup_mutex;
std::shared_ptr<const Nv12Rec709LookupTables> nv12_rec709_lookup_cache;
LensShadingCache nv12_rec709_lens_shading_cache;

std::shared_ptr<const Nv12Rec709LookupTables> nv12_rec709_lookup_tables(
    int black_level,
    int white_level,
    float exposure_gain,
    float red_gain,
    float green_even_gain,
    float green_odd_gain,
    float blue_gain
) {
    std::lock_guard<std::mutex> lock(nv12_rec709_lookup_mutex);
    const auto cached = nv12_rec709_lookup_cache;
    if (cached != nullptr && cached->black_level == black_level &&
        cached->white_level == white_level && cached->exposure_gain == exposure_gain &&
        cached->red_gain == red_gain && cached->green_even_gain == green_even_gain &&
        cached->green_odd_gain == green_odd_gain && cached->blue_gain == blue_gain) {
        return cached;
    }

    constexpr int kLinearScale = 4096;
    auto tables = std::make_shared<Nv12Rec709LookupTables>();
    tables->black_level = black_level;
    tables->white_level = white_level;
    tables->exposure_gain = exposure_gain;
    tables->red_gain = red_gain;
    tables->green_even_gain = green_even_gain;
    tables->green_odd_gain = green_odd_gain;
    tables->blue_gain = blue_gain;

    const auto normalized_linear = [black_level, white_level](int value, float gain) -> int {
        const float linear = std::clamp(
            (value - black_level) / static_cast<float>(white_level - black_level),
            0.0f,
            1.0f
        );
        return static_cast<int>(
            std::lround(std::clamp(linear * gain, 0.0f, 1.0f) * kLinearScale)
        );
    };

    // Rec.709 Cine Gamma S-Curve:
    // Gentle shadow toe (< 0.02), 18% neutral gray anchored at ~40% IRE (~102-104 in 8-bit),
    // soft shoulder roll-off starting around 75% IRE (~190) with asymptotic compression towards 254.
    constexpr float k = 0.395f / 0.4242640687f; // 0.395 / sqrt(0.18)
    constexpr float toe_limit = 0.02f;
    constexpr float knee = 0.795f; // ~190 in 8-bit limited range: 16 + 0.795 * 219 = 190.1
    constexpr float max_norm = (254.0f - 16.0f) / 219.0f; // 1.08675799f -> 254 in 8-bit
    constexpr float shoulder_headroom = max_norm - knee;

    for (int value = 0; value <= kLinearScale; ++value) {
        const float linear = std::max(
            0.0f,
            exposure_gain * static_cast<float>(value) / kLinearScale
        );
        if (linear <= 0.0f) {
            tables->rec709_cine[value] = 16;
            continue;
        }
        float base = k * std::sqrt(linear);
        if (linear < toe_limit) {
            const float t = linear / toe_limit;
            const float smooth_t = t * t * (3.0f - 2.0f * t);
            base = base * smooth_t + (linear / toe_limit) * (base * 0.5f) * (1.0f - smooth_t);
        }
        if (base > knee) {
            const float excess = base - knee;
            base = knee + shoulder_headroom * (excess / (excess + shoulder_headroom * 0.75f));
        }
        const float y8_f = 16.0f + base * 219.0f;
        tables->rec709_cine[value] = static_cast<uint8_t>(
            std::clamp(std::lround(y8_f), 16L, 254L)
        );
    }

    for (int value = 0; value < 1024; ++value) {
        tables->red[value] = normalized_linear(value, red_gain);
        tables->green_even[value] = normalized_linear(value, green_even_gain);
        tables->green_odd[value] = normalized_linear(value, green_odd_gain);
        tables->blue[value] = normalized_linear(value, blue_gain);
    }
    nv12_rec709_lookup_cache = tables;
    return tables;
}

class P010RowWorkerPool {
public:
    P010RowWorkerPool() {
        try {
            for (int index = 0; index < 2; ++index) {
                workers_[index] = std::thread([this, index] { worker_loop(index); });
            }
        } catch (...) {
            {
                std::lock_guard<std::mutex> lock(state_mutex_);
                stopping_ = true;
                ++generation_;
            }
            task_ready_.notify_all();
            for (auto& worker : workers_) {
                if (worker.joinable()) worker.join();
            }
            throw;
        }
    }

    ~P010RowWorkerPool() {
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            stopping_ = true;
            ++generation_;
        }
        task_ready_.notify_all();
        for (auto& worker : workers_) {
            if (worker.joinable()) worker.join();
        }
    }

    void run(
        const std::function<void(int, int)>& task,
        std::array<std::pair<int, int>, 2> worker_ranges,
        std::pair<int, int> caller_range
    ) {
        std::lock_guard<std::mutex> run_lock(run_mutex_);
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            task_ = task;
            worker_ranges_ = worker_ranges;
            completed_ = 0;
            ++generation_;
        }
        task_ready_.notify_all();
        task(caller_range.first, caller_range.second);
        std::unique_lock<std::mutex> lock(state_mutex_);
        task_complete_.wait(lock, [this] { return completed_ == 2; });
        task_ = nullptr;
    }

private:
    void worker_loop(int index) {
        uint64_t observed_generation = 0;
        while (true) {
            std::function<void(int, int)> task;
            std::pair<int, int> range;
            {
                std::unique_lock<std::mutex> lock(state_mutex_);
                task_ready_.wait(lock, [this, observed_generation] {
                    return stopping_ || generation_ != observed_generation;
                });
                if (stopping_) return;
                observed_generation = generation_;
                task = task_;
                range = worker_ranges_[index];
            }
            task(range.first, range.second);
            {
                std::lock_guard<std::mutex> lock(state_mutex_);
                ++completed_;
            }
            task_complete_.notify_one();
        }
    }

    std::mutex run_mutex_;
    std::mutex state_mutex_;
    std::condition_variable task_ready_;
    std::condition_variable task_complete_;
    bool stopping_ = false;
    uint64_t generation_ = 0;
    int completed_ = 0;
    std::function<void(int, int)> task_;
    std::array<std::pair<int, int>, 2> worker_ranges_{};
    std::array<std::thread, 2> workers_;
};

P010RowWorkerPool* p010_row_workers() {
    static std::unique_ptr<P010RowWorkerPool> workers = [] {
        try {
            return std::make_unique<P010RowWorkerPool>();
        } catch (const std::system_error&) {
            return std::unique_ptr<P010RowWorkerPool>();
        }
    }();
    return workers.get();
}

struct P010WorkerScratchpad {
    std::vector<uint16_t> quad_rf;
    std::vector<uint16_t> quad_bf;
    std::vector<uint16_t> quad_gf;
    std::vector<uint16_t> quad_y;
    std::vector<int16_t> quad_g_diff;
    std::vector<int16_t> quad_mod_g;

    void ensure_capacity(size_t total_quads) {
        if (quad_rf.size() < total_quads) {
            quad_rf.resize(total_quads);
            quad_bf.resize(total_quads);
            quad_gf.resize(total_quads);
            quad_y.resize(total_quads);
            quad_g_diff.resize(total_quads);
            quad_mod_g.resize(total_quads);
        }
    }
};

thread_local P010WorkerScratchpad p010_scratchpad;

void set_error(const std::string& message) {
    last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
}

void set_errno_error(const char* operation) {
    set_error(std::string(operation) + ": " + std::strerror(errno));
}

bool write_all(int fd, const void* data, size_t size) {
    const auto* cursor = static_cast<const uint8_t*>(data);
    while (size > 0) {
        const ssize_t written = ::write(fd, cursor, size);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (written == 0) {
            errno = EIO;
            return false;
        }
        cursor += written;
        size -= static_cast<size_t>(written);
    }
    return true;
}

bool write_vectors(int fd, std::vector<iovec>& vectors) {
    size_t first = 0;
    while (first < vectors.size()) {
        const size_t remaining = vectors.size() - first;
        const int count = static_cast<int>(remaining > 256 ? 256 : remaining);
        const ssize_t written = ::writev(fd, vectors.data() + first, count);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        if (written == 0) {
            errno = EIO;
            return false;
        }

        ssize_t consumed = written;
        while (first < vectors.size() &&
               consumed >= static_cast<ssize_t>(vectors[first].iov_len)) {
            consumed -= static_cast<ssize_t>(vectors[first].iov_len);
            ++first;
        }
        if (consumed > 0 && first < vectors.size()) {
            auto* base = static_cast<uint8_t*>(vectors[first].iov_base);
            vectors[first].iov_base = base + consumed;
            vectors[first].iov_len -= static_cast<size_t>(consumed);
        }
    }
    return true;
}

void put_le32(uint8_t* destination, size_t offset, uint32_t value) {
    destination[offset] = static_cast<uint8_t>(value & 0xff);
    destination[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xff);
    destination[offset + 2] = static_cast<uint8_t>((value >> 16) & 0xff);
    destination[offset + 3] = static_cast<uint8_t>((value >> 24) & 0xff);
}

bool ends_with(const std::string& value, const char* suffix) {
    const size_t suffix_size = std::strlen(suffix);
    return value.size() >= suffix_size &&
        value.compare(value.size() - suffix_size, suffix_size, suffix) == 0;
}

std::string from_jstring(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string output(chars);
    env->ReleaseStringUTFChars(value, chars);
    return output;
}

struct Writer {
    struct CompressedJob {
        uint64_t sequence = 0;
        int frame = 0;
        std::string metadata;
        std::vector<uint8_t> raw;
        rawlite::EncodedRaw10Frame encoded;
        int64_t process_ns = 0;
        bool encoded_ok = false;
    };

    int raw_fd = -1;
    int metadata_fd = -1;
    int state_fd = -1;
    int width = 0;
    int height = 0;
    int row_bytes = 0;
    int checkpoint_frames = 0;
    int completed_frames = 0;
    int64_t raw_bytes = 0;
    int64_t logical_raw_bytes = 0;
    std::string raw_path;
    bool direct_io = false;
    bool staged_io = false;
    bool compressed_io = false;
    void* staging_buffer = nullptr;
    size_t frame_bytes = 0;
    size_t staging_bytes = 0;
    int direct_batch_frames = 0;
    int staged_frames = 0;
    std::vector<std::string> staged_metadata;
    int64_t write_total_ns = 0;
    int64_t write_max_ns = 0;
    int write_operations = 0;
    int direct_fallbacks = 0;
    int compression_fallback_frames = 0;
    int64_t process_total_ns = 0;
    int64_t process_max_ns = 0;
    int64_t enqueue_copy_total_ns = 0;
    int64_t enqueue_copy_max_ns = 0;
    int enqueue_operations = 0;
    int compression_worker_count = 0;
    int maximum_compression_jobs = 0;
    int outstanding_compression_jobs = 0;
    int peak_compression_jobs = 0;
    uint64_t next_compression_sequence = 0;
    uint64_t next_write_sequence = 0;
    bool compression_accepting = false;
    bool compression_stopping = false;
    bool compression_failed = false;
    std::string compression_failure;
    std::deque<std::unique_ptr<CompressedJob>> compression_jobs;
    std::vector<std::thread> compression_workers;
    std::mutex compression_mutex;
    std::condition_variable compression_available;
    std::condition_variable compression_order;
    bool closed = false;
    bool closing = false;
    std::mutex mutex;

    ~Writer() {
        close_files(false);
        std::free(staging_buffer);
        staging_buffer = nullptr;
    }

    bool open_buffered_raw(bool truncate) {
        const int flags = O_WRONLY | O_CREAT | O_CLOEXEC | (truncate ? O_TRUNC : 0);
        raw_fd = ::open(raw_path.c_str(), flags, 0644);
        if (raw_fd < 0) {
            set_errno_error("open buffered RAW");
            return false;
        }
        if (!truncate && ::lseek(raw_fd, raw_bytes, SEEK_SET) < 0) {
            set_errno_error("seek buffered RAW");
            ::close(raw_fd);
            raw_fd = -1;
            return false;
        }
        return true;
    }

    bool configure_raw_output() {
        frame_bytes = static_cast<size_t>(row_bytes) * static_cast<size_t>(height);
        if (frame_bytes == 0) {
            errno = EINVAL;
            set_errno_error("calculate RAW frame size");
            return false;
        }

        compressed_io = ends_with(raw_path, ".raw10c");
        if (compressed_io) {
            direct_io = false;
            staged_io = false;
            direct_batch_frames = 0;
            if (!open_buffered_raw(true)) {
                return false;
            }
            std::array<uint8_t, kCompressedContainerHeaderBytes> header{};
            std::copy(
                kCompressedContainerMagic.begin(),
                kCompressedContainerMagic.end(),
                header.begin()
            );
            put_le32(header.data(), 8, static_cast<uint32_t>(kCompressedContainerHeaderBytes));
            put_le32(header.data(), 12, 1);
            put_le32(header.data(), 16, static_cast<uint32_t>(width));
            put_le32(header.data(), 20, static_cast<uint32_t>(height));
            put_le32(header.data(), 24, static_cast<uint32_t>(row_bytes));
            put_le32(header.data(), 28, static_cast<uint32_t>(frame_bytes));
            put_le32(header.data(), 32, 1);  // CFA delta transform.
            put_le32(header.data(), 36, 1);  // Deflate codec.
            if (!write_all(raw_fd, header.data(), header.size())) {
                set_errno_error("write compressed RAW header");
                return false;
            }
            raw_bytes = static_cast<int64_t>(header.size());
            __android_log_print(
                ANDROID_LOG_INFO,
                kLogTag,
                "RAW writer opened lossless container frame_bytes=%zu",
                frame_bytes
            );
            return true;
        }

        const size_t divisor = std::gcd(frame_bytes, kDirectAlignment);
        const size_t batch_frames = kDirectAlignment / divisor;
        if (batch_frames <= static_cast<size_t>(kMaximumDirectBatchFrames)) {
#ifdef O_DIRECT
            raw_fd = ::open(
                raw_path.c_str(),
                O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_DIRECT,
                0644
            );
            if (raw_fd >= 0) {
                staging_bytes = frame_bytes * batch_frames;
                if (::posix_memalign(&staging_buffer, kDirectAlignment, staging_bytes) == 0) {
                    direct_io = true;
                    staged_io = true;
                    direct_batch_frames = static_cast<int>(batch_frames);
                    staged_metadata.reserve(batch_frames);
                    __android_log_print(
                        ANDROID_LOG_INFO,
                        kLogTag,
                        "RAW writer opened direct frame_bytes=%zu batch_frames=%d batch_bytes=%zu",
                        frame_bytes,
                        direct_batch_frames,
                        staging_bytes
                    );
                    return true;
                }
                ::close(raw_fd);
                raw_fd = -1;
                staging_buffer = nullptr;
            }
#endif
        }

        direct_io = false;
        staged_io = false;
        direct_batch_frames = 0;
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "RAW writer using buffered fallback frame_bytes=%zu",
            frame_bytes
        );
        return open_buffered_raw(true);
    }

    bool open_files(
        const std::string& raw_path,
        const std::string& metadata_path,
        const std::string& state_path
    ) {
        this->raw_path = raw_path;
        if (!configure_raw_output()) {
            return false;
        }
        metadata_fd =
            ::open(metadata_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (metadata_fd < 0) {
            set_errno_error("open metadata");
            return false;
        }
        state_fd =
            ::open(state_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (state_fd < 0) {
            set_errno_error("open state");
            return false;
        }
        if (!write_all(metadata_fd, kMetadataHeader, sizeof(kMetadataHeader) - 1)) {
            set_errno_error("write metadata header");
            return false;
        }
        if (!write_state("recording", true)) {
            return false;
        }
        return !compressed_io || start_compression_workers();
    }

    bool write_state(const char* status, bool sync) {
        char state[1024];
        const char* io_mode = compressed_io
            ? "lossless_huffman_parallel"
            : (direct_io ? "direct" : (staged_io ? "buffered_staged" : "buffered"));
        const int64_t average_write_us = write_operations > 0
            ? write_total_ns / write_operations / 1000
            : 0;
        const int length = std::snprintf(
            state,
            sizeof(state),
            "version=2\nstatus=%s\ncompleted_frames=%d\nraw_bytes=%lld\n"
            "logical_raw_bytes=%lld\ncontainer=%s\n"
            "width=%d\nheight=%d\nrow_bytes=%d\nio_mode=%s\n"
            "direct_batch_frames=%d\ndirect_fallbacks=%d\n"
            "compression_fallback_frames=%d\ncompression_workers=%d\n"
            "compression_peak_pending=%d\n"
            "write_operations=%d\nwrite_average_us=%lld\nwrite_max_us=%lld\n"
            "process_average_us=%lld\nprocess_max_us=%lld\n"
            "enqueue_copy_average_us=%lld\nenqueue_copy_max_us=%lld\n",
            status,
            completed_frames,
            static_cast<long long>(raw_bytes),
            static_cast<long long>(logical_raw_bytes),
            compressed_io ? "rawlite-raw10c" : "rawlite-raw10",
            width,
            height,
            row_bytes,
            io_mode,
            direct_batch_frames,
            direct_fallbacks,
            compression_fallback_frames,
            compression_worker_count,
            peak_compression_jobs,
            write_operations,
            static_cast<long long>(average_write_us),
            static_cast<long long>(write_max_ns / 1000),
            static_cast<long long>(
                completed_frames > 0 ? process_total_ns / completed_frames / 1000 : 0
            ),
            static_cast<long long>(process_max_ns / 1000),
            static_cast<long long>(
                enqueue_operations > 0 ? enqueue_copy_total_ns / enqueue_operations / 1000 : 0
            ),
            static_cast<long long>(enqueue_copy_max_ns / 1000)
        );
        if (length <= 0 || length >= static_cast<int>(sizeof(state))) {
            errno = EOVERFLOW;
            set_errno_error("format state");
            return false;
        }
        if (::lseek(state_fd, 0, SEEK_SET) < 0 || ::ftruncate(state_fd, 0) < 0 ||
            !write_all(state_fd, state, static_cast<size_t>(length))) {
            set_errno_error("write state");
            return false;
        }
        if (sync && ::fdatasync(state_fd) < 0) {
            set_errno_error("sync state");
            return false;
        }
        return true;
    }

    bool checkpoint() {
        return write_state("recording", false);
    }

    bool copy_frame(
        const uint8_t* buffer,
        int buffer_offset,
        int row_stride,
        uint8_t* destination
    ) {
        const auto* first_row = buffer + buffer_offset;
        if (row_stride == row_bytes) {
            std::memcpy(destination, first_row, frame_bytes);
            return true;
        }
        for (int row = 0; row < height; ++row) {
            std::memcpy(
                destination + static_cast<size_t>(row) * row_bytes,
                first_row + static_cast<size_t>(row) * row_stride,
                static_cast<size_t>(row_bytes)
            );
        }
        return true;
    }

    bool switch_to_buffered_raw() {
        ++direct_fallbacks;
        const int original_errno = errno;
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "Direct RAW write failed (%s); switching to buffered I/O",
            std::strerror(original_errno)
        );
        if (raw_fd >= 0) {
            if (::ftruncate(raw_fd, raw_bytes) < 0) {
                set_errno_error("truncate failed direct RAW batch");
                return false;
            }
            ::close(raw_fd);
            raw_fd = -1;
        }
        direct_io = false;
        return open_buffered_raw(false);
    }

    bool flush_staged(bool final_batch) {
        if (staged_frames <= 0) {
            return true;
        }
        const size_t logical_bytes = frame_bytes * static_cast<size_t>(staged_frames);
        size_t write_bytes = logical_bytes;
        if (direct_io) {
            write_bytes = (logical_bytes + kDirectAlignment - 1) & ~(kDirectAlignment - 1);
            if (write_bytes > logical_bytes) {
                std::memset(
                    static_cast<uint8_t*>(staging_buffer) + logical_bytes,
                    0,
                    write_bytes - logical_bytes
                );
            }
        }

        const auto write_started = std::chrono::steady_clock::now();
        if (!write_all(raw_fd, staging_buffer, write_bytes)) {
            if (!direct_io || !switch_to_buffered_raw() ||
                !write_all(raw_fd, staging_buffer, logical_bytes)) {
                set_errno_error("write staged RAW frames");
                return false;
            }
            write_bytes = logical_bytes;
        }
        const auto write_finished = std::chrono::steady_clock::now();
        const int64_t write_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            write_finished - write_started
        ).count();
        write_total_ns += write_ns;
        write_max_ns = std::max(write_max_ns, write_ns);
        ++write_operations;

        if (direct_io && final_batch && write_bytes != logical_bytes) {
            if (::ftruncate(raw_fd, raw_bytes + static_cast<int64_t>(logical_bytes)) < 0) {
                set_errno_error("truncate final direct RAW padding");
                return false;
            }
        }
        for (const std::string& metadata : staged_metadata) {
            if (!write_all(metadata_fd, metadata.data(), metadata.size())) {
                set_errno_error("write staged frame metadata");
                return false;
            }
        }

        completed_frames += staged_frames;
        raw_bytes += static_cast<int64_t>(logical_bytes);
        logical_raw_bytes += static_cast<int64_t>(logical_bytes);
        staged_frames = 0;
        staged_metadata.clear();
        if (checkpoint_frames > 0 && completed_frames % checkpoint_frames == 0) {
            return checkpoint();
        }
        return true;
    }

    bool write_compressed_job(CompressedJob& job) {
        process_total_ns += job.process_ns;
        process_max_ns = std::max(process_max_ns, job.process_ns);
        if ((job.encoded.flags & rawlite::kRaw10FrameFlagDeflate) == 0) {
            ++compression_fallback_frames;
        }

        std::array<uint8_t, kCompressedFrameHeaderBytes> header{};
        std::copy(kCompressedFrameMagic.begin(), kCompressedFrameMagic.end(), header.begin());
        put_le32(header.data(), 4, static_cast<uint32_t>(kCompressedFrameHeaderBytes));
        put_le32(header.data(), 8, static_cast<uint32_t>(job.frame));
        put_le32(header.data(), 12, job.encoded.flags);
        put_le32(header.data(), 16, static_cast<uint32_t>(frame_bytes));
        put_le32(header.data(), 20, static_cast<uint32_t>(job.encoded.payload.size()));
        put_le32(header.data(), 24, job.encoded.raw_crc32);
        put_le32(header.data(), 28, job.encoded.payload_crc32);

        std::vector<iovec> chunks{
            iovec{.iov_base = header.data(), .iov_len = header.size()},
            iovec{
                .iov_base = job.encoded.payload.data(),
                .iov_len = job.encoded.payload.size(),
            },
        };
        const auto write_started = std::chrono::steady_clock::now();
        if (!write_vectors(raw_fd, chunks)) {
            set_errno_error("write compressed RAW frame");
            return false;
        }
        const auto write_finished = std::chrono::steady_clock::now();
        const int64_t write_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            write_finished - write_started
        ).count();
        write_total_ns += write_ns;
        write_max_ns = std::max(write_max_ns, write_ns);
        ++write_operations;

        if (!write_all(metadata_fd, job.metadata.data(), job.metadata.size())) {
            set_errno_error("write compressed frame metadata");
            return false;
        }
        ++completed_frames;
        logical_raw_bytes += static_cast<int64_t>(frame_bytes);
        raw_bytes += static_cast<int64_t>(header.size() + job.encoded.payload.size());
        if (checkpoint_frames > 0 && completed_frames % checkpoint_frames == 0) {
            return checkpoint();
        }
        return true;
    }

    void compression_worker() {
        std::vector<uint8_t> transform_scratch;
        while (true) {
            std::unique_ptr<CompressedJob> job;
            {
                std::unique_lock<std::mutex> lock(compression_mutex);
                compression_available.wait(lock, [this] {
                    return compression_stopping || !compression_jobs.empty();
                });
                if (compression_jobs.empty()) {
                    if (compression_stopping) return;
                    continue;
                }
                job = std::move(compression_jobs.front());
                compression_jobs.pop_front();
            }

            const auto process_started = std::chrono::steady_clock::now();
            job->encoded_ok = rawlite::encode_raw10_lossless(
                job->raw.data(),
                width,
                height,
                row_bytes,
                transform_scratch,
                job->encoded
            );
            const auto process_finished = std::chrono::steady_clock::now();
            job->process_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                process_finished - process_started
            ).count();
            std::unique_lock<std::mutex> lock(compression_mutex);
            compression_order.wait(lock, [this, &job] {
                return compression_failed || job->sequence == next_write_sequence;
            });
            if (!compression_failed) {
                if (!job->encoded_ok) {
                    compression_failed = true;
                    compression_failure = "compress RAW10 frame failed";
                } else {
                    lock.unlock();
                    const bool written = write_compressed_job(*job);
                    const std::string write_error = last_error;
                    lock.lock();
                    if (!written) {
                        compression_failed = true;
                        compression_failure = write_error.empty()
                            ? "write compressed RAW10 frame failed"
                            : write_error;
                    }
                }
                ++next_write_sequence;
            }
            --outstanding_compression_jobs;
            compression_order.notify_all();
        }
    }

    bool start_compression_workers() {
        const unsigned cores = std::thread::hardware_concurrency();
        compression_worker_count = static_cast<int>(std::clamp(
            cores == 0 ? 3U : (cores + 2U) / 3U,
            2U,
            4U
        ));
        maximum_compression_jobs = compression_worker_count * 4;
        compression_accepting = true;
        compression_stopping = false;
        try {
            compression_workers.reserve(static_cast<size_t>(compression_worker_count));
            for (int index = 0; index < compression_worker_count; ++index) {
                compression_workers.emplace_back([this] { compression_worker(); });
            }
        } catch (const std::system_error& error) {
            compression_accepting = false;
            compression_stopping = true;
            compression_available.notify_all();
            for (std::thread& worker : compression_workers) {
                if (worker.joinable()) worker.join();
            }
            compression_workers.clear();
            set_error(std::string("start RAW compression workers: ") + error.what());
            return false;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "RAW compression pipeline workers=%d maximum_jobs=%d",
            compression_worker_count,
            maximum_compression_jobs
        );
        return true;
    }

    bool stop_compression_workers(bool drain) {
        {
            std::lock_guard<std::mutex> lock(compression_mutex);
            compression_accepting = false;
            if (!drain) {
                outstanding_compression_jobs -= static_cast<int>(compression_jobs.size());
                compression_jobs.clear();
            }
            compression_stopping = true;
        }
        compression_available.notify_all();
        compression_order.notify_all();
        for (std::thread& worker : compression_workers) {
            if (worker.joinable()) worker.join();
        }
        compression_workers.clear();
        if (compression_failed) {
            set_error(compression_failure.empty() ? "RAW compression pipeline failed" : compression_failure);
            return false;
        }
        return true;
    }

    bool enqueue_compressed_frame(
        uint8_t* buffer,
        int buffer_offset,
        int row_stride,
        int frame,
        const char* metadata,
        size_t metadata_length
    ) {
        {
            std::lock_guard<std::mutex> lock(compression_mutex);
            if (!compression_accepting || compression_failed) {
                set_error(compression_failure.empty() ? "RAW compression writer is closing" : compression_failure);
                errno = EPIPE;
                return false;
            }
            if (outstanding_compression_jobs >= maximum_compression_jobs) {
                set_error("RAW compression queue is full");
                errno = EAGAIN;
                return false;
            }
        }

        const auto copy_started = std::chrono::steady_clock::now();
        auto job = std::make_unique<CompressedJob>();
        job->frame = frame;
        job->metadata.assign(metadata, metadata_length);
        job->raw.resize(frame_bytes);
        copy_frame(buffer, buffer_offset, row_stride, job->raw.data());
        const auto copy_finished = std::chrono::steady_clock::now();
        const int64_t copy_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            copy_finished - copy_started
        ).count();

        {
            std::lock_guard<std::mutex> lock(compression_mutex);
            if (!compression_accepting || compression_failed) {
                set_error(compression_failure.empty() ? "RAW compression writer is closing" : compression_failure);
                errno = EPIPE;
                return false;
            }
            if (outstanding_compression_jobs >= maximum_compression_jobs) {
                set_error("RAW compression queue is full");
                errno = EAGAIN;
                return false;
            }
            job->sequence = next_compression_sequence++;
            compression_jobs.push_back(std::move(job));
            ++outstanding_compression_jobs;
            peak_compression_jobs = std::max(peak_compression_jobs, outstanding_compression_jobs);
            enqueue_copy_total_ns += copy_ns;
            enqueue_copy_max_ns = std::max(enqueue_copy_max_ns, copy_ns);
            ++enqueue_operations;
        }
        compression_available.notify_one();
        return true;
    }

    bool write_frame(
        uint8_t* buffer,
        int buffer_offset,
        int buffer_limit,
        int row_stride,
        int frame,
        int64_t timestamp_ns,
        int iso,
        int64_t exposure_ns,
        int64_t frame_duration_ns,
        float focus_diopters,
        float wb_red,
        float wb_green_even,
        float wb_green_odd,
        float wb_blue
    ) {
        std::unique_lock<std::mutex> lock(mutex);
        if (closed || closing || raw_fd < 0 || metadata_fd < 0) {
            set_error("writer is closed");
            return false;
        }
        if (row_stride < row_bytes || buffer_offset < 0 || buffer_limit < buffer_offset) {
            set_error("invalid RAW10 buffer geometry");
            return false;
        }
        const int64_t required =
            static_cast<int64_t>(buffer_offset) +
            static_cast<int64_t>(height - 1) * row_stride +
            row_bytes;
        if (required > buffer_limit) {
            set_error("RAW10 buffer is shorter than its row geometry");
            return false;
        }

        char metadata[512];
        const int metadata_length = std::snprintf(
            metadata,
            sizeof(metadata),
            "%d,%lld,%d,%lld,%lld,%.9g,%.9g,%.9g,%.9g,%.9g\n",
            frame,
            static_cast<long long>(timestamp_ns),
            iso,
            static_cast<long long>(exposure_ns),
            static_cast<long long>(frame_duration_ns),
            static_cast<double>(focus_diopters),
            static_cast<double>(wb_red),
            static_cast<double>(wb_green_even),
            static_cast<double>(wb_green_odd),
            static_cast<double>(wb_blue)
        );
        if (metadata_length <= 0 || metadata_length >= static_cast<int>(sizeof(metadata))) {
            set_error("metadata line is too long");
            return false;
        }
        if (compressed_io) {
            lock.unlock();
            return enqueue_compressed_frame(
                buffer,
                buffer_offset,
                row_stride,
                frame,
                metadata,
                static_cast<size_t>(metadata_length)
            );
        }
        if (staged_io) {
            auto* destination = static_cast<uint8_t*>(staging_buffer) +
                static_cast<size_t>(staged_frames) * frame_bytes;
            if (!copy_frame(buffer, buffer_offset, row_stride, destination)) {
                return false;
            }
            staged_metadata.emplace_back(metadata, static_cast<size_t>(metadata_length));
            ++staged_frames;
            if (staged_frames >= direct_batch_frames) {
                return flush_staged(false);
            }
            return true;
        }

        auto* first_row = buffer + buffer_offset;
        const auto write_started = std::chrono::steady_clock::now();
        if (row_stride == row_bytes) {
            if (!write_all(raw_fd, first_row, frame_bytes)) {
                set_errno_error("write RAW frame");
                return false;
            }
        } else {
            std::vector<iovec> rows;
            rows.reserve(static_cast<size_t>(height));
            for (int row = 0; row < height; ++row) {
                rows.push_back(
                    iovec{
                        .iov_base = first_row + static_cast<size_t>(row) * row_stride,
                        .iov_len = static_cast<size_t>(row_bytes),
                    }
                );
            }
            if (!write_vectors(raw_fd, rows)) {
                set_errno_error("write RAW rows");
                return false;
            }
        }
        const auto write_finished = std::chrono::steady_clock::now();
        const int64_t write_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
            write_finished - write_started
        ).count();
        write_total_ns += write_ns;
        write_max_ns = std::max(write_max_ns, write_ns);
        ++write_operations;

        if (!write_all(metadata_fd, metadata, static_cast<size_t>(metadata_length))) {
            set_errno_error("write frame metadata");
            return false;
        }
        ++completed_frames;
        raw_bytes += static_cast<int64_t>(frame_bytes);
        logical_raw_bytes += static_cast<int64_t>(frame_bytes);
        if (checkpoint_frames > 0 && completed_frames % checkpoint_frames == 0) {
            return checkpoint();
        }
        return true;
    }

    int close_files(bool finalize) {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (closed || closing) {
                return 0;
            }
            closing = true;
        }
        int result = 0;
        if (compressed_io && !stop_compression_workers(finalize)) {
            result = -EIO;
        }
        std::lock_guard<std::mutex> lock(mutex);
        if (finalize && raw_fd >= 0 && metadata_fd >= 0 && state_fd >= 0) {
            if (!flush_staged(true) || ::fdatasync(raw_fd) < 0 || ::fdatasync(metadata_fd) < 0 ||
                !write_state("closed", true)) {
                set_errno_error("finalize recording");
                result = -errno;
            }
        }
        if (raw_fd >= 0 && ::close(raw_fd) < 0 && result == 0) {
            result = -errno;
        }
        if (metadata_fd >= 0 && ::close(metadata_fd) < 0 && result == 0) {
            result = -errno;
        }
        if (state_fd >= 0 && ::close(state_fd) < 0 && result == 0) {
            result = -errno;
        }
        raw_fd = -1;
        metadata_fd = -1;
        state_fd = -1;
        closed = true;
        closing = false;
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "RAW writer closed mode=%s frames=%d bytes=%lld logical=%lld writes=%d avg_us=%lld max_us=%lld fallbacks=%d compression_fallbacks=%d process_avg_us=%lld result=%d",
            compressed_io ? "lossless_huffman_parallel" :
                (direct_io ? "direct" : (staged_io ? "buffered_staged" : "buffered")),
            completed_frames,
            static_cast<long long>(raw_bytes),
            static_cast<long long>(logical_raw_bytes),
            write_operations,
            static_cast<long long>(write_operations > 0 ? write_total_ns / write_operations / 1000 : 0),
            static_cast<long long>(write_max_ns / 1000),
            direct_fallbacks,
            compression_fallback_frames,
            static_cast<long long>(
                completed_frames > 0 ? process_total_ns / completed_frames / 1000 : 0
            ),
            result
        );
        return result;
    }
};

Writer* from_handle(jlong handle) {
    return reinterpret_cast<Writer*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rawlite_NativeRawWriter_open(
    JNIEnv* env,
    jobject,
    jstring raw_path,
    jstring metadata_path,
    jstring state_path,
    jint width,
    jint height,
    jint row_bytes,
    jint checkpoint_frames
) {
    last_error.clear();
    if (width <= 0 || height <= 0 || row_bytes <= 0) {
        set_error("invalid writer dimensions");
        return 0;
    }
    auto* writer = new Writer();
    writer->width = width;
    writer->height = height;
    writer->row_bytes = row_bytes;
    writer->checkpoint_frames = checkpoint_frames;
    if (!writer->open_files(
            from_jstring(env, raw_path),
            from_jstring(env, metadata_path),
            from_jstring(env, state_path)
        )) {
        delete writer;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(writer));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_rawlite_NativeRawWriter_writeFrame(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject buffer,
    jint buffer_offset,
    jint buffer_limit,
    jint row_stride,
    jint frame,
    jlong timestamp_ns,
    jint iso,
    jlong exposure_ns,
    jlong frame_duration_ns,
    jfloat focus_diopters,
    jfloat wb_red,
    jfloat wb_green_even,
    jfloat wb_green_odd,
    jfloat wb_blue
) {
    last_error.clear();
    Writer* writer = from_handle(handle);
    if (writer == nullptr) {
        set_error("invalid writer handle");
        return -EINVAL;
    }
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr) {
        set_error("RAW10 buffer is not direct");
        return -EINVAL;
    }
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0 || buffer_limit > capacity) {
        set_error("RAW10 buffer limit exceeds its capacity");
        return -EINVAL;
    }
    if (!writer->write_frame(
            data,
            buffer_offset,
            buffer_limit,
            row_stride,
            frame,
            timestamp_ns,
            iso,
            exposure_ns,
            frame_duration_ns,
            focus_diopters,
            wb_red,
            wb_green_even,
            wb_green_odd,
            wb_blue
        )) {
        return errno == 0 ? -EIO : -errno;
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_rawlite_NativeRawWriter_close(
    JNIEnv*,
    jobject,
    jlong handle
) {
    last_error.clear();
    Writer* writer = from_handle(handle);
    if (writer == nullptr) {
        return 0;
    }
    const int result = writer->close_files(true);
    delete writer;
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rawlite_NativeRawWriter_lastError(
    JNIEnv* env,
    jobject
) {
    return env->NewStringUTF(last_error.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_rawlite_NativeRawCompression_decodeFrame(
    JNIEnv* env,
    jobject,
    jobject payload_buffer,
    jint payload_size,
    jint flags,
    jlong raw_crc32,
    jlong payload_crc32,
    jobject output_buffer,
    jint width,
    jint height,
    jint row_bytes
) {
    last_error.clear();
    auto* payload = static_cast<uint8_t*>(env->GetDirectBufferAddress(payload_buffer));
    auto* output = static_cast<uint8_t*>(env->GetDirectBufferAddress(output_buffer));
    const jlong payload_capacity = env->GetDirectBufferCapacity(payload_buffer);
    const jlong output_capacity = env->GetDirectBufferCapacity(output_buffer);
    const int64_t frame_bytes = static_cast<int64_t>(row_bytes) * height;
    if (payload == nullptr || output == nullptr || payload_size <= 0 ||
        payload_size > payload_capacity || frame_bytes <= 0 || frame_bytes > output_capacity) {
        set_error("invalid compressed RAW buffers");
        return -EINVAL;
    }
    const uint32_t actual_payload_crc = static_cast<uint32_t>(
        ::crc32(0L, payload, static_cast<uInt>(payload_size))
    );
    if (actual_payload_crc != static_cast<uint32_t>(payload_crc32)) {
        set_error("compressed RAW payload CRC mismatch");
        return -EBADMSG;
    }
    std::vector<uint8_t> scratch;
    if (!rawlite::decode_raw10_lossless(
            payload,
            static_cast<size_t>(payload_size),
            static_cast<uint32_t>(flags),
            static_cast<uint32_t>(raw_crc32),
            width,
            height,
            row_bytes,
            output,
            static_cast<size_t>(output_capacity),
            scratch
        )) {
        set_error("compressed RAW frame decode or CRC validation failed");
        return -EBADMSG;
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rawlite_NativeTenBitConverter_resetP010HlgCaches(
    JNIEnv*,
    jobject
) {
    {
        std::lock_guard<std::mutex> lock(p010_hlg_lens_shading_cache.mutex);
        p010_hlg_lens_shading_cache.correction.reset();
    }
    {
        std::lock_guard<std::mutex> lock(p010_hlg_lookup_mutex);
        p010_hlg_lookup_cache.reset();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_rawlite_NativeTenBitConverter_resetNv12Rec709Caches(
    JNIEnv*,
    jobject
) {
    {
        std::lock_guard<std::mutex> lock(nv12_rec709_lens_shading_cache.mutex);
        nv12_rec709_lens_shading_cache.correction.reset();
    }
    {
        std::lock_guard<std::mutex> lock(nv12_rec709_lookup_mutex);
        nv12_rec709_lookup_cache.reset();
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rawlite_NativeTenBitConverter_raw10ToP010Hlg(
    JNIEnv* env,
    jobject,
    jobject raw_buffer,
    jint raw_offset,
    jint raw_limit,
    jint raw_row_stride,
    jobject output_buffer,
    jint source_width,
    jint source_height,
    jint crop_x,
    jint crop_y,
    jint output_width,
    jint output_height,
    jint output_row_stride_bytes,
    jint output_slice_height,
    jint cfa,
    jint black_level,
    jint white_level,
    jfloat exposure_gain,
    jfloat red_gain,
    jfloat green_even_gain,
    jfloat green_odd_gain,
    jfloat blue_gain,
    jfloatArray camera_to_rec2020_array,
    jfloatArray lens_shading_gains_array,
    jint lens_shading_rows,
    jint lens_shading_columns
) {
    const auto started = std::chrono::steady_clock::now();
    auto* raw_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(raw_buffer));
    auto* output_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(output_buffer));
    const jlong raw_capacity = env->GetDirectBufferCapacity(raw_buffer);
    const jlong output_capacity = env->GetDirectBufferCapacity(output_buffer);
    if (raw_base == nullptr || output_base == nullptr ||
        source_width <= 0 || source_height <= 0 ||
        crop_x < 0 || crop_y < 0 ||
        output_width <= 0 || output_height <= 0 ||
        (source_width & 1) != 0 || (source_height & 1) != 0 ||
        (crop_x & 1) != 0 || (crop_y & 1) != 0 ||
        (output_width & 1) != 0 || (output_height & 1) != 0 ||
        crop_x + output_width > source_width ||
        crop_y + output_height > source_height) {
        return -EINVAL;
    }

    const int packed_row_bytes = (source_width * 10 + 7) / 8;
    const int64_t required_raw = static_cast<int64_t>(raw_offset) +
        static_cast<int64_t>(source_height - 1) * raw_row_stride + packed_row_bytes;
    const int64_t required_output =
        static_cast<int64_t>(output_row_stride_bytes) * output_slice_height +
        static_cast<int64_t>(output_row_stride_bytes) * (output_height / 2 - 1) +
        static_cast<int64_t>(output_width) * 2;
    if (raw_offset < 0 || raw_limit > raw_capacity || raw_row_stride < packed_row_bytes ||
        required_raw > raw_limit || output_row_stride_bytes < output_width * 2 ||
        output_slice_height < output_height || (output_row_stride_bytes & 1) != 0 ||
        required_output > output_capacity ||
        white_level <= black_level) {
        return -EINVAL;
    }

    std::array<float, 9> camera_to_rec2020{
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    if (camera_to_rec2020_array != nullptr &&
        env->GetArrayLength(camera_to_rec2020_array) >= 9) {
        env->GetFloatArrayRegion(camera_to_rec2020_array, 0, 9, camera_to_rec2020.data());
    }
    const auto safe_gain = [](float value) -> float {
        return std::isfinite(value) ? std::clamp(value, 0.125f, 8.0f) : 1.0f;
    };
    const float safe_red_gain = safe_gain(red_gain);
    const float safe_green_even_gain = safe_gain(green_even_gain);
    const float safe_green_odd_gain = safe_gain(green_odd_gain);
    const float safe_blue_gain = safe_gain(blue_gain);
    const auto lens_shading = lens_shading_correction(
        p010_hlg_lens_shading_cache,
        true,
        env,
        lens_shading_gains_array,
        lens_shading_rows,
        lens_shading_columns,
        source_width,
        source_height,
        crop_x,
        crop_y,
        output_width / 2,
        output_height / 2,
        2,
        cfa
    );

    constexpr int kLinearScale = 4096;
    constexpr int kMatrixScale = 16384;
    std::array<int, 9> camera_to_rec2020_q{};
    for (size_t i = 0; i < camera_to_rec2020.size(); ++i) {
        camera_to_rec2020_q[i] = static_cast<int>(
            std::lround(camera_to_rec2020[i] * kMatrixScale)
        );
    }

    const float safe_exposure_gain = std::clamp(exposure_gain, 0.25f, 8.0f);
    const auto lookup_tables = p010_hlg_lookup_tables(
        black_level,
        white_level,
        safe_exposure_gain,
        safe_red_gain,
        safe_green_even_gain,
        safe_green_odd_gain,
        safe_blue_gain
    );
    const auto& red_lut = lookup_tables->red;
    const auto& green_even_lut = lookup_tables->green_even;
    const auto& green_odd_lut = lookup_tables->green_odd;
    const auto& blue_lut = lookup_tables->blue;
    const auto& hlg_lut = lookup_tables->hlg;

    const int output_stride_pixels = output_row_stride_bytes / 2;
    auto* y_plane = reinterpret_cast<uint16_t*>(output_base);
    auto* uv_plane = reinterpret_cast<uint16_t*>(
        output_base + static_cast<size_t>(output_row_stride_bytes) * output_slice_height
    );
    const size_t y_capacity_pixels = static_cast<size_t>(output_capacity) / 2;
    const size_t uv_offset_bytes =
        static_cast<size_t>(output_row_stride_bytes) * output_slice_height;
    const size_t uv_capacity_pixels = output_capacity > static_cast<jlong>(uv_offset_bytes)
        ? static_cast<size_t>(output_capacity - static_cast<jlong>(uv_offset_bytes)) / 2
        : 0;
    const auto limited_chroma = [](int value) -> uint16_t {
        return static_cast<uint16_t>(std::clamp(512 + value, 64, 960));
    };

    const auto luma = [](const std::array<uint16_t, 3>& rgb_in) -> uint16_t {
        return static_cast<uint16_t>(std::clamp(
            (269 * static_cast<int>(rgb_in[0]) +
                694 * static_cast<int>(rgb_in[1]) +
                61 * static_cast<int>(rgb_in[2]) + 512) >> 10,
            64,
            940
        ));
    };

    const auto convert_rows = [&](int first_y, int end_y) {
        if (first_y >= end_y) return;
        const int num_row_pairs = (end_y - first_y) / 2;
        const int num_quads = output_width / 2;
        const size_t total_quads = static_cast<size_t>(num_row_pairs) * num_quads;
        p010_scratchpad.ensure_capacity(total_quads);

        uint16_t* quad_rf = p010_scratchpad.quad_rf.data();
        uint16_t* quad_bf = p010_scratchpad.quad_bf.data();
        uint16_t* quad_gf = p010_scratchpad.quad_gf.data();
        uint16_t* quad_y = p010_scratchpad.quad_y.data();
        int16_t* quad_g_diff = p010_scratchpad.quad_g_diff.data();
        int16_t* quad_mod_g = p010_scratchpad.quad_mod_g.data();

        // Pass 1a: extract raw samples, apply lookup tables and lens shading
        for (int ry = 0; ry < num_row_pairs; ++ry) {
            const int y = first_y + ry * 2;
            const int source_y = crop_y + y;
            const uint8_t* top_row = raw_base + raw_offset +
                static_cast<size_t>(source_y) * raw_row_stride;
            const uint8_t* bottom_row = top_row + raw_row_stride;
            for (int qx = 0; qx < num_quads; ++qx) {
                const int x = qx * 2;
                const int source_x = crop_x + x;
                const int group = source_x >> 2;
                const int lane = source_x & 3;
                const uint8_t* top_packed = top_row + group * 5;
                const uint8_t* bottom_packed = bottom_row + group * 5;
                const int p00 = (static_cast<int>(top_packed[lane]) << 2) |
                    ((top_packed[4] >> (lane * 2)) & 0x03);
                const int p01 = (static_cast<int>(top_packed[lane + 1]) << 2) |
                    ((top_packed[4] >> ((lane + 1) * 2)) & 0x03);
                const int p10 = (static_cast<int>(bottom_packed[lane]) << 2) |
                    ((bottom_packed[4] >> (lane * 2)) & 0x03);
                const int p11 = (static_cast<int>(bottom_packed[lane + 1]) << 2) |
                    ((bottom_packed[4] >> ((lane + 1) * 2)) & 0x03);
                int r = 0;
                int b_value = 0;
                int g0 = 0;
                int g1 = 0;
                switch (cfa) {
                    case 1:  // GRBG
                        g0 = p00; r = p01; b_value = p10; g1 = p11;
                        break;
                    case 2:  // GBRG
                        g0 = p00; b_value = p01; r = p10; g1 = p11;
                        break;
                    case 3:  // BGGR
                        b_value = p00; g0 = p01; g1 = p10; r = p11;
                        break;
                    case 0:  // RGGB
                    default:
                        r = p00; g0 = p01; g1 = p10; b_value = p11;
                        break;
                }

                int rf = red_lut[std::clamp(r, 0, 1023)];
                int bf = blue_lut[std::clamp(b_value, 0, 1023)];
                int gf0 = green_even_lut[std::clamp(g0, 0, 1023)];
                int gf1 = green_odd_lut[std::clamp(g1, 0, 1023)];
                if (lens_shading != nullptr) {
                    const size_t gain_base =
                        (static_cast<size_t>(y / 2) * (output_width / 2) + qx) * 4;
                    const auto corrected = [&](int value, int site) -> int {
                        return std::min(
                            (value * static_cast<int>(lens_shading->site_gains_q12[gain_base + site]) +
                                2048) >> 12,
                            kLinearScale
                        );
                    };
                    switch (cfa) {
                        case 1:  // GRBG: G0,R,B,G1
                            gf0 = corrected(gf0, 0); rf = corrected(rf, 1);
                            bf = corrected(bf, 2); gf1 = corrected(gf1, 3);
                            break;
                        case 2:  // GBRG: G0,B,R,G1
                            gf0 = corrected(gf0, 0); bf = corrected(bf, 1);
                            rf = corrected(rf, 2); gf1 = corrected(gf1, 3);
                            break;
                        case 3:  // BGGR: B,G0,G1,R
                            bf = corrected(bf, 0); gf0 = corrected(gf0, 1);
                            gf1 = corrected(gf1, 2); rf = corrected(rf, 3);
                            break;
                        case 0:
                        default:  // RGGB: R,G0,G1,B
                            rf = corrected(rf, 0); gf0 = corrected(gf0, 1);
                            gf1 = corrected(gf1, 2); bf = corrected(bf, 3);
                            break;
                    }
                }
                const size_t idx = static_cast<size_t>(ry) * num_quads + qx;
                quad_rf[idx] = static_cast<uint16_t>(rf);
                quad_bf[idx] = static_cast<uint16_t>(bf);
                quad_gf[idx] = static_cast<uint16_t>((gf0 + gf1 + 1) >> 1);
                quad_g_diff[idx] = static_cast<int16_t>(gf0 - gf1);
            }
        }

        // Pass 1b: Subpixel spatial centering of R and B to match G and Y center (cancels chromatic aberration)
        const bool r_on_right = (cfa == 1 || cfa == 3);
        const bool b_on_right = !r_on_right;
        const bool r_on_bottom = (cfa == 2 || cfa == 3);
        const bool b_on_bottom = !r_on_bottom;

        for (int ry = 0; ry < num_row_pairs; ++ry) {
            const int y = first_y + ry * 2;
            const int r_ry_neighbor = r_on_bottom
                ? (ry > 0 ? ry - 1 : ry)
                : (ry + 1 < num_row_pairs ? ry + 1 : ry);
            const int b_ry_neighbor = b_on_bottom
                ? (ry > 0 ? ry - 1 : ry)
                : (ry + 1 < num_row_pairs ? ry + 1 : ry);

            const int prev_ry_g = ry > 0 ? ry - 1 : ry;
            const int next_ry_g = ry + 1 < num_row_pairs ? ry + 1 : ry;

            const auto* curr_r = &quad_rf[static_cast<size_t>(ry) * num_quads];
            const auto* v_r = &quad_rf[static_cast<size_t>(r_ry_neighbor) * num_quads];
            const auto* prev_r = &quad_rf[static_cast<size_t>(prev_ry_g) * num_quads];
            const auto* next_r = &quad_rf[static_cast<size_t>(next_ry_g) * num_quads];

            const auto* curr_b = &quad_bf[static_cast<size_t>(ry) * num_quads];
            const auto* v_b = &quad_bf[static_cast<size_t>(b_ry_neighbor) * num_quads];
            const auto* prev_b = &quad_bf[static_cast<size_t>(prev_ry_g) * num_quads];
            const auto* next_b = &quad_bf[static_cast<size_t>(next_ry_g) * num_quads];

            const auto* curr_g = &quad_gf[static_cast<size_t>(ry) * num_quads];
            const auto* prev_g = &quad_gf[static_cast<size_t>(prev_ry_g) * num_quads];
            const auto* next_g = &quad_gf[static_cast<size_t>(next_ry_g) * num_quads];

            for (int qx = 0; qx < num_quads; ++qx) {
                const int r_qx_neighbor = r_on_right
                    ? (qx > 0 ? qx - 1 : qx)
                    : (qx + 1 < num_quads ? qx + 1 : qx);
                const int b_qx_neighbor = b_on_right
                    ? (qx > 0 ? qx - 1 : qx)
                    : (qx + 1 < num_quads ? qx + 1 : qx);

                const uint32_t r_c = curr_r[qx];
                const uint32_t r_h = (r_c * 3 + curr_r[r_qx_neighbor] + 2) >> 2;
                const uint32_t r_v_c = v_r[qx];
                const uint32_t r_v_h = (r_v_c * 3 + v_r[r_qx_neighbor] + 2) >> 2;
                const int rf_centered = static_cast<int>((r_h * 3 + r_v_h + 2) >> 2);

                const uint32_t b_c = curr_b[qx];
                const uint32_t b_h = (b_c * 3 + curr_b[b_qx_neighbor] + 2) >> 2;
                const uint32_t b_v_c = v_b[qx];
                const uint32_t b_v_h = (b_v_c * 3 + v_b[b_qx_neighbor] + 2) >> 2;
                const int bf_centered = static_cast<int>((b_h * 3 + b_v_h + 2) >> 2);

                const int gf = static_cast<int>(curr_g[qx]);

                // Pass 1c: Green-Guided Optical Despilling and chromatic fringe suppression.
                // Evaluates the green guide gradient to cure lens chromatic aberration (LoCA)
                // and subpixel CFA bleeding along high-contrast highlight boundaries.
                const int qx_prev = qx > 0 ? qx - 1 : qx;
                const int qx_next = qx + 1 < num_quads ? qx + 1 : qx;

                const int g_left = static_cast<int>(curr_g[qx_prev]);
                const int g_right = static_cast<int>(curr_g[qx_next]);
                const int g_up = static_cast<int>(prev_g[qx]);
                const int g_down = static_cast<int>(next_g[qx]);

                const int grad_g_h = std::abs(g_right - g_left);
                const int grad_g_v = std::abs(g_down - g_up);
                const int grad_g = std::max(grad_g_h, grad_g_v);

                int rf_final = rf_centered;
                int bf_final = bf_centered;

                constexpr int kEdgeMinThreshold = 128; // ~3% contrast threshold in 12-bit linear space
                if (grad_g > kEdgeMinThreshold) {
                    const int delta_rg = rf_centered - gf;
                    const int delta_bg = bf_centered - gf;

                    const int d_rg_left = static_cast<int>(curr_r[qx_prev]) - g_left;
                    const int d_rg_right = static_cast<int>(curr_r[qx_next]) - g_right;
                    const int d_rg_up = static_cast<int>(prev_r[qx]) - g_up;
                    const int d_rg_down = static_cast<int>(next_r[qx]) - g_down;

                    const int d_bg_left = static_cast<int>(curr_b[qx_prev]) - g_left;
                    const int d_bg_right = static_cast<int>(curr_b[qx_next]) - g_right;
                    const int d_bg_up = static_cast<int>(prev_b[qx]) - g_up;
                    const int d_bg_down = static_cast<int>(next_b[qx]) - g_down;

                    const int g_neighbors[4] = { g_left, g_right, g_up, g_down };
                    const int d_rg_neighbors[4] = { d_rg_left, d_rg_right, d_rg_up, d_rg_down };
                    const int d_bg_neighbors[4] = { d_bg_left, d_bg_right, d_bg_up, d_bg_down };

                    const int g_max_neighbor = std::max({g_left, g_right, g_up, g_down});
                    const int g_min_neighbor = std::min({g_left, g_right, g_up, g_down});
                    const int edge_midpoint = (g_max_neighbor + g_min_neighbor) >> 1;

                    // Optical despill check: if adjacent to a bright highlight and the current
                    // pixel is on the darker side of the boundary, red/blue photons spilling
                    // over green represent optical dispersion (chromatic bleed). Bound them strictly
                    // to the photometric peer neighbors on the dark side of the edge.
                    constexpr int kHighlightThreshold = 1200; // ~30% linear sensor saturation
                    if (g_max_neighbor >= kHighlightThreshold &&
                        (g_max_neighbor - g_min_neighbor) >= 300 &&
                        gf < edge_midpoint) {
                        int dark_min_rg = 4096, dark_max_rg = -4096;
                        int dark_min_bg = 4096, dark_max_bg = -4096;
                        int dark_count = 0;

                        for (int i = 0; i < 4; ++i) {
                            if (g_neighbors[i] <= edge_midpoint) {
                                dark_min_rg = std::min(dark_min_rg, d_rg_neighbors[i]);
                                dark_max_rg = std::max(dark_max_rg, d_rg_neighbors[i]);
                                dark_min_bg = std::min(dark_min_bg, d_bg_neighbors[i]);
                                dark_max_bg = std::max(dark_max_bg, d_bg_neighbors[i]);
                                dark_count++;
                            }
                        }

                        if (dark_count == 0) {
                            int best_idx = 0;
                            int best_diff = std::abs(g_neighbors[0] - gf);
                            for (int i = 1; i < 4; ++i) {
                                const int diff = std::abs(g_neighbors[i] - gf);
                                if (diff < best_diff) {
                                    best_diff = diff;
                                    best_idx = i;
                                }
                            }
                            dark_min_rg = d_rg_neighbors[best_idx];
                            dark_max_rg = d_rg_neighbors[best_idx];
                            dark_min_bg = d_bg_neighbors[best_idx];
                            dark_max_bg = d_bg_neighbors[best_idx];
                        }

                        const int despill_margin = std::max(24, (std::abs(gf - g_min_neighbor) >> 3));
                        const int filtered_delta_rg = std::clamp(delta_rg, dark_min_rg - despill_margin, dark_max_rg + despill_margin);
                        const int filtered_delta_bg = std::clamp(delta_bg, dark_min_bg - despill_margin, dark_max_bg + despill_margin);

                        rf_final = std::clamp(gf + filtered_delta_rg, 0, 4096);
                        bf_final = std::clamp(gf + filtered_delta_bg, 0, 4096);
                    } else {
                        // Standard bilateral edge filtering for normal contrast edges & highlight interiors
                        const int margin = std::max(48, (grad_g >> 4));

                        const int min_rg = std::min({d_rg_left, d_rg_right, d_rg_up, d_rg_down}) - margin;
                        const int max_rg = std::max({d_rg_left, d_rg_right, d_rg_up, d_rg_down}) + margin;
                        const int filtered_delta_rg = std::clamp(delta_rg, min_rg, max_rg);

                        const int min_bg = std::min({d_bg_left, d_bg_right, d_bg_up, d_bg_down}) - margin;
                        const int max_bg = std::max({d_bg_left, d_bg_right, d_bg_up, d_bg_down}) + margin;
                        const int filtered_delta_bg = std::clamp(delta_bg, min_bg, max_bg);

                        rf_final = std::clamp(gf + filtered_delta_rg, 0, 4096);
                        bf_final = std::clamp(gf + filtered_delta_bg, 0, 4096);
                    }
                }

                const std::array<int64_t, 3> red_blue_contribution = {
                    static_cast<int64_t>(camera_to_rec2020_q[0]) * rf_final +
                        static_cast<int64_t>(camera_to_rec2020_q[2]) * bf_final,
                    static_cast<int64_t>(camera_to_rec2020_q[3]) * rf_final +
                        static_cast<int64_t>(camera_to_rec2020_q[5]) * bf_final,
                    static_cast<int64_t>(camera_to_rec2020_q[6]) * rf_final +
                        static_cast<int64_t>(camera_to_rec2020_q[8]) * bf_final
                };
                const auto channel = [&](int row) -> int {
                    const int64_t value = red_blue_contribution[row / 3] +
                        static_cast<int64_t>(camera_to_rec2020_q[row + 1]) * gf;
                    return std::clamp(
                        static_cast<int>((value + (kMatrixScale >> 1)) / kMatrixScale),
                        0,
                        kLinearScale
                    );
                };
                const std::array<uint16_t, 3> rgb = {
                    hlg_lut[channel(0)],
                    hlg_lut[channel(3)],
                    hlg_lut[channel(6)]
                };
                const uint16_t y_val = luma(rgb);
                const size_t quad_idx = static_cast<size_t>(ry) * num_quads + qx;
                quad_y[quad_idx] = y_val;

                // High-frequency green retention:
                // Modulate luminance using raw G0 vs G1 intra-cell variation
                const int g_diff = quad_g_diff[quad_idx];
                if (std::abs(static_cast<int>(g_diff)) > 4) {
                    const int base_ch_g = channel(3);
                    const int delta_ch_g = static_cast<int>(
                        (static_cast<int64_t>(camera_to_rec2020_q[4]) * g_diff + (kMatrixScale >> 1)) / kMatrixScale
                    );
                    const int half_delta = (delta_ch_g + (delta_ch_g >= 0 ? 1 : -1)) >> 1;
                    const int ch_g0 = std::clamp(base_ch_g + half_delta, 0, kLinearScale);
                    const int ch_g1 = std::clamp(base_ch_g - half_delta, 0, kLinearScale);
                    const int hlg_g0 = hlg_lut[ch_g0];
                    const int hlg_g1 = hlg_lut[ch_g1];
                    const int delta_luma_green = ((hlg_g0 - hlg_g1) * 694 + 512) >> 10;
                    quad_mod_g[quad_idx] = static_cast<int16_t>(
                        (delta_luma_green + (delta_luma_green >= 0 ? 1 : -1)) >> 1
                    );
                } else {
                    quad_mod_g[quad_idx] = 0;
                }

                int raw_cb_delta = ((static_cast<int>(rgb[2]) - static_cast<int>(y_val)) * 557) >> 10;
                int raw_cr_delta = ((static_cast<int>(rgb[0]) - static_cast<int>(y_val)) * 711) >> 10;

                // Highlight Desaturation & Magenta Fringing Roll-off
                // Fast path: Only evaluate in mid-to-high luminance (> 580) or when a channel is hot (> 650)
                const int max_c = std::max({static_cast<int>(rgb[0]), static_cast<int>(rgb[1]), static_cast<int>(rgb[2])});
                if (max_c > 650 || static_cast<int>(y_val) > 580) {
                    constexpr int kHighlightKnee = 700;
                    constexpr int kHighlightMax = 880;
                    int desat_scale_q8 = 256;
                    if (static_cast<int>(y_val) > kHighlightKnee) {
                        const int luma_scale = std::clamp(
                            ((kHighlightMax - static_cast<int>(y_val)) << 8) / (kHighlightMax - kHighlightKnee),
                            0,
                            256
                        );
                        desat_scale_q8 = std::min(desat_scale_q8, luma_scale);
                    }

                    constexpr int kChannelKnee = 820;
                    constexpr int kChannelMax = 940;
                    if (max_c > kChannelKnee) {
                        const int chan_scale = std::clamp(
                            ((kChannelMax - max_c) << 8) / (kChannelMax - kChannelKnee),
                            0,
                            256
                        );
                        desat_scale_q8 = std::min(desat_scale_q8, chan_scale);
                    }

                    if (raw_cb_delta > 0 && raw_cr_delta > 0) {
                        constexpr int kMagentaLumaKnee = 580;
                        constexpr int kMagentaLumaMax = 740;
                        constexpr int kMagentaChanKnee = 650;
                        constexpr int kMagentaChanMax = 800;

                        int mag_scale_q8 = 256;
                        if (static_cast<int>(y_val) > kMagentaLumaKnee) {
                            const int luma_mag = std::clamp(
                                ((kMagentaLumaMax - static_cast<int>(y_val)) << 8) / (kMagentaLumaMax - kMagentaLumaKnee),
                                0,
                                256
                            );
                            mag_scale_q8 = std::min(mag_scale_q8, luma_mag);
                        }
                        if (max_c > kMagentaChanKnee) {
                            const int chan_mag = std::clamp(
                                ((kMagentaChanMax - max_c) << 8) / (kMagentaChanMax - kMagentaChanKnee),
                                0,
                                256
                            );
                            mag_scale_q8 = std::min(mag_scale_q8, chan_mag);
                        }
                        const int final_mag_scale = (desat_scale_q8 * mag_scale_q8 + 128) >> 8;
                        raw_cb_delta = (raw_cb_delta * final_mag_scale + 128) >> 8;
                        raw_cr_delta = (raw_cr_delta * final_mag_scale + 128) >> 8;
                    } else if (desat_scale_q8 < 256) {
                        raw_cb_delta = (raw_cb_delta * desat_scale_q8 + 128) >> 8;
                        raw_cr_delta = (raw_cr_delta * desat_scale_q8 + 128) >> 8;
                    }
                }

                const uint16_t cb = limited_chroma(raw_cb_delta);
                const uint16_t cr = limited_chroma(raw_cr_delta);
                const size_t uv = static_cast<size_t>(y / 2) * output_stride_pixels + (qx * 2);
                uv_plane[uv] = static_cast<uint16_t>(cb << 6);
                uv_plane[uv + 1] = static_cast<uint16_t>(cr << 6);
            }
        }

        // Pass 2: continuous 2D spatial bilinear interpolation with native green modulation.
        // Directly writes to y_plane in a single cache-efficient streaming pass (zero intermediate buffers).
        for (int ry = 0; ry < num_row_pairs; ++ry) {
            const int y = first_y + ry * 2;
            const int prev_ry = ry > 0 ? ry - 1 : ry;
            const int next_ry = ry + 1 < num_row_pairs ? ry + 1 : ry;
            const auto* curr_row = &quad_y[static_cast<size_t>(ry) * num_quads];
            const auto* prev_row = &quad_y[static_cast<size_t>(prev_ry) * num_quads];
            const auto* next_row = &quad_y[static_cast<size_t>(next_ry) * num_quads];
            const auto* curr_mod = &quad_mod_g[static_cast<size_t>(ry) * num_quads];

            const size_t top = static_cast<size_t>(y) * output_stride_pixels;
            const size_t bottom = top + output_stride_pixels;

            for (int qx = 0; qx < num_quads; ++qx) {
                const int prev_qx = qx > 0 ? qx - 1 : qx;
                const int next_qx = qx + 1 < num_quads ? qx + 1 : qx;

                const uint32_t c = curr_row[qx];
                const uint32_t h_left = (c * 3 + curr_row[prev_qx] + 2) >> 2;
                const uint32_t h_right = (c * 3 + curr_row[next_qx] + 2) >> 2;

                const uint32_t up_left = (static_cast<uint32_t>(prev_row[qx]) * 3 + prev_row[prev_qx] + 2) >> 2;
                const uint32_t up_right = (static_cast<uint32_t>(prev_row[qx]) * 3 + prev_row[next_qx] + 2) >> 2;
                const uint32_t down_left = (static_cast<uint32_t>(next_row[qx]) * 3 + next_row[prev_qx] + 2) >> 2;
                const uint32_t down_right = (static_cast<uint32_t>(next_row[qx]) * 3 + next_row[next_qx] + 2) >> 2;

                const int y00_base = static_cast<int>((h_left * 3 + up_left + 2) >> 2);
                const int y01_base = static_cast<int>((h_right * 3 + up_right + 2) >> 2);
                const int y10_base = static_cast<int>((h_left * 3 + down_left + 2) >> 2);
                const int y11_base = static_cast<int>((h_right * 3 + down_right + 2) >> 2);

                const int mod_g = curr_mod[qx];
                int y00 = y00_base;
                int y01 = y01_base;
                int y10 = y10_base;
                int y11 = y11_base;

                // Modulate native green sites based on CFA mosaic
                if (cfa == 1 || cfa == 2) {
                    // GRBG (1) / GBRG (2, Samsung GN1): G0 at (0,0), G1 at (1,1)
                    y00 = std::clamp(y00_base + mod_g, 64, 940);
                    y11 = std::clamp(y11_base - mod_g, 64, 940);
                } else {
                    // RGGB (0) / BGGR (3): G0 at (0,1), G1 at (1,0)
                    y01 = std::clamp(y01_base + mod_g, 64, 940);
                    y10 = std::clamp(y10_base - mod_g, 64, 940);
                }

                const size_t px = static_cast<size_t>(qx * 2);
                y_plane[top + px] = static_cast<uint16_t>(y00 << 6);
                y_plane[top + px + 1] = static_cast<uint16_t>(y01 << 6);
                y_plane[bottom + px] = static_cast<uint16_t>(y10 << 6);
                y_plane[bottom + px + 1] = static_cast<uint16_t>(y11 << 6);
            }
        }
    };

    // PURE 10 spends its frame budget in Bayer -> P010 conversion. Reuse two persistent
    // workers instead of creating and joining new threads for every camera frame.
    if (static_cast<int64_t>(output_width) * output_height >= 1920LL * 1080LL) {
        const int first_split_y = (output_height / 3) & ~1;
        const int second_split_y = ((output_height * 2) / 3) & ~1;
        if (auto* workers = p010_row_workers()) {
            workers->run(
                convert_rows,
                {{{0, first_split_y}, {first_split_y, second_split_y}}},
                {second_split_y, output_height}
            );
        } else {
            convert_rows(0, output_height);
        }
    } else {
        convert_rows(0, output_height);
    }

    // Codec2 exposes 4096-byte P010 rows for 2032 pixels on the Pixel 6, while
    // its linear buffer intentionally omits the padding after the last UV row.
    // Initialize every padding sample that is actually allocated.
    constexpr uint16_t limited_black = 64u << 6;
    constexpr uint16_t neutral_chroma = 512u << 6;
    for (int y = 0; y < output_height; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels + output_width;
        const size_t end = std::min(
            static_cast<size_t>(y + 1) * output_stride_pixels,
            y_capacity_pixels
        );
        if (start < end) std::fill(y_plane + start, y_plane + end, limited_black);
    }
    for (int y = output_height; y < output_slice_height; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels;
        const size_t end = std::min(start + output_stride_pixels, y_capacity_pixels);
        if (start < end) std::fill(y_plane + start, y_plane + end, limited_black);
    }
    for (int y = 0; y < output_height / 2; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels + output_width;
        const size_t end = std::min(
            static_cast<size_t>(y + 1) * output_stride_pixels,
            uv_capacity_pixels
        );
        if (start < end) std::fill(uv_plane + start, uv_plane + end, neutral_chroma);
    }
    for (int y = output_height / 2; ; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels;
        if (start >= uv_capacity_pixels) break;
        const size_t end = std::min(start + output_stride_pixels, uv_capacity_pixels);
        std::fill(uv_plane + start, uv_plane + end, neutral_chroma);
    }

    const auto elapsed = std::chrono::steady_clock::now() - started;
    return std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rawlite_NativeTenBitConverter_raw10ToNv12Rec709(
    JNIEnv* env,
    jobject,
    jobject raw_buffer,
    jint raw_offset,
    jint raw_limit,
    jint raw_row_stride,
    jobject output_buffer,
    jint source_width,
    jint source_height,
    jint crop_x,
    jint crop_y,
    jint output_width,
    jint output_height,
    jint output_row_stride_bytes,
    jint output_slice_height,
    jint cfa,
    jint black_level,
    jint white_level,
    jfloat exposure_gain,
    jfloat red_gain,
    jfloat green_even_gain,
    jfloat green_odd_gain,
    jfloat blue_gain,
    jfloatArray camera_to_rec709_array,
    jfloatArray lens_shading_gains_array,
    jint lens_shading_rows,
    jint lens_shading_columns
) {
    const auto started = std::chrono::steady_clock::now();
    auto* raw_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(raw_buffer));
    auto* output_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(output_buffer));
    const jlong raw_capacity = env->GetDirectBufferCapacity(raw_buffer);
    const jlong output_capacity = env->GetDirectBufferCapacity(output_buffer);
    if (raw_base == nullptr || output_base == nullptr ||
        source_width <= 0 || source_height <= 0 ||
        crop_x < 0 || crop_y < 0 ||
        output_width <= 0 || output_height <= 0 ||
        (source_width & 1) != 0 || (source_height & 1) != 0 ||
        (crop_x & 1) != 0 || (crop_y & 1) != 0 ||
        (output_width & 1) != 0 || (output_height & 1) != 0 ||
        crop_x + output_width > source_width ||
        crop_y + output_height > source_height) {
        return -EINVAL;
    }

    const int packed_row_bytes = (source_width * 10 + 7) / 8;
    const int64_t required_raw = static_cast<int64_t>(raw_offset) +
        static_cast<int64_t>(source_height - 1) * raw_row_stride + packed_row_bytes;
    const int64_t required_output =
        static_cast<int64_t>(output_row_stride_bytes) * output_slice_height +
        static_cast<int64_t>(output_row_stride_bytes) * (output_height / 2 - 1) +
        static_cast<int64_t>(output_width);
    if (raw_offset < 0 || raw_limit > raw_capacity || raw_row_stride < packed_row_bytes ||
        required_raw > raw_limit || output_row_stride_bytes < output_width ||
        output_slice_height < output_height ||
        required_output > output_capacity ||
        white_level <= black_level) {
        return -EINVAL;
    }

    std::array<float, 9> camera_to_rec709{
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    if (camera_to_rec709_array != nullptr &&
        env->GetArrayLength(camera_to_rec709_array) >= 9) {
        env->GetFloatArrayRegion(camera_to_rec709_array, 0, 9, camera_to_rec709.data());
    }
    const auto safe_gain = [](float value) -> float {
        return std::isfinite(value) ? std::clamp(value, 0.125f, 8.0f) : 1.0f;
    };
    const float safe_red_gain = safe_gain(red_gain);
    const float safe_green_even_gain = safe_gain(green_even_gain);
    const float safe_green_odd_gain = safe_gain(green_odd_gain);
    const float safe_blue_gain = safe_gain(blue_gain);
    const auto lens_shading = lens_shading_correction(
        nv12_rec709_lens_shading_cache,
        true,
        env,
        lens_shading_gains_array,
        lens_shading_rows,
        lens_shading_columns,
        source_width,
        source_height,
        crop_x,
        crop_y,
        output_width / 2,
        output_height / 2,
        2,
        cfa
    );

    constexpr int kLinearScale = 4096;
    constexpr int kMatrixScale = 16384;
    std::array<int, 9> camera_to_rec709_q{};
    for (size_t i = 0; i < camera_to_rec709.size(); ++i) {
        camera_to_rec709_q[i] = static_cast<int>(
            std::lround(camera_to_rec709[i] * kMatrixScale)
        );
    }

    const float safe_exposure_gain = std::clamp(exposure_gain, 0.25f, 8.0f);
    const auto lookup_tables = nv12_rec709_lookup_tables(
        black_level,
        white_level,
        safe_exposure_gain,
        safe_red_gain,
        safe_green_even_gain,
        safe_green_odd_gain,
        safe_blue_gain
    );
    const auto& red_lut = lookup_tables->red;
    const auto& green_even_lut = lookup_tables->green_even;
    const auto& green_odd_lut = lookup_tables->green_odd;
    const auto& blue_lut = lookup_tables->blue;
    const auto& rec709_cine_lut = lookup_tables->rec709_cine;

    auto* y_plane = output_base;
    auto* uv_plane = output_base + static_cast<size_t>(output_row_stride_bytes) * output_slice_height;
    const size_t y_capacity_bytes = std::min(
        static_cast<size_t>(output_capacity),
        static_cast<size_t>(output_row_stride_bytes) * output_slice_height
    );
    const size_t uv_offset_bytes = static_cast<size_t>(output_row_stride_bytes) * output_slice_height;
    const size_t uv_capacity_bytes = output_capacity > static_cast<jlong>(uv_offset_bytes)
        ? static_cast<size_t>(output_capacity - static_cast<jlong>(uv_offset_bytes))
        : 0;

    const auto limited_chroma = [](int value) -> uint8_t {
        return static_cast<uint8_t>(std::clamp(128 + value, 16, 240));
    };

    const auto luma = [](const std::array<uint8_t, 3>& rgb_in) -> uint8_t {
        return static_cast<uint8_t>(std::clamp(
            (218 * static_cast<int>(rgb_in[0]) +
             732 * static_cast<int>(rgb_in[1]) +
              74 * static_cast<int>(rgb_in[2]) + 512) >> 10,
            16,
            254
        ));
    };

    const auto convert_rows = [&](int first_y, int end_y) {
        if (first_y >= end_y) return;
        const int num_row_pairs = (end_y - first_y) / 2;
        const int num_quads = output_width / 2;
        const size_t total_quads = static_cast<size_t>(num_row_pairs) * num_quads;
        p010_scratchpad.ensure_capacity(total_quads);

        uint16_t* quad_rf = p010_scratchpad.quad_rf.data();
        uint16_t* quad_bf = p010_scratchpad.quad_bf.data();
        uint16_t* quad_gf = p010_scratchpad.quad_gf.data();
        uint16_t* quad_y = p010_scratchpad.quad_y.data();
        int16_t* quad_g_diff = p010_scratchpad.quad_g_diff.data();
        int16_t* quad_mod_g = p010_scratchpad.quad_mod_g.data();

        // Pass 1a: extract raw samples, apply lookup tables and lens shading
        for (int ry = 0; ry < num_row_pairs; ++ry) {
            const int y = first_y + ry * 2;
            const int source_y = crop_y + y;
            const uint8_t* top_row = raw_base + raw_offset +
                static_cast<size_t>(source_y) * raw_row_stride;
            const uint8_t* bottom_row = top_row + raw_row_stride;
            for (int qx = 0; qx < num_quads; ++qx) {
                const int x = qx * 2;
                const int source_x = crop_x + x;
                const int group = source_x >> 2;
                const int lane = source_x & 3;
                const uint8_t* top_packed = top_row + group * 5;
                const uint8_t* bottom_packed = bottom_row + group * 5;
                const int p00 = (static_cast<int>(top_packed[lane]) << 2) |
                    ((top_packed[4] >> (lane * 2)) & 0x03);
                const int p01 = (static_cast<int>(top_packed[lane + 1]) << 2) |
                    ((top_packed[4] >> ((lane + 1) * 2)) & 0x03);
                const int p10 = (static_cast<int>(bottom_packed[lane]) << 2) |
                    ((bottom_packed[4] >> (lane * 2)) & 0x03);
                const int p11 = (static_cast<int>(bottom_packed[lane + 1]) << 2) |
                    ((bottom_packed[4] >> ((lane + 1) * 2)) & 0x03);
                int r = 0;
                int b_value = 0;
                int g0 = 0;
                int g1 = 0;
                switch (cfa) {
                    case 1:  // GRBG
                        g0 = p00; r = p01; b_value = p10; g1 = p11;
                        break;
                    case 2:  // GBRG
                        g0 = p00; b_value = p01; r = p10; g1 = p11;
                        break;
                    case 3:  // BGGR
                        b_value = p00; g0 = p01; g1 = p10; r = p11;
                        break;
                    case 0:  // RGGB
                    default:
                        r = p00; g0 = p01; g1 = p10; b_value = p11;
                        break;
                }

                int rf = red_lut[std::clamp(r, 0, 1023)];
                int bf = blue_lut[std::clamp(b_value, 0, 1023)];
                int gf0 = green_even_lut[std::clamp(g0, 0, 1023)];
                int gf1 = green_odd_lut[std::clamp(g1, 0, 1023)];
                if (lens_shading != nullptr) {
                    const size_t gain_base =
                        (static_cast<size_t>(y / 2) * (output_width / 2) + qx) * 4;
                    const auto corrected = [&](int value, int site) -> int {
                        return std::min(
                            (value * static_cast<int>(lens_shading->site_gains_q12[gain_base + site]) +
                                2048) >> 12,
                            kLinearScale
                        );
                    };
                    switch (cfa) {
                        case 1:  // GRBG: G0,R,B,G1
                            gf0 = corrected(gf0, 0); rf = corrected(rf, 1);
                            bf = corrected(bf, 2); gf1 = corrected(gf1, 3);
                            break;
                        case 2:  // GBRG: G0,B,R,G1
                            gf0 = corrected(gf0, 0); bf = corrected(bf, 1);
                            rf = corrected(rf, 2); gf1 = corrected(gf1, 3);
                            break;
                        case 3:  // BGGR: B,G0,G1,R
                            bf = corrected(bf, 0); gf0 = corrected(gf0, 1);
                            gf1 = corrected(gf1, 2); rf = corrected(rf, 3);
                            break;
                        case 0:
                        default:  // RGGB: R,G0,G1,B
                            rf = corrected(rf, 0); gf0 = corrected(gf0, 1);
                            gf1 = corrected(gf1, 2); bf = corrected(bf, 3);
                            break;
                    }
                }
                const size_t idx = static_cast<size_t>(ry) * num_quads + qx;
                quad_rf[idx] = static_cast<uint16_t>(rf);
                quad_bf[idx] = static_cast<uint16_t>(bf);
                quad_gf[idx] = static_cast<uint16_t>((gf0 + gf1 + 1) >> 1);
                quad_g_diff[idx] = static_cast<int16_t>(gf0 - gf1);
            }
        }

        // Pass 1b: Subpixel spatial centering of R and B to match G and Y center
        const bool r_on_right = (cfa == 1 || cfa == 3);
        const bool b_on_right = !r_on_right;
        const bool r_on_bottom = (cfa == 2 || cfa == 3);
        const bool b_on_bottom = !r_on_bottom;

        for (int ry = 0; ry < num_row_pairs; ++ry) {
            const int y = first_y + ry * 2;
            const int r_ry_neighbor = r_on_bottom
                ? (ry > 0 ? ry - 1 : ry)
                : (ry + 1 < num_row_pairs ? ry + 1 : ry);
            const int b_ry_neighbor = b_on_bottom
                ? (ry > 0 ? ry - 1 : ry)
                : (ry + 1 < num_row_pairs ? ry + 1 : ry);

            const int prev_ry_g = ry > 0 ? ry - 1 : ry;
            const int next_ry_g = ry + 1 < num_row_pairs ? ry + 1 : ry;

            const auto* curr_r = &quad_rf[static_cast<size_t>(ry) * num_quads];
            const auto* v_r = &quad_rf[static_cast<size_t>(r_ry_neighbor) * num_quads];
            const auto* prev_r = &quad_rf[static_cast<size_t>(prev_ry_g) * num_quads];
            const auto* next_r = &quad_rf[static_cast<size_t>(next_ry_g) * num_quads];

            const auto* curr_b = &quad_bf[static_cast<size_t>(ry) * num_quads];
            const auto* v_b = &quad_bf[static_cast<size_t>(b_ry_neighbor) * num_quads];
            const auto* prev_b = &quad_bf[static_cast<size_t>(prev_ry_g) * num_quads];
            const auto* next_b = &quad_bf[static_cast<size_t>(next_ry_g) * num_quads];

            const auto* curr_g = &quad_gf[static_cast<size_t>(ry) * num_quads];
            const auto* prev_g = &quad_gf[static_cast<size_t>(prev_ry_g) * num_quads];
            const auto* next_g = &quad_gf[static_cast<size_t>(next_ry_g) * num_quads];

            for (int qx = 0; qx < num_quads; ++qx) {
                const int r_qx_neighbor = r_on_right
                    ? (qx > 0 ? qx - 1 : qx)
                    : (qx + 1 < num_quads ? qx + 1 : qx);
                const int b_qx_neighbor = b_on_right
                    ? (qx > 0 ? qx - 1 : qx)
                    : (qx + 1 < num_quads ? qx + 1 : qx);

                const uint32_t r_c = curr_r[qx];
                const uint32_t r_h = (r_c * 3 + curr_r[r_qx_neighbor] + 2) >> 2;
                const uint32_t r_v_c = v_r[qx];
                const uint32_t r_v_h = (r_v_c * 3 + v_r[r_qx_neighbor] + 2) >> 2;
                const int rf_centered = static_cast<int>((r_h * 3 + r_v_h + 2) >> 2);

                const uint32_t b_c = curr_b[qx];
                const uint32_t b_h = (b_c * 3 + curr_b[b_qx_neighbor] + 2) >> 2;
                const uint32_t b_v_c = v_b[qx];
                const uint32_t b_v_h = (b_v_c * 3 + v_b[b_qx_neighbor] + 2) >> 2;
                const int bf_centered = static_cast<int>((b_h * 3 + b_v_h + 2) >> 2);

                const int gf = static_cast<int>(curr_g[qx]);

                // Pass 1c: Green-Guided Optical Despilling and chromatic fringe suppression
                const int qx_prev = qx > 0 ? qx - 1 : qx;
                const int qx_next = qx + 1 < num_quads ? qx + 1 : qx;

                const int g_left = static_cast<int>(curr_g[qx_prev]);
                const int g_right = static_cast<int>(curr_g[qx_next]);
                const int g_up = static_cast<int>(prev_g[qx]);
                const int g_down = static_cast<int>(next_g[qx]);

                const int grad_g_h = std::abs(g_right - g_left);
                const int grad_g_v = std::abs(g_down - g_up);
                const int grad_g = std::max(grad_g_h, grad_g_v);

                int rf_final = rf_centered;
                int bf_final = bf_centered;

                constexpr int kEdgeMinThreshold = 128; // ~3% contrast threshold in 12-bit linear space
                if (grad_g > kEdgeMinThreshold) {
                    const int delta_rg = rf_centered - gf;
                    const int delta_bg = bf_centered - gf;

                    const int d_rg_left = static_cast<int>(curr_r[qx_prev]) - g_left;
                    const int d_rg_right = static_cast<int>(curr_r[qx_next]) - g_right;
                    const int d_rg_up = static_cast<int>(prev_r[qx]) - g_up;
                    const int d_rg_down = static_cast<int>(next_r[qx]) - g_down;

                    const int d_bg_left = static_cast<int>(curr_b[qx_prev]) - g_left;
                    const int d_bg_right = static_cast<int>(curr_b[qx_next]) - g_right;
                    const int d_bg_up = static_cast<int>(prev_b[qx]) - g_up;
                    const int d_bg_down = static_cast<int>(next_b[qx]) - g_down;

                    const int g_neighbors[4] = { g_left, g_right, g_up, g_down };
                    const int d_rg_neighbors[4] = { d_rg_left, d_rg_right, d_rg_up, d_rg_down };
                    const int d_bg_neighbors[4] = { d_bg_left, d_bg_right, d_bg_up, d_bg_down };

                    const int g_max_neighbor = std::max({g_left, g_right, g_up, g_down});
                    const int g_min_neighbor = std::min({g_left, g_right, g_up, g_down});
                    const int edge_midpoint = (g_max_neighbor + g_min_neighbor) >> 1;

                    constexpr int kHighlightThreshold = 1200; // ~30% linear sensor saturation
                    if (g_max_neighbor >= kHighlightThreshold &&
                        (g_max_neighbor - g_min_neighbor) >= 300 &&
                        gf < edge_midpoint) {
                        int dark_min_rg = 4096, dark_max_rg = -4096;
                        int dark_min_bg = 4096, dark_max_bg = -4096;
                        int dark_count = 0;

                        for (int i = 0; i < 4; ++i) {
                            if (g_neighbors[i] <= edge_midpoint) {
                                dark_min_rg = std::min(dark_min_rg, d_rg_neighbors[i]);
                                dark_max_rg = std::max(dark_max_rg, d_rg_neighbors[i]);
                                dark_min_bg = std::min(dark_min_bg, d_bg_neighbors[i]);
                                dark_max_bg = std::max(dark_max_bg, d_bg_neighbors[i]);
                                dark_count++;
                            }
                        }

                        if (dark_count == 0) {
                            int best_idx = 0;
                            int best_diff = std::abs(g_neighbors[0] - gf);
                            for (int i = 1; i < 4; ++i) {
                                const int diff = std::abs(g_neighbors[i] - gf);
                                if (diff < best_diff) {
                                    best_diff = diff;
                                    best_idx = i;
                                }
                            }
                            dark_min_rg = d_rg_neighbors[best_idx];
                            dark_max_rg = d_rg_neighbors[best_idx];
                            dark_min_bg = d_bg_neighbors[best_idx];
                            dark_max_bg = d_bg_neighbors[best_idx];
                        }

                        const int despill_margin = std::max(24, (std::abs(gf - g_min_neighbor) >> 3));
                        const int filtered_delta_rg = std::clamp(delta_rg, dark_min_rg - despill_margin, dark_max_rg + despill_margin);
                        const int filtered_delta_bg = std::clamp(delta_bg, dark_min_bg - despill_margin, dark_max_bg + despill_margin);

                        rf_final = std::clamp(gf + filtered_delta_rg, 0, 4096);
                        bf_final = std::clamp(gf + filtered_delta_bg, 0, 4096);
                    } else {
                        const int margin = std::max(48, (grad_g >> 4));

                        const int min_rg = std::min({d_rg_left, d_rg_right, d_rg_up, d_rg_down}) - margin;
                        const int max_rg = std::max({d_rg_left, d_rg_right, d_rg_up, d_rg_down}) + margin;
                        const int filtered_delta_rg = std::clamp(delta_rg, min_rg, max_rg);

                        const int min_bg = std::min({d_bg_left, d_bg_right, d_bg_up, d_bg_down}) - margin;
                        const int max_bg = std::max({d_bg_left, d_bg_right, d_bg_up, d_bg_down}) + margin;
                        const int filtered_delta_bg = std::clamp(delta_bg, min_bg, max_bg);

                        rf_final = std::clamp(gf + filtered_delta_rg, 0, 4096);
                        bf_final = std::clamp(gf + filtered_delta_bg, 0, 4096);
                    }
                }

                const std::array<int64_t, 3> red_blue_contribution = {
                    static_cast<int64_t>(camera_to_rec709_q[0]) * rf_final +
                        static_cast<int64_t>(camera_to_rec709_q[2]) * bf_final,
                    static_cast<int64_t>(camera_to_rec709_q[3]) * rf_final +
                        static_cast<int64_t>(camera_to_rec709_q[5]) * bf_final,
                    static_cast<int64_t>(camera_to_rec709_q[6]) * rf_final +
                        static_cast<int64_t>(camera_to_rec709_q[8]) * bf_final
                };
                const auto channel = [&](int row) -> int {
                    const int64_t value = red_blue_contribution[row / 3] +
                        static_cast<int64_t>(camera_to_rec709_q[row + 1]) * gf;
                    return std::clamp(
                        static_cast<int>((value + (kMatrixScale >> 1)) / kMatrixScale),
                        0,
                        kLinearScale
                    );
                };
                const std::array<uint8_t, 3> rgb = {
                    rec709_cine_lut[channel(0)],
                    rec709_cine_lut[channel(3)],
                    rec709_cine_lut[channel(6)]
                };
                const uint8_t y_val = luma(rgb);
                const size_t quad_idx = static_cast<size_t>(ry) * num_quads + qx;
                quad_y[quad_idx] = static_cast<uint16_t>(y_val);

                // High-frequency green retention:
                // Modulate luminance using raw G0 vs G1 intra-cell variation rounded to 8 bits
                const int g_diff = quad_g_diff[quad_idx];
                if (std::abs(static_cast<int>(g_diff)) > 4) {
                    const int base_ch_g = channel(3);
                    const int delta_ch_g = static_cast<int>(
                        (static_cast<int64_t>(camera_to_rec709_q[4]) * g_diff + (kMatrixScale >> 1)) / kMatrixScale
                    );
                    const int half_delta = (delta_ch_g + (delta_ch_g >= 0 ? 1 : -1)) >> 1;
                    const int ch_g0 = std::clamp(base_ch_g + half_delta, 0, kLinearScale);
                    const int ch_g1 = std::clamp(base_ch_g - half_delta, 0, kLinearScale);
                    const int cine_g0 = rec709_cine_lut[ch_g0];
                    const int cine_g1 = rec709_cine_lut[ch_g1];
                    const int delta_luma_green = ((cine_g0 - cine_g1) * 732 + 512) >> 10;
                    quad_mod_g[quad_idx] = static_cast<int16_t>(
                        (delta_luma_green + (delta_luma_green >= 0 ? 1 : -1)) >> 1
                    );
                } else {
                    quad_mod_g[quad_idx] = 0;
                }

                int raw_cb_delta = ((static_cast<int>(rgb[2]) - static_cast<int>(y_val)) * 552) >> 10;
                int raw_cr_delta = ((static_cast<int>(rgb[0]) - static_cast<int>(y_val)) * 650) >> 10;

                // Highlight Desaturation & Magenta Fringing Roll-off in 8-bit scale
                const int max_c = std::max({static_cast<int>(rgb[0]), static_cast<int>(rgb[1]), static_cast<int>(rgb[2])});
                if (max_c > 162 || static_cast<int>(y_val) > 145) {
                    constexpr int kHighlightKnee = 175;
                    constexpr int kHighlightMax = 220;
                    int desat_scale_q8 = 256;
                    if (static_cast<int>(y_val) > kHighlightKnee) {
                        const int luma_scale = std::clamp(
                            ((kHighlightMax - static_cast<int>(y_val)) << 8) / (kHighlightMax - kHighlightKnee),
                            0,
                            256
                        );
                        desat_scale_q8 = std::min(desat_scale_q8, luma_scale);
                    }

                    constexpr int kChannelKnee = 205;
                    constexpr int kChannelMax = 235;
                    if (max_c > kChannelKnee) {
                        const int chan_scale = std::clamp(
                            ((kChannelMax - max_c) << 8) / (kChannelMax - kChannelKnee),
                            0,
                            256
                        );
                        desat_scale_q8 = std::min(desat_scale_q8, chan_scale);
                    }

                    if (raw_cb_delta > 0 && raw_cr_delta > 0) {
                        constexpr int kMagentaLumaKnee = 145;
                        constexpr int kMagentaLumaMax = 185;
                        constexpr int kMagentaChanKnee = 162;
                        constexpr int kMagentaChanMax = 200;

                        int mag_scale_q8 = 256;
                        if (static_cast<int>(y_val) > kMagentaLumaKnee) {
                            const int luma_mag = std::clamp(
                                ((kMagentaLumaMax - static_cast<int>(y_val)) << 8) / (kMagentaLumaMax - kMagentaLumaKnee),
                                0,
                                256
                            );
                            mag_scale_q8 = std::min(mag_scale_q8, luma_mag);
                        }
                        if (max_c > kMagentaChanKnee) {
                            const int chan_mag = std::clamp(
                                ((kMagentaChanMax - max_c) << 8) / (kMagentaChanMax - kMagentaChanKnee),
                                0,
                                256
                            );
                            mag_scale_q8 = std::min(mag_scale_q8, chan_mag);
                        }
                        const int final_mag_scale = (desat_scale_q8 * mag_scale_q8 + 128) >> 8;
                        raw_cb_delta = (raw_cb_delta * final_mag_scale + 128) >> 8;
                        raw_cr_delta = (raw_cr_delta * final_mag_scale + 128) >> 8;
                    } else if (desat_scale_q8 < 256) {
                        raw_cb_delta = (raw_cb_delta * desat_scale_q8 + 128) >> 8;
                        raw_cr_delta = (raw_cr_delta * desat_scale_q8 + 128) >> 8;
                    }
                }

                const uint8_t cb = limited_chroma(raw_cb_delta);
                const uint8_t cr = limited_chroma(raw_cr_delta);
                const size_t uv = static_cast<size_t>(y / 2) * static_cast<size_t>(output_row_stride_bytes) + (qx * 2);
                uv_plane[uv] = cb;
                uv_plane[uv + 1] = cr;
            }
        }

        // Pass 2: continuous 2D spatial bilinear interpolation with native green modulation.
        for (int ry = 0; ry < num_row_pairs; ++ry) {
            const int y = first_y + ry * 2;
            const int prev_ry = ry > 0 ? ry - 1 : ry;
            const int next_ry = ry + 1 < num_row_pairs ? ry + 1 : ry;
            const auto* curr_row = &quad_y[static_cast<size_t>(ry) * num_quads];
            const auto* prev_row = &quad_y[static_cast<size_t>(prev_ry) * num_quads];
            const auto* next_row = &quad_y[static_cast<size_t>(next_ry) * num_quads];
            const auto* curr_mod = &quad_mod_g[static_cast<size_t>(ry) * num_quads];

            const size_t top = static_cast<size_t>(y) * static_cast<size_t>(output_row_stride_bytes);
            const size_t bottom = top + static_cast<size_t>(output_row_stride_bytes);

            for (int qx = 0; qx < num_quads; ++qx) {
                const int prev_qx = qx > 0 ? qx - 1 : qx;
                const int next_qx = qx + 1 < num_quads ? qx + 1 : qx;

                const uint32_t c = curr_row[qx];
                const uint32_t h_left = (c * 3 + curr_row[prev_qx] + 2) >> 2;
                const uint32_t h_right = (c * 3 + curr_row[next_qx] + 2) >> 2;

                const uint32_t up_left = (static_cast<uint32_t>(prev_row[qx]) * 3 + prev_row[prev_qx] + 2) >> 2;
                const uint32_t up_right = (static_cast<uint32_t>(prev_row[qx]) * 3 + prev_row[next_qx] + 2) >> 2;
                const uint32_t down_left = (static_cast<uint32_t>(next_row[qx]) * 3 + next_row[prev_qx] + 2) >> 2;
                const uint32_t down_right = (static_cast<uint32_t>(next_row[qx]) * 3 + next_row[next_qx] + 2) >> 2;

                const int y00_base = static_cast<int>((h_left * 3 + up_left + 2) >> 2);
                const int y01_base = static_cast<int>((h_right * 3 + up_right + 2) >> 2);
                const int y10_base = static_cast<int>((h_left * 3 + down_left + 2) >> 2);
                const int y11_base = static_cast<int>((h_right * 3 + down_right + 2) >> 2);

                const int mod_g = curr_mod[qx];
                int y00 = y00_base;
                int y01 = y01_base;
                int y10 = y10_base;
                int y11 = y11_base;

                // Modulate native green sites based on CFA mosaic
                if (cfa == 1 || cfa == 2) {
                    // GRBG (1) / GBRG (2, Samsung GN1): G0 at (0,0), G1 at (1,1)
                    y00 = std::clamp(y00_base + mod_g, 16, 254);
                    y11 = std::clamp(y11_base - mod_g, 16, 254);
                } else {
                    // RGGB (0) / BGGR (3): G0 at (0,1), G1 at (1,0)
                    y01 = std::clamp(y01_base + mod_g, 16, 254);
                    y10 = std::clamp(y10_base - mod_g, 16, 254);
                }

                const size_t px = static_cast<size_t>(qx * 2);
                y_plane[top + px] = static_cast<uint8_t>(y00);
                y_plane[top + px + 1] = static_cast<uint8_t>(y01);
                y_plane[bottom + px] = static_cast<uint8_t>(y10);
                y_plane[bottom + px + 1] = static_cast<uint8_t>(y11);
            }
        }
    };

    if (static_cast<int64_t>(output_width) * output_height >= 1920LL * 1080LL) {
        const int first_split_y = (output_height / 3) & ~1;
        const int second_split_y = ((output_height * 2) / 3) & ~1;
        if (auto* workers = p010_row_workers()) {
            workers->run(
                convert_rows,
                {{{0, first_split_y}, {first_split_y, second_split_y}}},
                {second_split_y, output_height}
            );
        } else {
            convert_rows(0, output_height);
        }
    } else {
        convert_rows(0, output_height);
    }

    // Initialize every padding sample that is actually allocated (QG-8.4).
    constexpr uint8_t limited_black = 16;
    constexpr uint8_t neutral_chroma = 128;
    for (int y = 0; y < output_height; ++y) {
        const size_t start = static_cast<size_t>(y) * static_cast<size_t>(output_row_stride_bytes) + output_width;
        const size_t end = std::min(
            static_cast<size_t>(y + 1) * static_cast<size_t>(output_row_stride_bytes),
            y_capacity_bytes
        );
        if (start < end) std::fill(y_plane + start, y_plane + end, limited_black);
    }
    for (int y = output_height; y < output_slice_height; ++y) {
        const size_t start = static_cast<size_t>(y) * static_cast<size_t>(output_row_stride_bytes);
        const size_t end = std::min(start + static_cast<size_t>(output_row_stride_bytes), y_capacity_bytes);
        if (start < end) std::fill(y_plane + start, y_plane + end, limited_black);
    }
    for (int y = 0; y < output_height / 2; ++y) {
        const size_t start = static_cast<size_t>(y) * static_cast<size_t>(output_row_stride_bytes) + output_width;
        const size_t end = std::min(
            static_cast<size_t>(y + 1) * static_cast<size_t>(output_row_stride_bytes),
            uv_capacity_bytes
        );
        if (start < end) std::fill(uv_plane + start, uv_plane + end, neutral_chroma);
    }
    for (int y = output_height / 2; ; ++y) {
        const size_t start = static_cast<size_t>(y) * static_cast<size_t>(output_row_stride_bytes);
        if (start >= uv_capacity_bytes) break;
        const size_t end = std::min(start + static_cast<size_t>(output_row_stride_bytes), uv_capacity_bytes);
        std::fill(uv_plane + start, uv_plane + end, neutral_chroma);
    }

    const auto elapsed = std::chrono::steady_clock::now() - started;
    return std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rawlite_NativeTenBitConverter_raw10ToArgbPreview(
    JNIEnv* env,
    jobject,
    jobject raw_buffer,
    jint raw_offset,
    jint raw_limit,
    jint raw_row_stride,
    jobject output_bitmap,
    jint source_width,
    jint source_height,
    jint sample_step,
    jint cfa,
    jint black_level_0,
    jint black_level_1,
    jint black_level_2,
    jint black_level_3,
    jint white_level,
    jfloat red_gain,
    jfloat green_even_gain,
    jfloat green_odd_gain,
    jfloat blue_gain,
    jfloatArray lens_shading_gains_array,
    jint lens_shading_rows,
    jint lens_shading_columns
) {
    const auto started = std::chrono::steady_clock::now();
    auto* raw_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(raw_buffer));
    const jlong raw_capacity = env->GetDirectBufferCapacity(raw_buffer);
    AndroidBitmapInfo bitmap_info{};
    if (raw_base == nullptr || raw_capacity < 0 || output_bitmap == nullptr ||
        source_width <= 0 || source_height <= 0 || (source_width & 1) != 0 ||
        (source_height & 1) != 0 || sample_step <= 0 || cfa < 0 || cfa > 3 ||
        AndroidBitmap_getInfo(env, output_bitmap, &bitmap_info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmap_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return -EINVAL;
    }

    const int packed_row_bytes = (source_width * 10 + 7) / 8;
    const int output_width = source_width / 2 / sample_step;
    const int output_height = source_height / 2 / sample_step;
    const int64_t required_raw = static_cast<int64_t>(raw_offset) +
        static_cast<int64_t>(source_height - 1) * raw_row_stride + packed_row_bytes;
    const std::array<int, 4> black_levels = {
        black_level_0,
        black_level_1,
        black_level_2,
        black_level_3,
    };
    if (raw_offset < 0 || raw_limit > raw_capacity || raw_row_stride < packed_row_bytes ||
        required_raw > raw_limit || output_width <= 0 || output_height <= 0 ||
        bitmap_info.width != static_cast<uint32_t>(output_width) ||
        bitmap_info.height != static_cast<uint32_t>(output_height) ||
        std::any_of(black_levels.begin(), black_levels.end(), [white_level](int black) {
            return black < 0 || black >= white_level;
        })) {
        return -EINVAL;
    }

    const auto lens_shading = lens_shading_correction(
        argb_preview_lens_shading_cache,
        false,
        env,
        lens_shading_gains_array,
        lens_shading_rows,
        lens_shading_columns,
        source_width,
        source_height,
        0,
        0,
        output_width,
        output_height,
        sample_step * 2,
        cfa
    );

    void* bitmap_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, output_bitmap, &bitmap_pixels) !=
        ANDROID_BITMAP_RESULT_SUCCESS) {
        return -EIO;
    }

    static constexpr int kCfaColors[4][4] = {
        {0, 1, 1, 2},  // RGGB
        {1, 0, 2, 1},  // GRBG
        {1, 2, 0, 1},  // GBRG
        {2, 1, 1, 0},  // BGGR
    };
    const auto raw10_at = [raw_base, raw_offset, raw_row_stride](int x, int y) -> int {
        const uint8_t* row = raw_base + raw_offset + static_cast<size_t>(y) * raw_row_stride;
        const uint8_t* packed = row + (x >> 2) * 5;
        const int lane = x & 3;
        return (static_cast<int>(packed[lane]) << 2) |
            ((packed[4] >> (lane * 2)) & 0x03);
    };
    struct Rgb {
        float red;
        float green;
        float blue;
    };
    const auto sample_cell = [
        &raw10_at,
        &black_levels,
        white_level,
        cfa,
        sample_step,
        source_width,
        source_height,
        red_gain,
        green_even_gain,
        green_odd_gain,
        blue_gain,
        lens_shading,
        output_width
    ](int output_x, int output_y) -> Rgb {
        const int source_x = std::min(output_x * sample_step * 2, source_width - 2);
        const int source_y = std::min(output_y * sample_step * 2, source_height - 2);
        Rgb result{0.0f, 0.0f, 0.0f};
        float green_sum = 0.0f;
        int green_count = 0;
        for (int site = 0; site < 4; ++site) {
            const int x = source_x + (site & 1);
            const int y = source_y + (site >> 1);
            const int black = black_levels[site];
            float normalized = std::max(raw10_at(x, y) - black, 0) /
                static_cast<float>(white_level - black);
            if (lens_shading != nullptr) {
                const size_t gain_index =
                    (static_cast<size_t>(output_y) * output_width + output_x) * 4 + site;
                normalized *= lens_shading->site_gains_q12[gain_index] / 4096.0f;
            }
            switch (kCfaColors[cfa][site]) {
                case 0:
                    result.red = normalized * red_gain;
                    break;
                case 2:
                    result.blue = normalized * blue_gain;
                    break;
                case 1:
                default:
                    green_sum += normalized *
                        (green_count == 0 ? green_even_gain : green_odd_gain);
                    ++green_count;
                    break;
            }
        }
        result.green = green_count > 0 ? green_sum / green_count : 0.0f;
        return result;
    };

    const size_t pixel_count = static_cast<size_t>(output_width) * output_height;
    std::vector<float> highlights;
    highlights.reserve((pixel_count + 15) / 16);
    for (size_t pixel = 0; pixel < pixel_count; pixel += 16) {
        const int output_y = static_cast<int>(pixel / output_width);
        const int output_x = static_cast<int>(pixel % output_width);
        const Rgb rgb = sample_cell(output_x, output_y);
        highlights.push_back(
            rgb.red * 0.2126f + rgb.green * 0.7152f + rgb.blue * 0.0722f
        );
    }
    std::sort(highlights.begin(), highlights.end());
    const size_t percentile_index = static_cast<size_t>(
        static_cast<float>(highlights.size() - 1) * 0.985f
    );
    const float percentile = std::max(highlights[percentile_index], 0.001f);
    const float display_gain = std::clamp(0.90f / percentile, 0.25f, 8.0f);

    static std::array<uint8_t, 4096> srgb_lut{};
    static std::once_flag srgb_lut_once;
    std::call_once(srgb_lut_once, [] {
        for (size_t index = 0; index < srgb_lut.size(); ++index) {
            const float linear = static_cast<float>(index) / (srgb_lut.size() - 1);
            const float srgb = linear <= 0.0031308f
                ? linear * 12.92f
                : 1.055f * std::pow(linear, 1.0f / 2.4f) - 0.055f;
            srgb_lut[index] = static_cast<uint8_t>(
                std::clamp(std::lround(srgb * 255.0f), 0L, 255L)
            );
        }
    });
    const auto to_srgb = [](float value) -> uint8_t {
        const float bounded = std::clamp(value, 0.0f, 1.0f);
        const size_t index = static_cast<size_t>(
            std::lround(bounded * static_cast<float>(srgb_lut.size() - 1))
        );
        return srgb_lut[index];
    };

    auto* output_base = static_cast<uint8_t*>(bitmap_pixels);
    for (int output_y = 0; output_y < output_height; ++output_y) {
        uint8_t* output_row = output_base + static_cast<size_t>(output_y) * bitmap_info.stride;
        for (int output_x = 0; output_x < output_width; ++output_x) {
            Rgb rgb = sample_cell(output_x, output_y);
            rgb.red *= display_gain;
            rgb.green *= display_gain;
            rgb.blue *= display_gain;
            const float luminance =
                rgb.red * 0.2126f + rgb.green * 0.7152f + rgb.blue * 0.0722f;
            rgb.red = luminance + (rgb.red - luminance) * 1.12f;
            rgb.green = luminance + (rgb.green - luminance) * 1.12f;
            rgb.blue = luminance + (rgb.blue - luminance) * 1.12f;
            uint8_t* pixel = output_row + static_cast<size_t>(output_x) * 4;
            pixel[0] = to_srgb(rgb.red);
            pixel[1] = to_srgb(rgb.green);
            pixel[2] = to_srgb(rgb.blue);
            pixel[3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, output_bitmap);
    const auto elapsed = std::chrono::steady_clock::now() - started;
    return std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count();
}
