#include "../image/guest/EnvironmentState.h"
#include <iostream>
int main(int argc,char** argv) {
    if(argc!=2&&argc!=3)return 2;mcphone::Snapshot s;
    if(argc==3){std::cout<<"READY"<<std::endl;if(std::cin.get()==EOF)return 2;}
    if(!mcphone::readSnapshot(s,argv[1])) {
        std::ifstream file(argv[1],std::ios::binary);std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)),{});
        std::cerr<<"state bytes="<<bytes.size()<<" now="<<mcphone::monotonicNanos();
        if(bytes.size()>=8)std::cerr<<" received="<<mcphone::big(bytes.data(),8)<<" decode="<<mcphone::decode(std::vector<uint8_t>(bytes.begin()+8,bytes.end()),s)<<" available="<<s.available;
        std::cerr<<std::endl;return 3;
    }
    std::cout<<s.sequence<<" "<<s.dimension<<" "<<s.values[0]<<" "<<s.values[11]<<" "<<s.locationValid<<std::endl;
    return 0;
}
