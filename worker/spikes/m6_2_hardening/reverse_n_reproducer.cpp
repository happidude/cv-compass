// Reduced Tesseract 5.5.3 ReverseN/FReadEndian/DeSerialize path.
// Adapted from Apache-2.0 upstream; this is a diagnostic, not a replacement binary.
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
namespace tesseract {
inline void ReverseN(void *ptr, int num_bytes) {
  assert(num_bytes == 1 || num_bytes == 2 || num_bytes == 4 || num_bytes == 8);
  char *cptr = static_cast<char *>(ptr);
  int halfsize = num_bytes / 2;
  for (int i = 0; i < halfsize; ++i) {
    char tmp = cptr[i];
    cptr[i] = cptr[num_bytes - 1 - i];
    cptr[num_bytes - 1 - i] = tmp;
  }
}
struct TFile {
  std::vector<char> *data_; size_t offset_ = 0; bool swap_ = true;
  size_t FRead(void *buffer, size_t size, size_t count) {
    size_t required_size;
    if (SIZE_MAX / size <= count) required_size = data_->size()-offset_;
    else {
      required_size = size*count;
      if(data_->size()-offset_ < required_size) required_size=data_->size()-offset_;
    }
    if(required_size > 0 && buffer != nullptr) memcpy(buffer,&(*data_)[offset_],required_size);
    offset_ += required_size;
    return required_size/size;
  }
  size_t FReadEndian(void *buffer, size_t size, size_t count) {
    auto num_read=FRead(buffer,size,count);
    if(swap_ && size != 1) {
      char *char_buffer=static_cast<char *>(buffer);
      for(size_t i=0;i<num_read;++i,char_buffer+=size) ReverseN(char_buffer,size);
    }
    return num_read;
  }
  template<typename T> bool DeSerialize(T *data,size_t count=1) {
    return FReadEndian(data,sizeof(T),count)==count;
  }
  bool ReadSize(uint32_t &out) {
    uint32_t size;
    if (!DeSerialize(&size)) return false;
    out=size;
    return true;
  }
};
}
int main() {
  // Bounded ordinary buffers including short reads; no hostile traineddata/PDF.
  unsigned cases=0;
  for(size_t length=0;length<=512;++length) {
    std::vector<char> data(length);
    for(size_t i=0;i<length;++i) data[i]=static_cast<char>(i);
    tesseract::TFile file{&data}; uint32_t actual=0;
    bool ok=file.ReadSize(actual);
    if(ok != (length>=4)) return 1;
    if(ok && actual!=0x00010203u) return 2;
    ++cases;
  }
  for(int size: {1,2,4,8}) {
    char actual[8]={0,1,2,3,4,5,6,7};
    tesseract::ReverseN(actual,size);
    for(int i=0;i<size;++i) if(actual[i]!=size-i-1) return 3;
    ++cases;
  }
  return cases==517 ? 0 : 4;
}
