#include <jni.h>
#include <android/bitmap.h>
#include <libheif/heif.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace {
void check(heif_error error) {
    if (error.code != heif_error_Ok) throw std::runtime_error(error.message ? error.message : "HEIC decode failed");
}

jobject decodeEye(JNIEnv* env, heif_context* context, heif_item_id id) {
    heif_image_handle* rawHandle = nullptr;
    check(heif_context_get_image_handle(context, id, &rawHandle));
    std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> handle(rawHandle, heif_image_handle_release);
    const auto width = heif_image_handle_get_width(handle.get());
    const auto height = heif_image_handle_get_height(handle.get());
    if (width <= 0 || height <= 0 || width > 12000 || height > 12000 || int64_t(width) * height > 64000000)
        throw std::runtime_error("Image dimensions exceed alpha memory limit");
    heif_image* rawImage = nullptr;
    check(heif_decode_image(handle.get(), &rawImage, heif_colorspace_RGB, heif_chroma_interleaved_RGBA, nullptr));
    std::unique_ptr<heif_image, decltype(&heif_image_release)> image(rawImage, heif_image_release);
    const auto decodedWidth = heif_image_get_width(image.get(), heif_channel_interleaved);
    const auto decodedHeight = heif_image_get_height(image.get(), heif_channel_interleaved);
    const double scale = std::min(1.0, 2048.0 / std::max(decodedWidth, decodedHeight));
    const int outputWidth = std::max(1, int(decodedWidth * scale));
    const int outputHeight = std::max(1, int(decodedHeight * scale));
    if (scale < 1.0) {
        heif_image* scaled = nullptr;
        check(heif_image_scale_image(image.get(), &scaled, outputWidth, outputHeight, nullptr));
        image.reset(scaled);
    }
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, outputWidth, outputHeight, env->GetStaticObjectField(configClass, argb));
    if (env->ExceptionCheck() || !bitmap) throw std::runtime_error("Could not allocate stereo bitmap");
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        throw std::runtime_error("Could not access stereo bitmap");
    size_t stride = 0;
    const uint8_t* source = heif_image_get_plane_readonly2(image.get(), heif_channel_interleaved, &stride);
    if (!source || stride < size_t(outputWidth) * 4) {
        AndroidBitmap_unlockPixels(env, bitmap);
        throw std::runtime_error("Invalid decoded image plane");
    }
    for (int row = 0; row < outputHeight; ++row)
        std::memcpy(static_cast<uint8_t*>(pixels) + row * info.stride, source + row * stride, outputWidth * 4);
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_okuyuki_stereo_StereoDecoder_decodeNative(JNIEnv* env, jobject, jstring path) {
    try {
        std::unique_ptr<heif_context, decltype(&heif_context_free)> context(heif_context_alloc(), heif_context_free);
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return nullptr;
        heif_error read = heif_context_read_from_file(context.get(), chars, nullptr);
        env->ReleaseStringUTFChars(path, chars);
        check(read);
        int count = 0;
        heif_entity_group* groups = heif_context_get_entity_groups(context.get(), heif_entity_group_ster, 0, &count);
        heif_item_id left = 0, right = 0;
        for (int index = 0; index < count; ++index) {
            if (groups[index].num_entities == 2) { left = groups[index].entities[0]; right = groups[index].entities[1]; break; }
        }
        heif_entity_groups_release(groups, count);
        if (!left || !right || left == right) throw std::runtime_error("This HEIC has no unambiguous stereo group");
        jobjectArray result = env->NewObjectArray(2, env->FindClass("android/graphics/Bitmap"), nullptr);
        if (!result) return nullptr;
        env->SetObjectArrayElement(result, 0, decodeEye(env, context.get(), left));
        env->SetObjectArrayElement(result, 1, decodeEye(env, context.get(), right));
        return result;
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what());
        return nullptr;
    }
}
