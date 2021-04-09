/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 * 对象Channel
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    protected boolean continueReading(RecvByteBufAllocator.Handle allocHandle) {
        return allocHandle.continueReading();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            // 获取Channel的配置对象
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            // 获取计算内存分配Handle
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            // 清空上次的记录
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        /**
                         * 调用子类的 doReadMessages（）方法
                         * 读取数据包，并放入readBuf链表中
                         * 当成功读取时，返回1
                         */
                        int localRead = doReadMessages(readBuf);
                        // 已无数据，跳出循环
                        if (localRead == 0) {
                            break;
                        }
                        // 链路关闭，跳出循环
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }

                        // 记录成功读取的次数
                        allocHandle.incMessagesRead(localRead);
                        // 默认不能超过16次
                    } while (continueReading(allocHandle));
                } catch (Throwable t) {
                    exception = t;
                }

                int size = readBuf.size();
                // 循环处理读取的数据包
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    // 触发channelRead事件
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                readBuf.clear();
                // 记录当前读取记录，以便下次分配合理内存
                allocHandle.readComplete();
                // 触发readComplete事件
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    // 处理channel 异常关闭
                    closed = closeOnReadError(exception);

                    pipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                // 读操作完毕，且没有配置自动读
                if (!readPending && !config.isAutoRead()) {
                    // 移除读操作事件
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        // 获取Key兴趣集
        final int interestOps = key.interestOps();

        int maxMessagesPerWrite = maxMessagesPerWrite();
        while (maxMessagesPerWrite > 0) {
            Object msg = in.current();
            // 数据已全部发送完，从兴趣集中移除OP_WRITE事件
            if (msg == null) {
                break;
            }
            try {
                boolean done = false;
                // 获取配置中循环写的最大次数
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    // 调用子类方法，若msg写成功了，则返回true
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                // 若发送成功，则将其从缓存链表中移除
                // 继续发送下一个缓存节点数据
                if (done) {
                    maxMessagesPerWrite--;
                    in.remove();
                } else {
                    // 若没有成功，直接跳出循环
                    break;
                }
            } catch (Exception e) {
                // 当出现异常时，判断是否继续写
                if (continueOnWriteError()) {
                    maxMessagesPerWrite--;
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
        if (in.isEmpty()) {
            // Wrote all messages.
            if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
            }
        } else {
            // Did not write all messages.
            if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                key.interestOps(interestOps | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
