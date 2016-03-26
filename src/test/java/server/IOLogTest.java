package server;

import org.junit.Before;
import org.junit.Test;
import utils.IOLog;

/**
 * Created by huangli on 3/23/16.
 */
public class IOLogTest {

    IOLog ioLog;

    @Before
    public void setUp() throws Exception {
        ioLog = new IOLog("test.log", true);
    }

    @Test
    public void IOWrite() throws Exception {
        ioLog.IOWrite("test.");
    }
}