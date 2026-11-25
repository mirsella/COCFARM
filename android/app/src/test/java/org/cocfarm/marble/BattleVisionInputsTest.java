package org.cocfarm.marble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public class BattleVisionInputsTest {
    @Test public void validatesRawScreenFormatAndExactLength() throws IOException {
        byte[] data = new byte[16 + 4 * 4 * 2];
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(4).putInt(2).putInt(1).putInt(1);
        assertEquals(new ScreenPixels(4, 2), ScreenPixels.readHeader(data));
        assertThrows(IOException.class, () -> ScreenPixels.readHeader(new byte[16]));
        header.putInt(4, 3);
        assertThrows(IOException.class, () -> ScreenPixels.readHeader(data));
        header.putInt(4, 2).putInt(8, 3);
        assertThrows(IOException.class, () -> ScreenPixels.readHeader(data));
    }

    @Test public void parsesOnlyIsolatedAndPlausibleResultNumbers() {
        assertEquals(Integer.valueOf(76_000), BattleScore.gold(" 76000\n"));
        assertEquals(Integer.valueOf(1_000_000), BattleScore.gold("1,000,000"));
        assertNull(BattleScore.gold("76 00O"));
        assertNull(BattleScore.gold("76000\n14"));
        assertEquals(Integer.valueOf(200), BattleScore.damage("200 %"));
        assertEquals(Integer.valueOf(0), BattleScore.damage("0%"));
        assertNull(BattleScore.damage("201%"));
        assertNull(BattleScore.damage("18"));
    }
}
