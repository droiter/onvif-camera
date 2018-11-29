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

package net.majorkernelpanic.streaming.rtp;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Random;

import net.majorkernelpanic.streaming.rtcp.SenderReport;

/**
 * Each packetizer inherits from this one and therefore uses RTP and UDP.
 * <p>
 * 从{@link AbstractPacketizer}的实现来看，{@link AbstractPacketizer}从最初的
 * 设计开始就从来没有打算使用TCP来最为底层的传输通道.
 * 参考{@link #AbstractPacketizer()}当中注释信息.
 * 如果我们要为rtp添加tcp传输通道，不能在{@link AbstractPacketizer}当中进行，
 * 而是需要在{@link AbstractPacketizer}的上一层进行，即{@link net.majorkernelpanic.streaming.MediaStream}
 * 当中进行，也就是说在多媒体数据流分包之前进行，毕竟只有udp才需要分包，tcp是不需要分包的.
 * tcp只需要从流进行操作.
 */
public abstract class AbstractPacketizer {
    protected static final int rtphl = RtpSocket.RTP_HEADER_LENGTH;

    // Maximum size of RTP packets
    protected final static int MAX_PACKET_SIZE = RtpSocket.MTU - 28;

    protected RtpSocket socket;
    protected InputStream is = null;
    protected byte[] buffer;

    protected long ts;

    public AbstractPacketizer() {
        int ssrc = new Random().nextInt();
        ts = new Random().nextInt();
        // 这里的设计有问题，理论上，我们只有在用户发起了setup请求之后，
        // 我们才能知道用于最终是想通过tcp还是udp来作为rtp的传输通道
        // 但是这里再创建AbstractPacketizer时，就直接默认采用RtpSocket了(即默认采用udp作为传输通道了).
        // 但是还有另外一种可能，那就是AbstractPacketizer从最开始的设计理念就是打算采用
        // udp来进行传输，而不是tcp来进行传输
        // 毕竟只有udp才需要分包，tcp是不需要分包，直接按流来进行传输
        socket = new RtpSocket();
        socket.setSSRC(ssrc);
    }

    public RtpSocket getRtpSocket() {
        return socket;
    }

    public SenderReport getRtcpSocket() {
        return socket.getRtcpSocket();
    }


    public void setSSRC(int ssrc) {
        socket.setSSRC(ssrc);
    }

    public int getSSRC() {
        return socket.getSSRC();
    }

    public void setInputStream(InputStream is) {
        this.is = is;
    }

    public void setTimeToLive(int ttl) throws IOException {
        socket.setTimeToLive(ttl);
    }

    /**
     * Sets the destination of the stream.
     *
     * @param dest     The destination address of the stream
     * @param rtpPort  Destination port that will be used for RTP
     * @param rtcpPort Destination port that will be used for RTCP
     */
    public void setDestination(InetAddress dest, int rtpPort, int rtcpPort) {
        socket.setDestination(dest, rtpPort, rtcpPort);
    }

    /**
     * Starts the packetizer.
     */
    public abstract void start();

    /**
     * Stops the packetizer.
     */
    public abstract void stop();

    /**
     * Updates data for RTCP SR and sends the packet.
     */
    protected void send(int length) throws IOException {
        socket.commitBuffer(length);
    }

    /**
     * For debugging purposes.
     */
    protected static String printBuffer(byte[] buffer, int start, int end) {
        String str = "";
        for (int i = start; i < end; i++) {
            str += "," + Integer.toHexString(buffer[i] & 0xFF);
        }
        return str;
    }

    /**
     * Used in packetizers to estimate timestamps in RTP packets.
     */
    protected static class Statistics {

        public final static String TAG = "Statistics";

        private int count = 700, c = 0;
        private float m = 0, q = 0;
        private long elapsed = 0;
        private long start = 0;
        private long duration = 0;
        private long period = 10000000000L;
        private boolean initoffset = false;

        Statistics() {
        }

        public Statistics(int count, int period) {
            this.count = count;
            this.period = period;
        }

        void reset() {
            initoffset = false;
            q = 0;
            m = 0;
            c = 0;
            elapsed = 0;
            start = 0;
            duration = 0;
        }

        void push(long value) {
            elapsed += value;
            if (elapsed > period) {
                elapsed = 0;
                long now = System.nanoTime();
                if (!initoffset || (now - start < 0)) {
                    start = now;
                    duration = 0;
                    initoffset = true;
                }
                // Prevents drifting issues by comparing the real duration of the
                // stream with the sum of all temporal lengths of RTP packets.
                value += (now - start) - duration;
                //Log.d(TAG, "sum1: "+duration/1000000+" sum2: "+(now-start)/1000000+" drift: "+((now-start)-duration)/1000000+" v: "+value/1000000);
            }
            if (c < 5) {
                // We ignore the first 20 measured values because they may not be accurate
                c++;
                m = value;
            } else {
                m = (m * q + value) / (q + 1);
                if (q < count) q++;
            }
        }

        long average() {
            long l = (long) m;
            duration += l;
            return l;
        }
    }
}
