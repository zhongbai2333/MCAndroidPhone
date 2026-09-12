# SPDX-License-Identifier: Apache-2.0
# Included directly at the end of the upstream *_go product. Keep boot layout.
include vendor/mcandroidphone/go-optimization.mk
PRODUCT_BRAND := MCAndroidPhone
PRODUCT_MANUFACTURER := MCAndroidPhone
PRODUCT_MODEL := Minecraft Android Phone
PRODUCT_LOCALES := en_US zh_CN
PRODUCT_PACKAGES += mcphone-environmentd MCPhoneCamera
PRODUCT_VENDOR_PROPERTIES += ro.mcandroidphone.environment.version=1
# Product overlays outrank device overlays (including Lineage power-menu defaults).
PRODUCT_PACKAGE_OVERLAYS := vendor/mcandroidphone/overlay $(PRODUCT_PACKAGE_OVERLAYS)
# Keep WebView, installer, settings and the Go launcher from the inherited product.
# Do not enable ADB, mock location, sensor test injection or permissive SELinux here.
