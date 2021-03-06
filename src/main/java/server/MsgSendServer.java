package src.main.java.server;

import com.rabbitmq.client.*;
import org.json.JSONException;
import src.main.java.DataSource.DataSource;
import src.main.java.MessageUtils.Message;
import src.main.java.MessageUtils.MessageDeparturer;
import src.main.java.PackerUtils.PackerTimer;
import utils.Pair;
import wheellllll.config.Config;
import wheellllll.performance.IntervalLogger;
import wheellllll.performance.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.lang.Thread.sleep;

/**
 * Created by Siyao on 16/5/27.
 */
public class MsgSendServer extends ServerSocket {

    private boolean withLog;
    private String logDir;
    private String zipDir;

    private static DataSource dataSource;

    private IntervalLogger pm;
    private String forwarded_msg = "forwardedMessage";

    private PackerTimer pmPacker;
    private PackerTimer pmPackerWeek;

    private HashMap<String, Socket> user2socket = new HashMap<>();
    private HashMap<String, MessageDeparturer> user2depart = new HashMap<>();

    //用户群租分发
    private HashMap<String,HashMap<String,Message>> allMsg;

    Consumer logoutConsumer;
    Consumer reloginConsumer;
    Consumer loginFailConsumer;
    Consumer loginSuccessConsumer;

    public MsgSendServer(int SERVER_PORT, String logDirname, String zipDirname, String dbUser, String dbPwd, boolean withLog)
            throws IOException, TimeoutException, JSONException {

        super(SERVER_PORT);

        this.withLog = withLog;
        this.logDir = logDirname;
        this.zipDir = zipDirname;

        dataSource = new DataSource(dbUser, dbPwd);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        Channel logoutChannel = connection.createChannel();
        logoutChannel.queueDeclare("logout_msg", true, false, false, null);
        logoutConsumer = new DefaultConsumer(logoutChannel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                    throws IOException {
                String getMsg = new String(body, "UTF-8");
                try {
                    Message logoutMsg = new Message("{}", "");
                    logoutMsg.reset(getMsg);
                    String getUsername = logoutMsg.getValue("username");

                    MessageDeparturer messageLoginDeparturer = user2depart.get(getUsername);
                    messageLoginDeparturer.cancelConsumer();

                    user2socket.remove(getUsername);
                    user2depart.remove(getUsername);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };
        logoutChannel.basicConsume("logout_msg", true, logoutConsumer);

        Channel reloginChannel = connection.createChannel();
        reloginChannel.queueDeclare("relogin_msg", true, false, false, null);
        reloginConsumer = new DefaultConsumer(reloginChannel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                    throws IOException {
                String sReloginMsg = new String(body, "UTF-8");
                try {
                    Message reloginMsg = new Message("{}", "");
                    reloginMsg.reset(sReloginMsg);
                    String reloginUsername = reloginMsg.getValue("username");
                    Socket relogClient = user2socket.get(reloginUsername);
                    PrintWriter reloginOut = new PrintWriter(relogClient.getOutputStream(),true);
                    reloginMsg.setValue("event","relogin");
                    reloginOut.println(reloginMsg);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };
        reloginChannel.basicConsume("relogin_msg", true, reloginConsumer);

        Channel loginFailChannel = connection.createChannel();
        loginFailChannel.queueDeclare("login_fail_msg", true, false, false, null);
        loginFailConsumer = new DefaultConsumer(loginFailChannel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                    throws IOException {
                String sLoginFailMsg = new String(body, "UTF-8");
                try {
                    Message loginFailMsg = new Message("{}", "");
                    loginFailMsg.reset(sLoginFailMsg);
                    String loginFailUsername = loginFailMsg.getValue("username");
                    Socket loginFailClient = user2socket.get(loginFailUsername);
                    PrintWriter loginFailOut = new PrintWriter(loginFailClient.getOutputStream(),true);
                    loginFailMsg.setValue("event","invalid");
                    loginFailOut.println(loginFailMsg);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };
        loginFailChannel.basicConsume("login_fail_msg", true, loginFailConsumer);

        Channel loginSuccessChannel = connection.createChannel();
        loginSuccessChannel.queueDeclare("login_success_msg", true, false, false, null);
        loginSuccessConsumer = new DefaultConsumer(loginSuccessChannel) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
                    throws IOException {
                String sLoginSuccessMsg = new String(body, "UTF-8");
                try {
                    Message loginSuccessMsg = new Message("{}", "");
                    loginSuccessMsg.reset(sLoginSuccessMsg);
                    String loginSuccessUsername = loginSuccessMsg.getValue("username");
                    Socket loginSuccessClient = user2socket.get(loginSuccessUsername);
                    PrintWriter loginSuccessOut = new PrintWriter(loginSuccessClient.getOutputStream(),true);
                    loginSuccessMsg.setValue("event","valid");
                    System.out.println(loginSuccessMsg.toString());
                    loginSuccessOut.println(loginSuccessMsg);

                    MessageDeparturer messageLoginDeparturer = user2depart.get(loginSuccessUsername);
                    messageLoginDeparturer.beginConsumer();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };
        loginSuccessChannel.basicConsume("login_success_msg", true, loginSuccessConsumer);

        Message msg;
        HashMap<String,Message> user2msg;
        allMsg = new HashMap<String,HashMap<String,Message>>();
        ArrayList<Pair<String,String>> res = dataSource.getGroupUser();
        for (Pair<String,String> p : res) {
            msg = new Message("{}", p.getR());
            msg.init(p.getR(), "localhost");
            msg.bindTo(p.getL(), p.getR());
            if (allMsg.containsKey(p.getL())) {
                allMsg.get(p.getL()).put(p.getR(), msg);
            }
            else {
                user2msg = new HashMap<String, Message>();
                user2msg.put(p.getR(), msg);
                allMsg.put(p.getL(), user2msg);
            }
        }
    }


    public void run() throws IOException {
        if (withLog) {
            //转发消息计数
            pm = new IntervalLogger();
            pm.setMaxFileSize(500, Logger.SizeUnit.KB);
            pm.setMaxTotalSize(200, Logger.SizeUnit.MB);
            pm.setLogDir(this.logDir + "/pm/msgSend");
            pm.setLogPrefix("Server");
            pm.setInterval(1, TimeUnit.MINUTES);
            pm.addIndex(forwarded_msg);
            pm.start();

            //转发消息计数打包
            pmPacker = new PackerTimer(this.logDir + "/pm/msgSend", this.zipDir + "/pm/day/msgSend");
            pmPacker.setInterval(1,TimeUnit.DAYS);
            pmPacker.setDelay(1,TimeUnit.DAYS);
            pmPacker.setPackDateFormat("yyyy-MM-dd");
            pmPacker.setbEncryptIt(true);
            pmPacker.start();

            //转发消息计数周信息归档
            pmPackerWeek = new PackerTimer(this.zipDir + "/pm/day/msgSend", this.zipDir + "/pm/week/msgSend");
            pmPackerWeek.setInterval(7,TimeUnit.DAYS);
            pmPackerWeek.setDelay(7,TimeUnit.DAYS);
            pmPackerWeek.setPackDateFormat("yyyy-MM-dd");
            pmPackerWeek.setbUnpack(true);
            pmPackerWeek.setbEncryptIt(true);
            pmPackerWeek.start();

        }
        try {
            while(true){
                Socket client = accept();

                //发送登录消息
                PrintWriter out = new PrintWriter(client.getOutputStream(),true);
                Message msg = new Message("{}", "");
                msg.setValue("event", "login");
                out.println(msg);

                //获取用户名
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));

                String inLine = null;
                while(inLine == null){
                    inLine = in.readLine();
                }
                Message recMsg = new Message("{}", "");
                recMsg.reset(inLine);
                String username = recMsg.getValue("username");

                user2socket.put(username, client);

                //消息发送
                MessageDeparturer messageDeparturer;

                Message sendMsg = new Message("{}", "");
                String queueName = username;
                String exchangeName = "";
                for (String key : allMsg.keySet()) {
                    if (allMsg.get(key).containsKey(username)) {
                        exchangeName = key;
                        sendMsg = allMsg.get(key).get(username);
                    }
                }

                messageDeparturer = new MessageDeparturer(sendMsg, out, pm, forwarded_msg);
                user2depart.put(username, messageDeparturer);
            }
        }catch (Exception e) {
        }finally{
            close();
        }
    }





    public static void main(String[] args) throws IOException, TimeoutException, JSONException {
        Config.setConfigName("./application.conf");
        String host = Config.getConfig().getString("SERVER_IP");
        int port = 9002;
        String logDirname = "./log/server";
        String zipDirname = "./archive/server";
        String dbUser = "root";
        String dbPwd = "Wsy_07130713";
        MsgSendServer msgSendServer = new MsgSendServer(port, logDirname, zipDirname, dbUser, dbPwd, true);
        msgSendServer.run();
    }
}
