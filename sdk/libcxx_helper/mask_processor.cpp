#include <jni.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <vector>

// リリース版ではログを完全に消去するための設定
#ifdef NDEBUG
  #define LOGD(...) ((void)0)
  #define LOGE(...) ((void)0)
#else
  #include <android/log.h>
  #define LOG_TAG "UtilsCore"
  #define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
  #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

using namespace cv;

extern "C" {

// 内部ヘルパー関数の名前も少し分かりにくく変更
bool b2m_safe(JNIEnv *env, jobject b, Mat &d) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, b, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, b, &pixels) < 0) return false;
    Mat tmp(info.height, info.width, CV_8UC4, pixels, info.stride);
    tmp.copyTo(d); 
    AndroidBitmap_unlockPixels(env, b);
    return true;
}

bool m2b_safe(JNIEnv *env, const Mat &s, jobject b) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, b, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, b, &pixels) < 0) return false;
    Mat dst(info.height, info.width, CV_8UC4, pixels, info.stride);
    if (s.type() == CV_8UC4) s.copyTo(dst);
    else if (s.type() == CV_8UC3) cvtColor(s, dst, COLOR_RGB2RGBA);
    else if (s.type() == CV_8UC1) cvtColor(s, dst, COLOR_GRAY2RGBA);
    AndroidBitmap_unlockPixels(env, b);
    return true;
}

// JNIメソッド名も Kotlin のパッケージ名と整合させつつ、
// クラス内の @JvmStatic に対応したシグネチャ (jclass) を維持
JNIEXPORT jboolean JNICALL
Java_com_timcatworld_platemasker_MaskProcessor_blurNative(
        JNIEnv *env, jclass clazz, jobject bitmap, jfloatArray vertices, jfloat radius) {
    if (bitmap == nullptr) return JNI_FALSE;
    Mat mat;
    if (!b2m_safe(env, bitmap, mat)) return JNI_FALSE;
    jsize len = env->GetArrayLength(vertices);
    if (len < 6) return JNI_FALSE; 
    jfloat *v = env->GetFloatArrayElements(vertices, nullptr);
    if (v == nullptr) return JNI_FALSE;
    std::vector<Point> pts;
    for (int i = 0; i < len; i += 2) pts.push_back(Point((int)v[i], (int)v[i+1]));
    env->ReleaseFloatArrayElements(vertices, v, JNI_ABORT);
    try {
        Mat mask = Mat::zeros(mat.size(), CV_8UC1);
        std::vector<std::vector<Point>> polyPts = {pts};
        fillPoly(mask, polyPts, Scalar(255), LINE_AA);
        Mat blurred;
        int ksize = (int)radius * 2 + 1;
        ksize = std::max(1, std::min(ksize, 255));
        GaussianBlur(mat, blurred, Size(ksize, ksize), 0);
        blurred.copyTo(mat, mask);
        return m2b_safe(env, mat, bitmap) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

JNIEXPORT jint JNICALL
Java_com_timcatworld_platemasker_MaskProcessor_calcAverageColorNative(
        JNIEnv *env, jclass clazz, jobject bitmap, jfloatArray vertices) {
    if (bitmap == nullptr) return -1;
    Mat mat;
    if (!b2m_safe(env, bitmap, mat)) return -1;
    jsize len = env->GetArrayLength(vertices);
    if (len < 6) return -1;
    jfloat *v = env->GetFloatArrayElements(vertices, nullptr);
    if (v == nullptr) return -1;
    std::vector<Point> pts;
    for (int i = 0; i < len; i += 2) pts.push_back(Point((int)v[i], (int)v[i+1]));
    env->ReleaseFloatArrayElements(vertices, v, JNI_ABORT);
    try {
        Mat mask = Mat::zeros(mat.size(), CV_8UC1);
        std::vector<std::vector<Point>> polyPts = {pts};
        fillPoly(mask, polyPts, Scalar(255));
        Mat gray;
        cvtColor(mat, gray, COLOR_RGBA2GRAY);
        Rect roi = boundingRect(pts) & Rect(0, 0, gray.cols, gray.rows);
        if (roi.width <= 0 || roi.height <= 0) return -1;
        Mat subGray = gray(roi);
        Mat binary;
        double thresh = threshold(subGray, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);
        Mat maskAbove, maskBelow;
        threshold(gray, binary, thresh, 255, THRESH_BINARY);
        bitwise_and(binary, mask, maskAbove);
        bitwise_not(binary, binary);
        bitwise_and(binary, mask, maskBelow);
        int countAbove = countNonZero(maskAbove);
        int countBelow = countNonZero(maskBelow);
        Mat finalMask = (countAbove >= countBelow) ? maskAbove : maskBelow;
        if (countNonZero(finalMask) == 0) finalMask = mask;
        Scalar avg = mean(mat, finalMask);
        int r = (int)avg[0]; int g = (int)avg[1]; int b = (int)avg[2];
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    } catch (...) { return -1; }
}

} // extern "C"
