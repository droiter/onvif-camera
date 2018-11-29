ROOT_PATH := $(call my-dir)
YUV_PATH := $(call my-dir)
LIVE_SERVER_PATH := $(call my-dir)

$(info "the yuv path are :" $(YUV_PATH))

include $(YUV_PATH)/libyuv/Android.mk

include $(LIVE_SERVER_PATH)/live_server/Android.mk

##################################################
# CLEAR_VARS will not clear LOCAL_PATH, but other variables will be cleared. but other module may define the LOCAL_PATH again
include $(CLEAR_VARS)

$(info "current local path are :" $(ROOT_PATH))

# curious here, that we must specify the detailed path of the color_converter.c file
LOCAL_SRC_FILES := $(ROOT_PATH)/color_converter.cc \
                   $(ROOT_PATH)/color_convert_jni.cc

LOCAL_C_INCLUDES := $(ROOT_PATH)/ \
                    $(ROOT_PATH)/../libyuv/include \


LOCAL_MODULE := color_converter

LOCAL_SHARED_LIBRARIES := libyuv

LOCAL_LDLIBS := -llog -ljnigraphics -landroid

include $(BUILD_SHARED_LIBRARY)