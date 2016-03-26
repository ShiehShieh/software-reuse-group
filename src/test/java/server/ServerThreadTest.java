package server;

import org.junit.Before;
import org.junit.Test;
import java.io.*;

import static org.junit.Assert.*;

/**
 * Created by huangli on 3/23/16.
 */
public class ServerThreadTest {

    Server.ServerThread serverThread;
    DataSource dataSource;

    @Before
    public void setUp() throws Exception {
        serverThread = new Server.ServerThread();
        dataSource = new DataSource();
    }

    @Test
    public void login() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream("{'username':'shieh','password':'hi'}\n{'username':'shieh','password':'shieh'}".getBytes());
        System.setIn(in);
        serverThread.login(new BufferedReader(new InputStreamReader(in)), new PrintWriter(System.out,true), dataSource);
    }
}