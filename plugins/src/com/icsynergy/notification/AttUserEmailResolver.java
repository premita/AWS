package com.icsynergy.notification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import oracle.iam.identity.orgmgmt.api.OrganizationManager;
import oracle.iam.identity.orgmgmt.vo.Organization;
import oracle.iam.identity.usermgmt.api.UserManager;
import oracle.iam.identity.usermgmt.vo.User;
import oracle.iam.notification.impl.NotificationEventResolver;
import oracle.iam.notification.vo.NotificationAttribute;
import oracle.iam.platform.Platform;

public class AttUserEmailResolver implements NotificationEventResolver {
	private static final String TAG = "AttUserEmailResolver";

	/* eventType is the name of the template.. */

	@Override
	public List<NotificationAttribute> getAvailableData(String eventType,
			Map<String, Object> params) throws Exception {
		System.out.println(TAG + ": getAvailableData");
		List<NotificationAttribute> attrs = new ArrayList<NotificationAttribute>();
		NotificationAttribute fname = new NotificationAttribute();
		NotificationAttribute lname = new NotificationAttribute();
		NotificationAttribute userlogin = new NotificationAttribute();
		NotificationAttribute orgname = new NotificationAttribute();

		fname.setName("firstName");
		lname.setName("lastName");
		userlogin.setName("userLogin");
		orgname.setName("organization");

		attrs.add(fname);
		attrs.add(lname);
		attrs.add(userlogin);
		attrs.add(orgname);

		return attrs;
	}

	private HashMap<String, Object> getGeneratePasswordResolver(
			Map<String, Object> params) {
		HashMap<String, Object> retval = new HashMap<String, Object>();
		String firstName = params.get("firstName").toString();
		String lastName = params.get("lastName").toString();
		String password = params.get("password").toString();
		String userLogin = params.get("userLoginId").toString();
		String userKey = params.get("user").toString();

		try {
			UserManager usrMgr = Platform.getService(UserManager.class);
			User user = usrMgr.getDetails(userKey, null, false);

			String org_key = user.getOrganizationKey();
			OrganizationManager orgMgr = Platform
					.getService(OrganizationManager.class);
			Organization org = orgMgr.getDetails(org_key, null, false);
			String orgName = (String) org.getAttribute("Organization Name");
			retval.put("organization", orgName);
		} catch (Exception e) {
			System.out.println(e.getMessage());
			e.printStackTrace();

		}
		retval.put("firstName", firstName);
		retval.put("lastName", lastName);
		retval.put("userLogin", userLogin);
		retval.put("password", password);
		return retval;
	}

	/*
	 * eventType : event Name for which the template is to be read params: map
	 * of base values such as usr_key required to resolve the variables in the
	 * template.
	 */
	@Override
	public HashMap<String, Object> getReplacedData(String eventType,
			Map<String, Object> params) throws Exception {
		System.out.println(TAG + ": getReplacedData");
		System.out.println(TAG + ": event -> " + eventType);
		System.out.println(TAG + ": params ->" + params);

		HashMap<String, Object> resolvedData = null;
		if (eventType.equalsIgnoreCase("GeneratePassword")) {
			resolvedData = getGeneratePasswordResolver(params);
		} 
		else if ( eventType.equalsIgnoreCase("CreateSelfUser")) {
			resolvedData = getSelfUserResolver(params);
		}
		else if ( eventType.equalsIgnoreCase("ForgottenUsername") ||
							eventType.equalsIgnoreCase("PasswordWarning") ||
							eventType.equalsIgnoreCase("PasswordExpiration")
						) {
			resolvedData = getForgottenUserResolver(params);
		} 
		else //if (eventType.equalsIgnoreCase("NotifyUserIdToUser") || eventType.equalsIgnoreCase("NotifyUserPasswordChanged")) {
		{
			resolvedData = new HashMap<String, Object>();
			UserManager usrMgr = Platform.getService(UserManager.class);
			User user = usrMgr.getDetails(params.get("usr_key").toString(),
					null, false);

			String firstName = user.getFirstName();
		//	System.out.println(TAG + ": firstName -> " + firstName);
			String lastName = user.getLastName();
		//	System.out.println(TAG + ": lastName -> " + lastName);
			String userLogin = user.getLogin();
		//	System.out.println(TAG + ": userLogin -> " + userLogin);

			String org_key = user.getOrganizationKey();
			// get all of the organization's data
			// see org attributes below
			OrganizationManager orgMgr = Platform
					.getService(OrganizationManager.class);
			Organization org = orgMgr.getDetails(org_key, null, false);

			String orgName = (String) org.getAttribute("Organization Name");
		//	System.out.println(TAG + ": organization -> " + orgName);

			resolvedData.put("firstName", firstName);
			resolvedData.put("lastName", lastName);
			resolvedData.put("userLogin", userLogin);
			resolvedData.put("organization", orgName);
		}
		System.out.println(TAG + ": exiting getReplacedData");
		return resolvedData;
	}

	private HashMap<String, Object> getForgottenUserResolver(
			Map<String, Object> params) {
		HashMap<String, Object> retval = new HashMap<String, Object>();
		String loginId = params.get("userLoginId").toString().replace("[", "").replace("]","");
		System.out.println("login id: " + loginId);
		
		try {
		UserManager usrMgr = Platform.getService(UserManager.class);
		User user = usrMgr.getDetails(loginId, null, true);

		String firstName = user.getFirstName();
		//	System.out.println(TAG + ": firstName -> " + firstName);
			String lastName = user.getLastName();
		//	System.out.println(TAG + ": lastName -> " + lastName);
			String userLogin = user.getLogin();
		//	System.out.println(TAG + ": userLogin -> " + userLogin);

			String org_key = user.getOrganizationKey();
			// get all of the organization's data
			// see org attributes below
			OrganizationManager orgMgr = Platform
					.getService(OrganizationManager.class);
			Organization org = orgMgr.getDetails(org_key, null, false);

			String orgName = (String) org.getAttribute("Organization Name");
		//	System.out.println(TAG + ": organization -> " + orgName);

			retval.put("firstName", firstName);
			retval.put("lastName", lastName);
			retval.put("userLogin", userLogin);
			retval.put("organization", orgName);
			
	} catch (Exception e) {
		System.out.println(e.getMessage());
		e.printStackTrace();

	}
		
		return retval;
	}

	private HashMap<String, Object> getSelfUserResolver(
			Map<String, Object> params) {
		HashMap<String, Object> retval = new HashMap<String, Object>();
		
		retval.put("firstName", params.get("firstName"));
		retval.put("lastName", params.get("lastName"));
		retval.put("userLogin", params.get("userLoginId"));
		retval.put("password", params.get("password"));
		return retval;
	}

	/*
	 * org attributes ORG Certifier User Key -> null ORG Certifier User Login ->
	 * null ORG Organization Status -> Active ORG Parent Organization Name ->
	 * Top ORG Organization Name -> Home Users ORG act_updateby -> 1 ORG act_key
	 * -> 4 ORG Password Policy -> 1 ORG Organization Customer Type -> Company
	 * ORG Password Policy Name -> Default Policy ORG grp_id -> 01525 ORG
	 * parent_key -> 3 ORG User Membership Rule -> null ORG act_createby -> 1
	 * ORG act_create -> Wed May 07 11:51:45 CDT 2014 ORG act_data_level -> null
	 * ORG act_update -> Wed May 07 12:10:57 CDT 2014 ORG pin -> HM123 ORG
	 * act_disabled -> null
	 */

}
