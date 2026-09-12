# SPDX-License-Identifier: Apache-2.0
# Included directly in our root Go products, after all upstream inherit calls.
ifeq ($(filter lineage_virtio_arm64only_go lineage_virtio_x86_64_go,$(TARGET_PRODUCT)),)
$(error MCAndroidPhone Go optimization applied to an unsupported product)
endif

# Ship English/Chinese resource variants, while keeping all font and input support.
PRODUCT_LOCALES := en_US zh_CN

# Retain dex2oat/JIT and the original DEX. Compile ordinary preinstalled apps at
# runtime as needed; keep the system server profile and hot launcher/SystemUI code.
PRODUCT_DEX_PREOPT_DEFAULT_COMPILER_FILTER := verify
PRODUCT_SYSTEM_SERVER_COMPILER_FILTER := speed-profile
PRODUCT_DEXPREOPT_SPEED_APPS += SystemUI Launcher3QuickStepGo
PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD := false
PRODUCT_MINIMIZE_JAVA_DEBUG_INFO := true

# Do not override Go's heap/LMKD settings blindly, remove WebView/APEX/HAL, or
# disable DEX preoptimization globally. Compatibility is checked on built images.

# Fully compile the x86_64 system-server startup path; ordinary APKs keep DEX/JIT.
# ARM64 retains the upstream profile-based system-server compilation choice.
ifeq ($(TARGET_PRODUCT),lineage_virtio_x86_64_go)
PRODUCT_SYSTEM_SERVER_COMPILER_FILTER := speed
endif

include vendor/mcandroidphone/compact/product.mk
