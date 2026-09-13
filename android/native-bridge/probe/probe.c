// Test fixture, MIT. Deliberately no host-dependent C library calls.
__attribute__((visibility("default")))
int Java_com_zhongbai233_mcphone_armprobe_MainActivity_nativeProbe(void *env, void *type) {
    (void)env; (void)type;
    int value=40;
    __asm__ volatile("add %w0, %w0, #2" : "+r"(value));
    return value;
}
