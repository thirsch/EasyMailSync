package de.thirsch.imapsync;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

public class Program {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Properties props = System.getProperties();
		props.setProperty("mail.store.protocol", "imaps");

		try {
			Session session = Session.getDefaultInstance(props, null);

			Properties config = new Properties();
			config.load(new FileInputStream("config.properties"));

			int count = Integer.parseInt(config.getProperty("numberOfAccounts",
					"0"));

			for (int i = 0; i < count; i++) {
				log("===================================================================");
				log("Synchronise account " + i);
				log("===================================================================");

				String sourceProtocol = config.getProperty(
						"sourceProtocol" + i, "imaps");
				String sourceHost = config.getProperty("sourceHost" + i);
				String sourcePort = config.getProperty("sourcePort" + i);
				String sourceUser = config.getProperty("sourceUser" + i);
				String sourcePassword = config
						.getProperty("sourcePassword" + i);

				Store sourceStore = session.getStore(sourceProtocol);

				if (sourcePort != null) {
					sourceStore.connect(sourceHost,
							Integer.parseInt(sourcePort), sourceUser,
							sourcePassword);
				} else {
					sourceStore.connect(sourceHost, sourceUser, sourcePassword);
				}

				log("Connected source to %s", sourceStore);

				String targetProtocol = config.getProperty(
						"targetProtocol" + i, "imaps");
				String targetHost = config.getProperty("targetHost" + i);
				String targetPort = config.getProperty("targetPort" + i);
				String targetUser = config.getProperty("targetUser" + i);
				String targetPassword = config
						.getProperty("targetPassword" + i);

				Store targetStore = session.getStore(targetProtocol);

				if (targetPort != null) {
					targetStore.connect(targetHost,
							Integer.parseInt(targetPort), targetUser,
							targetPassword);
				} else {
					targetStore.connect(targetHost, targetUser, targetPassword);
				}

				log("Connected target to %s", targetStore);
				log("===================================================================");

				synchronizeMails(sourceStore, sourceStore.getDefaultFolder(),
						targetStore, targetStore.getDefaultFolder(), 0);

				targetStore.close();
				sourceStore.close();
			}

		} catch (MessagingException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(2);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(3);
		}
	}

	private static void synchronizeMails(Store sourceStore,
			Folder sourceFolder, Store targetStore, Folder targetFolder,
			int level) throws MessagingException {

		log("Synchronising folder %s.", sourceFolder.getFullName());

		if ((sourceFolder.getType() & Folder.HOLDS_MESSAGES) == Folder.HOLDS_MESSAGES) {
			synchroniseMessages(sourceFolder, targetFolder, true);
		}

		if ((sourceFolder.getType() & Folder.HOLDS_FOLDERS) == Folder.HOLDS_FOLDERS) {
			log("Synchronising subfolders of %s.", sourceFolder.getName());
			// follow the folders.
			Folder[] sourceSubfolderList = sourceFolder.list();

			for (Folder source : sourceSubfolderList) {
				Folder targetSubfolder = targetStore.getFolder(source
						.getFullName().replace(source.getSeparator(),
								targetFolder.getSeparator()));
				if (!targetSubfolder.exists()) {
					log("Create folder %s in target store.", source.getName());
					targetSubfolder.create(Folder.HOLDS_MESSAGES);
				}
				synchronizeMails(sourceStore, source, targetStore,
						targetSubfolder, level + 1);
			}
		}
	}

	private static void synchroniseMessages(Folder sourceFolder,
			Folder targetFolder, boolean replicateOnly)
			throws MessagingException {
		log("Synchronising messages in folder %s.", sourceFolder.getName());

		sourceFolder.open(Folder.READ_ONLY);
		targetFolder.open(Folder.READ_WRITE);

		Map<String, Message> sourceMessages = getMessages(sourceFolder);
		Map<String, Message> targetMessages = getMessages(targetFolder);

		Set<String> itemsToRemove = targetMessages.keySet();
		for (String messageId : sourceMessages.keySet()) {

			boolean exists = false;
			for (String key : targetMessages.keySet()) {
				if (key.equalsIgnoreCase(messageId)) {
					itemsToRemove.remove(key);
					exists = true;
					break;
				}
			}

			if (!exists) {
				log("Create message %s in target store.", messageId);
				Message message = sourceMessages.get(messageId);

				targetFolder.appendMessages(new Message[] { message });
			}
		}

		if (!replicateOnly) {
			for (String key : itemsToRemove) {
				log("Remove message %s", key);

				Message message = targetMessages.get(key);
				message.setFlag(Flags.Flag.DELETED, true);
			}
		}

		targetFolder.close(true);
		sourceFolder.close(true);
	}

	private static Map<String, Message> getMessages(Folder sourceFolder)
			throws MessagingException {
		Map<String, Message> result = new HashMap<String, Message>();

		Message[] messages = sourceFolder.getMessages();

		FetchProfile fp = new FetchProfile();
		fp.add(FetchProfile.Item.ENVELOPE);
		fp.add("Message-ID");
		sourceFolder.fetch(messages, fp);

		for (Message message : messages) {
			String msgId = message.getHeader("Message-ID")[0];
			result.put(msgId, message);
		}

		return result;
	}

	private static void log(String format, Object... arguments) {
		System.out.println(String.format(format, arguments));
	}
}
