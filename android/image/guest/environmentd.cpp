// SPDX-License-Identifier: Apache-2.0
#include "EnvironmentState.h"
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <poll.h>
#include <thread>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
bool transfer(int fd,uint8_t* bytes,size_t count,bool writing) {
    uint64_t deadline=mcphone::monotonicNanos()+3000000000ULL;
    while(count) {
        pollfd p{fd,short(writing?POLLOUT:POLLIN),0};int ready=poll(&p,1,200);
        if(ready<0&&errno==EINTR)continue;
        if(ready<0 || (p.revents&(POLLERR|POLLNVAL)) || mcphone::monotonicNanos()>deadline)return false;
        if(ready==0)continue;
        ssize_t n=writing?write(fd,bytes,count):read(fd,bytes,count);
        if(n<0&&(errno==EAGAIN||errno==EINTR))continue;
        if(n<=0)return false;bytes+=n;count-=n;
    }
    return true;
}
bool store(const std::string& path,const std::vector<uint8_t>& frame) {
    std::vector<uint8_t> bytes;mcphone::putBig(bytes,mcphone::monotonicNanos(),8);bytes.insert(bytes.end(),frame.begin(),frame.end());
    std::string temporary=path+".tmp";int fd=open(temporary.c_str(),O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0644);
    if(fd<0)return false;bool ok=transfer(fd,bytes.data(),bytes.size(),true);close(fd);
    if(ok)ok=rename(temporary.c_str(),path.c_str())==0;if(!ok)unlink(temporary.c_str());return ok;
}
std::string findPort() {
    std::error_code ec;
    for(const auto& entry:std::filesystem::directory_iterator("/sys/class/virtio-ports",ec)) {
        std::ifstream file(entry.path()/"name");std::string name;std::getline(file,name);
        if(name=="com.mcandroidphone.environment")return "/dev/"+entry.path().filename().string();
    }
    return {};
}
bool session(int input,int output,const std::string& path) {
    std::vector<uint8_t> hello;mcphone::putBig(hello,mcphone::kMagic,4);mcphone::putBig(hello,1,4);
    if(!transfer(output,hello.data(),hello.size(),true))return false;
    uint64_t previous=0;
    for(;;) {
        std::vector<uint8_t> frame(4);if(!transfer(input,frame.data(),4,false))return false;
        auto size=mcphone::big(frame.data(),4);if(size<192||size>512)return false;
        frame.resize(size+4);if(!transfer(input,frame.data()+4,size,false))return false;
        mcphone::Snapshot snapshot;if(!mcphone::decode(frame,snapshot)||snapshot.sequence<previous)return false;
        previous=snapshot.sequence;if(!store(path,frame))return false;
        std::vector<uint8_t> ack;mcphone::putBig(ack,previous,8);if(!transfer(output,ack.data(),ack.size(),true))return false;
    }
}
}
int main(int argc,char** argv) {
    // --stdio is only for the portable cross-language test, with a caller-owned output path.
    if(argc==3&&std::string(argv[1])=="--stdio") {session(STDIN_FILENO,STDOUT_FILENO,argv[2]);unlink(argv[2]);return 0;}
    if(argc!=1)return 2;
    for(;;) {
        std::string port=findPort();int fd=port.empty()?-1:open(port.c_str(),O_RDWR|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
        if(fd>=0){session(fd,fd,mcphone::kStatePath);close(fd);}
        unlink(mcphone::kStatePath);std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
}
