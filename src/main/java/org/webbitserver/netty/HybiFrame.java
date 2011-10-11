package org.webbitserver.netty;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.webbitserver.WebSocketHandler;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

public class HybiFrame {

    private final int opcode;

    private final boolean fin;
    private final int rsv;
    private List<ChannelBuffer> fragments = new ArrayList<ChannelBuffer>();

    public HybiFrame(int opcode, boolean fin, int rsv, ChannelBuffer fragment) {
        this.opcode = opcode;
        this.fin = fin;
        this.rsv = rsv;
        fragments.add(fragment);
    }

    public void append(ChannelBuffer fragment) {
        fragments.add(fragment);
    }

    public ChannelBuffer encode() throws TooLongFrameException {
        int b0 = 0;
        if (fin) {
            b0 |= (1 << 7);
        }
        b0 |= (rsv % 8) << 4;
        b0 |= opcode % 128;

        ChannelBuffer buffer;
        int length = messageLength();

        if (opcode == Opcodes.OPCODE_PING && length > 125) {
            throw new TooLongFrameException("invalid payload for PING (payload length must be <= 125, was " + length);
        }

        if (length <= 125) {
            buffer = createBuffer(length + 2);
            buffer.writeByte(b0);
            buffer.writeByte(length);
        } else if (length <= 0xFFFF) {
            buffer = createBuffer(length + 4);
            buffer.writeByte(b0);
            buffer.writeByte(126);
            buffer.writeByte((length >>> 8) & 0xFF);
            buffer.writeByte((length) & 0xFF);
        } else {
            buffer = createBuffer(length + 10);
            buffer.writeByte(b0);
            buffer.writeByte(127);
            buffer.writeLong(length);
        }

        for (ChannelBuffer fragment : fragments) {
            buffer.writeBytes(fragment, fragment.readerIndex(), fragment.readableBytes());
        }
        return buffer;
    }

    private int messageLength() {
        int n = 0;
        for (ChannelBuffer fragment : fragments) {
            n += fragment.readableBytes();
        }
        return n;
    }

    private byte[] messageBytes() {
        byte[] result = new byte[messageLength()];
        int offset = 0;
        for (ChannelBuffer fragment : fragments) {
            byte[] array = fragment.array();
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private String messageString() throws UnsupportedEncodingException {
        return new String(messageBytes(), "UTF-8");
    }

    private ChannelBuffer createBuffer(int length) {
        return ChannelBuffers.buffer(length);
    }

    public void dispatch(WebSocketHandler handler, NettyWebSocketConnection connection) throws Throwable {
        switch (opcode) {
            case Opcodes.OPCODE_TEXT:
                handler.onMessage(connection, messageString());
                return;
            case Opcodes.OPCODE_BINARY:
                handler.onMessage(connection, messageBytes());
                return;
            case Opcodes.OPCODE_PONG:
                handler.onPong(connection, messageString());
                return;
            default:
                throw new IllegalStateException("Unexpected opcode:" + opcode);
        }
    }
}