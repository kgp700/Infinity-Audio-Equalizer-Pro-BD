#include <jni.h>
#include <cmath>
#include <algorithm>
#include <vector>
#include <mutex>
#include <cstdint>

namespace {
constexpr double PI = 3.14159265358979323846;
struct Biquad {
    double b0=1,b1=0,b2=0,a1=0,a2=0;
    double z1[2]={},z2[2]={};
    void configure(double fs, double freq, double gain, double q) {
        freq=std::clamp(freq, 10.0, fs*0.49);
        q=std::clamp(q,0.1,20.0);
        const double a=std::pow(10.0,gain/40.0), w=2*PI*freq/fs;
        const double alpha=std::sin(w)/(2*q), cw=std::cos(w);
        const double norm=1+alpha/a;
        b0=(1+alpha*a)/norm; b1=(-2*cw)/norm; b2=(1-alpha*a)/norm;
        a1=(-2*cw)/norm; a2=(1-alpha/a)/norm;
    }
    inline double process(double x,int ch) {
        double y=b0*x+z1[ch];
        z1[ch]=b1*x-a1*y+z2[ch]; z2[ch]=b2*x-a2*y;
        if(std::abs(z1[ch])<1e-25) z1[ch]=0;
        if(std::abs(z2[ch])<1e-25) z2[ch]=0;
        return y;
    }
};
struct Processor {
    std::mutex lock;
    int sampleRate=48000;
    double preamp=0;
    double stereoWidth=0; // 0 = exact bypass; 0.5 = gentle side boost with fixed headroom
    std::vector<Biquad> filters;
    // Reconfiguration swaps banks; gradual crossfade avoids filter coefficient discontinuities.
    std::vector<Biquad> oldFilters;
    double oldPreamp=0;
    int fadeRemaining=0,fadeTotal=0;
    void configure(int rate, const std::vector<double>& data, double pre, int fadeFrames) {
        std::lock_guard<std::mutex> guard(lock);
        oldFilters=filters; oldPreamp=preamp;
        filters.clear(); sampleRate=rate; preamp=std::clamp(pre,-60.0,0.0);
        for(size_t i=0;i+2<data.size();i+=3) {
            if(!std::isfinite(data[i]) || !std::isfinite(data[i+1]) || !std::isfinite(data[i+2])) continue;
            Biquad f; f.configure(rate,data[i],std::clamp(data[i+1],-24.0,24.0),data[i+2]); filters.push_back(f);
        }
        fadeTotal=std::clamp(fadeFrames,128,8192); fadeRemaining=fadeTotal;
    }
    void run(float* data, int frames) {
        std::lock_guard<std::mutex> guard(lock);
        const double g=std::pow(10.0,preamp/20.0), oldG=std::pow(10.0,oldPreamp/20.0);
        for(int i=0;i<frames;i++) {
            const double t=fadeRemaining>0 && fadeTotal>0 ? 1.0-double(fadeRemaining)/fadeTotal : 1.0;
            for(int ch=0;ch<2;ch++) {
                const int idx=2*i+ch;
                double v=data[idx]; if(!std::isfinite(v)) v=0;
                double y=v*g;
                for(auto& f:filters) y=f.process(y,ch);
                if(fadeRemaining>0) {
                    double old=v*oldG;
                    for(auto& f:oldFilters) old=f.process(old,ch);
                    y=old*(1-t)+y*t;
                }
                // No compressor, limiter, or AGC. PCM float may exceed 1.0;
                // conversion to integer PCM must explicitly clamp at the output boundary.
                data[idx]=std::isfinite(y)?float(y):0.f;
            }
            if(stereoWidth>0) {
                // Mid/Side expansion, static normalization, no time-varying gain or phase tricks.
                const double l=data[2*i], r=data[2*i+1];
                const double mid=(l+r)*0.5, side=(l-r)*0.5;
                const double norm=1.0/(1.0+stereoWidth);
                data[2*i]=float((mid+side*(1.0+stereoWidth))*norm);
                data[2*i+1]=float((mid-side*(1.0+stereoWidth))*norm);
            }
            if(fadeRemaining>0) --fadeRemaining;
        }
        if(fadeRemaining==0) oldFilters.clear();
    }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_com_infinity_audioequalizerpro_NativeDsp_nativeCreate(JNIEnv*,jobject) { return reinterpret_cast<jlong>(new Processor()); }
extern "C" JNIEXPORT void JNICALL Java_com_infinity_audioequalizerpro_NativeDsp_nativeDestroy(JNIEnv*,jobject,jlong ptr) { delete reinterpret_cast<Processor*>(ptr); }
extern "C" JNIEXPORT void JNICALL Java_com_infinity_audioequalizerpro_NativeDsp_nativeConfigure(JNIEnv* env,jobject,jlong ptr,jint rate,jdoubleArray settings,jdouble pre,jint fade) {
    if(!ptr || rate<8000 || rate>384000 || !settings) return;
    const jsize n=env->GetArrayLength(settings); if(n>192 || n%3) return;
    std::vector<double> vals(n); env->GetDoubleArrayRegion(settings,0,n,vals.data());
    reinterpret_cast<Processor*>(ptr)->configure(rate,vals,pre,fade);
}
extern "C" JNIEXPORT void JNICALL Java_com_infinity_audioequalizerpro_NativeDsp_nativeProcess(JNIEnv* env,jobject,jlong ptr,jfloatArray samples) {
    if(!ptr || !samples) return;
    jsize n=env->GetArrayLength(samples); if(n<=0 || n%2 || n>1048576) return;
    std::vector<float> data(n); env->GetFloatArrayRegion(samples,0,n,data.data());
    reinterpret_cast<Processor*>(ptr)->run(data.data(),n/2);
    env->SetFloatArrayRegion(samples,0,n,data.data());
}

extern "C" JNIEXPORT void JNICALL Java_com_infinity_audioequalizerpro_NativeDsp_nativeSetStereoWidth(JNIEnv*,jobject,jlong ptr,jdouble width) {
    if(!ptr || !std::isfinite(width)) return;
    auto* p=reinterpret_cast<Processor*>(ptr); std::lock_guard<std::mutex> guard(p->lock);
    p->stereoWidth=std::clamp(double(width),0.0,0.5);
}
