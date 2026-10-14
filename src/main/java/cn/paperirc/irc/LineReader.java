package cn.paperirc.irc;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** Incremental byte framing: partial reads and socket timeouts retain the same frame. */
public final class LineReader {
    private final InputStream in;
    private final byte[] bytes=new byte[8192];
    private int size;
    public LineReader(InputStream in) { this.in=in; }
    public String read() throws IOException {
        while(true) {
            int next=in.read();
            if(next<0) { if(size>0)throw new EOFException("IRC 半帧断开");return null; }
            if(next==10) {
                if(size==0||bytes[size-1]!=13)throw new IOException("IRC 行缺少 CRLF");
                // ngIRCd can retain a CR from a Windows MOTD file before appending CRLF.
                // Tolerate only redundant CRs at the frame boundary; embedded CRs remain invalid.
                int end=size-1;
                while(end>0&&bytes[end-1]==13)end--;
                try {
                    String line=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,end)).toString();
                    size=0;return line;
                } catch(CharacterCodingException e) { throw new IOException("IRC UTF-8 无效",e); }
            }
            if(size>=bytes.length)throw new IOException("IRC 入站帧超出 8192 字节安全上限");
            bytes[size++]=(byte)next;
        }
    }
}
