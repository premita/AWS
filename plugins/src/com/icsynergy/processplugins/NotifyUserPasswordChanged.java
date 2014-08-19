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
import oracle.iam.platform.kernel.vo.EntityOrchestration;
import oracle.iam.platform.kernel.vo.EventResult;
import oracle.iam.platform.kernel.vo.Orchestration;

public class NotifyUserPasswordChanged implements PostProcessHandler {
	private static final String TAG = "NotifyUserPasswordChanged";

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
		String usrKey;

		System.out.println(TAG + ": execute");
		String operation = orch.getOperation();
		if (!operation.equalsIgnoreCase("CHANGE_PASSWORD")) {
			System.out.println(TAG + ":-- " + operation + " :-- not the modify operation exiting");
			return new EventResult();
		}
		/*
		 params -> {usr_pwd_expire_date=Wed Sep 17 09:42:00 CDT 2014, 
		 confirmPassword=oracle.iam.platform.context.ContextAwareString@3ef82643, 
		 usr_pwd_warned=0, 
		 usr_pwd_warn_date=Wed Sep 10 09:42:00 CDT 2014, 
		 usr_password=oracle.iam.platform.context.ContextAwareString@1aded04a, 
		 oldPassword=oracle.iam.platform.context.ContextAwareString@3403f1c8, 
		 usr_pwd_expired=0, usr_change_pwd_at_next_logon=0}
*/
		try {
			HashMap<String, Serializable> params = orch.getParameters();
			System.out.println("params -> " + params);
			EntityOrchestration target = (EntityOrchestration) orch.getTarget();
			System.out.println("target -> " + target);
			System.out.println(" entityid -> " + target.getEntityId());
			System.out.println("context val ->" + orch.getContextVal());
			Set<String> keySet = params.keySet();
			// System.out.println("keySet -> " + keySet);
			if (keySet.contains("usr_password")) {
				 usrKey = target.getEntityId();

				NotificationService notService = Platform
						.getService(NotificationService.class);
				NotificationEvent eventToSend = this.createNotificationEvent(
						"NotifyUserPasswordChanged", usrKey);
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
		return new BulkEventResult();
	}

	private NotificationEvent createNotificationEvent(String poTemplateName,
			String userKey) {
		NotificationEvent event = null;
		try {
			event = new NotificationEvent();
			String[] receiverUserIds = getRecipientUserIds(userKey);
			event.setUserIds(receiverUserIds);
			event.setTemplateName(poTemplateName);
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
