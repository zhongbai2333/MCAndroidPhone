// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <time.h>
#include <vector>

namespace mcphone {
constexpr const char* kStatePath = "/data/vendor/mcandroidphone/environment.bin";
constexpr uint32_t kMagic = 0x4d435045;
inline uint64_t monotonicNanos() {
    timespec t{};
#ifdef CLOCK_BOOTTIME
    clock_gettime(CLOCK_BOOTTIME, &t);
#else
    clock_gettime(CLOCK_MONOTONIC, &t);
#endif
    return uint64_t(t.tv_sec) * 1000000000ULL + t.tv_nsec;
}
inline uint64_t big(const uint8_t* p, size_t n) {uint64_t v=0;while(n--)v=(v<<8)|*p++;return v;}
inline void putBig(std::vector<uint8_t>& bytes,uint64_t v,size_t n){for(size_t i=n;i>0;--i)bytes.push_back(uint8_t(v>>((i-1)*8)));}
struct Snapshot {
    uint64_t sequence{}, hostElapsed{};
    bool available{}, locationValid{}, discontinuity{};
    std::string dimension;
    std::array<double,20> values{};
};
inline bool decode(const std::vector<uint8_t>& frame,Snapshot& out) {
    if(frame.size()<196 || frame.size()>516 || big(frame.data(),4)!=frame.size()-4)return false;
    const uint8_t* p=frame.data()+4;
    if(big(p,4)!=kMagic || big(p+4,4)!=1)return false;
    auto nameLength=big(p+27,4);
    if(nameLength<1 || nameLength>128 || frame.size()!=195+nameLength || p[24]>1 || p[25]>1 || p[26]>1)return false;
    out.sequence=big(p+8,8);out.hostElapsed=big(p+16,8);
    if(out.sequence>INT64_MAX || out.hostElapsed>INT64_MAX)return false;
    out.available=p[24];out.locationValid=out.available&&p[25];out.discontinuity=p[26];
    out.dimension.assign(reinterpret_cast<const char*>(p+31),nameLength);
    for(char c:out.dimension)if(!((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='_'||c=='.'||c==':'||c=='/'||c=='-'))return false;
    for(size_t i=0;i<20;++i){uint64_t bits=big(p+31+nameLength+i*8,8);std::memcpy(&out.values[i],&bits,8);if(!std::isfinite(out.values[i]))return false;}
    const auto& v=out.values;double n=v[6]*v[6]+v[7]*v[7]+v[8]*v[8]+v[9]*v[9];
    return std::abs(n-1)<.001 && std::abs(v[3])<=90 && std::abs(v[4])<=180 && v[16]>0 && v[17]>=0 && v[18]>=0 && v[19]>=0 && v[19]<360;
}
inline bool readSnapshot(Snapshot& result,const char* path=kStatePath) {
    std::ifstream file(path,std::ios::binary);if(!file)return false;
    std::array<uint8_t,525> buffer{};file.read(reinterpret_cast<char*>(buffer.data()),buffer.size());auto count=file.gcount();
    if(count<204 || count>=static_cast<std::streamsize>(buffer.size()))return false;
    uint64_t received=big(buffer.data(),8),now=monotonicNanos();
    if(received>now || now-received>500000000ULL)return false;
    return decode(std::vector<uint8_t>(buffer.begin()+8,buffer.begin()+count),result)&&result.available;
}
// A virtual magnetic north (25 uT horizontal, -40 uT vertical), transformed into device axes.
inline std::array<float,3> magnetic(const Snapshot& s) {
    double x=-s.values[6],y=-s.values[7],z=-s.values[8],w=s.values[9];
    double vx=0,vy=25,vz=-40,tx=2*(y*vz-z*vy),ty=2*(z*vx-x*vz),tz=2*(x*vy-y*vx);
    return {float(vx+w*tx+y*tz-z*ty),float(vy+w*ty+z*tx-x*tz),float(vz+w*tz+x*ty-y*tx)};
}
}
