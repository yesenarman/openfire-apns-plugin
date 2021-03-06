package com.wecapslabs.openfire.plugin.apns;

import com.google.common.base.Strings;
import com.notnoop.apns.*;
import com.notnoop.apns.internal.Utilities;
import com.notnoop.exceptions.ApnsDeliveryErrorException;
import com.notnoop.exceptions.NetworkIOException;
import org.jivesoftware.openfire.IQRouter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.UserNameManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ApnsPlugin implements Plugin, PacketInterceptor, ApnsDelegate {

    private static final Logger Log = LoggerFactory.getLogger(ApnsPlugin.class);

    private final XMPPServer server;
    private final InterceptorManager interceptorManager;
    private final MultiUserChatManager mucManager;
    private final ApnsDBHandler dbHandler;
    private ApnsService apnsService;

    public ApnsPlugin() {
        server = XMPPServer.getInstance();
        interceptorManager = InterceptorManager.getInstance();
        mucManager = server.getMultiUserChatManager();
        dbHandler = new ApnsDBHandler();
    }

    @SuppressWarnings("unused")
    public void setCertificatePath(String certificatePath) {
        JiveGlobals.setProperty("plugin.apns.certificatePath", certificatePath);
    }

    public String getCertificatePath() {
        return JiveGlobals.getProperty("plugin.apns.certificatePath", "/certificate.p12");
    }

    @SuppressWarnings("unused")
    public void setPassword(String password) {
        JiveGlobals.setProperty("plugin.apns.password", password);
    }

    public String getPassword() {
        return JiveGlobals.getProperty("plugin.apns.password", "");
    }

    @SuppressWarnings("unused")
    public void setBadge(String badge) {
        JiveGlobals.setProperty("plugin.apns.badge", badge);
    }

    public int getBadge() {
        return Integer.parseInt(JiveGlobals.getProperty("plugin.apns.badge", "1"));
    }

    @SuppressWarnings("unused")
    public void setSound(String sound) {
        JiveGlobals.setProperty("plugin.apns.sound", sound);
    }

    public String getSound() {
        return JiveGlobals.getProperty("plugin.apns.sound", "default");
    }

    @SuppressWarnings("unused")
    public void setProduction(String production) {
        JiveGlobals.setProperty("plugin.apns.production", production);
    }

    public boolean getProduction() {
        return Boolean.parseBoolean(JiveGlobals.getProperty("plugin.apns.production", "false"));
    }

    @SuppressWarnings("unused")
    public void setSendMessageBody(String sendMessageBody) {
        JiveGlobals.setProperty("plugin.apns.sendMessageBody", sendMessageBody);
    }

    public boolean getSendMessageBody() {
        return Boolean.parseBoolean(JiveGlobals.getProperty("plugin.apns.sendMessageBody", "false"));
    }

    @SuppressWarnings("unused")
    public void setMessageBodyPlaceholder(String messageBodyPlaceholder) {
        JiveGlobals.setProperty("plugin.apns.messageBodyPlaceholder", messageBodyPlaceholder);
    }

    public String getMessageBodyPlaceholder() {
        return JiveGlobals.getProperty("plugin.apns.messageBodyPlaceholder", "Sent message.");
    }

    private ApnsService getApnsService() {
        if (apnsService == null) {
            try {
                apnsService = APNS.newService()
                        .withCert(getCertificatePath(), getPassword())
                        .withAppleDestination(getProduction())
                        .withDelegate(this)
                        .build();
            } catch (Exception e) {
                Log.error(e.getMessage(), e);
            }
        }
        return apnsService;
    }

    public void initializePlugin(PluginManager pManager, File pluginDirectory) {
        interceptorManager.addInterceptor(this);

        IQHandler myHandler = new ApnsIQHandler();
        IQRouter iqRouter = server.getIQRouter();
        iqRouter.addHandler(myHandler);
    }

    public void destroyPlugin() {
        if (apnsService != null) {
            apnsService.stop();
        }
        interceptorManager.removeInterceptor(this);
    }

    public void interceptPacket(Packet packet, Session session, boolean read, boolean processed) throws PacketRejectedException {
        if (isValidTargetPacket(packet, read, processed)) {
            Message receivedMessage = (Message) packet;

            Message.Type messageType = receivedMessage.getType();
            if (messageType != Message.Type.chat && messageType != Message.Type.groupchat) {
                return;
            }

            String messageBody = receivedMessage.getBody();
            if (messageBody == null) {
                return;
            }

            if (!getSendMessageBody()) {
                // Don't send the original message text with push notification.
                messageBody = getMessageBodyPlaceholder();
            }

            JID fromJID = receivedMessage.getFrom();
            JID toJID = receivedMessage.getTo();
            String username = fromJID.getNode();
            String displayName = getDisplayName(fromJID);
            List<String> deviceTokens = new ArrayList<String>();
            PayloadBuilder payloadBuilder = APNS.newPayload().badge(getBadge()).sound(getSound());

            if (messageType == Message.Type.chat) {
                String senderJIDStr = fromJID.toBareJID();
                payloadBuilder.customField("jid", senderJIDStr);

                // 'username' custom field is now deprecated.
                // Remove it when no more clients depend on it.
                payloadBuilder.customField("username", username);

                String token = dbHandler.getDeviceToken(toJID);
                if (token != null) {
                    deviceTokens.add(token);
                }

                if (Strings.isNullOrEmpty(messageBody)) {
                    payloadBuilder.alertBody(displayName);
                } else {
                    payloadBuilder.alertBody(displayName + ": " + messageBody);
                }
            } else if (messageType == Message.Type.groupchat) {
                String roomJIDStr = toJID.toBareJID();
                payloadBuilder.customField("jid", roomJIDStr);

                String roomDisplayName = null;

                MUCRoom room = getRoom(toJID);
                if (room != null) {
                    roomDisplayName = room.getNaturalLanguageName();
                    deviceTokens.addAll(dbHandler.getDeviceTokensForRoom(room.getID()));

                    // 'roomname' custom field is now deprecated.
                    // Remove it when no more clients depend on it.
                    payloadBuilder.customField("roomname", room.getName());
                }

                if (Strings.isNullOrEmpty(messageBody)) {
                    payloadBuilder.alertBody(displayName + "@" + roomDisplayName);
                } else {
                    payloadBuilder.alertBody(displayName + "@" + roomDisplayName + ": " + messageBody);
                }
            }

            if (deviceTokens.isEmpty() || getApnsService() == null) {
                return;
            }

            try {
                String payloadString = payloadBuilder.shrinkBody("...").build();
                getApnsService().push(deviceTokens, payloadString);
            } catch (NetworkIOException e) {
                Log.error(e.getMessage(), e);
            }
        }
    }

    private boolean isValidTargetPacket(Packet packet, boolean read, boolean processed) {
        return !processed && read && packet instanceof Message;
    }

    private String getDisplayName(JID jid) {
        String name = null;
        try {
            name = UserNameManager.getUserName(jid);
        } catch (UserNotFoundException e) {
            Log.error("User not found: jid = " + jid, e);
        }

        return name == null ? jid.getNode() : name;
    }

    private MUCRoom getRoom(JID roomJID) {
        String roomName = roomJID.getNode();
        String serviceName = roomJID.getDomain().split("\\.")[0];

        MultiUserChatService mucService = mucManager.getMultiUserChatService(serviceName);
        if (mucService != null) {
            return mucService.getChatRoom(roomName);
        }

        return null;
    }


    // ApnsDelegate methods

    public void messageSent(final ApnsNotification message, final boolean resent) {
        String deviceToken = Utilities.encodeHex(message.getDeviceToken());
        Log.info("messageSent: Id = " + message.getIdentifier() +
                ", deviceToken = " + deviceToken + ", resent: " + resent);
    }

    public void messageSendFailed(final ApnsNotification message, final Throwable e) {
        String deviceToken = Utilities.encodeHex(message.getDeviceToken());
        Log.error("messageSendFailed: Id = " + message.getIdentifier() + ", deviceToken = " + deviceToken, e);

        if (e instanceof ApnsDeliveryErrorException) {
            ApnsDeliveryErrorException deliveryErrorException = (ApnsDeliveryErrorException) e;
            if (deliveryErrorException.getDeliveryError() == DeliveryError.INVALID_TOKEN) {
                dbHandler.deleteDeviceToken(deviceToken);
            }
        }
    }

    public void connectionClosed(final DeliveryError e, final int messageIdentifier) {
        Log.error("connectionClosed: Id = " + messageIdentifier + ", error = " + e);
    }

    public void cacheLengthExceeded(final int newCacheLength) {
        Log.info("cacheLengthExceeded: newCacheLength = " + newCacheLength);
    }

    public void notificationsResent(final int resendCount) {
        Log.info("notificationsResent: resendCount = " + resendCount);
    }

//    private void cleanInactiveDevices(ApnsService apnsService) throws NetworkIOException {
//        final Map<String, Date> inactiveDevices = apnsService.getInactiveDevices();
//        for (final Map.Entry<String, Date> ent : inactiveDevices.entrySet()) {
//            System.out.println("Inactive " + ent.getKey() + " at date " + ent.getValue());
//        }
//    }
}
