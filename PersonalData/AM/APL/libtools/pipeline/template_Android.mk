LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := __APP_NAME__
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_BUILT_MODULE_STEM := package.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_PRIVILEGED_MODULE := true
LOCAL_CERTIFICATE := testkey
ifeq ($(TARGET_BUILD_VARIANT),user)
LOCAL_SRC_FILES := $(LOCAL_MODULE)-release.apk
else
# In an userdebug/eng build, look for -debug.apk. If not found, look for release apk.
# If neither of them are found, build will fail.
ifneq ($(wildcard $(LOCAL_PATH)/$(LOCAL_MODULE)-debug.apk),)
LOCAL_SRC_FILES := $(LOCAL_MODULE)-debug.apk
else
LOCAL_SRC_FILES := $(LOCAL_MODULE)-release.apk
endif
endif

include $(BUILD_PREBUILT)