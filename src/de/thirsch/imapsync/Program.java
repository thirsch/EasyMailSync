package de.thirsch.imapsync;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

public class Program {

    private static int totalMails = 0;

    public static void main(String[] args) {
        Properties props = System.getProperties();
        props.setProperty("mail.store.protocol", "imaps");

        Session session = Session.getDefaultInstance(props, null);

        try {
            Properties config = new Properties();
            config.load(new FileInputStream("config.properties"));

            int count = Integer.parseInt(config.getProperty("numberOfAccounts", "0"));

            for (int i = 0; i < count; i++) {
                try {
                    Boolean enabled = Boolean.parseBoolean(config.getProperty("accountEnabled" + i, "true"));
                    if (!enabled) {
                        log("Skip account " + i + ", because it is disabled.");
                        continue;
                    }
                    String sourceProtocol = config.getProperty("sourceProtocol" + i, "imaps");
                    String sourceHost = config.getProperty("sourceHost" + i);
                    String sourcePort = config.getProperty("sourcePort" + i);
                    String sourceUser = config.getProperty("sourceUser" + i);
                    String sourcePassword = config.getProperty("sourcePassword" + i);

                    log("===================================================================");
                    log("  Synchronizing account " + sourceUser);
                    log("===================================================================");

                    Store sourceStore = session.getStore(sourceProtocol);

                    if (sourcePort != null) {
                        sourceStore.connect(sourceHost, Integer.parseInt(sourcePort), sourceUser, sourcePassword);
                    } else {
                        sourceStore.connect(sourceHost, sourceUser, sourcePassword);
                    }

                    log("Connected source to %s", sourceStore);

                    String targetProtocol = config.getProperty("targetProtocol" + i, "imaps");
                    String targetHost = config.getProperty("targetHost" + i);
                    String targetPort = config.getProperty("targetPort" + i);
                    String targetUser = config.getProperty("targetUser" + i);
                    String targetPassword = config.getProperty("targetPassword" + i);

                    Store targetStore = session.getStore(targetProtocol);

                    if (targetPort != null) {
                        targetStore.connect(targetHost, Integer.parseInt(targetPort), targetUser, targetPassword);
                    } else {
                        targetStore.connect(targetHost, targetUser, targetPassword);
                    }

                    log("Connected target to %s", targetStore);
                    log("===================================================================");

                    synchronizeMails(sourceStore.getDefaultFolder(), targetStore, targetStore.getDefaultFolder(), 0);

                    targetStore.close();
                    sourceStore.close();

                } catch (MessagingException e) {
                    e.printStackTrace();
                }
            }

            log("Done! %d mails synchronised...", totalMails);
            log("  Bye...");
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.exit(2);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(3);
        }
    }

    private static void synchronizeMails(Folder sourceFolder, Store targetStore, Folder targetFolder, int level) throws MessagingException {

        log("Synchronizing folder %s.", sourceFolder.getFullName());

        if ((sourceFolder.getType() & Folder.HOLDS_MESSAGES) == Folder.HOLDS_MESSAGES) {
            synchronizeMessages(sourceFolder, targetFolder);
        }

        if ((sourceFolder.getType() & Folder.HOLDS_FOLDERS) == Folder.HOLDS_FOLDERS) {
            // follow the folders.
            Folder[] sourceSubfolderList = sourceFolder.list();

            for (Folder source : sourceSubfolderList) {
                Folder targetSubfolder = targetStore.getFolder(source.getFullName().replace(source.getSeparator(), targetFolder.getSeparator()));
                if (!targetSubfolder.exists()) {
                    log("Creating folder %s in target store.", source.getName());
                    targetSubfolder.create(Folder.HOLDS_MESSAGES);
                }
                synchronizeMails(source, targetStore, targetSubfolder, level + 1);
            }
        }
    }

    private static void synchronizeMessages(Folder sourceFolder, Folder targetFolder) throws MessagingException {
        Map<String, Message> sourceMessages = getMessages(sourceFolder, Folder.READ_ONLY, true);
        Map<String, Message> targetMessages = getMessages(targetFolder, Folder.READ_WRITE, false);

        int currentMessage = 0;
        Set<String> itemsToRemove = targetMessages.keySet();
        for (String messageId : sourceMessages.keySet()) {

            log("  %.2f %d/%d: Processing message with id %s.", ++currentMessage * 100 / (float)sourceMessages.size(), currentMessage, sourceMessages.size(), messageId);

            boolean exists = false;
            for (String key : targetMessages.keySet()) {
                if (key.equalsIgnoreCase(messageId)) {
                    itemsToRemove.remove(key);
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                log("  Create message %s in target store.", messageId);
                Message message = new MimeMessage((MimeMessage)sourceMessages.get(messageId));

                if(message.getHeader("Message-ID") == null) {
                    message.setHeader("Message-ID", messageId);
                }

                targetFolder.appendMessages(new Message[] {message});
                ++totalMails;
            }
        }

        targetFolder.close(true);
        sourceFolder.close(true);
    }

    private static Map<String, Message> getMessages(Folder folder, int mode, boolean logCount) throws MessagingException {
        UIDFolder uidFolder = (UIDFolder)folder;
        folder.open(mode);

        Map<String, Message> result = new HashMap<>();

        Message[] messages = folder.getMessages();

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(UIDFolder.FetchProfileItem.UID);
        fp.add("Message-ID");
        folder.fetch(messages, fp);

        if (logCount) {
            log("Processing %d messages...", messages.length);
        }

        boolean headerFound = false;

        for (Message message : messages) {
            long uid = uidFolder.getUID(message);

            Enumeration headers = message.getAllHeaders();
            while (headers.hasMoreElements()) {
                Header nextElement = (Header)headers.nextElement();
                if ("Message-ID".equalsIgnoreCase(nextElement.getName())) {
                    result.put(nextElement.getValue(), message);
                    headerFound = true;
                    break;
                }
            }

            if(!headerFound) {
                // No header has been found. Inserting our own, to allow synchronisation.
                result.put(String.format("%d@easymailsync", uid), message);
            }
        }

        return result;
    }

    private static void log(String format, Object... arguments) {
        System.out.println(String.format(format, arguments));
    }
}
