# Experimental size profiles are scoped to the tested 64-bit VirtIO product.
ifneq ($(filter compact minimal,$(MCANDROIDPHONE_IMAGE_PROFILE)),)
ifneq ($(TARGET_PRODUCT),lineage_virtio_x86_64_go)
$(error MCAndroidPhone compact profiles currently require AMD64 Go)
endif
# Keep original APK DEX and runtime JIT. Avoid whole-app speed precompilation.
PRODUCT_DEXPREOPT_SPEED_APPS :=
PRODUCT_SYSTEM_SERVER_COMPILER_FILTER := speed-profile
# Let the outer image compressor see APEX contents instead of nested deflate.
PRODUCT_COMPRESSED_APEX := false
$(call soong_config_set_bool,mcandroidphone,compact_amd64,true)
endif

# SystemServer must not start WebViewUpdateService without a default provider.
ifeq ($(MCANDROIDPHONE_IMAGE_PROFILE),minimal)
PRODUCT_COPY_FILES += \
    vendor/mcandroidphone/compact/no-webview.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/mcandroidphone-no-webview.xml
endif