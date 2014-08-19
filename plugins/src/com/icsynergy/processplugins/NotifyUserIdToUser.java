package com.icsynergy.processplugins;

import static oracle.iam.identity.usermgmt.api.UserManagerConstants.AttributeName.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import oracle.iam.configservice.exception.AccessDeniedException;
import oracle.iam.identity.exception.NoSuchUserException;
import oracle.iam.identity.exception.UserLookupException;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.notification.api.NotificationService;
import oracle.iam.notification.vo.NotificationEvent;
import oracle.iam.platform.Platform;
import oracle.iam.platform.kernel.spi.PostProcessHandler;
import oracle.iam.platform.kernel.vo.AbstractGenericOrchestration;
import oracle.iam.platform.kernel.vo.BulkEventResult;
import oracle.iam.platform.kernel.vo.BulkOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;

public class NotifyUserIdToUser implements PostProcessHandler {
	private static final String TAG = "NotifyUserIdToUser";

	@Override
	public void initialize(HashMap<String, String> arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public boolean cancel(long arg0, long arg1,
			AbstractGenericOrchestration arg2) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void compensate(long arg0, long arg1,
			AbstractGenericOrchestration arg2) {
		// TODO Auto-generated method stub

	}

	@Override
	public EventResult execute(long arg0, long arg1, Orchestration orch) {
		String usrLogin, usrKey;

		System.out.println(TAG + ": execute");
		String operation = orch.getOperation();
		if (!operation.equalsIgnoreCase("CREATE")) {
			System.out.println(TAG + ":-- not the create operation exiting");
			return new EventResult();
		}
		try {
			HashMap<String, Serializable> params = orch.getParameters();
			System.out.println("params -> " + params);

			if (params.containsKey("Password Generated")
					&& params.get("Password Generated").toString()
							.equalsIgnoreCase("1")) {
				System.out.println("Generating event to notify user, this is an admin created user!");
				// System.out.println("keySet -> " + keySet);

				// System.out.println("key ->" + key);
				Serializable serializable = params.get("User Login");
				usrLogin = serializable.toString();
				UserManager usrMgr = Platform.getService(UserManager.class);
				User user = usrMgr.getDetails(usrLogin, null, true);
				usrKey = user.getEntityId();
				String uid = user.getId();
				System.out.println("uid-->" + uid);
				System.out.println("userKey ->" + usrKey);
				String templateName = "Notify UserId To User"; // Notify UserId
																// To User

				NotificationService notService = Platform
						.getService(NotificationService.class);
				NotificationEvent eventToSend = this.createNotificationEvent(
						templateName, usrKey);
				System.out.println("event: " + eventToSend);
				notService.notify(eventToSend);
			}
		} catch (Exception e) {
			System.out.println(TAG + ": execute - ERROR " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println(TAG + "Exiting EventResult");
		return new EventResult();
	}

	@Override
	public BulkEventResult execute(long arg0, long arg1,
			BulkOrchestration bulkOrch) {
		// try {
		// System.out
		// .println("Entering bulkeventresult of notifyuseridtouser");
		// String operation = bulkOrch.getOperation();
		// System.out.println("operation ->" + operation);
		// HashMap<String, Serializable>[] bulkParams = bulkOrch
		// .getBulkParameters();
		// for (HashMap<String, Serializable> bulkParam : bulkParams) {
		// System.out.println("bulkParam ->" + bulkParam);
		// Set<String> bulkKeySet = bulkParam.keySet();
		// System.out.println("bulkKeySet ->" + bulkKeySet);
		// String usrLogin = null;
		// String usrKey = null;
		// for (String key : bulkKeySet) {
		// System.out.println("key ->" + key);
		// Serializable serializable = bulkParam.get(key);
		// System.out.println("serializable ->" + serializable);
		// if (key.equalsIgnoreCase("User Login")) {
		// usrLogin = serializable.toString();
		// UserManager usrMgr = Platform
		// .getService(UserManager.class);
		// User user = usrMgr.getDetails(usrLogin, null, true);
		// usrKey = user.getEntityId();
		// String uid = user.getId();
		// System.out.println("uid-->" + uid);
		// System.out.println("userKey ->" + usrKey);
		// String templateName =
		// "Notify UserId To User";//"Notify UserId to User";
		//
		// NotificationService notService = Platform
		// .getService(NotificationService.class);
		// NotificationEvent eventToSend = this
		// .createNotificationEvent(templateName, usrKey);
		// notService.notify(eventToSend);
		// }
		// }
		// }
		// } catch (Exception e) {
		// System.out.println("Exception e in bulkevent execute:"
		// + e.getMessage());
		// e.printStackTrace();
		// }
		// System.out.println("Exiting BulkEventResult of NotifyUserIdToUser");
		return new BulkEventResult();
	}

	private NotificationEvent createNotificationEvent(String poTemplateName,
			String userKey) {
		NotificationEvent event = null;
		try {
			event = new NotificationEvent();
			String[] receiverUserIds = getRecipientUserIds(userKey);
			event.setUserIds(receiverUserIds);
			event.setTemplateName("Notify UserId To User");
			event.setSender(null);
			HashMap<String, Object> templateParams = new HashMap<String, Object>();
			templateParams.put("usr_key", userKey);
			event.setParams(templateParams);
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("e-------->" + e.getMessage());
		}
		return event;
	}

	private String[] getRecipientUserIds(String userKey)
			throws NoSuchUserException, UserLookupException,
			AccessDeniedException {
		UserManager usrMgr = Platform.getService(UserManager.class);
		User user = null;
		String userId = null;
		Set<String> userRetAttrs = new HashSet<String>();
		// userRetAttrs.add(MANAGER_KEY.getId());
		userRetAttrs.add(USER_LOGIN.getId());
		// User manager = null;
		// String managerId = null;
		// String managerKey = null;
		// Set<String> managerRetAttrs = new HashSet<String>();
		// managerRetAttrs.add(USER_LOGIN.getId());
		user = usrMgr.getDetails(userKey, userRetAttrs, false);
		userId = user.getAttribute(USER_LOGIN.getId()).toString();
		List<String> userIds = new ArrayList<String>();
		userIds.add(userId);
		// if (user.getAttribute(MANAGER_KEY.getId()) != null) {
		// managerKey = user.getAttribute(MANAGER_KEY.getId()).toString();
		// manager = usrMgr.getDetails(managerKey, managerRetAttrs, false);
		// managerId = manager.getAttribute(USER_LOGIN.getId()).toString();
		// userIds.add(managerId);
		// }
		String[] recipientIDs = userIds.toArray(new String[0]);
		return recipientIDs;
	}

}
