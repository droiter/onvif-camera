/*
 * Copyright (C) 2011-2014 GUIGUI Simon, fyhertz@gmail.com
 *
 * This file is part of libstreaming (https://github.com/fyhertz/libstreaming)
 *
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.video;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import net.majorkernelpanic.spydroid.SpydroidApplication;
import net.majorkernelpanic.spydroid.Utilities;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.exceptions.ConfNotSupportedException;
import net.majorkernelpanic.streaming.exceptions.StorageUnavailableException;
import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.mp4.MP4Config;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;

import android.annotation.SuppressLint;
import android.content.SharedPreferences.Editor;
import android.graphics.ImageFormat;
import android.hardware.Camera.CameraInfo;
import android.media.MediaRecorder;
import android.os.Environment;
import android.service.textservice.SpellCheckerService.Session;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import org.spongycastle.asn1.esf.SPuri;

/**
 * A class for streaming H.264 from the camera of an android device using RTP.
 * You should use a {@link Session} instantiated with {@link SessionBuilder} instead of using this class directly.
 * Call {@link net.majorkernelpanic.streaming.rtp.AbstractPacketizer#setDestination(InetAddress, int, int)},
 * {@link #setDestinationPorts(int)} and {@link #setVideoQuality(VideoQuality)}
 * to configure the stream. You can then call {@link #start()} to start the RTP stream.
 * Call {@link #stop()} to stop the stream.
 */
public class H264Stream extends VideoStream {

    public final static String TAG = "H264Stream";

    private Semaphore mLock = new Semaphore(0);
    private MP4Config mConfig;

    /**
     * Constructs the H.264 stream.
     * Uses CAMERA_FACING_BACK by default.
     */
    public H264Stream() {
        this(CameraInfo.CAMERA_FACING_BACK);
    }

    /**
     * Constructs the H.264 stream.
     *
     * @param cameraId Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
     * @throws IOException
     */
    public H264Stream(int cameraId) {
        super(cameraId);
        mMimeType = "video/avc";
        mCameraImageFormat = ImageFormat.NV21;
        mVideoEncoder = MediaRecorder.VideoEncoder.H264;
        mPacketizer = new H264Packetizer();
    }

    /**
     * Returns a description of the stream using SDP(Session Description Protocol).
     * It can then be included in an SDP file.
     */
    @Override
    public synchronized String getSessionDescription() throws IllegalStateException {
        if (mConfig == null) {
            throw new IllegalStateException("You need to call configure() first !");
        }
        String sessionDescription = "m=video " + String.valueOf(getDestinationPorts()[0]) +
                " RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1;profile-level-id=" +
                mConfig.getProfileLevel() +
                ";sprop-parameter-sets=" + mConfig.getB64SPS() +
                "," + mConfig.getB64PPS() + ";\r\n";

        Log.d(TAG, "Session Description are --> " + sessionDescription);
        return sessionDescription;
    }

    /**
     * Starts the stream.
     * This will also open the camera and dispay the preview if {@link #startPreview()} has not aready been called.
     */
    @Override
    public synchronized void start() throws IllegalStateException, IOException {
        configure();
        if (!mStreaming) {
            Log.d(TAG, "the configured sps are " + mConfig.getB64SPS() + ", pps are " + mConfig.getB64PPS());
            byte[] pps = Base64.decode(mConfig.getB64PPS(), Base64.NO_WRAP);
            byte[] sps = Base64.decode(mConfig.getB64SPS(), Base64.NO_WRAP);
            Log.d(TAG, "pps content --> ");
            Utilities.printByteArr(TAG, pps);
            Log.d(TAG, "sps content --> ");
            Utilities.printByteArr(TAG, sps);
            ((H264Packetizer) mPacketizer).setStreamParameters(pps, sps);
            super.start();
        }
    }

    /**
     * Configures the stream. You need to call this before calling {@link #getSessionDescription()} to apply
     * your configuration of the stream.
     */
    @Override
    public synchronized void configure() throws IllegalStateException, IOException {
        super.configure();
        Log.d(TAG, "Configure H264 Stream");
        mMode = mRequestedMode;
        mQuality = mRequestedQuality.clone();

        // 配置时,会根据视频要传输的分辨率以及比特率来决定采用的编码器
        // 这种策略很牛逼
        // 如果我们使用ShareBuffer时,就不需要使用自动调整参数设置,而是使用我们固定好的参数
        if (!SpydroidApplication.USE_SHARE_BUFFER_DATA) {
            mConfig = testH264();
        } else {
            Log.d(TAG, "Encoder Debug start ... ");
            // 此时，我们需要自己手动生成config
            // 对于Ky设备，经过EncoderDebugger的测试，发现这个设备当中并没有直接针对
            // 640x480的编码器
            // 但是考虑到我们会将获取到的ShareBuffer数据通过yuv转换时，将视频帧缩小到
            // 320x240的格式，
            // 因此我们这里也可以直接定义成320x240的格式
//            EncoderDebugger shareBufferDebugger = EncoderDebugger.debug(SpydroidApplication.getInstance(),
//                    320, 240);
//            String ppsStr = shareBufferDebugger.getB64PPS();
//            String spsStr = shareBufferDebugger.getB64SPS();
            // Log.d(TAG, "our own test pps str are " + ppsStr + ", sps str are " + spsStr);
            // 这是针对前路镜头的
            mQuality.resX = 640;
            mQuality.resY = 480;

            Pair<String, String> spsNppsPair = EncoderDebugger.searchSPSandPPSForShareBuffer();
            final String sps, pps;
            if (spsNppsPair != null) {
                pps = spsNppsPair.first;
                sps = spsNppsPair.second;
            } else {
                pps = "aOpDyw==";
                sps = "Z2QAKawbGoFB+gHhEIpw";
            }
            mConfig = new MP4Config(sps, pps);
        }
    }

    /**
     * 这里的方法名称可能不太恰当,让人误以为这是测试方法
     * 准确来说,这个方法应该是"探测方法",即探测以前当前系统的性能底线,然后再决定采用什么编码方式.
     * <p>
     * Tests if streaming with the given configuration (bit rate, frame rate, resolution) is possible
     * and determines the pps and sps. Should not be called by the UI thread.
     **/
    private MP4Config testH264() throws IllegalStateException, IOException {
        if (mMode != MODE_MEDIARECORDER_API) {
            return testMediaCodecAPI();
        } else {
            return testMediaRecorderAPI();
        }
    }

    @SuppressLint("NewApi")
    private MP4Config testMediaCodecAPI() throws RuntimeException, IOException {
        Log.v(TAG, "test media codec API");
        createCamera();
        updateCamera();
        try {
            // 目前这里的策略指定过程考虑到了UI界面的交互操作
            // 但是实际上我们程序最终部署到地方是不需要考虑这些,因此这里的策略之后需要调整
            if (mQuality.resX >= 640) {
                // Using the MediaCodec API with the buffer method for high resolutions is too slow
                mMode = MODE_MEDIARECORDER_API;
            }
            EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);
            Log.d(TAG, "the sps are " + debugger.getB64SPS() + ", pps are " + debugger.getB64PPS());
            return new MP4Config(debugger.getB64SPS(), debugger.getB64PPS());
        } catch (Exception e) {
            // Fallback on the old streaming method using the MediaRecorder API
            Log.e(TAG, "Resolution not supported with the MediaCodec API, we fallback on the old streamign method.", e);
            mMode = MODE_MEDIARECORDER_API;
            return testH264();
        }
    }

    // Should not be called by the UI thread
    private MP4Config testMediaRecorderAPI() throws RuntimeException, IOException {
        Log.v(TAG, "test mediaRecorder API");
        String key = PREF_PREFIX + "h264-mr-" + mRequestedQuality.framerate + "," + mRequestedQuality.resX + "," + mRequestedQuality.resY;

        if (mSettings != null) {
            if (mSettings.contains(key)) {
                String[] s = mSettings.getString(key, "").split(",");
                return new MP4Config(s[0], s[1], s[2]);
            }
        }

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            throw new StorageUnavailableException("No external storage or external storage not ready !");
        }

        final String TESTFILE = Environment.getExternalStorageDirectory().getPath() + "/spydroid-test.mp4";

        Log.i(TAG, "Testing H264 support... Test file saved at: " + TESTFILE);

        try {
            File file = new File(TESTFILE);
            file.createNewFile();
        } catch (IOException e) {
            throw new StorageUnavailableException(e.getMessage());
        }

        // Save flash state & set it to false so that led remains off while testing h264
        boolean savedFlashState = mFlashEnabled;
        mFlashEnabled = false;

        boolean cameraOpen = mCamera != null;
        createCamera();

        // Stops the preview if needed
        if (mPreviewStarted) {
            lockCamera();
            try {
                mCamera.stopPreview();
            } catch (final Exception e) {
                Log.e(TAG, "Exception happened while stop the preview", e);
            }
            mPreviewStarted = false;
        }

        try {
            Thread.sleep(100);
        } catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        unlockCamera();

        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setCamera(mCamera);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setVideoEncoder(mVideoEncoder);
            if (!SpydroidApplication.USE_SHARE_BUFFER_DATA) {
                mMediaRecorder.setPreviewDisplay(mSurfaceView.getHolder().getSurface());
            }
            mMediaRecorder.setVideoSize(mRequestedQuality.resX, mRequestedQuality.resY);
            mMediaRecorder.setVideoFrameRate(mRequestedQuality.framerate);
            mMediaRecorder.setVideoEncodingBitRate((int) (mRequestedQuality.bitrate * 0.8));
            mMediaRecorder.setOutputFile(TESTFILE);
            mMediaRecorder.setMaxDuration(3000);

            // We wait a little and stop recording
            mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                public void onInfo(MediaRecorder mr, int what, int extra) {
                    Log.d(TAG, "MediaRecorder callback called !");
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.d(TAG, "MediaRecorder: MAX_DURATION_REACHED");
                    } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                        Log.d(TAG, "MediaRecorder: MAX_FILESIZE_REACHED");
                    } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
                        Log.d(TAG, "MediaRecorder: INFO_UNKNOWN");
                    } else {
                        Log.d(TAG, "WTF ?");
                    }
                    mLock.release();
                }
            });

            // Start recording
            mMediaRecorder.prepare();
            mMediaRecorder.start();

            if (mLock.tryAcquire(6, TimeUnit.SECONDS)) {
                Log.d(TAG, "MediaRecorder callback was called :)");
                Thread.sleep(400);
            } else {
                Log.d(TAG, "MediaRecorder callback was not called after 6 seconds... :(");
            }
        } catch (IOException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } catch (RuntimeException e) {
            throw new ConfNotSupportedException(e.getMessage());
        } catch (InterruptedException e) {
            Log.e(TAG, "thread interrupted exception happened", e);
        } finally {
            try {
                mMediaRecorder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Exception happened while stop the MediaRecorder", e);
            }
            mMediaRecorder.release();
            mMediaRecorder = null;
            lockCamera();
            if (!cameraOpen) destroyCamera();
            // Restore flash state
            mFlashEnabled = savedFlashState;
        }

        // Retrieve SPS & PPS & ProfileId with MP4Config
        MP4Config config = new MP4Config(TESTFILE);

        // Delete dummy video
        File file = new File(TESTFILE);
        if (!file.delete()) Log.e(TAG, "Temp file could not be erased");

        Log.i(TAG, "H264 Test succeded...");

        // Save test result
        if (mSettings != null) {
            Editor editor = mSettings.edit();
            editor.putString(key, config.getProfileLevel() + "," + config.getB64SPS() + "," + config.getB64PPS());
            editor.commit();
        }
        return config;
    }
}
