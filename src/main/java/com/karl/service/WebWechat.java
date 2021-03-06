package com.karl.service;

import java.util.regex.Matcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import blade.kit.DateKit;
import blade.kit.StringKit;
import blade.kit.http.HttpRequest;
import blade.kit.json.JSON;
import blade.kit.json.JSONArray;
import blade.kit.json.JSONObject;

import com.karl.domain.RuntimeDomain;
import com.karl.utils.AppUtils;
import com.karl.utils.CookieUtil;
import com.karl.utils.Matchers;

@Service
public class WebWechat implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebWechat.class);

    private RuntimeDomain runtimeDomain;

    private QRCodeFrame qrCodeFrame;

    private Thread runThread;

    private volatile boolean stopRequested;

    @Override
    public void run() {
        runThread = Thread.currentThread();
        stopRequested = false;
        System.setProperty("jsse.enableSNIExtension", "false");
    }

    @Autowired
    public WebWechat(RuntimeDomain runtimeDomain) throws InterruptedException {
        System.setProperty("jsse.enableSNIExtension", "false");
        this.runtimeDomain = runtimeDomain;
        // Thread thread = new Thread(this);
        // thread.start();
    }

    /**
     * 获取UUID
     * 
     * @return
     */
    public String getUUID() {
        String url = "https://login.weixin.qq.com/jslogin";
        HttpRequest request = HttpRequest.get(url, true, "appid", "wx782c26e4c19acffb", "fun",
                "new", "lang", "zh_CN", "_", DateKit.getCurrentUnixTime());

        LOGGER.info("[*] " + request);

        String res = request.body();
        request.disconnect();

        if (StringKit.isNotBlank(res)) {
            String code = Matchers.match("window.QRLogin.code = (\\d+);", res);
            if (null != code) {
                if (code.equals("200")) {
                    runtimeDomain.setUuid(Matchers.match("window.QRLogin.uuid = \"(.*)\";", res));
                    return runtimeDomain.getUuid();
                } else {
                    LOGGER.info("[*] 错误的状态码: %s", code);
                }
            }
        }
        return null;
    }

    /**
     * 显示二维码
     * 
     * @return
     */
    public void showQrCode() {

        String url = "https://login.weixin.qq.com/qrcode/" + runtimeDomain.getUuid();

        HttpRequest.post(url, true, "t", "webwx", "_", DateKit.getCurrentUnixTime()).receive(
                runtimeDomain.getQrCodeFile());

        // if (null != output && output.exists() && output.isFile()) {
        // try {
        // //
        // UIManager.setLookAndFeel("com.sun.java.swing.plaf.nimbus.NimbusLookAndFeel");
        // // qrCodeFrame = new QRCodeFrame(output.getPath());
        //
        // } catch (Exception e) {
        // LOGGER.error("failed:", e);
        // }
        // }
    }

    /**
     * 等待登录
     */
    public String waitForLogin() {
        runtimeDomain.setTip(1);
        String url = "https://login.weixin.qq.com/cgi-bin/mmwebwx-bin/login";
        HttpRequest request = HttpRequest.get(url, true, "tip", runtimeDomain.getTip(), "uuid",
                runtimeDomain.getUuid(), "_", DateKit.getCurrentUnixTime());

        LOGGER.info("[*] " + request.toString());

        String res = request.body();
        request.disconnect();

        if (null == res) {
            LOGGER.info("[*] 扫描二维码验证失败");
            return "";
        }

        String code = Matchers.match("window.code=(\\d+);", res);
        if (null == code) {
            LOGGER.info("[*] 扫描二维码验证失败");
            return "";
        } else {
            if (code.equals("201")) {
                LOGGER.info("[*] 成功扫描,请在手机上点击确认以登录");
                runtimeDomain.setTip(0);
            } else if (code.equals("200")) {
                LOGGER.info("[*] 正在登录...");
                String pm = Matchers.match("window.redirect_uri=\"(\\S+?)\";", res);
                AppUtils.redirect_uri = pm + "&fun=new";
                LOGGER.info("[*] redirect_uri=%s", AppUtils.redirect_uri);
                AppUtils.base_uri = AppUtils.redirect_uri.substring(0,
                        AppUtils.redirect_uri.lastIndexOf("/"));
                LOGGER.info("[*] base_uri=%s", AppUtils.base_uri);
            } else if (code.equals("408")) {
                LOGGER.info("[*] 登录超时");
            } else {
                LOGGER.info("[*] 扫描code=%s", code);
            }
        }
        return code;
    }

    private void closeQrWindow() {
        qrCodeFrame.dispose();
    }

    /**
     * 登录
     */
    public boolean login() {

        HttpRequest request = HttpRequest.get(AppUtils.redirect_uri);

        LOGGER.info("[*] " + request);

        String res = request.body();
        runtimeDomain.setCookie(CookieUtil.getCookie(request));

        request.disconnect();

        if (StringKit.isBlank(res)) {
            return false;
        }

        runtimeDomain.setSkey(Matchers.match("<skey>(\\S+)</skey>", res));
        runtimeDomain.setWxsid(Matchers.match("<wxsid>(\\S+)</wxsid>", res));
        runtimeDomain.setWxuin(Matchers.match("<wxuin>(\\S+)</wxuin>", res));
        runtimeDomain.setPassTicket(Matchers.match("<pass_ticket>(\\S+)</pass_ticket>", res));

        LOGGER.info("[*] skey[%s]", runtimeDomain.getSkey());
        LOGGER.info("[*] wxsid[%s]", runtimeDomain.getWxsid());
        LOGGER.info("[*] wxuin[%s]", runtimeDomain.getWxuin());
        LOGGER.info("[*] pass_ticket[%s]", runtimeDomain.getPassTicket());

        runtimeDomain.setBaseRequest(new JSONObject());
        runtimeDomain.getBaseRequest().put("Uin", runtimeDomain.getWxuin());
        runtimeDomain.getBaseRequest().put("Sid", runtimeDomain.getWxsid());
        runtimeDomain.getBaseRequest().put("Skey", runtimeDomain.getSkey());
        runtimeDomain.getBaseRequest().put("DeviceID", runtimeDomain.getDeviceId());

        return true;
    }

    /**
     * 微信初始化
     */
    public boolean wxInit() {

        String url = AppUtils.base_uri + "/webwxinit?r=" + DateKit.getCurrentUnixTime()
                + "&pass_ticket=" + runtimeDomain.getPassTicket() + "&skey="
                + runtimeDomain.getSkey();

        JSONObject body = new JSONObject();
        body.put("BaseRequest", this.runtimeDomain.getBaseRequest());

        HttpRequest request = HttpRequest.post(url)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cookie", runtimeDomain.getCookie()).send(body.toString());

        LOGGER.debug("[*] " + request);
        String res = request.body();
        request.disconnect();

        if (StringKit.isBlank(res)) {
            return false;
        }

        try {
            JSONObject jsonObject = JSON.parse(res).asObject();
            if (null != jsonObject) {
                JSONObject BaseResponse = jsonObject.getJSONObject("BaseResponse");

                if (null != BaseResponse) {
                    int ret = BaseResponse.getInt("Ret", -1);
                    if (ret == 0) {
                        runtimeDomain.setSyncKeyJNode(jsonObject.getJSONObject("SyncKey"));
                        runtimeDomain.setUser(jsonObject.getJSONObject("User"));

                        StringBuffer synckey = new StringBuffer();

                        JSONArray list = runtimeDomain.getSyncKeyJNode().getJSONArray("List");
                        for (int i = 0, len = list.size(); i < len; i++) {
                            JSONObject item = list.getJSONObject(i);
                            synckey.append("|" + item.getInt("Key", 0) + "_"
                                    + item.getInt("Val", 0));
                        }

                        runtimeDomain.setSynckey(synckey.substring(1));
                        if (!assemableContactors(jsonObject.getJSONArray("ContactList"))) {
                            return false;
                        }
                        return true;
                    }
                }

            }
        } catch (Exception e) {
            LOGGER.error("wechat initial failed!", e);
        }
        return false;
    }

    /**
     * 微信状态通知
     */
    public boolean wxStatusNotify() {

        String url = AppUtils.base_uri + "/webwxstatusnotify?lang=zh_CN&pass_ticket="
                + runtimeDomain.getPassTicket();

        JSONObject body = new JSONObject();
        body.put("BaseRequest", runtimeDomain.getBaseRequest());
        body.put("Code", 3);
        body.put("FromUserName", runtimeDomain.getUser().getString("UserName"));
        body.put("ToUserName", runtimeDomain.getUser().getString("UserName"));
        body.put("ClientMsgId", DateKit.getCurrentUnixTime());

        HttpRequest request = HttpRequest.post(url)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cookie", runtimeDomain.getCookie()).send(body.toString());

        String res = request.body();
        request.disconnect();

        if (StringKit.isBlank(res)) {
            return false;
        }

        try {
            JSONObject jsonObject = JSON.parse(res).asObject();
            JSONObject BaseResponse = jsonObject.getJSONObject("BaseResponse");
            if (null != BaseResponse) {
                int ret = BaseResponse.getInt("Ret", -1);
                return ret == 0;
            }
        } catch (Exception e) {
            LOGGER.error("wxStatusNotify failed due to:", e);
        }
        return false;
    }

    /**
     * 获取联系人
     */
    public boolean getContact() {

        String url = AppUtils.base_uri + "/webwxgetcontact?pass_ticket="
                + runtimeDomain.getPassTicket() + "&skey=" + runtimeDomain.getSkey() + "&r="
                + DateKit.getCurrentUnixTime();

        JSONObject body = new JSONObject();
        body.put("BaseRequest", runtimeDomain.getBaseRequest());

        HttpRequest request = HttpRequest.post(url)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cookie", runtimeDomain.getCookie()).send(body.toString());

        String res = request.body();
        request.disconnect();

        if (StringKit.isBlank(res)) {
            return false;
        }

        try {
            JSONObject jsonObject = JSON.parse(res).asObject();
            JSONObject BaseResponse = jsonObject.getJSONObject("BaseResponse");
            if (null != BaseResponse) {
                int ret = BaseResponse.getInt("Ret", -1);
                if (ret == 0) {
                    JSONArray memberList = jsonObject.getJSONArray("MemberList");
                    if (null != memberList) {
                        assemableContactors(memberList);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("fetching contactors failed due to:", e);
        }
        return false;
    }

    private boolean assemableContactors(JSONArray contactList) {
        JSONObject contact = null;
        for (int i = 0, len = contactList.size(); i < len; i++) {
            contact = contactList.getJSONObject(i);
            // 所有用戶
            runtimeDomain.putAllUsrMap(contact.getString("UserName"), contact);
            // 公众号/服务号
            if (contact.getInt("VerifyFlag", 0) == 8) {
                runtimeDomain.putPublicUsrMap(contact.getString("UserName"), contact);
                continue;
            }
            // 特殊联系人
            if (AppUtils.specialUsers.contains(contact.getString("UserName"))) {
                runtimeDomain.putSpecialUsrMap(contact.getString("UserName"), contact);
                continue;
            }
            // 群
            if (contact.getString("UserName").indexOf("@@") != -1) {
                runtimeDomain.putGroupMap(contact.getString("UserName"), contact);
                continue;
            }
            // 自己
            if (contact.getString("UserName").equals(runtimeDomain.getUser().getString("UserName"))) {
                continue;
            }
        }
        runtimeDomain.putAllUsrMap(runtimeDomain.getUser().getString("UserName"),
                runtimeDomain.getUser());

        return true;
    }

    /**
     * 获取群组联系人
     */
    public boolean getGroupMembers() {

        String url = AppUtils.base_uri + "/webwxbatchgetcontact?type=ex&pass_ticket="
                + runtimeDomain.getPassTicket() + "&r=" + DateKit.getCurrentUnixTime();

        if (runtimeDomain.getGroupMap().size() < 1) {
            LOGGER.warn("No Group found during contactor sync");
            return true;
        }

        JSONObject body = new JSONObject();
        body.put("BaseRequest", runtimeDomain.getBaseRequest());
        body.put("Count", runtimeDomain.getGroupMap().size());
        String jsonStr = "[";
        String[] keyStr = (String[]) runtimeDomain.getGroupMap().keySet().toArray();
        for (int i = 0; i < keyStr.length; i++) {
            jsonStr += "{ UserName: " + keyStr[i] + ", EncryChatRoomId: \"\" }";
            jsonStr += i == keyStr.length - 1 ? "" : ",";
        }
        jsonStr += "]";
        body.put("List", jsonStr);

        HttpRequest request = HttpRequest.post(url)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cookie", runtimeDomain.getCookie()).send(body.toString());

        LOGGER.info("[*] " + request);
        String res = request.body();
        request.disconnect();

        if (StringKit.isBlank(res)) {
            return false;
        }

        LOGGER.debug("[webwxbatchgetcontact response]: " + res);

        try {
            JSONObject jsonObject = JSON.parse(res).asObject();
            JSONObject BaseResponse = jsonObject.getJSONObject("BaseResponse");
            if (null != BaseResponse) {
                int ret = BaseResponse.getInt("Ret", -1);
                if (ret == 0) {
                    JSONArray groupCollection = jsonObject.getJSONArray("ContactList");
                    if (groupCollection == null) {
                        return false;
                    }
                    JSONArray memberList = null;
                    for (int x = 0, xlen = groupCollection.size(); x < xlen; x++) {
                        memberList = groupCollection.getJSONObject(x).getJSONArray("MemberList");
                        if (null != memberList) {
                            JSONObject contact = null;
                            for (int i = 0, len = memberList.size(); i < len; i++) {
                                contact = memberList.getJSONObject(i);
                                // 所有用戶
                                runtimeDomain.putAllUsrMap(contact.getString("UserName"), contact);
                                // 群组成员
                                runtimeDomain
                                        .putGroupUsrMap(contact.getString("UserName"), contact);
                            }
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("fetching contactors failed due to:", e);
        }
        return false;
    }

    /**
     * 消息检查
     */
    public int[] syncCheck() {

        int[] arr = new int[2];

        String url = AppUtils.webpush_url;

        JSONObject body = new JSONObject();
        body.put("BaseRequest", runtimeDomain.getBaseRequest());

        HttpRequest request = HttpRequest.get(url, true, "r", DateKit.getCurrentUnixTime(), "skey",
                runtimeDomain.getSkey(), "uin", runtimeDomain.getWxuin(), "sid",
                runtimeDomain.getWxsid(), "deviceid", runtimeDomain.getDeviceId(), "SyncKey",
                runtimeDomain.getSyncKeyJNode(), "_", System.currentTimeMillis()).header("Cookie",
                runtimeDomain.getCookie());

        // LOGGER.debug("[syncCheck request ] " + request);
        String res = request.body();
        request.disconnect();

        if (StringKit.isBlank(res)) {
            return arr;
        }
        // LOGGER.debug("[syncCheck response ] " + res);

        String retcode = Matchers.match("retcode:\"(\\d+)\",", res);
        String selector = Matchers.match("selector:\"(\\d+)\"}", res);
        if (null != retcode && null != selector) {
            arr[0] = Integer.parseInt(retcode);
            arr[1] = Integer.parseInt(selector);
            return arr;
        }
        return arr;
    }

    /**
     * sent the latest luck info to specific group
     */
    public void webwxsendLuckInfo() {
        String content = "公布结果如下：\n";
        content += "========\n";
        for (String name : runtimeDomain.getLatestLuckInfo().keySet()) {
            content += name + ":    " + runtimeDomain.getLatestLuckInfo().get(name).toString()
                    + "\n";
        }
        content += "========\n";

        webwxsendmsg(content);
        runtimeDomain.clearLatestLuckInfo();
    }

    /**
     * sent the latest Bet info to specific group
     */
    public void webwxsendBetInfo() {
        String content = "Bet result：\n";
        content += "======\n";
        for (String name : runtimeDomain.getLatestBetInfo().keySet()) {
            content += name + " Bet " + runtimeDomain.getLatestBetInfo().get(name).doubleValue()
                    + "\n";
        }
        content += "======\n";

        webwxsendmsg(content);
        runtimeDomain.clearLatestBetInfo();
    }

    /**
     * Sent message to specific group
     * 
     * @param content
     */
    public void webwxsendmsg(String content) {
        webwxsendmsg(content, runtimeDomain.getCurrentGroupId());
    }

    /**
     * Sent message
     * 
     * @param content
     * @param to
     *            : UserName
     */
    public void webwxsendmsg(String content, String to) {

        String url = AppUtils.base_uri + "/webwxsendmsg?lang=zh_CN&pass_ticket="
                + runtimeDomain.getPassTicket();

        JSONObject body = new JSONObject();

        String clientMsgId = DateKit.getCurrentUnixTime() + StringKit.getRandomNumber(5);
        JSONObject Msg = new JSONObject();
        Msg.put("Type", 1);
        Msg.put("Content", content);
        Msg.put("FromUserName", runtimeDomain.getUser().getString("UserName"));
        Msg.put("ToUserName", to);
        Msg.put("LocalID", clientMsgId);
        Msg.put("ClientMsgId", clientMsgId);
        body.put("BaseRequest", this.runtimeDomain.getBaseRequest());
        body.put("Msg", Msg);

        HttpRequest request = HttpRequest.post(url)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cookie", runtimeDomain.getCookie()).send(body.toString());

        LOGGER.debug("Message sent to runtimeDomain.getUser()[{}] reuest {}", to, request);

        LOGGER.debug("Message sent to runtimeDomain.getUser()[{}] response {}", to, request.body());

        request.disconnect();

    }

    /**
     * 获取最新消息
     */
    public JSONObject webwxsync() {

        String url = AppUtils.base_uri + "/webwxsync?lang=zh_CN&pass_ticket="
                + runtimeDomain.getPassTicket() + "&skey=" + runtimeDomain.getSkey() + "&sid="
                + runtimeDomain.getWxsid() + "&r=" + DateKit.getCurrentUnixTime();

        JSONObject body = new JSONObject();
        body.put("BaseRequest", runtimeDomain.getBaseRequest());
        body.put("SyncKey", runtimeDomain.getSyncKeyJNode());
        body.put("rr", ~DateKit.getCurrentUnixTime());

        HttpRequest request = HttpRequest.post(url)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Cookie", runtimeDomain.getCookie()).send(body.toString());

        // LOGGER.debug("[webwxsync request:] " + request);
        String res = request.body();
        request.disconnect();
        // LOGGER.debug("[webwxsync response:]" + res);
        if (StringKit.isBlank(res)) {
            return null;
        }

        JSONObject jsonObject = JSON.parse(res).asObject();
        JSONObject BaseResponse = jsonObject.getJSONObject("BaseResponse");
        if (null != BaseResponse) {
            int ret = BaseResponse.getInt("Ret", -1);
            if (ret == 0) {
                runtimeDomain.setSyncKeyJNode(jsonObject.getJSONObject("SyncKey"));

                StringBuffer synckey = new StringBuffer();
                JSONArray list = runtimeDomain.getSyncKeyJNode().getJSONArray("List");
                for (int i = 0, len = list.size(); i < len; i++) {
                    JSONObject item = list.getJSONObject(i);
                    synckey.append("|" + item.getInt("Key", 0) + "_" + item.getInt("Val", 0));
                }
                runtimeDomain.setSynckey(synckey.substring(1));
            }
        }
        return jsonObject;
    }

    /**
     * 获取最新消息
     */
    public void handleMsg(JSONObject data) {
        if (null == data) {
            return;
        }

        JSONArray addMsgList = data.getJSONArray("AddMsgList");

        for (int i = 0, len = addMsgList.size(); i < len; i++) {
            JSONObject msg = addMsgList.getJSONObject(i);
            int msgType = msg.getInt("MsgType", 0);

            switch (msgType) {
            case 51:
                break;
            case 1:
                handleTextMsg(msg);
                break;
            case 3:
                // webwxsendmsg("二蛋还不支持图片呢", msg.getString("FromUserName"));
                break;
            case 34:
                // webwxsendmsg("二蛋还不支持语音呢", msg.getString("FromUserName"));
                break;
            case 42:
                break;
            default:
                break;
            }
            LOGGER.debug("Message Detail： {}" + msg.toString());
        }
        LOGGER.debug("Message Package： {}", data.toString());
    }

    private String getUserRemarkName(String id) {
        String name = "这个人物名字未知";
        JSONObject member = runtimeDomain.getAllUsrMap().get(id);
        if (member != null && member.getString("UserName").equals(id)) {
            if (StringKit.isNotBlank(member.getString("RemarkName"))) {
                name = member.getString("RemarkName");
            } else {
                name = member.getString("NickName");
            }
        }
        return name;
    }

    /**
     * handle text message
     * 
     * @param jsonMsg
     */
    private void handleTextMsg(JSONObject jsonMsg) {

        String remarkName = "";
        String content = "";

        if (jsonMsg.getString("FromUserName").equals(runtimeDomain.getCurrentGroupId())) {
            LOGGER.debug("FromUserName{} message", jsonMsg.getString("FromUserName"));

            String contentStr = jsonMsg.getString("Content");
            if (contentStr != null && !contentStr.isEmpty()) {
                String[] contentArray = contentStr.split(":<br/>");
                if (contentArray.length > 1) {
                    content = contentArray[1];
                    String fromUsrId = contentArray[0];
                    remarkName = getUserRemarkName(fromUsrId);
                } else {
                    LOGGER.warn("FromUserName{} message's message can't be interpret! {}",
                            jsonMsg.getString("FromUserName"), contentStr);

                }
            } else {
                LOGGER.warn("FromUserName{} message's content is empty!",
                        jsonMsg.getString("FromUserName"));

            }
        } else {
            LOGGER.warn("FromUserName[{}] message is not come from specific group[{}]!",
                    jsonMsg.getString("FromUserName"), runtimeDomain.getCurrentGroupId());
        }

        if (runtimeDomain.getUser().getString("UserName").equals(jsonMsg.getString("FromUserName"))) {
            remarkName = runtimeDomain.getUser().getString("NickName");
            content = jsonMsg.getString("Content");

        }

        Matcher matcher = Matchers.DOUBLE.matcher(content);
        if (matcher.find()) {
            runtimeDomain.getLatestBetInfo().put(remarkName, Double.valueOf(matcher.group(0)));
        } else {
            LOGGER.warn("FromUserName{} message's message content {}",
                    jsonMsg.getString("FromUserName"), content);
        }

        switch (content) {
        case "bet":
            this.webwxsendBetInfo();
            break;
        case "luck":
            this.webwxsendLuckInfo();
            break;

        default:
            break;
        }

        LOGGER.debug("【" + remarkName + "】说： 【" + content + "】");
    }

    public void listenMsgMode() {
        new Thread(new Runnable() {

            public void run() {
                LOGGER.info("[*] 进入消息监听模式 ...");
                long sleepTime = 1000;
                while (!stopRequested) {

                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        LOGGER.error("sleeping failed:", e);
                    }

                    int[] arr = syncCheck();

                    LOGGER.info("[*] retcode={},selector={}", arr[0], arr[1]);

                    if (arr[0] == 1100) {
                        LOGGER.info("[*] 你在手机上登出了微信，债见");
                        break;
                    }

                    if (arr[0] == 0) {
                        JSONObject data = null;

                        switch (arr[1]) {
                        case 2:// 新的消息
                            data = webwxsync();
                            handleMsg(data);
                            sleepTime = 1000;
                            break;
                        case 6:// 红包
                            data = webwxsync();
                            handleMsg(data);
                            sleepTime = 1000;
                            break;
                        case 7:// 进入/离开聊天界面
                            data = webwxsync();
                            sleepTime = 1000;
                            break;
                        default:
                            sleepTime = 2000;
                            break;
                        }
                    }
                }
            }
        }, "listenMsgMode").start();
    }

    public void loginWechat() throws InterruptedException {
        String uuid = getUUID();
        if (null == uuid || uuid.isEmpty()) {
            LOGGER.info("[*] uuid获取失败");
        } else {
            LOGGER.info("[*] 获取到uuid为 [{}]", runtimeDomain.getUuid());
            showQrCode();
            while (!"200".equals(waitForLogin())) {
                Thread.sleep(AppUtils.LOGIN_WAITING_TIME);
            }
            // closeQrWindow();

            if (!login()) {
                LOGGER.info("微信登录失败");
                return;
            }
            LOGGER.info("[*] 微信登录成功");
        }
    }

    public void buildWechat() throws InterruptedException {

        if (!login()) {
            LOGGER.info("微信登录失败");
            return;
        }

        LOGGER.info("[*] 微信登录成功");

        if (!wxInit()) {
            LOGGER.info("[*] 微信初始化失败");
            return;
        }

        LOGGER.info("[*] 微信初始化成功");

        if (!wxStatusNotify()) {
            LOGGER.info("[*] 开启状态通知失败");
            return;
        }

        LOGGER.info("[*] 开启状态通知成功");

        if (!getContact()) {
            LOGGER.info("[*] 获取联系人失败");
            return;
        }
        // if (!app.getGroupMembers()) {
        // LOGGER.info("[*] 获取群成员失败");
        // return;
        // }

        LOGGER.info("[*] 获取联系人成功");
        LOGGER.info("[*] 共有 {} 位联系人", runtimeDomain.getAllUsrMap().size());
        LOGGER.info("[*] 共有 {} 位群聊联系人", runtimeDomain.getGroupUsrMap().size());
        LOGGER.info("[*] 共有 {} 位特殊联系人", runtimeDomain.getSpecialUsrMap().size());
        LOGGER.info("[*] 共有 {} 个群", runtimeDomain.getGroupMap().size());

        // 监听消息
        listenMsgMode();

    }

    /**
     * Interpret LUCKAGEPACAKGE that received from socket connection
     * 
     * @param packageInfo
     *            JSON string
     */
    public void interpretPackage(String packageInfo) {
        try {
            JSONObject jsonObject = JSON.parse(packageInfo).asObject();
            JSONArray jsonLuckPeople = jsonObject.getJSONArray("LuckPeople");
            if (jsonLuckPeople == null || jsonLuckPeople.size() < 1) {
                LOGGER.warn("Luck package is empty! {}", packageInfo);
                return;
            }

            JSONObject jsonLuckOne = null;
            for (int i = 0; i < jsonLuckPeople.size(); i++) {
                jsonLuckOne = jsonLuckPeople.getJSONObject(i);

                Matcher matcher = Matchers.DOUBLE.matcher(jsonLuckOne.getString("Money"));
                if (matcher.find()) {

                    this.runtimeDomain.getLatestBetInfo().put(jsonLuckOne.getString("RemarkName"),
                            Double.valueOf(matcher.group(0)));
                } else {
                    LOGGER.warn("Luck message RemarkUser {} Money{} interpret failed!",
                            jsonLuckOne.getString("RemarkName"), jsonLuckOne.getString("Money"));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Luck package[{}] interpret failed!", packageInfo, e);
        }
    }

    public void stopListen() {
        stopRequested = true;
        if (runThread != null) {
            runThread.interrupt();
        }
    }

    public RuntimeDomain getRuntimeDomain() {
        return runtimeDomain;
    }

    public void setRuntimeDomain(RuntimeDomain runtimeDomain) {
        this.runtimeDomain = runtimeDomain;
    }
}