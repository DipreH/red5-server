package org.red5.server.net.rtmp;

import static org.junit.Assert.*;

import org.apache.mina.core.buffer.IoBuffer;
import org.junit.Test;

public class TestRTMPUtils {

    @Test
    public void testWriteMediumInt() {
        IoBuffer out = IoBuffer.allocate(3);
        int value =  1 + 256 + 65536;
        RTMPUtils.writeMediumInt(out,value);
        for (int i = 0; i<3;i++){
            assertEquals(1, out.get(i));
        }
    }

    @Test
    public void testReadUnsignedMediumInt() {
        fail("Not yet implemented");
    }

    @Test
    public void testReadMediumInt() {
        fail("Not yet implemented");
    }

    @Test
    public void testCompareTimestamps() {
        fail("Not yet implemented");
    }

    @Test
    public void testDiffTimestamps() {
        fail("Not yet implemented");
    }

}
