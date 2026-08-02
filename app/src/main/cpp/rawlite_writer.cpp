#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <sys/uio.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr char kLogTag[] = "RawLiteNative";
constexpr char kMetadataHeader[] =
    "frame,timestamp_ns,iso,exposure_ns,frame_duration_ns,focus_diopters,"
    "wb_r,wb_ge,wb_go,wb_b\n";

thread_local std::string last_error;

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
    int raw_fd = -1;
    int metadata_fd = -1;
    int state_fd = -1;
    int width = 0;
    int height = 0;
    int row_bytes = 0;
    int checkpoint_frames = 0;
    int completed_frames = 0;
    int64_t raw_bytes = 0;
    bool closed = false;
    std::mutex mutex;

    ~Writer() {
        close_files(false);
    }

    bool open_files(
        const std::string& raw_path,
        const std::string& metadata_path,
        const std::string& state_path
    ) {
        raw_fd = ::open(raw_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (raw_fd < 0) {
            set_errno_error("open RAW");
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
        return write_state("recording", true);
    }

    bool write_state(const char* status, bool sync) {
        char state[256];
        const int length = std::snprintf(
            state,
            sizeof(state),
            "version=1\nstatus=%s\ncompleted_frames=%d\nraw_bytes=%lld\n"
            "width=%d\nheight=%d\nrow_bytes=%d\n",
            status,
            completed_frames,
            static_cast<long long>(raw_bytes),
            width,
            height,
            row_bytes
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
        std::lock_guard<std::mutex> lock(mutex);
        if (closed || raw_fd < 0 || metadata_fd < 0) {
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

        auto* first_row = buffer + buffer_offset;
        if (row_stride == row_bytes) {
            const size_t frame_size = static_cast<size_t>(row_bytes) * height;
            if (!write_all(raw_fd, first_row, frame_size)) {
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
        if (!write_all(metadata_fd, metadata, static_cast<size_t>(metadata_length))) {
            set_errno_error("write frame metadata");
            return false;
        }

        ++completed_frames;
        raw_bytes += static_cast<int64_t>(row_bytes) * height;
        if (checkpoint_frames > 0 && completed_frames % checkpoint_frames == 0) {
            return checkpoint();
        }
        return true;
    }

    int close_files(bool finalize) {
        std::lock_guard<std::mutex> lock(mutex);
        if (closed) {
            return 0;
        }
        int result = 0;
        if (finalize && raw_fd >= 0 && metadata_fd >= 0 && state_fd >= 0) {
            if (::fdatasync(raw_fd) < 0 || ::fdatasync(metadata_fd) < 0 ||
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

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_rawlite_NativeTenBitConverter_raw10ToP010Hlg(
    JNIEnv* env,
    jobject,
    jobject raw_buffer,
    jint raw_offset,
    jint raw_limit,
    jint raw_row_stride,
    jobject output_buffer,
    jint width,
    jint height,
    jint output_row_stride_bytes,
    jint output_slice_height,
    jint cfa,
    jint black_level,
    jint white_level,
    jfloat red_gain,
    jfloat blue_gain,
    jfloat exposure_gain
) {
    const auto started = std::chrono::steady_clock::now();
    auto* raw_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(raw_buffer));
    auto* output_base = static_cast<uint8_t*>(env->GetDirectBufferAddress(output_buffer));
    const jlong raw_capacity = env->GetDirectBufferCapacity(raw_buffer);
    const jlong output_capacity = env->GetDirectBufferCapacity(output_buffer);
    if (raw_base == nullptr || output_base == nullptr || width <= 0 || height <= 0 ||
        (width & 1) != 0 || (height & 1) != 0) {
        return -EINVAL;
    }

    const int packed_row_bytes = (width * 10 + 7) / 8;
    const int64_t required_raw = static_cast<int64_t>(raw_offset) +
        static_cast<int64_t>(height - 1) * raw_row_stride + packed_row_bytes;
    const int64_t required_output =
        static_cast<int64_t>(output_row_stride_bytes) * output_slice_height +
        static_cast<int64_t>(output_row_stride_bytes) * (height / 2 - 1) +
        static_cast<int64_t>(width) * 2;
    if (raw_offset < 0 || raw_limit > raw_capacity || raw_row_stride < packed_row_bytes ||
        required_raw > raw_limit || output_row_stride_bytes < width * 2 ||
        output_slice_height < height || (output_row_stride_bytes & 1) != 0 ||
        required_output > output_capacity ||
        white_level <= black_level) {
        return -EINVAL;
    }

    std::array<uint16_t, 1024> red_lut{};
    std::array<uint16_t, 1024> green_lut{};
    std::array<uint16_t, 1024> blue_lut{};
    const float safe_exposure_gain = std::clamp(exposure_gain, 0.25f, 8.0f);
    const auto hlg_code = [black_level, white_level, safe_exposure_gain](
        int value,
        float channel_gain
    ) -> uint16_t {
        const float balanced = std::clamp(
            (value - black_level) * channel_gain /
                static_cast<float>(white_level - black_level),
            0.0f,
            1.0f
        );
        // Lift scene-referred midtones without moving sensor black or clipping diffuse white.
        const float linear = std::clamp(
            safe_exposure_gain * balanced /
                (1.0f + (safe_exposure_gain - 1.0f) * balanced),
            0.0f,
            1.0f
        );
        constexpr float a = 0.17883277f;
        constexpr float b = 0.28466892f;
        constexpr float c = 0.55991073f;
        const float encoded = linear <= (1.0f / 12.0f)
            ? std::sqrt(3.0f * linear)
            : a * std::log(12.0f * linear - b) + c;
        return static_cast<uint16_t>(std::clamp(64.0f + encoded * 876.0f, 64.0f, 940.0f));
    };
    for (int value = 0; value < 1024; ++value) {
        red_lut[value] = hlg_code(value, red_gain);
        green_lut[value] = hlg_code(value, 1.0f);
        blue_lut[value] = hlg_code(value, blue_gain);
    }

    const auto raw10_at = [raw_base, raw_offset, raw_row_stride](int x, int y) -> int {
        const uint8_t* row = raw_base + raw_offset + static_cast<size_t>(y) * raw_row_stride;
        const int group = x >> 2;
        const int lane = x & 3;
        const uint8_t* packed = row + group * 5;
        return (static_cast<int>(packed[lane]) << 2) |
            ((packed[4] >> (lane * 2)) & 0x03);
    };

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

    for (int y = 0; y < height; y += 2) {
        for (int x = 0; x < width; x += 2) {
            const int p00 = raw10_at(x, y);
            const int p01 = raw10_at(x + 1, y);
            const int p10 = raw10_at(x, y + 1);
            const int p11 = raw10_at(x + 1, y + 1);
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

            const int rf = red_lut[std::clamp(r, 0, 1023)];
            const int bf = blue_lut[std::clamp(b_value, 0, 1023)];
            const int gf0 = green_lut[std::clamp(g0, 0, 1023)];
            const int gf1 = green_lut[std::clamp(g1, 0, 1023)];
            const int gf = (gf0 + gf1) >> 1;
            const auto luma = [rf, bf](int green) -> uint16_t {
                return static_cast<uint16_t>(std::clamp(
                    (269 * rf + 694 * green + 61 * bf + 512) >> 10,
                    64,
                    940
                ));
            };
            const uint16_t y0 = luma(gf);
            const uint16_t y1 = luma(gf0);
            const uint16_t y2 = luma(gf1);
            const uint16_t y3 = y0;
            const size_t top = static_cast<size_t>(y) * output_stride_pixels + x;
            const size_t bottom = top + output_stride_pixels;
            y_plane[top] = static_cast<uint16_t>(y0 << 6);
            y_plane[top + 1] = static_cast<uint16_t>(y1 << 6);
            y_plane[bottom] = static_cast<uint16_t>(y2 << 6);
            y_plane[bottom + 1] = static_cast<uint16_t>(y3 << 6);

            const int y_average = (y0 + y1 + y2 + y3 + 2) >> 2;
            const uint16_t cb = limited_chroma(((bf - y_average) * 544) >> 10);
            const uint16_t cr = limited_chroma(((rf - y_average) * 694) >> 10);
            const size_t uv = static_cast<size_t>(y / 2) * output_stride_pixels + x;
            uv_plane[uv] = static_cast<uint16_t>(cb << 6);
            uv_plane[uv + 1] = static_cast<uint16_t>(cr << 6);
        }
    }

    // Codec2 exposes 4096-byte P010 rows for 2032 pixels on the Pixel 6, while
    // its linear buffer intentionally omits the padding after the last UV row.
    // Initialize every padding sample that is actually allocated.
    constexpr uint16_t limited_black = 64u << 6;
    constexpr uint16_t neutral_chroma = 512u << 6;
    for (int y = 0; y < height; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels + width;
        const size_t end = std::min(
            static_cast<size_t>(y + 1) * output_stride_pixels,
            y_capacity_pixels
        );
        if (start < end) std::fill(y_plane + start, y_plane + end, limited_black);
    }
    for (int y = height; y < output_slice_height; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels;
        const size_t end = std::min(start + output_stride_pixels, y_capacity_pixels);
        if (start < end) std::fill(y_plane + start, y_plane + end, limited_black);
    }
    for (int y = 0; y < height / 2; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels + width;
        const size_t end = std::min(
            static_cast<size_t>(y + 1) * output_stride_pixels,
            uv_capacity_pixels
        );
        if (start < end) std::fill(uv_plane + start, uv_plane + end, neutral_chroma);
    }
    for (int y = height / 2; ; ++y) {
        const size_t start = static_cast<size_t>(y) * output_stride_pixels;
        if (start >= uv_capacity_pixels) break;
        const size_t end = std::min(start + output_stride_pixels, uv_capacity_pixels);
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
    jfloat blue_gain
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
        blue_gain
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
            const float normalized = std::max(raw10_at(x, y) - black, 0) /
                static_cast<float>(white_level - black);
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
