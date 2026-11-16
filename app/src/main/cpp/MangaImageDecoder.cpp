#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>

namespace {

struct VipsImage;
struct VipsSource;

// C ABI values from libvips v8.18.6 public headers; no libvips C++ dependency.
enum VipsBandFormat { VIPS_FORMAT_UCHAR = 0 };
enum VipsInterpretation { VIPS_INTERPRETATION_sRGB = 22 };
enum VipsSize { VIPS_SIZE_DOWN = 2 };
enum VipsAccess { VIPS_ACCESS_RANDOM = 0 };

struct PendingJavaException {};

struct Api {
    void* handle;
    VipsSource* (*sourceNew)(int);
    VipsImage* (*imageNew)(VipsSource*, const char*, ...);
    int (*width)(const VipsImage*);
    int (*height)(const VipsImage*);
    int (*bands)(const VipsImage*);
    VipsBandFormat (*format)(const VipsImage*);
    std::size_t (*typeOf)(const VipsImage*, const char*);
    int (*getInt)(const VipsImage*, const char*, int*);
    int (*remove)(VipsImage*, const char*);
    int (*hasAlpha)(VipsImage*);
    int (*autorot)(VipsImage*, VipsImage**, ...);
    int (*thumbnail)(VipsImage*, VipsImage**, int, ...);
    int (*colourspace)(VipsImage*, VipsImage**, VipsInterpretation, ...);
    int (*cast)(VipsImage*, VipsImage**, VipsBandFormat, ...);
    int (*bandjoinConst)(VipsImage*, VipsImage**, double*, int, ...);
    int (*premultiply)(VipsImage*, VipsImage**, ...);
    int (*unpremultiply)(VipsImage*, VipsImage**, ...);
    void* (*writeMemory)(VipsImage*, std::size_t*);
    const char* (*errorBuffer)();
    void (*errorClear)();
    void (*unref)(void*);
    void (*freeMemory)(void*);

    template<typename T>
    void load(T& destination, const char* name) {
        destination = reinterpret_cast<T>(dlsym(handle, name));
        if (!destination) {
            throw std::runtime_error(std::string("Missing imagedecoder symbol: ") + name);
        }
    }

    Api() : handle(dlopen("libimagedecoder2.so", RTLD_NOW | RTLD_LOCAL)) {
        if (!handle) {
            const char* error = dlerror();
            throw std::runtime_error(error ? error : "Cannot load imagedecoder2");
        }
        // Java loads imagedecoder2 first for JNI_OnLoad; keep its handle resident.
        load(sourceNew, "vips_source_new_from_descriptor");
        load(imageNew, "vips_image_new_from_source");
        load(width, "vips_image_get_width");
        load(height, "vips_image_get_height");
        load(bands, "vips_image_get_bands");
        load(format, "vips_image_get_format");
        load(typeOf, "vips_image_get_typeof");
        load(getInt, "vips_image_get_int");
        load(remove, "vips_image_remove");
        load(hasAlpha, "vips_image_hasalpha");
        load(autorot, "vips_autorot");
        load(thumbnail, "vips_thumbnail_image");
        load(colourspace, "vips_colourspace");
        load(cast, "vips_cast");
        load(bandjoinConst, "vips_bandjoin_const");
        load(premultiply, "vips_premultiply");
        load(unpremultiply, "vips_unpremultiply");
        load(writeMemory, "vips_image_write_to_memory");
        load(errorBuffer, "vips_error_buffer");
        load(errorClear, "vips_error_clear");
        load(unref, "g_object_unref");
        load(freeMemory, "g_free");
    }
};

Api& api() {
    static Api instance;
    return instance;
}

void checkJava(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        throw PendingJavaException{};
    }
}

void require(bool condition, const char* message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void checkVips(Api& a, int status) {
    if (status != 0) {
        const char* error = a.errorBuffer();
        std::string message = error && *error ? error : "Image decoding failed";
        a.errorClear();
        throw std::runtime_error(message);
    }
}

void throwJava(JNIEnv* env, const char* type, const char* message) noexcept {
    if (!env->ExceptionCheck()) {
        jclass exception = env->FindClass(type);
        if (exception) {
            env->ThrowNew(exception, message);
            env->DeleteLocalRef(exception);
        }
    }
}

template<typename T>
struct Object {
    Api& a;
    T* value = nullptr;
    explicit Object(Api& api) : a(api) {}
    ~Object() { if (value) a.unref(value); }
    Object(const Object&) = delete;
    Object& operator=(const Object&) = delete;
};

struct Descriptor {
    int value = -1;
    ~Descriptor() { if (value >= 0) close(value); }
    Descriptor() = default;
    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;
};

std::string filePath(JNIEnv* env, jstring path) {
    require(path != nullptr, "Image path is null");
    const jsize length = env->GetStringLength(path);
    checkJava(env);
    const jchar* chars = env->GetStringChars(path, nullptr);
    checkJava(env);
    if (!chars) throw std::bad_alloc();
    struct Release {
        JNIEnv* env;
        jstring string;
        const jchar* chars;
        ~Release() { env->ReleaseStringChars(string, chars); }
    } release{env, path, chars};
    std::string result;
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        require(c != 0, "Image path contains NUL");
        if (c >= 0xd800 && c <= 0xdbff) {
            require(i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff,
                    "Invalid UTF-16 image path");
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        } else {
            require(c < 0xdc00 || c > 0xdfff, "Invalid UTF-16 image path");
        }
        if (c < 0x80) {
            result.push_back(static_cast<char>(c));
        } else {
            if (c < 0x800) {
                result.push_back(static_cast<char>(0xc0 | (c >> 6)));
            } else {
                if (c < 0x10000) {
                    result.push_back(static_cast<char>(0xe0 | (c >> 12)));
                } else {
                    result.push_back(static_cast<char>(0xf0 | (c >> 18)));
                    result.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3f)));
                }
                result.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3f)));
            }
            result.push_back(static_cast<char>(0x80 | (c & 0x3f)));
        }
    }
    return result;
}

void validateSourceSize(Api& a, VipsImage* image) {
    const int w = a.width(image);
    const int h = a.height(image);
    require(w > 0 && h > 0 && uint64_t(w) * uint64_t(h) <= (uint64_t(1) << 28),
            "Image exceeds source pixel limit");
}

struct Input {
    Descriptor fd;
    Object<VipsSource> source;
    Object<VipsImage> header;
    Object<VipsImage> oriented;

    Input(Api& a, const std::string& path) : source(a), header(a), oriented(a) {
        fd.value = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd.value < 0) throw std::runtime_error(std::strerror(errno));
        struct stat statBuffer{};
        if (fstat(fd.value, &statBuffer) != 0) throw std::runtime_error(std::strerror(errno));
        require(S_ISREG(statBuffer.st_mode) && statBuffer.st_size > 0 &&
                    uint64_t(statBuffer.st_size) <= (uint64_t(512) << 20),
                "Image must be a regular file no larger than 512 MiB");
        a.errorClear();
        source.value = a.sourceNew(fd.value);
        checkVips(a, source.value ? 0 : -1);
        header.value = a.imageNew(source.value, "", "access", VIPS_ACCESS_RANDOM, nullptr);
        checkVips(a, header.value ? 0 : -1);
        validateSourceSize(a, header.value);
        checkVips(a, a.autorot(header.value, &oriented.value, nullptr));
        validateSourceSize(a, oriented.value);
    }
};

uint64_t outputBytes(Api& a, VipsImage* image, uint64_t budget) {
    const int w = a.width(image);
    const int h = a.height(image);
    require(w > 0 && h > 0 && w <= 16384 && h <= 16384,
            "Invalid output dimensions");
    const uint64_t bytes = uint64_t(w) * uint64_t(h) * 4;
    require(bytes <= budget, "Decoded image exceeds byte budget");
    return bytes;
}

jobject copyBitmap(JNIEnv* env, void* pixels, uint64_t bytes, int w, int h, bool alpha) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    checkJava(env);
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    checkJava(env);
    jfieldID configField = env->GetStaticFieldID(configClass, "ARGB_8888",
                                               "Landroid/graphics/Bitmap$Config;");
    checkJava(env);
    jobject config = env->GetStaticObjectField(configClass, configField);
    checkJava(env);
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    checkJava(env);
    jmethodID copy = env->GetMethodID(bitmapClass, "copyPixelsFromBuffer", "(Ljava/nio/Buffer;)V");
    checkJava(env);
    jmethodID setAlpha = env->GetMethodID(bitmapClass, "setHasAlpha", "(Z)V");
    checkJava(env);
    jmethodID setPremultiplied = env->GetMethodID(bitmapClass, "setPremultiplied", "(Z)V");
    checkJava(env);
    jobject buffer = env->NewDirectByteBuffer(pixels, static_cast<jlong>(bytes));
    checkJava(env);
    if (!buffer) throw std::bad_alloc();
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, w, h, config);
    checkJava(env);
    if (!bitmap) throw std::bad_alloc();
    env->CallVoidMethod(bitmap, copy, buffer);
    checkJava(env);
    env->CallVoidMethod(bitmap, setAlpha, alpha ? JNI_TRUE : JNI_FALSE);
    checkJava(env);
    if (alpha) {
        env->CallVoidMethod(bitmap, setPremultiplied, JNI_TRUE);
        checkJava(env);
    }
    return bitmap;
}

jobject decode(JNIEnv* env, jstring path, jint sample, jlong byteBudget, jintArray sourceSize) {
    require(sample >= 1 && byteBudget >= 4, "Invalid sample or byte budget");
    require(sourceSize != nullptr, "sourceSize is null");
    const jsize length = env->GetArrayLength(sourceSize);
    checkJava(env);
    require(length >= 2, "sourceSize must contain at least two elements");
    Api& a = api();
    Input input(a, filePath(env, path));
    VipsImage* image = input.oriented.value;
    const jint dimensions[] = {a.width(image), a.height(image)};
    env->SetIntArrayRegion(sourceSize, 0, 2, dimensions);
    checkJava(env);
    const uint64_t budget = static_cast<uint64_t>(byteBudget);
    uint64_t scale = 1;
    while (scale < static_cast<uint64_t>(sample)) scale *= 2;
    uint64_t targetW, targetH;
    do {
        targetW = std::max(uint64_t(1), uint64_t(dimensions[0]) / scale);
        targetH = std::max(uint64_t(1), uint64_t(dimensions[1]) / scale);
        if (targetW <= 16384 && targetH <= 16384 && targetW * targetH * 4 <= budget) break;
        scale *= 2;
    } while (true);

    const bool alpha = a.hasAlpha(image) != 0;
    Object<VipsImage> straight(a), restored(a), thumbnail(a), rgb(a), bytesImage(a);
    Object<VipsImage> rgba(a), premultiplied(a), finalImage(a);
    int alreadyPremultiplied = 0;
    if (a.typeOf(image, "premultiplied")) {
        checkVips(a, a.getInt(image, "premultiplied", &alreadyPremultiplied));
    }
    if (alreadyPremultiplied) {
        require(alpha, "Premultiplied image has no alpha channel");
        const VipsBandFormat originalFormat = a.format(image);
        checkVips(a, a.unpremultiply(image, &straight.value, nullptr));
        checkVips(a, a.cast(straight.value, &restored.value, originalFormat, nullptr));
        a.remove(restored.value, "premultiplied");
        image = restored.value;
    }
    checkVips(a, a.thumbnail(image, &thumbnail.value, static_cast<int>(targetW),
            "height", static_cast<int>(targetH), "size", VIPS_SIZE_DOWN,
            "no_rotate", 1, "export_profile", "srgb", nullptr));
    checkVips(a, a.colourspace(thumbnail.value, &rgb.value, VIPS_INTERPRETATION_sRGB, nullptr));
    checkVips(a, a.cast(rgb.value, &bytesImage.value, VIPS_FORMAT_UCHAR, nullptr));
    image = bytesImage.value;
    require(a.bands(image) == (alpha ? 4 : 3), "Unexpected sRGB channel count");
    if (!alpha) {
        double opaque = 255.0;
        checkVips(a, a.bandjoinConst(image, &rgba.value, &opaque, 1, nullptr));
        image = rgba.value;
    } else {
        // Bitmap consumes premultiplied RGBA bytes, not merely a premultiplied flag.
        checkVips(a, a.premultiply(image, &premultiplied.value, "max_alpha", 255.0, nullptr));
        checkVips(a, a.cast(premultiplied.value, &finalImage.value, VIPS_FORMAT_UCHAR, nullptr));
        image = finalImage.value;
    }
    require(a.bands(image) == 4 && a.format(image) == VIPS_FORMAT_UCHAR,
            "Output is not RGBA8");
    const uint64_t expectedBytes = outputBytes(a, image, budget);
    std::size_t size = 0;
    errno = 0;
    std::unique_ptr<void, void (*)(void*)> pixels(a.writeMemory(image, &size), a.freeMemory);
    if (!pixels) {
        if (errno == ENOMEM) throw std::bad_alloc();
        checkVips(a, -1);
    }
    require(size == expectedBytes && size <= budget, "Unexpected rendered byte count");
    return copyBitmap(env, pixels.get(), size, a.width(image), a.height(image), alpha);
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_igalia_wolvic_manga_MangaImageDecoder_nativeReadBounds(JNIEnv* env, jclass, jstring path) {
    try {
        Api& a = api();
        Input input(a, filePath(env, path));
        const jint dimensions[] = {a.width(input.oriented.value), a.height(input.oriented.value)};
        jintArray result = env->NewIntArray(2);
        checkJava(env);
        if (!result) throw std::bad_alloc();
        env->SetIntArrayRegion(result, 0, 2, dimensions);
        checkJava(env);
        return result;
    } catch (const PendingJavaException&) {
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Cannot allocate image bounds");
    } catch (const std::exception& error) {
        throwJava(env, "java/io/IOException", error.what());
    } catch (...) {
        throwJava(env, "java/io/IOException", "Cannot read image bounds");
    }
    return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_igalia_wolvic_manga_MangaImageDecoder_nativeDecodeSdr(
        JNIEnv* env, jclass, jstring path, jint sample, jlong byteBudget, jintArray sourceSize) {
    try {
        return decode(env, path, sample, byteBudget, sourceSize);
    } catch (const PendingJavaException&) {
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Cannot allocate decoded image");
    } catch (const std::exception& error) {
        throwJava(env, "java/io/IOException", error.what());
    } catch (...) {
        throwJava(env, "java/io/IOException", "Cannot decode image");
    }
    return nullptr;
}
